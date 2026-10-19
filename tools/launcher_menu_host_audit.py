#!/usr/bin/env python3
"""只读解析宿主符号表与 HyperCeiler 规则 DEX，定位四项菜单功能的宿主挂载点。

只读输入：宿主 `libapp.so` 的 `.gnu_debugdata`（解压后含完整符号表）与
参考模块 `classes.dex`。不执行参考代码、不加载目标库、不接触设备。

两个重要事实（本工具据此如实报告，不做推断放大）：

1. 宿主符号表给出真实挂载点地址与长度，可直接用于后续静态合同。
2. 参考模块的类名/方法名字符串经 lsparanoid（`Deobfuscator.getString(int)`）
   运行期解码，规则方法体内没有明文宿主类名常量，因此**不能**从 DEX 静态
   反推宿主类。本工具只报告该混淆事实与可见的框架调用，不伪造宿主目标。
"""

import argparse
import json
import lzma
import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from launcher_native_audit import (AuditError, ElfImage, digest, require,  # noqa: E402
                                   verify_contract)
from launcher_native_audit import number  # noqa: E402


# 上游分支语义：小窗 = 普通 freeform；新任务（中文资源「多开」）= 追加多任务标志。
FLAG_ACTIVITY_MULTIPLE_TASK = 0x08000000


RULES = ("Lcom/sevtinge/hyperceiler/libhook/rules/home/other/ShortcutItemCount;",
         "Lcom/sevtinge/hyperceiler/libhook/rules/home/other/AllowShareApk;",
         "Lcom/sevtinge/hyperceiler/libhook/rules/home/other/FreeformShortcutMenu;")
MENU_ITEM_SUFFIX = "getShortcutMenuType"
CONTROLLER = "ShortcutMenuGetXController"

# 四项功能在宿主符号表中的挂载点；未知项按缺符号处理，不猜测地址。
FEATURE_MOUNTS = (
    ("数量点", ("%s._getMaxCountInCurrentOrientation" % CONTROLLER,
                "%s._sortAndFilterShortcuts" % CONTROLLER)),
    ("分享 APK", ("ValidShareAppShortcutMenu.isValid",
                  "ValidShareAppShortcutMenu.onItemClick",
                  "ValidShareAppShortcutMenu.shareApk",
                  "ValidShareAppShortcutMenu.showDisclaimerDialog",
                  "ShareAppShortcutMenuItem.getShortcutMenuType")),
    ("小窗", ("ValidSmallWindowShortcutMenu.isValid",
              "ValidSmallWindowShortcutMenu.onItemClick",
              "ValidSmallWindowShortcutMenu._startSmallWindowActivity",
              "ValidSmallWindowShortcutMenu._findTaskId",
              "SmallWindowShortcutMenuItem.getShortcutMenuType")),
    # 上游把「新任务/多开」做成与小窗同族、仅多一个
    # FLAG_ACTIVITY_MULTIPLE_TASK；宿主对应类叫 MultipleSmallWindow，见 verify_task_mapping。
    ("新任务(宿主名 MultipleSmallWindow)", ("ValidMultipleSmallWindowShortcutMenu.isValid",
                                              "ValidMultipleSmallWindowShortcutMenu.onItemClick",
                                              "ValidMultipleSmallWindowShortcutMenu._startMultipleSmallWindow",
                                              "ValidMultipleSmallWindowShortcutMenu._isAppRunning",
                                              "MultipleSmallWindowShortcutMenuItem.getShortcutMenuType")),
)
# 「新任务」在宿主符号表里没有独立菜单项类；显式记录搜索关键词，使输出报告
# 「确实缺失」而不是静默留空。
NEW_TASK_PROBES = ("NewTask", "new_task", "FreeformShortcut", "FreeformMenuItem")
DEOBFUSCATOR = "Lorg/lsposed/lsparanoid/Deobfuscator$HyperCeiler$libhook;->getString"


def _instruction_sizes():
    """DEX 035/039 全部 opcode 的指令长度（单位：16 位 code unit）。

    未定义 opcode 记为 None，遇到时直接报错，避免按错误对齐继续盲扫而造假证据。
    """
    sizes = [None] * 256

    def fill(first, last, size):
        for opcode in range(first, last + 1):
            sizes[opcode] = size

    fill(0x00, 0x01, 1)
    sizes[0x02] = 2
    sizes[0x03] = 3
    sizes[0x04] = 1
    sizes[0x05] = 2
    sizes[0x06] = 3
    sizes[0x07] = 1
    sizes[0x08] = 2
    sizes[0x09] = 3
    fill(0x0A, 0x12, 1)
    sizes[0x13] = 2
    sizes[0x14] = 3
    sizes[0x15] = 2
    sizes[0x16] = 2
    sizes[0x17] = 3
    sizes[0x18] = 5
    sizes[0x19] = 2
    sizes[0x1A] = 2
    sizes[0x1B] = 3
    sizes[0x1C] = 2
    fill(0x1D, 0x1E, 1)
    fill(0x1F, 0x20, 2)
    sizes[0x21] = 1
    fill(0x22, 0x23, 2)
    fill(0x24, 0x26, 3)
    sizes[0x27] = 1
    sizes[0x28] = 1
    sizes[0x29] = 2
    sizes[0x2A] = 3
    fill(0x2B, 0x3D, 2)
    fill(0x44, 0x51, 2)
    fill(0x52, 0x6D, 2)
    fill(0x6E, 0x72, 3)
    fill(0x74, 0x78, 3)
    fill(0x7B, 0x8F, 1)
    fill(0x90, 0xAF, 2)
    fill(0xB0, 0xCF, 1)
    fill(0xD0, 0xE2, 2)
    sizes[0xFA] = 4
    sizes[0xFB] = 4
    sizes[0xFC] = 3
    sizes[0xFD] = 3
    sizes[0xFE] = 2
    sizes[0xFF] = 2
    return sizes


INSTRUCTION_SIZES = _instruction_sizes()

TYPE_OPS = frozenset((0x1C, 0x1F, 0x20, 0x22, 0x23, 0x24, 0x25))
INVOKE_OPS = frozenset(range(0x6E, 0x73)) | frozenset(range(0x74, 0x79))
FIELD_OPS = frozenset(range(0x52, 0x6E))


def host_symbols(host_elf):
    """解压 `.gnu_debugdata` 并解析 `.symtab`，返回 (符号列表, 调试段摘要)。"""
    image = ElfImage(host_elf)
    sections = [section for section in image.sections if section["name"] == ".gnu_debugdata"]
    require(len(sections) == 1 and sections[0]["size"], "宿主缺少唯一的 .gnu_debugdata")
    raw = image.section_data(sections[0])
    try:
        debug = lzma.decompress(raw)
    except lzma.LZMAError as error:
        raise AuditError(f".gnu_debugdata 解压失败：{error}") from error
    mini = ElfImage(debug)
    tables = [section for section in mini.sections if section["name"] == ".symtab"]
    require(len(tables) == 1, "调试段缺少唯一的 .symtab")
    table = tables[0]
    require(table["type"] == 2 and table["entry_size"] == 24 and table["size"] % 24 == 0,
            "符号表格式不符")
    require(table["link"] < len(mini.sections), "符号字符串表索引越界")
    strings = mini.section_data(mini.sections[table["link"]])
    symbols = []
    for off in range(table["offset"], table["offset"] + table["size"], 24):
        name, info, other, shndx, value, size = struct.unpack_from("<IBBHQQ", mini.data, off)
        text = mini.string(strings, name)
        if text:
            symbols.append((value, size, text))
    require(len(symbols) > 1000, "调试符号数量异常")
    return symbols, {"symbolCount": len(symbols), "debugBytes": len(debug)}


def menu_mounts(symbols):
    """列出全部菜单项类型，以及四项功能已定位的宿主挂载点。"""
    types = sorted({name.split(".")[0] for _, _, name in symbols
                    if name.endswith(MENU_ITEM_SUFFIX)})
    require("ShareAppShortcutMenuItem" in types and "SmallWindowShortcutMenuItem" in types
            and "MultipleSmallWindowShortcutMenuItem" in types, "菜单项类型集合变化")
    lookup = {}
    for value, size, name in symbols:
        lookup.setdefault(name, []).append((value, size))
    features = {}
    missing = {}
    for label, names in FEATURE_MOUNTS:
        resolved = {}
        absent = []
        for name in names:
            entries = lookup.get(name) or []
            if len(entries) == 1:
                value, size = entries[0]
                resolved[name] = {"address": hex(value), "size": size}
            else:
                absent.append(name)
        features[label] = resolved
        if absent:
            missing[label] = absent
    probes = {probe: [name for _, _, name in symbols if probe in name]
              for probe in NEW_TASK_PROBES}
    return {"菜单项类型数": len(types), "菜单项类型": types,
            "四项功能挂载点": features, "未能唯一的挂载点": missing,
            "新任务独立菜单项探测": probes}


class Dex:
    """最小只读 DEX 解析：够用即可，不执行 dexopt、不加载类。"""

    def __init__(self, data):
        require(data[:4] == b"dex\n", "不是 DEX 文件")
        self.data = data
        self.strings_size = self.u32(0x38)
        self.strings_off = self.u32(0x3C)
        self.type_count = self.u32(0x40)
        self.types_off = self.u32(0x44)
        self.fields_count = self.u32(0x50)
        self.fields_off = self.u32(0x54)
        self.methods_size = self.u32(0x58)
        self.methods_off = self.u32(0x5C)
        self.classes_size = self.u32(0x60)
        self.classes_off = self.u32(0x64)
        self.type_cache = {}
        self._class_data_offsets = None

    def u32(self, off):
        require(off + 4 <= len(self.data), "DEX 偏移越界")
        return struct.unpack_from("<I", self.data, off)[0]

    def uleb(self, off):
        value = shift = 0
        while True:
            require(off < len(self.data) and shift <= 28, "ULEB128 越界或超长")
            byte = self.data[off]
            off += 1
            value |= (byte & 0x7F) << shift
            if not byte & 0x80:
                return value, off
            shift += 7

    def string(self, index):
        require(0 <= index < self.strings_size, f"字符串下标越界：{index}")
        off = self.u32(self.strings_off + index * 4)
        size, cursor = self.uleb(off)
        require(cursor + size <= len(self.data), "字符串数据被截断")
        return self.data[cursor:cursor + size].decode("utf-8", "replace")

    def type_name(self, index):
        require(0 <= index < self.type_count, f"类型下标越界：{index}")
        if index not in self.type_cache:
            self.type_cache[index] = self.string(self.u32(self.types_off + index * 4))
        return self.type_cache[index]

    def method_ref(self, index):
        require(0 <= index < self.methods_size, f"方法下标越界：{index}")
        off = self.methods_off + index * 8
        class_index = struct.unpack_from("<H", self.data, off)[0]
        return self.type_name(class_index) + "->" + self.string(self.u32(off + 4))

    def field_ref(self, index):
        require(0 <= index < self.fields_count, f"字段下标越界：{index}")
        off = self.fields_off + index * 8
        class_index = struct.unpack_from("<H", self.data, off)[0]
        return self.type_name(class_index) + "." + self.string(self.u32(off + 4))

    def method_refs(self, code_off):
        """按指令长度表逐条解码，收集类型/方法/字段/字符串引用。

        不按 2 字节盲扫：否则会把指令立即数误当 opcode，解出伪造的常量下标。
        """
        require(code_off != 0, "方法没有代码体")
        require(code_off + 16 <= len(self.data), "代码头被截断")
        registers = struct.unpack_from("<H", self.data, code_off)[0]
        insns_size = self.u32(code_off + 12)
        start = code_off + 16
        end = start + insns_size * 2
        require(end <= len(self.data), "方法代码体被截断")
        types, methods, fields, strings = [], [], [], []
        cursor = start
        while cursor < end:
            opcode = self.data[cursor]
            width = INSTRUCTION_SIZES[opcode]
            require(width is not None, f"未定义 opcode：0x{opcode:02x}@0x{cursor:x}")
            require(cursor + width * 2 <= end, f"指令越过代码体尾部：0x{opcode:02x}")
            if opcode == 0x1A:  # const-string
                strings.append(self.string(struct.unpack_from("<H", self.data, cursor + 2)[0]))
            elif opcode == 0x1B:  # const-string/jumbo
                strings.append(self.string(self.u32(cursor + 2)))
            elif opcode in TYPE_OPS:
                types.append(self.type_name(struct.unpack_from("<H", self.data, cursor + 2)[0]))
            elif opcode in INVOKE_OPS:
                methods.append(self.method_ref(struct.unpack_from("<H", self.data, cursor + 2)[0]))
            elif opcode in FIELD_OPS:
                fields.append(self.field_ref(struct.unpack_from("<H", self.data, cursor + 2)[0]))
            cursor += width * 2
        return {"registers": registers, "insns": insns_size,
                "types": types, "methods": methods, "fields": fields, "strings": strings}

    def class_data_offsets(self):
        """一次遍历 class_defs，建立 class_idx -> class_data_off 映射。"""
        if self._class_data_offsets is None:
            mapping = {}
            for slot in range(self.classes_size):
                base = self.classes_off + slot * 32
                require(base + 32 <= len(self.data), "class_defs 被截断")
                mapping[self.u32(base)] = self.u32(base + 24)
            self._class_data_offsets = mapping
        return self._class_data_offsets

    def methods(self, class_index):
        """返回 [(method_index, access, code_off)]，无代码体的 code_off 为 0。"""
        data_off = self.class_data_offsets().get(class_index)
        if not data_off:
            return []
        cursor = data_off
        counts = []
        for _ in range(4):
            value, cursor = self.uleb(cursor)
            counts.append(value)
        for count in counts[:2]:  # static_fields / instance_fields
            index = 0
            for _ in range(count):
                delta, cursor = self.uleb(cursor)
                _, cursor = self.uleb(cursor)
                index += delta
        result = []
        for count in counts[2:]:  # direct_methods / virtual_methods
            index = 0
            for _ in range(count):
                delta, cursor = self.uleb(cursor)
                access, cursor = self.uleb(cursor)
                code, cursor = self.uleb(cursor)
                index += delta
                result.append((index, access, code))
        return result

    def method_name(self, index):
        require(0 <= index < self.methods_size, f"方法下标越界：{index}")
        off = self.methods_off + index * 8
        return self.string(self.u32(off + 4))

    def type_names(self):
        return [self.type_name(index) for index in range(self.type_count)]


def rule_targets(dex, class_name):
    """列出规则类引用的框架/宿主类型与混淆事实，不反推被混淆的宿主类名。"""
    require(class_name in set(dex.type_names()), f"参考 DEX 未包含规则类 {class_name}")
    result = {}
    for index in range(dex.type_count):
        name = dex.type_name(index)
        if name != class_name and not name.startswith(class_name[:-1] + "$"):
            continue
        methods = {}
        for method_index, _, code_off in dex.methods(index):
            if not code_off:
                continue
            refs = dex.method_refs(code_off)
            deobfuscator = refs["methods"].count(DEOBFUSCATOR)
            host_types = sorted({ref for ref in refs["types"]
                                 if ref.startswith(("Lcom/miui/home", "Lmiui/"))})
            framework = sorted({ref for ref in refs["types"] if ref.startswith(("Lio/", "Lorg/"))})
            literals = sorted(set(refs["strings"]))
            # 只保留有信号的条目；混淆后绝大多数方法在本 DEX 里没有任何可读引用。
            if not (deobfuscator or host_types or framework or literals):
                continue
            methods[dex.method_name(method_index)] = {
                "deobfuscatorCalls": deobfuscator,
                "hostTypes": host_types,
                "frameworkTypes": framework,
                "strings": literals,
            }
        result[name.split("/")[-1]] = methods
    return result


def verify_task_mapping(image, contract):
    """核验「小窗」与「新任务（多开）」的宿主映射。

    上游 `FreeformShortcutMenu` 把二者都做成 AppDetails 自定义项，区别只在
    `createFreeformIntent(component, isMultiTask)` 是否追加
    `FLAG_ACTIVITY_MULTIPLE_TASK`。宿主两条 `_start*SmallWindow*` 的 setFlags
    立即数必须恰好相差该标志，否则映射不成立、本函数直接失败。
    """
    require("taskMapping" in contract, "菜单挂载合同缺少 taskMapping 段")
    mapping = contract["taskMapping"]
    require(number(mapping["flagDiff"]) == FLAG_ACTIVITY_MULTIPLE_TASK,
            "合同声明的 flags 差值不是 FLAG_ACTIVITY_MULTIPLE_TASK")
    require(mapping["flagDiffName"].endswith("MULTIPLE_TASK"), "标志名称与常量不一致")
    require(len(mapping["windows"]) == 2, "taskMapping 必须恰好含两条 setFlags 窗口")
    immediates = []
    for window in mapping["windows"]:
        address = number(window["address"])
        require(address % 4 == 0, "指令地址未对齐")
        expected = bytes.fromhex(window["bytes"])
        require(len(expected) == 8, f"setFlags 窗口必须覆盖 mov+stur：{window['name']}")
        require(image.code(address, len(expected)) == expected,
                f"taskMapping 指令窗口不符：{window['name']}")
        immediates.append(_movz_immediate(expected))
    require([hex(value) for value in immediates] ==
            [window["expectedImmediate"] for window in mapping["windows"]],
            "setFlags 立即数与合同声明不一致")
    require(immediates[1] - immediates[0] == FLAG_ACTIVITY_MULTIPLE_TASK,
            "小窗与多开 setFlags 立即数差值不是 FLAG_ACTIVITY_MULTIPLE_TASK")
    return {"小窗 setFlags 立即数": hex(immediates[0]),
            "新任务(多开) setFlags 立即数": hex(immediates[1]),
            "差值": hex(immediates[1] - immediates[0]),
            "结论": "宿主 MultipleSmallWindow 路径即上游『新任务/多开』，与『小窗』同族；"
                    "两者共用 isAppRunning/启动路径，仅多任务标志不同",
            "限制": "仅由指令立即数与上游源码分支推定语义；不证明 OS4 实机菜单文本或行为"}


def _movz_immediate(code):
    """解出窗口首条 `mov xN, #imm`（MOVZ/MOVN 组合）的立即数。"""
    require(len(code) >= 4, "指令窗口过短")
    word = struct.unpack_from("<I", code, 0)[0]
    require(word & 0xFF800000 == 0xD2800000 or word & 0xFF800000 == 0xD2A00000,
            "首条指令不是 64 位 MOVZ 立即数装载")
    immediate = (word >> 5) & 0xFFFF
    shift = (word >> 21) & 3
    return immediate << (16 * shift)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host-elf", required=True, help="固定宿主 libapp.so")
    parser.add_argument("--rules-dex", help="参考模块 classes.dex（只读）")
    parser.add_argument("--contract", help="复核四项功能挂载点静态合同 JSON")
    parser.add_argument("--task-evidence", help="复核新任务映射取证 JSON 的机器字")
    args = parser.parse_args()
    try:
        symbols, debug = host_symbols(Path(args.host_elf).read_bytes())
        report = {
            "宿主符号段": debug,
            "菜单挂载": menu_mounts(symbols),
            "限制": "只读符号与常量取证；不证明这些规则在 OS4 宿主上可执行，"
                    "也不含任何安装路径、设备写入或参考代码执行；安装许可=false",
        }
        if args.contract:
            image = ElfImage(Path(args.host_elf).read_bytes())
            contract = json.loads(Path(args.contract).read_text(encoding="utf-8"))
            require(contract.get("installPermitted") is False, "合同不得声明安装许可")
            report["合同复核"] = verify_contract(image, contract)
            report["任务映射复核"] = verify_task_mapping(image, contract)
            report["合同摘要"] = digest(image.data)
        if args.task_evidence:
            image = ElfImage(Path(args.host_elf).read_bytes())
            evidence = json.loads(Path(args.task_evidence).read_text(encoding="utf-8"))
            require(evidence.get("installPermitted") is False, "取证不得声明安装许可")
            checked = {}
            for label, entry in evidence["hostEvidence"].items():
                if not isinstance(entry, dict) or "machineWord" not in entry:
                    continue
                address = number(entry["setFlags指令"])
                word = struct.unpack_from("<I", image.code(address, 4))[0]
                require(f"0x{word:08x}" == entry["machineWord"], f"{label} 机器字与取证不符")
                checked[label] = entry["machineWord"]
            require(len(checked) == 2, "取证必须含小窗与新任务两条 setFlags 机器字")
            report["新任务映射取证复核"] = checked
        if args.rules_dex:
            dex = Dex(Path(args.rules_dex).read_bytes())
            report["规则可见引用"] = {name: rule_targets(dex, name) for name in RULES}
            report["宿主类名可解性"] = (
                "参考 DEX 用 lsparanoid 混淆类名/常量，规则方法体内无明文宿主类名，"
                "故规则与宿主挂载点的对应关系不能由本 DEX 静态确定")
        require(args.rules_dex or args.contract or args.task_evidence,
                "至少提供 --rules-dex、--contract 或 --task-evidence")
        print(json.dumps(report, ensure_ascii=False, indent=2))
    except (AuditError, OSError, struct.error) as error:
        print(f"菜单宿主审计失败：{error}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
