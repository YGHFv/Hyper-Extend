/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
// 复用内存文件系统，执行真实自动初始化，不访问设备。
#define LAUNCHER_SAFETY_MAINTENANCE_TRIAL
#include "launcher_safety_startup_trial.cpp"

extern "C" __attribute__((visibility("default")))
int launcher_first_start_trial(int code) {
    using namespace launcher;
    using namespace trial;
    automatic_start_test = true;
    app_group_files = true;
    state_exists = lock_exists = false;
    if (code >= 18 && code <= 27) {
        bool expected_absent = code == 18;
        if (code == 19) state_exists = true;
        if (code == 20) lock_exists = true;
        if (code == 21) pending_exists = true;
        if (code == 22) initial_probe_error = EACCES;
        if (code == 23) scenario = 12;
        if (code == 24) scenario = 67;
        if (code == 25) scenario = 68;
        if (code == 26) scenario = 74;
        if (code == 27) scenario = 58;
        CHECK(launcher_safety_paths_absent(uid) == expected_absent);
        CHECK(violations == 0 && live_fds == 0 && !locked);
        CHECK(io_calls > 0);
        return 0;
    }
    uint32_t expected = 0;
    switch (code) {
        case 0: break;
        case 1: state_exists = true; expected = kStartupStateUnreadable; break;
        case 2: pending_exists = true; expected = kStartupTemporaryRejected; break;
        case 3: initial_probe_error = EACCES; expected = kStartupStateUnreadable; break;
        case 4: initial_lock_race = true; expected = kStartupLockRejected; break;
        case 5: initial_state_race = true; expected = kStartupPublishFailed; break;
        case 6: scenario = 23; expected = kStartupWriteFailed; break;
        case 7: scenario = 26; expected = kStartupFileSyncFailed; break;
        case 8: scenario = 31; expected = kStartupPublishFailed; break;
        case 9: scenario = 32; expected = kStartupDirectorySyncFailed; break;
        case 10: scenario = 33; expected = kStartupReadbackFailed; break;
        case 11: scenario = 44; expected = kStartupFuseRejected; break;
        case 12: lock_exists = true; expected = kStartupStateUnreadable; break;
        case 13: scenario = 3; expected = kStartupSnapshotRejected; break;
        case 14: uid = euid = 0; expected = kStartupCallerRejected; break;
        case 15: lock_exists = state_exists = true; set_state("1700000000000,3,1,0,1700000000000");
            expected = kStartupFuseRejected; break;
        case 16: break;
        case 17: scenario = 38; expected = kStartupCloseFailed; break;
        default: return __LINE__;
    }
    auto config = snapshot();
    if (code == 16) { config.features = 0; expected = kStartupNoFeatures; }
    const auto result = commit_safety_startup_once(context(), config, 6);
    CHECK(result.blockers == expected && violations == 0 && live_fds == 0 && !locked);
    CHECK(result.safety_ready() == (code == 0));
    if (code == 0) {
        CHECK(lock_creates == 1 && result.published && result.committed);
        CHECK(text_equal(state, "1700000001000,0,0,0,1700000001000"));
        const int before = io_calls;
        CHECK(commit_safety_startup_once(context(), config, 6).blockers == kStartupAlreadyAttempted);
        CHECK(io_calls == before);
        g_startup_owner = 0; ++pid;
        const auto next = commit_safety_startup_once(context(), config, 6);
        CHECK(next.safety_ready() && lock_creates == 1 && next.next.rapid_restarts == 1);
    } else if (code < 5 || code == 11 || code >= 12) {
        if (code != 17) CHECK(lock_creates == 0 && writes == 0 && renames == 0);
    }
    if (code == 9 || code == 10 || code == 17) CHECK(result.published && !result.committed);
    else if (code != 0) CHECK(!result.published && !result.committed);
    return 0;
}
