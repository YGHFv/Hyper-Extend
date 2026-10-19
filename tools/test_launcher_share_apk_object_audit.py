"""对象边界审计的合成夹具；不依赖本机宿主、网络、NDK 或设备。"""

import copy
import io
import json
import struct
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import launcher_share_apk_object_audit as audit
from launcher_native_audit import AuditError, digest, number
from test_launcher_share_apk_flow_audit import fixture as flow_fixture
from test_launcher_menu_island_audit import object_fixture


def fixture():
    image, flows, obj = flow_fixture()
    contract = audit.load_contract()
    contract["image"] = copy.deepcopy(flows[0]["image"])
    for item in contract["functions"].values():
        address = number(item["address"])
        image.chunks[address] = bytes.fromhex(item["bytes"])
        if item["symbol"] is not None:
            image.symbols.append({"name": item["symbol"], "address": address, "size": item["size"]})
    for field in contract["fields"]:
        for side in ("encode", "decode"):
            item = field[side]
            symbol = {"name": item["symbol"], "address": number(item["target"]), "size": 4}
            if symbol not in image.symbols:
                image.symbols.append(symbol)
    return image, flows, obj, contract


class ContractTest(unittest.TestCase):
    def setUp(self):
        self.image, self.flows, self.obj, self.contract = fixture()

    def verify(self):
        audit.verify_contract(self.contract, self.flows[0])

    def test_real_and_synthetic_contracts_pass(self):
        self.verify()
        audit.verify_contract(audit.load_contract(), audit.flow_audit.load_contracts()[0])

    def test_policy_version_or_permission_drift_fails(self):
        for key, value in (("policy", "other"), ("schemaVersion", 2), ("installPermitted", True)):
            broken = copy.deepcopy(self.contract)
            broken[key] = value
            with self.subTest(key=key), self.assertRaises(AuditError):
                audit.verify_contract(broken, self.flows[0])

    def test_closing_any_gate_fails(self):
        for gate in (1, 3, 5):
            self.contract["notClosedGates"] = [item for item in (1, 3, 5) if item != gate]
            with self.subTest(gate=gate), self.assertRaises(AuditError):
                self.verify()

    def test_changing_host_identity_fails(self):
        self.contract["image"]["buildId"] = "other"
        with self.assertRaisesRegex(AuditError, "宿主身份"):
            self.verify()

    def test_pool_resolution_cannot_be_asserted(self):
        self.contract["poolContentsResolved"] = True
        with self.assertRaisesRegex(AuditError, "池槽内容尚未解码"):
            self.verify()

    def test_pool_offset_cannot_change(self):
        self.contract["poolSlots"]["firstField"] = "0xb9470"
        with self.assertRaisesRegex(AuditError, "替换槽位"):
            self.verify()

    def test_field_semantic_names_cannot_be_guessed(self):
        for index in range(8):
            broken = copy.deepcopy(self.contract)
            broken["fields"][index]["semanticName"] = "action"
            with self.subTest(index=index), self.assertRaisesRegex(AuditError, "不能猜语义"):
                audit.verify_contract(broken, self.flows[0])

    def test_field_width_kind_order_or_missing_field_fails(self):
        for key, value in (("memoryWidth", 8), ("kind", "String"), ("index", 1), ("offset", "0xb")):
            broken = copy.deepcopy(self.contract)
            broken["fields"][0][key] = value
            with self.subTest(key=key), self.assertRaises(AuditError):
                audit.verify_contract(broken, self.flows[0])
        self.contract["fields"].pop()
        with self.assertRaises(AuditError):
            self.verify()

    def test_memory_integer_width_does_not_imply_int64_wire(self):
        self.contract["fields"][2]["encode"]["symbol"] = "WriteBuffer.putInt64"
        with self.assertRaisesRegex(AuditError, "编解码类型"):
            self.verify()

    def test_matrix_cannot_shrink(self):
        for key, value in (("cases", 72), ("comparisons", 36), ("apiStates", ["ready"]), ("rejections", 0)):
            broken = copy.deepcopy(self.contract)
            broken["matrix"][key] = value
            with self.subTest(key=key), self.assertRaisesRegex(AuditError, "矩阵"):
                audit.verify_contract(broken, self.flows[0])

    def test_tag_class_or_size_drift_fails(self):
        for key, value in (("allocationTag", "0xd7731c"), ("intentClassId", "0xd77"), ("allocationSize", 32)):
            broken = copy.deepcopy(self.contract)
            broken[key] = value
            with self.subTest(key=key), self.assertRaises(AuditError):
                audit.verify_contract(broken, self.flows[0])

    def test_function_set_identity_or_bytes_drift_fails(self):
        self.contract["functions"].pop("intentEquals")
        with self.assertRaisesRegex(AuditError, "函数证据集合"):
            self.verify()
        for key, value in (("address", "0x9a2c5c"), ("size", 104),
                           ("symbol", "Bundle.new"), ("bytes", "00" * 108)):
            broken = audit.load_contract()
            broken["image"] = self.flows[0]["image"]
            broken["functions"]["bundleWrapper"][key] = value
            with self.subTest(key=key), self.assertRaises(AuditError):
                audit.verify_contract(broken, self.flows[0])


class StaticTest(unittest.TestCase):
    def setUp(self):
        self.image, self.flows, self.obj, self.contract = fixture()

    def verify(self):
        return audit.verify_host(self.image, self.contract, self.flows[0])

    def mutate_and_resign(self, role, address, word):
        # 绕过该窗口的字节摘要，确认后面的指令判定本身也会拒绝错误，而非只测试摘要。
        self.image.write_word(address, word)
        item = self.contract["functions"][role]
        raw = self.image.code(number(item["address"]), item["size"])
        item["bytes"], item["sha256"] = raw.hex(), digest(raw)

    def test_complete_fixture_passes_and_reports_unknown_pool(self):
        result = self.verify()
        self.assertEqual("0xd78", result["类ID"])
        self.assertEqual(48, result["分配字节"])
        self.assertEqual(8, result["字段数"])
        self.assertFalse(result["池槽内容已解码"])

    def test_host_digest_must_match(self):
        self.image.data += b"drift"
        with self.assertRaisesRegex(AuditError, "宿主文件"):
            self.verify()

    def test_code_word_drift_is_rejected(self):
        self.image.write_word(0x18C00D8, 0xD503201F)
        with self.assertRaisesRegex(AuditError, "机器码漂移"):
            self.verify()

    def test_exact_symbol_identity_must_exist(self):
        self.image.symbols = [s for s in self.image.symbols if s["name"] != "Intent.=="]
        with self.assertRaisesRegex(AuditError, "对象函数符号身份"):
            self.verify()

    def test_same_name_wrapper_elsewhere_is_not_ambiguous(self):
        self.image.symbols.append({"name": "RustLibApiImpl.sse_decode_intent", "address": 0xEE6970, "size": 40})
        self.verify()

    def test_duplicate_symbol_at_same_address_is_rejected(self):
        symbol = next(s for s in self.image.symbols if s["address"] == 0x8CCAF0)
        self.image.symbols.append(copy.deepcopy(symbol))
        with self.assertRaisesRegex(AuditError, "对象函数符号身份"):
            self.verify()

    def test_allocator_tag_register_and_target_are_decoded(self):
        self.mutate_and_resign("intentAllocate", 0x18C00D8, 0xD2906383)
        with self.assertRaisesRegex(AuditError, "向 x2 装入 tag"):
            self.verify()

    def test_changed_allocator_class_fails_even_with_new_window_digest(self):
        self.mutate_and_resign("intentAllocate", 0x18C00D8, 0xD2800002 | (0x731C << 5))
        with self.assertRaisesRegex(AuditError, "分配 tag"):
            self.verify()

    def test_class_comparison_and_size_extraction_are_checked(self):
        for role, address in (("intentEquals", 0x179825C), ("allocateObject", 0x1B57110)):
            image, flows, obj, contract = fixture()
            self.image, self.flows, self.obj, self.contract = image, flows, obj, contract
            self.mutate_and_resign(role, address, 0xD503201F)
            with self.subTest(role=role), self.assertRaises(AuditError):
                self.verify()

    def test_field_width_and_receiver_register_are_decoded(self):
        self.mutate_and_resign("intentEncode", 0x8CBF88, 0xF8407082)
        with self.assertRaisesRegex(AuditError, "字段寄存器、偏移或宽度"):
            self.verify()

    def test_codec_symbol_cannot_point_elsewhere(self):
        symbol = next(s for s in self.image.symbols if s["name"] == "WriteBuffer.putInt32")
        symbol["address"] += 4
        with self.assertRaisesRegex(AuditError, "编解码目标符号"):
            self.verify()

    def test_unexpected_extra_bl_is_rejected(self):
        self.mutate_and_resign("intentEncode", 0x8CC044, 0x94000000)
        with self.assertRaisesRegex(AuditError, "完整 BL 集合"):
            self.verify()

    def test_share_write_width_must_match_intent_schema(self):
        self.flows[0]["recordWrites"][0]["size"] = 8
        with self.assertRaisesRegex(AuditError, "分享字段"):
            self.verify()

    def test_ldur_stur_sign_extension_and_nonmatching_opcode(self):
        self.assertEqual(("store", 8, 29, 0, -8), audit.ldur_stur(0xF81F83A0))
        with self.assertRaises(AuditError):
            audit.ldur_stur(0xD503201F)


class ExecutionTest(unittest.TestCase):
    def setUp(self):
        self.image, self.flows, self.obj, self.contract = fixture()

    def trial(self, scenario="nonnull", **kwargs):
        return audit.ObjectTrial(self.image, self.flows[0], self.obj, self.flows[0]["sites"][0],
                                 "island", scenario, **kwargs)

    def test_full_144_execution_matrix(self):
        result = audit.run_matrix(self.image, self.flows[0], self.obj)
        self.assertEqual(144, result["执行次数"])
        self.assertEqual(72, result["逐项等价对照"])
        self.assertEqual(196, result["新增真实代码范围字节"])

    def test_bundle_wrapper_forwards_to_named_api(self):
        trial = self.trial()
        result = trial.run()
        self.assertEqual(["RustLibApiImpl.hyperApiBundleBundleNew"], result["对象边界"]["命名转发"])
        self.assertEqual(0, result["对象边界"]["迟初始化次数"])
        self.assertEqual(trial.initial[26], trial.read_x(26))
        self.assertEqual(trial.initial[28], trial.read_x(28))

    def test_lazy_branch_runs_once_and_keeps_frames(self):
        trial = self.trial(api_state="lazy")
        result = trial.run()
        self.assertEqual(1, result["对象边界"]["迟初始化次数"])
        self.assertEqual(trial.top, trial.read_x(15))
        self.assertEqual(trial.initial[29], trial.read_x(29))
        self.assertEqual(trial.caller + 4, trial.read_x(30))
        self.assertEqual(trial.c_top, result["sp"])

    def test_real_allocator_sets_tag_top_and_unwritten_fields_to_null(self):
        trial = self.trial()
        trial.run()
        self.assertEqual(audit.TAG, struct.unpack("<Q", trial.m.mem_read(trial.raw_record, 8))[0])
        self.assertEqual(trial.raw_record + 48, struct.unpack("<Q", trial.m.mem_read(trial.thread + 0x60, 8))[0])
        for offset in (11, 23, 31, 35):
            self.assertEqual(trial.tokens["null"] & 0xFFFFFFFF,
                             struct.unpack("<I", trial.m.mem_read(trial.record + offset, 4))[0])
        self.assertEqual(1, struct.unpack("<Q", trial.m.mem_read(trial.record + 15, 8))[0])
        self.assertEqual(8, len(trial.allocation_writes))

    def test_initialization_spills_eight_bytes_past_bump_pointer(self):
        trial = self.trial()
        trial.run()
        wanted = struct.pack("<I", trial.tokens["null"] & 0xFFFFFFFF) * 2
        self.assertEqual(wanted, bytes(trial.m.mem_read(trial.raw_record + 48, 8)))
        self.assertEqual(b"\xa5" * 9, bytes(trial.m.mem_read(trial.raw_record + 56, 9)))

    def test_null_and_low32_alias_do_not_allocate(self):
        for scenario in ("null", "low32-alias"):
            trial = self.trial(scenario)
            result = trial.run()
            self.assertEqual(0, result["对象边界"]["分配写回调数"])
            self.assertEqual("a5" * 64, result["record"])
            self.assertEqual(trial.raw_record, struct.unpack("<Q", trial.m.mem_read(trial.thread + 0x60, 8))[0])

    def test_missing_api_aborts_before_rust_call_and_has_no_success(self):
        trial = self.trial(api_state="missing")
        with self.assertRaisesRegex(AuditError, "异常构造路径未建模"):
            trial.run()
        self.assertIsNone(trial.result)
        self.assertEqual([], trial.nested)
        self.assertEqual(["makeBundle"], trial.calls)

    def test_full_or_insufficient_heap_aborts_without_allocator_writes(self):
        for space in ("exact", "short"):
            trial = self.trial(space=space)
            with self.subTest(space=space), self.assertRaisesRegex(AuditError, "分配慢路径未建模"):
                trial.run()
            self.assertIsNone(trial.result)
            self.assertEqual([], trial.allocation_writes)

    def test_all_eight_negative_controls_reject_at_expected_boundary(self):
        result = audit.negative_controls(self.image, self.flows[0], self.obj)
        self.assertEqual(8, len(result))
        self.assertTrue(all(item["已拒绝"] for item in result))

    def test_fault_checks_do_not_accept_arbitrary_error_or_silent_success(self):
        for effect in ({"side_effect": AuditError("无关错误")}, {"return_value": {}}):
            with patch.object(audit.ObjectTrial, "run", **effect), self.assertRaises(AuditError):
                audit.negative_controls(self.image, self.flows[0], self.obj)

    def test_matrix_detects_pair_difference(self):
        with patch.object(audit.ObjectTrial, "run", side_effect=[{"x": 1}, {"x": 2}]):
            with self.assertRaisesRegex(AuditError, "不等价"):
                audit.run_matrix(self.image, self.flows[0], self.obj)

    def test_invalid_scenarios_fail_before_execution(self):
        for kwargs in ({"api_state": "other"}, {"space": "other"}):
            with self.subTest(kwargs=kwargs), self.assertRaises(AuditError):
                self.trial(**kwargs)

    def test_runtime_fixture_and_static_table_are_readonly_not_executable(self):
        trial = self.trial()
        regions = list(trial.m.mem_regions())
        for address in (trial.runtime_objects, trial.static_table + 0x2000, trial.pool):
            perms = next(flags for start, end, flags in regions if start <= address <= end)
            self.assertEqual(trial.uc.UC_PROT_READ, perms)
        for address in (trial.thread, trial.heap):
            perms = next(flags for start, end, flags in regions if start <= address <= end)
            self.assertEqual(trial.uc.UC_PROT_READ | trial.uc.UC_PROT_WRITE, perms)


class CliTest(unittest.TestCase):
    def test_missing_file_fails_without_leaking_path(self):
        with tempfile.TemporaryDirectory() as folder:
            with patch("sys.stdout", new_callable=io.StringIO) as output:
                status = audit.main(["--host-elf", str(Path(folder) / "missing.so"), "--object", "missing.o"])
            self.assertEqual(1, status)
            self.assertIn("FileNotFoundError", output.getvalue())
            self.assertNotIn(folder, output.getvalue())

    def test_malformed_json_fails_before_reading_image(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "bad.json"
            path.write_text("{", encoding="utf-8")
            with patch("sys.stdout", new_callable=io.StringIO):
                status = audit.main(["--host-elf", "unused.so", "--object", "unused.o", "--contract", str(path)])
            self.assertEqual(1, status)

    def test_synthetic_cli_keeps_gate_and_pool_status_false(self):
        image, flows, _, contract = fixture()
        with tempfile.TemporaryDirectory() as folder:
            host, obj = Path(folder) / "fake.so", Path(folder) / "fake.o"
            host.write_bytes(image.data)
            obj.write_bytes(object_fixture())
            with patch.object(audit, "ElfImage", return_value=image), \
                    patch.object(audit, "load_contract", return_value=contract), \
                    patch.object(audit.flow_audit, "load_contracts", return_value=flows), \
                    patch("sys.stdout", new_callable=io.StringIO) as output:
                status = audit.main(["--host-elf", str(host), "--object", str(obj)])
            self.assertEqual(0, status)
            result = json.loads(output.getvalue())
            self.assertEqual([1, 3, 5], result["未关闭门槛"])
            self.assertFalse(result["安装许可"])
            self.assertFalse(result["静态证据"]["池槽内容已解码"])
            self.assertEqual(144, result["矩阵"]["执行次数"])
            self.assertNotIn(folder, output.getvalue())


if __name__ == "__main__":
    unittest.main()
