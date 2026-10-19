"""启动持久化与安装边界的回归，不把内存文件系统当成实机持久化。"""

import subprocess
import shutil
import re
import tempfile
import unittest
from pathlib import Path

from launcher_diagnostic_emulate import run_case, trial_entry
from launcher_native_audit import AuditError, ElfImage
from launcher_native_package_audit import (DYNAMIC_IMPORTS, FORBIDDEN_IMPORTS, IMPORTS,
    PERSISTENCE_IMPORTS, TRAMPOLINE_IMPORTS)
from launcher_safety_parity_audit import code_only, find_clang, hook_call_sites, strip_comments
from launcher_safety_startup_emulate import CASES, ENTRY


ROOT = Path(__file__).resolve().parents[1]


class SafetyStartupBoundaryTest(unittest.TestCase):
    def test_writer_is_linked_and_install_point_follows_plan_gate(self):
        # 启动后端与唯一安装点已接线；计划不完整必须早退，不能用占位实参伪造安装。
        from launcher_safety_parity_audit import function_spans, reachable_functions

        cmake = (ROOT / "app/src/main/cpp/CMakeLists.txt").read_text(encoding="utf-8")
        code = "\n".join(line for line in cmake.splitlines() if not line.lstrip().startswith("#"))
        self.assertIn("launcher/safety_startup.cpp", code)
        self.assertNotIn("GLOB", code)
        entry = (ROOT / "app/src/main/cpp/launcher/native_entry.cpp").read_text(encoding="utf-8")
        self.assertIn('#include "safety_startup.h"', strip_comments(entry))
        entry_code = code_only(entry)
        self.assertRegex(entry_code, r"\blauncher::commit_safety_startup_once\s*\(")
        reachable, bodies = reachable_functions(function_spans(entry_code),
                                                {"native_init", "JNI_OnLoad"})
        self.assertIn("guarded_install_once", reachable)
        self.assertIn("launcher::commit_safety_startup_once", bodies["guarded_install_once"])
        direct, aliased = hook_call_sites(entry_code)
        self.assertEqual(1, len(direct))
        self.assertEqual([], aliased)
        self.assertNotRegex(entry_code, r"\bhook_func\s*\(\s*nullptr")
        self.assertIn("if (!resolve_launcher_install_plan(plan))", entry_code)
        self.assertIn("plan.hook_func(plan.target, plan.replacement, plan.backup_slot)", entry_code)
        self.assertIn("kPreflightUnsupportedFeature", entry_code)
        self.assertIn(
            "if (!launcher::snapshot_features_supported_by_production(snapshot.features))",
            entry_code,
        )
        self.assertIn("launcher_shortcut_count_state", entry_code)
        self.assertIn("launcher::shortcut_count_state(", entry_code)
        self.assertIn("g_hook_install_state", entry_code)
        self.assertIn("launcher::claim_install_once(&g_hook_install_state)", entry_code)
        self.assertIn("launcher::finish_install(&g_hook_install_state, true)", entry_code)
        self.assertIn("kPreflightPersistencePending", entry_code)
        self.assertIn("if (!result.committed) return kPreflightPersistencePending;", entry_code)

    def test_install_permit_uses_live_evidence_and_experimental_switch(self):
        # 版本号与待验收阶段锁均已删除；普通构建只由显式实验开关保持关闭。
        permit = (ROOT / "app/src/main/cpp/launcher/install_permit.h").read_text(encoding="utf-8")
        self.assertNotIn("kPendingAcceptanceBlockers", permit)
        self.assertNotIn("kSupportedNativeAbiVersion", permit)
        self.assertIn("constexpr bool kExperimentalInstallEnabled = false;", permit)
        self.assertNotIn("abi_version", permit)
        self.assertIn("return permit_blockers(evidence) == 0;", permit)

    def test_package_imports_allow_only_the_audited_persistence_symbols(self):
        # 接线后允许的写侧导入必须精确等于实测得到的集合，不能顺手放宽。
        self.assertEqual({"write", "__write_chk", "fsync", "flock", "renameat", "fstatat", "__errno"},
                         PERSISTENCE_IMPORTS)
        self.assertTrue(PERSISTENCE_IMPORTS <= IMPORTS)
        # 白名单没有的写侧符号一律按「未审核导入」拒绝。
        for symbol in ("pwrite", "writev", "fdatasync"):
            self.assertNotIn(symbol, IMPORTS)
        # 原子替换之外的破坏性写与等待线程必须硬拒；
        # 蹦床页分配与封页的三项（mmap/mprotect/munmap）已随线 A 第一增量转入
        # TRAMPOLINE_IMPORTS 精确管控，不再按破坏性写拒绝。
        for symbol in ("unlink", "unlinkat", "ftruncate", "truncate",
                       "pthread_join"):
            self.assertNotIn(symbol, IMPORTS)
            self.assertIn(symbol, FORBIDDEN_IMPORTS)
        self.assertEqual({"dlopen"}, DYNAMIC_IMPORTS)
        self.assertEqual({"mmap", "mprotect", "munmap"}, TRAMPOLINE_IMPORTS)
        self.assertTrue(TRAMPOLINE_IMPORTS <= IMPORTS)
        self.assertTrue(TRAMPOLINE_IMPORTS.isdisjoint(FORBIDDEN_IMPORTS))

    def test_fault_cases_are_not_silently_removed(self):
        self.assertEqual(set(range(85)), set(CASES))
        self.assertEqual(85, len(set(CASES.values())))

    def test_reader_and_writer_use_the_same_state_file(self):
        entry = (ROOT / "app/src/main/cpp/launcher/native_entry.cpp").read_text(encoding="utf-8")
        writer = (ROOT / "app/src/main/cpp/launcher/safety_startup.cpp").read_text(encoding="utf-8")
        reader_names = re.findall(r'openat\(files_fd,\s*"([^"]+)"', entry)
        writer_names = re.findall(r'kStateName\[\]\s*=\s*"([^"]+)"', writer)
        self.assertEqual(["hyperextend_launcher_bootguard"], reader_names)
        self.assertEqual(reader_names, writer_names)
        self.assertIn("openat(user_fd, kLauncherSafetyScope,", writer)


class SafetyStartupExecutionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        clang = find_clang(None, ROOT)
        if clang is None:
            raise unittest.SkipTest("缺少 NDK，持久化行为未执行，不能算验证通过")
        cls.temporary = tempfile.TemporaryDirectory(prefix="launcher-safety-startup-")
        cls.addClassCleanup(cls.temporary.cleanup)
        target = Path(cls.temporary.name) / "trial.so"
        cls.command = [
            str(clang), "--target=aarch64-linux-android26", "-std=c++17", "-O1",
            "-Wall", "-Wextra", "-Werror", "-fno-exceptions", "-fno-rtti",
            "-fno-stack-protector", "-fvisibility=hidden", "-mno-outline-atomics",
            "-fno-builtin", "-U_FORTIFY_SOURCE", "-D_FORTIFY_SOURCE=0",
            "-shared", "-nostdlib", "-Wl,-Bsymbolic", "-Wl,--no-undefined",
            str(ROOT / "app/src/test/cpp/launcher_safety_startup_trial.cpp"), "-o", str(target),
        ]
        build = subprocess.run(cls.command, capture_output=True, check=False)
        if build.returncode != 0:
            raise AssertionError(build.stderr.decode("utf-8", errors="replace"))
        cls.image = ElfImage(target.read_bytes())
        cls.entry = trial_entry(cls.image, ENTRY)

    def test_real_backend_against_all_faults(self):
        for case, name in CASES.items():
            with self.subTest(case=case, name=name):
                run_case(self.image, self.entry, case, CASES)

    def test_bypass_mutations_are_caught_by_executed_trial(self):
        """临时副本注入错误，要求真实执行失败；不改工作区，也不接受编译失败冒充检出。"""
        mutations = [
            ("阈值早退丢失锁存", "if (!decision.ok) return kStartupFuseRejected;",
             "if (!safety_permits_install(decision)) return kStartupFuseRejected;", [1]),
            ("跳过发布后目录同步", "if (fsync(directory) != 0) return kStartupDirectorySyncFailed;",
             "if (false) return kStartupDirectorySyncFailed;", [0, 32]),
            ("每次重置进程记录", "uint32_t owner = 0;",
             "__atomic_store_n(&g_startup_owner, 0, __ATOMIC_RELEASE);\n    uint32_t owner = 0;", [51, 52]),
            ("跳过清理后租期复核", "result.blockers |= request_errors(context, snapshot, last_revision);",
             "result.blockers |= 0;", [76]),
            ("启动事务退回形态校验", "ordinary_snapshot_errors(snapshot, revision,",
             "snapshot_errors(snapshot, revision,", [3, 79, 80, 81, 82]),
        ]
        base = Path(self.temporary.name) / "mutations"
        native = base / "app/src/main/cpp/launcher"
        tests = base / "app/src/test/cpp"
        native.mkdir(parents=True)
        tests.mkdir(parents=True)
        for header in (ROOT / "app/src/main/cpp/launcher").glob("*.h"):
            shutil.copyfile(header, native / header.name)
        trial = tests / "launcher_safety_startup_trial.cpp"
        shutil.copyfile(ROOT / "app/src/test/cpp/launcher_safety_startup_trial.cpp", trial)
        original = (ROOT / "app/src/main/cpp/launcher/safety_startup.cpp").read_text(encoding="utf-8")
        for label, before, after, cases in mutations:
            with self.subTest(mutation=label):
                self.assertEqual(1, original.count(before))
                (native / "safety_startup.cpp").write_text(original.replace(before, after), encoding="utf-8")
                command = list(self.command)
                command[-3] = str(trial)
                command[-1] = str(base / "mutated.so")
                build = subprocess.run(command, capture_output=True, check=False)
                self.assertEqual(0, build.returncode, build.stderr.decode("utf-8", errors="replace"))
                image = ElfImage((base / "mutated.so").read_bytes())
                entry = trial_entry(image, ENTRY)
                for case in cases:
                    with self.subTest(case=case), self.assertRaisesRegex(AuditError, "失败"):
                        run_case(image, entry, case, CASES)


if __name__ == "__main__":
    unittest.main()
