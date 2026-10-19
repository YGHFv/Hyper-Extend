/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#pragma once

#include <stdint.h>

// 桌面是 Dart AOT（HyperOS Rust 运行时）编译产物，寄存器约定与 AAPCS64 不同。
// 依据 dart-lang/sdk runtime/vm/constants_arm64.h（ARM64）：
//   R15 = SP in Dart code（Dart 自己的栈指针，不是线程的 C 栈）
//   R16/R17 = TMP/TMP2（编译器保留暂存，不会承载 Dart 活值）
//   R29 = FP、R30 = LR、R28 = HEAP_BITS、R26 = THR、R27 = PP
//   kAbiPreservedCpuRegs = R19..R28（所以 x19-x28 本来就由 ABI 保证）
//   kDartAvailableCpuRegs 含 x9-x11，可承载 Dart 活值，因此透明蹦床必须自行保存。
// 参考实现 home_layout_dart_arm64.S 的 dart_save/dart_restore 是同一结论的设备实测版：
// 蹦床保存 x9/x10/x11 与 nzcv，并在调用原函数前后各占 16 字节 Dart 栈。
namespace launcher {

// 数量 getter 返回的是 x0 中的未装箱整数（不是 Smi、不是对象指针）。
// 观察到的原生取值只有 3 与 4，所以上限必须按裸整数写入，不能乘 2 编码成 Smi。
constexpr uint32_t kShortcutCountRawEncoding = 1;

// 上限范围与配置预检一致；0 在不透明蹦床里表示“不干预”。
constexpr uint32_t kShortcutCountMin = 5;
constexpr uint32_t kShortcutCountMax = 20;

// 蹦床读到的单个原子字：0 = 关闭，其它值 = 直接作为 x0 的裸整数上限。
// 用单字承载“开关 + 数值”，避免读到“已启用但数值尚未发布”的中间态。
constexpr uint32_t shortcut_count_state(bool enabled, uint32_t limit) {
    return enabled && limit >= kShortcutCountMin && limit <= kShortcutCountMax ? limit : 0;
}

// 蹦床只改写 x0 后尾跳原中继，因此必须排除 0 与越界值被写进 x0。
constexpr bool shortcut_count_overrides(uint32_t state) {
    return state >= kShortcutCountMin && state <= kShortcutCountMax;
}

// shortcut_count_arm64.S 的逐条编码。它们不参与运行，只把蹦床指令钉在编译期：
// 改动汇编或常量时必须同时更新这里，避免“改了源码但忘了同步契约”。
namespace encoding {

constexpr uint32_t kStpX9X10PreDec = 0xa9bf29e9U;   // stp x9, x10, [x15, #-16]!
constexpr uint32_t kMrsNzcvX9 = 0xd53b4209U;        // mrs x9, nzcv
constexpr uint32_t kStpX9X11PreDec = 0xa9bf2de9U;   // stp x9, x11, [x15, #-16]!
constexpr uint32_t kLdarW10X9 = 0x88dffd2aU;        // ldar w10, [x9]
constexpr uint32_t kSubW11W10Min = 0x5100154bU;    // sub w11, w10, #5
constexpr uint32_t kCmpW11Range = 0x71003d7fU;     // cmp w11, #15
constexpr uint32_t kBhiKeepOriginal = 0x54000048U; // b.hi，越界跳过覆盖
constexpr uint32_t kMovW0W10 = 0x2a0a03e0U;         // mov w0, w10（高 32 位清零）
constexpr uint32_t kLdpX9X11PostInc = 0xa8c12de9U;  // ldp x9, x11, [x15], #16
constexpr uint32_t kMsrNzcvX9 = 0xd51b4209U;        // msr nzcv, x9
constexpr uint32_t kLdpX9X10PostInc = 0xa8c129e9U;  // ldp x9, x10, [x15], #16
constexpr uint32_t kLdrX16X16 = 0xf9400210U;        // ldr x16, [x16]
constexpr uint32_t kBrX16 = 0xd61f0200U;            // br x16（尾跳，不动 x30）

// 无符号的 state-5 <= 15 同时排除零、小值及高位异常值；修改的 NZCV 随后恢复。
static_assert(kSubW11W10Min == 0x5100154bU && kCmpW11Range == 0x71003d7fU &&
    kBhiKeepOriginal == 0x54000048U, "汇编必须独立校验 5..20，不能只检查非零");
// 上限只允许落在 5..20，写 W0 会将 X0 的高 32 位清零，正好形成裸整数。
static_assert(kMovW0W10 == 0x2a0a03e0U && (kMovW0W10 >> 31) == 0,
    "写 x0 的指令必须是 32 位形式，不能写成 64 位 mov x0, x10");
// 保存/恢复必须成对，且都在 Dart 栈 x15 上；x30 不在其中，因为这是叶蹦床。
static_assert(kStpX9X10PreDec == 0xa9bf29e9U && kLdpX9X10PostInc == 0xa8c129e9U,
    "Dart 活寄存器 x9/x10 必须在 Dart 栈上成对保存与恢复");
static_assert(kBrX16 != 0xd63f0200U, "尾跳必须用 br，不能把返回地址写进 x30");

}  // 命名空间 encoding

}  // 命名空间 launcher
