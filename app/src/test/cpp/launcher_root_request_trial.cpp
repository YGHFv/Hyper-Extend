/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#define LAUNCHER_SAFETY_MAINTENANCE_TRIAL
#include "launcher_safety_startup_trial.cpp"

extern "C" __attribute__((visibility("default")))
int launcher_root_request_trial(int code) {
    using namespace launcher;
    using namespace trial;
    root_request_mode = true;
    scenario = 100 + code;
    if (code == 6) root_request_exists = false;
    if (code == 13) root_request_text = "1,10001,1000,2,51";
    if (code == 14) root_request_text = "1,10002,1000,1,1073741831";
    if (code == 15) root_request_text = "1,10001,970,1,1073741831";
    if (code == 16) root_request_text = "1,10001,1001,1,1073741831";
    if (code == 17) root_request_text = "1,10001,1000,3,1073741831";
    if (code == 18) root_request_text = "1,10001,1000,1,7";
    if (code == 19) root_request_text = "1,10001,1000,1,1073741831,0";
    if (code == 20) root_request_text = "1,10001,9223372036854775807,1,1073741831";
    StartupRestartGrant grant{};
    const int result = consume_root_restart_request(context(), grant);
    CHECK(violations == 0 && live_fds == 0 && writes == 0 && state_opens == 0);
    CHECK(text_equal(state, "1700000000000,0,0,0,1700000000000"));
    if (code == 0 || code == 13) {
        CHECK(result == 1 && renames == 1 && !root_request_exists);
        CHECK(grant.recover == (code == 13) && grant.pid == pid && grant.uid == uid);
        CHECK(consume_root_restart_request(context(), grant) == 0 && renames == 1);
    } else if (code == 6) CHECK(result == 0 && renames == 0);
    else CHECK(result == -1);
    return 0;
}
