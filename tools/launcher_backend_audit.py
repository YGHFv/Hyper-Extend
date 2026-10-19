#!/usr/bin/env python3
"""只读审计留存框架补丁机器码；可选在模拟器执行，绝不加载到本机或设备进程。"""

import argparse
import json
import struct
from pathlib import Path

from launcher_native_audit import (
    AuditError, DEFAULT_CONTRACT, ElfImage, digest, number, require, verify_contract,
)


CONTRACT = Path(__file__).resolve().parents[1] / "app/src/test/resources/launcher-backend-sample-contract.json"
PATCH = 0xA5324
FRAMEWORK_BASE = 0x1000000
LIBC_BASE = 0x2000000
TARGET_REGION = 0x3000000
SOURCE = 0x4000000
STACK = 0x5000000
STUBS = 0x6000000
STOP = 0x7000000
REPLACEMENT = 0x8000000
COPY_VARIANTS = {"simd": 0x6F300, "scalar": 0x6F4C0, "nt": 0xA60C0}
CACHE_OPS = {0xA54D4: "dc_cvau", 0xA54F4: "dsb_ish", 0xA5544: "ic_ivau",
             0xA555C: "dsb_ish", 0xA5560: "isb"}

# 从模块入口包装到 Dobby 控制流、routing 提交和补丁例程的精确函数边界，用于证明
# 这条路径不调用任何线程/锁/等待原语，因此不会与正在执行目标函数的线程协作。
# 边界按样本内相邻函数切分，避免把邻近无关函数（例如创建线程的辅助函数）算进来。
HOOK_REGIONS = (
    (0x7D298, 0x7D2D8),   # Dobby 局部 backup 包装
    (0x89358, 0x8938C),   # 模块 original 回填包装
    (0x8D764, 0x8D78C),   # 模块 hook 入口包装
    (0x9D498, 0x9D764),   # Dobby hook 控制流
    (0x9D764, 0x9D79C),   # routing 构造
    (0x9D79C, 0x9D7C0),   # routing 基类构造
    (0x9DD20, 0x9DE80),   # routing 提交与虚调用分派
    (0xA5324, 0xA542C),   # 补丁例程（改页 + 返回值）
    (0xA542C, 0xA5448),   # 复制适配器
    (0xA5448, 0xA5568),   # 缓存维护例程
    (0xA5568, 0xA5574),   # 缓存适配器
)
# 这条链只允许出现这些导入；任何线程/锁/等待原语都在 COORDINATION_SYMBOLS 中。
HOOK_IMPORTS = {
    "sysconf": [0xA5338], "mprotect": [0xA536C, 0xA5394, 0xA53C4, 0xA53E8],
    "__memcpy_chk": [0xA5438],
}
COORDINATION_SYMBOLS = {
    "pthread_mutex_lock", "pthread_mutex_unlock", "pthread_create", "pthread_join",
    "pthread_detach", "pthread_cond_wait", "pthread_cond_signal", "pthread_cond_broadcast",
    "pthread_once", "nanosleep", "sched_yield", "syscall",
}

# 门槛 5 的第二条否定性证据：整个框架样本都不存在「暂停其它执行线程」的手段。
# 这些导入或标记一旦出现，就说明后端可能在安装期与正在执行目标函数的线程协作；
# 全部缺失则证明唯一出路只能是「安装窗口内无并发执行」或换用无覆盖写窗口的协议。
THREAD_SUSPENSION_SYMBOLS = {
    "ptrace", "tgkill", "pthread_kill", "pthread_sigmask", "kill", "raise", "sigqueue",
    "process_vm_readv", "process_vm_writev", "clone", "clone3", "clone3_set_tid",
}
THREAD_SUSPENSION_MARKERS = (b"/proc/self/task", b"/proc/self/status", b"SIGSTOP",
                             b"PTRACE_ATTACH", b"ptrace")

# 覆盖长度 24 的正面依据：近距单字插件 `NearBranchTrampolinePlugin` 只有被显式
# 调用 `dobby_enable_near_branch_trampoline()` 才会注册（源码里该注册函数是
# 唯一把 `RoutingPluginManager::near_branch_trampoline` 置为非空的路径；
# `InterceptRouting::GenerateTrampolineBuffer` 只在它非空时才走插件分支）。
# 框架样本里没有下列任一符号或日志串，就说明本设备后端没有启用近距分支，
# 远距 `ldr+br+nop+nop+字面量`（24 字节）足迹成立。
NEAR_BRANCH_MARKERS = (
    b"near_branch_trampoline", b"dobby_enable_near_branch_trampoline",
    b"dobby_disable_near_branch_trampoline", b"register %s plugin",
    b"forward trampoline use", b"Failed enable near branch trampoline plugin",
)
_NEAR_BRANCH_DYNAMIC_TABLE = b"near_branch_trampoline"


def audit_near_branch_plugin(framework, libc):
    """复核近距单字插件在本样本里没有启用，因此 24 字节远距合同成立。

    近距插件命中时覆盖长度会退化为单条 `B`（4 字节），会直接推翻本项目固定
    的 24 字节覆盖窗口。这里同时检查框架与 libc 样本，避免只看一侧。
    """
    found = {}
    for name, image in (("framework", framework), ("libc", libc)):
        hits = [marker.decode("ascii") for marker in NEAR_BRANCH_MARKERS
                if image.data.count(marker)]
        # 插件注册会把字符串字面量 `.data.rel.ro` 的指针表也带进来；额外确认
        # 「近距插件」这个词整体在样本里一次都不出现。
        if image.data.count(_NEAR_BRANCH_DYNAMIC_TABLE):
            hits.append(_NEAR_BRANCH_DYNAMIC_TABLE.decode("ascii"))
        found[name] = sorted(set(hits))
    present = {name: hits for name, hits in found.items() if hits}
    require(not present, f"框架样本出现近距分支插件标记，24 字节覆盖合同不再成立：{present}")
    return {"已搜索标记": [marker.decode("ascii") for marker in NEAR_BRANCH_MARKERS],
            "命中": [], "结论": "未启用近距单字插件，远距 24 字节足迹成立",
            "限制": "只证明留存的框架/libc 样本里没有该插件的符号与日志串；"
                    "不排除未留样的其它后端版本"}


def relr_addresses(raw):
    """解码 ELF RELR 位图；这里只返回需加加载偏移的位置，不修改输入文件。"""
    require(len(raw) % 8 == 0, "RELR 大小错误")
    cursor = None
    result = set()
    for off in range(0, len(raw), 8):
        word, = struct.unpack_from("<Q", raw, off)
        if word & 1:
            require(cursor is not None, "RELR 位图前没有地址")
            positions = [cursor + (bit - 1) * 8 for bit in range(1, 64) if word & (1 << bit)]
            cursor += 63 * 8
        else:
            require(word % 8 == 0, "RELR 地址未对齐")
            positions = [word]
            cursor = word + 8
        require(not result.intersection(positions), "RELR 地址重复")
        require(all(p <= (1 << 64) - 8 for p in positions), "RELR 地址溢出")
        result.update(positions)
    return result


def relocations(image):
    symbols = image.section(".dynsym")
    strings = image.section_data(image.sections[symbols["link"]])
    require(symbols["entry_size"] == 24 and symbols["size"] % 24 == 0, "动态符号表形状错误")
    names = []
    for off in range(symbols["offset"], symbols["offset"] + symbols["size"], 24):
        name, = struct.unpack_from("<I", image.data, off)
        names.append(image.string(strings, name))
    entries, packed = {}, set()
    for section in image.sections:
        if section["name"] == ".relr.dyn":
            packed = relr_addresses(image.section_data(section))
        if section["type"] != 4 or not section["flags"] & 2:
            continue
        require(section["entry_size"] == 24 and section["size"] % 24 == 0, "RELA 形状错误")
        for off in range(section["offset"], section["offset"] + section["size"], 24):
            address, info, addend = struct.unpack_from("<QQq", image.data, off)
            symbol, kind = info >> 32, info & 0xffffffff
            require(symbol < len(names) and address not in entries, "重定位符号错误或地址重复")
            entries[address] = (kind, names[symbol], addend)
    require(not packed.intersection(entries), "RELR 与 RELA 重叠")
    return entries, packed


def pointer_value(image, address):
    return struct.unpack("<Q", image.read(image.file_offset(address, 8), 8))[0]


def plt_symbols(image):
    """把 .plt 桩地址映射到导入符号名；只读，不执行重定位。"""
    require(any(s["name"] == ".plt" for s in image.sections)
            and any(s["name"] == ".rela.plt" for s in image.sections),
            "样本缺少 .plt 或 .rela.plt，无法核验导入调用")
    symbols = image.section(".dynsym")
    strings = image.section_data(image.sections[symbols["link"]])
    names = [image.string(strings, struct.unpack_from("<I", image.data, off)[0])
             for off in range(symbols["offset"], symbols["offset"] + symbols["size"], 24)]
    rela = next(section for section in image.sections if section["name"] == ".rela.plt")
    got = {}
    for off in range(rela["offset"], rela["offset"] + rela["size"], 24):
        address, info = struct.unpack_from("<QQ", image.data, off)
        got[address] = names[info >> 32]
    plt = next(section for section in image.sections if section["name"] == ".plt")
    stubs = {}
    # Android 链接器的 PLT0 解析器占 .plt 开头 32 字节，之后每 16 字节一个桩，
    # 顺序与 .rela.plt 一致；PLT0 与末尾填充都不算导入桩。
    count = (plt["size"] - 32) // 16
    require(count == len(got), "PLT 桩数量与 .rela.plt 不一致")
    for index in range(count):
        stub = plt["address"] + 32 + index * 16
        first, second, _, _ = struct.unpack_from(
            "<IIII", image.data, plt["offset"] + 32 + index * 16)
        require((first & 0x9F000000) == 0x90000000 and (second & 0xFFC00000) == 0xF9400000,
                "PLT 桩形状变化")
        immediate = (((first >> 5) & 0x7FFFF) << 2) | ((first >> 29) & 3)
        if immediate & (1 << 20):
            immediate -= 1 << 21
        slot = ((stub & ~0xFFF) + (immediate << 12)) + (((second >> 10) & 0xFFF) * 8)
        stubs[stub] = got.get(slot)
    return stubs


def direct_calls(image, low, high):
    """扫描 [low, high) 内对齐的 BL 目标地址，供导入调用与协调原语检查使用。"""
    targets = {}
    for address in range(low, high, 4):
        word, = struct.unpack("<I", image.read(image.file_offset(address, 4), 4))
        if (word & 0xFC000000) != 0x94000000:
            continue
        immediate = word & 0x3FFFFFF
        if immediate & (1 << 25):
            immediate -= 1 << 26
        targets.setdefault(address + immediate * 4, []).append(address)
    return targets


def imported_calls(image, low, high):
    """收集区间内的导入调用：BL 到 .plt 桩，以及 adrp+ldr 常量传播出的 GOT 槽。"""
    entries, _ = relocations(image)
    slots = {address: entry[1] for address, entry in entries.items()
             if isinstance(entry[1], str) and entry[1]}
    stubs = plt_symbols(image)
    found = {}
    for target, sites in direct_calls(image, low, high).items():
        symbol = stubs.get(target)
        if symbol:
            found.setdefault(symbol, []).extend(sites)
    registers = {}
    for address in range(low, high, 4):
        word, = struct.unpack("<I", image.read(image.file_offset(address, 4), 4))
        if (word & 0x9F000000) == 0x90000000:  # ADRP：记录页基址
            immediate = (((word >> 5) & 0x7FFFF) << 2) | ((word >> 29) & 3)
            if immediate & (1 << 20):
                immediate -= 1 << 21
            registers[word & 0x1F] = (address & ~0xFFF) + (immediate << 12)
        elif (word & 0xFFC00000) == 0xF9400000:  # LDR (64-bit) [Xn, #imm]
            base = registers.get((word >> 5) & 0x1F)
            if base is not None:
                slot = base + (((word >> 10) & 0xFFF) * 8)
                if slot in slots:
                    found.setdefault(slots[slot], []).append(address)
    return found


def verify_samples(framework, libc, contract):
    require(contract["schemaVersion"] == 1 and contract["installPermitted"] is False, "合同不允许授予安装许可")
    for name, image in (("framework", framework), ("libc", libc)):
        expected = contract["images"][name]
        require(len(image.data) == expected["size"] and digest(image.data) == expected["sha256"],
                f"{name} 完整样本摘要不匹配")
        require(image.build_id() == expected["buildId"], f"{name} Build ID 不匹配")
        for function in expected["functions"]:
            require(digest(image.code(number(function["address"]), function["size"])) == function["sha256"],
                    f"{name} 指令范围不匹配：{function['name']}")
    entries, packed = relocations(framework)
    for slot, target in contract["pointerSlots"].items():
        address = number(slot)
        if target.startswith("0x"):
            require(address in packed and pointer_value(framework, address) == number(target),
                    f"内部指针槽不匹配：{slot}")
        else:
            require(entries.get(address) == (257, target, 0), f"导入指针槽不匹配：{slot}")
    for slot, targets in contract["branchTables"].items():
        address = number(slot)
        for index, target in enumerate(targets):
            at = address + index * 8
            require(at in packed and pointer_value(framework, at) == number(target), "间接分支表不匹配")
    # 不允许仅刷新摘要就把关键调用或忽略返回值的行为解释成另外一种协议。
    for address, words in {
        0xA5370: (0x528000E2, 0x8A080297, 0xD63F0120, 0xEB17031F),
        0xA53C0: (0xD63F0100, 0xF9411748),
        0xA53D0: (0x528000A2, 0xD63F0100, 0xD503201F),
        0xA540C: (0xD63F0100, 0xA9444FF4, 0x2A1F03E0),
    }.items():
        require(framework.code(address, len(words) * 4) == struct.pack("<" + "I" * len(words), *words),
                "补丁的权限、复制或返回值指令发生变化")
    # 提交路径本身不能与执行目标函数的线程协作：模块包装、Dobby 控制流、
    # routing 提交和补丁例程区间内不得出现任何锁、线程或等待原语。这既证明
    # 后端没有“暂停执行者”的协议，也是门槛 5 仍不放行的机器码依据。
    imported = {}
    for low, high in HOOK_REGIONS:
        for symbol, sites in imported_calls(framework, low, high).items():
            imported.setdefault(symbol, []).extend(sites)
    coordination = {name: sites for name, sites in imported.items() if name in COORDINATION_SYMBOLS}
    require(not coordination, f"Hook 提交路径出现线程/锁/等待原语：{coordination}")
    require(imported == HOOK_IMPORTS,
            f"Hook 提交路径导入调用集合变化：{ {k: [hex(v) for v in s] for k, s in imported.items()} }")
    suspension = audit_thread_suspension(framework)
    near_branch = audit_near_branch_plugin(framework, libc)
    return {"框架BuildID": framework.build_id(), "补丁例程": hex(PATCH),
            "复制路径": "0xa53c0 -> 0xa542c -> __memcpy_chk",
            "恢复后缓存同步": "0xa540c -> 0xa5568 -> 0xa5448",
            "提交路径导入调用": {name: [hex(site) for site in sites]
                                 for name, sites in sorted(imported.items())},
            "提交路径协调原语": [],
            "全样本线程挂起机制": suspension,
            "近距分支插件": near_branch,
            "限制": "样本范围取证；未证明当前设备映射一致；仅证明包装/Hook/补丁路径"
                    "本身不含线程协调，未覆盖更上层调用者或宿主加载状态",
            "安装许可": False}


def audit_thread_suspension(framework):
    """全样本扫描：既无挂起导入，也无挂起标记，证明后端不具备暂停执行线程的手段。

    这不等于门槛 5 通过：它只排除「后端会暂停其它线程再改代码」这条假设，
    剩下仍须靠「安装窗口内无并发执行」或换用无覆盖写窗口的发布协议。
    """
    executable = [section for section in framework.sections if section["flags"] & 4]
    require(executable, "框架缺少可执行节，无法扫描挂起机制")
    low = min(section["address"] for section in executable)
    high = max(section["address"] + section["size"] for section in executable)
    symbols = {name for name in imported_calls(framework, low, high) if name}
    present_symbols = sorted(symbols & THREAD_SUSPENSION_SYMBOLS)
    markers = [marker.decode("ascii", "replace") for marker in THREAD_SUSPENSION_MARKERS
               if framework.data.count(marker)]
    require(not present_symbols, f"框架存在线程挂起导入：{present_symbols}")
    require(not markers, f"框架存在线程挂起标记：{markers}")
    return {"扫描范围": [hex(low), hex(high)], "挂起导入": [],
            "挂起标记": [],
            "已搜索标记": [marker.decode("ascii", "replace") for marker in THREAD_SUSPENSION_MARKERS],
            "结论": "缺少挂起原语，后端不能暂停其它执行线程；门槛 5 仍须独立证据"}


def patch_bytes():
    # 只构造既有 24 字节远跳转形状的模拟器输入，不是运行期补丁生成或安装接口。
    return struct.pack("<IIIIQ", 0x58000091, 0xD61F0220, 0xD503201F, 0xD503201F, REPLACEMENT)


def mixed_jump_target(raw, patch):
    require(len(raw) == len(patch) == 24, "只检查固定的 24 字节样例")
    if raw[:8] == patch[:8] and raw[16:] != patch[16:]:
        return struct.unpack_from("<Q", raw, 16)[0]
    return None


class PatchMachine:
    """真实样本指令；sysconf/mprotect 是替身，缓存指令只记事件，不模拟一致性。"""

    def __init__(self, framework, libc, stock, variant, page_size, crossing, failure):
        import unicorn as uc
        from unicorn import arm64_const as reg
        self.uc, self.reg = uc, reg
        self.machine = uc.Uc(uc.UC_ARCH_ARM64, uc.UC_MODE_ARM)
        self.page_size, self.failure = page_size, failure
        self.target = TARGET_REGION + page_size - 16 if crossing else TARGET_REGION + 0x1c
        self.patch, self.stock = patch_bytes(), stock
        self.events, self.snapshots, self.permissions = [], [], {}
        self.protect_count, self.pending_store = 0, None
        self.expected_fault = False
        self.gprs = [getattr(reg, f"UC_ARM64_REG_X{i}") for i in range(31)]
        self.load(framework, FRAMEWORK_BASE, {"sysconf": STUBS, "mprotect": STUBS + 16,
                                            "__memcpy_chk": LIBC_BASE + 0xBD680})
        self.load(libc, LIBC_BASE, {"memcpy": LIBC_BASE + COPY_VARIANTS[variant]})
        m = self.machine
        for address, size in ((TARGET_REGION, 0x10000), (SOURCE, 4096), (STACK, 0x10000),
                              (STUBS, 4096), (STOP, 4096)):
            m.mem_map(address, size)
        m.mem_write(SOURCE, self.patch)
        m.mem_write(self.target, stock)
        m.mem_protect(TARGET_REGION, 0x10000, uc.UC_PROT_READ | uc.UC_PROT_EXEC)
        for address in range(TARGET_REGION, TARGET_REGION + 0x10000, page_size):
            self.permissions[address] = 5
        m.reg_write(reg.UC_ARM64_REG_SP, STACK + 0xff00)
        m.reg_write(reg.UC_ARM64_REG_LR, STOP)
        m.reg_write(self.gprs[0], self.target)
        m.reg_write(self.gprs[1], SOURCE)
        m.reg_write(self.gprs[2], 24)
        m.hook_add(uc.UC_HOOK_CODE, self.on_code)
        m.hook_add(uc.UC_HOOK_MEM_WRITE, self.on_write)

    def load(self, image, base, imports):
        m = self.machine
        end = max(s["address"] + s["memory"] for s in image.segments if s["type"] == 1)
        require(end < 0x400000, "样本映像过大")
        m.mem_map(base, (end + 4095) & ~4095)
        for segment in image.segments:
            if segment["type"] == 1 and segment["size"]:
                m.mem_write(base + segment["address"], image.read(segment["offset"], segment["size"]))
        entries, packed = relocations(image)
        for address in packed:
            value = pointer_value(image, address)
            m.mem_write(base + address, struct.pack("<Q", base + value))
        for address, (kind, symbol, addend) in entries.items():
            if kind == 1027:
                value = base + addend
            elif symbol in imports:
                require(kind in (257, 1025, 1026) and addend == 0, "替身导入重定位类型错误")
                value = imports[symbol]
            else:
                # 未运行加载构造器，未解析的导入保留为零；若误入它们，模拟器必须失败。
                continue
            m.mem_write(base + address, struct.pack("<Q", value))

    def on_write(self, machine, _access, address, size, _value, _data):
        if self.target <= address < self.target + 24:
            self.pending_store = machine.reg_read(self.reg.UC_ARM64_REG_PC)

    def on_code(self, machine, address, _size, _data):
        if self.pending_store is not None:
            raw = bytes(machine.mem_read(self.target, 24))
            self.snapshots.append({"pc": hex(self.pending_store - LIBC_BASE), "bytes": raw.hex()})
            self.events.append("store")
            self.pending_store = None
        if address == STUBS:
            require(machine.reg_read(self.gprs[0]) == 39, "不是已核验的页大小查询")
            self.events.append("sysconf")
            self.return_from_stub(self.page_size)
        elif address == STUBS + 16:
            self.protect_count += 1
            page, length, prot = [machine.reg_read(self.gprs[i]) for i in range(3)]
            require(page in self.permissions and length == self.page_size and prot in (5, 7),
                    "出现未审核的改页请求")
            failed = ((self.failure == "enable" and self.protect_count == 1) or
                      (self.failure == "restore" and prot == 5))
            self.events.append(f"mprotect:{prot}:{'fail' if failed else 'ok'}")
            if not failed:
                machine.mem_protect(page, length, prot)
                self.permissions[page] = prot
            self.return_from_stub((1 << 64) - 1 if failed else 0)
        elif address == FRAMEWORK_BASE + 0xA5484:
            self.events.append("read_ctr")
            machine.reg_write(self.gprs[9], 0x00040004)
            machine.reg_write(self.reg.UC_ARM64_REG_PC, address + 4)
        elif address - FRAMEWORK_BASE in CACHE_OPS:
            self.events.append(CACHE_OPS[address - FRAMEWORK_BASE])
            machine.reg_write(self.reg.UC_ARM64_REG_PC, address + 4)

    def return_from_stub(self, value):
        self.machine.reg_write(self.gprs[0], value)
        self.machine.reg_write(self.reg.UC_ARM64_REG_PC, self.machine.reg_read(self.reg.UC_ARM64_REG_LR))

    def run(self):
        m = self.machine
        try:
            m.emu_start(FRAMEWORK_BASE + PATCH, STOP, count=10000)
        except self.uc.UcError as error:
            require(self.failure == "enable" and error.errno == self.uc.UC_ERR_WRITE_PROT,
                    f"样本执行出现非预期异常：{error}")
            self.expected_fault = True
        if self.failure == "enable":
            require(self.expected_fault, "权限失败没有被验证为继续尝试写入")
            return {"权限失败后仍尝试写入": True,
                    "写入故障指令": hex(m.reg_read(self.reg.UC_ARM64_REG_PC) - LIBC_BASE)}
        require(m.reg_read(self.reg.UC_ARM64_REG_PC) == STOP, "样本执行超出指令预算")
        require(m.reg_read(self.gprs[0]) == 0, "补丁例程返回值发生变化")
        require(bytes(m.mem_read(self.target, 24)) == self.patch, "复制结果不是完整样例")
        require(len(self.snapshots) == 2, "24 字节复制不再是两个存储指令")
        witness = mixed_jump_target(bytes.fromhex(self.snapshots[0]["bytes"]), self.patch)
        require(witness is not None, "未复现新跳转头与旧字面量中间态")
        require(self.events.index("store") < self.events.index("dc_cvau") < self.events.index("ic_ivau")
                < self.events.index("isb"), "缓存事件顺序发生变化")
        require(self.events.index("store") < self.events.index(
            "mprotect:5:fail" if self.failure == "restore" else "mprotect:5:ok"),
            "恢复权限必须位于复制之后")
        if self.failure == "restore":
            require(any(prot == 7 for prot in self.permissions.values()), "未保留恢复失败后的 RWX 状态")
        else:
            require(all(prot == 5 for prot in self.permissions.values()), "正常返回后仍存在 RWX")
        return {"存储指令": [s["pc"] for s in self.snapshots], "混合字面量": hex(witness),
                "中间态": self.snapshots[0]["bytes"], "返回值": 0,
                "恢复失败但仍成功": self.failure == "restore",
                "事件顺序": self.events}


def emulate_samples(framework, libc, stock):
    counts = {"正常路径": 0, "写权限失败": 0, "恢复权限失败": 0}
    examples, faults = {}, {}
    for variant in COPY_VARIANTS:
        for page_size in (4096, 16384):
            for crossing in (False, True):
                for failure in ("none", "enable", "restore"):
                    machine = PatchMachine(framework, libc, stock, variant, page_size, crossing, failure)
                    result = machine.run()
                    counts[{"none": "正常路径", "enable": "写权限失败", "restore": "恢复权限失败"}[failure]] += 1
                    if page_size == 4096 and not crossing and failure == "none":
                        examples[variant] = result
                    if page_size == 4096 and not crossing and variant == "simd" and failure != "none":
                        faults[failure] = result
    return {"场景数": sum(counts.values()), "复现": counts, "中间态示例": examples, "故障示例": faults,
            "限制": "真实补丁和复制指令；权限/页大小为替身，缓存只记事件；未模拟 MOPS、硬件取指或完整框架初始化",
            "安装许可": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--framework", required=True, help="本地留存框架 SO")
    parser.add_argument("--libc", required=True, help="本地留存 libc SO")
    parser.add_argument("--host-elf", required=True, help="固定宿主 libapp.so")
    parser.add_argument("--emulate", action="store_true", help="仅在 Unicorn 地址空间运行审计范围")
    args = parser.parse_args()
    try:
        framework = ElfImage(Path(args.framework).read_bytes())
        libc = ElfImage(Path(args.libc).read_bytes())
        host = ElfImage(Path(args.host_elf).read_bytes())
        verify_contract(host, json.loads(DEFAULT_CONTRACT.read_text(encoding="utf-8")))
        contract = json.loads(CONTRACT.read_text(encoding="utf-8"))
        result = {"静态审计": verify_samples(framework, libc, contract)}
        if args.emulate:
            result["离线执行"] = emulate_samples(framework, libc, host.code(0x114701C, 24))
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except (AuditError, KeyError, OSError, ImportError, struct.error) as error:
        print(f"后端样本审计失败：{error}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
