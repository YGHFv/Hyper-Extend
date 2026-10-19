"""分享阶段链的离线回归；合成 APK 通过不代表设备执行。"""

import copy
import io
import json
import tempfile
import re
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

import launcher_share_stage_chain_audit as audit
from launcher_native_audit import AuditError


def contract():
    return json.loads(audit.DEFAULT_CONTRACT.read_text(encoding="utf-8"))


def sources():
    return {
        key: (audit.ROOT / path).read_text(encoding="utf-8")
        for key, path in audit.SOURCES.items()
    }


class ContractTest(unittest.TestCase):
    def test_real_source_matches_contract(self):
        result = audit.verify_contract(contract())
        self.assertEqual(7, result["源码文件数"])
        self.assertEqual(8, result["分享日志额度"])
        self.assertEqual("每次请求", result["分享日志计数范围"])
        self.assertFalse(result["安装许可"])
        self.assertFalse(result["门槛3已关闭"])
        self.assertTrue(result["有效性替换已启用"])
        self.assertEqual("async-false-result-exit-island", result["有效性替换层级"])

    def test_permission_gate_and_limits_are_fixed(self):
        for key, value in (("schemaVersion", 2), ("policy", "other"),
                           ("installPermitted", True), ("notClosedGates", [3, 5]),
                           ("limits", {"snapshotReport": 9,
                                       "shareReportPerRequest": 8})):
            changed = contract()
            changed[key] = value
            with self.subTest(key=key), self.assertRaises(AuditError):
                audit.verify_contract(changed)

    def test_share_must_not_reuse_snapshot_report_budget(self):
        changed = sources()
        changed["endpoint"] = changed["endpoint"].replace(
            'reportShare("share queued point=$point")',
            'reportOnce("share queued point=$point")',
        )
        with self.assertRaisesRegex(AuditError, "共用普通快照额度"):
            audit.verify_contract(contract(), changed)
        changed = sources()
        changed["endpoint"] = changed["endpoint"].replace(
            "private val shareReported = AtomicInteger(0)", "",
        )
        with self.assertRaisesRegex(AuditError, "独立计数器"):
            audit.verify_contract(contract(), changed)

    def test_system_server_share_logs_are_counted_per_request(self):
        changed = sources()
        changed["systemServer"] = changed["systemServer"].replace(
            "if (reported.getAndIncrement() < REPORT_LIMIT)",
            "if (reported.getAndIncrement() < 0)",
        )
        with self.assertRaisesRegex(AuditError, "单次请求计数器"):
            audit.verify_contract(contract(), changed)

    def test_share_call_set_cannot_drop_or_add_messages(self):
        changed = sources()
        changed["endpoint"] = changed["endpoint"].replace(
            'reportShare("share rejected caller uid=$uid pid=$pid")',
            "",
        )
        with self.assertRaisesRegex(AuditError, "调用集合"):
            audit.verify_contract(contract(), changed)

    def test_unknown_share_phase_is_rejected(self):
        changed = sources()
        changed["endpoint"] = changed["endpoint"].replace(
            'reportShare("share queued point=$point")',
            'reportShare("share something point=$point")',
        )
        with self.assertRaisesRegex(AuditError, "调用集合"):
            audit.verify_contract(contract(), changed)

    def test_prefix_cannot_move_into_message(self):
        changed = sources()
        changed["endpoint"] = changed["endpoint"].replace(
            'reportShare("share queued point=$point")',
            'reportShare("launcher endpoint: share queued point=$point")',
        )
        with self.assertRaisesRegex(AuditError, "前缀重复"):
            audit.verify_contract(contract(), changed)

    def test_each_stage_text_is_required(self):
        for group in ("nativeEntry", "trampoline", "systemServer", "provider", "activity"):
            for text in contract()[group]:
                changed = sources()
                key = {"nativeEntry": "native", "trampoline": "trampoline",
                       "systemServer": "systemServer", "provider": "provider",
                       "activity": "activity"}[group]
                changed[key] = changed[key].replace(text, "阶段已删除")
                with self.subTest(group=group, text=text), self.assertRaises(AuditError):
                    audit.verify_contract(contract(), changed)

    def test_wire_protocol_is_fixed(self):
        for key, value in (("transaction", "0x00484544"), ("protocol", 2),
                           ("queuedAck", "0x48455333")):
            changed = contract()
            changed["wire"][key] = value
            with self.subTest(key=key), self.assertRaises(AuditError):
                audit.verify_contract(changed)

    def test_both_points_must_remain_accepted(self):
        changed = sources()
        changed["endpoint"] = changed["endpoint"].replace(
            "point == LauncherSnapshotWire.SHARE_POINT_SECONDARY", "false",
        )
        with self.assertRaisesRegex(AuditError, "主、次"):
            audit.verify_contract(contract(), changed)

    def test_action_plan_must_keep_async_validity_exit_hooks_enabled(self):
        changed = contract()
        changed["actionHookPlan"]["validityReplacementsEnabled"] = False
        with self.assertRaisesRegex(AuditError, "false 出口必须进入"):
            audit.verify_contract(changed)
        changed = contract()
        changed["actionHookPlan"]["requiredValidityPoints"] = ["kShareValid"]
        with self.assertRaisesRegex(AuditError, "识别点必须齐全"):
            audit.verify_contract(changed)

    def test_action_plan_requires_both_share_islands(self):
        changed = sources()
        changed["native"] = changed["native"].replace(
            "const bool share_actions = g_share_primary_island.published &&\n"
            "        g_share_secondary_island.published;",
            "const bool share_actions = g_share_primary_island.published;",
            1,
        )
        with self.assertRaisesRegex(AuditError, "分享主、次动作岛页必须分别发布"):
            audit.verify_contract(contract(), changed)

    def test_native_install_must_hook_async_validity_false_exits(self):
        changed = sources()
        prefix, body = changed["native"].split("int install_launcher_batch(", 1)
        body = body.replace(
            "reinterpret_cast<void *>(&launcher_menu_share_valid_exit)",
            "nullptr",
            1,
        )
        changed["native"] = prefix + "int install_launcher_batch(" + body
        with self.assertRaisesRegex(AuditError, "异步 false 出口必须按功能独立发布"):
            audit.verify_contract(contract(), changed)


class PackageTest(unittest.TestCase):
    dex_text = (
        "share queued point= share rejected uid="
        "apk resolved package="
        "launcher share provider: begin package="
        "launcher share provider: file ready package="
        "provider accepted package="
        "activity launched package="
        "launcher share: "
        "launcher share activity: uri received"
        "launcher share activity: chooser started"
    )
    native_text = (
        "hook_word_share_primary hook_word_share_secondary "
        "share_entry_primary_entered share_entry_secondary_entered "
        "share_entry_package_ok share_entry_bridge_acked "
        "share_entry_bridge_failed"
    )

    def make_apk(self, folder, dex=None, native=None):
        path = Path(folder) / "app.apk"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("classes.dex", self.dex_text if dex is None else dex)
            archive.writestr(
                "lib/arm64-v8a/libHyperExtendLauncherNative.so",
                self.native_text if native is None else native,
            )
        return path

    def test_final_apk_requires_all_stage_strings(self):
        with tempfile.TemporaryDirectory() as folder:
            result = audit.packaged_strings(self.make_apk(folder))
        self.assertEqual(10, result["DEX 阶段数"])
        self.assertEqual(7, result["原生阶段数"])

    def test_missing_dex_or_native_stage_is_rejected(self):
        for field, text in (("dex", "chooser started"),
                            ("native", "share_entry_bridge_acked")):
            with tempfile.TemporaryDirectory() as folder:
                values = {field: getattr(self, field + "_text").replace(text, "")}
                path = self.make_apk(folder, **values)
                with self.subTest(field=field), self.assertRaisesRegex(AuditError, "缺少阶段字符串"):
                    audit.packaged_strings(path)

    def test_duplicate_dex_or_library_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "app.apk"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("classes.dex", self.dex_text)
                archive.writestr("classes2.dex", self.dex_text)
                archive.writestr("lib/arm64-v8a/libHyperExtendLauncherNative.so",
                                 self.native_text)
            with self.assertRaisesRegex(AuditError, "DEX 数量"):
                audit.packaged_strings(path)


class CliTest(unittest.TestCase):
    def test_cli_reports_contract_digest_without_paths(self):
        with patch("sys.stdout", new_callable=io.StringIO) as output:
            status = audit.main([])
        result = json.loads(output.getvalue())
        self.assertEqual(0, status)
        self.assertEqual(64, len(result["合同摘要"]))
        self.assertNotIn(str(audit.ROOT), output.getvalue())

    def test_cli_rejects_bad_contract_before_reading_apk(self):
        changed = contract()
        changed["installPermitted"] = True
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "contract.json"
            path.write_text(json.dumps(changed), encoding="utf-8")
            with patch.object(audit, "packaged_strings") as packaged, \
                    patch("sys.stdout", new_callable=io.StringIO) as output:
                status = audit.main(["--contract", str(path), "--apk", "unused.apk"])
        packaged.assert_not_called()
        self.assertEqual(1, status)
        self.assertIn("不得放行安装", output.getvalue())

    def test_missing_files_do_not_leak_paths(self):
        with tempfile.TemporaryDirectory() as folder:
            missing = Path(folder) / "missing.apk"
            with patch("sys.stdout", new_callable=io.StringIO) as output:
                status = audit.main(["--apk", str(missing)])
            self.assertEqual(1, status)
            self.assertIn("FileNotFoundError", output.getvalue())
            self.assertNotIn(folder, output.getvalue())


if __name__ == "__main__":
    unittest.main()
