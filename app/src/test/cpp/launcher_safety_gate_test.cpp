/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#include "launcher/safety_gate.h"

using namespace launcher;

namespace {

constexpr bool decode(const char *text, int64_t length, SafetyFuseState &out) {
    return decode_safety_fuse(text, static_cast<uint32_t>(length), out);
}

constexpr int64_t len(const char *text) {
    int64_t value = 0;
    while (text[value] != '\0') value++;
    return value;
}

constexpr bool decode_literal(const char *text, SafetyFuseState &out) {
    return decode(text, len(text), out);
}

}  // 匿名命名空间

// 桌面作用域的真实来源是 launcherNative 的 META-INF/xposed/scope.list 与
// LauncherHostContract.PACKAGE（主模块 SCOPES 是界面侧可重启宿主清单，不含桌面）；
// 退化为界面标题就会写错文件。
static_assert(kLauncherSafetyScope[0] == 'c' && kLauncherSafetyScope[2] == 'm' &&
    kLauncherSafetyScope[9] == 'h' && kLauncherSafetyScope[10] == 'o' &&
    kLauncherSafetyScope[11] == 'm' && kLauncherSafetyScope[12] == 'e' &&
    kLauncherSafetyScope[13] == '\0',
    "桌面安全作用域必须是 com.miui.home");

// 合法状态行：完整五列与省略后两列的旧格式都必须能解出同一语义。
static_assert([] {
    SafetyFuseState state{};
    return decode_literal("1700000000000,2,0,7,1699999999000", state) &&
        state.last_start_ms == 1700000000000LL && state.rapid_restarts == 2 &&
        !state.disabled && state.last_reset_token == 7 && state.window_start_ms == 1699999999000LL;
}(), "完整状态行必须正确解析");
static_assert([] {
    SafetyFuseState state{};
    return decode_literal("1700000000000,0", state) && state.window_start_ms == 1700000000000LL &&
        state.last_reset_token == 0 && !state.disabled;
}(), "旧的两列格式必须按 window=last 解析");
static_assert([] {
    SafetyFuseState state{};
    return decode_literal("1700000000000,3", state) && state.disabled;
}(), "计数达到 3 必须自动视为熔断");

// 任何畸形输入都不得被当成可用状态。
static_assert([] { SafetyFuseState s{}; return !decode_literal("", s); }(), "空串必须失败");
static_assert([] { SafetyFuseState s{}; return !decode_literal("1700000000000", s); }(), "缺少计数必须失败");
static_assert([] { SafetyFuseState s{}; return !decode_literal("1700000000000,1,", s); }(), "悬空逗号必须失败");
static_assert([] { SafetyFuseState s{}; return !decode_literal("1700000000000,4", s); }(), "计数越界必须失败");
static_assert([] { SafetyFuseState s{}; return !decode_literal("1700000000000,1,2", s); }(), "布尔位非 0/1 必须失败");
static_assert([] { SafetyFuseState s{}; return !decode_literal("1700000000000,1,0,x", s); }(), "非数字列必须失败");
static_assert([] { SafetyFuseState s{}; return !decode_literal("1700000000000,1,0,1,1800000000000", s); }(),
    "窗口晚于起始时间必须失败");

// 熔断决策：读不到旧状态、时钟异常都必须拒绝，且不产生可持久化替代状态。
static_assert(!safety_fuse_next(SafetyFuseState{}, false, 1700000000000LL, 0, true).ok,
    "没有旧状态不能开始");
static_assert((safety_fuse_next(SafetyFuseState{}, false, 1700000000000LL, 0, true).blockers & kSafetyNotArmed) != 0,
    "缺少旧状态时阻塞位是未武装");
static_assert(!safety_fuse_next(SafetyFuseState{}, true, 1000, 0, true).ok,
    "早于可信下界的时钟必须拒绝");
static_assert((safety_fuse_next(SafetyFuseState{}, true, 1000, 0, true).blockers & kSafetyUnsafeClock) != 0,
    "时钟异常要有对应阻塞位");
static_assert(!safety_fuse_next(SafetyFuseState{1700000000000LL, 0, false, 0, 1700000000000LL}, true,
    1699999999000LL, 0, true).ok, "时钟倒退必须拒绝");

// 已熔断状态必须持续拒绝，直到出现严格递增的恢复令牌。
constexpr SafetyFuseState fired{1700000000000LL, 3, true, 5, 1699999999000LL};
static_assert(!safety_fuse_next(fired, true, 1700000000001LL, 5, true).ok, "熔断后不得继续");
static_assert((safety_fuse_next(fired, true, 1700000000001LL, 5, true).blockers & kSafetyFuseDisabled) != 0,
    "熔断要有对应阻塞位");
static_assert(safety_fuse_next(fired, true, 1700000000001LL, 6, true).ok, "严格递增的恢复令牌可以复位");
static_assert(!safety_fuse_next(fired, true, 1700000000001LL, 5, true).ok, "相同令牌不能复位");
static_assert(!safety_fuse_next(fired, true, 1700000000001LL, 4, true).ok, "回退令牌不能复位");

// 正常计数：五分钟窗口内第三次即熔断，窗口外重新计数。
constexpr auto first = safety_fuse_next(SafetyFuseState{1700000000000LL, 0, false, 0, 1700000000000LL}, true,
    1700000001000LL, 0, true);
static_assert(first.ok && first.next.rapid_restarts == 1 && !first.next.disabled && safety_permits_install(first),
    "窗口内第一次重启不熔断");
constexpr auto second = safety_fuse_next(first.next, true, 1700000002000LL, 0, true);
static_assert(second.ok && second.next.rapid_restarts == 2 && !second.next.disabled, "第二次重启仍不熔断");
constexpr auto third = safety_fuse_next(second.next, true, 1700000003000LL, 0, true);
static_assert(third.ok && third.next.rapid_restarts == 3 && third.next.disabled, "第三次重启必须熔断");
static_assert(!safety_permits_install(third), "熔断后不允许安装");
constexpr auto later = safety_fuse_next(second.next, true, 1700001000000LL, 0, true);
static_assert(later.ok && later.next.rapid_restarts == 0 && !later.next.disabled, "窗口外必须重新计数");

// 不带计数（例如同进程内多次询问）不改变次数，也不熔断。
constexpr auto idle = safety_fuse_next(SafetyFuseState{1700000000000LL, 1, false, 0, 1700000000000LL}, true,
    1700000000500LL, 0, false);
static_assert(idle.ok && idle.next.rapid_restarts == 1 && safety_permits_install(idle), "不计数时必须保持原状");

int main() {
    return 0;
}
