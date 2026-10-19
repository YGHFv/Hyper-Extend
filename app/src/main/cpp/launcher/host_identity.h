/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#pragma once

#include <stddef.h>
#include <stdint.h>

// 桌面原生宿主的身份判据。桌面是 usap64 池 fork 出的进程，exe 仍是
// /system_ext/bin/hyos_spawner（fork 后不更换可执行文件），特化靠 setprogname
// 把 cmdline 设为 com.miui.home；本进程没有 ART，不能使用 Java 路径。
namespace launcher {

constexpr char kLauncherPackage[] = "com.miui.home";
constexpr char kSpawnerExe[] = "/system_ext/bin/hyos_spawner";
constexpr char kUsapCmdline[] = "usap64";

enum HostIdentity : uint32_t {
    kIdentityLauncherChild = 1U << 0,  // cmdline=com.miui.home，已特化的桌面子进程
    kIdentityUsapPool = 1U << 1,       // cmdline=usap64，spawner 池
    kIdentitySpawnerOther = 1U << 2,   // exe=spawner，但既不是桌面也不是池（其它 Rust 应用）
    kIdentityUnknown = 1U << 3,
};

// 全部 constexpr，便于交叉编译期断言；不做分配，可在 native_init 中早期调用。
constexpr size_t fixed_len(const char *value) {
    size_t size = 0;
    while (value[size] != '\0') size++;
    return size;
}

constexpr bool fixed_eq(const char *value, size_t size, const char *expected) {
    const size_t want = fixed_len(expected);
    if (size != want) return false;
    for (size_t index = 0; index < want; index++) {
        if (value[index] != expected[index]) return false;
    }
    return true;
}

// cmdline 缓冲形如 "com.miui.home\0"，需要跳过结尾的零字节。
constexpr size_t trim_nul(const char *value, size_t size) {
    size_t end = size;
    while (end > 0 && value[end - 1] == '\0') end--;
    return end;
}

constexpr uint32_t classify_host(const char *cmdline, size_t cmdline_size,
    const char *exe, size_t exe_size) {
    const size_t cmd = trim_nul(cmdline, cmdline_size);
    const bool launcher = fixed_eq(cmdline, cmd, kLauncherPackage);
    const bool spawner = exe_size > 0 && fixed_eq(exe, exe_size, kSpawnerExe);
    const bool usap = fixed_eq(cmdline, cmd, kUsapCmdline);
    // cmdline 是特化的判据，exe 在 fork 后仍为 spawner，不能用来排除桌面。
    if (launcher) return kIdentityLauncherChild;
    if (usap) return kIdentityUsapPool;
    if (spawner) return kIdentitySpawnerOther;
    return kIdentityUnknown;
}

// 只有 cmdline=com.miui.home 才是桌面子进程。身份只是前置条件之一，
// 安全与后端许可尚未核验，因此这并不等于安装许可。
constexpr bool host_is_specialized_launcher(uint32_t identity) {
    return identity == kIdentityLauncherChild;
}

}  // 命名空间 launcher
