/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#include "launcher/share_wire.h"

using namespace launcher;

static_assert(kShareTransaction == 0x00484543U, "分享事务码必须固定");
static_assert(kShareTransaction != 0x00484541U && kShareTransaction != 0x00484542U,
    "分享事务码不得与快照/维护事务重叠");
static_assert(kShareProtocol == 1 && kShareReplyWords == 2, "分享协议字段必须固定");
static_assert(kShareAck == 0x48455331 && kShareRejectedAck == 0x48455330,
    "确认码与拒绝码必须可区分");
static_assert(kShareQueuedAck == 0x48455332 && kShareQueuedAck != kShareAck,
    "排队确认码必须与转发完成码区分");
static_assert(share_point_supported(kSharePointPrimary) &&
    share_point_supported(kSharePointSecondary), "主/次分享调用点必须支持");
static_assert(!share_point_supported(0) && !share_point_supported(3),
    "未知分享调用点必须拒绝");
static_assert(share_ack_valid(kShareAck, kShareProtocol), "合法确认必须通过");
static_assert(!share_ack_valid(kShareRejectedAck, kShareProtocol), "拒绝码不得通过");
static_assert(!share_ack_valid(kShareAck, 2), "协议版本不得外推");
static_assert(share_reply_ack(true) == kShareAck && share_reply_ack(false) == kShareRejectedAck,
    "回复确认码必须由接受结果唯一决定");
static_assert(share_reply_queued(kShareQueuedAck, kShareProtocol), "排队确认必须通过");
static_assert(!share_reply_queued(kShareAck, kShareProtocol), "完成码不能被误判为排队");

int main() {
    return 0;
}
