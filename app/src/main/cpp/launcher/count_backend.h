/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#pragma once

#include <stddef.h>
#include <stdint.h>
#include <link.h>

namespace launcher {
namespace backend {

// HYOS 的 AOT 映像可能不在链接器枚举表中；用真实 ELF 头和全部映射段核对，
// 不以 APK 的最小映射地址猜测基址；可用 Hook 点由调用方核对 ABI，不绑定版本。
bool visit_loaded_launcher_images(int (*callback)(dl_phdr_info *, size_t, void *), void *opaque);
uint32_t launcher_image_probe_status();

enum class PrepareError : uint32_t {
    kNone = 0,
    kInvalidArgument,
    kPageSizeUnavailable,
    kNoNearMapping,
    kBadTemplate,
    kRelocationFailed,
    kCachePublishFailed,
    kSealFailed,
};

enum class InstallError : uint32_t {
    kNone = 0,
    kInstallBusy,
    kInvalidPreparedIsland,
    kInvalidCallSitePlan,
    kCallSiteChanged,
    kWritableProtectionFailed,
    kProtectionRestoreFailed,
    kRollbackConflict,
    kHostPageLeftWritableExecutable,
    kRollbackConflictAndWritableExecutable,
};

struct PreparedIsland {
    void *mapping;
    size_t mapping_size;
    uintptr_t entry;
    uintptr_t original;
    uintptr_t continuation;
    uintptr_t *original_slot;
    uintptr_t *continuation_slot;
    bool sealed;
    bool slots_published;
    // 开始写宿主调用点后置位；即使随后回滚或 RX 恢复失败，岛页也必须永久保留。
    bool host_may_reference;
    // 只有 RX 已恢复且调用点回读等于期望新字时才置位，代表可采信的发布成功。
    bool published;
    bool host_page_may_be_wx;
};

struct InstallOps {
    long (*page_size)();
    bool (*page_is_rx)(uintptr_t page_start, size_t page_size);
    int (*protect)(void *page, size_t page_size, int protection);
    uint32_t (*load_word)(const uint32_t *site);
    void (*store_word)(uint32_t *site, uint32_t word);
    bool (*compare_exchange_word)(uint32_t *site, uint32_t *expected, uint32_t desired);
    void (*publish_code)(void *address, size_t bytes);
};

// 只准备 RX 岛页与槽位，不写宿主调用点，也不授予安装许可。
PrepareError prepare_count_island(uintptr_t site, uintptr_t original,
    uintptr_t continuation, const uint32_t *template_words, size_t template_word_count,
    uintptr_t *original_slot, uint32_t *state_slot, uintptr_t *continuation_slot,
    PreparedIsland *out);

// 准备一个只转发 Dart 调用的叶蹦床。每个调用点独占一个 original 槽，
// 因而不需要在蹦床内猜测调用者，也不会改写 x0/x30。
PrepareError prepare_menu_island(uintptr_t site, uintptr_t original,
    const uint32_t *template_words, size_t template_word_count,
    uintptr_t *original_slot, PreparedIsland *out);

// 与普通菜单叶页不同，调用点通过 BL 进入岛页会改写 x30；必须在跳回宿主
// 原函数前恢复“调用点 + 4”，否则原函数返回时会落入岛页尾部。
PrepareError prepare_menu_island_with_continuation(uintptr_t site, uintptr_t original,
    uintptr_t continuation, const uint32_t *template_words, size_t template_word_count,
    uintptr_t *original_slot, uintptr_t *continuation_slot, PreparedIsland *out);

PrepareError prepare_menu_island_with_target(uintptr_t site, uintptr_t original,
    uintptr_t target, uintptr_t continuation, const uint32_t *template_words,
    size_t template_word_count, uintptr_t *original_slot, uintptr_t *continuation_slot,
    PreparedIsland *out);

// 准备一个把调用点转发到固定替换入口的叶蹦床。它不依赖 original 槽，
// 适用于需要改变返回值语义的函数入口；调用点提交不写 x30 的单条 B。
PrepareError prepare_direct_island(uintptr_t site, uintptr_t replacement,
    PreparedIsland *out);

// 异步 Dart 结果的出口蹦床：把布尔结果适配后，沿原函数的返回蹦床继续执行。
PrepareError prepare_validity_exit_island(uintptr_t site, uintptr_t replacement,
    PreparedIsland *out);

// 入口透明蹦床：复制经解析器验证的位置无关序言，再跳回原函数第 6 条之后。
// 返回槽指向序言副本，失败回退时原闭包帧只会建立一次。
PrepareError prepare_transparent_entry_island(uintptr_t site, uintptr_t replacement,
    const uint32_t *prologue, size_t prologue_word_count,
    uintptr_t *original_trampoline_slot, PreparedIsland *out);

// 仅允许释放尚未发布到宿主调用点的映射。
bool discard_unpublished_island(PreparedIsland *island);

// 临时把宿主调用点页改为 RWX，仅执行单条对齐 BL 发布后恢复 RX。
// 此路径由原型唯一安装点调用，仅在安装许可与计划校验均通过后执行。
InstallError install_count_island(PreparedIsland *island, uintptr_t site,
    uint32_t expected_old_word);

// 可注入系统调用的同一事务入口，供故障注入测试模拟权限切换与代码字冲突。
InstallError install_count_island_with_ops(PreparedIsland *island, uintptr_t site,
    uint32_t expected_old_word, const InstallOps *ops);

// 与数量岛页共用发布事务，但不改写返回值；用于分享 APK/小窗等透明调用点。
InstallError install_menu_island(PreparedIsland *island, uintptr_t site,
    uint32_t expected_old_word);

// 提交固定替换入口的单字调用点。expected_old_word 可以是任意经过取证的旧指令，
// 不要求它本身是 BL。
InstallError install_direct_island(PreparedIsland *island, uintptr_t site,
    uint32_t expected_old_word);

}  // namespace backend
}  // namespace launcher
