"""打包门禁的负向测试；真实 ELF 的导入导出由产物审计命令复核。"""

import io
import struct
import unittest
import warnings
import zipfile
from pathlib import Path
from unittest.mock import patch

from launcher_native_audit import AuditError
from launcher_native_package_audit import (BRIDGE, DESTRUCTIVE_IMPORTS, ENTRY, EXPORTS,
    FORBIDDEN_IMPORTS, IMPORTS, LAUNCHER_SCOPE, LIBRARY, PERSISTENCE_IMPORTS, PROTOTYPE_PREFIX,
    JAVA_SCOPES, MERGED_PROTOTYPE_SCOPES, PROTOTYPE_SCOPES, SCOPE, SYSTEM_SCOPE,
    verify_library, verify_package)
from test_launcher_native_audit import fixture


def package(entries, scope="com.android.systemui\n"):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        archive.writestr(SCOPE, scope)
        for name, data in entries:
            archive.writestr(name, data)
    output.seek(0)
    return output


def library(exports=EXPORTS, imports=IMPORTS, needed="libc.so"):
    data = bytearray(fixture(needed=needed))
    start = data.index(b".symtab\0")
    data[start:start + 8] = b".dynsym\0"
    entries = [(name, True) for name in sorted(exports)] + [(name, False) for name in sorted(imports)]
    strings = bytearray(b"\0")
    for index, (name, defined) in enumerate(entries, 1):
        name_offset = len(strings)
        strings.extend(name.encode() + b"\0")
        struct.pack_into("<IBBHQQ", data, 0x600 + index * 24, name_offset, 0x12, 0,
                         4 if defined else 0, 0x2000 if defined else 0, 4 if defined else 0)
    data[0x1000:0x1000 + len(strings)] = strings
    struct.pack_into("<Q", data, 0x1800 + 2 * 64 + 24, 0x1000)
    struct.pack_into("<Q", data, 0x1800 + 2 * 64 + 32, len(strings))
    struct.pack_into("<I", data, 0x1800 + 3 * 64 + 4, 11)
    struct.pack_into("<Q", data, 0x1800 + 3 * 64 + 32, (len(entries) + 1) * 24)
    return bytes(data)


class PackageAuditTest(unittest.TestCase):
    def test_directory_compatibility_only_adds_readonly_acl_import(self):
        from launcher_native_package_audit import DIRECTORY_READ_IMPORTS, DIRECTORY_WRITE_IMPORTS
        self.assertEqual({"fgetxattr"}, DIRECTORY_READ_IMPORTS)
        self.assertTrue(DIRECTORY_READ_IMPORTS <= IMPORTS)
        verify_library(library(imports=IMPORTS | DIRECTORY_READ_IMPORTS))
        for symbol in DIRECTORY_WRITE_IMPORTS:
            with self.subTest(symbol=symbol):
                self.assertNotIn(symbol, IMPORTS)
                self.assertIn(symbol, FORBIDDEN_IMPORTS)
                with self.assertRaises(AuditError):
                    verify_library(library(imports=IMPORTS | {symbol}))

    def prototype_entries(self):
        return [(ENTRY, LIBRARY + "\n"), ("lib/arm64-v8a/" + LIBRARY, "测试库".encode()),
                ("classes.dex", BRIDGE)]

    def test_regular_package_has_no_prototype(self):
        self.assertEqual("未打包", verify_package(package([]), False)["原型入口"])

    def test_regular_package_scopes_exclude_launcher(self):
        with self.assertRaises(AuditError):
            verify_package(package([], LAUNCHER_SCOPE + "\n"), False)

    def test_regular_package_allows_system_scope(self):
        self.assertEqual("未打包", verify_package(
            package([], SYSTEM_SCOPE + "\ncom.android.systemui\n"), False)["原型入口"])

    def test_each_prototype_component_is_forbidden_in_regular_package(self):
        for entry in self.prototype_entries():
            with self.assertRaises(AuditError):
                verify_package(package([entry]), False)

    def test_regular_package_also_rejects_configuration_or_trial_classes(self):
        for name in [b"LauncherConfiguration;", b"LauncherTrialGuard;"]:
            with self.assertRaises(AuditError):
                verify_package(package([("classes.dex", PROTOTYPE_PREFIX + name)]), False)

    def test_missing_prototype_component_is_rejected(self):
        entries = self.prototype_entries()
        for index in range(3):
            with self.assertRaises((AuditError, KeyError)):
                verify_package(package(entries[:index] + entries[index + 1:],
                                       "\n".join(MERGED_PROTOTYPE_SCOPES) + "\n"), True)

    def test_library_name_and_architecture_must_match(self):
        for entries in [[(ENTRY, "错误名称.so"), *self.prototype_entries()[1:]],
                        [*self.prototype_entries(), ("lib/x86_64/" + LIBRARY, b"")]]:
            with self.assertRaises(AuditError):
                verify_package(package(entries, "\n".join(MERGED_PROTOTYPE_SCOPES) + "\n"), True)

    def test_duplicate_zip_entry_is_rejected(self):
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            apk = package([("classes.dex", b""), ("classes.dex", b"")])
        with self.assertRaises(AuditError):
            verify_package(apk, False)

    def test_prototype_delegates_to_real_library_audit(self):
        with patch("launcher_native_package_audit.verify_library", return_value={"导出数": 4}) as library:
            self.assertEqual("诊断原型", verify_package(
                package(self.prototype_entries(), "\n".join(MERGED_PROTOTYPE_SCOPES) + "\n"), True)["构建"])
        library.assert_called_once()

    def test_prototype_scope_must_preserve_java_scope_and_append_launcher(self):
        self.assertEqual(["com.miui.home", "system"], PROTOTYPE_SCOPES)
        self.assertEqual(JAVA_SCOPES + ["com.miui.home"], MERGED_PROTOTYPE_SCOPES)
        expected_scope = "\n".join(MERGED_PROTOTYPE_SCOPES) + "\n"
        with patch("launcher_native_package_audit.verify_library", return_value={}) as audit:
            self.assertEqual("诊断原型", verify_package(
                package(self.prototype_entries(), expected_scope), True)["构建"])
            audit.assert_called_once()
            audit.reset_mock()
            cases = [
                ("空清单", ""),
                ("缺少 Java 作用域", "\n".join(JAVA_SCOPES[:-1] + [LAUNCHER_SCOPE]) + "\n"),
                ("缺少桌面", "\n".join(JAVA_SCOPES) + "\n"),
                ("只有 system", SYSTEM_SCOPE + "\n"),
                ("桌面加其他宿主", LAUNCHER_SCOPE + "\ncom.android.systemui\n"),
                ("只有其他宿主", "com.android.systemui\n"),
                ("多一个宿主", expected_scope + "com.android.systemui\n"),
                ("顺序颠倒", SYSTEM_SCOPE + "\n" + LAUNCHER_SCOPE + "\n"),
                ("重复桌面", expected_scope + LAUNCHER_SCOPE + "\n"),
                ("重复 system", expected_scope + SYSTEM_SCOPE + "\n"),
                ("开头空行", "\n" + expected_scope),
                ("中间空行", LAUNCHER_SCOPE + "\n\n" + SYSTEM_SCOPE + "\n"),
                ("末尾空行", expected_scope + "\n"),
                ("仅含空白的干扰行", LAUNCHER_SCOPE + "\n \t\n" + SYSTEM_SCOPE + "\n"),
            ]
            for label, scope in cases:
                with self.subTest(场景=label), self.assertRaisesRegex(
                        AuditError, "必须是正式 Java 作用域并追加桌面，顺序固定且不得重复"):
                    verify_package(package(self.prototype_entries(), scope), True)
            audit.assert_not_called()

    def test_exact_diagnostic_exports_and_imports(self):
        self.assertEqual({"导出数": len(EXPORTS), "导入数": len(IMPORTS)}, verify_library(library()))

    def test_unreviewed_import_name_is_reported(self):
        with self.assertRaisesRegex(AuditError, r"\['unreviewed_symbol'\]"):
            verify_library(library(imports=IMPORTS | {"unreviewed_symbol"}))

    def test_persistence_imports_are_accepted_but_stay_bounded(self):
        # 接线 safety_startup.cpp 后新增的合法导入：真实 NDK 产物实测得到的 7 个符号，
        # 必须被接受（否则接线后的产物会被自己拒绝），且不得被顺手扩大。
        self.assertEqual({"write", "__write_chk", "fsync", "flock", "renameat", "fstatat", "__errno"},
                         PERSISTENCE_IMPORTS)
        self.assertTrue(PERSISTENCE_IMPORTS <= IMPORTS)
        self.assertTrue(PERSISTENCE_IMPORTS.isdisjoint(FORBIDDEN_IMPORTS))
        for symbol in PERSISTENCE_IMPORTS:
            verify_library(library(imports=IMPORTS | {symbol}))

    def test_destructive_writes_outside_atomic_replace_are_rejected(self):
        # 持久化后端只做「临时文件 + renameat」发布，绝不删除或截断已核对的文件；
        # 越界到删除/截断的符号必须硬拒，而不是悄悄放宽。
        for symbol in sorted(DESTRUCTIVE_IMPORTS):
            self.assertNotIn(symbol, IMPORTS)
            self.assertIn(symbol, FORBIDDEN_IMPORTS)
            with self.assertRaises(AuditError):
                verify_library(library(imports=IMPORTS | {symbol}))

    def test_missing_or_extra_exports_rejected(self):
        for exports in [EXPORTS - {"native_init"}, EXPORTS | {"nativeEnableHooks"}]:
            with self.assertRaises(AuditError):
                verify_library(library(exports=exports))

    def test_load_time_constructor_section_is_rejected(self):
        data = bytearray(library())
        start = data.index(b".note.gnu.build-id\0")
        data[start:start + 12] = b".init_array\0"
        with self.assertRaises(AuditError):
            verify_library(bytes(data))

    def test_wait_loader_or_patch_imports_rejected(self):
        # write/fsync/flock/renameat/fstatat/__errno + mmap/mprotect/munmap 已随接线进入白名单，改由正向用例覆盖；
        # 这里只保留仍然禁止的等待线程、其余内存改写、加载器与属性探测符号。
        for name in ["pthread_join", "pthread_cancel",
                     "pwrite", "system", "__system_property_get", "getauxval"]:
            with self.assertRaises(AuditError):
                verify_library(library(imports=IMPORTS | {name}))
        with self.assertRaises(AuditError):
            verify_library(library(imports=IMPORTS - {"getpid"}))
        self.assertIn("dlopen", IMPORTS)
        native_entry = Path(__file__).resolve().parents[1] / "app/src/main/cpp/launcher/native_entry.cpp"
        source = native_entry.read_text(encoding="utf-8")
        self.assertEqual(1, source.count('dlopen("libbinder_ndk.so"'))

    def test_trampoline_page_imports_are_bounded(self):
        # 线 A 第一增量：蹦床页分配与封页仅 mmap/mprotect/munmap 三项入白名单，且不得扩大到其它越界符号。
        from launcher_native_package_audit import TRAMPOLINE_IMPORTS
        self.assertEqual({"mmap", "mprotect", "munmap"}, TRAMPOLINE_IMPORTS)
        self.assertTrue(TRAMPOLINE_IMPORTS <= IMPORTS)
        self.assertTrue(TRAMPOLINE_IMPORTS.isdisjoint(FORBIDDEN_IMPORTS))
        for symbol in TRAMPOLINE_IMPORTS:
            verify_library(library(imports=IMPORTS | {symbol}))

    def test_guarded_static_initialization_is_rejected(self):
        # 类初始化缓存必须是手工无锁原子；一旦有人写成函数内 static，
        # 编译器会引入 __cxa_guard_*，必须被门禁拒绝而不是悄悄放行。
        for name in ["__cxa_guard_acquire", "__cxa_guard_release", "__cxa_guard_abort"]:
            with self.assertRaises(AuditError):
                verify_library(library(imports=IMPORTS | {name}))

    def test_linking_binder_is_rejected(self):
        # binder 只能经 dlsym 使用；把它链成 DT_NEEDED 必须被拒绝。
        for name in ["libbinder_ndk.so", "libbinder.so"]:
            with self.assertRaises(AuditError):
                verify_library(library(needed=name))
        for name in ["libm.so", "libdl.so", "libc.so", "liblog.so"]:
            verify_library(library(needed=name))


if __name__ == "__main__":
    unittest.main()
