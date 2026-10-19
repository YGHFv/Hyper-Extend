/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#pragma once

#include "entry_trace.h"

#include <errno.h>
#include <stdint.h>
#include <sys/stat.h>
#include <sys/xattr.h>

namespace launcher {

enum class DirectoryRole { kPackage, kFiles };

enum DirectoryBlocker : uint32_t {
    kDirectoryStatFailed = 1U << 0,
    kDirectoryWrongType = 1U << 1,
    kDirectoryWrongOwner = 1U << 2,
    kDirectoryUnsafeWriters = 1U << 3,
    kDirectoryAclUnverified = 1U << 4,
};

// Android ContextImpl.getFilesDir -> ensurePrivateDirExists 的默认权限是 0771。
// 只允许 files 的同应用组写入；比较完整 UID/GID，不能把多用户 appId 或共享/缓存组当成同组。
// 同 UID/应用组进程属于同一信任边界；不防御 root 或同身份的非协作修改。
constexpr uint32_t directory_metadata_blockers(
    mode_t mode, uid_t owner, gid_t group, uint32_t uid, DirectoryRole role) {
    const uint32_t app_id = uid % 100000U;
    const bool private_app_group = role == DirectoryRole::kFiles &&
        app_id >= 10000U && app_id < 20000U && group == uid && (mode & 07000) == 0;
    const bool unsafe_writers = (mode & 0002) != 0 ||
        ((mode & 0020) != 0 && !private_app_group);
    return (S_ISDIR(mode) ? 0U : kDirectoryWrongType) |
        (owner == uid ? 0U : kDirectoryWrongOwner) |
        (unsafe_writers ? kDirectoryUnsafeWriters : 0U);
}

inline uint32_t private_directory_blockers(int fd, uint32_t uid, DirectoryRole role) {
    struct stat info{};
    if (fstat(fd, &info) != 0) return kDirectoryStatFailed;
    if (role == DirectoryRole::kFiles) {
        // 只输出权限和身份关系，不记录真实 UID/GID；供实机验证兼容分支，而非假定宿主为 0771。
        experimental_entry_trace("directory_files_mode", info.st_mode & 07777U);
        experimental_entry_trace("directory_files_identity",
            (info.st_uid == uid ? 1U : 0U) | (info.st_gid == uid ? 2U : 0U));
    }
    const uint32_t blockers = directory_metadata_blockers(
        info.st_mode, info.st_uid, info.st_gid, uid, role);
    if (blockers != 0 || (info.st_mode & 0020) == 0) return blockers;

    // 组权限可能是 POSIX ACL 的 mask；仅 GID 相等不能排除额外身份拥有写权限。
    // fd 必须是已核对目录的可读句柄，不能用 O_PATH，也不按路径重新打开。
    const ssize_t acl_size = fgetxattr(fd, "system.posix_acl_access", nullptr, 0);
    const int acl_error = acl_size < 0 ? errno : 0;
    if (acl_size < 0 && (acl_error == ENODATA || acl_error == EOPNOTSUPP)) return 0;
    return kDirectoryAclUnverified;
}

}  // 命名空间 launcher
