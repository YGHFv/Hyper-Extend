/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#pragma once

#include <stdint.h>

#include "native_api.h"

namespace launcher {

enum ProbeStatus : uint32_t {
    kPrototypeOnly = 1U << 0,
    kJniLoaded = 1U << 1,
    kNativeCallbackSeen = 1U << 2,
    kEntriesRejected = 1U << 3, // native_init 没拿到可用的入口结构
    kInheritedState = 1U << 4,
    // 以下位由 native_entry 直接叠加，probe_status 本身不返回它们，
    // 以免改变既有静态断言的语义。
    kHookPointerStored = 1U << 5,
    kUnhookPointerStored = 1U << 6,
    kHostSpecializedLauncher = 1U << 7,
    kHostSpawnerOther = 1U << 8,
    kHostUsapPool = 1U << 9,
    kHostUnknown = 1U << 10,
    // 由 probe_status 返回：本进程拿到了可用的原生入口结构。
    // 版本字段不参与判定，仍不等于安装许可。
    kEntriesVerified = 1U << 11,
};

constexpr uint64_t kEntriesPresent = UINT64_C(1) << 63;

// 单个原子记录同时发布进程代际和版本，避免混合读取两个回调的诊断字段。
constexpr uint64_t callback_record(uint32_t pid, uint32_t version, bool entries_present) {
    return (static_cast<uint64_t>(pid & 0x7fffffffU) << 32) | version |
        (entries_present ? kEntriesPresent : 0);
}

constexpr uint32_t callback_pid(uint64_t record) {
    return static_cast<uint32_t>((record >> 32) & 0x7fffffffU);
}

constexpr uint32_t probe_status(uint32_t pid, uint32_t jni_pid, uint64_t record) {
    const uint32_t owner = callback_pid(record);
    const bool mine = pid != 0 && owner == pid;
    // 只核对入口是否到达；record 的低 32 位是诊断用版本，不是门禁。
    const uint32_t entries = mine && (record & kEntriesPresent) ? kEntriesVerified :
        (mine ? kEntriesRejected : 0U);
    return kPrototypeOnly |
        (pid != 0 && jni_pid == pid ? kJniLoaded : 0U) |
        (mine ? kNativeCallbackSeen | entries : 0U) |
        ((jni_pid != 0 && jni_pid != pid) || (owner != 0 && owner != pid) ? kInheritedState : 0U);
}

constexpr int64_t observed_version(uint32_t pid, uint64_t record) {
    return pid != 0 && callback_pid(record) == pid && (record & kEntriesPresent)
        ? static_cast<int64_t>(static_cast<uint32_t>(record)) : -1;
}

constexpr bool configuration_probe_ready(uint32_t pid, uint32_t jni_pid, uint64_t record) {
    constexpr auto needed = kJniLoaded | kNativeCallbackSeen;
    return (probe_status(pid, jni_pid, record) & needed) == needed &&
        (record & kEntriesPresent) != 0;
}

}  // 命名空间 launcher
