"""维护事务的真实 ARM64 执行；授权方和文件系统均为替身，不是生产授权或实机验收。"""

import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from launcher_diagnostic_emulate import run_case, trial_entry
from launcher_native_audit import AuditError, ElfImage
from launcher_safety_parity_audit import find_clang


ROOT = Path(__file__).resolve().parents[1]
ENTRY = "launcher_safety_maintenance_trial"
CASES = dict(enumerate([
    "首次武装创建稳定锁且首次启动计数为零", "复用稳定锁武装缺失状态",
    "专用授权重置已知熔断", "续完合法普通启动残留", "续完阈值残留保持熔断",
    "没有授权方拒绝", "授权方拒绝不创建锁", "授权 PID 不符", "授权 UID 不符",
    "授权操作不符", "授权到期边界", "授权签发在未来", "授权租期超长",
    "武装零令牌拒绝", "负令牌拒绝", "超出线路整数上界拒绝",
    "相同令牌不能重置", "旧令牌不能重置", "令牌耗尽不能回绕",
    "未知操作拒绝", "root 拒绝", "重置不能初始化", "初始化不能覆盖",
    "损坏状态不能重置", "重置不能丢弃残留", "无残留不能续完",
    "损坏残留拒绝", "两列写入前缀拒绝", "非规范临时记录拒绝",
    "残留不能携带新恢复令牌", "残留不能减少计数", "已锁存状态不能借残留解锁",
    "恢复不能重建丢失的锁", "锁访问错误不是缺失", "锁竞争不等待",
    "锁不是普通文件", "锁多个硬链接", "锁同步失败", "锁目录项同步失败",
    "写入失败", "短写循环完整提交", "临时文件同步失败", "临时文件关闭失败",
    "发布失败", "发布后目录同步失败不回滚", "读回损坏", "读回 inode 不符",
    "锁关闭失败", "目录关闭失败", "发布前授权过期", "关闭后授权过期",
    "墙上时钟失败", "提交后墙上时钟失败", "授权期间状态被换 inode",
    "授权期间出现新残留", "维护与启动重入共用一次机会", "授权期间 PID 改变",
    "关闭期间 PID 改变", "续完残留重新同步失败", "残留时间在未来",
    "授权返回时已过期不创建锁", "旧状态关闭失败", "续完不能重建缺失状态",
    "初始化不能忽略残留", "零字节残留拒绝", "初始化失败保留稳定锁与临时文件",
    "续完已授权的初始化残留", "续完已授权的重置残留", "续写授权必须匹配原令牌",
    "武装残留不能复用旧令牌", "截断的武装残留拒绝", "武装残留不是零计数初态",
    "续写武装不能重建稳定锁", "普通零令牌不能续写武装残留",
]))


class SafetyMaintenanceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.clang = find_clang(None, ROOT)
        if cls.clang is None:
            raise unittest.SkipTest("缺少 NDK，维护事务没有执行，不能算验证通过")
        cls.temporary = tempfile.TemporaryDirectory(prefix="launcher-maintenance-")
        cls.addClassCleanup(cls.temporary.cleanup)

    def compile_trial(self, source, target):
        command = [
            str(self.clang), "--target=aarch64-linux-android26", "-std=c++17", "-O1",
            "-Wall", "-Wextra", "-Werror", "-fno-exceptions", "-fno-rtti",
            "-fno-stack-protector", "-fvisibility=hidden", "-mno-outline-atomics",
            "-fno-builtin", "-U_FORTIFY_SOURCE", "-D_FORTIFY_SOURCE=0",
            "-shared", "-nostdlib", "-Wl,-Bsymbolic", "-Wl,--no-undefined",
            str(source), "-o", str(target),
        ]
        build = subprocess.run(command, capture_output=True, check=False)
        self.assertEqual(0, build.returncode, build.stderr.decode("utf-8", errors="replace"))
        image = ElfImage(target.read_bytes())
        return image, trial_entry(image, ENTRY)

    def test_all_maintenance_faults(self):
        self.assertEqual(set(range(74)), set(CASES))
        self.assertEqual(74, len(set(CASES.values())))
        image, entry = self.compile_trial(ROOT / "app/src/test/cpp/launcher_safety_maintenance_trial.cpp",
                                          Path(self.temporary.name) / "maintenance.so")
        for case, name in CASES.items():
            with self.subTest(case=case, name=name):
                run_case(image, entry, case, CASES)

    def test_maintenance_is_reachable_only_through_the_brokered_entry(self):
        cmake = (ROOT / "app/src/main/cpp/CMakeLists.txt").read_text(encoding="utf-8")
        code = "\n".join(line for line in cmake.splitlines() if not line.lstrip().startswith("#"))
        self.assertIn("safety_startup.cpp", code)
        self.assertNotIn("GLOB", code)
        entry = (ROOT / "app/src/main/cpp/launcher/native_entry.cpp").read_text(encoding="utf-8")
        self.assertIn("query_maintenance_grant", entry)
        self.assertIn("request.grant.reset_token != snapshot.safety_reset_token", entry)
        self.assertIn("commit_safety_startup_once(context, snapshot, last_revision, restart_ptr)", entry)
        endpoint = (ROOT / "app/src/main/java/io/github/YGHFv/HyperExtend/hook/feature/launcher/LauncherSnapshotEndpoint.kt").read_text(encoding="utf-8")
        self.assertIn("answerMaintenance", endpoint)
        self.assertIn("LauncherSafetyMaintenance.pending", endpoint)
        self.assertIn("readSetting(pending.key) == pending.stored", endpoint)
        self.assertIn("remove(pending.key)", endpoint)

    def test_planned_restart_is_not_gated_by_the_safety_mode_projection(self):
        # 安全模式会把主开关投影为 false；普通计划重启必须仍然发布请求，
        # 主开关只允许在显式恢复时用于打开旧端点的空功能通道。
        source = (ROOT / "app/src/main/java/io/github/YGHFv/HyperExtend/hook/feature/launcher/"
                  "LauncherSafetyMaintenance.kt").read_text(encoding="utf-8")
        body = source[source.index("fun requestForRestart"):source.index("internal fun rootRequestScript")]
        self.assertNotIn("if (!recover &&", body)
        self.assertNotIn("if (!recover && !enabled)", body)
        self.assertIn(
            "if (recover && preferences.all[LauncherSnapshotWire.MASTER] != true)",
            body,
        )
        self.assertIn("rootRequestScript(Process.myUid() / 100000, issued, wireToken, recover)", body)

    def test_dangerous_mutations_fail_at_execution_not_compilation(self):
        base = Path(self.temporary.name) / "mutations"
        native = base / "app/src/main/cpp/launcher"
        tests = base / "app/src/test/cpp"
        native.mkdir(parents=True)
        tests.mkdir(parents=True)
        for header in (ROOT / "app/src/main/cpp/launcher").glob("*.h"):
            shutil.copyfile(header, native / header.name)
        for name in ("launcher_safety_startup_trial.cpp", "launcher_safety_maintenance_trial.cpp"):
            shutil.copyfile(ROOT / "app/src/test/cpp" / name, tests / name)
        original = (ROOT / "app/src/main/cpp/launcher/safety_startup.cpp").read_text(encoding="utf-8")
        mutations = [
            ("略过人工授权", "if (!authorize(opaque, context, operation, seen, grant))",
             "if (authorize(opaque, context, operation, seen, grant) && false)", [6]),
            ("将两列前缀当成完整残留", "if (canonical) {", "if (canonical && false) {", [27, 28]),
            ("残留计数不核对", "return step.ok && equal_state(step.next, seen.pending);",
             "return step.ok;", [29, 30]),
            ("重置令牌允许复用", "grant.reset_token > seen.state.last_reset_token",
             "grant.reset_token >= seen.state.last_reset_token", [16, 18]),
            ("维护完成后放开同进程启动", "return result;\n}\n\n}  // 命名空间 launcher",
             "g_startup_owner = 0;\n    return result;\n}\n\n}  // 命名空间 launcher", [55]),
            ("用另一令牌认领武装残留", "grant.reset_token == seen.pending.last_reset_token",
             "true", [68]),
        ]
        for label, before, after, cases in mutations:
            with self.subTest(mutation=label):
                self.assertEqual(1, original.count(before))
                (native / "safety_startup.cpp").write_text(original.replace(before, after), encoding="utf-8")
                image, entry = self.compile_trial(tests / "launcher_safety_maintenance_trial.cpp",
                                                  base / "mutated.so")
                for case in cases:
                    with self.subTest(case=case), self.assertRaisesRegex(AuditError, "失败"):
                        run_case(image, entry, case, CASES)


if __name__ == "__main__":
    unittest.main()
