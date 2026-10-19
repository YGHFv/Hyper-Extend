/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
// 仅提供真实汇编所需槽位；运行时调用由模拟器验证，不连接设备。
#include <stdint.h>

extern "C" {
// 模拟真实 C++ 运行时按 AAPCS64 破坏 x19..x29 的全部情况。
__attribute__((visibility("default"), noinline))
unsigned int launcher_share_bridge(unsigned int point, unsigned long package) {
    (void)point;
    (void)package;
    asm volatile(
        "mov x19, #0x119\n"
        "mov x20, #0x120\n"
        "mov x21, #0x121\n"
        "mov x22, #0x122\n"
        "mov x23, #0x123\n"
        "mov x24, #0x124\n"
        "mov x25, #0x125\n"
        "mov x26, #0x126\n"
        "mov x27, #0x127\n"
        "mov x28, #0x128\n"
        "mov x29, #0x129\n"
        :
        :
        : "x19", "x20", "x21", "x22", "x23", "x24", "x25", "x26", "x27", "x28", "x29");
    return 0;
}
// 入口桥的链接桩。离线帧测试在符号入口截获调用，以精确控制 ACK 与失败
// 分支；该实现只保证真实汇编对象可以独立链接。
__attribute__((visibility("default"), noinline))
unsigned int launcher_share_entry_bridge(unsigned int point, unsigned long package) {
    (void)point;
    (void)package;
    return 0;
}
__attribute__((visibility("hidden"))) void *launcher_menu_original_primary = nullptr;
__attribute__((visibility("hidden"))) void *launcher_menu_original_share_secondary = nullptr;
__attribute__((visibility("hidden"))) void *launcher_menu_original_freeform = nullptr;
__attribute__((visibility("hidden"))) void *launcher_menu_original_share_valid = nullptr;
__attribute__((visibility("hidden"))) void *launcher_menu_original_freeform_valid = nullptr;
__attribute__((visibility("hidden"))) unsigned int launcher_menu_feature_state = 0;
__attribute__((visibility("hidden"))) void *launcher_menu_entry_original_primary = nullptr;
__attribute__((visibility("hidden"))) void *launcher_menu_entry_original_secondary = nullptr;
}
