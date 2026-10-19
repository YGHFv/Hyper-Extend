/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#pragma once

#include <stddef.h>
#include <stdint.h>

namespace launcher {

using HookFunction = int (*)(void *, void *, void **);
using UnhookFunction = int (*)(void *);
using ModuleLoadedCallback = void (*)(const char *, void *);

// 框架边界的布局参考 HyperCeiler 5e4686069dd7；不是内部的双指针适配器。
// 这里只声明布局，不据此认定任何原生 ABI 版本已经受支持。
struct NativeApiEntries {
    uint32_t version;
    HookFunction hook_func;
    UnhookFunction unhook_func;
};

static_assert(sizeof(void *) == 8, "需要 ARM64 指针宽度");
static_assert(offsetof(NativeApiEntries, version) == 0, "版本字段偏移错误");
static_assert(offsetof(NativeApiEntries, hook_func) == 8, "挂载入口偏移错误");
static_assert(offsetof(NativeApiEntries, unhook_func) == 16, "卸载入口偏移错误");
static_assert(sizeof(NativeApiEntries) == 24, "框架原始结构大小错误");

// `version` 只保留作诊断字段，绝不参与宿主适配或安装判定。
// 卸载入口由框架按代际提供，原生挂载链只需要 hook_func；已发布的代码岛
// 不会在宿主运行期间主动卸载，因此 unhook_func 缺失不能把整个挂载链拒绝。
// 真正的兼容性由 hook_func 调用约定与现场 Hook 点参数合同决定。
constexpr bool native_entries_ready(const NativeApiEntries *entries) {
    return entries != nullptr && entries->hook_func != nullptr;
}

}  // 命名空间 launcher
