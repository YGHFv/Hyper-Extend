"""单字调用点资格审计器的合成用例。

真实宿主只由审计命令显式验证，不以跳过代替通过。这里用可控的合成映像覆盖
正向通过路径与各类拒绝路径，确保任何判据失效都会失败而不是静默放行。
"""

import copy
import hashlib
import struct
import unittest

from launcher_native_audit import (AuditError, concurrent_modification_eligible,
                                   concurrent_modification_kind, digest)
from launcher_menu_singleword_audit import (CLASSIFIER_SAMPLES, ELIGIBLE_NAMES,
                                            POLICY, UNDECODED_MEMBERS, verify_contract,
                                            verify_publication_policy)
import launcher_branch_publication_audit


TARGET = 0x1000
TARGET_SIZE = 0x40
SITE_A = 0x2000
SITE_B = 0x2100
OTHER = 0x3000


def bl(pc, target):
    return 0x94000000 | (((target - pc) // 4) & 0x3FFFFFF)


def b(pc, target):
    return 0x14000000 | (((target - pc) // 4) & 0x3FFFFFF)


def build_words():
    words = {}
    # 目标以 stp 序言开头：不在例外集合内。
    words[TARGET] = 0xA9BF79FD
    words[TARGET + 4] = 0xAA0F03FD
    words[TARGET + 8] = 0xD65F03C0
    # 两条调用点都是 BL 指向目标。
    words[SITE_A] = bl(SITE_A, TARGET)
    words[SITE_B] = bl(SITE_B, TARGET)
    # 调用点的接续指令（BL 返回后要执行的那条）。
    words[SITE_A + 4] = 0xAA0003E2
    words[SITE_B + 4] = 0xAA0003E3
    # 无关分支，用于证明扫描不会把它当成调用点。
    words[OTHER] = b(OTHER, SITE_A + 0x40)
    return words


class FakeDebug:
    def __init__(self, functions):
        self._functions = functions

    def functions(self):
        return list(self._functions)


class FakeImage:
    def __init__(self, words, base=0x1000, size=0x3000, build_id="deadbeef"):
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
        return {"demo.target": (TARGET, TARGET_SIZE)}

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
    blob = bytearray(image.data)
    struct.pack_into("<I", blob, address, value)
    image.data = bytes(blob)


def build_contract(image):
    name, (address, size) = next(iter(image.symbols().items()))
    return {
        "schemaVersion": 1,
        "policy": POLICY,
        "installPermitted": False,
        "architecture": {
            "document": "ARM DDI 0487 L.a", "section": "B2.2.5", "page": 230,
            "documentSha256":
                "20d6ab39200e3d0c84cfc5948c6d3983a7a1867c11c9babe1ee4c2ee311d5368",
            "eligibleSet": list(ELIGIBLE_NAMES),
            "undecodedMembers": list(UNDECODED_MEMBERS),
        },
        "image": {"sha256": digest(image.data), "buildId": image.build_id(),
                  "size": len(image.data)},
        "targets": [{
            "name": name, "address": hex(address), "size": size,
            "sha256": digest(image.code(address, size)),
            "entryWord": word(image, address), "entryKind": "非例外集合（STP）",
            "entryEligible": False,
            "callers": 2, "pointerRefs": 0,
        }],
        "sites": [
            {"feature": "示例", "address": hex(SITE_A), "word": word(image, SITE_A),
             "kind": "BL", "target": hex(address), "targetName": name,
             "eligible": True, "returnAddress": hex(SITE_A + 4),
             "continuationWord": word(image, SITE_A + 4)},
            {"feature": "示例", "address": hex(SITE_B), "word": word(image, SITE_B),
             "kind": "BL", "target": hex(address), "targetName": name,
             "eligible": True, "returnAddress": hex(SITE_B + 4),
             "continuationWord": word(image, SITE_B + 4)},
        ],
        # 控制流/发布要求与未关闭门槛不是装饰：合同缺项必须被拒绝。
        "controlFlowRequirements": [
            "只改调用点那一个对齐的 32 位 BL 机器字。",
            "旧、新机器字都属于例外集合。",
            "蹦床必须让返回时 x30 等于原 returnAddress。",
            "蹦床必须复现原被调函数语义，本轮未实现。",
            "新代码必须先完成指令/数据缓存同步再发布。",
        ],
        "notClosedGates": [
            "门槛1：蹦床发布次序仍须独立验收。",
            "门槛3：安全通道实机验收未做。",
            "门槛5：写入独占与映射生命周期缺证据。",
        ],
    }


def fixture():
    image = FakeImage(build_words())
    return image, build_contract(image)


class ClassifierTest(unittest.TestCase):
    def test_eligible_set_matches_architecture_manual(self):
        self.assertEqual(sorted(ELIGIBLE_NAMES),
                         sorted(["B", "B.cond", "BL", "BRK", "CBNZ", "CBZ", "HVC",
                                 "ISB", "NOP", "SMC", "SVC", "TBNZ", "TBZ", "TRCIT"]))
        self.assertIn("TRCIT", UNDECODED_MEMBERS)
        for member in ELIGIBLE_NAMES:
            if member not in UNDECODED_MEMBERS:
                self.assertIn(member, [kind for _, kind in CLASSIFIER_SAMPLES])

    def test_every_sample_classifies_as_declared(self):
        for word_value, expected in CLASSIFIER_SAMPLES:
            with self.subTest(word=hex(word_value)):
                self.assertEqual(expected, concurrent_modification_kind(word_value))
                self.assertEqual(expected is not None,
                                 concurrent_modification_eligible(word_value))

    def test_function_prologue_is_not_eligible(self):
        for word_value in (0xA9BF79FD, 0xAA0103E0, 0x58000091, 0xF85F83A1, 0xD65F03C0):
            self.assertFalse(concurrent_modification_eligible(word_value))


class SingleWordContractTest(unittest.TestCase):
    def setUp(self):
        self.image, self.contract = fixture()

    def verify(self, image=None, contract=None):
        return verify_contract(image or self.image, contract or self.contract)

    def test_valid_contract_passes_without_install_permission(self):
        result = self.verify()
        self.assertIs(result["安装许可"], False)
        self.assertEqual(2, len(result["调用点"]))
        self.assertEqual(1, len(result["目标"]))
        self.assertTrue(all(item["分支入口数"] == 0 for item in result["调用点"]))

    def test_install_permitted_true_is_rejected(self):
        broken = copy.deepcopy(self.contract)
        broken["installPermitted"] = True
        with self.assertRaisesRegex(AuditError, "安装许可"):
            self.verify(contract=broken)

    def test_wrong_policy_is_rejected(self):
        broken = copy.deepcopy(self.contract)
        broken["policy"] = "other"
        with self.assertRaisesRegex(AuditError, "策略"):
            self.verify(contract=broken)

    def test_aarch32_reference_is_refused(self):
        broken = copy.deepcopy(self.contract)
        broken["architecture"]["section"] = "E2.2.5"
        with self.assertRaisesRegex(AuditError, "架构依据"):
            self.verify(contract=broken)

    def test_missing_eligible_member_is_refused(self):
        broken = copy.deepcopy(self.contract)
        broken["architecture"]["eligibleSet"].remove("BL")
        with self.assertRaisesRegex(AuditError, "例外集合"):
            self.verify(contract=broken)

    def test_undecoded_member_list_must_match_tool(self):
        broken = copy.deepcopy(self.contract)
        broken["architecture"]["undecodedMembers"] = []
        with self.assertRaisesRegex(AuditError, "未解码"):
            self.verify(contract=broken)

    def test_target_entry_must_be_outside_eligible_set(self):
        image = FakeImage(build_words())
        poke(image, TARGET, 0x14000000)  # 目标入口变成 B，前提不再成立
        broken = build_contract(image)
        with self.assertRaisesRegex(AuditError, "目标入口|入口"):
            self.verify(image=image, contract=broken)

    def test_target_entry_kind_cannot_be_claimed_eligible(self):
        broken = copy.deepcopy(self.contract)
        broken["targets"][0]["entryEligible"] = True
        with self.assertRaisesRegex(AuditError, "入口"):
            self.verify(contract=broken)

    def test_call_site_must_be_a_bl_to_target(self):
        image = FakeImage(build_words())
        poke(image, SITE_A, b(SITE_A, TARGET))  # 改成 B
        broken = build_contract(image)
        with self.assertRaisesRegex(AuditError, "目标入口|BL"):
            self.verify(image=image, contract=broken)

    def test_call_site_that_is_jumped_into_is_refused(self):
        image = FakeImage(build_words())
        poke(image, OTHER, bl(OTHER, SITE_A))  # 别的分支跳进调用点
        broken = build_contract(image)
        with self.assertRaisesRegex(AuditError, "跳入"):
            self.verify(image=image, contract=broken)

    def test_changed_call_site_word_is_rejected(self):
        image = FakeImage(build_words())
        poke(image, SITE_B, bl(SITE_B, 0x1000))
        broken = build_contract(image)
        broken["sites"][1]["word"] = "0x94000000"
        with self.assertRaisesRegex(AuditError, "机器字"):
            self.verify(image=image, contract=broken)

    def test_changed_target_digest_is_rejected(self):
        broken = copy.deepcopy(self.contract)
        broken["targets"][0]["sha256"] = "0" * 64
        with self.assertRaisesRegex(AuditError, "摘要"):
            self.verify(contract=broken)

    def test_changed_host_digest_is_rejected(self):
        broken = copy.deepcopy(self.contract)
        broken["image"]["sha256"] = "0" * 64
        with self.assertRaisesRegex(AuditError, "宿主"):
            self.verify(contract=broken)

    def test_wrong_caller_count_is_rejected(self):
        broken = copy.deepcopy(self.contract)
        broken["targets"][0]["callers"] = 1
        with self.assertRaisesRegex(AuditError, "调用点数量"):
            self.verify(contract=broken)

    def test_pointer_reference_is_rejected(self):
        image = FakeImage(build_words())
        blob = bytearray(image.data)
        struct.pack_into("<Q", blob, 0x800, TARGET)
        image.data = bytes(blob)
        broken = build_contract(image)
        with self.assertRaisesRegex(AuditError, "指针引用"):
            self.verify(image=image, contract=broken)

    def test_duplicate_call_site_is_rejected(self):
        broken = copy.deepcopy(self.contract)
        broken["sites"][1]["address"] = broken["sites"][0]["address"]
        with self.assertRaisesRegex(AuditError, "重复"):
            self.verify(contract=broken)

    def test_return_address_must_be_call_site_plus_four(self):
        broken = copy.deepcopy(self.contract)
        broken["sites"][0]["returnAddress"] = hex(SITE_A + 8)
        with self.assertRaisesRegex(AuditError, "返回地址"):
            self.verify(contract=broken)

    def test_changed_continuation_instruction_is_rejected(self):
        image = FakeImage(build_words())
        poke(image, SITE_A + 4, 0xAA0003E4)  # 接续指令被改动
        broken = build_contract(image)
        broken["sites"][0]["continuationWord"] = word(self.image, SITE_A + 4)
        with self.assertRaisesRegex(AuditError, "接续指令"):
            self.verify(image=image, contract=broken)

    def test_continuation_word_is_pinned_by_contract(self):
        broken = copy.deepcopy(self.contract)
        broken["sites"][0]["continuationWord"] = "0xd503201f"
        with self.assertRaisesRegex(AuditError, "接续指令"):
            self.verify(contract=broken)

    def test_result_reports_publication_requirements_and_policy(self):
        result = self.verify()
        self.assertEqual(5, result["发布要求"]["控制流要求数"])
        self.assertEqual(3, result["发布要求"]["未关闭门槛数"])
        self.assertTrue(result["发布策略"]["状态数"] > 0)
        self.assertEqual(["early_publish", "early_release"], result["发布策略"]["故障对照"])

    def test_missing_control_flow_requirement_is_rejected(self):
        broken = copy.deepcopy(self.contract)
        broken["controlFlowRequirements"] = []
        with self.assertRaisesRegex(AuditError, "控制流"):
            self.verify(contract=broken)

    def test_dropped_return_address_constraint_is_rejected(self):
        # 用等量但无关的要求替换掉 x30/返回地址那条，数量仍够：仍必须拒绝。
        broken = copy.deepcopy(self.contract)
        requirements = broken["controlFlowRequirements"]
        for index, requirement in enumerate(requirements):
            if "x30" in requirement:
                requirements[index] = "蹦床必须保持宿主可观察行为不变，本轮未实现。"
        with self.assertRaisesRegex(AuditError, "returnAddress|x30"):
            self.verify(contract=broken)

    def test_missing_not_closed_gate_is_rejected(self):
        broken = copy.deepcopy(self.contract)
        broken["notClosedGates"] = ["门槛3：安全通道实机验收未做。"]
        with self.assertRaisesRegex(AuditError, "门槛"):
            self.verify(contract=broken)

    def test_dropped_gate_five_declaration_is_rejected(self):
        broken = copy.deepcopy(self.contract)
        # 保持 3 条（数量仍够），只把门槛 5 换成别的门槛：必须单独被拒。
        gates = broken["notClosedGates"]
        for index, gate in enumerate(gates):
            if gate.startswith("门槛5"):
                gates[index] = "门槛4：宿主映射生命周期仍须独立验收。"
        with self.assertRaisesRegex(AuditError, "门槛 5"):
            self.verify(contract=broken)


class PublicationPolicyTest(unittest.TestCase):
    def test_policy_model_passes_with_all_required_interleavings(self):
        result = verify_publication_policy()
        self.assertEqual(1164, result["状态数"])
        self.assertEqual(6188, result["迁移数"])
        self.assertEqual(["先新后旧", "关闭时已有调用读到旧值", "恢复后旧跳转"],
                         result["关键交错"])

    def test_premature_publish_control_is_detected(self):
        self.assertFalse(launcher_branch_publication_audit.explore("early_publish")["通过"])

    def test_premature_release_control_is_detected(self):
        self.assertFalse(launcher_branch_publication_audit.explore("early_release")["通过"])


if __name__ == "__main__":
    unittest.main()
