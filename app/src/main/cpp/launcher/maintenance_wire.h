/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#pragma once

#include <stdint.h>

#include "safety_maintenance.h"

namespace launcher {

// 维护请求与普通配置使用同一条 window Binder 通道，但使用独立事务码和回复协议。
// 回复只携带经过 system_server 调用方校验的短时授权材料；它不是普通配置字段，
// 也不能被桌面侧改写成安装许可。
constexpr uint32_t kMaintenanceTransaction = 0x00484542U;
constexpr int32_t kMaintenanceAck = 0x48454d31;  // "HEM1"
constexpr int32_t kMaintenanceProtocol = 1;
constexpr int kMaintenanceReplyWords = 8;

struct MaintenanceGrantWire {
    int32_t ack;
    int32_t protocol;
    int32_t operation;
    int32_t pid;
    int32_t uid;
    int32_t issued_s;
    int32_t expires_s;
    int32_t reset_token;
};

constexpr int32_t maintenance_operation_value(SafetyMaintenanceOperation operation) {
    return static_cast<int32_t>(operation);
}

constexpr bool maintenance_operation_supported(int32_t operation) {
    return operation == maintenance_operation_value(SafetyMaintenanceOperation::kArmAbsent) ||
        operation == maintenance_operation_value(SafetyMaintenanceOperation::kRearmKnown);
}

constexpr bool maintenance_grant_shape(const MaintenanceGrantWire &wire,
    SafetyMaintenanceOperation operation) {
    return wire.ack == kMaintenanceAck && wire.protocol == kMaintenanceProtocol &&
        wire.operation == maintenance_operation_value(operation) && wire.pid > 0 && wire.uid >= 10000 &&
        wire.issued_s >= 0 && wire.expires_s > wire.issued_s &&
        wire.expires_s - wire.issued_s <= kSnapshotLeaseSeconds &&
        wire.reset_token > 0 && wire.reset_token <= INT32_MAX;
}

constexpr SafetyMaintenanceGrant maintenance_grant_from_wire(const MaintenanceGrantWire &wire) {
    return SafetyMaintenanceGrant{static_cast<uint32_t>(wire.pid), static_cast<uint32_t>(wire.uid),
        static_cast<SafetyMaintenanceOperation>(wire.operation), wire.issued_s, wire.expires_s,
        wire.reset_token};
}

}  // namespace launcher
