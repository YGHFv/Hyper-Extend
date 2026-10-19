/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
// 只读目录元数据与 ACL 的离线替身，不访问真实文件系统。
#include "../../main/cpp/launcher/directory_policy.h"

namespace {
struct stat metadata{};
int stat_error = 0, acl_error = ENODATA, acl_calls = 0, violations = 0;
ssize_t acl_result = -1;

bool equal(const char *left, const char *right) {
    while (*left != '\0' && *left == *right) { ++left; ++right; }
    return *left == *right;
}
}

extern "C" int *__errno() { return &acl_error; }
extern "C" int fstat(int fd, struct stat *out) {
    if (fd != 10) ++violations;
    out->st_mode = metadata.st_mode;
    out->st_uid = metadata.st_uid;
    out->st_gid = metadata.st_gid;
    return stat_error;
}
extern "C" ssize_t fgetxattr(int fd, const char *name, void *value, size_t size) {
    ++acl_calls;
    if (fd != 10 || !equal(name, "system.posix_acl_access") || value != nullptr || size != 0)
        ++violations;
    return acl_result;
}

extern "C" __attribute__((visibility("default")))
int launcher_directory_policy_trial(int scenario) {
    using namespace launcher;
    uint32_t uid = 10001;
    DirectoryRole role = DirectoryRole::kFiles;
    metadata.st_mode = S_IFDIR | 0771;
    metadata.st_uid = metadata.st_gid = uid;
    uint32_t expected = 0;
    bool expect_acl = true;
    switch (scenario) {
        case 0: metadata.st_mode = S_IFDIR | 0700; role = DirectoryRole::kPackage; expect_acl = false; break;
        case 1: break;
        case 2: metadata.st_mode = S_IFDIR | 0770; acl_error = EOPNOTSUPP; break;
        case 3: metadata.st_mode |= 0002; expected = kDirectoryUnsafeWriters; expect_acl = false; break;
        case 4: metadata.st_gid = 0; expected = kDirectoryUnsafeWriters; expect_acl = false; break;
        case 5: metadata.st_gid += 100000; expected = kDirectoryUnsafeWriters; expect_acl = false; break;
        case 6: ++metadata.st_uid; expected = kDirectoryWrongOwner; expect_acl = false; break;
        case 7: role = DirectoryRole::kPackage; expected = kDirectoryUnsafeWriters; expect_acl = false; break;
        case 8: acl_result = 16; expected = kDirectoryAclUnverified; break;
        case 9: acl_error = EACCES; expected = kDirectoryAclUnverified; break;
        case 10: stat_error = -1; expected = kDirectoryStatFailed; expect_acl = false; break;
        case 11: metadata.st_mode = S_IFREG | 0771; expected = kDirectoryWrongType; expect_acl = false; break;
        case 12: acl_result = 0; expected = kDirectoryAclUnverified; break;
        case 13: acl_error = EIO; expected = kDirectoryAclUnverified; break;
        case 14: uid = metadata.st_uid = metadata.st_gid = 0; expected = kDirectoryUnsafeWriters; expect_acl = false; break;
        case 15: metadata.st_gid += 10000; expected = kDirectoryUnsafeWriters; expect_acl = false; break;
        case 16: uid = metadata.st_uid = metadata.st_gid = 1210001; break;
        case 17: uid = metadata.st_uid = 1210001; expected = kDirectoryUnsafeWriters; expect_acl = false; break;
        case 18: metadata.st_mode = S_IFDIR | 0750; metadata.st_gid = 0; expect_acl = false; break;
        case 19: metadata.st_mode |= S_ISGID; expected = kDirectoryUnsafeWriters; expect_acl = false; break;
        case 20: metadata.st_mode |= S_ISVTX; expected = kDirectoryUnsafeWriters; expect_acl = false; break;
        case 21: acl_error = EINTR; expected = kDirectoryAclUnverified; break;
        case 22: acl_error = ENOMEM; expected = kDirectoryAclUnverified; break;
        default: return __LINE__;
    }
    const uint32_t actual = private_directory_blockers(10, uid, role);
    if (actual != expected || acl_calls != (expect_acl ? 1 : 0) || violations != 0) return __LINE__;
    return 0;
}
