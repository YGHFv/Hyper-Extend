/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
// 复用原生启动试验的内存文件系统，不运行真实设备或本机状态文件。
#define LAUNCHER_SAFETY_MAINTENANCE_TRIAL
#include "launcher_safety_startup_trial.cpp"

namespace maintenance_trial {
using namespace launcher;
using namespace trial;
int test_case = 0, approvals = 0;
SafetyMaintenanceResult reentered{};
SafetyStartupResult startup_reentered{};

void set_pending(const char *text) {
    pending_size = length(text);
    copy(pending, text, pending_size + 1);
    pending_exists = true;
    pending_inode = 202;
}

bool authorize(void *, const DiagnosticContext &caller, SafetyMaintenanceOperation operation,
    const SafetyMaintenanceObservation &seen, SafetyMaintenanceGrant &grant) {
    ++approvals;
    grant = {caller.pid, caller.uid, operation, 1000, 1030,
        operation == SafetyMaintenanceOperation::kResumePending ? 0 :
        operation == SafetyMaintenanceOperation::kResumeArming ? seen.pending.last_reset_token :
        operation == SafetyMaintenanceOperation::kArmAbsent ? 1 : 6};
    switch (test_case) {
        case 6: return false;
        case 7: ++grant.pid; break;
        case 8: ++grant.uid; break;
        case 9: grant.operation = SafetyMaintenanceOperation::kResumePending; break;
        case 10: grant.expires_s = 1000; break;
        case 11: grant.issued_s = 1001; break;
        case 12: grant.expires_s = 1031; break;
        case 13: grant.reset_token = 0; break;
        case 14: grant.reset_token = -1; break;
        case 15: grant.reset_token = static_cast<int64_t>(INT32_MAX) + 1; break;
        case 16: grant.reset_token = 5; break;
        case 17: grant.reset_token = 4; break;
        case 18: grant.reset_token = INT32_MAX; break;
        case 53: ++state_inode; break;
        case 54: set_pending("1700000001000,1,0,0,1700000000000"); break;
        case 55:
            reentered = maintain_safety_once(caller, operation, authorize, nullptr);
            startup_reentered = commit_safety_startup_once(caller, snapshot(), 6);
            break;
        case 56: ++pid; break;
        case 60: scenario = 40; break;
        case 68: ++grant.reset_token; break;
        case 73: grant.reset_token = 0; break;
        default: break;
    }
    return true;
}

uint32_t errors(int code) {
    if ((code >= 5 && code <= 18) || code == 49 || code == 50 || code == 60 || code == 68 || code == 73) {
        return kMaintenanceAuthorizationRejected;
    }
    switch (code) {
        case 19: return kMaintenanceOperationRejected;
        case 20: case 56: case 57: return kStartupCallerRejected;
        case 21: case 22: case 24: case 25: case 29: case 30: case 31: case 62: case 63:
        case 69: case 71:
            return kMaintenanceResidualRejected;
        case 23: case 26: case 27: case 28: case 64: case 70: return kStartupStateUnreadable;
        case 32: case 33: case 34: case 35: case 36: case 72: return kStartupLockRejected;
        case 37: case 41: case 58: return kStartupFileSyncFailed;
        case 38: case 44: return kStartupDirectorySyncFailed;
        case 39: case 65: return kStartupWriteFailed;
        case 42: case 47: case 48: return kStartupCloseFailed;
        case 43: case 53: case 54: return kStartupPublishFailed;
        case 45: case 46: return kStartupReadbackFailed;
        case 51: case 52: case 59: return kStartupFuseRejected;
        case 61: return kStartupStateUnreadable | kStartupCloseFailed;
        default: return 0;
    }
}
}  // 命名空间 maintenance_trial

extern "C" __attribute__((visibility("default")))
int launcher_safety_maintenance_trial(int code) {
    using namespace launcher;
    using namespace trial;
    using namespace maintenance_trial;
    test_case = code;
    maintenance_mode = true;
    auto operation = SafetyMaintenanceOperation::kRearmKnown;
    set_state("1700000000000,3,1,5,1700000000000");
    if (code == 0 || code == 1 || (code >= 6 && code <= 15) || code == 22 ||
        code == 60 || code == 63 || code == 65) {
        operation = SafetyMaintenanceOperation::kArmAbsent;
        state_exists = lock_exists = false;
    }
    if (code == 1) lock_exists = true;
    if (code == 3 || code == 4 || (code >= 25 && code <= 31) || code == 58 ||
        code == 59 || code == 62 || code == 64) {
        operation = SafetyMaintenanceOperation::kResumePending;
        set_state("1700000000000,0,0,0,1700000000000");
        set_pending("1700000001000,1,0,0,1700000000000");
    }
    if (code == 4) {
        set_state("1700000000000,2,0,0,1700000000000");
        set_pending("1700000001000,3,1,0,1700000000000");
    }
    if (code == 18) set_state("1700000000000,3,1,2147483647,1700000000000");
    if (code == 19) operation = static_cast<SafetyMaintenanceOperation>(0);
    if (code == 20) uid = euid = 0;
    if (code == 21 || code == 62) state_exists = false;
    if (code == 22) state_exists = lock_exists = true;
    if (code == 23) set_state("损坏状态");
    if (code == 24 || code == 63) set_pending("1700000001000,1,0,0,1700000000000");
    if (code == 25) pending_exists = false;
    if (code == 26) set_pending("损坏临时文件");
    if (code == 27) set_pending("1700000001000,1");
    if (code == 28) set_pending("01700000001000,1,0,0,1700000000000");
    if (code == 29) set_pending("1700000001000,0,0,1,1700000001000");
    if (code == 30) set_pending("1700000001000,0,0,0,1700000000000");
    if (code == 31) set_state("1700000000000,3,1,0,1700000000000");
    if (code == 32) lock_exists = false;
    if (code == 59) set_pending("1700000002000,1,0,0,1700000000000");
    if (code == 64) set_pending("");
    if (code >= 66) {
        operation = SafetyMaintenanceOperation::kResumeArming;
        set_pending("0,0,0,6,0");
    }
    if (code == 66) { state_exists = false; set_pending("0,0,0,1,0"); }
    if (code == 69) set_pending("0,0,0,5,0");
    if (code == 70) set_pending("0,0,0,6");
    if (code == 71) set_pending("0,1,0,6,0");
    if (code == 72) lock_exists = false;
    const int faults[][2] = {
        {33,13}, {34,14}, {35,15}, {36,65}, {37,17}, {38,18}, {39,23}, {40,25},
        {41,26}, {42,27}, {43,31}, {44,32}, {45,34}, {46,35}, {47,38}, {48,39},
        {49,42}, {50,76}, {51,44}, {52,46}, {57,78}, {58,26}, {61,69}, {65,24},
    };
    for (const auto &fault : faults) if (code == fault[0]) scenario = fault[1];
    char original[256]{};
    copy(original, state, state_size + 1);
    const uint32_t initial_pid = pid;
    const auto result = maintain_safety_once(context(), operation, code == 5 ? nullptr : authorize, nullptr);
    CHECK(violations == 0 && live_fds == 0 && !locked);
    CHECK(result.blockers == errors(code));
    const bool published = errors(code) == 0 || (code >= 44 && code <= 48) ||
        code == 50 || code == 52 || code == 57;
    const bool committed = published && !(code >= 44 && code <= 48);
    CHECK(result.published == published && result.committed == committed);
    CHECK(result.maintenance_complete() == (errors(code) == 0));
    CHECK(install_permitted({kIdentityLauncherChild, true,
        result.maintenance_complete(), true}) == result.maintenance_complete());
    if (published) {
        CHECK(renames == 1 && state_exists && !pending_exists);
        CHECK(sync_event < pending_close_event && pending_close_event < rename_event);
        if (committed) CHECK(rename_event < directory_sync_event && directory_sync_event < readback_event);
        if (operation == SafetyMaintenanceOperation::kResumePending ||
            operation == SafetyMaintenanceOperation::kResumeArming) {
            CHECK(writes == 0 && state_inode == 202);
            if (code == 4) CHECK(result.next.disabled && result.next.rapid_restarts == 3);
        } else {
            CHECK(writes > 0 && result.next.last_start_ms == 0 && result.next.rapid_restarts == 0);
            CHECK(text_equal(state, operation == SafetyMaintenanceOperation::kArmAbsent ?
                "0,0,0,1,0" : "0,0,0,6,0"));
        }
    } else CHECK(renames == 0 && text_equal(state, original));
    if (code >= 6 && code <= 15) CHECK(lock_creates == 0 && writes == 0);
    if (code == 0 || code == 65) CHECK(lock_creates == 1 && lock_exists);
    if (code == 65) CHECK(pending_exists && !state_exists);
    if (code == 5 || code == 19 || code == 20) CHECK(io_calls == 0);
    else CHECK(g_startup_owner == initial_pid);
    if (code == 55) {
        CHECK(approvals == 1 && reentered.blockers == kStartupAlreadyAttempted);
        CHECK(startup_reentered.blockers == kStartupAlreadyAttempted);
    }
    if (g_startup_owner != 0) {
        scenario = 0;
        const auto expected = g_startup_owner == pid ? kStartupAlreadyAttempted : kStartupInheritedAttempt;
        const int before = io_calls;
        CHECK(maintain_safety_once(context(), operation, authorize, nullptr).blockers == expected);
        CHECK(commit_safety_startup_once(context(), snapshot(), 6).blockers == expected);
        CHECK(io_calls == before);
    }
    if (code == 0 || code == 66) {
        // 仅内存试验模拟独立新进程；生产代码没有清除一次机会的接口。
        for (uint32_t count = 0; count <= 3; ++count) {
            g_startup_owner = 0;
            ++pid;
            const auto started = commit_safety_startup_once(context(), snapshot(), 6);
            CHECK(started.committed && started.next.rapid_restarts == count);
            CHECK(started.next.last_reset_token == 1 && started.next.disabled == (count == 3));
            CHECK(started.safety_ready() == (count < 3));
        }
    }
    CHECK(violations == 0 && live_fds == 0);
    return 0;
}
