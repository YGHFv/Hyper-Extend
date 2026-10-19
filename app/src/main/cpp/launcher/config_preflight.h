/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#pragma once

#include <stdint.h>

namespace launcher {

constexpr int kConfigWords = 7;
constexpr int64_t kConfigProtocol = 1;
constexpr int64_t kConfigLeaseMs = 30000;

enum ConfigBlocker : uint32_t {
    kMalformedConfig = 1U << 0,
    kWrongProcess = 1U << 1,
    kStaleRevision = 1U << 2,
    kUnsafeClock = 1U << 3,
    kExpiredConfig = 1U << 4,
    kNoFeatures = 1U << 5,
    kSessionUnavailable = 1U << 6,
    kConcurrentUpdate = 1U << 7,
    kHostNotVerified = 1U << 8,
    kSafetyNotVerified = 1U << 9,
    kBackendNotVerified = 1U << 10,
};

// 三项许可没有实现，不能由调用者通过配置包中的布尔值伪造。
constexpr uint32_t kUnverifiedPermissions = kHostNotVerified | kSafetyNotVerified | kBackendNotVerified;

struct ConfigPacket {
    int64_t protocol;
    int64_t pid;
    int64_t revision;
    int64_t issued_ms;
    int64_t expires_ms;
    int64_t features;
    int64_t count_limit;
};

constexpr uint32_t config_errors(const ConfigPacket &packet, uint32_t pid,
    uint32_t last_revision, int64_t now_ms) {
    uint32_t errors = 0;
    if (packet.protocol != kConfigProtocol || packet.revision <= 0 || packet.revision > UINT32_MAX ||
        packet.features < 0 || packet.features > 15 || packet.count_limit < 5 || packet.count_limit > 20) {
        errors |= kMalformedConfig;
    }
    if (pid == 0 || packet.pid <= 0 || packet.pid != pid) errors |= kWrongProcess;
    if (packet.revision <= last_revision) errors |= kStaleRevision;
    if (now_ms < 0 || packet.issued_ms < 0 || packet.issued_ms > now_ms ||
        packet.expires_ms <= packet.issued_ms ||
        (packet.issued_ms >= 0 && packet.expires_ms > packet.issued_ms &&
            packet.expires_ms - packet.issued_ms > kConfigLeaseMs)) {
        errors |= kUnsafeClock;
    }
    if (packet.expires_ms <= now_ms) errors |= kExpiredConfig;
    if (packet.features == 0) errors |= kNoFeatures;
    return errors;
}

constexpr uint64_t revision_record(uint32_t pid, uint32_t revision) {
    return (static_cast<uint64_t>(pid) << 32) | revision;
}

constexpr uint32_t revision_owner(uint64_t record) {
    return static_cast<uint32_t>(record >> 32);
}

}  // 命名空间 launcher
