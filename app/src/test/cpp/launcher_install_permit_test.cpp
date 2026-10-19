/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#include "launcher/install_permit.h"

using namespace launcher;

// 安全证据只能来自安全门禁：熔断/时钟异常/未武装都必须把安全维度判为未核验。
constexpr SafetyFuseDecision armed = safety_fuse_next(
    SafetyFuseState{1700000000000LL, 0, false, 0, 1700000000000LL}, true, 1700000001000LL, 0, true);
static_assert(safety_evidence(armed), "正常武装结论可作为安全证据");
constexpr SafetyFuseDecision fired = safety_fuse_next(
    SafetyFuseState{1700000000000LL, 3, true, 0, 1699999999000LL}, true, 1700000001000LL, 0, true);
static_assert(!safety_evidence(fired), "熔断结论不能作为安全证据");

// 现场参数与安全证据齐备即可进入安装事务。
constexpr PermitEvidence realLauncher{kIdentityLauncherChild, true,
    safety_evidence(armed), true};
static_assert(install_permitted(realLauncher), "现场参数和安全证据齐备应允许安装事务");

// usap 池、其它 spawner 应用、未知进程都不是已特化桌面。
static_assert(permit_blockers({kIdentityUsapPool, true, true, true}) ==
    kPermitHostUnverified,
    "usap 池不能作为桌面宿主");
static_assert(permit_blockers({kIdentitySpawnerOther, true, true, true}) ==
    kPermitHostUnverified,
    "其它同 exe 的 Rust 应用不能作为桌面宿主");
static_assert(permit_blockers({kIdentityUnknown, true, true, true}) ==
    kPermitHostUnverified,
    "未知进程不能作为桌面宿主");

// 没有入口结构时必须阻断。
static_assert(permit_blockers({kIdentityLauncherChild, true, true, true}) == 0,
    "现场入口和安全证据齐备不得阻断");
static_assert(permit_blockers({kIdentityLauncherChild, false, true, true}) ==
    kPermitEntriesUnverified,
    "空入口参数必须阻断");
static_assert(permit_blockers({kIdentityLauncherChild, false, true, true}) ==
    kPermitEntriesUnverified,
    "未拿到 entries 时入口不可信");

static_assert(install_permitted({kIdentityLauncherChild, true, true, true}),
    "现场入口和安全证据齐备即可进入安装事务");

constexpr bool all_claims_are_checked() {
    constexpr uint32_t hosts[] = {kIdentityLauncherChild, kIdentityUsapPool,
        kIdentitySpawnerOther, kIdentityUnknown, UINT32_MAX};
    for (auto host : hosts) {
        for (uint32_t flags = 0; flags < 8; ++flags) {
            const PermitEvidence evidence{host, (flags & 1) != 0,
                (flags & 2) != 0, (flags & 4) != 0};
            if (install_permitted(evidence) &&
                (host != kIdentityLauncherChild || !(flags & 1) || !(flags & 2) ||
                 !(flags & 4))) {
                return false;
            }
        }
    }
    return true;
}
static_assert(all_claims_are_checked(), "所有声明组合都必须遵守宿主、入口和安全证据");

// 安全证据若来自门禁结论，未武装状态仍然阻断安装。
static_assert(permit_blockers({kIdentityLauncherChild, true,
    safety_evidence({false, SafetyFuseState{}, kSafetyNotArmed}), true}) == kPermitSafetyUnverified,
    "安全门禁未武装时安装必须被阻断");

// 桌面真实到达的安全证据是「system_server 快照 + 本地熔断」的组合结论。
// 快照过期、未武装或本地已熔断都必须让安全维度保持未核验。
constexpr Snapshot liveSnapshot{kSnapshotAck, kSnapshotProtocol, 7, 1000, 1030, 1, 5, 1, 0, 0};
constexpr SafetyFuseState liveLocal{1700000000000LL, 0, false, 0, 1700000000000LL};
static_assert(safety_evidence(liveSnapshot, 0, 1000, 1700000001000LL, liveLocal, true, true),
    "可信快照加可用本地熔断可作为安全证据");
constexpr Snapshot expiredSnapshot{kSnapshotAck, kSnapshotProtocol, 7, 1000, 1030, 1, 5, 1, 0, 0};
static_assert(!safety_evidence(expiredSnapshot, 0, 1030, 1700000001000LL, liveLocal, true, true),
    "过期快照不能作为安全证据");
constexpr Snapshot unarmedSnapshot{kSnapshotAck, kSnapshotProtocol, 7, 1000, 1030, 1, 5, 0, 0, 0};
static_assert(!safety_evidence(unarmedSnapshot, 0, 1000, 1700000001000LL, liveLocal, true, true),
    "未武装快照不能作为安全证据");
constexpr SafetyFuseState firedLocal{1700000000000LL, 3, true, 0, 1699999999000LL};
static_assert(!safety_evidence(liveSnapshot, 0, 1000, 1700000001000LL, firedLocal, true, true),
    "本地熔断不能作为安全证据");
static_assert(permit_blockers({kIdentityLauncherChild, true,
    safety_evidence(expiredSnapshot, 0, 1030, 1700000001000LL, liveLocal, true, true), true})
    == kPermitSafetyUnverified, "过期快照必须留下安全阻断位");

int main() {
    return 0;
}
