#!/usr/bin/env python3
"""执行真实菜单动作与入口蹦床，检查寄存器现场、ACK 路径与透明回退。"""
import struct
import subprocess
from pathlib import Path

from launcher_native_audit import ElfImage, require
from launcher_safety_parity_audit import find_clang
from launcher_hook_points_audit import ROOT


def compile_menu(directory):
    output = Path(directory) / "menu-frame.so"
    command = [
        str(find_clang(None, ROOT)), "--target=aarch64-linux-android26",
        "-shared", "-nostdlib", "-Wl,-Bsymbolic", "-Wl,--no-undefined",
        str(ROOT / "app/src/main/cpp/launcher/launcher_menu_arm64.S"),
        str(ROOT / "app/src/test/cpp/launcher_menu_frame_trial.cpp"), "-o", str(output),
    ]
    result = subprocess.run(command, capture_output=True)
    require(result.returncode == 0, result.stderr.decode("utf-8", "replace"))
    return ElfImage(output.read_bytes())


def symbol(image, name):
    table = image.section(".symtab")
    strings = image.section_data(image.sections[table["link"]])
    matches = []
    for offset in range(table["offset"], table["offset"] + table["size"], 24):
        name_offset, _, _, section, address, _ = struct.unpack_from("<IBBHQQ", image.data, offset)
        if section and image.string(strings, name_offset) == name:
            matches.append(address)
    require(len(matches) == 1, "测试符号必须唯一")
    return matches[0]


def run_share_trace_frame(menu, host, primary, bridge_result=0):
    """在上报前的不透明蹦床入口执行，核对 C++ 调用不会污染 Dart 与 AArch64 状态。"""
    import unicorn as uc
    from unicorn import arm64_const as reg

    source, module = 0x10000000, 0x14000000
    entry = module + symbol(
        menu, "launcher_menu_primary_trace" if primary else "launcher_menu_secondary_trace")

    machine = uc.Uc(uc.UC_ARCH_ARM64, uc.UC_MODE_ARM)
    stack, heap, target, stop = 0x20000000, 0x21000000, 0x22000000, 0x23000000
    for base, image in [(source, host), (module, menu)]:
        size = max(s["address"] + s["memory"] for s in image.segments if s["type"] == 1)
        machine.mem_map(base, (size + 4095) & ~4095)
        for segment in image.segments:
            if segment["type"] == 1 and segment["size"]:
                machine.mem_write(base + segment["address"], image.read(segment["offset"], segment["size"]))
        for section in image.sections:
            if section["type"] == 4 and section["flags"] & 2:
                for at in range(section["offset"], section["offset"] + section["size"], 24):
                    address, info, addend = struct.unpack_from("<QQq", image.data, at)
                    require(info == 1027, "帧试验不加载外部重定位")
                    machine.mem_write(base + address, struct.pack("<Q", base + addend))
    machine.mem_map(heap, 0x40000)
    machine.mem_map(stack - 0x20000, 0x40000)
    machine.mem_map(target, 0x10000)
    machine.mem_map(stop, 0x10000)

    slot = module + symbol(
        menu, "launcher_menu_original_primary" if primary
        else "launcher_menu_original_share_secondary")
    expected = {}
    for index in range(31):
        expected[index] = 0x5150000000000000 | (index << 16) | (1 if primary else 2)
    expected[15], expected[22], expected[26] = (
        (stack + 0xf000) | (1 if primary else 2), heap + 0x1001, 0x26000000)
    expected[27], expected[29], expected[30] = heap + 0x2001, stack + 0xff00, source + 0x234
    # 主、次调用点在 BL shareApk 前都把包名句柄写入 x1；桥测试用第三个堆页
    # 模拟字符串对象，同时故意给 x2 另一个值以捕获取错寄存器的回归。
    expected[1] = heap + 0x3001
    machine.mem_write(expected[1] + 7, struct.pack("<I", 19))
    machine.mem_write(expected[1] + 11, b"com.tencent.mobileqq")
    initial_q = [(0xA5A5 << 112) | index | ((1 if primary else 2) << 32) for index in range(32)]
    machine.mem_write(slot, struct.pack("<Q", target))
    for index, value in expected.items():
        machine.reg_write(getattr(reg, f"UC_ARM64_REG_X{index}"), value)
    for index, value in enumerate(initial_q):
        machine.reg_write(getattr(reg, f"UC_ARM64_REG_Q{index}"), value)
    machine.reg_write(reg.UC_ARM64_REG_SP, stack + 0xe000)
    machine.reg_write(reg.UC_ARM64_REG_NZCV, 0xF0000000)
    entered = []
    bridge_arguments = []

    def runtime(m, address, size, opaque):
        if address == module + symbol(menu, "launcher_share_bridge"):
            bridge_arguments.append((
                m.reg_read(reg.UC_ARM64_REG_X0),
                m.reg_read(reg.UC_ARM64_REG_X1),
            ))
            # 模拟 C++ 桥的 ACK 或失败码；成功返回调用点+4，失败尾跳原闭包。
            m.reg_write(reg.UC_ARM64_REG_X0, bridge_result)
            m.reg_write(reg.UC_ARM64_REG_PC, m.reg_read(reg.UC_ARM64_REG_LR))
            return
        if address != target:
            return
        entered.extend(m.reg_read(getattr(reg, f"UC_ARM64_REG_X{index}"))
                       for index in range(31) if index not in (16, 17))
        require(m.reg_read(reg.UC_ARM64_REG_X30) == expected[30], "尾跳前未恢复 x30")
        require(m.reg_read(reg.UC_ARM64_REG_SP) == stack + 0xe000, "尾跳前未恢复 C 栈")
        require(m.reg_read(reg.UC_ARM64_REG_NZCV) == 0xF0000000, "尾跳前未保留条件标志")
        actual_q = [m.reg_read(getattr(reg, f"UC_ARM64_REG_Q{index}")) for index in range(32)]
        require(actual_q == initial_q, "尾跳前未保留向量寄存器")
        m.emu_stop()

    # Unicorn 在 `emu_start(begin, until)` 中先检查下一条 PC 是否等于 `until`，再调用
    # UC_HOOK_CODE。若把 `stop` 直接作为 until，尾跳后的 PC 已等于 `stop` 时不会触发
    # 代码钩子，测试会误以为蹦床没有抵达目标。这里把 until 放到目标之后的哨兵地址，
    # 只由 `runtime` 在真正进入尾跳目标时停止。
    machine.hook_add(uc.UC_HOOK_CODE, runtime)
    # 成功路径把原闭包槽指向 target；失败路径则必须尾跳同一槽。
    # 两条路径都通过 target 钩子核对完整现场，不能在桥失败时直接返回。
    machine.mem_write(expected[30], struct.pack("<I", 0xD503201F))
    machine.emu_start(entry, expected[30], count=10000)
    if bridge_result == 0:
        require(entered == [], "分享桥 ACK 路径仍进入了宿主 shareApk")
        require(machine.reg_read(reg.UC_ARM64_REG_PC) == expected[30],
                "分享桥没有返回调用点+4")
        require(machine.reg_read(reg.UC_ARM64_REG_X0) == expected[22],
                "分享桥没有返回 Dart null")
    else:
        require(entered == [expected[index] for index in range(31)
                            if index not in (16, 17)],
                "分享桥失败后没有完整恢复并尾跳原闭包")
    require(bridge_arguments == [(1 if primary else 2, expected[1])],
            "分享桥点位或 tagged 包名参数不符")
    for index in (19, 20, 21, 23, 24, 25, 26, 27, 28, 29):
        require(machine.reg_read(getattr(reg, f"UC_ARM64_REG_X{index}")) == expected[index],
                "分享桥破坏调用者寄存器 x%d" % index)
    actual_q = [machine.reg_read(getattr(reg, f"UC_ARM64_REG_Q{index}")) for index in range(32)]
    require(actual_q == initial_q, "分享桥破坏向量寄存器")
    return {"入口": "主" if primary else "次", "桥结果": bridge_result,
            "寄存器与向量": "通过", "桥参数": "通过"}


def run_share_entry_frame(menu, host, primary, bridge_result, disclaimer=False):
    """执行真实闭包入口蹦床，核对包名解析、ACK 消费与透明回退现场。"""
    import unicorn as uc
    from unicorn import arm64_const as reg

    source, module = 0x10000000, 0x14000000
    entry_name = ("launcher_menu_share_entry_primary" if primary
                  else "launcher_menu_share_entry_secondary")
    entry = module + symbol(menu, entry_name)
    machine = uc.Uc(uc.UC_ARCH_ARM64, uc.UC_MODE_ARM)
    stack, heap, target, stop = 0x20000000, 0x21000000, 0x22000000, 0x23000000
    for base, image in [(source, host), (module, menu)]:
        size = max(s["address"] + s["memory"] for s in image.segments if s["type"] == 1)
        machine.mem_map(base, (size + 4095) & ~4095)
        for segment in image.segments:
            if segment["type"] == 1 and segment["size"]:
                machine.mem_write(base + segment["address"],
                                  image.read(segment["offset"], segment["size"]))
        for section in image.sections:
            if section["type"] == 4 and section["flags"] & 2:
                for at in range(section["offset"], section["offset"] + section["size"], 24):
                    address, info, addend = struct.unpack_from("<QQq", image.data, at)
                    require(info == 1027, "入口帧试验不加载外部重定位")
                    machine.mem_write(base + address, struct.pack("<Q", base + addend))
    # 入口函数使用 1568 字节栈帧；调用者栈从 stack+0xe000 向下展开，
    # 必须把完整窗口映射为连续区域。
    machine.mem_map(heap, 0x40000)
    machine.mem_map(stack - 0x20000, 0x40000)
    machine.mem_map(target, 0x10000)
    machine.mem_map(stop, 0x10000)

    original_slot = module + symbol(
        menu, "launcher_menu_entry_original_primary" if primary
        else "launcher_menu_entry_original_secondary")
    # 真实入口回退槽指向透明岛复制序言的第三条；前两条已经由入口蹦床
    # 执行，因此回退不会重复压入 Dart 帧。夹具直接把该槽模拟为第三条目标。
    machine.mem_write(original_slot, struct.pack("<Q", target))
    machine.mem_write(target, struct.pack("<I", 0xD503201F))

    frame = stack + 0xF000
    outer, stage = heap + 0x1001, heap + 0x10000 + 0x2001
    package = heap + 0x3001
    # 主入口从 [x29+0x18] 取外层对象，次入口从 [x29+0x10] 取；
    # 两个闭包共享 tagged 对象布局，包名位于 stage+0xf。
    machine.mem_write(frame + (0x18 if primary else 0x10), struct.pack("<Q", outer))
    if primary:
        # 主闭包从调用者帧 [x29+0x10] 读取免责声明标志，而不是从 x0。
        machine.mem_write(frame + 0x10, struct.pack("<Q", 0x10 if disclaimer else 0))
    # x28 在本试验中固定为零，tagged 指针的低 32 位就是对象完整地址。
    # 不能在这里减掉堆基址，否则闭包会把字段解释成另一个未初始化对象。
    machine.mem_write(outer + 0x17, struct.pack("<I", stage & 0xFFFFFFFF))
    machine.mem_write(stage + 0xF, struct.pack("<I", package & 0xFFFFFFFF))
    machine.mem_write(package - 1, struct.pack("<Q", 94 << 12))
    machine.mem_write(package + 7, struct.pack("<I", 19))
    machine.mem_write(package + 11, b"com.tencent.mobileqq")
    # 当前解析按 tagged 低位与 x28 高位的既有闭包约定取字段；测试直接用
    # 地址低位和零高位，保证 ldur 与 add 得到完整 tagged 指针。
    machine.reg_write(reg.UC_ARM64_REG_X28, 0)

    expected = {index: 0x5150000000000000 | (index << 16) | (1 if primary else 2)
                for index in range(31)}
    # 入口蹦床补执行原始 Dart 序言；入口前 x15 还要再向上 16 字节，执行
    # `stp ..., [x15, #-16]!` 后才得到这里的 Dart 帧。回退继续从第三条
    # 原指令执行时，x15/x29 必须保持已建立的帧；它们与 C 栈 SP 是两套栈指针。
    expected[15], expected[22], expected[28] = frame, heap + 0x4001, 0
    expected[29], expected[30] = frame, source + 0x234
    expected[1] = package
    initial_nzcv = 0xF0000000
    initial_q = [(0xA5A5 << 112) | index | ((1 if primary else 2) << 32)
                 for index in range(32)]
    for index, value in expected.items():
        machine.reg_write(getattr(reg, f"UC_ARM64_REG_X{index}"), value)
    machine.reg_write(reg.UC_ARM64_REG_X15, frame + 16)
    for index, value in enumerate(initial_q):
        machine.reg_write(getattr(reg, f"UC_ARM64_REG_Q{index}"), value)
    machine.reg_write(reg.UC_ARM64_REG_SP, stack + 0x8000)
    machine.reg_write(reg.UC_ARM64_REG_NZCV, initial_nzcv)
    # ACK 成功时入口按宿主闭包约定直接返回调用点+4；夹具在这里放一个
    # 合法 NOP，避免把宿主样本该位置的任意指令误当成蹦床行为。
    machine.mem_write(expected[30], struct.pack("<I", 0xD503201F))
    bridge_calls = []
    fallback_entered = []
    caller_returned = []

    def runtime(m, address, size, opaque):
        # 每次调用都读取当前 SP；失败时把 Unicorn 状态带进异常，便于
        # 定位是桥栈帧还是回退路径发生越界，而不是只看笼统的写失败。
        current_sp = m.reg_read(reg.UC_ARM64_REG_SP)
        current_pc = address
        if address == module + symbol(menu, "launcher_share_entry_bridge"):
            bridge_calls.append((m.reg_read(reg.UC_ARM64_REG_X0),
                                 m.reg_read(reg.UC_ARM64_REG_X1)))
            m.reg_write(reg.UC_ARM64_REG_X0, bridge_result)
            m.reg_write(reg.UC_ARM64_REG_PC, m.reg_read(reg.UC_ARM64_REG_LR))
            return
        if address == expected[30]:
            caller_returned.append(True)
            require(m.reg_read(reg.UC_ARM64_REG_SP) == stack + 0x8000,
                    "返回调用者前未恢复栈")
            require(m.reg_read(reg.UC_ARM64_REG_X15) == frame + 16,
                    "ACK 路径未弹出 Dart 帧")
            require(m.reg_read(reg.UC_ARM64_REG_NZCV) == initial_nzcv,
                    "返回调用者前未恢复条件标志")
            actual_q = [m.reg_read(getattr(reg, f"UC_ARM64_REG_Q{index}"))
                        for index in range(32)]
            require(actual_q == initial_q, "返回调用者前未恢复向量寄存器")
            m.emu_stop()
            return
        if address != target:
            return
        fallback_entered.append(True)
        require(m.reg_read(reg.UC_ARM64_REG_SP) == stack + 0x8000,
                "回退前未恢复调用者栈")
        require(m.reg_read(reg.UC_ARM64_REG_NZCV) == initial_nzcv,
                "回退前未恢复条件标志")
        for index in range(19, 31):
            require(m.reg_read(getattr(reg, f"UC_ARM64_REG_X{index}")) == expected[index],
                    "回退前未恢复 x%d" % index)
        require(m.reg_read(reg.UC_ARM64_REG_X15) == expected[15],
                "回退前未恢复 Dart 栈指针 x15")
        require(m.reg_read(reg.UC_ARM64_REG_X1) == expected[1],
                "回退前未恢复调用者 x1")
        actual_q = [m.reg_read(getattr(reg, f"UC_ARM64_REG_Q{index}")) for index in range(32)]
        require(actual_q == initial_q, "回退前未恢复向量寄存器")
        m.emu_stop()

    machine.hook_add(uc.UC_HOOK_CODE, runtime)
    try:
        machine.emu_start(entry, stop, count=20000)
    except uc.UcError as error:
        pc = machine.reg_read(reg.UC_ARM64_REG_PC)
        sp = machine.reg_read(reg.UC_ARM64_REG_SP)
        raise AssertionError(
            "入口帧执行失败：%s pc=%#x sp=%#x hook_pc=%#x" %
            (error, pc, sp, locals().get("current_pc", 0))) from error
    # ACK 成功消费闭包；桥失败或免责声明分支才透明回退到原闭包。
    require(fallback_entered == ([] if bridge_result == 0 and not disclaimer else [True]),
            "入口 ACK/失败路径没有按合同消费或回退")
    # 免责声明分支不调用桥；其它路径必须恰好到达一次。
    require(bridge_calls == ([] if disclaimer else [(1 if primary else 2, package)]),
            "入口桥调用次数或参数不符")
    require(caller_returned == ([True] if bridge_result == 0 and not disclaimer else []),
            "入口 ACK 路径没有直接返回，或失败路径错误绕过原闭包")
    return {"入口": "主" if primary else "次", "ACK": bridge_result,
            "回退": bool(fallback_entered), "桥参数": "通过"}


def run_validity_frame(menu, host, share, original_result, feature_state):
    """执行可见性适配器，核对原函数先执行、只把 Dart false 改成 true。"""
    import unicorn as uc
    from unicorn import arm64_const as reg

    source, module = 0x10000000, 0x14000000
    entry_name = ("launcher_menu_share_valid_override" if share
                  else "launcher_menu_freeform_valid_override")
    slot_name = ("launcher_menu_original_share_valid" if share
                 else "launcher_menu_original_freeform_valid")
    entry = module + symbol(menu, entry_name)

    machine = uc.Uc(uc.UC_ARCH_ARM64, uc.UC_MODE_ARM)
    stack, target, stop = 0x20000000, 0x22000000, 0x23000000
    for base, image in [(source, host), (module, menu)]:
        size = max(s["address"] + s["memory"] for s in image.segments if s["type"] == 1)
        machine.mem_map(base, (size + 4095) & ~4095)
        for segment in image.segments:
            if segment["type"] == 1 and segment["size"]:
                machine.mem_write(base + segment["address"],
                                  image.read(segment["offset"], segment["size"]))
        for section in image.sections:
            if section["type"] == 4 and section["flags"] & 2:
                for at in range(section["offset"], section["offset"] + section["size"], 24):
                    address, info, addend = struct.unpack_from("<QQq", image.data, at)
                    require(info == 1027, "可见性帧试验不加载外部重定位")
                    machine.mem_write(base + address, struct.pack("<Q", base + addend))
    machine.mem_map(stack - 0x20000, 0x40000)
    machine.mem_map(target, 0x10000)
    machine.mem_map(stop, 0x10000)

    slot = module + symbol(menu, slot_name)
    machine.mem_write(slot, struct.pack("<Q", target))
    machine.mem_write(module + symbol(menu, "launcher_menu_feature_state"),
                      struct.pack("<I", feature_state))
    # 调用者 x15 必须是可写 Dart 栈；适配器在其上临时压入三个 16 字节槽。
    dart_stack = stack + 0x10000
    # 用宿主的真实 Dart false/true 语义模拟“原函数结果”：
    # 结果地址 = x22 + 0x30 (false) 或 x22 + 0x20 (true)。
    x22 = stack + 0x2000
    false_result, true_result = x22 + 0x30, x22 + 0x20
    original = false_result if original_result == "false" else true_result
    expected = {
        9: 0x9000000000000009, 10: 0x900000000000000A,
        11: 0x900000000000000B, 22: x22, 30: source + 0x444,
    }
    for index, value in expected.items():
        machine.reg_write(getattr(reg, f"UC_ARM64_REG_X{index}"), value)
    machine.reg_write(reg.UC_ARM64_REG_X15, dart_stack)
    machine.reg_write(reg.UC_ARM64_REG_LR, expected[30])
    machine.reg_write(reg.UC_ARM64_REG_NZCV, 0xF0000000)
    memory_before = machine.mem_read(dart_stack - 64, 128)
    calls = []

    def runtime(m, address, size, opaque):
        if address == target:
            calls.append((m.reg_read(reg.UC_ARM64_REG_X15),
                          m.reg_read(reg.UC_ARM64_REG_X30)))
            m.reg_write(reg.UC_ARM64_REG_X0, original)
            m.reg_write(reg.UC_ARM64_REG_PC, m.reg_read(reg.UC_ARM64_REG_LR))
            return
        if address == expected[30]:
            m.emu_stop()

    machine.hook_add(uc.UC_HOOK_CODE, runtime)
    machine.emu_start(entry, stop, count=10000)
    require(len(calls) == 1, "可见性适配器没有恰好执行一次原函数")
    # 适配器先压入三个 16 字节槽；原函数看到的 x15 是保存区顶部，
    # 返回后适配器再弹回调用者原来的 Dart 栈地址。
    require(calls[0][0] == dart_stack - 48,
            "调用原函数前 x15 应保留包装器保存区")
    require(calls[0][1] != expected[30], "调用原函数时没有使用可返回的 x30")
    require(machine.reg_read(reg.UC_ARM64_REG_PC) == expected[30],
            "可见性适配器没有返回调用者")
    require(machine.reg_read(reg.UC_ARM64_REG_X15) == dart_stack,
            "返回前没有恢复 Dart 栈 x15")
    require(machine.reg_read(reg.UC_ARM64_REG_X30) == expected[30],
            "返回前没有恢复 x30")
    require(machine.reg_read(reg.UC_ARM64_REG_NZCV) == 0xF0000000,
            "返回前没有恢复 NZCV")
    for index in (9, 10, 11):
        require(machine.reg_read(getattr(reg, f"UC_ARM64_REG_X{index}")) == expected[index],
                "返回前没有恢复 x%d" % index)
    # 弹出栈槽不会把旧内容清零；x15 已恢复是完整弹栈的判据。
    feature_bit = 0x2 if share else 0x4
    should_true = (feature_state & feature_bit) != 0 and original_result == "false"
    actual = machine.reg_read(reg.UC_ARM64_REG_X0)
    require(actual == (true_result if should_true else original),
            "可见性适配器返回值语义不符")
    return {"入口": "分享" if share else "小窗", "原结果": original_result,
            "功能位": feature_state, "结果": "Dart true" if should_true else "保持原值"}


def run_validity_exit_frame(menu, share, feature_state):
    """模拟 async 状态机的 false 出口，检查功能位与原共享续接路径。"""
    import unicorn as uc
    from unicorn import arm64_const as reg

    module, stack, stop = 0x14000000, 0x20000000, 0x23000000
    name = ("launcher_menu_share_valid_exit" if share
            else "launcher_menu_freeform_valid_exit")
    entry = module + symbol(menu, name)
    machine = uc.Uc(uc.UC_ARCH_ARM64, uc.UC_MODE_ARM)
    size = max(s["address"] + s["memory"] for s in menu.segments if s["type"] == 1)
    machine.mem_map(module, (size + 4095) & ~4095)
    for segment in menu.segments:
        if segment["type"] == 1 and segment["size"]:
            machine.mem_write(module + segment["address"],
                              menu.read(segment["offset"], segment["size"]))
    for section in menu.sections:
        if section["type"] == 4 and section["flags"] & 2:
            for at in range(section["offset"], section["offset"] + section["size"], 24):
                address, info, addend = struct.unpack_from("<QQq", menu.data, at)
                require(info == 1027, "异步出口帧试验不加载外部重定位")
                machine.mem_write(module + address, struct.pack("<Q", module + addend))
    machine.mem_map(stack, 0x40000)
    machine.mem_map(stop, 0x1000)
    dart_stack, continuation_slot = stack + 0x20000, stack + 0x3f000
    continuation = stop + 0x100
    machine.mem_write(continuation_slot, struct.pack("<Q", continuation))
    machine.mem_write(module + symbol(menu, "launcher_menu_feature_state"),
                      struct.pack("<I", feature_state))
    x22 = stack + 0x1000
    expected = {
        9: 0x9000000000000009, 10: 0x900000000000000A,
        11: 0x900000000000000B, 16: 0x9000000000000010,
        22: x22, 30: 0x9000000000000030,
    }
    for index, value in expected.items():
        machine.reg_write(getattr(reg, f"UC_ARM64_REG_X{index}"), value)
    machine.reg_write(reg.UC_ARM64_REG_X15, dart_stack)
    machine.reg_write(reg.UC_ARM64_REG_X17, continuation_slot)
    machine.reg_write(reg.UC_ARM64_REG_NZCV, 0xA0000000)
    result = []
    def runtime(m, address, size, opaque):
        if address == continuation:
            result.append((m.reg_read(reg.UC_ARM64_REG_X0),
                           m.reg_read(reg.UC_ARM64_REG_X15),
                           m.reg_read(reg.UC_ARM64_REG_X16),
                           m.reg_read(reg.UC_ARM64_REG_X9),
                           m.reg_read(reg.UC_ARM64_REG_X10),
                           m.reg_read(reg.UC_ARM64_REG_X11),
                           m.reg_read(reg.UC_ARM64_REG_X30),
                           m.reg_read(reg.UC_ARM64_REG_NZCV)))
            m.emu_stop()
    machine.hook_add(uc.UC_HOOK_CODE, runtime)
    machine.emu_start(entry, continuation + 4, count=10000)
    feature_bit = 0x2 if share else 0x4
    expected_result = x22 + (0x20 if feature_state & feature_bit else 0x30)
    require(result == [(expected_result, dart_stack, expected[16], expected[9],
                        expected[10], expected[11], expected[30], 0xA0000000)],
            "异步有效性出口未按功能位返回正确对象或破坏了 Dart 状态")
    return {"入口": "分享" if share else "小窗", "功能位": feature_state,
            "结果": "Dart true" if feature_state & feature_bit else "Dart false"}
