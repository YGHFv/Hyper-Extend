import hashlib
import unittest
from unittest.mock import Mock

from mt_systemui_probe import MtSession, open_host, verify_text


class ProbeTest(unittest.TestCase):
    expected = {"packageName": "com.android.systemui", "versionCode": 202602260}

    def session(self, modern=True, hosts=None, opened=None):
        session = Mock()
        names = ["mt_apk_workspace_list", "mt_apk_workspace_open"] if modern else ["mt_apk_list_workspaces"]
        session.request.return_value = {"tools": [{"name": name} for name in names]}
        host = dict(self.expected, workspaceId="fresh", path="mt://workspace/fresh", sourcePath="/gone.apk")
        session.tool.side_effect = lambda name, args: (
            opened or host if name == "mt_apk_workspace_open" else {"items": hosts if hosts is not None else [host]})
        return session

    def test_modern_reopens_saved_uri_not_source_path(self):
        session = self.session()
        self.assertEqual("fresh", open_host(session, self.expected)["workspaceId"])
        session.tool.assert_any_call("mt_apk_workspace_open", {"path": "mt://workspace/fresh", "temporary": False})

    def test_legacy_api_only_when_advertised(self):
        session = self.session(modern=False)
        self.assertEqual("fresh", open_host(session, self.expected)["workspaceId"])
        session.tool.assert_called_once_with("mt_apk_list_workspaces", {})

    def test_stale_id_or_empty_listing_never_reuses_old_workspace(self):
        for session, workspace in [(self.session(), "old"), (self.session(hosts=[]), None)]:
            with self.assertRaises(RuntimeError):
                open_host(session, self.expected, workspace)

    def test_ambiguous_hosts_require_explicit_id(self):
        hosts = [dict(self.expected, workspaceId=x, path="mt://workspace/" + x) for x in ["one", "two"]]
        with self.assertRaises(RuntimeError):
            open_host(self.session(hosts=hosts), self.expected)
        open_host(self.session(hosts=hosts), self.expected, "one")

    def test_opened_package_and_version_are_rechecked(self):
        for changed in [{"packageName": "wrong"}, {"versionCode": 1}]:
            with self.assertRaises(RuntimeError):
                open_host(self.session(opened=dict(self.expected, **changed)), self.expected)

    def test_missing_identity_and_unsupported_api_are_rejected(self):
        with self.assertRaises(ValueError):
            open_host(self.session(), {"versionCode": 1})
        session = self.session()
        session.request.return_value = {"tools": []}
        with self.assertRaises(RuntimeError):
            open_host(session, self.expected)

    def test_tool_discovery_follows_pages_and_rejects_repeated_cursor(self):
        session = self.session()
        session.request.side_effect = [{"tools": [], "nextCursor": "next"}, session.request.return_value]
        open_host(session, self.expected)
        session.request.assert_any_call("tools/list", {"cursor": "next"})
        session = self.session()
        session.request.return_value = {"tools": [], "nextCursor": "stuck"}
        with self.assertRaises(RuntimeError):
            open_host(session, self.expected)

    def test_read_only_guard_rejects_writes_edit_sessions_and_temporary_open(self):
        session = MtSession.__new__(MtSession)
        session.request = Mock()
        for name, args in [("mt_apk_edit_begin", {}), ("mt_file_write", {}),
                           ("mt_apk_read_text", {"editSessionId": "editing"}),
                           ("mt_apk_workspace_open", {"temporary": True})]:
            with self.assertRaises(ValueError):
                session.tool(name, args)
        session.request.assert_not_called()

    def test_hashes_accept_only_exact_or_terminal_newline_compatibility(self):
        for content, normalization in [("body", "exact"), ("body\n", "terminal-newline")]:
            evidence = {"locator": "method", "sha256": hashlib.sha256(content.encode()).hexdigest()}
            result = verify_text(evidence, {"textWindow": {"text": "body"}})
            self.assertEqual(normalization, result["hashNormalization"])
            with self.assertRaises(RuntimeError):
                verify_text(evidence, {"textWindow": {"text": "changed body"}})

    def test_fragments_do_not_bypass_changed_hash_or_truncation(self):
        evidence = {"locator": "method", "contains": ["body"]}
        self.assertEqual("fragments-only", verify_text(evidence, {"textWindow": {"text": "body"}})["verification"])
        for data in [{"textWindow": {"text": "other"}},
                     {"textWindow": {"text": "body", "lineTruncated": True}},
                     {"textWindow": {"text": "body"}, "pagination": {"hasMore": True}},
                     {"textWindow": {"text": "body"}, "truncated": True}]:
            with self.assertRaises(RuntimeError):
                verify_text(evidence, data)
        with self.assertRaises(RuntimeError):
            verify_text(dict(evidence, sha256="mismatch"), {"textWindow": {"text": "body"}})
        with self.assertRaises(ValueError):
            verify_text({"locator": "method"}, {"textWindow": {"text": "body"}})


if __name__ == "__main__":
    unittest.main()
