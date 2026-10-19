"""门槛 3 日志准备的合成回归；不把合成文本通过称为实机验收。"""

import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import launcher_gate3_log_audit as audit
from launcher_native_audit import AuditError


def contract():
    return json.loads(audit.DEFAULT_CONTRACT.read_text(encoding="utf-8"))


def server_line(message, level="I"):
    return "2026-01-01 12:00:00 %s %s\n" % (level, message)


def server_session(pid=101, installed=True):
    text = "==== attach pid=%d (2 buffered) ====\n" % pid
    text += server_line("[entry] module loaded: process=system_server systemServer=true api=102")
    text += server_line("[entry] system_server starting: api=102")
    if installed:
        text += server_line("launcher endpoint: installed=true (transaction=%d)" % 0x00484541)
    return text


def served(revision=7, features=0, limit=5):
    return server_line("launcher endpoint: served revision=%d features=%d limit=%d" %
                       (revision, features, limit))


def rejected(pid=201):
    return server_line("launcher endpoint: rejected caller uid=10001 pid=%d" % pid)


def native_line(message, pid=201, tid=202, level="I"):
    return "01-01 12:00:00.123 %5d %5d %s HELauncherProbe: %s\n" % (pid, tid, level, message)


def native_start(pid=201, tid=202):
    return native_line(contract()["nativeTemplates"]["start"], pid, tid)


def native_done(channel=16, snapshot=64, revision=7, features=0, limit=5, armed=0, pid=201, tid=202):
    template = contract()["nativeTemplates"]["done"]
    return native_line(template % (channel, snapshot, revision, features, limit, armed), pid, tid)


def inputs(accepted=False):
    before_server = server_session()
    before_launcher = "--------- beginning of main\n"
    values = {"channel": 0, "snapshot": 0, "features": 3, "limit": 8, "armed": 1} if accepted else {}
    return dict(before_server=before_server,
                after_server=before_server + served(features=values.get("features", 0), limit=values.get("limit", 5)),
                before_launcher=before_launcher,
                after_launcher=before_launcher + native_start() + native_done(**values))


class ContractTest(unittest.TestCase):
    def test_real_source_and_contract_match(self):
        result = audit.verify_log_contract(contract())
        self.assertEqual(9, result["源码文件数"])
        self.assertEqual(11, result["模板数"])
        self.assertFalse(result["安装许可"])
        self.assertFalse(result["门槛3已关闭"])
        self.assertEqual([1, 3, 5], result["未关闭门槛"])

    def test_contract_cannot_relax_gates_or_protocol(self):
        for key, value in (("schemaVersion", 2), ("policy", "other"), ("installPermitted", True),
                           ("gate3Closed", True), ("notClosedGates", [3, 5]),
                           ("transaction", "0x0048434a"), ("endpointReportLimit", 9), ("countLimit", [0, 20])):
            changed = contract()
            changed[key] = value
            with self.subTest(key=key), self.assertRaises(AuditError):
                audit.verify_log_contract(changed)

    def test_bit_contract_drift_is_rejected(self):
        for group in ("channelBits", "snapshotBits"):
            changed = contract()
            first = next(iter(changed[group]))
            changed[group][first] = 256
            with self.subTest(group=group), self.assertRaisesRegex(AuditError, "阻断位"):
                audit.verify_log_contract(changed)

    def test_template_drift_is_rejected(self):
        for group in ("nativeTemplates", "endpointTemplates"):
            for key in contract()[group]:
                changed = contract()
                changed[group][key] += "漂移"
                with self.subTest(group=group, key=key), self.assertRaisesRegex(AuditError, "模板"):
                    audit.verify_log_contract(changed)

    def test_removed_template_is_rejected(self):
        changed = contract()
        del changed["nativeTemplates"]["identityRejected"]
        with self.assertRaisesRegex(AuditError, "模板集合"):
            audit.verify_log_contract(changed)

    def test_source_constant_phase_lock_and_template_drift_are_rejected(self):
        original = {key: (audit.ROOT / path).read_text(encoding="utf-8") for key, path in audit.SOURCES.items()}
        for key, before, after in (
            ("abi", "entries->hook_func != nullptr", "entries->hook_func == nullptr"),
            ("channel", "kChannelShortReply = 1U << 3", "kChannelShortReply = 1U << 9"),
            ("snapshot", "kSnapshotNotArmed = 1U << 6", "kSnapshotNotArmed = 1U << 9"),
            ("endpoint", "REPORT_LIMIT = 8", "REPORT_LIMIT = 9"),
            ("permit", "permit_blockers(evidence) == 0", "permit_blockers(evidence) == 1"),
            ("native", "install_permitted=false", "install_permitted=true"),
            ("log", "==== attach pid=", "==== changed pid="),
            ("entry", "system_server starting:", "changed starting:"),
        ):
            changed = dict(original)
            self.assertIn(before, changed[key])
            changed[key] = changed[key].replace(before, after)
            with self.subTest(key=key), self.assertRaises(AuditError):
                audit.verify_log_contract(contract(), changed)


class LogWindowTest(unittest.TestCase):
    def setUp(self):
        self.data = inputs()

    def verify(self, expected="unarmed"):
        return audit.audit_logs(**self.data, contract=contract(), expected=expected)

    def test_unarmed_pair_is_evidence_not_install_permission(self):
        result = self.verify()
        self.assertEqual("0x10", result["channel"])
        self.assertEqual("0x40", result["snapshot"])
        self.assertEqual({"revision": 7, "features": 0, "limit": 5}, result["匹配字段"])
        self.assertFalse(result["安装许可"])
        self.assertFalse(result["门槛3已关闭"])
        self.assertTrue(result["日志核对通过"])

    def test_accepted_pair_still_cannot_close_gate(self):
        self.data = inputs(accepted=True)
        result = self.verify("accepted")
        self.assertEqual("0x0", result["channel"])
        self.assertFalse(result["门槛3已关闭"])
        self.assertEqual([1, 3, 5], result["未关闭门槛"])

    def test_readonly_safety_log_does_not_upgrade_channel_evidence(self):
        self.data = inputs(accepted=True)
        self.data["after_launcher"] += native_line(
            "只读安全预检：blockers=0x180；persisted=true；install_permitted=false"
        ).replace("HELauncherProbe:", "HELauncherSafety:")
        result = self.verify("accepted")
        self.assertTrue(result["日志核对通过"])
        self.assertEqual(2, result["原生新增事件数"])
        self.assertFalse(result["安装许可"])
        self.assertFalse(result["门槛3已关闭"])

    def test_readonly_safety_log_cannot_replace_query_completion(self):
        self.data["after_launcher"] = self.data["before_launcher"] + native_start() + native_line(
            "只读安全预检：blockers=0x180；persisted=false；install_permitted=false"
        ).replace("HELauncherProbe:", "HELauncherSafety:")
        with self.assertRaisesRegex(AuditError, "证据不足"):
            self.verify()

    def test_accepted_snapshot_may_have_no_features(self):
        self.data = inputs(accepted=True)
        self.data["after_launcher"] = self.data["before_launcher"] + native_start() + native_done(
            channel=0, snapshot=0, armed=1)
        self.data["after_server"] = self.data["before_server"] + served()
        self.verify("accepted")

    def test_scenario_never_changes_itself(self):
        with self.assertRaisesRegex(AuditError, "场景"):
            self.verify("accepted")
        with self.assertRaisesRegex(AuditError, "未知"):
            self.verify("auto")

    def test_frozen_logs_cannot_reuse_old_success(self):
        for side in ("server", "launcher"):
            data = inputs()
            data["before_" + side] = data["after_" + side]
            with self.subTest(side=side), self.assertRaisesRegex(AuditError, "没有新增"):
                audit.audit_logs(**data, contract=contract())

    def test_rotated_or_different_sources_are_rejected(self):
        for side in ("server", "launcher"):
            data = inputs()
            data["after_" + side] = data["after_" + side].split("\n", 1)[1]
            with self.subTest(side=side), self.assertRaisesRegex(AuditError, "轮转"):
                audit.audit_logs(**data, contract=contract())

    def test_incomplete_capture_lines_are_rejected(self):
        for key in self.data:
            data = inputs()
            data[key] = data[key].rstrip("\n")
            with self.subTest(key=key), self.assertRaisesRegex(AuditError, "末行"):
                audit.audit_logs(**data, contract=contract())

    def test_old_native_success_is_not_counted_as_new_pair(self):
        old = native_start(pid=301) + native_done(pid=301)
        self.data["before_launcher"] += old
        self.data["after_launcher"] = self.data["before_launcher"] + native_start() + native_done()
        self.assertEqual(2, self.verify()["原生新增事件数"])

    def test_start_only_done_only_reversed_and_duplicate_pairs_are_rejected(self):
        for content in (native_start(), native_done(), native_done() + native_start(),
                        (native_start() + native_done()) * 2):
            self.data["after_launcher"] = self.data["before_launcher"] + content
            with self.subTest(content=content), self.assertRaisesRegex(AuditError, "证据不足"):
                self.verify()

    def test_query_start_before_capture_window_is_insufficient(self):
        self.data["before_launcher"] += native_start()
        self.data["after_launcher"] = self.data["before_launcher"] + native_done()
        with self.assertRaisesRegex(AuditError, "证据不足"):
            self.verify()

    def test_process_and_thread_must_both_match(self):
        for values in ({"pid": 301}, {"tid": 302}):
            self.data["after_launcher"] = self.data["before_launcher"] + native_start() + native_done(**values)
            with self.subTest(values=values), self.assertRaisesRegex(AuditError, "禁止拼接"):
                self.verify()

    def test_worker_failure_is_not_missing_endpoint(self):
        for key in ("identityRejected", "threadFailed"):
            self.data["after_launcher"] = self.data["before_launcher"] + native_line(
                contract()["nativeTemplates"][key], level="W")
            with self.subTest(key=key), self.assertRaisesRegex(AuditError, "身份或创建失败"):
                self.verify()

    def test_unknown_native_messages_abi_and_install_flags_are_rejected(self):
        for before, after in (("入口已到达", "入口未知"), ("install_permitted=false", "install_permitted=true"),
                              ("armed=0", "armed=0 extra=1")):
            data = inputs()
            data["after_launcher"] = data["after_launcher"].replace(before, after)
            with self.subTest(before=before), self.assertRaisesRegex(AuditError, "锁定模板"):
                audit.audit_logs(**data, contract=contract())

    def test_raw_message_without_metadata_is_insufficient(self):
        self.data["after_launcher"] = self.data["before_launcher"] + contract()["nativeTemplates"]["start"] + "\n"
        with self.assertRaisesRegex(AuditError, "threadtime"):
            self.verify()

    def test_wrong_tag_cannot_supply_native_evidence(self):
        self.data["after_launcher"] = self.data["after_launcher"].replace("HELauncherProbe", "OtherProbe")
        with self.assertRaisesRegex(AuditError, "证据不足"):
            self.verify()

    def test_native_priority_and_identifiers_are_checked(self):
        for before, after in ((" I HELauncherProbe", " W HELauncherProbe"), ("  201", "    0")):
            data = inputs()
            data["after_launcher"] = data["after_launcher"].replace(before, after)
            with self.subTest(before=before), self.assertRaisesRegex(AuditError, "元数据"):
                audit.audit_logs(**data, contract=contract())

    def test_native_threadtime_may_include_year_and_crlf(self):
        for key, value in self.data.items():
            self.data[key] = value.replace("\n", "\r\n").replace("01-01 12:", "2026-01-01 12:") if "launcher" in key else value
        self.verify()

    def test_invalid_calendar_fields_are_rejected(self):
        for key in ("after_server", "after_launcher"):
            data = inputs()
            if key == "after_server":
                data[key] += server_line("无关日志").replace("2026-01-01", "2026-02-30")
            else:
                data[key] = data[key].replace("01-01 12:", "02-30 12:")
            with self.subTest(key=key), self.assertRaisesRegex(AuditError, "日历格式"):
                audit.audit_logs(**data, contract=contract())

    def test_server_identity_log_priority_is_checked(self):
        self.data["before_server"] = ""
        self.data["after_server"] = self.data["after_server"].replace(" I [entry]", " W [entry]")
        with self.assertRaisesRegex(AuditError, "身份"):
            self.verify()

    def test_all_transmission_failures_remain_failures(self):
        for channel in (1, 2, 4, 8, 32, 64, 128):
            self.data["after_launcher"] = self.data["before_launcher"] + native_start() + native_done(
                channel=channel, snapshot=0, revision=0, features=0, limit=0, armed=0)
            with self.subTest(channel=channel), self.assertRaisesRegex(AuditError, "未获得"):
                self.verify()

    def test_no_window_does_not_overclaim_service_absence(self):
        values = dict(channel=2, snapshot=0, revision=0, features=0, limit=0, armed=0)
        with self.assertRaisesRegex(AuditError, "类关联失败"):
            audit.check_snapshot(values, "unarmed")

    def test_denied_zero_reply_is_malformed_not_unarmed(self):
        self.data["after_launcher"] = self.data["before_launcher"] + native_start() + native_done(
            channel=16, snapshot=4, revision=0, limit=0)
        with self.assertRaisesRegex(AuditError, "字段畸形"):
            self.verify()

    def test_snapshot_rejections_are_not_accepted_as_unarmed(self):
        for snapshot in (1, 2, 4, 8, 16, 32, 64 | 32):
            self.data["after_launcher"] = self.data["before_launcher"] + native_start() + native_done(snapshot=snapshot)
            with self.subTest(snapshot=snapshot), self.assertRaisesRegex(AuditError, "快照未通过"):
                self.verify()

    def test_contradictory_unknown_or_out_of_range_fields_are_rejected(self):
        for values in ({"channel": 0}, {"snapshot": 0}, {"channel": 3}, {"snapshot": 128},
                       {"revision": 0}, {"features": 16}, {"limit": 4}, {"armed": 2},
                       {"revision": 1 << 31}, {"features": 1}, {"armed": 1},
                       {"channel": 4, "snapshot": 0}, {"channel": 0, "snapshot": 0, "armed": 0}):
            self.data["after_launcher"] = self.data["before_launcher"] + native_start() + native_done(**values)
            with self.subTest(values=values), self.assertRaises(AuditError):
                self.verify()

    def test_endpoint_install_must_be_unique_success_with_correct_transaction(self):
        for content in (server_session(installed=False),
                        server_session().replace("installed=true", "installed=false"),
                        server_session().replace(str(0x00484541), str(0x0048434A)),
                        server_session() + server_line("launcher endpoint: installed=true (transaction=%d)" % 0x00484541)):
            self.data["before_server"] = ""
            self.data["after_server"] = content + served()
            with self.subTest(content=content), self.assertRaises(AuditError):
                self.verify()

    def test_latest_session_cannot_borrow_old_install_or_serve(self):
        self.data["after_server"] += server_session(pid=102, installed=False) + served()
        with self.assertRaisesRegex(AuditError, "安装成功"):
            self.verify()

    def test_current_session_must_have_identity_and_starting(self):
        for marker in ("[entry] module loaded:", "[entry] system_server starting:"):
            self.data = inputs()
            self.data["before_server"] = ""
            self.data["after_server"] = "\n".join(line for line in self.data["after_server"].split("\n")
                                                   if marker not in line)
            with self.subTest(marker=marker), self.assertRaisesRegex(AuditError, "身份"):
                self.verify()

    def test_wrong_server_identity_and_malformed_attach_are_rejected(self):
        for before, after in (("process=system_server", "process=other"),
                              ("systemServer=true", "systemServer=false"),
                              ("==== attach pid=101", "==== attach pid=bad")):
            data = inputs()
            data["before_server"] = ""
            data["after_server"] = data["after_server"].replace(before, after)
            with self.subTest(before=before), self.assertRaises(AuditError):
                audit.audit_logs(**data, contract=contract())
        self.data["after_server"] += "==== attach pid=bad (0 buffered) ====\n"
        with self.assertRaisesRegex(AuditError, "会话头"):
            self.verify()

    def test_endpoint_failures_and_safety_blockers_are_rejected(self):
        for message in ("launcher endpoint: reply failed | 示例异常",
                        "launcher endpoint: safe mode engaged — not installing",
                        "launcher endpoint: unknown status",
                        "launcher snapshot endpoint unavailable",
                        "system_server hooks skipped (kill switch engaged)",
                        "system_server settings unavailable — framework hooks skipped",
                        "framework lacks PROP_CAP_SYSTEM",
                        "SAFE MODE [system_server]: 示例拒绝",
                        "framework hook fuse: cannot persist start; hooks skipped"):
            data = inputs()
            data["after_server"] += server_line(message, "E")
            with self.subTest(message=message), self.assertRaises(AuditError):
                audit.audit_logs(**data, contract=contract())

    def test_malformed_critical_server_line_is_not_ignored(self):
        self.data["after_server"] += "launcher snapshot endpoint unavailable\n"
        with self.assertRaisesRegex(AuditError, "文件日志格式"):
            self.verify()

    def test_serve_must_be_new_and_match_every_visible_field(self):
        for content in (served(8), served(features=1), served(limit=8), server_line("其它无关日志")):
            self.data["after_server"] = self.data["before_server"] + content
            with self.subTest(content=content), self.assertRaisesRegex(AuditError, "served"):
                self.verify()
        self.data["before_server"] += served()
        self.data["after_server"] = self.data["before_server"] + server_line("其它无关日志")
        with self.assertRaisesRegex(AuditError, "served"):
            self.verify()

    def test_duplicate_or_reversed_server_revision_is_rejected(self):
        for content in (served() + served(), served(8) + served()):
            self.data["after_server"] = self.data["before_server"] + content
            with self.subTest(content=content), self.assertRaisesRegex(AuditError, "严格递增"):
                self.verify()

    def test_served_outside_the_snapshot_range_is_rejected(self):
        self.data["after_server"] += served(8, features=16)
        with self.assertRaisesRegex(AuditError, "越界"):
            self.verify()

    def test_response_before_install_is_rejected(self):
        self.data["before_server"] = ""
        prelude = server_session(installed=False)
        install = server_session().splitlines()[-1] + "\n"
        self.data["after_server"] = prelude + served() + install
        with self.assertRaisesRegex(AuditError, "早于安装"):
            self.verify()

    def test_rejection_of_same_native_process_blocks_matching(self):
        self.data["after_server"] += rejected()
        with self.assertRaisesRegex(AuditError, "同一原生进程"):
            self.verify()

    def test_rejection_of_other_process_is_not_attributed_to_launcher(self):
        self.data["after_server"] += rejected(301)
        self.verify()

    def test_report_saturation_is_insufficient_not_endpoint_absence(self):
        self.data["after_server"] = self.data["before_server"] + rejected(301) * 8
        with self.assertRaisesRegex(AuditError, "8 次限额"):
            self.verify()
        self.data["after_server"] += served()
        with self.assertRaisesRegex(AuditError, "超过源码限额"):
            self.verify()

    def test_success_report_excludes_raw_pid_uid_and_paths(self):
        result = json.dumps(self.verify(), ensure_ascii=False)
        for token in ('"pid"', '"uid"', '"tid"', "uid=10001", "pid=101", "pid=201", str(audit.ROOT)):
            self.assertNotIn(token, result)


class CliTest(unittest.TestCase):
    def test_contract_check_needs_no_device_logs(self):
        with patch("sys.stdout", new_callable=io.StringIO) as output:
            status = audit.main(["check-contract"])
        result = json.loads(output.getvalue())
        self.assertEqual(0, status)
        self.assertFalse(result["门槛3已关闭"])

    def test_cli_reads_four_files_and_reports_hashes(self):
        with tempfile.TemporaryDirectory() as folder:
            args = ["audit"]
            for name, text in inputs().items():
                path = Path(folder) / (name + ".log")
                path.write_text(text, encoding="utf-8")
                args += ["--" + name.replace("_", "-"), str(path)]
            with patch("sys.stdout", new_callable=io.StringIO) as output:
                status = audit.main(args)
            self.assertEqual(0, status)
            result = json.loads(output.getvalue())
            self.assertEqual(4, len(result["日志摘要"]))
            self.assertFalse(result["安装许可"])
            self.assertNotIn(folder, output.getvalue())

    def test_missing_file_returns_failure_without_leaking_path(self):
        with tempfile.TemporaryDirectory() as folder:
            args = ["audit"]
            for key in inputs():
                args += ["--" + key.replace("_", "-"), str(Path(folder) / "missing.log")]
            with patch("sys.stdout", new_callable=io.StringIO) as output:
                status = audit.main(args)
            self.assertEqual(1, status)
            self.assertIn("FileNotFoundError", output.getvalue())
            self.assertNotIn(folder, output.getvalue())

    def test_cli_does_not_skip_incomplete_logs(self):
        with tempfile.TemporaryDirectory() as folder:
            data = inputs()
            data["after_launcher"] = data["before_launcher"] + native_start()
            args = ["audit"]
            for key, text in data.items():
                path = Path(folder) / (key + ".log")
                path.write_text(text, encoding="utf-8")
                args += ["--" + key.replace("_", "-"), str(path)]
            with patch("sys.stdout", new_callable=io.StringIO) as output:
                status = audit.main(args)
            self.assertEqual(1, status)
            self.assertIn("证据不足", output.getvalue())


if __name__ == "__main__":
    unittest.main()
