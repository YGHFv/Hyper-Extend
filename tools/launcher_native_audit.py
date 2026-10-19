#!/usr/bin/env python3
"""桌面快捷菜单的离线 ELF 合同校验；可选通过 MT MCP 只读复核指令。"""

import argparse
import hashlib
import json
import lzma
import re
import struct
from pathlib import Path


DEFAULT_CONTRACT = Path(__file__).resolve().parents[1] / "app/src/test/resources/launcher-shortcut-native-contract.json"
LIMIT = 1 << 64
DEBUG_LIMIT = 64 * 1024 * 1024
DECODER_MEMORY_LIMIT = 128 * 1024 * 1024


class AuditError(ValueError):
    """材料不完整或与已审核合同不符，必须停止而不是猜测。"""


def require(condition, message):
    if not condition:
        raise AuditError(message)


def span(start, size, limit=LIMIT):
    require(isinstance(start, int) and isinstance(size, int)
            and 0 <= start < limit and 0 <= size <= limit - start, "区间越界或溢出")
    return start + size


def number(value):
    return int(value, 0) if isinstance(value, str) else value


def digest(data):
    return hashlib.sha256(data).hexdigest()


class ElfImage:
    """仅接受本试点使用的 ELF64 小端 ARM64；不依赖反汇编器的最近符号标签。"""

    def __init__(self, data):
        self.data = data
        require(len(data) >= 64, "ELF 头被截断")
        require(data[:7] == b"\x7fELF\x02\x01\x01", "不是 ELF64 小端文件")
        header = struct.unpack_from("<HHIQQQIHHHHHH", data, 16)
        kind, machine, version, _, phoff, shoff, _, ehsize, phsize, phnum, shsize, shnum, names = header
        require(kind == 3 and machine == 183 and version == 1 and ehsize == 64, "不支持的 ELF 类型或架构")
        require(phnum != 0xffff and shnum > 0 and names < shnum, "不支持缺失或扩展编号的节表")
        require(shsize == 64 and (not phnum or phsize == 56), "ELF 表项大小不符")
        self.read(shoff, shnum * shsize)
        self.read(phoff, phnum * phsize)
        self.segments = []
        for index in range(phnum):
            typ, flags, off, va, _, filesz, memsz, align = struct.unpack_from("<IIQQQQQQ", data, phoff + index * phsize)
            self.read(off, filesz)
            span(va, memsz)
            require(filesz <= memsz, "文件段大于内存段")
            require(align in (0, 1) or (align & (align - 1) == 0 and va % align == off % align), "段对齐无效")
            self.segments.append(dict(type=typ, flags=flags, offset=off, address=va, size=filesz, memory=memsz))
        loads = sorted((s for s in self.segments if s["type"] == 1 and s["memory"]), key=lambda s: s["address"])
        for left, right in zip(loads, loads[1:]):
            require(left["address"] + left["memory"] <= right["address"], "PT_LOAD 内存区间重叠")
        self.sections = []
        for index in range(shnum):
            name, typ, flags, va, off, size, link, _, align, entsize = struct.unpack_from("<IIQQQQIIQQ", data, shoff + index * shsize)
            if typ != 8:
                self.read(off, size)
            self.sections.append(dict(name_offset=name, type=typ, flags=flags, address=va,
                                      offset=off, size=size, link=link, entry_size=entsize))
        require(self.sections[names]["type"] == 3, "节名表类型不符")
        strings = self.section_data(self.sections[names])
        for section in self.sections:
            section["name"] = self.string(strings, section["name_offset"])

    def read(self, offset, size):
        end = span(offset, size, len(self.data) + 1)
        require(end <= len(self.data), "文件区间被截断")
        return self.data[offset:end]

    @staticmethod
    def string(table, offset):
        require(0 <= offset < len(table), "字符串偏移越界")
        end = table.find(b"\0", offset)
        require(end >= 0, "字符串缺少结束符")
        return table[offset:end].decode("utf-8", errors="strict")

    def section_data(self, section):
        require(section["type"] != 8, "无文件内容的节不可读取")
        return self.read(section["offset"], section["size"])

    def section(self, name):
        matches = [s for s in self.sections if s["name"] == name]
        require(len(matches) == 1, f"节必须唯一：{name}")
        return matches[0]

    def file_offset(self, address, size, executable=False):
        end = span(address, size)
        require(size > 0, "地址窗口不能为空")
        matches = [s for s in self.segments if s["type"] == 1 and s["address"] <= address
                   and end <= s["address"] + s["size"] and (not executable or s["flags"] & 1)]
        require(len(matches) == 1, "虚拟地址没有唯一的文件映射")
        segment = matches[0]
        return segment["offset"] + address - segment["address"]

    def code(self, address, size):
        require(address % 4 == 0 and size % 4 == 0, "ARM64 指令窗口未对齐")
        return self.read(self.file_offset(address, size, executable=True), size)

    def build_id(self):
        values = []
        for segment in self.segments:
            if segment["type"] != 4:
                continue
            raw = self.read(segment["offset"], segment["size"])
            pos = 0
            while pos < len(raw):
                require(pos + 12 <= len(raw), "NOTE 头被截断")
                namesz, descsz, kind = struct.unpack_from("<III", raw, pos)
                name = pos + 12
                desc = name + ((namesz + 3) & ~3)
                end = desc + ((descsz + 3) & ~3)
                require(end <= len(raw), "NOTE 内容被截断")
                if raw[name:name + namesz] == b"GNU\0" and kind == 3:
                    values.append(raw[desc:desc + descsz].hex())
                pos = end
        require(len(values) == 1 and values[0], "Build ID 缺失或不唯一")
        return values[0]

    def debug_image(self):
        raw = self.section_data(self.section(".gnu_debugdata"))
        decoder = lzma.LZMADecompressor(memlimit=DECODER_MEMORY_LIMIT)
        decoded = decoder.decompress(raw, max_length=DEBUG_LIMIT + 1)
        require(len(decoded) <= DEBUG_LIMIT and decoder.eof and not decoder.unused_data, "调试符号过大、截断或有尾随数据")
        return ElfImage(decoded)

    def functions(self):
        symbols = self.section(".symtab")
        require(symbols["type"] == 2 and symbols["entry_size"] == 24 and symbols["size"] % 24 == 0, "符号表格式不符")
        require(symbols["link"] < len(self.sections), "符号字符串表索引越界")
        strings = self.sections[symbols["link"]]
        require(strings["type"] == 3, "符号字符串表类型不符")
        table = self.section_data(strings)
        result = []
        for off in range(symbols["offset"], symbols["offset"] + symbols["size"], 24):
            name, info, _, section, address, size = struct.unpack_from("<IBBHQQ", self.data, off)
            if info & 15 == 2 and section != 0:
                require(section < len(self.sections), "函数节索引无效")
                result.append(dict(name=self.string(table, name), address=address, size=size))
        return result


def direct_call_target(address, instruction):
    word = struct.unpack("<I", instruction)[0]
    require(word & 0xfc000000 == 0x94000000, "调用点不是 ARM64 BL")
    displacement = word & 0x3ffffff
    if displacement & 0x2000000:
        displacement -= 0x4000000
    target = address + displacement * 4
    span(target, 4)
    return target


def direct_branch(word, address):
    if word & 0x7c000000 == 0x14000000:
        kind, encoded, bits = ("BL" if word & 0x80000000 else "B"), word & 0x3ffffff, 26
    elif word & 0xff000000 == 0x54000000:
        kind, encoded, bits = "B.cond/BC.cond", (word >> 5) & 0x7ffff, 19
    elif word & 0x7e000000 == 0x34000000:
        kind, encoded, bits = "CBNZ" if word & 0x1000000 else "CBZ", (word >> 5) & 0x7ffff, 19
    elif word & 0x7e000000 == 0x36000000:
        kind, encoded, bits = "TBNZ" if word & 0x1000000 else "TBZ", (word >> 5) & 0x3fff, 14
    else:
        return None
    signed = encoded - (1 << bits) if encoded & (1 << (bits - 1)) else encoded
    return kind, address + signed * 4


# 位置相关指令：中继副本按覆盖窗口逐字复制原指令，若窗口里出现这类指令，
# 复制到中继缓冲区后目标地址会偏到错误的地方。审计要求覆盖窗口内的前缀全部
# 位置无关，否则即使字节一致也必须拒绝该候选。
def is_position_independent(word):
    if word & 0x7c000000 == 0x14000000:   # B / BL
        return False
    if word & 0xff000000 == 0x54000000:   # B.cond / BC.cond
        return False
    if word & 0x7e000000 == 0x34000000:   # CBZ / CBNZ
        return False
    if word & 0x7e000000 == 0x36000000:   # TBZ / TBNZ
        return False
    if word & 0x1f000000 == 0x10000000:   # ADR / ADRP
        return False
    if word & 0x3b000000 == 0x18000000:   # LDR（字面量）/ LDRSW
        return False
    if word & 0xff000000 == 0x58000000:   # 64 位字面量 LDR
        return False
    return True


# ARM DDI 0487 L.a B2.2.5「Concurrent modification and execution of instructions」
# 把下面这组指令列为「旧、新都属于本集合时，无需显式执行端同步，架构保证语义等价于
# 取到旧或新指令之一」的并发修改例外集合：
#
#   B, B.cond, BL, BRK, CBNZ, CBZ, HVC, ISB, NOP, SMC, SVC, TBNZ, TBZ, TRCIT
#
# 注意：这是**指令级**例外，只说明被并发替换的那一个 32 位机器字是否安全，
# 不说明：
#   - 多字覆盖（例如 24 字节 Dobby 足迹一次改多条）也安全；
#   - 替换后的新目标代码已经完成缓存同步；
#   - 调用方返回地址、Dart 栈图/GC、副作用语义已经审核。
# 因此本判定只用于「单字选择」这一层，不能当成整体安装许可。
def concurrent_modification_kind(word):
    """返回该机器字在 B2.2.5 例外集合中的类别；不属于该集合返回 None。"""
    if word & 0x7C000000 == 0x14000000:      # B / BL（bit31 区分）
        return "BL" if word & 0x80000000 else "B"
    if word & 0xFF000000 == 0x54000000:      # B.cond / BC.cond
        return "B.cond"
    if word == 0xD503201F:                    # NOP（HINT #0）
        return "NOP"
    if word == 0xD5033FDF:                    # ISB
        return "ISB"
    if word & 0xFFE0001F == 0xD4200000:      # BRK #imm16
        return "BRK"
    if word & 0xFFE0001F == 0xD4000001:      # SVC #imm16
        return "SVC"
    if word & 0xFFE0001F == 0xD4000002:      # HVC #imm16
        return "HVC"
    if word & 0xFFE0001F == 0xD4000003:      # SMC #imm16
        return "SMC"
    if word & 0x7E000000 == 0x34000000:      # CBZ / CBNZ（bit24 区分）
        return "CBNZ" if word & 0x01000000 else "CBZ"
    if word & 0x7E000000 == 0x36000000:      # TBZ / TBNZ（bit24 区分）
        return "TBNZ" if word & 0x01000000 else "TBZ"
    return None


def concurrent_modification_eligible(word):
    """该机器字是否属于 B2.2.5 的无同步并发修改例外集合。"""
    return concurrent_modification_kind(word) is not None


# 覆盖窗口按 Dobby 常规中继的补丁足迹取值。上游 backend 在 `trampoline_arm64.cc`
# 里按源到目标的距离二选一：近距分支用 `adrp+add+br`（12 字节，单条 `B` 就地改写），
# 远距分支用 `ldr+br+字面量`（16 字节指令 + 8 字节字面量 = 24 字节）。
#
# 本模块 hook 的原目标是**当前宿主进程里内存映射（base+偏移）**的 Dart AOT 函数，
# 到后端任意可分配中继页的距离远超 `adrp` 的 ±4GiB，实测直接命中远距分支：
# 该设备桌面进程里被 HyperCeiler 打过 hook 的 libc 函数（`__system_property_get`、
# `setprogname`）代码页开头是 `91 00 00 58` = `ldr x17, #16 ; br x17 ; nop ; nop ;
# <8 字节字面量>`（字面量落在偏移 16，即窗口末尾的 8 字节），
# 覆盖长度 24 字节。因此本项目合同固定取 24。
#
# 逐尺寸的覆盖前缀（覆盖窗口必须以该字节串开头，且只含位置无关指令）：
#   12 → `mov x2,x0 ; ldur x1 ; stur x2`，恢复点 0x1147028
#   16 → 追加 `ldur x0`，恢复点 0x114702c
#   24 → 追加 `ubfx x0 ; str x1`，恢复点 0x1147034
# 采用 24 时窗口内 `ldur x0, [x1, #-0x10]` 读取的列表长度会在恢复点被 `ubfx`
# 重新解释，说明候选 2「先在窗口前改写 x0，再由窗口首条 `mov x2, x0` 转发新值」
# 的语义与覆盖长度无关；覆盖长度只决定中继副本包含几条原指令。
CONTINUATION_COVERAGE = {
    12: "e20300aaa1835ff8a2031ff8",
    16: "e20300aaa1835ff8a2031ff820f05ff8",
    24: "e20300aaa1835ff8a2031ff820f05ff8007c4cd3e10100f9",
}


def verify_continuation(image, contract):
    candidate = contract["continuation"]
    require(candidate["policy"] == "post-getter-raw-limit-v1", "不支持的接续策略")
    start, call, resume = (number(candidate[key]) for key in ("address", "callAddress", "resumeAddress"))
    coverage = candidate["coverageBytes"]
    require(coverage in CONTINUATION_COVERAGE,
            "覆盖长度必须是已审核的 Dobby 足迹（12/16/24 字节），不允许自动调整")
    expected_prefix = CONTINUATION_COVERAGE[coverage]
    require(call + 4 == start and resume == start + coverage,
            "接续起点或恢复点与已审核候选不符")
    owners = [f for f in contract["functions"] if f["name"] == candidate["owner"]]
    getters = [f for f in contract["functions"] if f["name"] == candidate["getter"]]
    require(len(owners) == len(getters) == 1, "接续所属函数或 getter 不唯一")
    owner, getter = owners[0], number(getters[0]["address"])
    require(number(owner["address"]) <= call and resume + 4 <= number(owner["address"]) + owner["size"],
            "接续窗口或恢复指令越过所属函数")
    require(direct_call_target(call, image.code(call, 4)) == getter, "接续前并非原 getter 调用")
    require(image.code(start, coverage) == bytes.fromhex(expected_prefix),
            "接续指令不再是已审核的 raw 上限复制与列表恢复序列")
    prefix = image.code(start, coverage)
    require(all(is_position_independent(word) for (word,) in struct.iter_unpack("<I", prefix)),
            "覆盖窗口前缀含有位置相关指令，复制进中继后会偏离目标")
    image.code(resume, 4)
    segments = [s for s in image.segments if s["type"] == 1 and s["flags"] & 1 and s["size"]]
    require(segments, "缺少可执行文件段")
    getter_edges, window_edges, scanned = [], [], 0
    for segment in segments:
        require(not segment["flags"] & 2, "可写执行段不属于已审核宿主")
        raw = image.code(segment["address"], segment["size"])
        scanned += len(raw)
        for index, (word,) in enumerate(struct.iter_unpack("<I", raw)):
            address = segment["address"] + index * 4
            branch = direct_branch(word, address)
            if branch is None:
                continue
            kind, target = branch
            if target == getter:
                getter_edges.append((address, kind))
            if start <= target < resume:
                window_edges.append((address, kind, target))
    require(getter_edges == [(call, "BL")], "getter 的直接入口集合发生变化")
    require(not window_edges, "存在直接跳入接续覆盖窗口的指令，拒绝候选")
    return {"候选地址": hex(start), "覆盖字节数": coverage, "恢复地址": hex(resume),
            "扫描执行字节数": scanned, "getter直接入口数": len(getter_edges),
            "窗口直接入口数": len(window_edges), "安装许可": False,
            "限制": "仅固定指令及直接分支静态审计；覆盖长度取自 Dobby 常规中继足迹，"
                    "仍不证明间接入口、异常入口或并发安装安全"}


def verify_contract(image, contract):
    require(contract["schemaVersion"] == 1, "合同版本不受支持")
    require(digest(image.data) == contract["image"]["sha256"], "SO 摘要不符")
    if "size" in contract["image"]:
        require(len(image.data) == contract["image"]["size"], "SO 文件长度不符")
    require(image.build_id() == contract["image"]["buildId"], "SO Build ID 不符")
    symbols = image.debug_image().functions()
    functions = contract["functions"]
    require(functions and contract["calls"] and contract["windows"], "合同不得省略函数、调用边或指令窗口")
    require(len({f["name"] for f in functions}) == len(functions), "合同存在重复函数")
    for expected in functions:
        matches = [s for s in symbols if s["name"] == expected["name"]]
        require(len(matches) == 1, f"函数候选不唯一：{expected['name']}")
        symbol = matches[0]
        address, size = number(expected["address"]), expected["size"]
        require(symbol["address"] == address and symbol["size"] == size and size > 0, "函数范围不符")
        require(digest(image.code(address, size)) == expected["sha256"], f"函数内容不符：{expected['name']}")
    for call in contract["calls"]:
        address, target = number(call["address"]), number(call["target"])
        owners = [f for f in functions if number(f["address"]) <= address < number(f["address"]) + f["size"]]
        require(len(owners) == 1 and owners[0]["name"] == call["owner"], "调用点不在指定函数内")
        require(direct_call_target(address, image.code(address, 4)) == target, "调用目标不符")
        image.code(target, 4)
    for window in contract["windows"]:
        expected = bytes.fromhex(window["bytes"])
        require(expected and image.code(number(window["address"]), len(expected)) == expected, f"指令窗口不符：{window['name']}")
    result = {"函数数": len(functions), "调用边数": len(contract["calls"]), "指令窗口数": len(contract["windows"])}
    if "continuation" in contract:
        result["接续候选"] = verify_continuation(image, contract)
    return result


def verify_startup_contract(image, contract):
    require(len(image.data) == contract["image"]["size"] and digest(image.data) == contract["image"]["sha256"],
            "原生启动库长度或摘要不符")
    require(len(contract["functions"]) == 1, "原生启动合同必须只包含已审核入口")
    expected = contract["functions"][0]
    require(expected["name"] == "app_entry_point", "原生启动入口名称不符")
    table = image.section(".dynsym")
    require(table["type"] == 11 and table["entry_size"] == 24 and table["size"] % 24 == 0,
            "原生启动库动态符号表格式不符")
    require(table["link"] < len(image.sections), "原生启动库字符串表索引越界")
    strings_section = image.sections[table["link"]]
    require(strings_section["type"] == 3, "原生启动库字符串表类型不符")
    strings = image.section_data(strings_section)
    matches = []
    for off in range(table["offset"], table["offset"] + table["size"], 24):
        name, info, other, section, address, size = struct.unpack_from("<IBBHQQ", image.data, off)
        symbol = image.string(strings, name)
        require(not (symbol == "JNI_OnLoad" and section != 0), "原生启动库出现未审核的 JNI 入口")
        if symbol == expected["name"]:
            require(info == 0x12 and other == 0 and 0 < section < len(image.sections),
                    "原生启动符号不是唯一可见的全局函数定义")
            matches.append((address, size))
    start, size = number(expected["address"]), expected["size"]
    require(matches == [(start, size)], "原生启动函数范围或唯一性不符")
    require(digest(image.code(start, size)) == expected["sha256"], "原生启动函数摘要不符")
    observations = [(0x18, "f30308aa"), (0x278, "680200a9"), (0x288, "680a00f9"),
                    (0x290, "f44f48a9f65747a9f85f46a9fd7b45a9ff430291c0035fd6")]
    require(size == 960, "原生启动函数长度不属于已审核实现")
    for offset, raw in observations:
        require(image.code(start + offset, len(bytes.fromhex(raw))) == bytes.fromhex(raw),
                "原生启动入口保存 x8、写回结果或返回指令发生变化")
    return {"入口": expected["name"], "地址": hex(start), "字节数": size,
            "返回存储": "保存 x8 到 x19，并经 x19 写入偏移 0、8、16 的三个机器字",
            "加载路径已证明": False,
            "限制": "仅确认入口实现；不提供函数原型、返回对象所有权或加载器调用方证明"}


def verify_disassembly(image, data, address, size):
    require(not data.get("truncated") and not data.get("pagination", {}).get("hasMore"), "MCP 指令结果不完整")
    lines = data["disassemblyText"].splitlines()
    require(len(lines) == size // 4, "MCP 指令数量不符")
    raw = bytearray()
    for index, line in enumerate(lines):
        match = re.fullmatch(r"0x([0-9a-fA-F]+): ([0-9a-fA-F]{8}) .+", line.strip())
        require(match is not None and int(match[1], 16) == address + index * 4, "MCP 指令地址不连续")
        raw.extend(bytes.fromhex(match[2]))
    require(number(data["mapping"]["fileOffset"]) == image.file_offset(address, size, executable=True), "MCP 文件映射不符")
    require(raw == image.code(address, size), "MCP 与本地指令不一致")
    return len(raw)


def verify_manifest_bytes(data, expected):
    require(not data.get("truncated") and not data.get("pagination", {}).get("hasMore"), "MCP 清单结果不完整")
    window = data["byteWindow"]
    require(window["byteOffset"] == 0 and window["nextByteOffset"] == expected["size"]
            and window["totalBytes"] == expected["size"], "MCP 清单字节区间不符")
    raw = bytes.fromhex(window["hex"])
    require(len(raw) == expected["size"] and digest(raw) == expected["sha256"], "MCP 清单摘要不符")
    return len(raw)


def verify_mcp(image, contract, url, workspace=None):
    from mt_systemui_probe import MtSession, open_host

    session = MtSession(url)
    host = open_host(session, contract["host"], workspace)
    require(host.get("counts", {}).get("dexEntries") == 0 and host.get("counts", {}).get("classes") == 0,
            "MCP 宿主不符合无 DEX 合同")
    manifest_bytes = 0
    if "manifest" in contract:
        expected = contract["manifest"]
        require(expected["locator"] == "zip_entry:AndroidManifest.xml" and 0 < expected["size"] <= 65536,
                "宿主清单合同不受支持")
        result = session.request("tools/call", {"name": "mt_apk_read_bytes", "arguments": {
            "workspaceId": host["workspaceId"], "editSessionId": "", "locator": expected["locator"],
            "byteOffset": 0, "maxBytes": 65536}})
        require(not result.get("isError"), "MCP 清单读取失败")
        payload = result.get("structuredContent") or json.loads(next(item["text"] for item in result["content"] if item["type"] == "text"))
        require(payload["ok"], "MCP 未返回有效清单")
        manifest_bytes = verify_manifest_bytes(payload["data"], expected)
    total = 0
    for function in contract["functions"]:
        start, remaining = number(function["address"]), function["size"]
        while remaining:
            size = min(remaining, 800)
            # 工具名和只读参数固定；不会接受编辑会话或调用任意工具。
            args = dict(workspaceId=host["workspaceId"], editSessionId="", locator=contract["image"]["locator"],
                        addressKind="virtual", address=hex(start), instructionMode="auto",
                        stopAtSymbolBoundary=False, maxBytes=size, maxInstructions=200)
            result = session.request("tools/call", {"name": "mt_apk_native_disassemble", "arguments": args})
            require(not result.get("isError"), "MCP 只读反汇编失败")
            payload = result.get("structuredContent") or json.loads(next(x["text"] for x in result["content"] if x["type"] == "text"))
            require(payload["ok"], "MCP 未返回有效指令")
            total += verify_disassembly(image, payload["data"], start, size)
            start += size
            remaining -= size
    return {"核验字节数": total, "清单核验字节数": manifest_bytes, "说明": "仅静态指令与清单一致，不代表运行期挂载通过"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--elf", type=Path, required=True, help="只读分析的本地 libapp.so")
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT, help="已审核的目标合同")
    parser.add_argument("--mcp-url", help="可选的 MT MCP 地址；不保存到文件")
    parser.add_argument("--workspace", help="可选的已保存工作区；仍重新核对宿主身份")
    parser.add_argument("--entry-elf", type=Path, help="可选的本地 libapp_launcher.so；只分析，不加载或调用")
    args = parser.parse_args()
    try:
        require(not args.workspace or args.mcp_url, "指定工作区时必须提供 MCP 地址")
        contract = json.loads(args.contract.read_text(encoding="utf-8"))
        image = ElfImage(args.elf.read_bytes())
        result = {"离线合同": verify_contract(image, contract)}
        if args.entry_elf:
            entry_image = ElfImage(args.entry_elf.read_bytes())
            result["启动入口合同"] = verify_startup_contract(entry_image, contract["startup"])
        if args.mcp_url:
            result["MCP"] = verify_mcp(image, contract, args.mcp_url, args.workspace)
            if args.entry_elf:
                entry_contract = {"host": contract["host"], **contract["startup"]}
                result["启动入口MCP"] = verify_mcp(entry_image, entry_contract, args.mcp_url, args.workspace)
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except (ValueError, TypeError, KeyError, OSError, RuntimeError, struct.error, lzma.LZMAError, StopIteration) as error:
        # 不回显 URL 或任意远端错误内容，避免把环境信息混入可分享的报告。
        reason = str(error) if isinstance(error, AuditError) else type(error).__name__
        print(f"校验失败：{reason}；未修改宿主或合同")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
