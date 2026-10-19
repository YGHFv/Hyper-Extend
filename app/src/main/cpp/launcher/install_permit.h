/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#pragma once

#include <stdint.h>

#include "host_identity.h"
#include "native_api.h"
#include "safety_gate.h"
#include "snapshot_wire.h"

// 安装许可的唯一判定点。任何一次 hook_func 调用之前都必须先通过这里，
// 不要把身份、框架版本或入口数字拼成许可，也不要让某个调用方自行拼装条件。
//
// 三类证据缺一不可：
//   1. 宿主已特化：cmdline==com.miui.home（不是 usap 池，也不是其它 spawner 应用）；
//   2. 原生入口可用：native_init 提供了两个非空函数指针；版本字段不进入证据；
//   3. 安全许可：快照与本地熔断均通过实机验收（门槛 3，尚未验收）；
//   4. 后端计划：现场参数、代码页权限和原子发布由安装事务自身核对。
namespace launcher {

enum PermitBlocker : uint32_t {
    kPermitHostUnverified = 1U << 0,
    kPermitEntriesUnverified = 1U << 1,
    kPermitSafetyUnverified = 1U << 2,
    kPermitBackendUnverified = 1U << 3,
};

// 普通构建不允许安装；实验构建由显式 Gradle 开关启用本地设备试验。
// 这里不再保留已失效的“待验收阶段锁”常量，兼容性只看现场入口与 Hook 点参数。
#if defined(LAUNCHER_NATIVE_EXPERIMENTAL_INSTALL)
constexpr bool kExperimentalInstallEnabled = true;
#else
constexpr bool kExperimentalInstallEnabled = false;
#endif

struct PermitEvidence {
    uint32_t host_identity;   // host_identity.h 的分类结果
    bool entries_present;     // native_init 是否真的拿到了 Non-null entries
    bool safety_verified;     // 门槛 3：安全门禁结论（见 safety_evidence）
    bool backend_verified;    // 门槛 1、5：模块中继发布与并发安装核验通过（未实现）
};

// 安全维度只能由安全门禁的结论产生，调用方不得自填一个 true。
// 未读回状态、时钟异常、已熔断都会让这里返回 false，从而留下 kPermitSafetyUnverified。
constexpr bool safety_evidence(const SafetyFuseDecision &decision) {
    return safety_permits_install(decision);
}

// 桌面进程里没有 Java，也没有 Context，因此实际到达的安全证据是
// system_server 回复的配置快照与本地熔断状态共同决定的结论。这里把二者收敛成
// 同一个布尔，调用方仍然不能自填。快照畸形、过期、序号回退、未武装或本地熔断
// 都会让这里返回 false 并留下 kPermitSafetyUnverified。
constexpr bool safety_evidence(const Snapshot &snapshot, uint32_t last_revision,
    int64_t boot_now_s, int64_t wall_now_ms, const SafetyFuseState &local, bool have_local,
    bool count_restart) {
    return snapshot_safety_permits(snapshot, last_revision, boot_now_s, wall_now_ms, local,
        have_local, count_restart);
}

constexpr uint32_t permit_blockers(const PermitEvidence &evidence) {
    return
        (host_is_specialized_launcher(evidence.host_identity) ? 0U : kPermitHostUnverified) |
        (evidence.entries_present ? 0U : kPermitEntriesUnverified) |
        (evidence.safety_verified ? 0U : kPermitSafetyUnverified) |
        (evidence.backend_verified ? 0U : kPermitBackendUnverified);
}

// 只有当阻断位恰好为 0 时才允许安装。任何未知/缺失证据都会留下阻断位。
constexpr bool install_permitted(const PermitEvidence &evidence) {
    return permit_blockers(evidence) == 0;
}

}  // 命名空间 launcher
