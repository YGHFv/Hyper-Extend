"""调用点返回语义审计器的合成用例。

真实宿主与真实汇编对象只由审计命令显式验证，不以跳过代替通过。这里用合成映像与
合成对象覆盖：分类器正确性（含掩码必须包含 Rd 的回归）、叶蹦床白名单解码、
以及各类拒绝路径。
"""

import struct
import unittest

from launcher_menu_return_contract_audit import (POLICY, X16, classify_site, decode_island,
                                                 discard_form, forward_target, verify_contract,
                                                 verify_island_preserves_x0, writes_x0)
from launcher_native_audit import AuditError, digest


BASE = 0x1000
CONTINUATION = 0xAA0003E2   # mov x2, x0
DISCARD_B = 0x1400000D      # b <尾部>
DISCARD_NULL = 0xAA1603E0   # mov x0, x22
BL = 0x94000010


class FakeImage:
    def __init__(self, words, size=0x4000, build_id="deadbeef"):
        blob = bytearray(size)
        for address, value in words.items():
            struct.pack_into("<I", blob, address, value)
        self.data = bytes(blob)
        self._build_id = build_id

    def code(self, address, size):
        if address % 4 or size % 4 or address + size > len(self.data):
            raise AuditError("合成映像的代码窗口非法")
        return self.data[address:address + size]

    def build_id(self):
        return self._build_id


def site(offset=BASE, word=BL, continuation=CONTINUATION, use="转发", rewrite=False):
    return {"feature": "示例", "offset": hex(offset), "word": hex(word),
            "target": hex(offset + 0x40), "continuationWord": hex(continuation),
            "returnUse": use, "rewriteReturn": rewrite,
            "allowsTrampolineToWriteX0": use == "转发" and rewrite}


def island_object(words=(0x90000010, 0x91000210, 0xF9400210, 0xD61F0200)):
    class Obj:
        pass
    obj = Obj()
    obj.code = struct.pack("<%dI" % len(words), *words)
    return obj


def island_contract(words=(0x90000010, 0x91000210, 0xF9400210, 0xD61F0200)):
    blob = struct.pack("<%dI" % len(words), *words)
    return {"symbol": "launcher_menu_island", "size": len(blob), "sha256": digest(blob),
            "words": [hex(word) for word in words], "preservesX0": True}


def wrapped(words=(0x90000010, 0x91000210, 0xF9400210, 0xD61F0200)):
    return {"island": island_contract(words)}


class ClassifierTest(unittest.TestCase):
    def test_forward_forms(self):
        self.assertIsNotNone(forward_target(0xAA0003E2))   # mov x2, x0
        self.assertIsNotNone(forward_target(0xAA0003E1))   # mov x1, x0
        self.assertIsNone(forward_target(0xAA1603E0))      # mov x0, x22（不是转发）
        self.assertIsNone(forward_target(0x91000210))      # add x16, x16, #0（掩码回归）

    def test_discard_forms(self):
        self.assertEqual("b <尾部>", discard_form(DISCARD_B))
        self.assertEqual("null 对象 mov x0, x22", discard_form(DISCARD_NULL))
        self.assertIsNone(discard_form(0xAA0003E2))

    def test_writes_x0_matches_rd_only(self):
        self.assertIsNotNone(writes_x0(0xAA0003E0))     # mov x0, x0
        self.assertIsNotNone(writes_x0(0x91000000))     # add x0, x0, #0
        self.assertIsNone(writes_x0(0x91000210))        # add x16, x16, #0
        self.assertIsNone(writes_x0(0xAA0003E1))        # mov x1, x0
        self.assertIsNone(writes_x0(0xD61F0200))        # br x16

    def test_island_whitelist_decodes_register(self):
        for word in (0x90000010, 0x91000210, 0xF9400210, 0xD61F0200):
            with self.subTest(word=hex(word)):
                name, decoded = decode_island(word)
                self.assertIsNotNone(name)
                self.assertEqual(X16, decoded)
        self.assertEqual((None, None), decode_island(0xAA0003E0))   # mov x0, x0
        self.assertEqual((None, None), decode_island(0xD65F03C0))   # ret


class ClassifySiteTest(unittest.TestCase):
    def test_forward_site(self):
        image = FakeImage({BASE: BL, BASE + 4: CONTINUATION})
        self.assertEqual("转发", classify_site(image, site())["形态"])

    def test_discard_site(self):
        image = FakeImage({BASE: BL, BASE + 4: DISCARD_B})
        self.assertEqual("丢弃",
                         classify_site(image, site(continuation=DISCARD_B, use="丢弃"))["形态"])

    def test_non_bl_call_is_rejected(self):
        image = FakeImage({BASE: 0x14000010, BASE + 4: CONTINUATION})
        with self.assertRaisesRegex(AuditError, "不是 BL"):
            classify_site(image, site(word=0x14000010))

    def test_changed_continuation_is_rejected(self):
        image = FakeImage({BASE: BL, BASE + 4: 0x91000000})
        with self.assertRaisesRegex(AuditError, "接续指令"):
            classify_site(image, site())

    def test_unknown_continuation_is_rejected(self):
        image = FakeImage({BASE: BL, BASE + 4: 0x91000000})
        broken = site()
        broken["continuationWord"] = hex(0x91000000)
        with self.assertRaisesRegex(AuditError, "人工确认"):
            classify_site(image, broken)


class IslandTest(unittest.TestCase):
    def test_clean_island_passes(self):
        result = verify_island_preserves_x0(island_object(), wrapped())
        self.assertEqual(0, result["写 x0 的指令数"])

    def test_island_writing_x0_is_rejected(self):
        words = (0x90000010, 0x91000210, 0xF9400210, 0xAA1603E0)   # 末尾改成 mov x0, x22
        with self.assertRaisesRegex(AuditError, "写 x0"):
            verify_island_preserves_x0(island_object(words), wrapped(words))

    def test_island_outside_whitelist_is_rejected(self):
        words = (0x90000010, 0x91000210, 0xF9400210, 0xD65F03C0)   # 末尾是 ret
        with self.assertRaisesRegex(AuditError, "白名单"):
            verify_island_preserves_x0(island_object(words), wrapped(words))

    def test_island_using_other_register_is_rejected(self):
        words = (0x90000011, 0x91000211, 0xF9400211, 0xD61F0220)   # 全部改成 x17
        with self.assertRaisesRegex(AuditError, "x16"):
            verify_island_preserves_x0(island_object(words), wrapped(words))

    def test_length_or_digest_mismatch_is_rejected(self):
        # 长度/摘要先于逐条比对；两者都覆盖「合同与对象不一致」。
        with self.assertRaisesRegex(AuditError, "长度或摘要"):
            verify_island_preserves_x0(island_object(), wrapped((0x90000010, 0x91000210)))

    def test_changed_words_are_rejected(self):
        # 只改合同里的 words 列表（摘要与长度仍来自真实字节）必须被逐条比对拒绝。
        words = (0x90000010, 0x91000210, 0xF9400210, 0xD61F0200)
        contract = wrapped(words)
        contract["island"]["words"] = ["0x90000010", "0x91000210", "0xF9400210", "0xd61f0000"]
        with self.assertRaisesRegex(AuditError, "逐条不符"):
            verify_island_preserves_x0(island_object(words), contract)


class ContractTest(unittest.TestCase):
    def contract(self, sites):
        return {"schemaVersion": 1, "policy": POLICY, "installPermitted": False,
                "image": {"sha256": "0" * 64, "buildId": "deadbeef", "size": 0x4000},
                "island": island_contract(),
                "sites": sites, "counts": {"sites": len(sites)},
                "notClosedGates": ["门槛5：未关闭。"]}

    def test_install_permitted_is_rejected(self):
        broken = self.contract([site()])
        broken["installPermitted"] = True
        with self.assertRaisesRegex(AuditError, "安装许可"):
            verify_contract(FakeImage({}), broken, island_object())

    def test_missing_gate_is_rejected(self):
        broken = self.contract([site()])
        broken["notClosedGates"] = ["门槛3：x"]
        with self.assertRaisesRegex(AuditError, "门槛 5"):
            verify_contract(FakeImage({}), broken, island_object())

    def test_wrong_policy_is_rejected(self):
        broken = self.contract([site()])
        broken["policy"] = "other"
        with self.assertRaisesRegex(AuditError, "策略"):
            verify_contract(FakeImage({}), broken, island_object())

    def test_host_digest_is_rejected(self):
        broken = self.contract([site()])
        with self.assertRaisesRegex(AuditError, "宿主文件摘要"):
            verify_contract(FakeImage({}), broken, island_object())

    def test_site_declaring_x0_rewrite_without_forward_use_is_rejected(self):
        item = site(continuation=DISCARD_B, use="丢弃")
        item["allowsTrampolineToWriteX0"] = True
        image = FakeImage({BASE: BL, BASE + 4: DISCARD_B})
        broken = self.contract([item])
        broken["image"]["sha256"] = digest(image.data)
        with self.assertRaisesRegex(AuditError, "改写 x0"):
            verify_contract(image, broken, island_object())

    def test_declared_return_use_must_match_host(self):
        image = FakeImage({BASE: BL, BASE + 4: CONTINUATION})
        broken = self.contract([site(use="丢弃")])
        broken["image"]["sha256"] = digest(image.data)
        with self.assertRaisesRegex(AuditError, "返回值用法"):
            verify_contract(image, broken, island_object())


if __name__ == "__main__":
    unittest.main()
