/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
// 系统调用只操作本文件的内存模型；该试验不进入 APK，也不访问真实文件系统。
#include "../../main/cpp/launcher/safety_startup.cpp"
#include "../../main/cpp/launcher/install_permit.h"

#include <stdarg.h>

namespace trial {
using namespace launcher;
int scenario = 0, violations = 0, io_calls = 0, live_fds = 0;
int writes = 0, renames = 0, directory_syncs = 0, state_opens = 0, locks = 0;
int events = 0, write_event = 0, sync_event = 0, pending_close_event = 0;
int rename_event = 0, directory_sync_event = 0, readback_event = 0, lock_close_event = 0;
uint32_t pid = 42, uid = 10001, euid = 10001;
bool locked = false, pending_exists = false, pending_open = false, state_exists = true;
bool maintenance_mode = false, lock_exists = true;
bool app_group_files = false;
bool automatic_start_test = false, initial_lock_race = false, initial_state_race = false;
bool root_request_mode = false, root_request_exists = true;
const char *root_request_text = "1,10001,1000,1,1073741831";
int initial_probe_error = 0;
int last_errno = 0, lock_creates = 0;
bool fd_live[16]{};
char state[256] = "1700000000000,0,0,0,1700000000000";
char pending[256]{};
size_t state_size = 33, pending_size = 0, read_offset = 0;
ino_t state_inode = 101, pending_inode = 0, next_inode = 102;
SafetyStartupResult nested{};

size_t length(const char *text) {
    size_t size = 0;
    while (text[size] != '\0') ++size;
    return size;
}
bool text_equal(const char *left, const char *right) {
    while (*left == *right) {
        if (*left == '\0') return true;
        ++left;
        ++right;
    }
    return false;
}
void copy(void *out, const void *in, size_t size) {
    for (size_t i = 0; i < size; ++i) static_cast<char *>(out)[i] = static_cast<const char *>(in)[i];
}
void set_state(const char *text) {
    state_size = length(text);
    copy(state, text, state_size + 1);
}
DiagnosticContext context() {
    return {pid, uid, euid, kIdentityLauncherChild, callback_record(pid, 3, true)};
}
Snapshot snapshot() {
    const int32_t token = scenario == 79 ? INT32_MAX :
        (scenario == 3 || (scenario >= 80 && scenario <= 82)) ? 1 : 0;
    return {kSnapshotAck, 1, 7, 1000, 1030, 3, 8, 1, token, 0};
}
int acquire(int fd) {
    if (fd_live[fd]) ++violations;
    fd_live[fd] = true;
    ++live_fds;
    return fd;
}
void event() { ++io_calls; ++events; }

uint32_t expected_errors(int code) {
    switch (code) {
        case 1: case 2: case 4: case 44: case 45: case 46: case 77: case 84:
            return kStartupFuseRejected;
        case 5: case 6: case 7: case 8: case 9: case 10: case 11: case 64:
            return kStartupStateUnreadable;
        case 12: case 58: case 74: return kStartupDirectoryRejected;
        case 13: case 14: case 15: case 16: case 65: case 70: return kStartupLockRejected;
        case 17: case 26: return kStartupFileSyncFailed;
        case 18: case 32: case 75: return kStartupDirectorySyncFailed;
        case 19: case 20: case 21: case 22: case 61: case 66: return kStartupTemporaryRejected;
        case 23: case 24: case 52: return kStartupWriteFailed;
        case 27: case 38: case 39: case 67: case 68: return kStartupCloseFailed;
        case 28: case 29: case 30: case 31: return kStartupPublishFailed;
        case 33: case 34: case 35: case 36: return kStartupReadbackFailed;
        case 37: return kStartupReadbackFailed | kStartupCloseFailed;
        case 3: case 40: case 41: case 42: case 43: case 72: case 73: case 76:
        case 79: case 80: case 81: case 82: return kStartupSnapshotRejected;
        case 47: case 49: case 55: case 56: case 57: case 78: return kStartupCallerRejected;
        case 50: return kStartupNoFeatures;
        case 69: return kStartupStateUnreadable | kStartupCloseFailed;
        default: return 0;
    }
}
}  // 命名空间 trial

extern "C" pid_t getpid() { return static_cast<pid_t>(trial::pid); }
extern "C" uid_t getuid() { return trial::uid; }
extern "C" uid_t geteuid() { return trial::euid; }
extern "C" int *__errno() { return &trial::last_errno; }
extern "C" int clock_gettime(clockid_t clock, timespec *out) {
    using namespace trial;
    out->tv_nsec = 0;
    if (clock == CLOCK_BOOTTIME) {
        if (scenario == 73) return -1;
        out->tv_sec = ((scenario == 40) || (scenario == 41 && state_opens > 0) ||
            (scenario == 42 && pending_size > 0) || (scenario == 43 && renames > 0) ||
            (scenario == 76 && renames > 0 && !fd_live[11])) ? 1030 : 1000;
    } else if (clock == CLOCK_REALTIME) {
        if (scenario == 44 || (scenario == 46 && renames > 0)) return -1;
        out->tv_sec = scenario == 45 ? 1699999999 : 1700000001;
        if (scenario == 77 && renames > 0 && !fd_live[10]) out->tv_sec = 1699999999;
        if (scenario == 62) {
            out->tv_sec = INT64_MAX / 1000 - 1;
            out->tv_nsec = 999000000;
        }
    } else { ++violations; return -1; }
    return 0;
}
extern "C" int open(const char *path, int flags, ...) {
    using namespace trial;
    event();
    if (!text_equal(path, scenario == 59 ? "/data/user/12" : "/data/user/0") ||
        flags != (O_PATH | O_DIRECTORY | O_CLOEXEC)) ++violations;
    return scenario == 12 ? -1 : acquire(8);
}
extern "C" int openat(int fd, const char *path, int flags, ...) {
    using namespace trial;
    event();
    if (fd < 8 || fd > 10 || !fd_live[fd]) ++violations;
    if (fd == 8 || fd == 9) {
        const int expected = (fd == 8 ? O_PATH : O_RDONLY) | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW;
        if (flags != expected || !text_equal(path, fd == 8 ? "com.miui.home" : "files")) ++violations;
        return acquire(fd + 1);
    }
    if (root_request_mode && text_equal(path, kRootRestartRequest)) {
        if (flags != (O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK)) ++violations;
        if (!root_request_exists) { last_errno = ENOENT; return -1; }
        if (scenario == 107) { last_errno = EACCES; return -1; }
        read_offset = 0;
        return acquire(15);
    }
    if (text_equal(path, "hyperextend_launcher_bootguard.lock")) {
        if ((maintenance_mode || automatic_start_test) && (flags & O_CREAT)) {
            if (flags != (O_RDWR | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK)) ++violations;
            va_list args;
            va_start(args, flags);
            if (va_arg(args, int) != 0600) ++violations;
            va_end(args);
            if (initial_lock_race) lock_exists = true;
            if (lock_exists) { last_errno = EEXIST; return -1; }
            lock_exists = true;
            ++lock_creates;
            return acquire(11);
        }
        if (flags != (O_RDWR | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK)) ++violations;
        if (!lock_exists) { last_errno = ENOENT; return -1; }
        if (scenario == 13) { last_errno = EACCES; return -1; }
        return acquire(11);
    }
    if (!locked && !(maintenance_mode && !lock_exists)) ++violations;
    if (text_equal(path, "hyperextend_launcher_bootguard")) {
        if (flags != (O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK)) ++violations;
        ++state_opens;
        read_offset = 0;
        if (!state_exists) { last_errno = ENOENT; return -1; }
        if (renames > 0 && scenario == 33) { last_errno = EIO; return -1; }
        if (renames > 0) readback_event = events;
        return acquire(renames > 0 ? 14 : 12);
    }
    if (text_equal(path, "hyperextend_launcher_bootguard.pending")) {
        if (maintenance_mode && !(flags & O_CREAT)) {
            if (flags != ((flags & O_RDWR) | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK)) ++violations;
            if (!pending_exists) { last_errno = ENOENT; return -1; }
            read_offset = 0;
            return acquire(15);
        }
        va_list args;
        va_start(args, flags);
        const int mode = va_arg(args, int);
        va_end(args);
        if (flags != (O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK) ||
            mode != 0600) ++violations;
        if (pending_exists || scenario == 20) return -1;
        pending_exists = pending_open = true;
        pending_size = 0;
        pending_inode = next_inode++;
        return acquire(13);
    }
    ++violations;
    return -1;
}

void stat_info(int fd, struct stat *out) {
    using namespace trial;
    out->st_dev = 1;
    out->st_ino = fd == 11 ? 100 : (fd == 13 || fd == 15) ? pending_inode : state_inode;
    out->st_mode = fd <= 10 ? S_IFDIR | 0700 : S_IFREG | 0600;
    out->st_uid = scenario == 59 ? 1210001 : 10001;
    out->st_gid = out->st_uid;
    if (fd == 10 && app_group_files) out->st_mode = S_IFDIR | 0771;
    out->st_nlink = 1;
    out->st_size = fd == 11 ? 0 : (fd == 13 || fd == 15) ? static_cast<off_t>(pending_size)
        : static_cast<off_t>(state_size);
    if (root_request_mode && fd == 15) {
        out->st_uid = scenario == 101 ? uid : 0;
        out->st_mode = scenario == 102 ? S_IFREG | 0644 : S_IFREG | 0444;
        if (scenario == 103) out->st_mode = S_IFLNK | 0444;
        if (scenario == 104) out->st_nlink = 2;
        out->st_size = scenario == 105 ? 129 : static_cast<off_t>(length(root_request_text));
        if (scenario == 108) ++out->st_size;
    }
}
extern "C" int fstat(int fd, struct stat *out) {
    using namespace trial;
    event();
    if (fd < 9 || fd > 15 || !fd_live[fd]) ++violations;
    stat_info(fd, out);
    if (fd == 12) {
        if (scenario == 9) out->st_size = 128;
        if (scenario == 10) out->st_mode |= 0040;
        if (scenario == 11) ++out->st_uid;
        if (scenario == 64) out->st_nlink = 2;
    }
    if (fd == 11) {
        if (scenario == 15) out->st_mode = S_IFIFO | 0600;
        if (scenario == 65) out->st_nlink = 2;
        if (scenario == 70) out->st_size = 1;
    }
    if (fd == 13 || fd == 15) {
        if (scenario == 21) return -1;
        if (scenario == 22) out->st_mode |= 0020;
        if (scenario == 66) out->st_nlink = 2;
    }
    if (fd == 14 && scenario == 35) ++out->st_ino;
    if (fd == 10 && scenario == 58) {
        out->st_mode |= 0020;
        ++out->st_gid;
    }
    if (fd == 10 && scenario == 74) return -1;
    return 0;
}
extern "C" ssize_t fgetxattr(int fd, const char *name, void *value, size_t size) {
    using namespace trial;
    event();
    if (fd != 10 || !fd_live[fd] || !app_group_files ||
        !text_equal(name, "system.posix_acl_access") || value != nullptr || size != 0) ++violations;
    last_errno = ENODATA;
    return -1;
}
extern "C" int fstatat(int directory, const char *name, struct stat *out, int flags) {
    using namespace trial;
    event();
    if (directory != 10 || !fd_live[10] || flags != AT_SYMLINK_NOFOLLOW) ++violations;
    if (root_request_mode && text_equal(name, kRootRestartRequest)) {
        stat_info(15, out);
        if (scenario == 110) ++out->st_ino;
        return 0;
    }
    const bool lock = text_equal(name, "hyperextend_launcher_bootguard.lock");
    const bool temporary = text_equal(name, "hyperextend_launcher_bootguard.pending");
    if (!lock && !temporary && !text_equal(name, "hyperextend_launcher_bootguard")) ++violations;
    if (automatic_start_test && !lock && !temporary) {
        if (initial_probe_error != 0) { last_errno = initial_probe_error; return -1; }
        if (initial_state_race && pending_size > 0) state_exists = true;
    }
    if ((lock && !lock_exists) || (temporary && !pending_exists) ||
        (!lock && !temporary && !state_exists)) { last_errno = ENOENT; return -1; }
    stat_info(lock ? 11 : temporary ? 13 : 12, out);
    if ((lock && scenario == 16) ||
        (lock && scenario == 30 && pending_size > 0) ||
        (!lock && !temporary && scenario == 28 && pending_size > 0) ||
        (temporary && scenario == 29)) ++out->st_ino;
    return 0;
}
extern "C" int flock(int fd, int operation) {
    using namespace trial;
    event();
    ++locks;
    if (fd != 11 || operation != (LOCK_EX | LOCK_NB) || !fd_live[11]) ++violations;
    if (scenario == 14) return -1;
    if (locked) ++violations;
    locked = true;
    if (scenario == 53) nested = launcher::commit_safety_startup_once(context(), snapshot(), 6);
    return 0;
}
extern "C" ssize_t read(int fd, void *out, size_t size) {
    using namespace trial;
    event();
    if (root_request_mode && fd == 15) {
        if (!fd_live[fd]) ++violations;
        if (scenario == 109) return -1;
        size_t got = length(root_request_text) - read_offset;
        if (got > size) got = size;
        copy(out, root_request_text + read_offset, got);
        read_offset += got;
        return static_cast<ssize_t>(got);
    }
    if ((fd != 12 && fd != 14 && fd != 15) || !fd_live[fd] ||
        (!locked && !(maintenance_mode && !lock_exists))) ++violations;
    if ((fd == 12 && scenario == 7) || (fd == 14 && scenario == 36)) return -1;
    if (scenario == 8 && read_offset >= state_size - 1) return 0;
    size_t got = (fd == 15 ? pending_size : state_size) - read_offset;
    if (got > size) got = size;
    if (scenario == 8 && got > 1) --got;
    if (got > 0) {
        copy(out, (fd == 15 ? pending : state) + read_offset, got);
        if (fd == 14 && scenario == 34) static_cast<char *>(out)[0] = 'x';
        read_offset += got;
    }
    return static_cast<ssize_t>(got);
}
extern "C" ssize_t write(int fd, const void *bytes, size_t size) {
    using namespace trial;
    event();
    ++writes;
    if (write_event == 0) write_event = events;
    if (fd != 13 || !fd_live[13] || !pending_open || !locked || size == 0) ++violations;
    if (scenario == 23 || scenario == 52) return -1;
    if (scenario == 24) return 0;
    if (scenario == 25 && size > 1) size = 1;
    if (pending_size + size >= sizeof(pending)) { ++violations; return -1; }
    copy(pending + pending_size, bytes, size);
    pending_size += size;
    pending[pending_size] = '\0';
    return static_cast<ssize_t>(size);
}
extern "C" int fsync(int fd) {
    using namespace trial;
    event();
    if (root_request_mode && fd == 10) {
        if (!fd_live[fd] || root_request_exists) ++violations;
        return scenario == 112 ? -1 : 0;
    }
    if (!locked || !fd_live[fd]) ++violations;
    if (fd == 11) return scenario == 17 ? -1 : 0;
    if (fd == 13 || fd == 15) {
        sync_event = events;
        return scenario == 26 ? -1 : 0;
    }
    if (fd != 10) ++violations;
    ++directory_syncs;
    if (renames > 0) directory_sync_event = events;
    if ((scenario == 18 && renames == 0) || ((scenario == 32 || scenario == 75) && renames > 0)) {
        return -1;
    }
    return 0;
}
extern "C" int renameat(int from, const char *old_name, int to, const char *new_name) {
    using namespace trial;
    event();
    if (root_request_mode) {
        if (from != 10 || to != 10 || fd_live[15] || !root_request_exists ||
            !text_equal(old_name, kRootRestartRequest) || !text_equal(new_name, kRootRestartConsumed))
            ++violations;
        if (scenario == 111) return -1;
        root_request_exists = false;
        ++renames;
        return 0;
    }
    if (from != 10 || to != 10 || !text_equal(old_name, "hyperextend_launcher_bootguard.pending") ||
        !text_equal(new_name, "hyperextend_launcher_bootguard") || !locked ||
        !pending_exists || pending_open || sync_event == 0 || pending_close_event <= sync_event) ++violations;
    if (scenario == 31) return -1;
    ++renames;
    rename_event = events;
    set_state(pending);
    state_exists = true;
    state_inode = pending_inode;
    pending_exists = false;
    return 0;
}
extern "C" int close(int fd) {
    using namespace trial;
    event();
    if (fd < 8 || fd > 15 || !fd_live[fd]) ++violations;
    else { fd_live[fd] = false; --live_fds; }
    if (fd == 11) { locked = false; lock_close_event = events; }
    if (fd == 13) { pending_open = false; pending_close_event = events; }
    if (fd == 15) pending_close_event = events;
    if (fd == 12 && scenario == 55) ++pid;
    if (fd == 13 && scenario == 56) ++pid;
    if (fd == 14 && scenario == 57) ++uid;
    if (fd == 10 && scenario == 78) ++pid;
    if ((fd == 13 && scenario == 27) || (fd == 14 && scenario == 37) ||
        (fd == 11 && scenario == 38) || (fd == 10 && scenario == 39) ||
        (fd == 8 && scenario == 67) || (fd == 9 && scenario == 68) ||
        (fd == 12 && scenario == 69)) return -1;
    return 0;
}
extern "C" void *memset(void *out, int value, size_t size) {
    for (size_t i = 0; i < size; ++i) static_cast<unsigned char *>(out)[i] = static_cast<unsigned char>(value);
    return out;
}
extern "C" void *memcpy(void *out, const void *in, size_t size) {
    trial::copy(out, in, size);
    return out;
}

#define CHECK(condition) do { if (!(condition)) return __LINE__; } while (false)
#ifndef LAUNCHER_SAFETY_MAINTENANCE_TRIAL
extern "C" __attribute__((visibility("default")))
int launcher_safety_startup_trial(int code) {
    using namespace launcher;
    using namespace trial;
    SafetyStartupResult incomplete{0, false, true, {}};
    CHECK(!incomplete.safety_ready());
    incomplete = {0, true, false, {}};
    CHECK(!incomplete.safety_ready());
    incomplete = {kStartupReadbackFailed, true, true, {}};
    CHECK(!incomplete.safety_ready());
    scenario = code;
    if (scenario == 1) set_state("1700000000000,2,0,0,1700000000000");
    if (scenario == 2 || scenario == 3 || scenario == 4) set_state("1700000000000,3,1,0,1700000000000");
    if (scenario == 5) state_exists = false;
    if (scenario == 6) set_state("损坏状态");
    if (scenario == 19 || scenario == 61) pending_exists = true;
    if (scenario == 47) uid = euid = 0;
    if (scenario == 49) euid = 0;
    if (scenario == 59) uid = euid = 1210001;
    if (scenario == 62) set_state("9223372036854770000,0,0,9223372036854775807,9223372036854770000");
    if (scenario == 63) set_state("1700000000000,0");
    if (scenario == 79) set_state("1700000000000,3,1,0,1700000000000");
    if (scenario == 81) set_state("1700000000000,1,0,1,1700000000000");
    if (scenario == 82) set_state("1700000000000,1,0,2,1700000000000");
    if (scenario == 83) set_state("1700000000000,0,0,2147483647,1700000000000");
    if (scenario == 84) set_state("1700000000000,3,1,2147483647,1700000000000");
    if (scenario == 71) {
        for (size_t i = 0; i < 112; ++i) state[i] = '0';
        copy(state + 112, "1700000000000,0", 16);
        state_size = 127;
    }
    auto input = context();
    auto config = snapshot();
    if (scenario == 48) input.callback = callback_record(pid, 3, true);
    if (scenario == 50) config.features = 0;
    const auto result = commit_safety_startup_once(input, config, scenario == 72 ? 7 : 6);
    CHECK(violations == 0 && live_fds == 0 && !locked);
    CHECK(result.blockers == expected_errors(code));
    const bool published = expected_errors(code) == 0 || code == 1 || code == 32 ||
        (code >= 33 && code <= 39) || code == 43 || code == 46 || code == 57 || code == 75;
    const bool late_change = code >= 76 && code <= 78;
    const bool committed = (published || late_change) && !(code >= 32 && code <= 39) && code != 75;
    CHECK(result.published == (published || late_change) && result.committed == committed);
    CHECK(result.safety_ready() == (expected_errors(code) == 0));
    CHECK(install_permitted({kIdentityLauncherChild, true, result.safety_ready(), true}) ==
        result.safety_ready());
    if (published || late_change) {
        CHECK(renames == 1 && !pending_exists && state_inode == 102);
        CHECK(write_event < sync_event && sync_event < pending_close_event &&
            pending_close_event < rename_event);
    } else CHECK(renames == 0);
    if (committed) {
        CHECK(rename_event < directory_sync_event && directory_sync_event < readback_event &&
            readback_event < lock_close_event && directory_syncs == 2);
        if (code == 1) CHECK(text_equal(state, "1700000001000,3,1,0,1700000000000"));
        else if (code == 83) CHECK(text_equal(state, "1700000001000,1,0,2147483647,1700000000000"));
        else if (code != 62) CHECK(text_equal(state, "1700000001000,1,0,0,1700000000000"));
        SafetyFuseState decoded{};
        CHECK(decode_safety_fuse(state, static_cast<uint32_t>(state_size), decoded));
        CHECK(equal_state(decoded, result.next));
    }
    if (code == 53) CHECK(nested.blockers == kStartupAlreadyAttempted && locks == 1);
    // 同一进程成功或失败后都拒绝重试，不能因 rename 后同步失败而重复计数。
    if (g_startup_owner != 0) {
        scenario = 0;
        uid = euid = code == 59 ? 1210001 : 10001;
        const int before = io_calls;
        const auto again = commit_safety_startup_once(context(), snapshot(), 6);
        CHECK(again.blockers == (g_startup_owner == pid ? kStartupAlreadyAttempted : kStartupInheritedAttempt));
        CHECK(!again.safety_ready() && !again.published && !again.committed && io_calls == before);
    } else CHECK(io_calls == 0);
    if (code == 54) {
        ++pid;
        const int before = io_calls;
        const auto child = commit_safety_startup_once(context(), snapshot(), 6);
        CHECK(child.blockers == kStartupInheritedAttempt && io_calls == before);
    }
    if (code == 60 || code == 75) {
        // 仅测试模型：保留磁盘内容，重置模块内存，模拟下一个独立进程；不是生产重置接口。
        scenario = 0;
        g_startup_owner = 0;
        ++pid;
        auto next = commit_safety_startup_once(context(), snapshot(), 6);
        CHECK(next.committed && next.safety_ready() && next.next.rapid_restarts == 2);
        g_startup_owner = 0;
        ++pid;
        next = commit_safety_startup_once(context(), snapshot(), 6);
        CHECK(next.committed && !next.safety_ready() && next.next.rapid_restarts == 3 && next.next.disabled);
    }
    CHECK(violations == 0 && live_fds == 0);
    return 0;
}
#endif
