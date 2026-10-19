/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#pragma once

#include "diagnostic_probe.h"

namespace launcher {

// 沿用已部署的维护线路：首次武装操作 + 独立令牌域表示主动重启。
// 旧原生端只会因已有状态拒绝首次武装，不会把它当成解除熔断。
constexpr int64_t kPlannedRestartTokenTag = 0x40000000;

struct StartupRestartGrant {
    uint32_t pid;
    uint32_t uid;
    int64_t issued_s;
    int64_t expires_s;
    int64_t token;
    bool recover = false;
};

constexpr bool is_planned_restart_token(int64_t token) {
    return token > kPlannedRestartTokenTag && token <= INT32_MAX;
}

constexpr bool restart_grant_valid(const StartupRestartGrant &grant,
    const DiagnosticContext &context, int64_t now_s) {
    return diagnostic_context_ready(context) && grant.pid == context.pid &&
        grant.uid == context.uid &&
        (grant.recover ? grant.token > 0 && grant.token <= INT32_MAX
                       : is_planned_restart_token(grant.token)) &&
        now_s >= 0 && grant.issued_s >= 0 && grant.issued_s <= now_s &&
        grant.expires_s > now_s && grant.expires_s > grant.issued_s &&
        grant.expires_s - grant.issued_s <= 30;
}

}  // 命名空间 launcher
