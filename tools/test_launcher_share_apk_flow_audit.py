"""分享函数体的合成回归；夹具不含本机 ELF，也不冒充实机行为验收。"""

import copy
import io
import json
import struct
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import launcher_share_apk_flow_audit as audit
from launcher_native_audit import AuditError, digest, number
from test_launcher_menu_island_audit import island_object, object_fixture


class FakeImage:
    """以合同机器码构造稀疏代码段，身份为合成值；真实 ELF 另由命令核验。"""

    def __init__(self, flow):
        self.data = b"synthetic-share-flow-fixture"
        self.identifier = "synthetic-build-id"
        function = flow["function"]
        self.chunks = {number(function["address"]): bytes.fromhex(function["bytes"])}
        self.symbols = [{"name": function["name"], "address": number(function["address"]),
                         "size": function["size"]}]
        for site in flow["sites"]:
            self.chunks[number(site["address"])] = struct.pack(
                "<II", number(site["word"]), number(site["continuationWord"]))
        seen = set()
        for call in flow["calls"]:
            if call["symbol"] is not None and call["symbol"] not in seen:
                self.symbols.append({"name": call["symbol"],
                                     "address": number(call["target"]), "size": 4})
                seen.add(call["symbol"])

    def build_id(self):
        return self.identifier

    def debug_image(self):
        return self

    def functions(self):
        return copy.deepcopy(self.symbols)

    def code(self, address, size):
        for start, raw in self.chunks.items():
            offset = address - start
            if 0 <= offset and offset + size <= len(raw):
                return raw[offset:offset + size]
        raise AuditError("合成代码段越界")

    def write_word(self, address, word):
        for start, raw in self.chunks.items():
            offset = address - start
            if 0 <= offset and offset + 4 <= len(raw):
                modified = bytearray(raw)
                struct.pack_into("<I", modified, offset, word)
                self.chunks[start] = bytes(modified)
                return
        raise AssertionError("合成写入越界")


def fixture():
    contracts = audit.load_contracts()
    image = FakeImage(contracts[0])
    for contract in contracts:
        contract["image"] = {"size": len(image.data), "sha256": digest(image.data),
                             "buildId": image.identifier}
    return image, contracts, island_object()


class ContractTest(unittest.TestCase):
    def setUp(self):
        self.contracts = audit.load_contracts()
        self.flow, self.pilot, self.returns, self.island = self.contracts

    def verify(self):
        audit.verify_contracts(*self.contracts)

    def test_reviewed_contracts_agree(self):
        self.verify()

    def test_policy_and_schema_drift_fail(self):
        for field, value in (("schemaVersion", 2), ("policy", "other")):
            broken = copy.deepcopy(self.contracts)
            broken[0][field] = value
            with self.subTest(field=field), self.assertRaises(AuditError):
                audit.verify_contracts(*broken)

    def test_install_permission_in_any_dependency_fails(self):
        for index in range(4):
            broken = copy.deepcopy(self.contracts)
            broken[index]["installPermitted"] = True
            with self.subTest(index=index), self.assertRaises(AuditError):
                audit.verify_contracts(*broken)
        self.pilot["pilot"]["installPermitted"] = True
        with self.assertRaises(AuditError):
            self.verify()

    def test_closing_any_gate_fails(self):
        for gate in (1, 3, 5):
            self.flow["notClosedGates"] = [item for item in (1, 3, 5) if item != gate]
            with self.subTest(gate=gate), self.assertRaises(AuditError):
                self.verify()

    def test_dependency_identity_drift_fails(self):
        for index in range(1, 4):
            broken = copy.deepcopy(self.contracts)
            broken[index]["image"]["sha256"] = "0" * 64
            with self.subTest(index=index), self.assertRaises(AuditError):
                audit.verify_contracts(*broken)

    def test_function_identity_or_bytes_drift_fails(self):
        for field, value in (("address", "0x17fda74"), ("size", 184),
                             ("name", "other"), ("sha256", "0" * 64),
                             ("bytes", "00" * 188)):
            broken = copy.deepcopy(self.contracts)
            broken[0]["function"][field] = value
            with self.subTest(field=field), self.assertRaises(AuditError):
                audit.verify_contracts(*broken)

    def test_sites_cannot_be_removed_or_reordered(self):
        for sites in (self.flow["sites"][:1], self.flow["sites"][::-1]):
            broken = copy.deepcopy(self.contracts)
            broken[0]["sites"] = sites
            with self.subTest(sites=sites), self.assertRaises(AuditError):
                audit.verify_contracts(*broken)

    def test_return_use_must_stay_discarded(self):
        site = next(site for site in self.returns["sites"] if site["feature"] == "分享 APK")
        site["rewriteReturn"] = True
        with self.assertRaisesRegex(AuditError, "返回值用法"):
            self.verify()

    def test_leaf_site_target_drift_fails(self):
        site = next(site for site in self.island["sites"] if site["feature"] == "分享 APK")
        site["target"] = "0x17fda74"
        with self.assertRaisesRegex(AuditError, "叶蹦床合同"):
            self.verify()

    def test_roles_cannot_be_removed_or_renamed(self):
        self.flow["calls"][0]["role"] = "intent"
        with self.assertRaisesRegex(AuditError, "角色集合"):
            self.verify()

    def test_named_boundary_cannot_be_anonymized(self):
        self.flow["calls"][1]["symbol"] = None
        with self.assertRaisesRegex(AuditError, "已命名外部边界"):
            self.verify()

    def test_unnamed_boundary_cannot_be_invented(self):
        self.flow["calls"][0]["symbol"] = "Intent.new"
        with self.assertRaisesRegex(AuditError, "已命名外部边界"):
            self.verify()

    def test_unnamed_target_drift_fails(self):
        self.flow["calls"][0]["target"] = "0x9a2c5c"
        with self.assertRaisesRegex(AuditError, "未命名外部边界"):
            self.verify()

    def test_matrix_cannot_shrink(self):
        for field, value in (("cases", 24), ("scenarios", ["nonnull"]),
                             ("negativeControls", 0), ("boundaryAborts", 0)):
            broken = copy.deepcopy(self.contracts)
            broken[0]["matrix"][field] = value
            with self.subTest(field=field), self.assertRaisesRegex(AuditError, "行为矩阵"):
                audit.verify_contracts(*broken)

    def test_pool_write_and_null_branch_contracts_are_fixed(self):
        for field, child, value in (("poolSlots", "firstKey", "0xb9460"),
                                     ("nullBranch", "comparisonBits", 64)):
            broken = copy.deepcopy(self.contracts)
            broken[0][field][child] = value
            with self.subTest(field=field), self.assertRaises(AuditError):
                audit.verify_contracts(*broken)
        self.flow["recordWrites"][0]["size"] = 8
        with self.assertRaisesRegex(AuditError, "对象字段"):
            self.verify()


class HostTest(unittest.TestCase):
    def setUp(self):
        self.image, self.contracts, self.obj = fixture()
        self.flow = self.contracts[0]

    def verify(self):
        return audit.verify_host(self.image, self.flow)

    def test_synthetic_image_passes_real_verifiers(self):
        audit.verify_contracts(*self.contracts)
        self.assertEqual(bytes.fromhex(self.flow["function"]["bytes"]), self.verify())
        audit.verify_island(self.obj, self.contracts[3])

    def test_host_length_hash_and_build_id_each_fail(self):
        for field, value in (("size", 0), ("sha256", "0" * 64), ("buildId", "other")):
            original = self.flow["image"][field]
            self.flow["image"][field] = value
            with self.subTest(field=field), self.assertRaises(AuditError):
                self.verify()
            self.flow["image"][field] = original

    def test_missing_or_duplicate_function_symbol_fails(self):
        function = self.image.symbols.pop(0)
        with self.assertRaisesRegex(AuditError, "宿主符号不唯一"):
            self.verify()
        self.image.symbols.extend([function, function])
        with self.assertRaisesRegex(AuditError, "宿主符号不唯一"):
            self.verify()

    def test_symbol_range_drift_fails(self):
        self.image.symbols[0]["size"] -= 4
        with self.assertRaisesRegex(AuditError, "函数范围"):
            self.verify()

    def test_body_word_drift_fails_before_execution(self):
        self.image.write_word(number(self.flow["function"]["address"]), 0xD503201F)
        with self.assertRaisesRegex(AuditError, "函数内容摘要"):
            self.verify()

    def test_declared_bytes_must_match_image_even_with_same_digest_field(self):
        self.flow["function"]["bytes"] = "00" * 188
        with self.assertRaisesRegex(AuditError, "函数体字节"):
            self.verify()

    def test_entire_bl_set_must_match(self):
        self.flow["calls"].pop(0)
        with self.assertRaisesRegex(AuditError, "完整 BL 集合"):
            self.verify()

    def test_missing_named_boundary_symbol_fails(self):
        self.image.symbols = [item for item in self.image.symbols if item["name"] != "BundleImpl.putString"]
        with self.assertRaisesRegex(AuditError, "外部调用符号身份"):
            self.verify()

    def test_duplicate_or_wrong_address_boundary_symbol_fails(self):
        symbol = self.image.symbols[1]
        self.image.symbols.append(copy.deepcopy(symbol))
        with self.assertRaisesRegex(AuditError, "外部调用符号身份"):
            self.verify()
        self.image.symbols.pop()
        symbol["address"] += 4
        with self.assertRaisesRegex(AuditError, "外部调用符号身份"):
            self.verify()

    def test_call_or_continuation_word_drift_fails(self):
        for offset in (0, 4):
            image = FakeImage(self.flow)
            image.write_word(number(self.flow["sites"][0]["address"]) + offset, 0xD503201F)
            with self.subTest(offset=offset), self.assertRaisesRegex(AuditError, "调用点或接续"):
                audit.verify_host(image, self.flow)

    def test_self_consistent_non_bl_call_site_fails(self):
        site = self.flow["sites"][0]
        site["word"] = hex(number(site["word"]) & ~0x80000000)
        self.image.write_word(number(site["address"]), number(site["word"]))
        with self.assertRaisesRegex(AuditError, "未指向分享入口"):
            self.verify()

    def test_build_isolation_ignores_only_full_line_comments(self):
        audit.verify_isolation()
        with patch.object(Path, "read_text", return_value="# launcher_menu_island_arm64.S\n"):
            audit.verify_isolation()
        with patch.object(Path, "read_text", return_value="add_library(x launcher_menu_island_arm64.S)\n"):
            with self.assertRaisesRegex(AuditError, "不得进入产品构建"):
                audit.verify_isolation()


class FlowTest(unittest.TestCase):
    def setUp(self):
        self.image, self.contracts, self.obj = fixture()
        self.flow = self.contracts[0]

    def trial(self, scenario="nonnull", mode="island", **kwargs):
        return audit.FlowTrial(self.image, self.flow, self.obj, self.flow["sites"][0],
                               mode, scenario, **kwargs)

    def body(self, address, word):
        raw = bytearray.fromhex(self.flow["function"]["bytes"])
        struct.pack_into("<I", raw, address - number(self.flow["function"]["address"]), word)
        return bytes(raw)

    def test_full_matrix_executes_72_cases_and_36_comparisons(self):
        result = audit.run_matrix(self.image, self.flow, self.obj)
        self.assertEqual(72, result["执行次数"])
        self.assertEqual(36, result["逐项等价对照"])

    def test_nonnull_calls_and_exact_write_widths(self):
        trial = self.trial()
        result = trial.run()
        self.assertEqual(list(audit.ROLES), result["calls"])
        self.assertEqual([(0x17FDAF0, 7, 4), (0x17FDAF8, 15, 8),
                          (0x17FDB00, 27, 4), (0x17FDB0C, 39, 4)], result["writes"])
        data = bytes.fromhex(result["record"])
        self.assertEqual(trial.tokens["firstField"] & 0xFFFFFFFF, struct.unpack_from("<I", data, 7)[0])
        self.assertEqual(1, struct.unpack_from("<Q", data, 15)[0])
        self.assertEqual(trial.tokens["bundle"] & 0xFFFFFFFF, struct.unpack_from("<I", data, 27)[0])
        self.assertEqual(b"\xa5" * 4, data[11:15])
        self.assertEqual(b"\xa5" * 21, data[43:])

    def test_null_skips_only_last_four_calls_and_all_object_writes(self):
        result = self.trial("null").run()
        self.assertEqual(list(audit.ROLES[:4]), result["calls"])
        self.assertEqual([], result["writes"])
        self.assertEqual("a5" * 64, result["record"])

    def test_low32_alias_takes_null_branch_without_claiming_valid_object(self):
        self.assertEqual(self.trial("null").run(), self.trial("low32-alias").run())

    def test_64_bit_compare_mutation_fails_for_low32_alias(self):
        with self.assertRaisesRegex(AuditError, "空接口分支仍进入"):
            self.trial("low32-alias", body=self.body(0x17FDAC0, 0xEB16001F)).run()

    def test_both_stacks_frame_link_and_dart_null_are_restored(self):
        trial = self.trial()
        result = trial.run()
        self.assertEqual(trial.tokens["null"], result["gpr"][0])
        self.assertEqual(trial.top, trial.read_x(15))
        self.assertEqual(trial.initial[29], trial.read_x(29))
        self.assertEqual(trial.caller + 4, trial.read_x(30))
        self.assertEqual(trial.c_top, result["sp"])
        self.assertEqual("5a" * 32, result["stack"][:64])

    def test_boundary_stubs_actually_clobber_temporaries_and_vectors(self):
        trial = self.trial()
        result = trial.run()
        self.assertNotEqual(trial.initial[9], trial.read_x(9))
        self.assertNotEqual(trial.initial_q[:8], result["q"][:8])
        self.assertEqual(trial.initial_q[8:], result["q"][8:])

    def test_all_eight_machine_faults_are_rejected(self):
        controls = audit.negative_controls(self.image, self.flow, self.obj)
        self.assertEqual(8, len(controls))
        self.assertTrue(all(item["已拒绝"] for item in controls))

    def test_fault_controls_do_not_treat_arbitrary_errors_as_success(self):
        with patch.object(audit.FlowTrial, "run", side_effect=AuditError("无关错误")):
            with self.assertRaisesRegex(AuditError, "非预期位置失败"):
                audit.negative_controls(self.image, self.flow, self.obj)

    def test_fault_controls_detect_an_inoperative_verifier(self):
        with patch.object(audit.FlowTrial, "run", return_value={}):
            with self.assertRaisesRegex(AuditError, "错误放行"):
                audit.negative_controls(self.image, self.flow, self.obj)

    def test_every_boundary_abort_rejects_normal_completion(self):
        result = audit.boundary_aborts(self.image, self.flow, self.obj)
        self.assertEqual(8, result["已拒绝正常完成判定"])
        self.assertEqual(list(audit.ROLES), result["边界"])

    def test_boundary_abort_meta_checks_reject_unrelated_error_and_success(self):
        for effect in ({"side_effect": AuditError("无关错误")}, {"return_value": {}}):
            with self.subTest(effect=effect), patch.object(audit.FlowTrial, "run", **effect):
                with self.assertRaises(AuditError):
                    audit.boundary_aborts(self.image, self.flow, self.obj)

    def test_matrix_rejects_pairwise_differences(self):
        with patch.object(audit.FlowTrial, "run", side_effect=[{"x": 1}, {"x": 2}]):
            with self.assertRaisesRegex(AuditError, "不同的数据流"):
                audit.run_matrix(self.image, self.flow, self.obj)

    def test_out_of_frame_write_is_rejected(self):
        # 把入口的帧内存储改为 str x2,[x15,#32]，不能借“都在栈页”放过越界。
        with self.assertRaisesRegex(AuditError, "未审核内存写入"):
            self.trial(body=self.body(0x17FDA84, 0xF90011E2)).run()

    def test_unexpected_control_flow_is_rejected(self):
        target = 0x2000100
        word = audit.branch26(number(self.flow["function"]["address"]), target, False)
        with self.assertRaisesRegex(AuditError, "执行流离开"):
            self.trial(body=self.body(0x17FDA70, word)).run()

    def test_instruction_budget_does_not_report_infinite_loop_as_success(self):
        with self.assertRaisesRegex(AuditError, "指令预算"):
            self.trial(body=self.body(0x17FDB1C, 0x14000000)).run()

    def test_bad_modes_layouts_flags_and_body_lengths_fail(self):
        for kwargs in ({"mode": "other"}, {"scenario": "other"}, {"layout": 2},
                       {"flags": 16}, {"body": b"\0" * 4}):
            with self.subTest(kwargs=kwargs), self.assertRaises(AuditError):
                self.trial(**kwargs)

    def test_data_pages_are_not_executable_and_code_is_not_writable(self):
        trial = self.trial()
        regions = list(trial.m.mem_regions())
        for address in (trial.start, trial.caller, trial.island):
            flags = next(flags for start, end, flags in regions if start <= address <= end)
            self.assertEqual(trial.uc.UC_PROT_READ | trial.uc.UC_PROT_EXEC, flags)
        for address in (trial.pool + 0xB9458, trial.slot):
            flags = next(flags for start, end, flags in regions if start <= address <= end)
            self.assertEqual(trial.uc.UC_PROT_READ, flags)
        for address in (trial.top, trial.c_top, trial.heap):
            flags = next(flags for start, end, flags in regions if start <= address <= end)
            self.assertEqual(trial.uc.UC_PROT_READ | trial.uc.UC_PROT_WRITE, flags)


class CliTest(unittest.TestCase):
    def test_missing_input_fails_without_leaking_local_path(self):
        with tempfile.TemporaryDirectory() as folder:
            args = ["--host-elf", str(Path(folder) / "missing.so"), "--object", "unused.o"]
            with patch("sys.stdout", new_callable=io.StringIO) as output:
                status = audit.main(args)
            self.assertEqual(1, status)
            self.assertIn("FileNotFoundError", output.getvalue())
            self.assertNotIn(folder, output.getvalue())

    def test_malformed_json_fails(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "contract.json"
            path.write_text("{", encoding="utf-8")
            with patch("sys.stdout", new_callable=io.StringIO) as output:
                status = audit.main(["--host-elf", "unused.so", "--object", "unused.o",
                                     "--contract", str(path)])
            self.assertEqual(1, status)
            self.assertIn("复核失败", output.getvalue())

    def test_synthetic_cli_runs_verifiers_and_keeps_permission_false(self):
        image, contracts, _ = fixture()
        with tempfile.TemporaryDirectory() as folder:
            host, obj = Path(folder) / "fake.so", Path(folder) / "fake.o"
            host.write_bytes(image.data)
            obj.write_bytes(object_fixture())
            with patch.object(audit, "ElfImage", return_value=image), \
                    patch.object(audit, "load_contracts", return_value=contracts), \
                    patch("sys.stdout", new_callable=io.StringIO) as output:
                status = audit.main(["--host-elf", str(host), "--object", str(obj)])
            result = json.loads(output.getvalue())
            self.assertEqual(0, status)
            self.assertEqual(72, result["矩阵"]["执行次数"])
            self.assertEqual(8, len(result["故障对照"]))
            self.assertEqual(8, result["边界中止对照"]["已拒绝正常完成判定"])
            self.assertFalse(result["安装许可"])
            self.assertEqual([1, 3, 5], result["未关闭门槛"])
            self.assertNotIn(folder, output.getvalue())

    def test_bad_contract_stops_before_reading_host(self):
        contracts = audit.load_contracts()
        contracts[0]["installPermitted"] = True
        with patch.object(audit, "load_contracts", return_value=contracts), \
                patch.object(audit, "ElfImage") as elf, \
                patch("sys.stdout", new_callable=io.StringIO) as output:
            status = audit.main(["--host-elf", "unused.so", "--object", "unused.o"])
        elf.assert_not_called()
        self.assertEqual(1, status)
        self.assertIn("不得放行安装", output.getvalue())


if __name__ == "__main__":
    unittest.main()
