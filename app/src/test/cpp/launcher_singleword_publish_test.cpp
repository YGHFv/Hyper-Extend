/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
/*
 * 单字发布协议的**构建期合同**。本文件被 `app/src/main/cpp/CMakeLists.txt` 的
 * `launcher_probe_contract` 目标引用，因此 `launcher/singleword_publish.h` 里
 * 那批 static_assert 每次**原型构建**（`-PlauncherNativePrototype=true`）都会重跑。
 * 普通构建不含整个桌面原生原型，自然也不含本文件。
 *
 * 这里额外钉住「生产实现与试验源是同一份协议」：
 *   app/src/main/cpp/launcher/singleword_publish.h   协议与判定（生产）
 *   app/src/main/cpp/launcher/singleword_publish.cpp 三个导出步骤（生产）
 * 门槛 5 残余项 (c) 的机器码审计针对的正是生产 `.cpp`，不再针对测试目录里的等价写法。
 * 一旦有人把生产实现改回多字存储或颠倒同步顺序，构建期断言与 Python 侧反汇编会分别失败。
 */

#include "launcher/singleword_publish.h"

using launcher::singleword::Fault;
using launcher::singleword::WordKind;

// 崩坏值（多字存储写进去的一半、未初始化栈、别的模块留下的指针）一律不是例外成员，
// 因此 fail-closed：认不出来就当拒绝，不当通过。
static_assert(launcher::singleword::classify(0x00000000U) == WordKind::kOther, "零字不得视为例外成员");
static_assert(launcher::singleword::classify(0xFFFFFFFFU) == WordKind::kOther, "全 1 不得视为例外成员");
static_assert(!launcher::singleword::in_exception_set(0x29000801U), "stp 不得视为例外成员");

// 空蹦床没有可同步的代码，必须在任何写内存动作之前就被拒绝。
static_assert(launcher::singleword::plan_fault(
    launcher::singleword::CommitPlan{0x1000, 0x2000, 0x3000, 0x14000001, 0x14000002, 0}) ==
    Fault::kEmptyTrampoline, "零长度蹦床必须先于一切动作被拒绝");

// 门禁是纯函数，不取内存：同一份输入永远得同一个结论。
constexpr launcher::singleword::CommitPlan verified{
    0x1000, 0x2000, 0x3000,
    launcher::singleword::encode_bl(0x1000, 0x2000),
    launcher::singleword::encode_bl(0x1000, 0x3000), 4096};
static_assert(launcher::singleword::plan_fault(verified) == Fault::kNone, "合法计划必须通过");
static_assert(launcher::singleword::plan_fault(verified) == launcher::singleword::plan_fault(verified),
    "门禁必须无副作用");

// 64 位存储（str x）与多字存储（stp）都不是例外成员，不能被当成「单字提交」。
static_assert(!launcher::singleword::in_exception_set(0xF9000008U), "str x 不得视为例外成员");

int main() {
    return 0;
}
