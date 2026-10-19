"""蹦床落点可达性审计器的合成用例。

真实宿主与真实映射快照只由审计命令显式验证，不以跳过代替通过。这里用可控的
合成映像与合成映射覆盖正向通过路径与各类拒绝路径。
"""

import copy
import struct
import unittest

from launcher_native_audit import AuditError, digest
from launcher_trampoline_landing_audit import (BRANCH_LIMIT, POLICY, decode_b, encode_b,
                                               find_holes, parse_maps, verify_contract)


BASE = 0x10000000
SITE = 0x2000
TARGET = 0x2400
STRIDE = 0x10000


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

    def file_offset(self, address, size, executable=False):
        if address % 4 or address + size > len(self.data):
            raise AuditError("合成映像的地址没有合法映射")
        return address

    def build_id(self):
        return self._build_id


def bl(pc, target):
    return 0x94000000 | (((target - pc) // 4) & 0x3FFFFFF)


def build_image():
    return FakeImage({SITE: bl(SITE, TARGET), TARGET: 0xD65F03C0})


def build_contract(image, sites=None):
    return {
        "schemaVersion": 1,
        "policy": POLICY,
        "installPermitted": False,
        "architecture": {"form": "B / BL", "immediateBits": 26},
        "image": {"sha256": digest(image.data), "buildId": image.build_id(),
                  "size": len(image.data)},
        "mapsRequirements": {"pageSize": 4096, "minimumHoleBytes": 32768,
                             "maximumIslandBytes": 4096, "holeAlignmentBytes": 16,
                             "packagePathSuffix": "demo.host"},
        "sites": sites if sites is not None else [{
            "feature": "示例", "offset": hex(SITE), "word": hex(bl(SITE, TARGET)),
            "kind": "BL", "target": hex(TARGET), "targetName": "demo.target"}],
        "notClosedGates": [
            "门槛1：蹦床发布次序仍须独立验收。",
            "门槛3：安全通道实机验收未做。",
            "门槛5：可达空洞不等于安装窗口内仍空闲。",
        ],
    }


def region(start, end, perms="---p", path=""):
    return "%x-%x %s 00000000 00:00 0 %s" % (start, end, perms, path)


def maps_text(regions):
    return "\n".join(regions) + "\n"


def default_regions():
    """宿主映射包住调用点，两侧各留一个可达空洞。"""
    site = BASE + SITE
    return [
        region(site - 0x80000, site - 0x60000, "r--p", "/data/app/demo.host/base.apk"),
        host_region(site - 0x1000, site + 0x1000),
        region(site + 0x40000, site + 0x50000, "r--p", "/data/app/demo.host/base.apk"),
    ]


def host_region(start, end, perms="r-xp", suffix="base.apk"):
    return region(start, end, perms, "/data/app/demo.host/" + suffix)


class EncoderTest(unittest.TestCase):
    def test_roundtrip_forward_backward_and_limit(self):
        for delta in (0, 4, -4, 0x1000, -0x1000, BRANCH_LIMIT - 4, -BRANCH_LIMIT):
            with self.subTest(delta=delta):
                target = SITE + delta
                word = encode_b(SITE, target)
                self.assertEqual(target, decode_b(word, SITE))
                self.assertEqual(target, decode_b(word | 0x80000000, SITE, link=True))

    def test_unaligned_or_out_of_range_is_refused(self):
        for delta in (2, BRANCH_LIMIT, -BRANCH_LIMIT - 4):
            with self.subTest(delta=delta), self.assertRaises(AuditError):
                encode_b(SITE, SITE + delta)

    def test_non_branch_word_is_refused(self):
        with self.assertRaises(AuditError):
            decode_b(0xD503201F, SITE)


class MapsTest(unittest.TestCase):
    def test_holes_between_regions(self):
        regions = parse_maps(maps_text(default_regions()))
        holes = find_holes(regions)
        self.assertTrue(holes)
        self.assertTrue(all(end > start for start, end in holes))

    def test_wx_segment_is_refused(self):
        regions = parse_maps(maps_text([
            region(BASE, BASE + 0x1000, "rwxp", "/dev/mem"),
            region(BASE + 0x2000, BASE + 0x9000),
        ]))
        with self.assertRaisesRegex(AuditError, "可写可执行"):
            verify_contract(FakeImage({}), build_contract(FakeImage({})), regions, BASE)

    def test_unaligned_or_overlapping_regions_are_refused(self):
        with self.assertRaisesRegex(AuditError, "未按页对齐"):
            verify_contract(FakeImage({}), build_contract(FakeImage({})),
                            parse_maps(maps_text([region(0x1001, 0x3000)])), BASE)
        with self.assertRaisesRegex(AuditError, "重叠"):
            parse_maps(maps_text([region(BASE, BASE + 0x3000),
                                  region(BASE + 0x2000, BASE + 0x4000)]))

    def test_unparsable_line_is_refused(self):
        with self.assertRaisesRegex(AuditError, "无法解析"):
            parse_maps("这不是一行合法的 maps\n")


class LandingContractTest(unittest.TestCase):
    def setUp(self):
        self.image = build_image()
        self.contract = build_contract(self.image)
        self.regions = parse_maps(maps_text(default_regions()))

    def verify(self, image=None, contract=None, regions=None):
        return verify_contract(image or self.image, contract or self.contract,
                              regions or self.regions, BASE)

    def test_valid_contract_passes_without_install_permission(self):
        result = self.verify()
        self.assertIs(result["安装许可"], False)
        self.assertEqual(1, result["调用点"])
        self.assertTrue(result["落点"])
        self.assertEqual(3, result["未关闭门槛"])

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

    def test_changed_site_word_is_rejected(self):
        broken = copy.deepcopy(self.contract)
        broken["sites"][0]["word"] = "0x14000000"
        with self.assertRaisesRegex(AuditError, "机器字"):
            self.verify(contract=broken)

    def test_site_must_be_a_bl_to_declared_target(self):
        image = FakeImage({SITE: 0x14000000, TARGET: 0xD65F03C0})
        broken = build_contract(image)
        broken["sites"][0]["word"] = "0x14000000"
        with self.assertRaisesRegex(AuditError, "BL"):
            self.verify(image=image, contract=broken)

    def test_missing_gate_is_rejected(self):
        broken = copy.deepcopy(self.contract)
        broken["notClosedGates"] = broken["notClosedGates"][:2]
        with self.assertRaisesRegex(AuditError, "门槛"):
            self.verify(contract=broken)

    def test_small_hole_is_rejected(self):
        # 两侧都有映射，只在调用点附近留出不足合同下限的空洞。
        site = BASE + SITE
        regions = parse_maps(maps_text([
            host_region(site - 0x100000, site + 0x1000),
            region(site + 0x5000, site + 0x9000, "r-xp", "/data/app/demo.host/base.apk"),
        ]))
        with self.assertRaisesRegex(AuditError, "可达空洞"):
            self.verify(regions=regions)

    def test_unreachable_hole_is_rejected(self):
        # 唯一空洞的起点已经在 26 位立即数范围之外：编码硬约束不允许。
        site = BASE + SITE
        fence = site + BRANCH_LIMIT
        regions = parse_maps(maps_text([
            host_region(site - 0x1000, fence),
            region(fence + 0x12000, fence + 0x2A000, "r-xp", "/system/lib64/libother.so"),
        ]))
        with self.assertRaisesRegex(AuditError, "可达空洞"):
            self.verify(regions=regions)

    def test_hole_that_cannot_reach_last_word_is_rejected(self):
        # 空洞起点仍在可达范围内，但末字已经越界：必须整体拒绝，而不是只看起点。
        site = BASE + SITE
        start = site + BRANCH_LIMIT - 0x1000
        broken = copy.deepcopy(self.contract)
        broken["mapsRequirements"]["maximumIslandBytes"] = 0x2000
        # 唯一的空洞从 start 起长 0x10000：满足最小长度，但蹦床末字越界。
        regions = parse_maps(maps_text([
            host_region(site - 0x1000, start),
            region(start + 0x10000, start + 0x11000, "r-xp", "/data/app/demo.host/base.apk"),
        ]))
        with self.assertRaisesRegex(AuditError, "可达空洞"):
            self.verify(contract=broken, regions=regions)

    def test_changed_host_digest_is_rejected(self):
        broken = copy.deepcopy(self.contract)
        broken["image"]["sha256"] = "0" * 64
        with self.assertRaisesRegex(AuditError, "宿主"):
            self.verify(contract=broken)

    def test_public_mapping_is_rejected(self):
        site = BASE + SITE
        # 站点落在 BASE+SITE，即第二个区间：把它设成共享映射（`s` 位）。
        regions = parse_maps(maps_text([
            host_region(site - 0x80000, site - 0x60000),
            host_region(site - 0x1000, site + 0x1000, perms="r-xs"),
            region(site + 0x40000, site + 0x50000, "r--p", "/data/app/demo.host/base.apk"),
        ]))
        with self.assertRaisesRegex(AuditError, "私有"):
            self.verify(regions=regions)

    def test_writable_site_mapping_is_rejected(self):
        site = BASE + SITE
        regions = parse_maps(maps_text([
            host_region(site - 0x1000, site + 0x1000, perms="rwxp"),
            region(site + 0x40000, site + 0x50000, "r--p", "/data/app/demo.host/base.apk"),
        ]))
        with self.assertRaisesRegex(AuditError, "可写可执行"):
            self.verify(regions=regions)

    def test_foreign_path_is_rejected(self):
        site = BASE + SITE
        regions = parse_maps(maps_text([
            region(site - 0x1000, site + 0x1000, "r-xp", "/system/lib64/libother.so"),
            region(site + 0x40000, site + 0x50000, "r--p", "/data/app/demo.host/base.apk"),
        ]))
        with self.assertRaisesRegex(AuditError, "宿主包"):
            self.verify(regions=regions)

    def test_non_executable_site_mapping_is_rejected(self):
        site = BASE + SITE
        regions = parse_maps(maps_text([
            host_region(site - 0x1000, site + 0x1000, perms="r--p"),
            region(site + 0x40000, site + 0x50000, "r--p", "/data/app/demo.host/base.apk"),
        ]))
        with self.assertRaisesRegex(AuditError, "可执行"):
            self.verify(regions=regions)

    def test_result_reports_three_encodings_per_site(self):
        result = self.verify()
        entry = result["落点"][0]["落点"][0]
        for key in ("跳入距离", "原目标距离", "返回距离"):
            self.assertIn(key, entry)
        self.assertEqual(1, len(result["调用点映射"]))
        self.assertEqual("r-xp", result["调用点映射"][0]["权限"])


if __name__ == "__main__":
    unittest.main()
