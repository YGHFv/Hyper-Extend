/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#include "safety_startup.h"
#include "safety_maintenance.h"
#include "entry_trace.h"
#include "directory_policy.h"
#include "root_restart_request.h"

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

namespace launcher {
namespace {

static_assert(__atomic_always_lock_free(sizeof(uint32_t), nullptr), "启动记录需要无锁原子");
uint32_t g_startup_owner = 0;
constexpr char kStateName[] = "hyperextend_launcher_bootguard";
constexpr char kLockName[] = "hyperextend_launcher_bootguard.lock";
constexpr char kPendingName[] = "hyperextend_launcher_bootguard.pending";
constexpr size_t kStateCapacity = 128;

bool caller_current(const DiagnosticContext &context) {
    return diagnostic_context_ready(context) && context.pid == static_cast<uint32_t>(getpid()) &&
        context.uid == getuid() && context.euid == geteuid();
}

int64_t clock_value(clockid_t clock, bool milliseconds) {
    timespec now{};
    if (clock_gettime(clock, &now) != 0 || now.tv_sec < 0 || now.tv_nsec < 0 ||
        now.tv_nsec >= 1000000000) return -1;
    if (!milliseconds) return static_cast<int64_t>(now.tv_sec);
    if (now.tv_sec > (INT64_MAX - 999) / 1000) return -1;
    return static_cast<int64_t>(now.tv_sec) * 1000 + now.tv_nsec / 1000000;
}

bool close_checked(int &fd) {
    if (fd < 0) return true;
    const int saved = fd;
    fd = -1;
    // close 失败也不重试，避免关闭已被别的线程复用的描述符。
    return close(saved) == 0;
}

bool private_directory(int fd, uint32_t uid, const char *stage,
                       DirectoryRole role = DirectoryRole::kPackage) {
    const uint32_t rejected = private_directory_blockers(fd, uid, role);
    if (rejected != 0) experimental_entry_trace(stage, rejected);
    return rejected == 0;
}

bool private_file(const struct stat &info, uint32_t uid) {
    return S_ISREG(info.st_mode) && info.st_uid == uid && info.st_nlink == 1 &&
        (info.st_mode & 07777) == 0600;
}

bool same_inode(const struct stat &left, const struct stat &right) {
    return left.st_dev == right.st_dev && left.st_ino == right.st_ino;
}

bool path_matches(int directory, const char *name, uint32_t uid, const struct stat &expected) {
    struct stat actual{};
    return fstatat(directory, name, &actual, AT_SYMLINK_NOFOLLOW) == 0 &&
        private_file(actual, uid) && same_inode(actual, expected) &&
        actual.st_size == expected.st_size;
}

int open_files(uint32_t uid, uint32_t &errors) {
    char path[32] = "/data/user/";
    char digits[10]{};
    uint32_t user = uid / 100000U;
    size_t size = 0, cursor = sizeof("/data/user/") - 1;
    do {
        digits[size++] = static_cast<char>('0' + user % 10U);
        user /= 10U;
    } while (user != 0);
    while (size != 0) path[cursor++] = digits[--size];
    int user_fd = open(path, O_PATH | O_DIRECTORY | O_CLOEXEC);
    if (user_fd < 0) {
        experimental_entry_trace("directory_user_open_errno", static_cast<uint32_t>(errno));
        errors |= kStartupDirectoryRejected;
        return -1;
    }
    int package_fd = openat(user_fd, kLauncherSafetyScope,
        O_PATH | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    if (package_fd < 0)
        experimental_entry_trace("directory_package_open_errno", static_cast<uint32_t>(errno));
    if (!close_checked(user_fd)) errors |= kStartupCloseFailed;
    if (package_fd < 0 || !private_directory(package_fd, uid, "directory_package_check"))
        errors |= kStartupDirectoryRejected;
    int files_fd = -1;
    if (errors == 0) {
        files_fd = openat(package_fd, "files", O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
        if (files_fd < 0)
            experimental_entry_trace("directory_files_open_errno", static_cast<uint32_t>(errno));
        if (files_fd < 0 || !private_directory(
                files_fd, uid, "directory_files_check", DirectoryRole::kFiles))
            errors |= kStartupDirectoryRejected;
    }
    if (!close_checked(package_fd)) errors |= kStartupCloseFailed;
    return files_fd;
}

size_t encode_state(const SafetyFuseState &state, char (&out)[kStateCapacity]);

bool read_record(int directory, const char *name, uint32_t uid, SafetyFuseState &state,
    struct stat &info, uint32_t &errors, bool canonical) {
    int fd = openat(directory, name, O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
    if (fd < 0) return false;
    char text[kStateCapacity]{};
    bool ok = fstat(fd, &info) == 0 && private_file(info, uid) &&
        info.st_size > 0 && info.st_size < static_cast<off_t>(sizeof(text));
    size_t size = 0;
    while (ok && size < static_cast<size_t>(info.st_size)) {
        const ssize_t got = read(fd, text + size, static_cast<size_t>(info.st_size) - size);
        if (got <= 0) ok = false;
        else size += static_cast<size_t>(got);
    }
    char extra = 0;
    if (ok) ok = read(fd, &extra, 1) == 0;
    if (!close_checked(fd)) { errors |= kStartupCloseFailed; ok = false; }
    if (!ok || !path_matches(directory, name, uid, info) ||
        !decode_safety_fuse(text, static_cast<uint32_t>(size), state)) return false;
    if (canonical) {
        char encoded[kStateCapacity]{};
        if (encode_state(state, encoded) != size) return false;
        for (size_t index = 0; index < size; ++index) {
            if (encoded[index] != text[index]) return false;
        }
    }
    return true;
}

bool read_state(int directory, uint32_t uid, SafetyFuseState &state, struct stat &info,
    uint32_t &errors) {
    return read_record(directory, kStateName, uid, state, info, errors, false);
}

bool equal_state(const SafetyFuseState &left, const SafetyFuseState &right) {
    return left.last_start_ms == right.last_start_ms && left.rapid_restarts == right.rapid_restarts &&
        left.disabled == right.disabled && left.last_reset_token == right.last_reset_token &&
        left.window_start_ms == right.window_start_ms;
}

size_t encode_state(const SafetyFuseState &state, char (&out)[kStateCapacity]) {
    const int64_t fields[] = {state.last_start_ms, state.rapid_restarts, state.disabled ? 1 : 0,
        state.last_reset_token, state.window_start_ms};
    size_t size = 0;
    for (size_t index = 0; index < 5; ++index) {
        if (fields[index] < 0) return 0;
        if (index != 0) out[size++] = ',';
        uint64_t value = static_cast<uint64_t>(fields[index]);
        char digits[20]{};
        size_t count = 0;
        do {
            digits[count++] = static_cast<char>('0' + value % 10U);
            value /= 10U;
        } while (value != 0);
        if (size + count >= sizeof(out)) return 0;
        while (count > 0) out[size++] = digits[--count];
    }
    SafetyFuseState decoded{};
    return decode_safety_fuse(out, static_cast<uint32_t>(size), decoded) &&
        equal_state(decoded, state) ? size : 0;
}

uint32_t request_errors(const DiagnosticContext &context, const Snapshot &snapshot, uint32_t revision) {
    return (caller_current(context) ? 0U : kStartupCallerRejected) |
        (ordinary_snapshot_errors(snapshot, revision, clock_value(CLOCK_BOOTTIME, false)) == 0
            ? 0U : kStartupSnapshotRejected);
}

bool absent_path(int directory, const char *name) {
    struct stat info{};
    return fstatat(directory, name, &info, AT_SYMLINK_NOFOLLOW) != 0 && errno == ENOENT;
}

// 首次启动只接受全新存储；已有锁而状态丢失、损坏状态和 pending 均不是首次初始化。
uint32_t first_start_errors(int directory, const DiagnosticContext &context,
                            const Snapshot &snapshot, uint32_t revision) {
    const uint32_t errors = request_errors(context, snapshot, revision);
    if (errors != 0) return errors;
    if (!absent_path(directory, kStateName)) return kStartupStateUnreadable;
    if (!absent_path(directory, kPendingName)) return kStartupTemporaryRejected;
    if (clock_value(CLOCK_REALTIME, true) < kSafetyMinTimeMs) return kStartupFuseRejected;
    return 0;
}

// 锁覆盖旧状态读取、判定、替换和读回；锁文件保持固定 inode，不随状态 rename。
uint32_t persist_locked(int directory, const DiagnosticContext &context,
    const Snapshot &snapshot, uint32_t revision, const struct stat &lock_info,
    SafetyStartupResult &result, uint32_t &cleanup_errors, bool first_start = false,
    const StartupRestartGrant *restart = nullptr) {
    if (restart != nullptr && !restart_grant_valid(*restart, context,
            clock_value(CLOCK_BOOTTIME, false))) return kStartupCallerRejected;
    SafetyFuseState previous{};
    struct stat old_info{};
    if (first_start) {
        const uint32_t errors = first_start_errors(directory, context, snapshot, revision);
        if (errors != 0) return errors;
        const int64_t now = clock_value(CLOCK_REALTIME, true);
        if (now < kSafetyMinTimeMs) return kStartupFuseRejected;
        result.next = {now, 0, false, 0, now};
    } else {
        if (!read_state(directory, context.uid, previous, old_info, cleanup_errors)) {
            return kStartupStateUnreadable;
        }
        const bool recover = restart != nullptr && restart->recover;
        if (recover && restart->token <= previous.last_reset_token) return kStartupFuseRejected;
        // 主动重启是一条独立路径：它既不是异常启动，也不是人工恢复。
        // 因此只记录本次启动时间并保留熔断/计数，不能把一次计划重启计入
        // 五分钟异常窗口，也不能借机清除已有锁存。
        const bool planned = restart != nullptr && !recover;
        const int64_t now = clock_value(CLOCK_REALTIME, true);
        // 主动重启也必须拒绝墙上时钟倒退；否则旧记录中的启动时间会被
        // 一个更早的“新启动”覆盖，后续异常窗口与计数就失去可信基准。
        if (planned && (now < kSafetyMinTimeMs || now < previous.last_start_ms)) {
            return kStartupFuseRejected;
        }
        // 计划重启在安全模式锁存后仍然允许执行：快照会把所有用户功能
        // 投影为关闭，原生熔断状态保持不变，重启本身不会扩大 Hook 风险。
        // 只有 recover 事务才能清除 disabled 和递增 reset token。
        const auto decision = planned
            ? SafetyFuseDecision{true, SafetyFuseState{now, previous.rapid_restarts,
                previous.disabled, previous.last_reset_token, previous.window_start_ms}, 0}
            : safety_fuse_next(previous, true, now,
                recover ? restart->token : snapshot.safety_reset_token, restart == nullptr);
        if (!decision.ok) return kStartupFuseRejected;
        result.next = decision.next;
    }
    uint32_t errors = request_errors(context, snapshot, revision);
    if (errors != 0) return errors;
    char text[kStateCapacity]{};
    const size_t size = encode_state(result.next, text);
    if (size == 0) return kStartupStateUnreadable;
    int pending_fd = openat(directory, kPendingName,
        O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK, 0600);
    if (pending_fd < 0) return kStartupTemporaryRejected;
    struct stat pending_info{};
    if (fstat(pending_fd, &pending_info) != 0 || !private_file(pending_info, context.uid) ||
        pending_info.st_size != 0) errors |= kStartupTemporaryRejected;
    size_t written = 0;
    while (errors == 0 && written < size) {
        const ssize_t count = write(pending_fd, text + written, size - written);
        if (count <= 0) errors |= kStartupWriteFailed;
        else written += static_cast<size_t>(count);
    }
    if (errors == 0 && fsync(pending_fd) != 0) errors |= kStartupFileSyncFailed;
    if (!close_checked(pending_fd)) cleanup_errors |= kStartupCloseFailed;
    if (errors != 0 || cleanup_errors != 0) return errors;
    pending_info.st_size = static_cast<off_t>(size);
    errors = request_errors(context, snapshot, revision);
    if (errors != 0) return errors;
    // 同 UID 非协作写入不在认证范围；可观察到的锁/状态/临时文件替换一律拒绝。
    if (!path_matches(directory, kLockName, context.uid, lock_info) ||
        (first_start ? !absent_path(directory, kStateName)
                     : !path_matches(directory, kStateName, context.uid, old_info)) ||
        !path_matches(directory, kPendingName, context.uid, pending_info)) return kStartupPublishFailed;
    if (restart != nullptr && !restart_grant_valid(*restart, context,
            clock_value(CLOCK_BOOTTIME, false))) return kStartupCallerRejected;
    if (renameat(directory, kPendingName, directory, kStateName) != 0) return kStartupPublishFailed;
    result.published = true;
    if (fsync(directory) != 0) return kStartupDirectorySyncFailed;
    SafetyFuseState readback{};
    struct stat readback_info{};
    if (!read_state(directory, context.uid, readback, readback_info, cleanup_errors) ||
        !same_inode(readback_info, pending_info) || !equal_state(readback, result.next)) {
        return kStartupReadbackFailed;
    }
    result.committed = true;
    // 新熔断必须先落盘再拒绝；不能因 next.disabled 就提前跳过提交。
    return result.next.disabled ? kStartupFuseRejected : 0U;
}

}  // 匿名命名空间

int consume_root_restart_request(const DiagnosticContext &context, StartupRestartGrant &out) {
    if (!caller_current(context)) return -1;
    uint32_t errors = 0;
    int directory = open_files(context.uid, errors);
    if (directory < 0 || errors != 0) {
        close_checked(directory);
        return 0; // 普通启动后端仍会独立拒绝不可读目录，不能由此取得许可。
    }
    int fd = openat(directory, kRootRestartRequest, O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
    if (fd < 0) {
        const bool missing = errno == ENOENT;
        return close_checked(directory) && missing ? 0 : -1;
    }
    struct stat info{}, current{};
    char text[129]{};
    size_t size = 0;
    bool ok = fstat(fd, &info) == 0 && root_restart_file(info);
    while (ok && size < sizeof(text)) {
        const ssize_t count = read(fd, text + size, sizeof(text) - size);
        if (count < 0) { ok = false; break; }
        if (count == 0) break;
        size += static_cast<size_t>(count);
    }
    ok = close_checked(fd) && ok && size == static_cast<size_t>(info.st_size) &&
        parse_root_restart(text, size, context, clock_value(CLOCK_BOOTTIME, false), out);
    if (ok) ok = fstatat(directory, kRootRestartRequest, &current, AT_SYMLINK_NOFOLLOW) == 0 &&
        root_restart_file(current) && same_inode(info, current) && info.st_size == current.st_size;
    if (ok) ok = renameat(directory, kRootRestartRequest, directory, kRootRestartConsumed) == 0 &&
        fsync(directory) == 0;
    ok = close_checked(directory) && ok && caller_current(context) &&
        restart_grant_valid(out, context, clock_value(CLOCK_BOOTTIME, false));
    return ok ? 1 : -1;
}

bool launcher_safety_paths_absent(uint32_t uid) {
    uint32_t errors = 0;
    int directory = open_files(uid, errors);
    if (directory < 0 || errors != 0) {
        close_checked(directory);
        return false;
    }
    const bool absent = absent_path(directory, kStateName) &&
        absent_path(directory, kLockName) && absent_path(directory, kPendingName);
    int fd = directory;
    return close_checked(fd) && absent;
}

SafetyStartupResult commit_safety_startup_once(const DiagnosticContext &context,
    const Snapshot &snapshot, uint32_t last_revision, const StartupRestartGrant *restart) {
    SafetyStartupResult result{};
    result.blockers = request_errors(context, snapshot, last_revision);
    if (restart != nullptr && !restart_grant_valid(*restart, context,
            clock_value(CLOCK_BOOTTIME, false))) result.blockers |= kStartupCallerRejected;
    // 计划重启（包括安全模式已投影为关闭时的普通桌面重启）仍需提交启动记录，
    // 否则这次受控重启会被误判为“无功能启动”而累计熔断。只有完全没有计划凭证
    // 的普通启动才要求至少存在一个功能位；恢复事务同样走这里的受控路径。
    if (!snapshot_has_features(snapshot) && restart == nullptr)
        result.blockers |= kStartupNoFeatures;
    if (result.blockers != 0) return result;
    uint32_t owner = 0;
    if (!__atomic_compare_exchange_n(&g_startup_owner, &owner, context.pid, false,
            __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
        result.blockers = owner == context.pid ? kStartupAlreadyAttempted : kStartupInheritedAttempt;
        return result;
    }
    uint32_t cleanup_errors = 0;
    int files_fd = open_files(context.uid, result.blockers);
    int lock_fd = -1;
    bool first_start = false;
    struct stat lock_info{};
    if (result.blockers == 0) {
        lock_fd = openat(files_fd, kLockName, O_RDWR | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
        const int lock_error = lock_fd < 0 ? errno : 0;
        if (lock_fd < 0 && lock_error == ENOENT) {
            result.blockers |= restart != nullptr && restart->recover ? kStartupStateUnreadable :
                first_start_errors(files_fd, context, snapshot, last_revision);
            if (result.blockers == 0) {
                lock_fd = openat(files_fd, kLockName,
                    O_RDWR | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK, 0600);
                first_start = lock_fd >= 0;
            }
        }
        if (result.blockers == 0 && (lock_fd < 0 || fstat(lock_fd, &lock_info) != 0 ||
            !private_file(lock_info, context.uid) || lock_info.st_size != 0 ||
            flock(lock_fd, LOCK_EX | LOCK_NB) != 0 ||
            !path_matches(files_fd, kLockName, context.uid, lock_info))) {
            result.blockers |= kStartupLockRejected;
        }
    }
    if (result.blockers == 0) {
        // 即使锁刚由受控武装方建立，也先同步其 inode 和目录项。
        if (fsync(lock_fd) != 0) result.blockers |= kStartupFileSyncFailed;
        else if (fsync(files_fd) != 0) result.blockers |= kStartupDirectorySyncFailed;
    }
    if (result.blockers == 0) {
        result.blockers |= persist_locked(files_fd, context, snapshot, last_revision, lock_info,
            result, cleanup_errors, first_start, restart);
    }
    // 不显式 LOCK_UN：fork 子进程若在中途返回，只关闭自己的引用，不解除父进程的锁。
    if (!close_checked(lock_fd)) cleanup_errors |= kStartupCloseFailed;
    if (!close_checked(files_fd)) cleanup_errors |= kStartupCloseFailed;
    if (cleanup_errors != 0) result.committed = false;
    result.blockers |= cleanup_errors;
    // close 也可能耗时；所有 I/O 完成后再取时钟，不能用清理前的租期签发结果。
    result.blockers |= request_errors(context, snapshot, last_revision);
    if (restart != nullptr && !restart_grant_valid(*restart, context,
            clock_value(CLOCK_BOOTTIME, false))) result.blockers |= kStartupCallerRejected;
    if (result.committed) {
        const auto final_clock = safety_fuse_next(result.next, true, clock_value(CLOCK_REALTIME, true),
            snapshot.safety_reset_token, false);
        if (!final_clock.ok) result.blockers |= kStartupFuseRejected;
    }
    return result;
}

namespace {

bool maintenance_observe(int directory, uint32_t uid, SafetyMaintenanceObservation &observation,
    struct stat &state_info, struct stat &pending_info, uint32_t &errors) {
    observation.have_state = !absent_path(directory, kStateName);
    observation.have_pending = !absent_path(directory, kPendingName);
    if (observation.have_state &&
        !read_state(directory, uid, observation.state, state_info, errors)) return false;
    // 只能续完完整的五列写入；两列前缀即便可被旧版解码器解析，也不是完整临时记录。
    return !observation.have_pending || read_record(directory, kPendingName, uid,
        observation.pending, pending_info, errors, true);
}

bool maintenance_shape(SafetyMaintenanceOperation operation, const SafetyMaintenanceObservation &seen) {
    switch (operation) {
        case SafetyMaintenanceOperation::kArmAbsent:
            return !seen.have_state && !seen.have_pending;
        case SafetyMaintenanceOperation::kRearmKnown:
            return seen.have_state && !seen.have_pending;
        case SafetyMaintenanceOperation::kResumePending: {
            if (!seen.have_state || !seen.have_pending) return false;
            const auto step = safety_fuse_next(seen.state, true, seen.pending.last_start_ms, 0, true);
            return step.ok && equal_state(step.next, seen.pending);
        }
        case SafetyMaintenanceOperation::kResumeArming:
            return seen.have_pending && seen.pending.last_start_ms == 0 &&
                seen.pending.window_start_ms == 0 && seen.pending.rapid_restarts == 0 &&
                !seen.pending.disabled && seen.pending.last_reset_token > 0 &&
                seen.pending.last_reset_token <= INT32_MAX &&
                (!seen.have_state || seen.pending.last_reset_token > seen.state.last_reset_token);
    }
    return false;
}

uint32_t maintenance_grant_errors(const DiagnosticContext &context, SafetyMaintenanceOperation operation,
    const SafetyMaintenanceGrant &grant, const SafetyMaintenanceObservation &seen) {
    uint32_t errors = caller_current(context) ? 0U : kStartupCallerRejected;
    const auto now = clock_value(CLOCK_BOOTTIME, false);
    bool valid = grant.pid == context.pid && grant.uid == context.uid && grant.operation == operation &&
        now >= 0 && grant.issued_s >= 0 && grant.issued_s <= now && grant.expires_s > now &&
        grant.expires_s - grant.issued_s <= kSnapshotLeaseSeconds;
    if (operation == SafetyMaintenanceOperation::kResumePending) valid &= grant.reset_token == 0;
    else if (operation == SafetyMaintenanceOperation::kResumeArming) {
        valid &= grant.reset_token == seen.pending.last_reset_token;
    }
    else valid &= grant.reset_token > 0 && grant.reset_token <= INT32_MAX &&
        (!seen.have_state || grant.reset_token > seen.state.last_reset_token);
    return errors | (valid ? 0U : kMaintenanceAuthorizationRejected);
}

bool maintenance_paths_match(int directory, uint32_t uid, const struct stat &lock_info,
    const SafetyMaintenanceObservation &seen, const struct stat &state_info) {
    return path_matches(directory, kLockName, uid, lock_info) &&
        (seen.have_state ? path_matches(directory, kStateName, uid, state_info)
            : absent_path(directory, kStateName));
}

uint32_t maintenance_stage(int directory, uint32_t uid, const SafetyFuseState &next,
    bool resume, struct stat &pending_info, uint32_t &cleanup_errors) {
    const int flags = resume ? O_RDWR : O_WRONLY | O_CREAT | O_EXCL;
    int fd = openat(directory, kPendingName, flags | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK, 0600);
    if (fd < 0) return kStartupTemporaryRejected;
    struct stat actual{};
    uint32_t errors = 0;
    if (fstat(fd, &actual) != 0 || !private_file(actual, uid) ||
        (resume ? !same_inode(actual, pending_info) || actual.st_size != pending_info.st_size
            : actual.st_size != 0)) errors |= kStartupTemporaryRejected;
    if (!resume && errors == 0) {
        char encoded[kStateCapacity]{};
        const size_t size = encode_state(next, encoded);
        size_t done = 0;
        if (size == 0) errors |= kStartupWriteFailed;
        while (errors == 0 && done < size) {
            const auto count = write(fd, encoded + done, size - done);
            if (count <= 0) errors |= kStartupWriteFailed;
            else done += static_cast<size_t>(count);
        }
        actual.st_size = static_cast<off_t>(size);
    }
    if (errors == 0 && fsync(fd) != 0) errors |= kStartupFileSyncFailed;
    if (!close_checked(fd)) cleanup_errors |= kStartupCloseFailed;
    pending_info = actual;
    return errors;
}

uint32_t maintenance_locked(int directory, int &lock_fd, const DiagnosticContext &context,
    SafetyMaintenanceOperation operation, SafetyMaintenanceAuthorizer authorize, void *opaque,
    SafetyMaintenanceGrant &grant, SafetyMaintenanceObservation &seen,
    SafetyMaintenanceResult &result, uint32_t &cleanup_errors) {
    struct stat lock_info{}, state_info{}, pending_info{};
    const bool missing_lock = lock_fd < 0;
    if (missing_lock && operation != SafetyMaintenanceOperation::kArmAbsent) return kStartupLockRejected;
    if (!missing_lock && (fstat(lock_fd, &lock_info) != 0 || !private_file(lock_info, context.uid) ||
        lock_info.st_size != 0 || flock(lock_fd, LOCK_EX | LOCK_NB) != 0 ||
        !path_matches(directory, kLockName, context.uid, lock_info))) return kStartupLockRejected;
    if (!maintenance_observe(directory, context.uid, seen, state_info, pending_info, cleanup_errors)) {
        return kStartupStateUnreadable;
    }
    if (!maintenance_shape(operation, seen)) return kMaintenanceResidualRejected;
    if (!authorize(opaque, context, operation, seen, grant)) return kMaintenanceAuthorizationRejected;
    uint32_t errors = maintenance_grant_errors(context, operation, grant, seen);
    if (errors != 0 || cleanup_errors != 0) return errors;
    if (missing_lock) {
        // 仅专用初始化授权可创建一次稳定锁；不替换已有锁，也不在失败后删除。
        lock_fd = openat(directory, kLockName,
            O_RDWR | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK, 0600);
        if (lock_fd < 0 || fstat(lock_fd, &lock_info) != 0 || !private_file(lock_info, context.uid) ||
            lock_info.st_size != 0 || flock(lock_fd, LOCK_EX | LOCK_NB) != 0) return kStartupLockRejected;
    }
    if (!maintenance_paths_match(directory, context.uid, lock_info, seen, state_info) ||
        (seen.have_pending ? !path_matches(directory, kPendingName, context.uid, pending_info)
            : !absent_path(directory, kPendingName))) return kStartupPublishFailed;
    if (fsync(lock_fd) != 0) return kStartupFileSyncFailed;
    if (fsync(directory) != 0) { return kStartupDirectorySyncFailed; }
    errors = maintenance_grant_errors(context, operation, grant, seen);
    if (errors != 0) return errors;
    const auto wall = clock_value(CLOCK_REALTIME, true);
    if (wall < kSafetyMinTimeMs ||
        (operation == SafetyMaintenanceOperation::kResumePending && wall < seen.pending.last_start_ms)) {
        return kStartupFuseRejected;
    }
    // 武装尚不算真实启动；下一独立进程第一次记录启动时，计数仍应从 0 开始。
    const bool resume = operation == SafetyMaintenanceOperation::kResumePending ||
        operation == SafetyMaintenanceOperation::kResumeArming;
    result.next = resume ? seen.pending :
        SafetyFuseState{0, 0, false, grant.reset_token, 0};
    errors = maintenance_stage(directory, context.uid, result.next, resume, pending_info, cleanup_errors);
    if (errors != 0 || cleanup_errors != 0) return errors;
    errors = maintenance_grant_errors(context, operation, grant, seen);
    if (errors != 0) return errors;
    if (!maintenance_paths_match(directory, context.uid, lock_info, seen, state_info) ||
        !path_matches(directory, kPendingName, context.uid, pending_info)) return kStartupPublishFailed;
    if (renameat(directory, kPendingName, directory, kStateName) != 0) return kStartupPublishFailed;
    result.published = true;
    if (fsync(directory) != 0) { return kStartupDirectorySyncFailed; }
    SafetyFuseState readback{};
    struct stat readback_info{};
    if (!read_state(directory, context.uid, readback, readback_info, cleanup_errors) ||
        !same_inode(readback_info, pending_info) || !equal_state(readback, result.next)) {
        return kStartupReadbackFailed;
    }
    result.committed = true;
    return 0;
}

}  // 匿名命名空间

SafetyMaintenanceResult maintain_safety_once(const DiagnosticContext &context,
    SafetyMaintenanceOperation operation, SafetyMaintenanceAuthorizer authorize, void *opaque) {
    SafetyMaintenanceResult result{};
    if (!caller_current(context)) result.blockers |= kStartupCallerRejected;
    if (authorize == nullptr) result.blockers |= kMaintenanceAuthorizationRejected;
    if (operation != SafetyMaintenanceOperation::kArmAbsent &&
        operation != SafetyMaintenanceOperation::kRearmKnown &&
        operation != SafetyMaintenanceOperation::kResumePending &&
        operation != SafetyMaintenanceOperation::kResumeArming) result.blockers |= kMaintenanceOperationRejected;
    if (result.blockers != 0) return result;
    uint32_t expected_owner = 0;
    if (!__atomic_compare_exchange_n(&g_startup_owner, &expected_owner, context.pid, false,
        __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
        result.blockers = expected_owner == context.pid ? kStartupAlreadyAttempted : kStartupInheritedAttempt;
        return result;
    }
    uint32_t cleanup_errors = 0;
    SafetyMaintenanceGrant grant{};
    SafetyMaintenanceObservation seen{};
    int directory = open_files(context.uid, result.blockers);
    int lock_fd = -1;
    if (result.blockers == 0) {
        lock_fd = openat(directory, kLockName, O_RDWR | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
        if (lock_fd < 0 && errno != ENOENT) result.blockers |= kStartupLockRejected;
    }
    if (result.blockers == 0) {
        result.blockers |= maintenance_locked(directory, lock_fd, context, operation, authorize, opaque,
            grant, seen, result, cleanup_errors);
    }
    if (!close_checked(lock_fd)) cleanup_errors |= kStartupCloseFailed;
    if (!close_checked(directory)) cleanup_errors |= kStartupCloseFailed;
    if (cleanup_errors != 0) result.committed = false;
    result.blockers |= cleanup_errors;
    if (result.published) result.blockers |= maintenance_grant_errors(context, operation, grant, seen);
    if (result.committed) {
        const auto wall = clock_value(CLOCK_REALTIME, true);
        if (wall < kSafetyMinTimeMs || wall < result.next.last_start_ms) result.blockers |= kStartupFuseRejected;
    }
    return result;
}

}  // 命名空间 launcher
