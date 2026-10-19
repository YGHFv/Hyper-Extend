"""模块中继发布审计的合同与反例判定；真实样本通过独立命令验证。"""

import copy
import json
import struct
import unittest
from unittest.mock import patch

from launcher_backend_publication_audit import (
    CONTRACT, EXPECTED_WINDOW, PUBLICATION_SLOTS, PUBLICATION_WORDS, RELAY,
    SYNC_WORDS, verify_observations, verify_publication, verify_publication_window,
)
from launcher_native_audit import AuditError


class PublicationFixture:
    def __init__(self):
        self.windows = {at: struct.pack("<" + "I" * len(words), *words)
                        for at, words in PUBLICATION_WORDS.items()}

    def code(self, address, size):
        raw = self.windows[address]
        if len(raw) != size:
            raise AuditError("测试指令大小不一致")
        return raw


def observations(initial=0):
    stages = ("内部回填后", "提交入口", "补丁完成", "模块回填前", "模块回填后")
    return [{"阶段": stage, "内部中继": RELAY,
             "模块中继": RELAY if index == 4 else initial, "完整补丁": index >= 2}
            for index, stage in enumerate(stages)]


class BackendPublicationTest(unittest.TestCase):
    def setUp(self):
        self.contract = json.loads(CONTRACT.read_text(encoding="utf-8"))
        self.image = PublicationFixture()

    def verify(self, slots=None, packed=None):
        slots = PUBLICATION_SLOTS if slots is None else slots
        packed = set(slots) if packed is None else packed
        with patch("launcher_backend_publication_audit.relocations", return_value=({}, packed)):
            with patch("launcher_backend_publication_audit.pointer_value",
                       side_effect=lambda _image, at: slots[at]):
                return verify_publication(self.image, self.contract)

    def test_known_chain_keeps_three_gates_closed(self):
        result = self.verify()
        self.assertEqual([1, 3, 5], result["未通过门槛"])
        self.assertIs(result["安装许可"], False)

    def test_contract_cannot_remove_pending_gate_or_claim_early_publication(self):
        for gates in ([3, 5], [], [1, 3], [1, 5]):
            with self.subTest(gates=gates):
                self.contract["publication"]["pendingGates"] = gates
                with self.assertRaisesRegex(AuditError, "门槛不可放行"):
                    self.verify()
        self.contract["publication"]["pendingGates"] = [1, 3, 5]
        for value in (True, None, 0, 1):
            self.contract["publication"]["moduleOriginalBeforePatch"] = value
            with self.subTest(value=value), self.assertRaisesRegex(AuditError, "门槛不可放行"):
                self.verify()

    def test_contract_addresses_cannot_silently_change(self):
        for key in ("apiGlobal", "hookFunction", "internalBackupStore", "moduleOriginalStore"):
            saved = self.contract["publication"][key]
            self.contract["publication"][key] = "0x0"
            with self.subTest(key=key), self.assertRaisesRegex(AuditError, "固定样本位置"):
                self.verify()
            self.contract["publication"][key] = saved

    def test_each_instruction_word_is_checked_beyond_fingerprint(self):
        for at, raw in self.image.windows.items():
            for off in range(0, len(raw), 4):
                changed = bytearray(raw)
                changed[off] ^= 1
                self.image.windows[at] = bytes(changed)
                with self.subTest(at=at, off=off), self.assertRaisesRegex(AuditError, "顺序指令变化"):
                    self.verify()
            self.image.windows[at] = raw

    def test_each_pointer_slot_and_relocation_is_required(self):
        for at in PUBLICATION_SLOTS:
            changed = dict(PUBLICATION_SLOTS)
            changed[at] += 4
            with self.subTest(at=at), self.assertRaisesRegex(AuditError, "调用链变化"):
                self.verify(slots=changed)
            with self.subTest(unrelocated=at), self.assertRaisesRegex(AuditError, "调用链变化"):
                self.verify(packed=set(PUBLICATION_SLOTS) - {at})

    def test_zero_and_stale_original_both_wait_for_outer_return(self):
        for initial in (0, 0x12345678):
            verify_observations(observations(initial), initial)

    def test_missing_duplicate_or_reordered_stage_rejected(self):
        good = observations()
        for bad in ([], good[:-1], [good[0]] + good, [good[1], good[0]] + good[2:]):
            with self.subTest(bad=bad), self.assertRaisesRegex(AuditError, "阶段不完整"):
                verify_observations(bad, 0)

    def test_early_module_publication_is_not_reported_as_late(self):
        for index in range(4):
            bad = observations()
            bad[index]["模块中继"] = RELAY
            with self.subTest(index=index), self.assertRaisesRegex(AuditError, "不再是底层返回后"):
                verify_observations(bad, 0)

    def test_missing_internal_or_module_publication_rejected(self):
        for index in range(5):
            bad = observations()
            bad[index]["内部中继"] = 0
            with self.subTest(index=index), self.assertRaisesRegex(AuditError, "内部中继未先回填"):
                verify_observations(bad, 0)
        bad = observations()
        bad[-1]["模块中继"] = 0
        with self.assertRaisesRegex(AuditError, "最终没有回填"):
            verify_observations(bad, 0)

    def test_patch_completion_must_be_between_internal_and_module_publication(self):
        for index in range(5):
            bad = copy.deepcopy(observations())
            bad[index]["完整补丁"] = not bad[index]["完整补丁"]
            with self.subTest(index=index), self.assertRaisesRegex(AuditError, "次序变化"):
                verify_observations(bad, 0)

    def test_publication_window_is_fully_pinned(self):
        verify_publication_window(EXPECTED_WINDOW)
        self.assertEqual(44, len(EXPECTED_WINDOW))
        self.assertEqual(0x9DD70, EXPECTED_WINDOW[0][0])
        self.assertEqual(0x8937C, EXPECTED_WINDOW[-1][0])
        self.assertTrue(SYNC_WORDS.isdisjoint(word for _, word in EXPECTED_WINDOW))

    def test_window_rejects_missing_extra_or_reordered_instructions(self):
        variants = (
            (),
            EXPECTED_WINDOW[:-1],
            (EXPECTED_WINDOW[1],) + EXPECTED_WINDOW[1:],
            EXPECTED_WINDOW[:5] + EXPECTED_WINDOW[6:],
            EXPECTED_WINDOW[1:] + EXPECTED_WINDOW[:1],
        )
        for window in variants:
            with self.subTest(window=window[:2]), self.assertRaises(AuditError) as caught:
                verify_publication_window(window)
            self.assertTrue("未记录补丁返回后的发布窗口" in str(caught.exception)
                            or "窗口指令序列变化" in str(caught.exception))

    def test_window_rejects_any_changed_instruction_word(self):
        for index in range(len(EXPECTED_WINDOW)):
            changed = list(EXPECTED_WINDOW)
            offset, word = changed[index]
            changed[index] = (offset, word ^ 1)
            with self.subTest(index=index), self.assertRaisesRegex(AuditError, "窗口指令序列变化"):
                verify_publication_window(tuple(changed))

    def test_sync_primitive_inside_window_is_reported_even_if_pinned(self):
        # 即使合同被错误地改成把同步点纳入窗口，也必须因独占访存而拒绝。
        for word in SYNC_WORDS:
            changed = tuple(EXPECTED_WINDOW) [:5] + ((0x0, word),) + tuple(EXPECTED_WINDOW)[6:]
            with self.subTest(word=hex(word)), self.assertRaises(AuditError) as caught:
                verify_publication_window(changed)
            self.assertTrue("窗口指令序列变化" in str(caught.exception)
                            or "同步原语" in str(caught.exception))


if __name__ == "__main__":
    unittest.main()
