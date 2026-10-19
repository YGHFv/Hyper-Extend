/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#pragma once

#include "host_identity.h"
#include <link.h>

namespace launcher {

constexpr char kSystemLauncherApk[] = "/product/priv-app/MiuiHome/MiuiHome.apk";
constexpr char kSystemLauncherLibrary[] = "/product/priv-app/MiuiHome/lib/arm64/libapp.so";
constexpr char kAotSuffix[] = "!/lib/arm64-v8a/libapp.so";
inline bool path_part(const char *path, size_t length, const char *expected) {
    return fixed_eq(path, length, expected);
}

inline bool launcher_apk_path(const char *path, size_t length) {
    if (path == nullptr) return false;
    if (path_part(path, length, kSystemLauncherApk)) return true;
    constexpr char prefix[] = "/data/app/";
    constexpr char suffix[] = "/base.apk";
    if (length <= sizeof(prefix) + sizeof(suffix) ||
        !path_part(path, sizeof(prefix) - 1, prefix) ||
        !path_part(path + length - (sizeof(suffix) - 1), sizeof(suffix) - 1, suffix)) return false;
    const size_t end = length - (sizeof(suffix) - 1);
    size_t begin = end;
    while (begin > 0 && path[begin - 1] != '/') --begin;
    const size_t package_size = sizeof(kLauncherPackage) - 1;
    const size_t segment = end - begin;
    return segment >= package_size && path_part(path + begin, package_size, kLauncherPackage) &&
        (segment == package_size || (segment > package_size + 1 && path[begin + package_size] == '-'));
}

inline bool launcher_library_path(const char *path, size_t length) {
    if (path == nullptr) return false;
    if (path_part(path, length, kSystemLauncherLibrary)) return true;
    const size_t suffix = sizeof(kAotSuffix) - 1;
    return length > suffix && path_part(path + length - suffix, suffix, kAotSuffix) &&
        launcher_apk_path(path, length - suffix);
}

inline bool readable_image_span(const dl_phdr_info &image, uintptr_t offset, size_t size) {
    if (offset > UINTPTR_MAX - size || image.dlpi_addr > UINTPTR_MAX - (offset + size)) return false;
    for (size_t i = 0; i < image.dlpi_phnum; ++i) {
        const auto &p = image.dlpi_phdr[i];
        if (p.p_type == PT_LOAD && (p.p_flags & PF_R) != 0 &&
            p.p_vaddr <= offset && p.p_vaddr <= UINTPTR_MAX - p.p_filesz &&
            offset + size <= p.p_vaddr + p.p_filesz) return true;
    }
    return false;
}

}  // 命名空间 launcher
