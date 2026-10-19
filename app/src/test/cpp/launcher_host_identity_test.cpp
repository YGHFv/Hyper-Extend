/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#include "launcher/host_identity.h"
#include "launcher/probe_state.h"

using namespace launcher;

namespace {

constexpr char kHome[] = "com.miui.home";
constexpr char kHomeRaw[] = "com.miui.home\0\0";
constexpr char kSpawner[] = "/system_ext/bin/hyos_spawner";
constexpr char kAppProcess[] = "/system/bin/app_process64";
constexpr char kUsap[] = "usap64";
constexpr char kOther[] = "com.miui.weather2";
constexpr char kGallery[] = "com.miui.gallery";

// 设备实测：/proc/<home>/cmdline=com.miui.home，/proc/<home>/exe 仍是 hyos_spawner。
static_assert(classify_host(kHome, fixed_len(kHome), kSpawner, fixed_len(kSpawner)) ==
    kIdentityLauncherChild, "cmdline 命中桌面即桌面，exe 仍是 spawner 不算未特化");
static_assert(classify_host(kHomeRaw, sizeof(kHomeRaw) - 1, kSpawner, fixed_len(kSpawner)) ==
    kIdentityLauncherChild, "cmdline 结尾零字节要被忽略");

// usap 池本身。
static_assert(classify_host(kUsap, fixed_len(kUsap), kSpawner, fixed_len(kSpawner)) ==
    kIdentityUsapPool, "usap 池不能挂载");
static_assert(classify_host(kUsap, fixed_len(kUsap), kAppProcess, fixed_len(kAppProcess)) ==
    kIdentityUsapPool, "usap 池与 zygote 无关，仍拒绝");

// 其它 Rust 应用（同 spawner，但 cmdline 不是桌面）。
static_assert(classify_host(kOther, fixed_len(kOther), kSpawner, fixed_len(kSpawner)) ==
    kIdentitySpawnerOther, "其它 Rust 应用不能挂载");
static_assert(classify_host(kGallery, fixed_len(kGallery), kSpawner, fixed_len(kSpawner)) ==
    kIdentitySpawnerOther, "相册等 Rust 应用不能挂载");

// 普通 Java 进程：cmdline 未知、exe 是 app_process。
static_assert(classify_host(kOther, fixed_len(kOther), kAppProcess, fixed_len(kAppProcess)) ==
    kIdentityUnknown, "其它进程拒绝");

static_assert(host_is_specialized_launcher(kIdentityLauncherChild), "已特化桌面通过身份前置");
static_assert(!host_is_specialized_launcher(kIdentitySpawnerOther), "其它 spawner 子进程不能挂载");
static_assert(!host_is_specialized_launcher(kIdentityUsapPool), "usap 池不能挂载");
static_assert(!host_is_specialized_launcher(kIdentityUnknown), "未知进程不能挂载");

static_assert((kHookPointerStored | kHostSpecializedLauncher) != kEntriesRejected,
    "保存指针与主机身份都不解除 ABI 拒绝位");

}  // 匿名命名空间

int main() {
    return 0;
}
