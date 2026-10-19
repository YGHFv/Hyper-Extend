/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#include "launcher/config_preflight.h"
#include "launcher/probe_state.h"

using namespace launcher;
constexpr ConfigPacket valid{1, 100, 1, 1000, 2000, 1, 5};
static_assert(config_errors(valid, 100, 0, 1000) == 0, "合法配置可预检");
static_assert(config_errors(valid, 101, 0, 1000) & kWrongProcess, "父进程配置不能进入子进程");
static_assert(config_errors(valid, 100, 1, 1000) & kStaleRevision, "相同修订不得回放");
static_assert(config_errors(valid, 100, 2, 1000) & kStaleRevision, "旧修订不得覆盖新修订");
static_assert(config_errors(valid, 100, 0, 999) & kUnsafeClock, "未来配置不得使用");
static_assert(config_errors(valid, 100, 0, -1) & kUnsafeClock, "读取时钟失败不能放行");
static_assert(config_errors(valid, 100, 0, 2000) & kExpiredConfig, "到期边界立即拒绝");
static_assert(config_errors({2, 100, 1, 1000, 2000, 1, 5}, 100, 0, 1000) & kMalformedConfig, "协议版本不可猜测");
static_assert(config_errors({1, 100, 0, 1000, 2000, 1, 5}, 100, 0, 1000) & kMalformedConfig, "修订从一开始");
static_assert(config_errors({1, 100, INT64_MAX, 1000, 2000, 1, 5}, 100, 0, 1000) & kMalformedConfig, "修订不能截断");
static_assert(config_errors({1, 100, 1, 1000, 2000, 16, 5}, 100, 0, 1000) & kMalformedConfig, "未知功能位拒绝");
static_assert(config_errors({1, 100, 1, 1000, 2000, 1, 40}, 100, 0, 1000) & kMalformedConfig, "上限不是 Smi");
static_assert(config_errors({1, 100, 1, 1000, 31000, 15, 20}, 100, 0, 1000) == 0, "最大租期与四个开关有效");
static_assert(config_errors({1, 100, 1, 1000, 31001, 1, 5}, 100, 0, 1000) & kUnsafeClock, "超长租期拒绝");
static_assert(config_errors({1, 100, 1, -1, INT64_MAX, 1, 5}, 100, 0, 1000) & kUnsafeClock, "恶意时钟不触发有符号溢出");
static_assert(config_errors({1, 100, 1, 1000, 2000, 0, 5}, 100, 0, 1000) == kNoFeatures, "全关与读取失败分开");
static_assert(revision_owner(revision_record(101, UINT32_MAX)) == 101, "修订不能污染进程字段");
static_assert(kUnverifiedPermissions != 0, "配置正确也不构成安装许可");
static_assert(!configuration_probe_ready(100, 100, callback_record(100, 0, false)), "空入口参数不能建立配置通道");
static_assert(!configuration_probe_ready(101, 100, callback_record(100, 1, true)), "父进程回调不能建立子进程通道");
static_assert(configuration_probe_ready(100, 100, callback_record(100, 1, true)), "本进程回调只允许配置诊断");
