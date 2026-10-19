"""目录兼容规则及三条生产路径的 ARM64 离线回归；不访问设备。"""

import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from launcher_diagnostic_emulate import CASES as DIAGNOSTIC_CASES, run_case, trial_entry
from launcher_native_audit import AuditError, ElfImage
from launcher_safety_parity_audit import find_clang
from launcher_safety_startup_emulate import CASES as STARTUP_CASES
from test_launcher_safety_maintenance import CASES as MAINTENANCE_CASES


ROOT = Path(__file__).resolve().parents[1]
CASES = dict(enumerate([
    "严格私有包目录", "同应用组可写且无 ACL", "文件系统不支持 ACL",
    "其他身份可写拒绝", "系统组可写拒绝", "其他用户组可写拒绝", "属主错误拒绝",
    "包目录不扩展组写权限", "额外访问 ACL 拒绝", "ACL 权限错误拒绝", "stat 失败拒绝",
    "普通文件冒充目录拒绝", "空 ACL 属性也拒绝", "ACL 读取错误拒绝", "root 不能冒充应用组",
    "缓存组不能冒充应用组", "完整多用户身份通过", "只比较 appId 的组拒绝",
    "原有无组写目录不额外读取 ACL", "组写加 setgid 拒绝", "组写加 sticky 拒绝",
    "ACL 中断不假定缺失", "ACL 内存错误不假定缺失",
]))


class DirectoryPolicyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.clang = find_clang(None, ROOT)
        if cls.clang is None:
            raise RuntimeError("需要本机 NDK 执行目录规则试验")
        cls.temporary = tempfile.TemporaryDirectory(prefix="launcher-directory-")
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.base = Path(cls.temporary.name)

    def compile(self, source, name, entry, defines=()):
        target = self.base / (name + ".so")
        command = [
            str(self.clang), "--target=aarch64-linux-android26", "-std=c++17", "-O1",
            "-Wall", "-Wextra", "-Werror", "-fno-exceptions", "-fno-rtti",
            "-fno-stack-protector", "-fvisibility=hidden", "-mno-outline-atomics",
            "-fno-builtin", "-U_FORTIFY_SOURCE", "-D_FORTIFY_SOURCE=0",
            "-shared", "-nostdlib", "-Wl,-Bsymbolic", "-Wl,--no-undefined",
            *["-D" + define for define in defines], str(source), "-o", str(target),
        ]
        result = subprocess.run(command, capture_output=True)
        self.assertEqual(0, result.returncode, result.stderr.decode("utf-8", errors="replace"))
        image = ElfImage(target.read_bytes())
        return image, trial_entry(image, entry)

    def test_metadata_and_acl_boundaries(self):
        image, entry = self.compile(ROOT / "app/src/test/cpp/launcher_directory_policy_trial.cpp",
                                    "policy", "launcher_directory_policy_trial")
        for case in CASES:
            with self.subTest(case=CASES[case]):
                run_case(image, entry, case, CASES)

    def replay(self, name, cases, defines=()):
        image, entry = self.compile(
            ROOT / "app/src/test/cpp/launcher_directory_integration_trial.cpp", name,
            "launcher_directory_integration_trial", defines)
        for case in cases:
            with self.subTest(case=cases[case]):
                run_case(image, entry, case, cases)

    def test_readonly_preflight_with_app_group_files(self):
        self.replay("preflight", DIAGNOSTIC_CASES, ["DIRECTORY_DIAGNOSTIC_TRIAL"])

    def test_startup_faults_with_app_group_files(self):
        self.replay("startup", STARTUP_CASES)

    def test_maintenance_faults_with_app_group_files(self):
        self.replay("maintenance", MAINTENANCE_CASES, ["DIRECTORY_MAINTENANCE_TRIAL"])

    def test_unsafe_rule_mutations_are_detected_at_execution(self):
        base = self.base / "mutations"
        native = base / "app/src/main/cpp/launcher"
        tests = base / "app/src/test/cpp"
        native.mkdir(parents=True)
        tests.mkdir(parents=True)
        for name in ("directory_policy.h", "entry_trace.h"):
            shutil.copyfile(ROOT / "app/src/main/cpp/launcher" / name, native / name)
        source = tests / "launcher_directory_policy_trial.cpp"
        shutil.copyfile(ROOT / "app/src/test/cpp/launcher_directory_policy_trial.cpp", source)
        original = (native / "directory_policy.h").read_text(encoding="utf-8")
        for label, before, after, case in [
            ("组关系省略", "group == uid", "(group == uid || true)", 4),
            ("其他身份可写", "(mode & 0002) != 0", "((mode & 0002) != 0 && false)", 3),
            ("包目录也兼容", "role == DirectoryRole::kFiles",
             "(role == DirectoryRole::kFiles || true)", 7),
            ("ACL 错误伪装缺失", "acl_error == ENODATA",
             "(acl_error == ENODATA || acl_error == EACCES)", 9),
            ("多用户只比 appId", "group == uid", "group % 100000U == uid % 100000U", 17),
        ]:
            with self.subTest(mutation=label):
                self.assertIn(before, original)
                (native / "directory_policy.h").write_text(original.replace(before, after),
                                                           encoding="utf-8")
                image, entry = self.compile(source, "mutant", "launcher_directory_policy_trial")
                with self.assertRaisesRegex(AuditError, "失败"):
                    run_case(image, entry, case, CASES)


if __name__ == "__main__":
    unittest.main()
