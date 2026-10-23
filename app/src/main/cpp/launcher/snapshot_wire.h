/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#pragma once

#include <stdint.h>

#include "safety_gate.h"

// 桌面原生进程没有 ART，Kotlin 侧的 SafeModeRuntime、RemotePreferences、
// SharedPreferences 与 ContentResolver 在本进程里全部不可用。设备只读取证表明
// 仍有唯一可行通道，且是宿主本来就有的：
//
//   * 桌面进程 maps 中已加载 /system/lib64/libbinder.so 与 libbinder_ndk.so；
//   * HyperCeiler 的配置缓存 /data/user/0/com.miui.home/files/layout_config_cache.bin
//     真实存在（头四字节 "HCLC"、版本 5、152 字节），该目录属主 u0_a139 正是桌面
//     uid，说明桌面私有目录就是本进程自己的可读写目录；
//   * system_server 侧的端点是 IWindowManager$Stub.onTransact 上的同步事务，
//     本模块已有 system_server 作用域，不需要新增宿主，也不需要桌面出现 Java。
//
// 所以配置与安全状态只能由「桌面发起同步 Binder 事务、system_server 应答快照」
// 这一条通道送来。本头只固定该回复快照的字节契约与判定，不发起调用、不持久化，
// 以便编译期断言和离线复算。
//
// 契约是 fail-closed：字段越界、确认码不符、协议不符、序号回退、时钟异常、到期
// 或未武装都留下阻断位；任何「读不到就当默认值」的写法在这里都被拒绝。
namespace launcher {

// 回复首字：确认码，用来区分「端点应答」和「框架返回的其它 Parcel 内容」。
constexpr int32_t kSnapshotAck = 0x48453431;  // "HE41"
constexpr int32_t kSnapshotProtocol = 1;
// 回复的 32 位字段数：确认码、协议、序号、签发、到期、功能位、上限、
// 已武装、恢复令牌、保留位。
constexpr int kSnapshotReplyWords = 10;
// 快照租期上限，用**整秒**的开机单调时钟计量。
//
// 这里必须是秒而不是毫秒：回复体一共只有 10 个 32 位字段，时间字段本身也是
// int32_t。开机毫秒数在 uptime 超过 24.85 天（2^31 / 86400000）后就会越过 INT32_MAX，
// 回包要么变成负数（被 kSnapshotMalformed 拒绝），要么回绕成一个远小于
// now_s 的值（被 kSnapshotExpired 拒绝）——也就是说，按毫秒编码会让这条通道
// 在设备连续开机约 25 天后**必然失效**，且症状与「端点没起来」无法区分。
// 整秒的开机时间到 INT32_MAX 需要约 68 年，远长于任何设备的 uptime。
// 系统时间被改动既不能延长、也不能缩短这份租期。
constexpr int64_t kSnapshotLeaseSeconds = 30;
constexpr int64_t kSnapshotCountLimitMin = 5;
constexpr int64_t kSnapshotCountLimitMax = 20;

// 功能位顺序必须与 system_server 侧 LauncherSnapshotWire 保持一致。
// 当前生产入口具备数量、分享 APK 与小窗的调用点蹦床；新任务仍待独立接入。
constexpr int32_t kSnapshotFeatureCount = 1 << 0;
constexpr int32_t kSnapshotFeatureShare = 1 << 1;
constexpr int32_t kSnapshotFeatureFreeform = 1 << 2;
constexpr int32_t kSnapshotFeatureTasks = 1 << 3;
// 禁用应用预加载。它不经过快捷菜单主开关，也不安装任何代码页蹦床：
// 只在桌面进程的 __system_property_get 观察器里改写五个预加载属性。
constexpr int32_t kSnapshotFeaturePrestart = 1 << 4;
// 线路可表示的全部功能位（bit0..bit4）。新任务 bit3 仍未接入生产安装链路。
constexpr int32_t kSnapshotFeatureAll = 0x1F;

enum SnapshotBlocker : uint32_t {
    kSnapshotAckMismatch = 1U << 0,        // 首字不是确认码：不是本通道的应答
    kSnapshotProtocolMismatch = 1U << 1,   // 协议版本不受支持
    kSnapshotMalformed = 1U << 2,          // 字段越界或保留位非零
    kSnapshotStaleRevision = 1U << 3,      // 序号未严格递增
    kSnapshotUnsafeClock = 1U << 4,        // 开机时钟无效，或租期超长
    kSnapshotExpired = 1U << 5,            // 快照已过期
    kSnapshotNotArmed = 1U << 6,           // 用户尚未在设置页启用桌面原生功能
};

// 用途判据独立于通道的七个线路阻断位，不改变既有 HELauncherProbe 日志合同。
enum OrdinarySnapshotBlocker : uint32_t {
    kSnapshotRecoveryUnsupported = 1U << 7,
};
static_assert((kSnapshotRecoveryUnsupported & (kSnapshotAckMismatch | kSnapshotProtocolMismatch |
    kSnapshotMalformed | kSnapshotStaleRevision | kSnapshotUnsafeClock | kSnapshotExpired |
    kSnapshotNotArmed)) == 0, "用途阻断位不能与线路阻断位重叠");

struct Snapshot {
    int32_t ack;
    int32_t protocol;
    int32_t revision;
    int32_t issued_s;    // 开机单调时钟整秒
    int32_t expires_s;   // 开机单调时钟整秒
    int32_t features;    // 功能位：数量、分享、小窗、新任务（未接入）、禁止预加载
    int32_t count_limit;
    int32_t safety_armed;         // 0/1
    int32_t safety_reset_token;   // 用户人工恢复令牌，非负
    int32_t reserved;             // 必须为 0；留给不改长度的升级
};

// 把回复字段拼成结构体，同时拒绝任何形态错误。数组长度不符由调用方先判断。
constexpr Snapshot snapshot_from_words(const int32_t *words) {
    return Snapshot{words[0], words[1], words[2], words[3], words[4],
        words[5], words[6], words[7], words[8], words[9]};
}

// 只校验快照形态与租期，不涉及本地熔断状态，也不认证恢复授权。
// 普通预检和启动事务必须再用 ordinary_snapshot_errors 拒绝恢复字段。
// 返回 0 表示快照可被采纳为一次有效配置；任何非 0 位都必须阻断安装。
// now_s 是**开机单调时钟的整秒**（CLOCK_BOOTTIME / SystemClock.elapsedRealtime），
// 不是墙上时钟、也不是毫秒。
constexpr uint32_t snapshot_errors(const Snapshot &snapshot, uint32_t last_revision, int64_t now_s) {
    uint32_t errors = 0;
    if (snapshot.ack != kSnapshotAck) errors |= kSnapshotAckMismatch;
    if (snapshot.protocol != kSnapshotProtocol) errors |= kSnapshotProtocolMismatch;
    // revision 是 int32_t，其上限就是 INT32_MAX，不需要再比 UINT32_MAX。
    if (snapshot.revision <= 0 ||
        snapshot.features < 0 || snapshot.features > kSnapshotFeatureAll ||
        snapshot.count_limit < kSnapshotCountLimitMin ||
        snapshot.count_limit > kSnapshotCountLimitMax ||
        snapshot.issued_s < 0 || snapshot.expires_s < 0 ||
        snapshot.safety_armed < 0 || snapshot.safety_armed > 1 ||
        snapshot.safety_reset_token < 0 || snapshot.reserved != 0) {
        errors |= kSnapshotMalformed;
    }
    if (errors & kSnapshotMalformed) return errors;
    if (snapshot.revision <= static_cast<int32_t>(last_revision)) errors |= kSnapshotStaleRevision;
    if (now_s < 0 || snapshot.issued_s > now_s ||
        snapshot.expires_s <= snapshot.issued_s ||
        snapshot.expires_s - snapshot.issued_s > kSnapshotLeaseSeconds) {
        errors |= kSnapshotUnsafeClock;
    }
    if (!(errors & kSnapshotUnsafeClock) && snapshot.expires_s <= now_s) {
        errors |= kSnapshotExpired;
    }
    if (snapshot.safety_armed == 0) errors |= kSnapshotNotArmed;
    return errors;
}

// 普通配置只能记录启动，不能顺便恢复熔断；较新、相同和较旧的非零令牌均拒绝。
// 保留底层线路形态与熔断算法，未来恢复必须有独立授权和提交路径，不能放宽此门禁。
constexpr uint32_t ordinary_snapshot_errors(const Snapshot &snapshot, uint32_t last_revision,
    int64_t now_s) {
    return snapshot_errors(snapshot, last_revision, now_s) |
        (snapshot.safety_reset_token == 0 ? 0U : kSnapshotRecoveryUnsupported);
}

constexpr bool snapshot_has_features(const Snapshot &snapshot) {
    return snapshot.features != 0;
}

// 新任务仍未接入；分享和小窗已经有独立的透明叶蹦床与调用点合同，不能再被
// 数量安装链路静默忽略。
constexpr bool snapshot_features_supported_by_production(int32_t features) {
    constexpr int32_t supported = kSnapshotFeatureCount | kSnapshotFeatureShare |
        kSnapshotFeatureFreeform | kSnapshotFeaturePrestart;
    return features != 0 && (features & ~supported) == 0;
}

// 预加载功能位与快捷菜单位相互独立；调用方仍需先确认快照已成功采纳。
constexpr bool snapshot_prestart_enabled(int32_t features) {
    return (features & kSnapshotFeaturePrestart) != 0;
}

// 快捷菜单位（数量、分享、小窗）。只有这些位需要代码页安装计划。
constexpr int32_t kSnapshotMenuFeatures = kSnapshotFeatureCount | kSnapshotFeatureShare |
    kSnapshotFeatureFreeform;

// 普通启动的安全计算：快照有效、无恢复请求 **且** 本地熔断状态允许。
// 计算结果不替代持久化提交或实机验收。
//
// 两个时钟必须分开传，而且单位不同，名字里带上单位以免调用方混用：
//   * boot_now_s 计量快照租期，必须是开机单调时钟的整秒，防止改系统时间延长租期；
//   * wall_now_ms 计量启动熔断，必须是墙上时钟，因为人工恢复令牌和五分钟窗口
//     要跨重启比较，只有墙上时钟跨重启才有意义。
constexpr bool snapshot_safety_permits(const Snapshot &snapshot, uint32_t last_revision,
    int64_t boot_now_s, int64_t wall_now_ms, const SafetyFuseState &local, bool have_local,
    bool count_restart) {
    if (ordinary_snapshot_errors(snapshot, last_revision, boot_now_s) != 0) return false;
    const SafetyFuseDecision decision = safety_fuse_next(local, have_local, wall_now_ms,
        snapshot.safety_reset_token, count_restart);
    return safety_permits_install(decision);
}

}  // 命名空间 launcher
