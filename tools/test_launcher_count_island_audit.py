"""数量点蹦床审计器的合成用例。

真实宿主与真实交叉汇编对象由审计命令显式验证，不以跳过代替通过。这里用可控的
合成映像与合成对象覆盖正向通过路径与各类拒绝路径。
"""

import copy
import struct
import unittest
from pathlib import Path

from launcher_count_island_audit import (CALLER, CONTINUATION_SLOT, CONTINUATION_SYMBOL,
                                         EXTERNALS, GETTER, ISLAND, ISLAND_SYMBOL,
                                         ORIGINAL_SLOT, ORIGINAL_SYMBOL, POLICY,
                                         SLOT_PAGE, STATE_SLOT, STATE_SYMBOL, Trial,
                                         decode_adr, effective_value, materialize,
                                         negative_controls, run_matrix,
                                         sentinel_registers, verify_island)
from launcher_menu_island_audit import branch26
from launcher_native_audit import AuditError, digest
from launcher_shortcut_count_emulate import Arm64Object


WORDS = (0x100000BE, 0x90000010, 0x91000210, 0xF9400211, 0xD61F0220,
         0x90000010, 0x91000210, 0x88DFFE11, 0x51001630, 0x71003E1F,
         0x54000048, 0x2A1103E0, 0x90000010, 0x91000210, 0xF940021E, 0xD61F03C0)
RELOCATIONS = ((4, 275), (8, 277), (20, 275), (24, 277), (48, 275), (52, 277))
RELOCATION_NAMES = (ORIGINAL_SYMBOL, ORIGINAL_SYMBOL, STATE_SYMBOL, STATE_SYMBOL,
                    CONTINUATION_SYMBOL, CONTINUATION_SYMBOL)


def object_fixture(words=WORDS, relocations=RELOCATIONS, names=RELOCATION_NAMES,
                   entry=ISLAND_SYMBOL):
    code = struct.pack("<%dI" % len(words), *words)
    # 符号顺序必须稳定：0=空、1=入口、其后是去重后的外部符号。
    externals = list(dict.fromkeys(names))
    symbols = [(0, 0, 0, 0, 0, 0), (1, 0x12, 2, 2, 0, len(code))]
    for name in externals:
        symbols.append((None, 0x10, 0, 0, 0, 0))
    data = bytearray(0x800)
    sections = b"\0.shstrtab\0.text\0.strtab\0.symtab\0.rela.text\0"
    strings = b"\0" + entry.encode() + b"\0" + b"".join(n.encode() + b"\0" for n in externals)
    for index, name in enumerate(externals):
        symbols[index + 2] = (strings.index(name.encode()), 0x10, 0, 0, 0, 0)
    data[:16] = b"\x7fELF\x02\x01\x01" + bytes(9)
    struct.pack_into("<HHIQQQIHHHHHH", data, 16, 1, 183, 1, 0, 0, 0x400, 0, 64, 0, 0, 64, 6, 1)
    data[0x40:0x40 + len(sections)] = sections
    data[0x80:0x80 + len(code)] = code
    data[0x100:0x100 + len(strings)] = strings
    for index, symbol in enumerate(symbols):
        struct.pack_into("<IBBHQQ", data, 0x200 + index * 24, *symbol)
    for index, ((offset, kind), name) in enumerate(zip(relocations, names)):
        struct.pack_into("<QQq", data, 0x2C0 + index * 24, offset,
                         (externals.index(name) + 2) << 32 | kind, 0)
    rows = [
        (0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        (sections.index(b".shstrtab"), 3, 0, 0, 0x40, len(sections), 0, 0, 1, 0),
        (sections.index(b".text"), 1, 6, 0, 0x80, len(code), 0, 0, 4, 0),
        (sections.index(b".strtab"), 3, 0, 0, 0x100, len(strings), 0, 0, 1, 0),
        (sections.index(b".symtab"), 2, 0, 0, 0x200, 24 * len(symbols), 3,
         len(symbols), 8, 24),
        (sections.index(b".rela.text"), 4, 0, 0, 0x2C0, 24 * len(relocations), 4, 2, 8, 24),
    ]
    for i, row in enumerate(rows):
        struct.pack_into("<IIQQQQIIQQ", data, 0x400 + i * 64, *row)
    return bytes(data)


def island_object(**kwargs):
    return Arm64Object(object_fixture(**kwargs), ISLAND_SYMBOL)


def contract_for(obj):
    return {
        "schemaVersion": 1,
        "policy": POLICY,
        "installPermitted": False,
        "island": {
            "symbol": ISLAND_SYMBOL,
            "size": len(obj.code),
            "sha256": digest(obj.code),
            "afterOffset": 20,
            "words": [hex(word) for word in WORDS],
            "relocations": [[offset, kind, name]
                            for (offset, kind), name in zip(RELOCATIONS, RELOCATION_NAMES)],
        },
    }


class EncoderTest(unittest.TestCase):
    def test_adr_decodes_own_after_offset(self):
        self.assertEqual(20, decode_adr(WORDS[0]))

    def test_adr_rejects_non_adr(self):
        with self.assertRaises(AuditError):
            decode_adr(0x90000010)


class EffectiveValueTest(unittest.TestCase):
    def test_state_range_decides_override(self):
        for stock in (3, 4):
            for state in (0, 4, 21, 0x7FFFFFFF, 0xFFFFFFFF):
                with self.subTest(state=state, stock=stock):
                    self.assertEqual(stock, effective_value(state, stock))
            for state in (5, 20):
                with self.subTest(state=state, stock=stock):
                    self.assertEqual(state, effective_value(state, stock))

    def test_out_of_range_state_word_is_refused(self):
        for state in (-1, 0x100000000, "5"):
            with self.subTest(state=state), self.assertRaises(AuditError):
                effective_value(state, 3)


class IslandContractTest(unittest.TestCase):
    def setUp(self):
        self.obj = island_object()
        self.contract = contract_for(self.obj)

    def test_reviewed_island_passes(self):
        result = verify_island(self.obj, self.contract)
        self.assertEqual(64, result["字节数"])
        self.assertEqual("偏移 20", result["岛内接续点"])

    def test_install_permission_or_wrong_policy_is_refused(self):
        for key, value in (("installPermitted", True), ("policy", "other"),
                           ("schemaVersion", 2)):
            broken = copy.deepcopy(self.contract)
            broken[key] = value
            with self.subTest(key=key), self.assertRaises(AuditError):
                verify_island(self.obj, broken)

    def test_changed_word_or_size_or_digest_is_refused(self):
        for target, value in (("words", [hex(w) for w in WORDS[:-1]]),
                              ("size", 60), ("sha256", "0" * 64)):
            broken = copy.deepcopy(self.contract)
            broken["island"][target] = value
            with self.subTest(target=target), self.assertRaises(AuditError):
                verify_island(self.obj, broken)

    def test_external_symbol_set_must_be_exactly_reviewed(self):
        missing = island_object(relocations=RELOCATIONS[:4], names=RELOCATION_NAMES[:4])
        with self.assertRaises(AuditError):
            verify_island(missing, contract_for(self.obj))
        # 多出一条未审核重定位：对象自身合法，但绝不能通过已审核合同。
        extra = island_object(relocations=RELOCATIONS + ((56, 275),),
                              names=RELOCATION_NAMES + (ORIGINAL_SYMBOL,))
        with self.assertRaises(AuditError):
            verify_island(extra, contract_for(self.obj))

    def test_after_offset_must_point_inside_island(self):
        broken = copy.deepcopy(self.contract)
        broken["island"]["afterOffset"] = 64
        with self.assertRaises(AuditError):
            verify_island(self.obj, broken)

    def test_tail_must_be_br_x30(self):
        for tail in (0xD65F03C0, 0xD63F03C0, 0xD503201F):
            words = WORDS[:-1] + (tail,)
            obj = island_object(words=words)
            broken = contract_for(obj)
            broken["island"]["words"] = [hex(word) for word in words]
            with self.subTest(tail=hex(tail)), self.assertRaises(AuditError):
                verify_island(obj, broken)

    def test_island_must_not_contain_ret_or_bl(self):
        for words in (WORDS[:-1] + (0xD65F03C0,), WORDS[:1] + (0x94000000,) + WORDS[2:]):
            obj = island_object(words=words)
            broken = contract_for(obj)
            broken["island"]["words"] = [hex(word) for word in words]
            with self.subTest(words=words), self.assertRaises(AuditError):
                verify_island(obj, broken)

    def test_empty_contract_fails_without_mutating_object(self):
        before = self.obj.code
        with self.assertRaises(KeyError):
            verify_island(self.obj, {"schemaVersion": 1})
        self.assertEqual(before, self.obj.code)


class MaterializeTest(unittest.TestCase):
    def test_external_set_must_match_exactly(self):
        obj = island_object()
        good = {name: SLOT_PAGE for name in EXTERNALS}
        for externals in ({}, dict(good, 额外=SLOT_PAGE)):
            with self.assertRaises(AuditError):
                materialize(obj, ISLAND, externals)

    def test_unknown_relocation_kind_is_refused(self):
        obj = island_object(relocations=((4, 999),), names=(ORIGINAL_SYMBOL,))
        with self.assertRaises(AuditError):
            materialize(obj, ISLAND, {ORIGINAL_SYMBOL: ORIGINAL_SLOT})

    def test_materialized_adrp_keeps_destination_register(self):
        obj = island_object()
        code = materialize(obj, ISLAND, {ORIGINAL_SYMBOL: ORIGINAL_SLOT,
                                         STATE_SYMBOL: STATE_SLOT,
                                         CONTINUATION_SYMBOL: CONTINUATION_SLOT})
        for offset in (4, 20, 48):
            word, = struct.unpack_from("<I", code, offset)
            self.assertEqual(0x10, word & 0x1F)
            self.assertEqual(0x90000000, word & 0x9F000000)
        self.assertEqual(WORDS[0], struct.unpack_from("<I", code)[0])


class IslandExecutionTest(unittest.TestCase):
    def setUp(self):
        self.obj = island_object()
        self.contract = contract_for(self.obj)
        self.states = [0, 4, 5, 20, 21, 0xFFFFFFFF]
        self.stocks = [3, 4]

    def test_island_covers_exactly_5_to_20(self):
        cases = run_matrix(self.obj, self.contract, self.states, self.stocks)
        self.assertEqual(len(self.states) * len(self.stocks), len(cases))
        for case in cases:
            expected = case["状态字"] if 5 <= case["状态字"] <= 20 else case["原 getter 返回值"]
            self.assertEqual(expected, case["生效上限"])
            self.assertEqual(5 <= case["状态字"] <= 20, case["覆盖"])

    def test_every_case_restores_return_address_and_dart_stack(self):
        for case in run_matrix(self.obj, self.contract, self.states, self.stocks):
            self.assertIn("生效上限", case)

    def test_state_four_passes_through_even_when_values_match(self):
        # 状态字 4 与原生返回值 4 数值相等，但语义上是“不干预”，不能被算成覆盖。
        cases = run_matrix(self.obj, self.contract, [4], [4])
        self.assertFalse(cases[0]["覆盖"])
        self.assertEqual(4, cases[0]["生效上限"])

    def test_zero_state_never_writes_x0(self):
        trial = Trial()
        code = materialize(self.obj, ISLAND, {ORIGINAL_SYMBOL: ORIGINAL_SLOT,
                                              STATE_SYMBOL: STATE_SLOT,
                                              CONTINUATION_SYMBOL: CONTINUATION_SLOT})
        dart = {"x15": 0x40000800, "x29": 0x40000900,
                "q": [0x8F3A5C << 32 | i for i in range(32)]}
        sentinel = sentinel_registers()
        sentinel[15], sentinel[29] = dart["x15"], dart["x29"]
        trial.run(code, branch26(CALLER, ISLAND, True), 0, 4, dart, sentinel)
        self.assertEqual(4, trial.reg_at[CALLER + 4]["gpr"][0])
        self.assertEqual(CALLER + 4, trial.reg_at[CALLER + 4]["gpr"][30])
        self.assertEqual(dart["x15"], trial.reg_at[CALLER + 4]["gpr"][15])

    def test_out_of_range_state_enables_nothing(self):
        server = run_matrix(self.obj, self.contract, [0x7FFFFFFF, 0xFFFFFFFF], [3, 4])
        for case in server:
            self.assertFalse(case["覆盖"])
            self.assertEqual(case["原 getter 返回值"], case["生效上限"])

    def test_broken_islands_are_detected(self):
        probes = negative_controls(self.obj, self.contract, self.states, self.stocks)
        self.assertEqual(4, len(probes))
        self.assertTrue(all(item["已检出"] for item in probes))


class ProductionIslandWiringTest(unittest.TestCase):
    def test_production_object_matches_the_simulated_instruction_sequence(self):
        root = Path(__file__).resolve().parents[1]
        production = (root / "app/src/main/cpp/launcher/shortcut_count_island_arm64.S")
        candidate = (root / "app/src/test/cpp/launcher_count_island_arm64.S")

        def instructions(path, replacements):
            result = []
            in_text = False
            for line in path.read_text(encoding="utf-8").splitlines():
                if line.strip() == ".text":
                    in_text = True
                    continue
                if not in_text:
                    continue
                line = line.split("/*", 1)[0].strip()
                if not line or line.startswith(".") or line.endswith(":"):
                    continue
                if line.startswith(("adr ", "adrp ", "add ", "ldr ", "br ", "ldar ",
                                    "sub ", "cmp ", "b.hi ", "mov ")):
                    for source, target in replacements:
                        line = line.replace(source, target)
                    result.append(" ".join(line.split()))
            return result

        replacements = (
            ("launcher_shortcut_count_island_original", "launcher_count_island_original"),
            ("launcher_shortcut_count_island_continuation", "launcher_count_island_continuation"),
            ("launcher_shortcut_count_state", "launcher_count_island_state"),
        )
        self.assertEqual(instructions(candidate, ()), instructions(production, replacements))
        self.assertEqual(16, len(instructions(production, replacements)))

    def test_production_object_is_used_by_hook_plan(self):
        root = Path(__file__).resolve().parents[1]
        cmake = (root / "app/src/main/cpp/CMakeLists.txt").read_text(encoding="utf-8")
        entry = (root / "app/src/main/cpp/launcher/native_entry.cpp").read_text(encoding="utf-8")
        self.assertIn("launcher/shortcut_count_island_arm64.S", cmake)
        self.assertIn("-Wl,-u,launcher_shortcut_count_island", cmake)
        self.assertIn("launcher_shortcut_count_island_original = nullptr", entry)
        self.assertIn("launcher_shortcut_count_island_continuation = nullptr", entry)
        self.assertIn("&launcher_shortcut_count_island", entry)
        self.assertIn("launcher_shortcut_count_state", entry)
        self.assertIn("prepare_count_island", entry)
        self.assertIn("install_count_island", entry)


if __name__ == "__main__":
    unittest.main()
