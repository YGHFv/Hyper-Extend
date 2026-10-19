/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#pragma once

#include <stdint.h>

#include "snapshot_wire.h"

// 桌面进程向 system_server 取配置的同步 Binder 事务。
//
// 上游 HyperCeiler 用 0x0048434A..0x0048434D 这一组事务码走同一个
// `IWindowManager` 通道；本项目必须用**不与它重叠**的码，否则两套模块的
// 端点会互相截获对方的 Parcel，读成畸形数据。这里固定为 0x00484541，
// 请求体为空（顺序与字节都固定），回复体是 snapshot_wire.h 的 10 个字。
namespace launcher {

constexpr uint32_t kChannelTransaction = 0x00484541U;

enum ChannelBlocker : uint32_t {
    kChannelNoBinder = 1U << 0,          // 本进程没有可用的 libbinder_ndk
    kChannelNoWindowService = 1U << 1,   // 取不到 window 服务
    kChannelTransactFailed = 1U << 2,    // 事务失败或没有回复体
    kChannelShortReply = 1U << 3,        // 回复体字段不足
    kChannelSnapshotRejected = 1U << 4,  // 回复体存在但不满足快照契约
    kChannelNotQueried = 1U << 5,        // 尚未发起查询，不得与成功的 0 混淆
    kChannelBusy = 1U << 6,              // 同进程已有查询在途；不等待、不再发事务
    kChannelWrongProcess = 1U << 7,      // fork 继承的 Binder/快照状态不可复用
};

// 把回复体判成一次可采纳的配置。字段数必须精确等于契约长度；不足即拒绝，
// 多余字段由调用方在读取层忽略（服务端升级时不改字数的兼容策略）。
//
// 返回结构体而不是出参：判定结果与解析出的快照必须一起返回，调用方无法只取其一，
// 也让整个判定可以在编译期断言。
struct ChannelDecision {
    uint32_t blockers;
    Snapshot snapshot;
};

constexpr ChannelDecision channel_decision(const int32_t *words, int length,
    uint32_t last_revision, int64_t boot_now_s) {
    if (length != kSnapshotReplyWords) return {kChannelShortReply, Snapshot{}};
    const Snapshot snapshot = snapshot_from_words(words);
    return {snapshot_errors(snapshot, last_revision, boot_now_s) == 0
        ? 0U : kChannelSnapshotRejected, snapshot};
}

}  // 命名空间 launcher
