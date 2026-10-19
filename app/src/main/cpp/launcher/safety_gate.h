/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#pragma once

#include <stddef.h>
#include <stdint.h>

// 桌面原生进程没有 ART，Kotlin 侧的 SafeModeRuntime/BootLoopGuard 在本进程里
// 根本跑不起来（它们依赖 SharedPreferences、ContentResolver 和 Java 线程）。
// 因此桌面必须有自己的一份等价实现，且必须是纯计算 + 极小的持久化，
// 以便交叉编译期断言和离线复算。
//
// 这里有且只有一条原则：**任何一次 hook 安装之前，本进程的安全状态必须已经
// 从磁盘读回并判定为“已启用且未熔断”**。读不到、格式错、时钟异常或已熔断，
// 一律按“不安全”处理，绝不放行。
namespace launcher {

// 桌面作用域的稳定标识，用于磁盘上的安全状态文件名与界面侧的键。
//
// 一致性来源有两处，且**都不在主模块的 `SCOPES` 里**：主模块 `SCOPES` 是界面侧的
// 「可重启宿主清单」，桌面进程不在其中。真正声明桌面注入作用域的是 launcherNative 的
// `resources/META-INF/xposed/scope.list`，其 Kotlin 化身是
// `LauncherHostContract.PACKAGE`。本常量必须同时等于这两处。
//
// 只读入口以本常量打开现成的桌面私有目录；安装仍须现场 Hook 点、安全状态
// 和计划校验全部通过。恒等关系由
// `tools/launcher_safety_parity_audit.py` 的「作用域令牌与注入作用域一致」断言只读核对。
constexpr char kLauncherSafetyScope[] = "com.miui.home";

enum SafetyBlocker : uint32_t {
    kSafetyStateUnreadable = 1U << 0,  // 文件缺失、格式错误或读取失败
    kSafetyFuseDisabled = 1U << 1,     // 五分钟内连续重启三次，已熔断
    kSafetyUnsafeClock = 1U << 2,      // 系统时间早于可信下界或倒退
    kSafetyNotArmed = 1U << 3,         // 尚未记录过本代际的起始状态
};

// 与 Kotlin BootFuseState 同构：单一状态行，逗号分隔，允许缺省后两列以便升级。
struct SafetyFuseState {
    int64_t last_start_ms;
    uint32_t rapid_restarts;
    bool disabled;
    int64_t last_reset_token;
    int64_t window_start_ms;
};

constexpr int64_t kSafetyFuseWindowMs = 5 * 60 * 1000;
// 2019-09-13 之前的系统时间一律视为不可信；设备实测时间远晚于此。
constexpr int64_t kSafetyMinTimeMs = 1'600'000'000'000;

constexpr int64_t digit_value(char value) {
    return value >= '0' && value <= '9' ? value - '0' : -1;
}

// 仅解析非负十进制整数，遇到非数字立即失败。constexpr 环境没有 errno/异常。
constexpr bool parse_uint(const char *text, uint32_t size, size_t &cursor, int64_t &out) {
    int64_t value = 0;
    bool any = false;
    while (cursor < size) {
        const int64_t digit = digit_value(text[cursor]);
        if (digit < 0) break;
        if (value > (INT64_MAX - digit) / 10) return false;
        value = value * 10 + digit;
        cursor++;
        any = true;
    }
    if (!any) return false;
    out = value;
    return true;
}

// 解析 "last,count[,disabled[,reset[,window]]]"。任何越界或多余字符都失败。
constexpr bool decode_safety_fuse(const char *text, uint32_t size, SafetyFuseState &out) {
    size_t cursor = 0;
    int64_t last = 0, count = 0, disabled = 0, reset = 0, window = -1;
    if (!parse_uint(text, size, cursor, last)) return false;
    auto field = [&](int64_t &value) -> int {
        if (cursor >= size || text[cursor] != ',') return 0;
        cursor++;
        int64_t parsed = 0;
        if (!parse_uint(text, size, cursor, parsed)) return -1;
        value = parsed;
        return 1;
    };
    if (field(count) != 1) return false;
    const int disabled_state = field(disabled);
    if (disabled_state < 0 || disabled > 1) return false;
    if (disabled_state == 1) {
        const int reset_state = field(reset);
        if (reset_state < 0) return false;
        if (reset_state == 1) {
            const int window_state = field(window);
            if (window_state < 0) return false;
            if (window_state == 0) window = last;
        } else {
            reset = 0;
            window = last;
        }
    } else {
        window = last;
    }
    if (cursor != size) return false;
    if (last < 0 || count < 0 || count > 3 || reset < 0 || window < 0 || window > last) return false;
    out = SafetyFuseState{last, static_cast<uint32_t>(count), disabled == 1 || count >= 3, reset, window};
    return true;
}

// 返回“是否可继续”，并把新状态写回 next（与 Kotlin BootFusePolicy.next 同构）。
// 读不到旧状态或时钟异常一律返回 false，且不产生可持久化的替代状态。
struct SafetyFuseDecision {
    bool ok;           // true 才允许按 next 记录启动并继续
    SafetyFuseState next;
    uint32_t blockers;  // ok==false 时说明原因
};

constexpr SafetyFuseDecision safety_fuse_next(const SafetyFuseState &previous, bool have_previous,
    int64_t now, int64_t reset_token, bool count_restart) {
    if (!have_previous || now < kSafetyMinTimeMs) {
        return {false, previous, kSafetyStateUnreadable | (now < kSafetyMinTimeMs ? kSafetyUnsafeClock : kSafetyNotArmed)};
    }
    if (reset_token > previous.last_reset_token) {
        return {true, SafetyFuseState{now, 0, false, reset_token, now}, 0};
    }
    if (previous.disabled) return {false, previous, kSafetyFuseDisabled};
    if (now < previous.last_start_ms) return {false, previous, kSafetyUnsafeClock};
    if (!count_restart) return {true, previous, 0};
    const bool same_window = previous.last_start_ms > 0 && now - previous.window_start_ms <= kSafetyFuseWindowMs;
    const uint32_t count = same_window ? (previous.rapid_restarts + 1 > 3 ? 3 : previous.rapid_restarts + 1) : 0;
    return {true, SafetyFuseState{now, count, count >= 3, previous.last_reset_token,
        same_window ? previous.window_start_ms : now}, 0};
}

// 把决定压缩成安装许可要用的布尔：只有 ok 且未熔断才算安全。
constexpr bool safety_permits_install(const SafetyFuseDecision &decision) {
    return decision.ok && !decision.next.disabled;
}

}  // 命名空间 launcher
