/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#define LAUNCHER_SAFETY_MAINTENANCE_TRIAL
#include "launcher_safety_startup_trial.cpp"

extern "C" __attribute__((visibility("default")))
int launcher_planned_restart_trial(int code) {
    using namespace launcher;
    using namespace trial;
    app_group_files = true;
    StartupRestartGrant grant{pid, uid, 1000, 1030, kPlannedRestartTokenTag + 7};
    if (code == 0) {
        for (int i = 0; i < 6; ++i) {
            g_startup_owner = 0; grant.pid = ++pid;
            auto config = snapshot(); config.count_limit = 8 + i;
            const auto result = commit_safety_startup_once(context(), config, 6, &grant);
            CHECK(result.safety_ready() && result.next.rapid_restarts == 0 && !result.next.disabled);
            CHECK(result.next.last_reset_token == 0);
        }
        for (int i = 1; i <= 3; ++i) {
            g_startup_owner = 0; ++pid;
            const auto result = commit_safety_startup_once(context(), snapshot(), 6);
            CHECK(result.next.rapid_restarts == static_cast<uint32_t>(i));
            CHECK(result.safety_ready() == (i < 3));
        }
    } else if (code >= 1 && code <= 7) {
        if (code == 1) ++grant.pid;
        if (code == 2) ++grant.uid;
        if (code == 3) grant.expires_s = 1000;
        if (code == 4) grant.issued_s = 1001;
        if (code == 5) grant.expires_s = 1031;
        if (code == 6) grant.token = kPlannedRestartTokenTag;
        if (code == 7) grant.token = INT64_MAX;
        const auto result = commit_safety_startup_once(context(), snapshot(), 6, &grant);
        CHECK(result.blockers == kStartupCallerRejected && io_calls == 0);
    } else if (code >= 8 && code <= 12) {
        set_state("1700000000000,3,1,50,1700000000000");
        if (code >= 9) { grant.recover = true; grant.token = code == 10 ? 50 : 51; }
        if (code == 11) pending_exists = true;
        if (code == 12) set_state("damaged");
        const auto result = commit_safety_startup_once(context(), snapshot(), 6, &grant);
        CHECK(result.safety_ready() == (code == 9));
        if (code == 9) {
            CHECK(result.next.last_start_ms == 1700000001000 && result.next.rapid_restarts == 0);
            CHECK(result.next.last_reset_token == 51);
        } else if (code == 8) {
            // 安全模式下的普通桌面重启仍需落盘启动时间，但保持熔断锁存；
            // 不能把“重启”误当成恢复，也不能因 disabled 提前跳过提交。
            CHECK(result.blockers == kStartupFuseRejected && result.published &&
                result.committed && result.next.disabled && writes > 0);
        } else CHECK(writes == 0 && !result.published && !result.committed);
    } else if (code == 13) {
        CHECK(commit_safety_startup_once(context(), snapshot(), 6, &grant).safety_ready());
        const int before = writes;
        CHECK(commit_safety_startup_once(context(), snapshot(), 6, &grant).blockers == kStartupAlreadyAttempted);
        CHECK(writes == before);
    } else if (code == 14) {
        scenario = 42;
        const auto result = commit_safety_startup_once(context(), snapshot(), 6, &grant);
        CHECK(result.blockers != 0 && !result.published && !result.safety_ready());
    } else if (code == 15) {
        automatic_start_test = true; lock_exists = state_exists = false;
        grant.recover = true; grant.token = 51;
        const auto result = commit_safety_startup_once(context(), snapshot(), 6, &grant);
        CHECK(result.blockers == kStartupStateUnreadable && writes == 0 && lock_creates == 0);
    } else if (code == 16) {
        set_state("1700000000000,3,1,50,1700000000000");
        grant.recover = true; grant.token = 51;
        auto config = snapshot(); config.features = 0;
        const auto result = commit_safety_startup_once(context(), config, 6, &grant);
        CHECK(result.safety_ready() && result.next.last_reset_token == 51);
    } else if (code == 17) {
        set_state("1700000002000,2,0,0,1700000002000");
        const auto result = commit_safety_startup_once(context(), snapshot(), 6, &grant);
        CHECK(result.blockers == kStartupFuseRejected && writes == 0 &&
            !result.published && !result.committed);
    } else if (code == 18 || code == 19) {
        const uint32_t count = code == 18 ? 1 : 2;
        set_state(code == 18
            ? "1700000000000,1,0,0,1700000000000"
            : "1700000000000,2,0,0,1700000000000");
        const auto result = commit_safety_startup_once(context(), snapshot(), 6, &grant);
        CHECK(result.safety_ready() && result.next.rapid_restarts == count &&
            result.next.last_start_ms == 1700000001000 &&
            result.next.window_start_ms == 1700000000000);
    } else if (code == 20) {
        set_state("1700000000000,2,0,0,1700000000000");
        const auto planned = commit_safety_startup_once(context(), snapshot(), 6, &grant);
        CHECK(planned.safety_ready() && planned.next.rapid_restarts == 2 &&
            !planned.next.disabled);
        g_startup_owner = 0;
        ++pid;
        const auto abnormal = commit_safety_startup_once(context(), snapshot(), 6);
        CHECK(abnormal.blockers == kStartupFuseRejected && abnormal.published &&
            abnormal.committed && abnormal.next.rapid_restarts == 3 &&
            abnormal.next.disabled);
    } else return __LINE__;
    CHECK(violations == 0 && live_fds == 0 && !locked);
    return 0;
}
