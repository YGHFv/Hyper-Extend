/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#pragma once

#include "safety_startup.h"

namespace launcher {

enum class SafetyMaintenanceOperation : uint32_t {
    kArmAbsent = 1,
    kRearmKnown = 2,
    kResumePending = 3,
    kResumeArming = 4,
};

enum SafetyMaintenanceBlocker : uint32_t {
    kMaintenanceAuthorizationRejected = 1U << 16,
    kMaintenanceOperationRejected = 1U << 17,
    kMaintenanceResidualRejected = 1U << 18,
};

struct SafetyMaintenanceObservation {
    bool have_state;
    bool have_pending;
    SafetyFuseState state;
    SafetyFuseState pending;
};

struct SafetyMaintenanceGrant {
    uint32_t pid;
    uint32_t uid;
    SafetyMaintenanceOperation operation;
    int64_t issued_s;
    int64_t expires_s;
    int64_t reset_token;
};

// 这是可信入口的依赖接口，不是认证实现。回调必须验证专用的当次人工授权、
// 宿主/用户/操作/观察到的状态、跨重启代际与令牌防回放；普通配置不能实现此接口。
// 当前没有生产授权方，默认传 nullptr 必须拒绝。结构体与函数指针均不是防伪凭证。
// 回调只能消费预先认证的授权，不得持锁等待用户交互；文件 I/O 无硬超时，需在工作线程调用。
using SafetyMaintenanceAuthorizer = bool (*)(void *, const DiagnosticContext &,
    SafetyMaintenanceOperation, const SafetyMaintenanceObservation &, SafetyMaintenanceGrant &);

struct SafetyMaintenanceResult {
    uint32_t blockers;
    bool published;
    bool committed;
    SafetyFuseState next;

    bool maintenance_complete() const {
        return published && committed && blockers == 0;
    }
};

// 维护与普通启动共用本后端实例的一次机会；维护后必须等待新的独立进程记录启动。
// 不提供 safety_ready，不签发安装许可；完整授权入口和安装接线尚未实现。
SafetyMaintenanceResult maintain_safety_once(const DiagnosticContext &context,
    SafetyMaintenanceOperation operation, SafetyMaintenanceAuthorizer authorize, void *opaque);

}  // 命名空间 launcher
