"""用页对齐 ELF/APK/映射夹具覆盖回放、独立基址推导与文件页别名拒绝。"""

import copy
import io
import json
import struct
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path
from unittest.mock import patch

import launcher_gate5_snapshot_audit as replay
from launcher_gate5_residual_audit import (
    POLICY, parse_maps, verify_apk_entry, verify_mapping_evidence,
    verify_page_properties,
)
from launcher_native_audit import AuditError, ElfImage, digest


PAGE = 0x1000
BASE = 0x10000000
APK_OFFSET = PAGE
HOST_PATH = "/data/app/demo.host-sample/base.apk"
ENTRY = "lib/arm64-v8a/libapp.so"


def fixture():
    """虚拟地址故意不等于文件偏移，防止重新引入旧等式的自证问题。"""
    data = bytearray(0x5000)
    data[:16] = b"\x7fELF\x02\x01\x01" + bytes(9)
    segments = [(1, 4, 0, 0, 0, 0x1000, 0x1000, PAGE),
                (1, 5, 0x2000, 0x4000, 0, 0x1800, 0x1800, PAGE),
                (1, 6, 0x4000, 0x6000, 0, 0x100, 0x100, PAGE),
                (4, 4, 0x240, 0x240, 0, 20, 20, 4),
                (2, 6, 0x4000, 0x6000, 0, 48, 48, 8)]
    struct.pack_into("<HHIQQQIHHHHHH", data, 16, 3, 183, 1, 0, 64, 0x800, 0,
                     64, 56, len(segments), 64, 2, 1)
    for index, segment in enumerate(segments):
        struct.pack_into("<IIQQQQQQ", data, 64 + index * 56, *segment)
    data[0x240:0x254] = struct.pack("<III", 4, 4, 3) + b"GNU\0" + b"\x12\x34\x56\x78"
    names = b"\0.shstrtab\0"
    data[0x500:0x500 + len(names)] = names
    struct.pack_into("<IIQQQQIIQQ", data, 0x840, 1, 3, 0, 0, 0x500, len(names), 0, 0, 1, 0)
    struct.pack_into("<I", data, 0x2100, 0x94000040)
    struct.pack_into("<QQQQQQ", data, 0x4000, 5, 0x100, 6, 0x200, 0, 0)
    return bytes(data)


def contract_for(image):
    return {"schemaVersion": 1, "policy": POLICY, "installPermitted": False,
            "notClosedGates": ["门槛3：未关闭", "门槛5：未关闭"],
            "image": {"size": len(image.data), "sha256": digest(image.data),
                      "buildId": image.build_id()},
            "mapsRequirements": {"pageSize": PAGE, "packagePathSuffix": "demo.host"},
            "sites": [{"feature": "合成调用点", "offset": "0x4100", "word": "0x94000040",
                       "target": "0x4200"}], "counts": {"sites": 1}}


def apk_for(data, aligned=True, compressed=False, duplicate=False, name=ENTRY):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w") as archive:
        entry = zipfile.ZipInfo(name)
        if aligned:
            pad = PAGE - (30 + len(name))
            entry.extra = struct.pack("<HH", 0xFFFF, pad - 4) + bytes(pad - 4)
        entry.compress_type = zipfile.ZIP_DEFLATED if compressed else zipfile.ZIP_STORED
        archive.writestr(entry, data)
        if duplicate:
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                archive.writestr(entry, data)
    return stream.getvalue()


def regions_for(base=BASE):
    rows = []
    for start, end, perms, offset in ((0, PAGE, "r--p", APK_OFFSET),
                                      (0x4000, 0x5000, "r-xp", APK_OFFSET + 0x2000),
                                      (0x5000, 0x6000, "r-xp", APK_OFFSET + 0x3000),
                                      (0x6000, 0x7000, "rw-p", APK_OFFSET + 0x4000)):
        rows.append("%x-%x %s %x fe:34 77 %s" % (base + start, base + end, perms, offset, HOST_PATH))
    return parse_maps("\n".join(rows))


def maps_bytes(regions):
    return ("\n".join("%x-%x %s %x %s %s %s" % (
        row["start"], row["end"], row["perms"], row["offset"], row["dev"], row["inode"], row["path"])
        for row in regions) + "\n").encode("utf-8")


class MappingEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.image = ElfImage(fixture())
        self.contract = contract_for(self.image)
        self.regions = regions_for()

    def verify(self, regions=None, base=None):
        return verify_mapping_evidence(self.image, self.contract,
                                       self.regions if regions is None else regions, APK_OFFSET, base)

    def test_split_loads_and_distinct_virtual_offsets_pass(self):
        result = self.verify()
        self.assertEqual(hex(BASE), result["加载基址"])
        self.assertEqual(3, result["残余项(b) 宿主映像映射生命周期"]["已核对文件 PT_LOAD"])
        self.assertEqual(hex(APK_OFFSET), result["残余项(b) 宿主映像映射生命周期"]["APK 内 ELF 起始偏移"])

    def test_aslr_is_derived_per_snapshot(self):
        self.assertEqual(hex(BASE * 2), self.verify(regions_for(BASE * 2))["加载基址"])

    def test_wrong_explicit_base_is_rejected(self):
        with self.assertRaisesRegex(AuditError, "APK/ELF"):
            self.verify(base=BASE + PAGE)

    def test_all_load_pages_are_required(self):
        for index in (0, 2, 3):
            with self.subTest(index=index), self.assertRaisesRegex(AuditError, "唯一推导"):
                self.verify(self.regions[:index] + self.regions[index + 1:])

    def test_shifted_file_offset_is_rejected(self):
        self.regions[1]["offset"] += PAGE
        with self.assertRaisesRegex(AuditError, "APK/ELF"):
            self.verify(base=BASE)

    def test_ambiguous_load_instances_are_rejected(self):
        with self.assertRaisesRegex(AuditError, "候选数=2"):
            self.verify(self.regions + regions_for(BASE * 2))

    def test_wrong_package_deleted_and_non_apk_paths_are_rejected(self):
        for path in (HOST_PATH.replace("demo.host-", "demo.host.spoof-"),
                     HOST_PATH + " (deleted)", HOST_PATH.replace("base.apk", "split.apk"),
                     "/tmp/demo.host/base.apk"):
            changed = copy.deepcopy(self.regions)
            for row in changed:
                row["path"] = path
            with self.subTest(path=path), self.assertRaisesRegex(AuditError, "宿主包"):
                self.verify(changed, BASE)

    def test_load_identity_includes_device_inode_and_path(self):
        for key, value in (("dev", "fe:35"), ("inode", "78"),
                           ("path", "/data/app/demo.host-other/base.apk")):
            changed = copy.deepcopy(self.regions)
            changed[0][key] = value
            with self.subTest(key=key), self.assertRaisesRegex(AuditError, "同一个宿主"):
                self.verify(changed, BASE)

    def test_remote_aliases_with_any_permissions_are_rejected(self):
        for perms, reason in (("rw-p", "可写别名"), ("r--p", "多个映射"), ("r-xp", "多个映射")):
            changed = copy.deepcopy(self.regions)
            changed.append(dict(changed[1], start=BASE * 2, end=BASE * 2 + PAGE,
                                perms=perms, path="/tmp/different-name.apk"))
            with self.subTest(perms=perms), self.assertRaisesRegex(AuditError, reason):
                self.verify(changed, BASE)

    def test_partial_file_page_alias_is_rejected(self):
        changed = copy.deepcopy(self.regions)
        changed.append(dict(changed[1], start=BASE * 2, end=BASE * 2 + 2 * PAGE,
                            offset=changed[1]["offset"] - PAGE, perms="rw-p"))
        with self.assertRaisesRegex(AuditError, "可写别名"):
            self.verify(changed, BASE)

    def test_same_inode_on_another_device_is_not_an_alias(self):
        changed = copy.deepcopy(self.regions)
        changed.append(dict(changed[1], start=BASE * 2, end=BASE * 2 + PAGE, dev="fe:35"))
        self.verify(changed, BASE)

    def test_neighboring_file_page_is_not_an_alias(self):
        changed = copy.deepcopy(self.regions)
        changed.append(dict(changed[1], start=BASE * 2, end=BASE * 2 + PAGE,
                            offset=APK_OFFSET + 0x3000, perms="rw-p"))
        self.verify(changed, BASE)

    def test_precedent_requires_same_device_and_readable_permissions(self):
        requirements = self.contract["mapsRequirements"]
        for key, value in (("dev", "fe:35"), ("perms", "-w-p")):
            changed = copy.deepcopy(self.regions)
            changed[-1][key] = value
            with self.subTest(key=key), self.assertRaisesRegex(AuditError, "既有先例"):
                verify_page_properties(changed, requirements, [dict(feature="示例", runtime=BASE + 0x4100)])

    def test_non_page_aligned_geometry_is_rejected(self):
        for key in ("start", "end", "offset"):
            changed = copy.deepcopy(self.regions)
            changed[0][key] += 1
            with self.subTest(key=key), self.assertRaisesRegex(AuditError, "页对齐"):
                self.verify(changed)

    def test_invalid_page_size_is_rejected(self):
        for size in (0, 3072, -4096, True):
            self.contract["mapsRequirements"]["pageSize"] = size
            with self.subTest(size=size), self.assertRaisesRegex(AuditError, "页大小"):
                self.verify()

    def test_malformed_permissions_device_and_inode_are_rejected(self):
        for bad in ("xwrp", "rwx-", "xxxx"):
            with self.subTest(perms=bad), self.assertRaisesRegex(AuditError, "无法解析"):
                parse_maps("1000-2000 %s 0 fe:34 77 /a\n" % bad)
        for field in ("wrong 77", "fe:34 bad"):
            with self.subTest(field=field), self.assertRaisesRegex(AuditError, "无法解析"):
                parse_maps("1000-2000 r--p 0 %s /a\n" % field)

    def test_safety_contract_is_checked_by_mapping_entry(self):
        for field, value in (("installPermitted", True), ("notClosedGates", []),
                             ("sites", []), ("schemaVersion", 2)):
            with self.subTest(field=field):
                old = self.contract[field]
                self.contract[field] = value
                with self.assertRaises(AuditError):
                    self.verify()
                self.contract[field] = old

    def test_negative_controls_use_the_real_checker(self):
        rows = replay.negative_controls(self.image, self.contract, self.regions, APK_OFFSET, BASE)
        self.assertEqual(9, len(rows))
        self.assertTrue(all(row["已拒绝"] for row in rows))
        self.assertEqual(regions_for(), self.regions)

    def test_fault_control_cannot_silently_pass(self):
        with patch.object(replay, "verify_mapping_evidence", return_value={}):
            with self.assertRaisesRegex(AuditError, "被错误放行"):
                replay.negative_controls(self.image, self.contract, self.regions, APK_OFFSET, BASE)

    def test_fault_control_requires_expected_reason(self):
        with patch.object(replay, "verify_mapping_evidence", side_effect=AuditError("不相关错误")):
            with self.assertRaisesRegex(AuditError, "非预期位置"):
                replay.negative_controls(self.image, self.contract, self.regions, APK_OFFSET, BASE)


class ApkEntryTest(unittest.TestCase):
    def setUp(self):
        self.image = ElfImage(fixture())
        self.requirements = contract_for(self.image)["mapsRequirements"]

    def test_stored_entry_independently_proves_offset(self):
        self.assertEqual(APK_OFFSET, verify_apk_entry(apk_for(self.image.data), self.image, self.requirements))

    def test_wrong_content_is_rejected(self):
        data = bytearray(self.image.data)
        data[-1] ^= 1
        with self.assertRaisesRegex(AuditError, "内容"):
            verify_apk_entry(apk_for(bytes(data)), self.image, self.requirements)

    def test_wrong_size_is_rejected(self):
        with self.assertRaisesRegex(AuditError, "长度"):
            verify_apk_entry(apk_for(self.image.data[:-1]), self.image, self.requirements)

    def test_compressed_unaligned_duplicate_and_missing_entries_are_rejected(self):
        for kwargs, reason in (({"compressed": True}, "未压缩"), ({"aligned": False}, "页对齐"),
                               ({"duplicate": True}, "只有一个"), ({"name": "other.so"}, "只有一个")):
            with self.subTest(kwargs=kwargs), self.assertRaisesRegex(AuditError, reason):
                verify_apk_entry(apk_for(self.image.data, **kwargs), self.image, self.requirements)

    def test_local_header_must_match_directory(self):
        data = bytearray(apk_for(self.image.data))
        struct.pack_into("<I", data, 18, 0)
        with self.assertRaisesRegex(AuditError, "本地条目头"):
            verify_apk_entry(bytes(data), self.image, self.requirements)

    def test_local_name_must_match_directory(self):
        data = bytearray(apk_for(self.image.data))
        data[30] ^= 1
        with self.assertRaisesRegex(AuditError, "本地条目名"):
            verify_apk_entry(bytes(data), self.image, self.requirements)


class ManifestReplayTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.local = patch.object(replay, "LOCAL_ROOT", self.root)
        self.local.start()
        self.addCleanup(self.local.stop)
        self.host = self.root / "host.so"
        self.apk = self.root / "host.apk"
        self.maps = self.root / "maps.txt"
        self.contract = self.root / "contract.json"
        self.manifest = self.root / "evidence" / "snapshots.json"
        image = ElfImage(fixture())
        self.host.write_bytes(image.data)
        self.apk.write_bytes(apk_for(image.data))
        self.maps.write_bytes(maps_bytes(regions_for()))
        self.contract.write_text(json.dumps(contract_for(image), ensure_ascii=False), encoding="utf-8")

    def record(self, maps_paths=None):
        return replay.record_manifest(self.manifest, self.host, self.apk,
                                      [self.maps] if maps_paths is None else maps_paths, self.contract)

    def replay(self):
        return replay.replay_manifest(self.manifest, self.contract)

    def mutate_manifest(self, callback):
        value = json.loads(self.manifest.read_text(encoding="utf-8"))
        callback(value)
        self.manifest.write_text(json.dumps(value), encoding="utf-8")

    def test_record_and_replay_are_identical_and_private(self):
        before = self.record()
        self.assertEqual(before, self.replay())
        self.assertFalse(before["安装许可"])
        self.assertEqual([3, 5], before["未关闭门槛"])
        raw = json.dumps(before)
        for secret in (HOST_PATH, str(self.root), hex(BASE), '"inode"'):
            self.assertNotIn(secret, raw)
        manifest = json.loads(self.manifest.read_text())
        self.assertEqual("../host.so", manifest["hostElf"]["path"])
        self.assertNotIn("base", manifest)

    def test_multiple_snapshots_have_independent_aslr(self):
        second = self.root / "second-maps.txt"
        second.write_bytes(maps_bytes(regions_for(BASE * 2)))
        result = self.record([self.maps, second])
        self.assertEqual(2, result["快照数"])
        self.assertEqual(result, self.replay())
        self.assertEqual(18, sum(len(row["拒绝对照"]) for row in result["快照回放"]))

    def test_each_input_is_hash_pinned(self):
        self.record()
        for path in (self.host, self.apk, self.maps, self.contract):
            original = path.read_bytes()
            path.write_bytes(original + b"\n")
            with self.subTest(path=path.name), self.assertRaisesRegex(AuditError, "摘要漂移"):
                self.replay()
            path.write_bytes(original)

    def test_snapshot_is_reaudited_even_if_its_hash_is_updated(self):
        self.record()
        changed = regions_for()
        changed[1]["perms"] = "rwxp"
        data = maps_bytes(changed)
        self.maps.write_bytes(data)
        self.mutate_manifest(lambda value: value["snapshots"][0].update(sha256=digest(data)))
        with self.assertRaises(AuditError):
            self.replay()

    def test_record_never_overwrites_existing_manifest(self):
        self.record()
        before = self.manifest.read_bytes()
        with self.assertRaisesRegex(AuditError, "不覆盖"):
            self.record()
        self.assertEqual(before, self.manifest.read_bytes())

    def test_record_requires_ignored_local_output(self):
        with self.assertRaisesRegex(AuditError, ".workbuddy"):
            replay.record_manifest(self.root.parent / "public.json", self.host, self.apk,
                                   [self.maps], self.contract)

    def test_record_rejects_bad_input_without_writing_manifest(self):
        self.maps.write_bytes(b"bad maps\n")
        with self.assertRaises(AuditError):
            self.record()
        self.assertFalse(self.manifest.exists())

    def test_duplicate_snapshots_are_rejected_on_record(self):
        with self.assertRaisesRegex(AuditError, "重复"):
            self.record([self.maps, self.maps])

    def test_empty_and_duplicate_snapshots_are_rejected_on_replay(self):
        self.record()
        original = self.manifest.read_bytes()
        for transform in (lambda value: value.update(snapshots=[]),
                          lambda value: value["snapshots"].append(value["snapshots"][0])):
            self.mutate_manifest(transform)
            with self.assertRaisesRegex(AuditError, "不能为空|重复"):
                self.replay()
            self.manifest.write_bytes(original)

    def test_manifest_cannot_relax_safety_policy(self):
        self.record()
        original = self.manifest.read_bytes()
        for field, value in (("schemaVersion", 2), ("policy", "other"),
                             ("installPermitted", True), ("notClosedGates", [3])):
            self.mutate_manifest(lambda item: item.update({field: value}))
            with self.subTest(field=field), self.assertRaises(AuditError):
                self.replay()
            self.manifest.write_bytes(original)

    def test_missing_file_is_not_treated_as_skip(self):
        self.record()
        self.maps.unlink()
        with self.assertRaises(FileNotFoundError):
            self.replay()

    def test_cli_returns_nonzero_on_missing_evidence(self):
        with patch("sys.stdout", new_callable=io.StringIO) as output:
            status = replay.main(["replay", "--manifest", str(self.manifest), "--contract", str(self.contract)])
        self.assertEqual(1, status)
        self.assertIn("快照回归失败", output.getvalue())

    def test_cli_record_and_replay(self):
        with patch("sys.stdout", new_callable=io.StringIO) as output:
            status = replay.main(["record", "--host-elf", str(self.host), "--host-apk", str(self.apk),
                                  "--maps", str(self.maps), "--output", str(self.manifest),
                                  "--contract", str(self.contract)])
            first = json.loads(output.getvalue())
        self.assertEqual(0, status)
        with patch("sys.stdout", new_callable=io.StringIO) as output:
            status = replay.main(["replay", "--manifest", str(self.manifest), "--contract", str(self.contract)])
            second = json.loads(output.getvalue())
        self.assertEqual(0, status)
        self.assertEqual(first, second)


if __name__ == "__main__":
    unittest.main()
