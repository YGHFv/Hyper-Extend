/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#include "launcher/channel.h"

using namespace launcher;

// 事务码必须与上游 HyperCeiler 的 0x0048434A..0x0048434D 完全不重叠，
// 否则两套模块的端点会互相截获对方的 Parcel。
static_assert(kChannelTransaction == 0x00484541U, "事务码必须固定为已审核值");
static_assert(kChannelTransaction != 0x0048434AU && kChannelTransaction != 0x0048434BU &&
    kChannelTransaction != 0x0048434CU && kChannelTransaction != 0x0048434DU,
    "事务码不得与上游任何已用码重叠");
static_assert((kChannelTransaction & 0xff000000U) == 0, "自定义事务码高字节必须为 0");

// 一个字段齐备、时钟正常、序号为 7 的回复体。时间字段是开机时钟的**整秒**。
constexpr int32_t good[kSnapshotReplyWords] = {
    kSnapshotAck, kSnapshotProtocol, 7, 1000, 1030, 1, 5, 1, 0, 0};

// 字段不足必须拒绝，并且不能把半截回复当快照。
constexpr int32_t partial[3] = {kSnapshotAck, kSnapshotProtocol, 7};
static_assert(channel_decision(partial, 3, 0, 1000).blockers == kChannelShortReply,
    "字段不足必须识别为短回复");
static_assert(channel_decision(partial, 3, 0, 1000).snapshot.ack == 0,
    "短回复不得留下半个快照");

// 字段齐备但确认码不符必须拒绝，同时保留解析结果供诊断。
constexpr int32_t wrong_ack[kSnapshotReplyWords] = {
    0, kSnapshotProtocol, 7, 1000, 1030, 1, 5, 1, 0, 0};
static_assert(channel_decision(wrong_ack, kSnapshotReplyWords, 0, 1000).blockers == kChannelSnapshotRejected,
    "确认码不符必须被快照契约拒绝");
static_assert(channel_decision(wrong_ack, kSnapshotReplyWords, 0, 1000).snapshot.revision == 7,
    "被拒绝的回复仍要保留解析结果供诊断");

// 合法回复必须被接受，且字段逐字一致。
static_assert(channel_decision(good, kSnapshotReplyWords, 6, 1000).blockers == 0,
    "合法回复必须被接受");
constexpr ChannelDecision ok = channel_decision(good, kSnapshotReplyWords, 6, 1000);
static_assert(ok.snapshot.ack == kSnapshotAck && ok.snapshot.protocol == kSnapshotProtocol &&
    ok.snapshot.revision == 7 && ok.snapshot.issued_s == 1000 && ok.snapshot.expires_s == 1030 &&
    ok.snapshot.features == 1 && ok.snapshot.count_limit == 5 && ok.snapshot.safety_armed == 1 &&
    ok.snapshot.safety_reset_token == 0 && ok.snapshot.reserved == 0,
    "接受后的快照字段必须与输入逐字一致");

// 序号回退与过期同样要拒绝，即使字段形状正确。
static_assert(channel_decision(good, kSnapshotReplyWords, 7, 1000).blockers == kChannelSnapshotRejected,
    "相同序号不得回放");
static_assert(channel_decision(good, kSnapshotReplyWords, 0, 1030).blockers == kChannelSnapshotRejected,
    "到期回复不得接受");

// 长度不等于契约就要按短回复处理，不能部分读取。
constexpr int32_t longer[kSnapshotReplyWords + 1] = {
    kSnapshotAck, kSnapshotProtocol, 7, 1000, 1030, 1, 5, 1, 0, 0, 0};
static_assert(channel_decision(longer, kSnapshotReplyWords + 1, 0, 1000).blockers == kChannelShortReply,
    "长度不符必须整体拒绝");

// 端点拒绝调用方时发确认码、协议与全零载荷，不等同于正常设置的主开关关闭。
constexpr int32_t denied[kSnapshotReplyWords] = {kSnapshotAck, kSnapshotProtocol};
static_assert(channel_decision(denied, kSnapshotReplyWords, 0, 1000).blockers == kChannelSnapshotRejected,
    "全零拒绝载荷属于快照拒绝，不是通道成功");
static_assert(snapshot_errors(snapshot_from_words(denied), 0, 1000) == kSnapshotMalformed,
    "全零拒绝载荷先命中字段畸形，不能误报仅未武装");
constexpr int32_t unarmed[kSnapshotReplyWords] = {
    kSnapshotAck, kSnapshotProtocol, 7, 1000, 1030, 0, 5, 0, 0, 0};
static_assert(channel_decision(unarmed, kSnapshotReplyWords, 0, 1000).blockers == kChannelSnapshotRejected,
    "合法未武装配置仍拒绝采纳");
static_assert(snapshot_errors(snapshot_from_words(unarmed), 0, 1000) == kSnapshotNotArmed,
    "只有字段合法的未武装配置才得到单独的未武装位");

int main() {
    return 0;
}
