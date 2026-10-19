/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#pragma once

#include <stdint.h>

namespace launcher {

// 桌面分享桥与 system_server 之间的独立事务。请求体首字是协议号，随后是
// AParcel 字符串形式的包名；回复体首字是确认码。该码不与快照/维护事务重叠。
constexpr uint32_t kShareTransaction = 0x00484543U;
constexpr int32_t kShareProtocol = 1;
constexpr int32_t kShareAck = 0x48455331;       // "HES1"
constexpr int32_t kShareRejectedAck = 0x48455330; // "HES0"
constexpr int32_t kShareQueuedAck = 0x48455332;   // "HES2"
constexpr int kShareReplyWords = 2;
constexpr uint32_t kSharePointPrimary = 1;
constexpr uint32_t kSharePointSecondary = 2;

constexpr bool share_point_supported(uint32_t point) {
    return point == kSharePointPrimary || point == kSharePointSecondary;
}

constexpr bool share_ack_valid(int32_t ack, int32_t protocol) {
    return ack == kShareAck && protocol == kShareProtocol;
}

constexpr int32_t share_reply_ack(bool accepted) {
    return accepted ? kShareAck : kShareRejectedAck;
}

constexpr bool share_reply_queued(int32_t ack, int32_t protocol) {
    return ack == kShareQueuedAck && protocol == kShareProtocol;
}

}  // 命名空间 launcher
