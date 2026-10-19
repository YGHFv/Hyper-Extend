"""门槛 5 三项残余审计器的合成用例。

真实宿主、真实映射快照与真实 NDK 工具链只由审计命令显式验证，不以跳过代替通过。
这里用可控的合成映像、合成映射与合成机器字覆盖正向通过路径与各类拒绝路径。
"""

import copy
import struct
import unittest
from pathlib import Path

from launcher_gate5_residual_audit import (POLICY, any_data_store, commit_store, dc_kind,
                                           ic_kind, parse_maps, verify_commit_symbol,
                                           verify_host_lifecycle, verify_page_properties,
                                           verify_runtime_mappings, verify_site_words,
                                           verify_sync_symbol,
                                           DYNAMIC_TAGS)
from launcher_gate5_residual_audit import (DEFAULT_TRIAL, ROOT, verify_no_product_write_path,
                                           verify_trial_source)
from launcher_native_audit import AuditError, digest


BASE = 0x10000000
PAGE = 0x1000
RET = 0xD65F03C0
DSB_ISH = 0xD5033B9F
DC_CVAU = 0xD50B7B29
IC_IVAU = 0xD50B7520
STLR_W = 0x889FFC01
STP = 0xA9BF7BFD


class FakeImage:
    """只暴露 verify_host_lifecycle 需要的接口。"""

    def __init__(self, segments, dynamic):
        self.segments = segments
        self._dynamic = dynamic

    def read(self, offset, size):
        return self._dynamic

    def build_id(self):
        return "deadbeef"

    def file_offset(self, address, size, executable=False):
        for item in self.segments:
            if (item["type"] == 1 and item["address"] <= address
                    and address + size <= item["address"] + item["size"]
                    and (not executable or item["flags"] & 1)):
                return item["offset"] + address - item["address"]
        raise AuditError("合成映像缺少文件映射")


class CodeImage:
    """只暴露 verify_site_words 需要的接口。"""

    def __init__(self, words):
        self.words = words

    def code(self, address, size):
        if address % 4 or size != 4 or address not in self.words:
            raise AuditError("合成映像缺少该调用点")
        return struct.pack("<I", self.words[address])


def bl(pc, target):
    return 0x94000000 | (((target - pc) // 4) & 0x03FFFFFF)


SITE = 0x850100
TARGET = 0x850400


def site_record():
    return [dict(feature="示例", offset=hex(SITE), word=hex(bl(SITE, TARGET)), target=hex(TARGET))]


def segment(typ, flags, offset, address, size):
    return dict(type=typ, flags=flags, offset=offset, address=address, size=size,
                memory=size)


def dynamic_blob(tags):
    raw = bytearray()
    for tag, value in tags:
        raw += struct.pack("<QQ", tag, value)
    raw += struct.pack("<QQ", 0, 0)
    return bytes(raw)


def region(start, end, perms="r-xp", path="", inode="0", offset=0, dev=None):
    if dev is None:
        dev = "00:00" if inode == "0" else "fe:34"
    return "%x-%x %s %x %s %s %s" % (start, end, perms, offset, dev, inode, path)


def maps_text(rows):
    return "\n".join(rows) + "\n"


HOST_SEGMENTS = [
    segment(6, 4, 0x40, 0x40, 0x188),
    segment(1, 4, 0x0, 0x0, 0x84C3F2),
    segment(1, 5, 0x850000, 0x850000, 0x130CCF0),
    segment(1, 6, 0x1B60000, 0x1B60000, 0x90),
    segment(2, 6, 0x1B60000, 0x1B60000, 0x60),
]


def host_dynamic():
    return dynamic_blob([(0x4, 0x300), (0x5, 0x1E8), (0xA, 0x85), (0x6, 0x270),
                         (0xB, 0x18)])


def default_regions(base=BASE):
    """宿主 RX 页紧邻 RW 页（同一模块），调用点落在代码页里。"""
    code = base + 0x850000
    frames = [
        region(base + 0x0, base + 0x84C3F2, "r--p", "/data/app/demo.host/base.apk", "77", 0),
        region(code, code + 0x130CF0, "r-xp", "/data/app/demo.host/base.apk", "77", 0x850000),
        region(code + 0x130CF0, code + 0x131000, "rw-p", "/data/app/demo.host/base.apk", "77", 0x1B60CF0),
        region(base + 0x2000000, base + 0x2001000, "rw-p", "/dev/zero (deleted)", "3371", 0, "00:01"),
    ]
    return parse_maps(maps_text(frames))


def requirements():
    return {"pageSize": PAGE, "packagePathSuffix": "demo.host"}


def sites():
    return [dict(feature="示例", offset=hex(0x850100))]


def runtime_sites(base=BASE):
    return [dict(feature="示例", offset=hex(0x850100), runtime=base + 0x850100)]


def runtime_mappings(regions, requirements, sites, base):
    return verify_runtime_mappings(regions, requirements, sites, base,
                                   FakeImage(HOST_SEGMENTS, host_dynamic()), 0)


class MaskTest(unittest.TestCase):
    def test_single_word_stores_are_recognised(self):
        for word, name in ((STLR_W, "stlr w"), (0xB9000020, "str w"), (0xB8000020, "stur w")):
            with self.subTest(word=hex(word)):
                self.assertEqual(name, commit_store(word)["指令"])
                self.assertTrue(any_data_store(word))

    def test_multiword_store_is_not_a_single_word_commit(self):
        self.assertIsNone(commit_store(STP))
        self.assertEqual("stp/stnp", any_data_store(STP))

    def test_cache_and_sync_instructions(self):
        self.assertEqual("dc cvau", dc_kind(DC_CVAU))
        self.assertEqual("ic ivau", ic_kind(IC_IVAU))
        self.assertIsNone(dc_kind(DSB_ISH))
        self.assertIsNone(any_data_store(DSB_ISH))


class SiteWordTest(unittest.TestCase):
    def test_matching_bl_passes(self):
        result = verify_site_words(CodeImage({SITE: bl(SITE, TARGET)}), site_record())
        self.assertEqual(1, len(result))
        self.assertEqual(hex(TARGET), result[0]["目标"])

    def test_changed_word_is_rejected(self):
        with self.assertRaisesRegex(AuditError, "机器字"):
            verify_site_words(CodeImage({SITE: bl(SITE, TARGET) + 4}), site_record())

    def test_non_bl_word_is_rejected(self):
        rows = [dict(feature="示例", offset=hex(SITE), word=hex(0x14000001), target=hex(SITE + 4))]
        with self.assertRaisesRegex(AuditError, "不是 BL"):
            verify_site_words(CodeImage({SITE: 0x14000001}), rows)

    def test_wrong_target_is_rejected(self):
        rows = [dict(feature="示例", offset=hex(SITE), word=hex(bl(SITE, TARGET)),
                     target=hex(TARGET + 4))]
        with self.assertRaisesRegex(AuditError, "目标"):
            verify_site_words(CodeImage({SITE: bl(SITE, TARGET)}), rows)

    def test_duplicate_site_is_rejected(self):
        rows = site_record() + site_record()
        with self.assertRaisesRegex(AuditError, "重复"):
            verify_site_words(CodeImage({SITE: bl(SITE, TARGET)}), rows)


class CommitSymbolTest(unittest.TestCase):
    def test_single_store_plus_ret_passes(self):
        result = verify_commit_symbol([STLR_W, RET], "launcher_commit_call_site")
        self.assertEqual("stlr w", result["存储"])
        self.assertEqual(2, result["指令数"])

    def test_extra_instructions_are_rejected(self):
        with self.assertRaisesRegex(AuditError, "恰好两条"):
            verify_commit_symbol([STLR_W, 0xD503201F, RET], "launcher_commit_call_site")

    def test_multiword_store_is_rejected(self):
        with self.assertRaisesRegex(AuditError, "多字覆盖"):
            verify_commit_symbol([STP, RET], "launcher_commit_call_site")


class SyncSymbolTest(unittest.TestCase):
    def test_ordered_sequence_passes(self):
        words = [0xD50B7B29, 0xD5033B9F, IC_IVAU, DSB_ISH, RET]
        result = verify_sync_symbol(words, "launcher_publish_trampoline")
        self.assertEqual("dc → dsb ish → ic → dsb ish", result["顺序"])

    def test_missing_barrier_is_rejected(self):
        with self.assertRaisesRegex(AuditError, "缺少 ic ivau"):
            verify_sync_symbol([DC_CVAU, IC_IVAU, DSB_ISH, RET], "launcher_publish_trampoline")

    def test_store_inside_sync_is_rejected(self):
        with self.assertRaisesRegex(AuditError, "数据存储"):
            verify_sync_symbol([DC_CVAU, DSB_ISH, IC_IVAU, DSB_ISH, STLR_W, RET],
                               "launcher_publish_trampoline")

    def test_reversed_order_is_rejected(self):
        with self.assertRaisesRegex(AuditError, "缺少 ic ivau"):
            verify_sync_symbol([IC_IVAU, DSB_ISH, DC_CVAU, DSB_ISH, RET],
                               "launcher_publish_trampoline")


class HostLifecycleTest(unittest.TestCase):
    def test_clean_host_passes(self):
        image = FakeImage(HOST_SEGMENTS, host_dynamic())
        result = verify_host_lifecycle(image, {}, sites())
        self.assertEqual(0, result["可写可执行 PT_LOAD"])
        self.assertEqual([], result["改址与卸载钩子"])

    def test_writable_executable_load_is_rejected(self):
        segments = [segment(1, 7, 0x850000, 0x850000, 0x1000)]
        image = FakeImage(segments, host_dynamic())
        with self.assertRaisesRegex(AuditError, "可写可执行"):
            verify_host_lifecycle(image, {}, sites())

    def test_unload_hook_tags_are_rejected(self):
        for tag in (0x1, 0xC, 0xD, 0x19, 0x1B, 0x7, 0x11, 0x17, 0x3):
            with self.subTest(tag=tag):
                blob = dynamic_blob([(0x5, 0x1E8), (0x6, 0x270), (tag, 0)])
                image = FakeImage(HOST_SEGMENTS, blob)
                with self.assertRaisesRegex(AuditError, "卸载期钩子|改址"):
                    verify_host_lifecycle(image, {}, sites())
        self.assertIn(1, DYNAMIC_TAGS)
        self.assertIn(0x19, DYNAMIC_TAGS)

    def test_site_in_writable_segment_is_rejected(self):
        segments = [segment(1, 6, 0x0, 0x0, 0x1B70000),
                    segment(2, 6, 0x1B60000, 0x1B60000, 0x60)]
        image = FakeImage(segments, host_dynamic())
        with self.assertRaisesRegex(AuditError, "不可执行"):
            verify_host_lifecycle(image, {}, sites())

    def test_site_outside_segments_is_rejected(self):
        image = FakeImage(HOST_SEGMENTS, host_dynamic())
        with self.assertRaisesRegex(AuditError, "不在任何 PT_LOAD"):
            verify_host_lifecycle(image, {}, [dict(feature="示例", offset=hex(0x1C00000))])


class PagePropertyTest(unittest.TestCase):
    def test_clean_snapshot_passes(self):
        result = verify_page_properties(default_regions(), requirements(), runtime_sites())
        self.assertEqual(0, result["W+X 段数"])
        self.assertEqual(1, len(result["调用点写入独占"]))
        self.assertIn("base.apk", result["页属性先例"]["来源"])

    def test_wx_region_is_rejected(self):
        rows = [region(BASE, BASE + 0x1000, "rwxp")]
        with self.assertRaisesRegex(AuditError, "可写可执行"):
            verify_page_properties(parse_maps(maps_text(rows)), requirements(), runtime_sites())

    def test_writable_site_page_is_rejected(self):
        rows = [region(BASE + 0x850000, BASE + 0x851000, "rwxp"),
                region(BASE + 0x851000, BASE + 0x852000, "rw-p")]
        with self.assertRaisesRegex(AuditError, "可写可执行"):
            verify_page_properties(parse_maps(maps_text(rows)), requirements(), runtime_sites())

    def test_missing_page_attribute_precedent_is_rejected(self):
        rows = [region(BASE + 0x850000, BASE + 0x851000, "r-xp", "/data/app/demo.host/base.apk", "77",
                       0x850000)]
        with self.assertRaisesRegex(AuditError, "先例"):
            verify_page_properties(parse_maps(maps_text(rows)), requirements(), runtime_sites())


class RuntimeMappingTest(unittest.TestCase):
    def test_single_host_image_passes(self):
        observed, delta = runtime_mappings(default_regions(), requirements(), sites(), BASE)
        self.assertEqual(1, len(observed))
        self.assertEqual(0, delta)

    def test_suffix_mismatch_is_rejected(self):
        rows = [region(BASE + 0x850000, BASE + 0x851000, "r-xp", "/system/lib64/libother.so", "9", 0x850000)]
        with self.assertRaisesRegex(AuditError, "宿主包"):
            runtime_mappings(parse_maps(maps_text(rows)), requirements(), sites(), BASE)

    def test_shared_mapping_is_rejected(self):
        rows = [region(BASE + 0x850000, BASE + 0x851000, "r-xs", "/data/app/demo.host/base.apk",
                       "77", 0x850000)]
        with self.assertRaisesRegex(AuditError, "私有"):
            runtime_mappings(parse_maps(maps_text(rows)), requirements(), sites(), BASE)

    def test_inconsistent_delta_is_rejected(self):
        sites = [dict(feature="甲", offset=hex(0x850100)), dict(feature="乙", offset=hex(0x900100))]
        rows = [region(BASE + 0x850000, BASE + 0x851000, "r-xp", "/data/app/demo.host/base.apk",
                       "77", 0x850000),
                region(BASE + 0x900000, BASE + 0x901000, "r-xp", "/data/app/demo.host/base.apk",
                       "77", 0x8F0000)]
        with self.assertRaisesRegex(AuditError, "不一致|同一个宿主映像"):
            runtime_mappings(parse_maps(maps_text(rows)), requirements(), sites, BASE)

    def test_multiple_images_is_rejected(self):
        sites = [dict(feature="甲", offset=hex(0x850100)), dict(feature="乙", offset=hex(0x860100))]
        rows = [region(BASE + 0x850000, BASE + 0x851000, "r-xp", "/data/app/demo.host/base.apk",
                       "77", 0x850000),
                region(BASE + 0x860000, BASE + 0x861000, "r-xp", "/data/app/other.host/base.apk",
                       "88", 0x860000)]
        with self.assertRaisesRegex(AuditError, "宿主包"):
            runtime_mappings(parse_maps(maps_text(rows)), requirements(), sites, BASE)


class ParseMapsTest(unittest.TestCase):
    def test_anonymous_inode_is_rejected(self):
        with self.assertRaisesRegex(AuditError, "匿名/文件属性"):
            parse_maps("1000-2000 rw-p 0 00:00 42 [anon:x]\n")

    def test_overlap_is_rejected(self):
        with self.assertRaisesRegex(AuditError, "重叠"):
            parse_maps("1000-3000 r-xp 0 fe:34 1 /a\n2000-4000 rw-p 0 fe:34 1 /a\n")

    def test_unparsable_line_is_rejected(self):
        with self.assertRaisesRegex(AuditError, "无法解析"):
            parse_maps("这是一行非法映射\n")


class TrialSourceTest(unittest.TestCase):
    """残余项 (c) 的证据来源必须锁定在生产实现上，不能换回测试替身。"""

    def contract(self):
        return {"trialSource": DEFAULT_TRIAL.relative_to(ROOT).as_posix()}

    def test_production_source_passes(self):
        self.assertEqual(DEFAULT_TRIAL.relative_to(ROOT).as_posix(),
                         verify_trial_source(self.contract(), DEFAULT_TRIAL))

    def test_declared_path_must_match(self):
        # 把合同改回测试目录里的等价写法：即使文件确实存在也必须失败。
        with self.assertRaisesRegex(AuditError, "trialSource"):
            verify_trial_source({"trialSource": "app/src/test/cpp/launcher_singleword_commit_trial.cpp"},
                                DEFAULT_TRIAL)

    def test_missing_declared_path_is_rejected(self):
        with self.assertRaisesRegex(AuditError, "trialSource"):
            verify_trial_source({}, DEFAULT_TRIAL)

    def test_substituted_file_is_rejected(self):
        # 合同没变，但命令行把它指向另一份文件（例如某个替身）也必须失败。
        with self.assertRaisesRegex(AuditError, "不是合同固定"):
            verify_trial_source(self.contract(), ROOT / "app/src/test/cpp/launcher_singleword_commit_faults.cpp")

    def test_test_directory_default_is_rejected(self):
        """兜底：万一将来有人把 DEFAULT_TRIAL 挪进测试目录，第三条断言必须拦下。"""
        import launcher_gate5_residual_audit as audit
        substitute = ROOT / "app/src/test/cpp/whatever.cpp"
        original = audit.DEFAULT_TRIAL
        audit.DEFAULT_TRIAL = substitute
        try:
            with self.assertRaisesRegex(AuditError, "替身"):
                verify_trial_source({"trialSource": "app/src/test/cpp/whatever.cpp"}, substitute)
        finally:
            audit.DEFAULT_TRIAL = original


class TrialSymbolNamespaceTest(unittest.TestCase):
    """合同里的三个试验符号必须与生产实现的导出名逐字一致。"""

    def test_contract_symbols_match_source(self):
        import json
        contract = json.loads((ROOT / "app/src/test/resources/launcher-gate5-residual-contract.json")
                              .read_text(encoding="utf-8"))
        source = (ROOT / "app/src/main/cpp/launcher/singleword_publish.cpp").read_text(encoding="utf-8")
        self.assertEqual(["launcher_commit_call_site", "launcher_publish_trampoline",
                          "launcher_publish_then_commit"], contract["trialSymbols"])
        for symbol in contract["trialSymbols"]:
            self.assertIn(" " + symbol + "(", source, "%s 未在生产实现里定义" % symbol)


class ProductWritePathTest(unittest.TestCase):
    """安装路径尚未验收时，代码页写入实现不得进入任何可执行构建目标。"""

    def test_current_cmake_is_clean(self):
        report = verify_no_product_write_path()
        self.assertEqual(0, report["命中"])

    def test_real_cmake_does_not_reference_implementation(self):
        # 直接对真实 CMakeLists 断言，避免上面的函数被误改成空实现也能通过。
        text = (ROOT / "app/src/main/cpp/CMakeLists.txt").read_text(encoding="utf-8")
        executable = [line for line in text.splitlines() if not line.lstrip().startswith("#")]
        self.assertTrue(any("singleword_publish.cpp" in line for line in executable))
        self.assertTrue(any("count_backend.cpp" in line for line in executable))

    def test_unrelated_target_reference_is_rejected(self):
        import tempfile
        with tempfile.TemporaryDirectory() as folder:
            fake = Path(folder) / "CMakeLists.txt"
            fake.write_text("add_library(HyperExtendLauncherNative SHARED\n"
                            "    launcher/count_backend.cpp\n"
                            "    launcher/singleword_publish.cpp\n"
                            ")\n"
                            "add_library(x OBJECT launcher/singleword_publish.cpp)\n", encoding="utf-8")
            with self.assertRaisesRegex(AuditError, "未知目标"):
                verify_no_product_write_path(fake)

    def test_multiline_production_target_is_recognized(self):
        import tempfile
        with tempfile.TemporaryDirectory() as folder:
            fake = Path(folder) / "CMakeLists.txt"
            fake.write_text("add_library( HyperExtendLauncherNative SHARED\n"
                            "    launcher/native_entry.cpp\n"
                            "    launcher/safety_startup.cpp\n"
                            "    launcher/count_backend.cpp\n"
                            "    launcher/singleword_publish.cpp\n"
                            ")\n", encoding="utf-8")
            report = verify_no_product_write_path(fake)
            self.assertEqual(0, report["命中"])

    def test_comment_only_reference_is_rejected(self):
        import tempfile
        with tempfile.TemporaryDirectory() as folder:
            fake = Path(folder) / "CMakeLists.txt"
            fake.write_text("# 注意 launcher/singleword_publish.cpp 不在这里编译\n", encoding="utf-8")
            with self.assertRaisesRegex(AuditError, "生产目标未接入单字提交实现"):
                verify_no_product_write_path(fake)


if __name__ == "__main__":
    unittest.main()
