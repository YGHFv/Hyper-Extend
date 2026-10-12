"""Pagination regression definitions; no live MCP access."""
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock

from mt_continuous_read import read


class ContinuousReadTest(unittest.TestCase):
    def test_modern_next_line_without_pagination_count(self):
        session = Mock()
        session.tool.side_effect = [
            {"textWindow": {"text": "a\nb\n", "nextLine": 2, "nextColumn": 0},
             "pagination": {"hasMore": True}, "truncated": True, "truncatedReason": "window"},
            {"textWindow": {"text": "c\n", "nextLine": 3}, "pagination": {"hasMore": False}},
        ]
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "member"
            result = read(session, "workspace", "member", output)
            self.assertEqual(2, result["pages"])
            self.assertEqual("a\nb\nc\n", output.with_suffix(".txt").read_text())
        self.assertEqual(2, session.tool.call_args_list[1].args[1]["startLine"])

    def test_nonadvancing_or_partial_column_page_is_rejected(self):
        for window in [{"nextLine": 0}, {"nextLine": 1, "nextColumn": 3}]:
            session = Mock()
            session.tool.return_value = {"textWindow": dict(window, text="text"), "pagination": {"hasMore": True}}
            with tempfile.TemporaryDirectory() as temp:
                with self.assertRaises(RuntimeError):
                    read(session, "workspace", "member", Path(temp) / "member")


if __name__ == "__main__":
    unittest.main()
