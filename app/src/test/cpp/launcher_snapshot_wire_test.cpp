/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#include "launcher/snapshot_wire.h"

using namespace launcher;

static_assert(kSnapshotFeatureCount == 1, "数量功能位必须是 bit0");
static_assert(kSnapshotFeatureShare == 2, "分享功能位必须是 bit1");
static_assert(kSnapshotFeatureFreeform == 4, "小窗功能位必须是 bit2");
static_assert(kSnapshotFeatureTasks == 8, "新任务功能位必须是 bit3");

// 一个字段齐备、时钟正常的快照。issued/expires 用开机时钟的整秒，租期恰好 30 秒。
constexpr Snapshot valid{kSnapshotAck, kSnapshotProtocol, 1, 1000, 1030, 1, 5, 1, 0, 0};
static_assert(snapshot_errors(valid, 0, 1000) == 0, "合法快照可通过");
static_assert(snapshot_errors(valid, 0, 1030) == kSnapshotExpired, "到期边界立即过期");
static_assert(snapshot_errors(valid, 0, 999) & kSnapshotUnsafeClock, "签发晚于当前开机时钟拒绝");
static_assert(snapshot_errors(valid, 0, -1) & kSnapshotUnsafeClock, "读取时钟失败不能放行");

// 确认码与协议是通道识别，不能靠数值范围猜。
static_assert(snapshot_errors({0, kSnapshotProtocol, 1, 1000, 1030, 1, 5, 1, 0, 0}, 0, 1000)
    & kSnapshotAckMismatch, "确认码不符必须识别为另一条消息");
static_assert(snapshot_errors({kSnapshotAck, 2, 1, 1000, 1030, 1, 5, 1, 0, 0}, 0, 1000)
    & kSnapshotProtocolMismatch, "协议版本不可外推");

// 序号必须严格递增，否则旧回包可以覆盖新配置。
static_assert(snapshot_errors(valid, 1, 1000) & kSnapshotStaleRevision, "相同序号不得回放");
static_assert(snapshot_errors(valid, 2, 1000) & kSnapshotStaleRevision, "旧序号不得覆盖新序号");
static_assert(snapshot_errors({kSnapshotAck, kSnapshotProtocol, 0, 1000, 1030, 1, 5, 1, 0, 0}, 0, 1000)
    & kSnapshotMalformed, "序号从一开始");
static_assert(snapshot_errors({kSnapshotAck, kSnapshotProtocol, INT32_MAX, 1000, 1030, 1, 5, 1, 0, 0}, 0, 1000)
    == 0, "序号到 INT32_MAX 仍合法");

// 功能位与上限范围与既有 JNI 预检保持同一语义。
static_assert(snapshot_errors({kSnapshotAck, kSnapshotProtocol, 1, 1000, 1030, 16, 5, 1, 0, 0}, 0, 1000)
    & kSnapshotMalformed, "未知功能位拒绝");
static_assert(snapshot_errors({kSnapshotAck, kSnapshotProtocol, 1, 1000, 1030, 1, 4, 1, 0, 0}, 0, 1000)
    & kSnapshotMalformed, "上限低于 5 拒绝");
static_assert(snapshot_errors({kSnapshotAck, kSnapshotProtocol, 1, 1000, 1030, 1, 21, 1, 0, 0}, 0, 1000)
    & kSnapshotMalformed, "上限高于 20 拒绝");
static_assert(snapshot_errors({kSnapshotAck, kSnapshotProtocol, 1, 1000, 1030, 15, 20, 1, 0, 0}, 0, 1000)
    == 0, "四个开关与上限上界有效");

// 全关配置合法但不能作为有效功能快照使用。
constexpr Snapshot allOff{kSnapshotAck, kSnapshotProtocol, 1, 1000, 1030, 0, 5, 1, 0, 0};
static_assert(snapshot_errors(allOff, 0, 1000) == 0, "全关本身不是畸形配置");
static_assert(!snapshot_has_features(allOff), "全关必须被识别为没有功能");
static_assert(snapshot_has_features(valid), "有开关位才算有效功能");

// 当前生产入口已接入数量、分享 APK 与小窗；新任务仍必须整体拒绝，不能静默
// 降级成“只安装已支持功能”。
static_assert(!snapshot_features_supported_by_production(0), "没有数量位不能进入数量安装链路");
static_assert(snapshot_features_supported_by_production(kSnapshotFeatureShare),
    "分享位应进入菜单安装链路");
static_assert(snapshot_features_supported_by_production(kSnapshotFeatureFreeform),
    "小窗位应进入菜单安装链路");
static_assert(snapshot_features_supported_by_production(kSnapshotFeatureCount | kSnapshotFeatureShare),
    "已支持的组合功能位应整体进入菜单安装链路");
static_assert(!snapshot_features_supported_by_production(kSnapshotFeatureTasks),
    "新任务位仍不得进入生产安装链路");
static_assert(snapshot_features_supported_by_production(kSnapshotFeatureCount),
    "只有数量位才可进入数量安装链路");

// 保留位与负值字段一律畸形，防止未来升级被旧版本误读。
static_assert(snapshot_errors({kSnapshotAck, kSnapshotProtocol, 1, 1000, 1030, 1, 5, 1, 0, 1}, 0, 1000)
    & kSnapshotMalformed, "保留位非零拒绝");
static_assert(snapshot_errors({kSnapshotAck, kSnapshotProtocol, 1, -1, 1030, 1, 5, 1, 0, 0}, 0, 1000)
    & kSnapshotMalformed, "负签发时间拒绝");
static_assert(snapshot_errors({kSnapshotAck, kSnapshotProtocol, 1, 1000, 1030, 1, 5, 1, -1, 0}, 0, 1000)
    & kSnapshotMalformed, "负恢复令牌拒绝");
static_assert(snapshot_errors({kSnapshotAck, kSnapshotProtocol, 1, 1000, 1030, 1, 5, 2, 0, 0}, 0, 1000)
    & kSnapshotMalformed, "armed 只能是 0 或 1");

// 租期上限与单调时钟边界。
static_assert(snapshot_errors({kSnapshotAck, kSnapshotProtocol, 1, 1000, 1031, 1, 5, 1, 0, 0}, 0, 1000)
    & kSnapshotUnsafeClock, "超长租期拒绝");
static_assert(snapshot_errors({kSnapshotAck, kSnapshotProtocol, 1, 2000, 1000, 1, 5, 1, 0, 0}, 0, 1000)
    & kSnapshotUnsafeClock, "到期早于签发拒绝");

// 回归：时间字段必须是**整秒**而不是毫秒。
//
// 设备连续开机超过 2^31 毫秒（约 24.85 天）时，毫秒编码会越过 int32 上界，
// 让整条通道在没有任何故障的情况下永久失效。用开机 300 万秒（约 34.7 天）
// 的快照钉住这一点：只要它仍然可采纳，就说明编码确实是秒。
constexpr Snapshot longUptime{kSnapshotAck, kSnapshotProtocol, 1, 3000000, 3000030, 1, 5, 1, 0, 0};
static_assert(snapshot_errors(longUptime, 0, 3000000) == 0, "长时间开机后快照仍须可采纳");
static_assert(snapshot_errors(longUptime, 0, 3000030) == kSnapshotExpired, "长时间开机后仍按秒判到期");
// 这三个常量把单位钉死：租期是 30 秒；若有人改回毫秒（30000），
// 「30000 秒租期」就会被上界拒绝，而不是被当作合法的 30 秒。
static_assert(kSnapshotLeaseSeconds == 30, "租期必须是 30 秒");
static_assert(snapshot_errors({kSnapshotAck, kSnapshotProtocol, 1, 1000, 31000, 1, 5, 1, 0, 0}, 0, 1000)
    & kSnapshotUnsafeClock, "30000 被按秒解读时必须是超长租期，而不是合法的 30 秒");

// 安全证据是「快照可信」与「本地熔断允许」两个条件的与。
// 本地状态用墙上时钟，快照租期用开机时钟，两个时钟互不污染。
constexpr SafetyFuseState localArmed{1700000000000LL, 0, false, 0, 1700000000000LL};
static_assert(snapshot_safety_permits(valid, 0, 1000, 1700000001000LL, localArmed, true, true),
    "快照可信且本地未熔断时可作为安全证据");
static_assert(!snapshot_safety_permits(valid, 0, 1000, 1700000001000LL, localArmed, false, true),
    "本地状态不可读时不能作为安全证据");
constexpr SafetyFuseState localFired{1700000000000LL, 3, true, 0, 1699999999000LL};
static_assert(!snapshot_safety_permits(valid, 0, 1000, 1700000001000LL, localFired, true, true),
    "本地熔断后即便快照可信也不能作为安全证据");
// 安全维度只看「快照可信 + 已武装 + 本地未熔断」，不看功能位；功能位由
// snapshot_has_features 单独判定，两者不能互相顶替。
static_assert(snapshot_safety_permits(allOff, 0, 1000, 1700000001000LL, localArmed, true, true),
    "功能位与安全维度互相独立：全关快照的安全结论仍可成立");

// 线路上可表示恢复字段，不等于普通预检或启动事务获准消费它。
constexpr Snapshot resetRequested{kSnapshotAck, kSnapshotProtocol, 1, 1000, 1030, 1, 5, 1, 1, 0};
constexpr Snapshot resetSaturated{kSnapshotAck, kSnapshotProtocol, 1, 1000, 1030, 1, 5, 1, INT32_MAX, 0};
constexpr Snapshot resetNegative{kSnapshotAck, kSnapshotProtocol, 1, 1000, 1030, 1, 5, 1, -1, 0};
static_assert(snapshot_errors(resetRequested, 0, 1000) == 0, "线路形态校验不是恢复授权");
static_assert(ordinary_snapshot_errors(valid, 0, 1000) == 0, "普通零令牌快照保持有效");
static_assert(ordinary_snapshot_errors(valid, 0, 1030) == kSnapshotExpired, "普通快照仍受租期约束");
static_assert(ordinary_snapshot_errors(resetRequested, 0, 1000) == kSnapshotRecoveryUnsupported,
    "普通启动拒绝正恢复令牌");
static_assert(ordinary_snapshot_errors(resetSaturated, 0, 1000) == kSnapshotRecoveryUnsupported,
    "普通启动拒绝饱和恢复令牌");
static_assert(ordinary_snapshot_errors(resetNegative, 0, 1000) ==
    (kSnapshotMalformed | kSnapshotRecoveryUnsupported), "负令牌不能绕过形态与用途门禁");
static_assert(ordinary_snapshot_errors(resetRequested, 0, 1030) ==
    (kSnapshotExpired | kSnapshotRecoveryUnsupported), "新增门禁不覆盖原有错误位");
static_assert(!snapshot_safety_permits(resetRequested, 0, 1000, 1700000001000LL, localFired, true, true),
    "普通快照不能解开已有熔断");
static_assert(!snapshot_safety_permits(resetRequested, 0, 1000, 1700000001000LL, localArmed, true, true),
    "尚未熔断也不能借普通快照清零计数");
constexpr SafetyFuseState recoveredEarlier{1700000000000LL, 1, false, INT32_MAX, 1700000000000LL};
static_assert(snapshot_safety_permits(valid, 0, 1000, 1700000001000LL, recoveredEarlier, true, true),
    "普通零令牌允许沿用现成本地恢复高水位");

int main() {
    return 0;
}
