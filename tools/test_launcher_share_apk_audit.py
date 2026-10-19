"""分享 APK 入口试点校验器的合成用例。

真实宿主只由审计命令显式验证，不以跳过代替通过。这里用可控的合成映像
覆盖正向通过路径与各类拒绝路径，确保任何判据失效都会失败而不是静默放行。
"""

import copy
import hashlib
import struct
import unittest

from launcher_native_audit import AuditError, digest
from launcher_share_apk_audit import (COVERAGE_BYTES, POLICY, decode_add_x22,
                                      decode_frame_slot, decode_ldr_literal_x17,
                                      decode_mov_from_x22, verify_share_apk_contract)


TARGET = 0x1000
TARGET_SIZE = 0x100
ISV = 0x1200
ISV_SIZE = 0x100
THUNK = 0x1800
MATERIALIZE = 0x1900
B_THUNK_CALL = 0x1A00
B_THUNK_SIZE = 4
B_THUNK_CALL2 = 0x1A20
WITNESS = 0x1400
WITNESS_SIZE = 0x20


def add_x22(rd, imm):
    return 0x91000000 | ((imm & 0xFFF) << 10) | (22 << 5) | rd


def mov_from_x22(rd):
    return 0xAA000000 | (22 << 16) | (31 << 5) | rd


def stur(rn, rt, offset):
    return 0xF8000000 | ((offset & 0x1FF) << 12) | (rn << 5) | rt


def stur_x29(rt, offset):
    return stur(29, rt, offset)


def bl(pc, target):
    return 0x94000000 | (((target - pc) // 4) & 0x3FFFFFF)


def b(pc, target):
    return 0x14000000 | (((target - pc) // 4) & 0x3FFFFFF)


TRUE_WORD = add_x22(0, 0x20)
FALSE_WORD = add_x22(0, 0x30)


def build_words():
    """返回 {地址: 32 位机器字}，涵盖所有被合同引用的位置。"""
    words = {}
    # 目标函数 shareApk 的 24 字节覆盖窗口。
    window = [0xA9BF79FD, 0xAA0F03FD, 0xD10041EF, 0xAA0103E0, 0xAA0203E3, 0xF81F83A2]
    for index, word in enumerate(window):
        words[TARGET + index * 4] = word
    words[TARGET + 0x18] = 0xD2800000
    words[TARGET + 0x1C] = 0xD65F03C0
    # 同步调用链：六条 BL 指向已声明的目标。
    callees = [(0x1060, 0x9000), (0x1064, 0x9000), (0x1068, 0x9010),
               (0x106C, 0x9020), (0x1070, 0x9030), (0x1074, 0x9040)]
    for address, target in callees:
        words[address] = bl(address, target)
    # 成功路径返回 Dart null 与 epilogue。
    words[0x10F0] = mov_from_x22(0)
    words[0x10F4] = 0xAA1D03EF
    words[0x10F8] = 0xA8C179FD
    words[0x10FC] = 0xD65F03C0
    # 两条合法入口为 BL 指向目标函数首地址。
    words[0x1100] = bl(0x1100, TARGET)
    words[0x1104] = bl(0x1104, TARGET)
    # isValid：帧槽、两条假值出口、四条内部跳转、布尔计算与续体证据。
    words[ISV + 0x00] = stur_x29(22, -8)
    words[ISV + 0x04] = stur_x29(1, -0xF0)
    words[ISV + 0x08] = FALSE_WORD
    words[ISV + 0x0C] = b(ISV + 0x0C, THUNK)
    words[ISV + 0x10] = FALSE_WORD
    words[ISV + 0x14] = b(ISV + 0x14, THUNK)
    words[ISV + 0x18] = b(ISV + 0x18, ISV + 0x60)
    words[ISV + 0x1C] = b(ISV + 0x1C, ISV + 0x64)
    words[ISV + 0x20] = b(ISV + 0x20, ISV + 0x68)
    words[ISV + 0x24] = b(ISV + 0x24, ISV + 0x6C)
    words[ISV + 0x40] = add_x22(16, 0x20)
    words[ISV + 0x44] = add_x22(17, 0x30)
    words[ISV + 0x48] = 0x9A910202
    words[ISV + 0x4C] = 0xD10403AF
    words[ISV + 0x50] = bl(ISV + 0x50, MATERIALIZE)
    # 共享返回蹦床与帧实体化桩的目标。
    words[THUNK] = 0xF85F83A2
    words[MATERIALIZE] = 0xAA1603E0
    # 让共享返回蹦床恰好被一个函数末尾的 `B` 引用，以复算复用计数。
    words[B_THUNK_CALL] = b(B_THUNK_CALL, THUNK)
    words[B_THUNK_CALL2] = b(B_THUNK_CALL2, THUNK)
    # 布尔目击函数（只要求摘要与范围一致）。
    words[WITNESS] = 0xD503201F
    return words


class FakeDebug:
    def __init__(self, functions):
        self._functions = functions

    def functions(self):
        return list(self._functions)


class FakeImage:
    """只实现分享 APK 校验器需要的最小 ELF 接口。"""

    def __init__(self, words, base=0x1000, size=0x1000, build_id="deadbeef"):
        self.base = base
        self.size = size
        self._build_id = build_id
        blob = bytearray(base + size)
        for address, value in words.items():
            struct.pack_into("<I", blob, address, value)
        self.data = bytes(blob)
        self.segments = [dict(type=1, flags=5, offset=base, address=base,
                              size=size, memory=size)]

    def symbols(self):
        return {
            "ValidShareAppShortcutMenu.shareApk": (TARGET, TARGET_SIZE),
            "ValidShareAppShortcutMenu.isValid": (ISV, ISV_SIZE),
            "Utilities.isSystemPackage": (WITNESS, WITNESS_SIZE),
            "_StringBase.isEmpty": (WITNESS, WITNESS_SIZE),
            "SmallWindowConfig.isSupportMultipleTask": (WITNESS, WITNESS_SIZE),
            "List.isNotEmpty": (WITNESS, WITNESS_SIZE),
            "List.isEmpty": (WITNESS, WITNESS_SIZE),
            "TailCaller": (B_THUNK_CALL, B_THUNK_SIZE),
            "TailCaller2": (B_THUNK_CALL2, B_THUNK_SIZE),
        }

    def code(self, address, size):
        if address < self.base or address + size > self.base + self.size or size % 4:
            raise AuditError("合成映像的代码窗口越界或未对齐")
        return self.data[address:address + size]

    def read(self, offset, size):
        if offset < 0 or offset + size > len(self.data):
            raise AuditError("合成映像的数据窗口越界")
        return self.data[offset:offset + size]

    def build_id(self):
        return self._build_id

    def debug_image(self):
        functions = [dict(name=name, address=address, size=length)
                     for name, (address, length) in self.symbols().items()]
        return FakeDebug(functions)


def word(image, address):
    return "0x%08x" % struct.unpack("<I", image.code(address, 4))[0]


def poke(image, address, value):
    """就地修改合成映像的一个机器字，模拟宿主字节变化。"""
    blob = bytearray(image.data)
    struct.pack_into("<I", blob, address, value)
    image.data = bytes(blob)


def func_entry(image, name):
    address, size = image.symbols()[name]
    return dict(name=name, address=hex(address), size=size,
                sha256=digest(image.code(address, size)))


def build_contract(image):
    return {
        "schemaVersion": 1,
        "policy": POLICY,
        "installPermitted": False,
        "image": {"sha256": digest(image.data), "buildId": image.build_id(),
                  "size": len(image.data)},
        "dartBooleanEncoding": {
            "trueWord": "0x%08x" % TRUE_WORD,
            "falseWord": "0x%08x" % FALSE_WORD,
            "trueMeaning": "Dart true",
            "falseMeaning": "Dart false",
            "sharedReturnThunk": {
                "address": hex(THUNK), "headWord": word(image, THUNK),
                "tailUserCount": 2,
            },
            "witnesses": [
                {"name": "Utilities.isSystemPackage",
                 "function": func_entry(image, "Utilities.isSystemPackage"),
                 "checks": [{"address": hex(WITNESS), "word": word(image, WITNESS),
                             "role": "other"}]},
                {"name": "_StringBase.isEmpty",
                 "checks": [{"address": hex(ISV + 0x40), "word": word(image, ISV + 0x40),
                             "role": "true"},
                            {"address": hex(ISV + 0x44), "word": word(image, ISV + 0x44),
                             "role": "false"}]},
                {"name": "SmallWindowConfig.isSupportMultipleTask",
                 "checks": [{"address": hex(ISV + 0x08), "word": word(image, ISV + 0x08),
                             "role": "false"},
                            {"address": hex(ISV + 0x10), "word": word(image, ISV + 0x10),
                             "role": "false"}]},
                {"name": "List.isNotEmpty",
                 "checks": [{"address": hex(ISV + 0x00), "word": word(image, ISV + 0x00),
                             "role": "other"},
                            {"address": hex(ISV + 0x04), "word": word(image, ISV + 0x04),
                             "role": "other"}]},
                {"name": "List.isEmpty",
                 "checks": [{"address": hex(ISV + 0x18), "word": word(image, ISV + 0x18),
                             "role": "other"},
                            {"address": hex(ISV + 0x1C), "word": word(image, ISV + 0x1C),
                             "role": "other"},
                            {"address": hex(ISV + 0x20), "word": word(image, ISV + 0x20),
                             "role": "other"}]},
            ],
        },
        "farPatchTemplate": {
            "coverageBytes": COVERAGE_BYTES,
            "words": ["0x58000091", "0xd61f0220", "0xd503201f", "0xd503201f"],
            "literalSlot": COVERAGE_BYTES - 8,
        },
        "pilot": {
            "feature": "分享 APK",
            "installPermitted": False,
            "target": func_entry(image, "ValidShareAppShortcutMenu.shareApk"),
            "window": {
                "address": hex(TARGET), "coverageBytes": COVERAGE_BYTES,
                "bytes": image.code(TARGET, COVERAGE_BYTES).hex(),
                "sha256": hashlib.sha256(image.code(TARGET, COVERAGE_BYTES)).hexdigest(),
                "words": [word(image, TARGET + 4 * index) for index in range(6)],
                "positionIndependent": True,
                "resumeAddress": hex(TARGET + COVERAGE_BYTES),
                "resumeBytes": image.code(TARGET + COVERAGE_BYTES, 12).hex(),
            },
            "directEntries": [
                {"address": "0x1100", "kind": "BL", "target": hex(TARGET),
                 "word": word(image, 0x1100)},
                {"address": "0x1104", "kind": "BL", "target": hex(TARGET),
                 "word": word(image, 0x1104)},
            ],
            "windowInteriorEntries": [],
            "pointerReferences": [],
            "callSites": [
                {"address": "0x1100", "word": word(image, 0x1100)},
                {"address": "0x1104", "word": word(image, 0x1104)},
            ],
            "synchronousEvidence": {
                "lastInstruction": "ret",
                "epilogueWords": {"0x10F4": word(image, 0x10F4),
                                  "0x10F8": word(image, 0x10F8),
                                  "0x10FC": word(image, 0x10FC)},
                "nullReturnAddress": "0x10F0", "nullReturnWord": word(image, 0x10F0),
                "callees": [
                    {"address": "0x1060", "target": "0x9000"},
                    {"address": "0x1064", "target": "0x9000"},
                    {"address": "0x1068", "target": "0x9010"},
                    {"address": "0x106C", "target": "0x9020"},
                    {"address": "0x1070", "target": "0x9030"},
                    {"address": "0x1074", "target": "0x9040"},
                ],
            },
        },
        "isValidFinding": {
            "name": "ValidShareAppShortcutMenu.isValid",
            "function": func_entry(image, "ValidShareAppShortcutMenu.isValid"),
            "conclusion": "两条可达出口都返回 Dart 假值对象",
            "exits": [
                {"address": "0x1208", "word": word(image, 0x1208), "dartValue": "false",
                 "next": {"address": "0x120C", "word": word(image, 0x120C)}},
                {"address": "0x1210", "word": word(image, 0x1210), "dartValue": "false",
                 "next": {"address": "0x1214", "word": word(image, 0x1214)}},
            ],
            "interiorBranchTargets": [
                {"address": "0x1218", "word": word(image, 0x1218), "target": hex(ISV + 0x60)},
                {"address": "0x121C", "word": word(image, 0x121C), "target": hex(ISV + 0x64)},
                {"address": "0x1220", "word": word(image, 0x1220), "target": hex(ISV + 0x68)},
                {"address": "0x1224", "word": word(image, 0x1224), "target": hex(ISV + 0x6C)},
            ],
            "boolComputation": {"x16": {"address": "0x1240", "word": word(image, 0x1240)},
                                "x17": {"address": "0x1244", "word": word(image, 0x1244)},
                                "csel": {"address": "0x1248", "word": word(image, 0x1248)}},
            "resumeEntry": {"address": "0x124C", "word": word(image, 0x124C)},
            "frameMaterialize": {"address": "0x1250", "word": word(image, 0x1250),
                                 "target": hex(MATERIALIZE)},
            "frameSlots": {
                "-0x8": {"writes": ["0x1200"], "reads": []},
                "-0xf0": {"writes": ["0x1204"], "reads": []},
            },
        },
    }


def fixture():
    image = FakeImage(build_words())
    return image, build_contract(image)


class DecoderTest(unittest.TestCase):
    def test_add_and_mov_decoders(self):
        self.assertEqual((0, 0x20), decode_add_x22(add_x22(0, 0x20)))
        self.assertEqual((16, 0x30), decode_add_x22(add_x22(16, 0x30)))
        self.assertIsNone(decode_add_x22(0xAA1603E0))
        self.assertEqual(0, decode_mov_from_x22(mov_from_x22(0)))
        self.assertIsNone(decode_mov_from_x22(add_x22(0, 0x20)))

    def test_frame_slot_decoder_needs_x29_and_64bit(self):
        self.assertEqual(("write", 1, -0xF0), decode_frame_slot(stur_x29(1, -0xF0)))
        self.assertEqual(("read", 0, -8), decode_frame_slot(0xF85F83A0))
        self.assertIsNone(decode_frame_slot(0xB85F83A0))  # 32 位形式不认
        self.assertIsNone(decode_frame_slot(stur(20, 16, -8)))  # 基址不是 x29

    def test_ldr_literal_decoder_reads_imm19_as_byte_offset(self):
        self.assertEqual(16, decode_ldr_literal_x17(0x58000091))  # imm19=4 → +16
        self.assertEqual(8, decode_ldr_literal_x17(0x58000051))  # imm19=2 → +8
        self.assertIsNone(decode_ldr_literal_x17(0x58000010))  # 目标寄存器不是 x17
        self.assertIsNone(decode_ldr_literal_x17(0xF8000000))  # 不是字面量 LDR


class ShareApkContractTest(unittest.TestCase):
    def setUp(self):
        self.image, self.contract = fixture()

    def verify(self, image=None, contract=None):
        return verify_share_apk_contract(image or self.image, contract or self.contract)

    def test_valid_contract_passes_without_install_permission(self):
        result = self.verify()
        self.assertIs(result["安装许可"], False)
        self.assertEqual(hex(TARGET), result["入口试点"]["入口"])
        self.assertEqual(COVERAGE_BYTES, result["入口试点"]["覆盖字节"])
        self.assertEqual(2, result["入口试点"]["直接入口数"])
        self.assertEqual(0, result["入口试点"]["窗口内部入口数"])
        self.assertIn("-0xf0", result["isValid 结论"]["写后未读槽"])

    def test_install_permitted_true_is_rejected(self):
        broken = copy.deepcopy(self.contract)
        broken["installPermitted"] = True
        with self.assertRaisesRegex(AuditError, "安装许可"):
            self.verify(contract=broken)

    def test_wrong_policy_is_rejected(self):
        broken = copy.deepcopy(self.contract)
        broken["policy"] = "other-policy"
        with self.assertRaisesRegex(AuditError, "策略"):
            self.verify(contract=broken)

    def test_changed_window_byte_is_rejected(self):
        image = FakeImage(build_words())
        poke(image, TARGET + 0x04, 0xAA1D03FD)  # 覆盖窗口内机器字被改动
        broken = build_contract(image)
        broken["pilot"]["window"]["bytes"] = self.contract["pilot"]["window"]["bytes"]
        with self.assertRaisesRegex(AuditError, "覆盖窗口"):
            self.verify(image=image, contract=broken)

    def test_position_dependent_window_is_rejected(self):
        image = FakeImage(build_words())
        poke(image, TARGET + 0x0C, 0x10000060)  # ADR，位置相关
        broken = build_contract(image)
        with self.assertRaisesRegex(AuditError, "位置相关"):
            self.verify(image=image, contract=broken)

    def test_extra_direct_entry_is_rejected(self):
        image = FakeImage(build_words())
        poke(image, 0x1108, bl(0x1108, TARGET))  # 第三条入口
        broken = build_contract(image)
        broken["pilot"]["window"]["bytes"] = image.code(TARGET, COVERAGE_BYTES).hex()
        broken["pilot"]["window"]["sha256"] = hashlib.sha256(
            image.code(TARGET, COVERAGE_BYTES)).hexdigest()
        with self.assertRaisesRegex(AuditError, "直接入口"):
            self.verify(image=image, contract=broken)

    def test_interior_window_entry_is_rejected(self):
        image = FakeImage(build_words())
        poke(image, 0x1108, bl(0x1108, TARGET + 8))  # 跳进覆盖窗口内部
        broken = build_contract(image)
        with self.assertRaisesRegex(AuditError, "内部|直接入口"):
            self.verify(image=image, contract=broken)

    def test_pointer_reference_is_rejected(self):
        image = FakeImage(build_words())
        blob = bytearray(image.data)
        struct.pack_into("<Q", blob, 0x800, TARGET)
        image.data = bytes(blob)
        broken = build_contract(image)
        with self.assertRaisesRegex(AuditError, "指针引用"):
            self.verify(image=image, contract=broken)

    def test_true_word_at_isvalid_exit_is_rejected(self):
        image = FakeImage(build_words())
        poke(image, 0x1208, TRUE_WORD)
        broken = build_contract(image)
        broken["isValidFinding"]["function"] = func_entry(
            image, "ValidShareAppShortcutMenu.isValid")
        with self.assertRaisesRegex(AuditError, "假值"):
            self.verify(image=image, contract=broken)

    def test_declared_false_word_must_be_add_x0_x22_20(self):
        broken = copy.deepcopy(self.contract)
        broken["dartBooleanEncoding"]["falseWord"] = "0x%08x" % add_x22(0, 0x20)
        with self.assertRaisesRegex(AuditError, "假值编码"):
            self.verify(contract=broken)

    def test_witness_mismatch_is_rejected(self):
        broken = copy.deepcopy(self.contract)
        witness = broken["dartBooleanEncoding"]["witnesses"][1]["checks"][0]
        witness["role"] = "false"  # 真值位置被声明成假值
        with self.assertRaisesRegex(AuditError, "操作数"):
            self.verify(contract=broken)

    def test_frame_slot_read_is_rejected(self):
        image = FakeImage(build_words())
        poke(image, ISV + 0x54, 0xF85103A0)  # ldur x0, [x29, #-0xf0]
        broken = build_contract(image)
        broken["isValidFinding"]["function"] = func_entry(
            image, "ValidShareAppShortcutMenu.isValid")
        with self.assertRaisesRegex(AuditError, "帧槽|直接入口"):
            self.verify(image=image, contract=broken)

    def test_far_patch_coverage_must_be_24(self):
        broken = copy.deepcopy(self.contract)
        broken["farPatchTemplate"]["coverageBytes"] = 16
        with self.assertRaisesRegex(AuditError, "24"):
            self.verify(contract=broken)

    def test_far_patch_words_must_be_ldr_br_nop_nop(self):
        broken = copy.deepcopy(self.contract)
        broken["farPatchTemplate"]["words"][1] = "0xd503201f"
        with self.assertRaisesRegex(AuditError, "br x17"):
            self.verify(contract=broken)

    def test_far_patch_ldr_displacement_must_match_literal_slot(self):
        # 0x58000051 的 imm19=2 → +8，与声明的 literalSlot=16 矛盾，必须失败。
        broken = copy.deepcopy(self.contract)
        broken["farPatchTemplate"]["words"][0] = "0x58000051"
        with self.assertRaisesRegex(AuditError, "字面量槽位"):
            self.verify(contract=broken)

    def test_far_patch_literal_slot_must_be_window_tail(self):
        broken = copy.deepcopy(self.contract)
        broken["farPatchTemplate"]["literalSlot"] = 4
        with self.assertRaisesRegex(AuditError, "末尾"):
            self.verify(contract=broken)

    def test_far_patch_mnemonics_must_describe_imm19(self):
        broken = copy.deepcopy(self.contract)
        broken["farPatchTemplate"]["mnemonics"] = [
            "ldr x17, #8", "br x17", "nop", "nop", "<8 字节岛地址字面量>"]
        with self.assertRaisesRegex(AuditError, "助记符"):
            self.verify(contract=broken)

    def test_changed_target_digest_is_rejected(self):
        image = FakeImage(build_words())
        broken = build_contract(image)
        broken["pilot"]["target"]["sha256"] = "0" * 64
        with self.assertRaisesRegex(AuditError, "摘要"):
            self.verify(image=image, contract=broken)

    def test_changed_host_digest_is_rejected(self):
        broken = copy.deepcopy(self.contract)
        broken["image"]["sha256"] = "0" * 64
        with self.assertRaisesRegex(AuditError, "文件摘要"):
            self.verify(contract=broken)

    def test_missing_null_return_is_rejected(self):
        image = FakeImage(build_words())
        poke(image, 0x10F0, 0xD2800000)  # 不再是 mov x0, x22
        broken = build_contract(image)
        with self.assertRaisesRegex(AuditError, "成功路径|直接入口"):
            self.verify(image=image, contract=broken)


if __name__ == "__main__":
    unittest.main()
