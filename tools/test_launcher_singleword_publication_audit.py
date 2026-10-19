"""单字发布协议审计器的合成用例。

真实宿主只由审计命令显式验证，不以跳过代替通过。这里用可控的合成映像覆盖
正向通过路径与各类拒绝路径，确保任何判据失效都会失败而不是静默放行。
"""

import copy
import struct
import unittest

from launcher_native_audit import AuditError, digest
from launcher_singleword_publication_audit import (FAULTS, POLICY, WITNESSES, explore,
                                                   verify_architecture, verify_contract)


TARGET = 0x1000
SITE_A = 0x2000
SITE_B = 0x2040
OTHER = 0x3000


def bl(pc, target):
    return 0x94000000 | (((target - pc) // 4) & 0x3FFFFFF)


def b(pc, target):
    return 0x14000000 | (((target - pc) // 4) & 0x3FFFFFF)


def build_words():
    words = {
        TARGET: 0xA9BF79FD,
        TARGET + 4: 0xAA0F03FD,
        TARGET + 8: 0xD65F03C0,
        SITE_A: bl(SITE_A, TARGET),
        SITE_B: bl(SITE_B, TARGET),
        SITE_A + 4: 0xAA0003E2,
        SITE_B + 4: 0xAA0003E3,
        OTHER: b(OTHER, SITE_A + 0x40),
    }
    return words


class FakeImage:
    def __init__(self, words, base=0x1000, size=0x3000, build_id="deadbeef"):
        self.base = base
        self.size = size
        self._build_id = build_id
        blob = bytearray(base + size)
        for address, value in words.items():
            struct.pack_into("<I", blob, address, value)
        self.data = bytes(blob)

    def code(self, address, size):
        if address < self.base or address + size > self.base + self.size or size % 4:
            raise AuditError("合成映像的代码窗口越界或未对齐")
        return self.data[address:address + size]

    def build_id(self):
        return self._build_id


def word(image, address):
    return "0x%08x" % struct.unpack("<I", image.code(address, 4))[0]


def build_contract(image):
    return {
        "schemaVersion": 1,
        "policy": POLICY,
        "installPermitted": False,
        "architecture": {
            "manual": {
                "document": "ARM DDI 0487 L.a", "section": "B2.2.5", "page": 230,
                "documentSha256":
                    "20d6ab39200e3d0c84cfc5948c6d3983a7a1867c11c9babe1ee4c2ee311d5368",
                "establishes": "concurrent-modification-exception-set",
                "note": "BL 属于无同步并发修改例外集合。",
            },
            "article": {
                "title": "Caches and Self-Modifying Code: Working with Threads",
                "author": "Jacob Bramley", "date": "2025-01-21",
                "url": "https://community.arm.com/arm-community-blogs/b/"
                       "architectures-and-processors-blog/posts/"
                       "caches-and-self-modifying-code-working-with-threads",
                "establishes": "prefetch-speculation-protection",
                "note": "预取推测保护：先用 dc cvau / dsb ish / ic ivau / dsb ish "
                        "完成同步再改分支；多字修改会出现新旧混合执行。",
            },
        },
        "image": {"sha256": digest(image.data), "buildId": image.build_id(),
                  "size": len(image.data)},
        "protocol": {
            "trampolinePublishedBeforeCommit": True, "commitWords": 1, "commitBits": 32,
            "pageRetention": "process-lifetime", "disable": "state-word-only",
        },
        "sites": [
            {"feature": "示例一", "address": hex(SITE_A), "word": word(image, SITE_A),
             "target": hex(TARGET), "replacementKind": "BL", "writeBytes": 4,
             "aligned": True, "returnAddress": hex(SITE_A + 4)},
            {"feature": "示例二", "address": hex(SITE_B), "word": word(image, SITE_B),
             "target": hex(TARGET), "replacementKind": "BL", "writeBytes": 4,
             "aligned": True, "returnAddress": hex(SITE_B + 4)},
        ],
        "counts": {"sites": 2},
        "notClosedGates": [
            "门槛1：蹦床发布次序仍须独立验收。",
            "门槛3：安全通道实机验收未做。",
            "门槛5：蹦床页内存属性、映射生命周期与单条存储都未取证。",
        ],
    }


def fixture():
    image = FakeImage(build_words())
    return image, build_contract(image)


class PolicyModelTest(unittest.TestCase):
    def test_correct_protocol_has_no_counterexample(self):
        result = explore()
        self.assertTrue(result["通过"])
        self.assertEqual(set(WITNESSES), set(result["关键交错"]))
        self.assertGreater(result["状态数"], 0)

    def test_every_fault_is_detected(self):
        for fault in FAULTS:
            with self.subTest(fault=fault):
                result = explore(fault)
                self.assertFalse(result["通过"])
                self.assertTrue(result["原因"])
                self.assertTrue(result["反例"])

    def test_unknown_fault_is_refused(self):
        with self.assertRaises(AuditError):
            explore("未知故障")

    def test_fault_reasons_match_the_claim(self):
        self.assertIn("缓存同步", explore("early_commit")["原因"])
        self.assertIn("回收", explore("free_live_page")["原因"])
        self.assertIn("多字", explore("multi_word_commit")["原因"])


class PublicationContractTest(unittest.TestCase):
    def setUp(self):
        self.image, self.contract = fixture()

    def verify(self, image=None, contract=None):
        return verify_contract(image or self.image, contract or self.contract)

    def test_reviewed_contract_passes_without_install_permission(self):
        result = self.verify()
        self.assertIs(result["安装许可"], False)
        self.assertEqual(2, len(result["调用点"]))
        self.assertEqual(set(FAULTS), set(result["故障反例"]))

    def test_install_permission_or_policy_is_refused(self):
        for key, value in (("installPermitted", True), ("policy", "other"),
                           ("schemaVersion", 2)):
            broken = copy.deepcopy(self.contract)
            broken[key] = value
            with self.subTest(key=key), self.assertRaises(AuditError):
                self.verify(contract=broken)

    def test_gate_three_must_stay_open(self):
        broken = copy.deepcopy(self.contract)
        broken["notClosedGates"] = [gate for gate in broken["notClosedGates"]
                                    if not gate.startswith("门槛3")]
        with self.assertRaises(AuditError):
            self.verify(contract=broken)

    def test_manual_reference_must_match(self):
        for key, value in (("page", 231), ("section", "B2.2.4"),
                           ("documentSha256", "0" * 64)):
            broken = copy.deepcopy(self.contract)
            broken["architecture"]["manual"][key] = value
            with self.subTest(key=key), self.assertRaises(AuditError):
                verify_architecture(broken)

    def test_article_must_be_the_official_source(self):
        for key, value in (("author", "匿名"), ("date", "2020-01-01"),
                           ("url", "https://example.com/post")):
            broken = copy.deepcopy(self.contract)
            broken["architecture"]["article"][key] = value
            with self.subTest(key=key), self.assertRaises(AuditError):
                verify_architecture(broken)

    def test_article_must_establish_prefetch_protection(self):
        broken = copy.deepcopy(self.contract)
        broken["architecture"]["article"]["establishes"] = "unknown"
        with self.assertRaises(AuditError):
            verify_architecture(broken)
        broken = copy.deepcopy(self.contract)
        broken["architecture"]["article"]["note"] = "只说单字提交，不提缓存同步"
        with self.assertRaises(AuditError):
            verify_architecture(broken)

    def test_protocol_must_stay_single_word_and_permanent(self):
        for key, value in (("commitWords", 2), ("commitBits", 64),
                           ("pageRetention", "until-quiet"), ("disable", "rewrite-word"),
                           ("trampolinePublishedBeforeCommit", False)):
            broken = copy.deepcopy(self.contract)
            broken["protocol"][key] = value
            with self.subTest(key=key), self.assertRaises(AuditError):
                self.verify(contract=broken)

    def test_site_word_must_match_host_and_be_eligible(self):
        broken = copy.deepcopy(self.contract)
        broken["sites"][0]["word"] = "0x14000000"
        with self.assertRaises(AuditError):
            self.verify(contract=broken)

    def test_site_must_write_one_aligned_word(self):
        for key, value in (("writeBytes", 8), ("aligned", False)):
            broken = copy.deepcopy(self.contract)
            broken["sites"][0][key] = value
            with self.subTest(key=key), self.assertRaises(AuditError):
                self.verify(contract=broken)

    def test_replacement_kind_must_stay_in_exception_set(self):
        broken = copy.deepcopy(self.contract)
        broken["sites"][0]["replacementKind"] = "STP"
        with self.assertRaises(AuditError):
            self.verify(contract=broken)

    def test_return_address_must_be_call_site_plus_four(self):
        broken = copy.deepcopy(self.contract)
        broken["sites"][0]["returnAddress"] = hex(SITE_A + 8)
        with self.assertRaises(AuditError):
            self.verify(contract=broken)

    def test_site_count_must_match(self):
        broken = copy.deepcopy(self.contract)
        broken["counts"]["sites"] = 1
        with self.assertRaises(AuditError):
            self.verify(contract=broken)

    def test_duplicate_site_is_refused(self):
        broken = copy.deepcopy(self.contract)
        broken["sites"][1]["address"] = broken["sites"][0]["address"]
        with self.assertRaises(AuditError):
            self.verify(contract=broken)

    def test_host_digest_must_match(self):
        broken = copy.deepcopy(self.contract)
        broken["image"]["sha256"] = "0" * 64
        with self.assertRaises(AuditError):
            self.verify(contract=broken)


if __name__ == "__main__":
    unittest.main()
