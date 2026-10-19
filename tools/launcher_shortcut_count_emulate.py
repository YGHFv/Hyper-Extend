#!/usr/bin/env python3
"""在隔离的 ARM64 模拟器中执行数量蹦床与宿主比较指令；不生成 SO、不安装。"""

import argparse
import copy
import json
import struct
from pathlib import Path

from launcher_native_audit import DEFAULT_CONTRACT, AuditError, ElfImage, require, verify_contract


OWNER = 0x1147004
GETTER = 0x11474F4
CONTINUATION = 0x114701C
RESUME = 0x1147034
BRANCH = 0x1147054
KEEP = 0x1147058
CLIP = 0x1147068
ENTRY = 0x2000000
STATE = 0x2100000
REPLAY = 0x2200000
STACK = 0x3000000
C_STACK = 0x3100000
LIST = 0x3200000
DISPATCH = 0x3300000
LENGTH_GETTER = 0x3500000
STATE_SYMBOL = "launcher_shortcut_count_state"
ORIGINAL_SYMBOL = "launcher_shortcut_count_original"
CONTINUATION_SYMBOL = "launcher_shortcut_count_continuation"
BRANCH_SYMBOL = "launcher_count_branch_trial"


def effective_limit(state, stock):
    require(isinstance(state, int) and 0 <= state <= 0xffffffff, "状态必须为无符号 32 位整数")
    require(stock in (3, 4), "试验仅覆盖已取证的原生返回值 3/4")
    return state if 5 <= state <= 20 else stock


def relocate_word(word, kind, pc, target):
    """只在模拟器输入缓冲中处理本试验的三种重定位，不写宿主文件。"""
    require(0 <= pc < 1 << 64 and 0 <= target < 1 << 64 and pc % 4 == 0,
            "重定位地址越界或未对齐")
    if kind == 275:
        require(word & 0x9f000000 == 0x90000000, "页地址重定位不是 ADRP")
        delta = (target >> 12) - (pc >> 12)
        require(-(1 << 20) <= delta < 1 << 20, "ADRP 超出可达范围")
        bits = delta & 0x1fffff
        return (word & ~0x60ffffe0) | ((bits & 3) << 29) | ((bits >> 2) << 5)
    if kind == 277:
        require(word & 0xffc00000 == 0x91000000, "低位重定位不是未移位的 64 位 ADD")
        return (word & ~0x003ffc00) | ((target & 0xfff) << 10)
    if kind == 282:
        require(word & 0xfc000000 == 0x14000000, "跳转重定位不是 B")
        delta = target - pc
        require(delta % 4 == 0 and -(1 << 27) <= delta < 1 << 27, "B 超出可达范围")
        return 0x14000000 | ((delta // 4) & 0x03ffffff)
    raise AuditError("出现未审核的 ARM64 重定位")


class Arm64Object:
    """只接受独立汇编函数的 ET_REL；运行用的 .text 与重定位均须完整、唯一。"""

    def __init__(self, data, symbol):
        self.data = data
        require(len(data) >= 64 and data[:7] == b"\x7fELF\x02\x01\x01", "对象不是 ELF64 小端")
        kind, machine, version, entry, phoff, shoff, _, ehsize, _, phnum, shsize, shnum, names = (
            struct.unpack_from("<HHIQQQIHHHHHH", data, 16))
        require((kind, machine, version, entry, phoff, ehsize, phnum, shsize) ==
                (1, 183, 1, 0, 0, 64, 0, 64), "只接受无程序段的 ARM64 汇编对象")
        require(0 < names < shnum < 4096, "对象节表编号无效")
        self.read(shoff, shnum * 64)
        sections = [struct.unpack_from("<IIQQQQIIQQ", data, shoff + i * 64) for i in range(shnum)]
        require(sections[names][1] == 3, "对象缺少节名字符串表")
        strings = self.read(sections[names][4], sections[names][5])
        named = {}
        for index, section in enumerate(sections):
            name = ElfImage.string(strings, section[0])
            require(name not in named, "对象节名重复")
            named[name] = index
            if section[1] != 8:
                self.read(section[4], section[5])
            if section[2] & 2:
                require(name == ".text" and section[1:4] == (1, 6, 0), "对象包含额外运行期节")
        require(all(n in named for n in (".text", ".symtab", ".rela.text")), "对象缺少代码或重定位表")
        text_index = named[".text"]
        text = sections[text_index]
        require(0 < text[5] <= 512 and text[5] % 4 == 0, "试验函数长度不受支持")
        self.code = self.read(text[4], text[5])
        table = sections[named[".symtab"]]
        require(table[1] == 2 and table[9] == 24 and table[5] % 24 == 0
                and table[6] < shnum and sections[table[6]][1] == 3, "对象符号表无效")
        symbol_strings = self.read(sections[table[6]][4], sections[table[6]][5])
        symbols = []
        definitions = []
        for off in range(table[4], table[4] + table[5], 24):
            name, info, other, sec, value, size = struct.unpack_from("<IBBHQQ", data, off)
            name = ElfImage.string(symbol_strings, name)
            symbols.append((name, sec, value))
            if name == symbol:
                definitions.append((info, other, sec, value, size))
        require(definitions == [(0x12, 2, text_index, 0, len(self.code))],
                "试验入口不是唯一且覆盖整段的隐藏全局函数")
        reloc = sections[named[".rela.text"]]
        require(reloc[1] == 4 and reloc[6] == named[".symtab"] and reloc[7] == text_index
                and reloc[9] == 24 and reloc[5] % 24 == 0, "对象重定位表无效")
        self.relocations = []
        occupied = set()
        for off in range(reloc[4], reloc[4] + reloc[5], 24):
            address, info, addend = struct.unpack_from("<QQq", data, off)
            require(address % 4 == 0 and address + 4 <= len(self.code) and address not in occupied,
                    "对象重定位越界、重复或未对齐")
            occupied.add(address)
            require(info >> 32 < len(symbols) and addend == 0, "重定位符号或加数不受支持")
            name, sec, value = symbols[info >> 32]
            require(sec == 0 and value == 0, "只接受显式的外部模拟器地址")
            self.relocations.append((address, info & 0xffffffff, name))

    def read(self, offset, size):
        require(0 <= offset <= len(self.data) and 0 <= size <= len(self.data) - offset,
                "对象文件区间被截断")
        return self.data[offset:offset + size]

    def materialize(self, address, externals):
        require({name for _, _, name in self.relocations} == set(externals), "对象外部符号集合不符")
        code = bytearray(self.code)
        for offset, kind, name in self.relocations:
            word, = struct.unpack_from("<I", code, offset)
            struct.pack_into("<I", code, offset, relocate_word(word, kind, address + offset, externals[name]))
        return bytes(code)


def verify_trial_site(image):
    # 这是独立离线候选，不改现有 24 字节 continuation 合同。
    require(image.code(0x1147048, 16).hex() == "017c4193a3035ff83f0003ebac000054",
            "独立试验的解箱、上限读取、比较或 B.gt 已变化")
    require(image.code(CONTINUATION, 24).hex() == "e20300aaa1835ff8a2031ff820f05ff8007c4cd3e10100f9",
            "原数量接续窗口已变化")


class CountMachine:
    def __init__(self, image, obj, strategy):
        try:
            import unicorn as uc
            from unicorn import arm64_const as reg
        except ImportError as error:
            raise AuditError("缺少离线仿真依赖，请在本地虚拟环境安装 requirements-launcher-emulation.txt") from error
        require(strategy in ("continuation", "branch"), "未知试验策略")
        self.uc, self.reg = uc, reg
        self.machine = uc.Uc(uc.UC_ARCH_ARM64, uc.UC_MODE_ARM)
        self.gprs = [getattr(reg, f"UC_ARM64_REG_X{i}") for i in range(31)]
        self.qregs = [getattr(reg, f"UC_ARM64_REG_Q{i}") for i in range(32)]
        self.strategy = strategy
        self.failure = None
        self.code_size = len(obj.code)
        m = self.machine
        for address, size in [(OWNER & ~4095, 4096), (ENTRY, 4096), (STATE, 4096),
                              (REPLAY, 4096), (STACK, 0x4000), (C_STACK, 4096),
                              (LIST, 4096), (DISPATCH, 0x100000), (LENGTH_GETTER, 4096)]:
            m.mem_map(address, size)
        m.mem_write(OWNER, image.code(OWNER, CLIP - OWNER + 4))
        # Dart 两个被调函数用明确标记的返回值替身；不伪称执行了完整运行时。
        m.mem_write(GETTER, struct.pack("<I", 0xd65f03c0))
        m.mem_write(LENGTH_GETTER, struct.pack("<I", 0xd65f03c0))
        m.mem_write(LIST, struct.pack("<Q", 7 << 12))
        m.mem_write(DISPATCH + (7 + 0x17b1c) * 8, struct.pack("<Q", LENGTH_GETTER))
        externals = {STATE_SYMBOL: STATE}
        if strategy == "continuation":
            externals[ORIGINAL_SYMBOL] = STATE + 8
            replay = image.code(CONTINUATION, 24)
            replay += struct.pack("<I", relocate_word(0x14000000, 282, REPLAY + 24, RESUME))
            m.mem_write(REPLAY, replay)
            m.mem_write(STATE + 8, struct.pack("<Q", REPLAY))
        else:
            externals.update(launcher_count_trial_keep=KEEP, launcher_count_trial_clip=CLIP)
            # 仅修改模拟器里的副本，而且在执行开始之前完成；不模拟并发提交。
            m.mem_write(BRANCH, struct.pack("<I", relocate_word(0x14000000, 282, BRANCH, ENTRY)))
        m.mem_write(ENTRY, obj.materialize(ENTRY, externals))
        for address in (OWNER & ~4095, ENTRY, REPLAY, LENGTH_GETTER):
            m.mem_protect(address, 4096, uc.UC_PROT_READ | uc.UC_PROT_EXEC)
        m.mem_protect(LIST, 4096, uc.UC_PROT_READ)
        m.mem_protect(DISPATCH, 0x100000, uc.UC_PROT_READ)
        m.hook_add(uc.UC_HOOK_CODE, self.on_instruction)
        m.hook_add(uc.UC_HOOK_MEM_WRITE, self.on_write)

    def on_write(self, machine, access, address, size, value, context):
        # 允许原生函数帧和蹦床暂存区，不允许碰本次试验之外的堆、配置或 C 栈。
        if not self.top - 0xc0 <= address <= self.top - size:
            self.failure = "执行写入超出已审核的 Dart 栈窗口"
            machine.emu_stop()

    def on_instruction(self, machine, address, size, context):
        if address in (KEEP, CLIP):
            self.stop = address
            machine.emu_stop()
        elif address == GETTER:
            self.getter_calls += 1
            machine.reg_write(self.gprs[0], self.stock)
        elif address == LENGTH_GETTER:
            self.length_calls += 1
            machine.reg_write(self.gprs[0], self.length << 1)
        elif address == CONTINUATION and self.strategy == "continuation":
            self.before = [machine.reg_read(r) for r in self.gprs]
            self.before_flags = machine.reg_read(self.reg.UC_ARM64_REG_NZCV)
            machine.reg_write(self.reg.UC_ARM64_REG_PC, ENTRY)
        elif address == REPLAY and self.strategy == "continuation":
            after = [machine.reg_read(r) for r in self.gprs]
            expected = list(self.before)
            expected[0] = self.effective
            expected[16] = REPLAY
            if after != expected or machine.reg_read(self.reg.UC_ARM64_REG_NZCV) != self.before_flags:
                self.failure = "接续蹦床未保持寄存器/标志，或未正确处理上限"
                machine.emu_stop()
        elif not (OWNER <= address <= BRANCH or ENTRY <= address < ENTRY + self.code_size
                  or REPLAY <= address < REPLAY + 28):
            self.failure = "执行跳出试验允许的代码区间"
            machine.emu_stop()

    def run(self, stock, state, length, flags):
        m, r = self.machine, self.reg
        self.stock, self.length = stock, length
        self.effective = effective_limit(state, stock)
        self.failure, self.stop = None, None
        self.getter_calls, self.length_calls = 0, 0
        self.top = STACK + 0x3000
        m.mem_write(STACK, b"\xa5" * 0x4000)
        m.mem_write(C_STACK, b"\x5a" * 4096)
        m.mem_write(STATE, struct.pack("<I", state))
        initial = [((0xa51c000000000000 | (i << 36)) ^ (state << 4) ^ length) for i in range(31)]
        initial[1], initial[15] = LIST + 1, self.top
        initial[21], initial[29], initial[30] = DISPATCH, self.top + 0x200, 0x12345678
        for register, value in zip(self.gprs, initial):
            m.reg_write(register, value)
        for i, register in enumerate(self.qregs):
            m.reg_write(register, (0x123456789abcdef << 64) | (i << 32) | state)
        m.reg_write(r.UC_ARM64_REG_SP, C_STACK + 0x800)
        m.reg_write(r.UC_ARM64_REG_NZCV, flags << 28)
        try:
            m.emu_start(OWNER, 0, count=256)
        except self.uc.UcError as error:
            raise AuditError("ARM64 模拟器拒绝了指令、地址或访问权限") from error
        label = f"策略={self.strategy} 原值={stock} 状态={state} 长度={length} 标志={flags}"
        require(self.failure is None, f"{self.failure}；{label}")
        require(self.stop == (CLIP if length > self.effective else KEEP), f"裁剪分支错误或执行超时；{label}")
        require(self.getter_calls == self.length_calls == 1, f"原 getter/长度查询未各执行一次；{label}")
        require(m.reg_read(self.gprs[3]) == self.effective, f"比较寄存器不是裸整数上限；{label}")
        require(m.reg_read(self.gprs[0]) == length << 1 and m.reg_read(self.gprs[1]) == length,
                f"长度查询的 Smi/裸整数结果被改写；{label}")
        require(m.reg_read(self.gprs[2]) == (self.effective if self.strategy == "continuation" else stock),
                f"原生首条复制结果被非预期改写；{label}")
        require(m.reg_read(self.gprs[16]) == (REPLAY if self.strategy == "continuation" else initial[16])
                and m.reg_read(self.gprs[17]) == 0x17b1c
                and m.reg_read(self.gprs[30]) == 0x1147048, f"暂存寄存器或返回地址不符；{label}")
        frame_value, = struct.unpack("<Q", m.mem_read(self.top - 0x20, 8))
        require(frame_value == self.effective, f"后续裁剪读取的帧上限不符；{label}")
        for i in list(range(4, 15)) + list(range(18, 29)):
            require(m.reg_read(self.gprs[i]) == initial[i], f"活寄存器 x{i} 被改写；{label}")
        require(m.reg_read(self.gprs[15]) == self.top - 0xa0
                and m.reg_read(self.gprs[29]) == self.top - 0x10, f"Dart 栈或帧指针未恢复；{label}")
        require(m.reg_read(r.UC_ARM64_REG_SP) == C_STACK + 0x800
                and m.mem_read(C_STACK, 4096) == b"\x5a" * 4096, f"C 栈被改写；{label}")
        for i, register in enumerate(self.qregs):
            require(m.reg_read(register) == (0x123456789abcdef << 64) | (i << 32) | state,
                    f"向量寄存器 Q{i} 被改写；{label}")
        require(m.mem_read(STATE, 4) == struct.pack("<I", state), f"执行改写了配置状态；{label}")
        # CMP 的减法操作数都非负且很小：N、Z、C 可直接复算，V 必为零。
        expected_flags = ((length < self.effective) << 3) | ((length == self.effective) << 2) | (
            (length >= self.effective) << 1)
        require(m.reg_read(r.UC_ARM64_REG_NZCV) == expected_flags << 28, f"裁剪比较标志不符；{label}")


def run_suite(image, obj, strategy):
    machine = CountMachine(image, obj, strategy)
    count = 0
    for stock in (3, 4):
        for state in (*range(23), 0x7fffffff, 0x80000000, 0xffffffff):
            cap = effective_limit(state, stock)
            for length in sorted({0, stock - 1, stock, stock + 1, cap - 1, cap, cap + 1, 25}):
                for flags in range(16):
                    machine.run(stock, state, length, flags)
                    count += 1
    return {"策略": strategy, "行为用例": count,
            "故障注入已检出": verify_negative_controls(image, obj, strategy), "安装许可": False}


def verify_negative_controls(image, obj, strategy):
    """主动破坏关键指令，要求测试确实失败，避免只验证了脚本自己的期望值。"""
    controls = [
        (0x54000048, 1, "移除越界保护"),
        (0xd51b4209, 5, "移除标志恢复"),
        (0xa8c129e9, 5, "移除活寄存器恢复"),
    ] if strategy == "continuation" else [
        (0xf81f03a3, 5, "移除帧上限写回"),
        (0xa8c145f0, 5, "移除栈恢复"),
    ]
    for instruction, state, label in controls:
        words = [word for (word,) in struct.iter_unpack("<I", obj.code)]
        require(words.count(instruction) == 1, f"故障注入找不到唯一指令：{label}")
        damaged = copy.copy(obj)
        words[words.index(instruction)] = 0xd503201f
        damaged.code = struct.pack("<" + "I" * len(words), *words)
        machine = CountMachine(image, damaged, strategy)
        try:
            machine.run(3, state, 6, 0)
        except AuditError:
            continue
        raise AuditError(f"测试未能检出故障：{label}")
    return len(controls)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--elf", type=Path, required=True, help="已取证的本地宿主 SO，仅只读")
    parser.add_argument("--object", type=Path, required=True, help="只交叉汇编得到的数量接续 .o")
    parser.add_argument("--branch-object", type=Path, help="可选的独立分支候选 .o，不改变现有合同")
    args = parser.parse_args()
    try:
        image = ElfImage(args.elf.read_bytes())
        contract = json.loads(DEFAULT_CONTRACT.read_text(encoding="utf-8"))
        audit = verify_contract(image, contract)
        verify_trial_site(image)
        results = [run_suite(image, Arm64Object(args.object.read_bytes(), CONTINUATION_SYMBOL), "continuation")]
        if args.branch_object:
            results.append(run_suite(image, Arm64Object(args.branch_object.read_bytes(), BRANCH_SYMBOL), "branch"))
        print(json.dumps({"宿主合同": audit, "离线试验": results, "安装许可": False,
                          "限制": "getter 与长度查询使用返回值替身；只执行宿主前缀与裁剪判定。"
                          "不验证完整 Dart 运行时、菜单 UI、并发补丁提交或真实后端中继。"},
                         ensure_ascii=False, indent=2))
    except (AuditError, OSError, ValueError, KeyError, struct.error) as error:
        reason = str(error) if isinstance(error, AuditError) else type(error).__name__
        print(f"离线试验失败：{reason}；安装许可=false")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
