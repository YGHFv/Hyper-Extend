/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#define LAUNCHER_RESTART_ENTRY_TRIAL
#include "launcher_diagnostic_entry_trial.cpp"

// 该试验只验证主动重启事务入口；宿主 ELF 扫描和真实纳秒睡眠由其它合同单独覆盖。
// 通用替身已放在被包含的诊断入口试验中，避免两个包含路径重复定义。

extern "C" __attribute__((visibility("default")))
int launcher_restart_entry_trial(int code) {
    using namespace launcher;
    using namespace trial;
    native_init(&entries);
    words[8] = static_cast<int32_t>(kPlannedRestartTokenTag + 7);
    if (code == 1) ++maintenance_words[7];
    if (code == 2) ++maintenance_words[3];
    if (code == 3) ++maintenance_words[4];
    if (code == 4) maintenance_words[2] = 3;
    if (code == 5) maintenance_words[1] = 2;
    if (code == 6) maintenance_words[6] = 999;
    if (code == 7) maintenance_words[6] = 1031;
    if (code == 8) words[8] = 0;
    if (code == 9) maintenance_words[7] = words[8] = 7;
    if (code == 10) { maintenance_words[2] = 2; maintenance_words[7] = words[8] = 77; }
    if (code == 11) maintenance_words[0] = 0;
    if (code == 12) maintenance_words[5] = -1;
    Snapshot snapshot{};
    uint32_t errors = 0;
    const auto status = query_configuration_once(snapshot, errors);
    const bool accepted = code == 0 || code == 8 || code == 10 || code == 13;
    CHECK(status == 0 && errors == 0 && violations == 0 && live_fds == 0);
    CHECK(restart_commit_calls == (accepted ? 1U : 0U));
    CHECK(transactions == (code == 8 ? 1 : 2));
    if (accepted) {
        CHECK(snapshot.safety_reset_token == 0);
        CHECK(restart_grant_present == (code != 8));
        if (restart_grant_present) {
            CHECK(captured_restart.pid == pid && captured_restart.uid == uid);
            CHECK(captured_restart.token == words[8] && captured_restart.recover == (code == 10));
        }
    } else CHECK(snapshot.safety_reset_token == words[8]);
    if (code == 13) {
        CHECK(query_configuration_once(snapshot, errors) == kChannelSnapshotRejected);
        CHECK(restart_commit_calls == 1 && transactions == 3);
    }
    return 0;
}
