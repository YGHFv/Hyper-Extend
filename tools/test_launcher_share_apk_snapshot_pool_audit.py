#!/usr/bin/env python3
"""分享 APK 快照对象池顺序解码器的纯合成回归。"""

import copy
import io
import json
import struct
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import launcher_share_apk_snapshot_audit as audit  # noqa: E402
from launcher_native_audit import AuditError, digest  # noqa: E402


def unsigned(value):
    result = bytearray()
    while value > 127:
        result.append(value & 127)
        value >>= 7
    result.append(value + 128)
    return bytes(result)


def fixed(value):
    result = bytearray()
    while value < -64 or value > 63:
        result.append(value & 127)
        value >>= 7
    result.append(value + 192)
    return bytes(result)


def ref(value):
    groups = [value & 127]
    value >>= 7
    while value:
        groups.append(value & 127)
        value >>= 7
    groups.reverse()
    groups[-1] += 128
    return bytes(groups)


class FakeImage:
    """保留完整字节身份，同时把动态符号来源固定成合成 `.dynsym` 结果。"""

    def __init__(self, data, build_id="synthetic-build-id"):
        self.data = data
        self._build_id = build_id

    def dynamic_symbols(self):
        return [
            {"name": "_kDartVmSnapshotData", "info": 17, "other": 0,
             "section": 1, "value": 0x340, "size": 18256},
            {"name": "_kDartIsolateSnapshotData", "info": 17, "other": 0,
             "section": 1, "value": 0x4AC0, "size": 8681712},
        ]

    def file_offset(self, address, size, executable=False):
        if not 0 <= address <= len(self.data) - size:
            raise AuditError("合成映射越界")
        return address

    def read(self, offset, size):
        if not 0 <= offset <= len(self.data) - size:
            raise AuditError("合成文件区间越界")
        return self.data[offset:offset + size]

    def build_id(self):
        return self._build_id


def make_fixture(*, version=audit.HOST_VERSION, features=audit.FEATURES,
                 header_clusters=1465, bad_ref=False, fill_pool_length=None,
                 bad_entry_type=False, bad_behavior=False, fill_mismatch=False,
                 slot_not_tagged=False):
    canonical_count = audit.EXPECTED_CANONICAL_STRINGS
    instance_count = 158517
    empty_instance_clusters = 1461
    extra_values = [item[3] for item in audit.SLOT_SPECS] + [""] * 18
    noncanonical_start = 277570
    slot_ref_ids = [noncanonical_start + index for index in range(4)]

    stream = bytearray()
    for value in audit.EXPECTED_HEADER:
        stream += unsigned(header_clusters if value == 1465 else value)

    # ReadAlloc：一个 canonical String、大量零字段 Instance、唯一池、非 canonical String。
    stream += fixed((93 << 12) | 2) + unsigned(canonical_count)
    stream += unsigned(0) * canonical_count
    stream += unsigned(1) + unsigned(canonical_count)
    stream += fixed(10000 << 12) + unsigned(instance_count) + fixed(2) + fixed(2)
    for cid in range(10001, 10001 + empty_instance_clusters):
        stream += fixed(cid << 12) + unsigned(0) + fixed(2) + fixed(2)
    stream += fixed(23 << 12) + unsigned(1) + unsigned(audit.EXPECTED_POOL_LENGTH)
    stream += fixed(93 << 12) + unsigned(len(extra_values))
    for value in extra_values:
        raw = value.encode("latin-1")
        stream += unsigned(len(raw) << 1)

    # ReadFill：空 canonical 字符串、每个 Instance 簇的 bitmap、对象池、尾部字符串。
    stream += unsigned(2 if fill_mismatch else 0)
    stream += unsigned(0) * (canonical_count - 1)
    stream += unsigned(0) * (1 + empty_instance_clusters)
    pool_length = audit.EXPECTED_POOL_LENGTH if fill_pool_length is None else fill_pool_length
    stream += unsigned(pool_length)
    slot_indexes = {item[2]: index for index, item in enumerate(audit.SLOT_SPECS)}
    for index in range(audit.EXPECTED_POOL_LENGTH):
        if index < audit.EXPECTED_ENTRY_COUNTS["obj"]:
            bits = 1
            value = 1
            if index == 0 and bad_ref:
                value = audit.EXPECTED_HEADER[1] + 1
            if index == 0 and bad_entry_type:
                bits = 3
            if index == 1 and bad_behavior:
                bits = (1 << 5) | 1
            if index in slot_indexes:
                value = slot_ref_ids[slot_indexes[index]]
                if slot_not_tagged and index == audit.SLOT_SPECS[0][2]:
                    bits = 0
            stream.append(bits)
            if bits & 15 == 0:
                stream += fixed(0)
            elif bits >> 5 == 0 and bits & 15 == 1:
                stream += ref(value)
            elif bits & 15 not in (2, 3):
                stream += ref(value)
        elif index < audit.EXPECTED_ENTRY_COUNTS["obj"] + audit.EXPECTED_ENTRY_COUNTS["imm"]:
            stream.append(0)
            stream += fixed(0)
        elif index < (audit.EXPECTED_ENTRY_COUNTS["obj"] + audit.EXPECTED_ENTRY_COUNTS["imm"]
                      + audit.EXPECTED_ENTRY_COUNTS["skip"]):
            stream.append(4 << 5)
        else:
            stream.append(2)
    for value in extra_values:
        raw = value.encode("latin-1")
        stream += unsigned(len(raw) << 1) + raw

    prefix = bytearray(20)
    prefix[0:4] = struct.pack("<I", audit.MAGIC)
    body = prefix + version.encode("ascii") + features.encode("utf-8") + b"\0" + stream
    body += bytes(audit.EXPECTED_SNAPSHOT_LENGTH + 4 - len(body))
    struct.pack_into("<q", body, 4, len(body) - 4)
    struct.pack_into("<q", body, 12, audit.KIND_FULL_AOT)
    total_size = 0x4AC0 + audit.EXPECTED_SYMBOLS["_kDartIsolateSnapshotData"][1]
    data = bytes(0x4AC0) + bytes(body)
    data += bytes(total_size - len(data))
    image = FakeImage(data)

    contract = json.loads(audit.DEFAULT_CONTRACT.read_text(encoding="utf-8"))
    contract["image"] = {"sha256": digest(data), "buildId": image.build_id(), "size": len(data)}
    contract["snapshot"]["length"] = len(body) - 4
    for item, ref_id in zip(contract["objectPool"]["slots"], slot_ref_ids):
        item["refId"] = ref_id
    return image, contract


class SnapshotPoolAuditTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.image, cls.contract = make_fixture()

    def test_synthetic_snapshot_resolves_four_slots_and_counts(self):
        result = audit.verify_snapshot_pool(self.image, self.contract)
        self.assertEqual([item[3] for item in audit.SLOT_SPECS],
                         [item["string"] for item in result["对象池"]["四槽"]])
        self.assertEqual(audit.EXPECTED_ENTRY_COUNTS, result["对象池"]["条目分类计数"])
        self.assertEqual("117870/117870",
                         result["自洽证据"]["canonical 字符串 alloc-fill 一致计数"])
        self.assertFalse(result["安装许可"])
        self.assertEqual([1, 3, 5], result["未关闭门槛"])

    def assert_fixture_rejected(self, reason, **kwargs):
        image, contract = make_fixture(**kwargs)
        with self.assertRaisesRegex(AuditError, reason):
            audit.verify_snapshot_pool(image, contract)

    def test_changed_version_is_rejected_by_first_gate(self):
        self.assert_fixture_rejected("版本串", version="0" + audit.HOST_VERSION[1:])

    def test_changed_features_are_rejected(self):
        changed = audit.FEATURES.replace("product", "producx", 1)
        self.assert_fixture_rejected("特征串", features=changed)

    def test_changed_cluster_count_is_rejected(self):
        self.assert_fixture_rejected("Deserialize 头计数", header_clusters=1464)

    def test_out_of_range_reference_is_rejected(self):
        self.assert_fixture_rejected("填充引用号越界", bad_ref=True)

    def test_pool_fill_length_mismatch_is_rejected(self):
        self.assert_fixture_rejected("ObjectPool alloc-fill length", fill_pool_length=123595)

    def test_entry_type_and_snapshot_behavior_are_rejected(self):
        self.assert_fixture_rejected("TypeBits 越界", bad_entry_type=True)
        self.assert_fixture_rejected("SnapshotBehavior 非法", bad_behavior=True)

    def test_string_alloc_fill_mismatch_is_rejected(self):
        self.assert_fixture_rejected("String alloc-fill 编码长度不一致", fill_mismatch=True)

    def test_slot_offset_drift_is_rejected(self):
        contract = copy.deepcopy(self.contract)
        contract["objectPool"]["slots"][0]["offset"] = "0xb9460"
        with self.assertRaisesRegex(AuditError, "槽偏移漂移"):
            audit.verify_snapshot_pool(self.image, contract)

    def test_slot_must_be_tagged_object(self):
        self.assert_fixture_rejected("entry 类型非 kTaggedObject", slot_not_tagged=True)


class SnapshotPoolCliTest(unittest.TestCase):
    def test_synthetic_cli_prints_readonly_boundary(self):
        image, contract = make_fixture()
        with tempfile.TemporaryDirectory() as folder:
            host = Path(folder) / "fake.so"
            contract_path = Path(folder) / "contract.json"
            host.write_bytes(image.data)
            contract_path.write_text(json.dumps(contract, ensure_ascii=False), encoding="utf-8")
            with patch.object(audit, "ElfImage", return_value=image), \
                    patch("sys.stdout", new_callable=io.StringIO) as output:
                status = audit.main(["--host-elf", str(host), "--contract", str(contract_path)])
        self.assertEqual(0, status)
        result = json.loads(output.getvalue())
        self.assertFalse(result["安装许可"])
        self.assertIn("宿主自洽证据", result["版本门禁"]["结论"])

    def test_malformed_contract_fails_closed(self):
        with tempfile.TemporaryDirectory() as folder:
            contract = Path(folder) / "bad.json"
            contract.write_text("{", encoding="utf-8")
            with patch("sys.stdout", new_callable=io.StringIO):
                status = audit.main(["--host-elf", "unused.so", "--contract", str(contract)])
        self.assertEqual(1, status)

    def test_missing_host_fails_without_leaking_local_path(self):
        with tempfile.TemporaryDirectory() as folder:
            missing = str(Path(folder) / "missing.so")
            with patch("sys.stdout", new_callable=io.StringIO) as output:
                status = audit.main(["--host-elf", missing])
            self.assertEqual(1, status)
            self.assertIn("FileNotFoundError", output.getvalue())
            self.assertNotIn(folder, output.getvalue())


if __name__ == "__main__":
    unittest.main()
