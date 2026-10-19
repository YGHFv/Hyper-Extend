/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#include "launcher/native_api.h"
#include "launcher/probe_state.h"

using namespace launcher;

constexpr int test_hook(void *, void *, void **) { return 0; }
static constexpr NativeApiEntries optional_unhook_entries{7, test_hook, nullptr};
static_assert(native_entries_ready(&optional_unhook_entries),
    "可选卸载入口缺失不能阻断原生挂载");

static_assert(probe_status(100, 0, 0) == kPrototypeOnly, "默认不具备原生回调或配置");
static_assert(probe_status(100, 100, 0) == (kPrototypeOnly | kJniLoaded), "加载库不等于原生入口到达");
// 版本字段只作诊断记录；不同数字不能阻断现场参数解析。
static_assert(probe_status(100, 100, callback_record(100, 102, true)) ==
    (kPrototypeOnly | kJniLoaded | kNativeCallbackSeen | kEntriesVerified), "版本数字不得阻断入口");
static_assert(probe_status(100, 100, callback_record(100, 0, false)) ==
    (kPrototypeOnly | kJniLoaded | kNativeCallbackSeen | kEntriesRejected), "空入口参数必须被拒绝");
static_assert(probe_status(100, 100, callback_record(100, 1, true)) ==
    (kPrototypeOnly | kJniLoaded | kNativeCallbackSeen | kEntriesVerified),
    "入口存在只解除入口阻断，不能额外放行宿主、安全或后端");
static_assert(probe_status(101, 100, callback_record(100, 1, true)) ==
    (kPrototypeOnly | kInheritedState), "子进程不能继承父进程有效状态");
static_assert(probe_status(101, 101, callback_record(101, 1, true)) ==
    (kPrototypeOnly | kJniLoaded | kNativeCallbackSeen | kEntriesVerified), "重新到达入口按现场参数继续");
static_assert(observed_version(100, callback_record(100, UINT32_MAX, true)) == UINT32_MAX,
    "最高版本值不能被截断或误作缺失");
static_assert(observed_version(100, callback_record(100, 0, true)) == 0, "版本零与缺失分开");
static_assert(observed_version(100, callback_record(100, 0, false)) == -1, "空参数没有版本");
static_assert(observed_version(101, callback_record(100, 1, true)) == -1, "父进程版本不能冒充本进程证据");
static_assert(observed_version(100, 0) == -1, "入口未到达时版本缺失");
static_assert(probe_status(0, 0, 0) == kPrototypeOnly, "无效进程不算到达");
