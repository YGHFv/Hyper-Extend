/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#include "launcher/diagnostic_probe.h"
#include "launcher/channel.h"

using namespace launcher;

constexpr DiagnosticContext child{42, 10001, 10001, kIdentityLauncherChild,
    callback_record(42, 2, true)};
static_assert(diagnostic_can_schedule(child, 0), "已特化且收到本进程 ABI 的桌面可以调度诊断");
static_assert(diagnostic_context_ready({42, 110001, 110001, kIdentityLauncherChild,
    callback_record(42, 2, true)}), "普通应用多用户 UID 可以调度");

constexpr bool rejected_contexts() {
    constexpr uint32_t uids[] = {0U, 1000U, 9999U, 20000U, 90000U, 99000U, 100000U, 199999U};
    constexpr uint32_t identities[] = {kIdentityUsapPool, kIdentitySpawnerOther, kIdentityUnknown};
    for (uint32_t uid : uids) {
        if (diagnostic_context_ready({42, uid, uid, kIdentityLauncherChild, child.callback})) return false;
    }
    for (uint32_t identity : identities) {
        if (diagnostic_context_ready({42, child.uid, child.euid, identity, child.callback})) return false;
    }
    return !diagnostic_context_ready({1, child.uid, child.euid, child.identity, callback_record(1, 2, true)}) &&
        !diagnostic_context_ready({42, child.uid, 0, child.identity, child.callback}) &&
        !diagnostic_context_ready({43, child.uid, child.euid, child.identity, child.callback}) &&
        !diagnostic_context_ready({42, child.uid, child.euid, child.identity, callback_record(42, 2, false)});
}
static_assert(!diagnostic_context_ready({1, child.uid, child.euid, child.identity,
    callback_record(1, 2, true)}), "预派生进程不能调度");
static_assert(!diagnostic_context_ready({42, child.uid, 0, child.identity, child.callback}),
    "权限不符不能调度");
static_assert(!diagnostic_context_ready({43, child.uid, child.euid, child.identity, child.callback}),
    "父进程记录不能冒充当前进程");
static_assert(!diagnostic_context_ready({42, child.uid, child.euid, child.identity,
    callback_record(42, 2, false)}), "缺少入口记录不能调度");

constexpr bool once_only() {
    constexpr uint32_t owners[] = {41U, 42U, 43U};
    constexpr DiagnosticPhase phases[] = {kDiagnosticQueued, kDiagnosticRunning, kDiagnosticFinished,
        kDiagnosticThreadFailed, kDiagnosticIdentityRejected};
    for (uint32_t owner : owners) {
        for (DiagnosticPhase phase : phases) {
            if (diagnostic_can_schedule(child, diagnostic_record(owner, phase))) return false;
        }
    }
    return true;
}
static_assert(once_only(), "排队、运行、完成或失败后都不能重复或跨 fork 重试");
static_assert(diagnostic_transition(diagnostic_record(42, kDiagnosticQueued), 43,
    kDiagnosticQueued, kDiagnosticRunning) == diagnostic_record(42, kDiagnosticQueued),
    "子进程不能接管父进程工作");
static_assert(diagnostic_transition(diagnostic_record(42, kDiagnosticQueued), 42,
    kDiagnosticQueued, kDiagnosticRunning) == diagnostic_record(42, kDiagnosticRunning),
    "仅所属进程可开始排队的工作");
static_assert(snapshot_record_revision(snapshot_record(INT32_MAX, 15)) == INT32_MAX &&
    snapshot_record_features(snapshot_record(INT32_MAX, 15)) == 15, "快照字段必须整包发布");
static_assert(kChannelNotQueried != 0 && kChannelBusy != 0 && kChannelWrongProcess != 0,
    "诊断状态不能与成功的零混淆");

int main() {
    return 0;
}
