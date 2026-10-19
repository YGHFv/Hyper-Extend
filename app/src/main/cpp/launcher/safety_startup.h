/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#pragma once

#include "diagnostic_probe.h"
#include "snapshot_wire.h"
#include "planned_restart.h"

namespace launcher {

enum SafetyStartupBlocker : uint32_t {
    kStartupCallerRejected = 1U << 0,
    kStartupSnapshotRejected = 1U << 1,
    kStartupNoFeatures = 1U << 2,
    kStartupAlreadyAttempted = 1U << 3,
    kStartupInheritedAttempt = 1U << 4,
    kStartupDirectoryRejected = 1U << 5,
    kStartupLockRejected = 1U << 6,
    kStartupStateUnreadable = 1U << 7,
    kStartupFuseRejected = 1U << 8,
    kStartupTemporaryRejected = 1U << 9,
    kStartupWriteFailed = 1U << 10,
    kStartupFileSyncFailed = 1U << 11,
    kStartupPublishFailed = 1U << 12,
    kStartupDirectorySyncFailed = 1U << 13,
    kStartupReadbackFailed = 1U << 14,
    kStartupCloseFailed = 1U << 15,
};

struct SafetyStartupResult {
    uint32_t blockers;
    bool published;   // rename 已返回成功；后续失败也不能回滚或重试
    bool committed;   // 同步、读回与关闭均成功；不代表安装许可或实机验收
    SafetyFuseState next;

    bool safety_ready() const {
        return published && committed && blockers == 0 && !next.disabled;
    }
};

// 功能开启后的首次启动自动建立全新的锁与状态，同一次提交完成后即可继续挂载。
// 仅锁、状态及临时文件全部缺失时允许初始化；不重置已有熔断，不清理或覆盖异常残留。
// 普通启动拒绝任何非零快照恢复令牌；不能把配置回复当成解除熔断的授权。
// restart 只能来自已认证并消费的一次性重启授权；普通重启免计数但保留锁存，
// 显式恢复仍要求已有完整状态、新恢复令牌和无残留，并在同一事务中记录本次启动。
// context 是未来入口提供的内部证据，不是不可伪造令牌；本函数不独立认证宿主 APK。
// 首次合格调用即消耗本后端实例的机会，成功或失败都不重试；fork 继承记录也不能复用。
// 当前已链入原型安装入口；提交失败或证据不足时不可安装。
SafetyStartupResult commit_safety_startup_once(const DiagnosticContext &context,
    const Snapshot &snapshot, uint32_t last_revision, const StartupRestartGrant *restart = nullptr);

// root 所有的只读请求是旧 system_server 端点未更新时的本地通道。
// 只移动请求到已消费名，不修改/删除熔断状态；0 无请求，1 已认证消费，-1 请求异常。
int consume_root_restart_request(const DiagnosticContext &context, StartupRestartGrant &out);

// 只读检查首次启动所需的“锁、状态、临时文件全部缺失”。
// 返回 false 也包括目录/元信息读取失败；调用方必须按不安全处理，不能猜成首次启动。
bool launcher_safety_paths_absent(uint32_t uid);

}  // 命名空间 launcher
