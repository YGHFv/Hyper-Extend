/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#pragma once

#include "host_identity.h"
#include "probe_state.h"

namespace launcher {

struct DiagnosticContext {
    uint32_t pid;
    uint32_t uid;
    uint32_t euid;
    uint32_t identity;
    uint64_t callback;
};

// 只准普通应用 UID；排除 root、system、隔离进程和 SDK 沙箱。支持多用户 UID。
// cmdline 只是本地诊断调度的保守筛选，不代替服务端 Binder 调用方验证。
constexpr bool diagnostic_context_ready(const DiagnosticContext &context) {
    const uint32_t app_id = context.uid % 100000U;
    return context.pid > 1 && context.uid == context.euid &&
        app_id >= 10000U && app_id < 20000U &&
        host_is_specialized_launcher(context.identity) &&
        callback_pid(context.callback) == context.pid &&
        (context.callback & kEntriesPresent) != 0;
}

enum DiagnosticPhase : uint32_t {
    kDiagnosticQueued = 1,
    kDiagnosticRunning = 2,
    kDiagnosticFinished = 3,
    kDiagnosticThreadFailed = 4,
    kDiagnosticIdentityRejected = 5,
};

constexpr uint64_t diagnostic_record(uint32_t pid, DiagnosticPhase phase) {
    return (static_cast<uint64_t>(pid) << 32) | phase;
}

// 包括失败在内均不重置；父进程留下的非零记录不能在子进程自动重新调度。
constexpr bool diagnostic_can_schedule(const DiagnosticContext &context, uint64_t record) {
    return record == 0 && diagnostic_context_ready(context);
}

constexpr uint64_t diagnostic_transition(uint64_t record, uint32_t pid,
    DiagnosticPhase from, DiagnosticPhase to) {
    return pid > 1 && record == diagnostic_record(pid, from)
        ? diagnostic_record(pid, to) : record;
}

// 两个字段一起发布；禁止先推进修订号，再由较旧事务覆盖功能位。
constexpr uint64_t snapshot_record(uint32_t revision, uint32_t features) {
    return (static_cast<uint64_t>(revision) << 32) | features;
}

constexpr uint32_t snapshot_record_revision(uint64_t record) {
    return static_cast<uint32_t>(record >> 32);
}

constexpr uint32_t snapshot_record_features(uint64_t record) {
    return static_cast<uint32_t>(record);
}

}  // 命名空间 launcher
