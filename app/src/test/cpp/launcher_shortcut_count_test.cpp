/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#include "launcher/dart_abi.h"

using namespace launcher;

// 数量上限按裸整数写入 x0：原生只返回 3/4，试点上限 5..20 不能被当成 Smi 乘二。
static_assert(kShortcutCountRawEncoding == 1, "数量返回是裸整数，不能改成 Smi 编码");
static_assert(shortcut_count_state(false, 10) == 0, "关闭时蹦床必须完全不干预");
static_assert(shortcut_count_state(true, 0) == 0, "0 是保留的关闭值，不能当成上限");
static_assert(shortcut_count_state(true, 4) == 0, "低于原生的上限没有意义，必须被拒绝");
static_assert(shortcut_count_state(true, 5) == 5, "下限 5 生效");
static_assert(shortcut_count_state(true, 20) == 20, "上限 20 生效");
static_assert(shortcut_count_state(true, 21) == 0, "越界上限必须回退为不干预");

// 蹦床只在 5..20 时改写 x0；0 与越界值都必须保持原 getter 结果。
static_assert(!shortcut_count_overrides(0), "关闭值不覆盖");
static_assert(!shortcut_count_overrides(4), "越界小值不覆盖");
static_assert(shortcut_count_overrides(5), "下限覆盖");
static_assert(shortcut_count_overrides(20), "上限覆盖");
static_assert(!shortcut_count_overrides(21), "越界大值不覆盖");
static_assert(!shortcut_count_overrides(UINT32_MAX), "异常高位状态不能变成上限");
static_assert(shortcut_count_state(true, UINT32_MAX) == 0, "异常高位配置必须关闭");

int main() {
    return 0;
}
