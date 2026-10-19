/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#include "launcher/count_backend.h"
#include "launcher/singleword_publish.h"

#include <sys/mman.h>

using launcher::backend::InstallError;
using launcher::backend::InstallOps;
using launcher::backend::PreparedIsland;

namespace {

alignas(4) uint32_t g_site_word = 0;

struct FakeKernel {
    uintptr_t site = 0;
    uint32_t old_word = 0;
    uint32_t new_word = 0;
    uint32_t protect_calls = 0;
    uint32_t load_calls = 0;
    uint32_t publish_calls = 0;
    uint32_t fail_rx_calls = 0;
    bool fail_writable = false;
    bool reject_page = false;
    bool drift_after_first_load = false;
    bool conflict_before_restore = false;
    bool silently_drop_store = false;
    bool keep_old_after_store = false;
    bool keep_stale_after_successful_restore = false;
    bool writable_executable = false;
};

FakeKernel *g_kernel = nullptr;
PreparedIsland *g_nested_island = nullptr;
uintptr_t g_nested_site = 0;
uint32_t g_nested_old_word = 0;
const InstallOps *g_nested_ops = nullptr;
InstallError (*g_nested_attempt)() = nullptr;
InstallError g_nested_result = InstallError::kInvalidCallSitePlan;

long fake_page_size() { return 4096; }

bool fake_page_is_rx(uintptr_t page_start, size_t page_size) {
    if (g_nested_attempt != nullptr) {
        auto *attempt = g_nested_attempt;
        g_nested_attempt = nullptr;
        g_nested_result = attempt();
    }
    return g_kernel != nullptr && !g_kernel->reject_page && page_size == 4096 &&
        page_start == (g_kernel->site & ~static_cast<uintptr_t>(4095));
}

int fake_protect(void *, size_t page_size, int protection) {
    if (g_kernel == nullptr || page_size != 4096) return -1;
    ++g_kernel->protect_calls;
    if (protection == (PROT_READ | PROT_WRITE | PROT_EXEC)) {
        if (g_kernel->fail_writable) return -1;
        g_kernel->writable_executable = true;
        return 0;
    }
    if (protection != (PROT_READ | PROT_EXEC)) return -1;
    if (g_kernel->fail_rx_calls != 0) {
        --g_kernel->fail_rx_calls;
        return -1;
    }
    g_kernel->writable_executable = false;
    if (g_kernel->keep_stale_after_successful_restore) {
        g_site_word = g_kernel->old_word;
        g_kernel->keep_stale_after_successful_restore = false;
    }
    return 0;
}

uint32_t fake_load_word(const uint32_t *site) {
    ++g_kernel->load_calls;
    if (g_kernel->drift_after_first_load && g_kernel->load_calls == 2) return *site ^ 4U;
    return *site;
}

void fake_store_word(uint32_t *site, uint32_t word) {
    if (g_kernel->silently_drop_store) return;
    *site = word;
}

bool fake_compare_exchange(uint32_t *site, uint32_t *expected, uint32_t desired) {
    if (*site != *expected) {
        *expected = *site;
        return false;
    }
    *site = desired;
    return true;
}

void fake_publish_code(void *, size_t bytes) {
    if (bytes == sizeof(uint32_t)) {
        ++g_kernel->publish_calls;
        if (g_kernel->conflict_before_restore && g_kernel->publish_calls == 1) {
            g_site_word ^= 4U;
            g_kernel->conflict_before_restore = false;
        }
    }
}

const InstallOps kFakeOps{fake_page_size, fake_page_is_rx, fake_protect,
    fake_load_word, fake_store_word, fake_compare_exchange, fake_publish_code};

InstallError nested_install() {
    return launcher::backend::install_count_island_with_ops(
        g_nested_island, g_nested_site, g_nested_old_word, g_nested_ops);
}

PreparedIsland make_prepared(FakeKernel &kernel) {
    kernel.site = reinterpret_cast<uintptr_t>(&g_site_word);
    kernel.old_word = launcher::singleword::encode_bl(kernel.site, kernel.site + 0x1000U);
    kernel.new_word = launcher::singleword::encode_bl(kernel.site, kernel.site + 0x2000U);
    g_site_word = kernel.old_word;
    return {reinterpret_cast<void *>(kernel.site + 0x2000U), 4096,
        kernel.site + 0x2000U, kernel.site + 0x1000U, kernel.site + 4U,
        nullptr, nullptr, true, true, false, false, false};
}

bool success_publishes_one_word_and_restores_rx() {
    FakeKernel kernel{};
    g_kernel = &kernel;
    PreparedIsland island = make_prepared(kernel);
    const auto result = launcher::backend::install_count_island_with_ops(
        &island, kernel.site, kernel.old_word, &kFakeOps);
    return result == InstallError::kNone && g_site_word == kernel.new_word &&
        island.published && island.host_may_reference &&
        !island.host_page_may_be_wx && !kernel.writable_executable &&
        kernel.protect_calls == 2 &&
        kernel.publish_calls == 1;
}

bool writable_permission_failure_does_not_touch_site() {
    FakeKernel kernel{};
    g_kernel = &kernel;
    PreparedIsland island = make_prepared(kernel);
    kernel.fail_writable = true;
    const auto result = launcher::backend::install_count_island_with_ops(
        &island, kernel.site, kernel.old_word, &kFakeOps);
    return result == InstallError::kWritableProtectionFailed && g_site_word == kernel.old_word &&
        !island.published && !island.host_may_reference && kernel.protect_calls == 1;
}

bool changed_site_is_not_overwritten() {
    FakeKernel kernel{};
    g_kernel = &kernel;
    PreparedIsland island = make_prepared(kernel);
    kernel.drift_after_first_load = true;
    const auto result = launcher::backend::install_count_island_with_ops(
        &island, kernel.site, kernel.old_word, &kFakeOps);
    return result == InstallError::kCallSiteChanged && g_site_word == kernel.old_word &&
        !island.published && !island.host_may_reference && !kernel.writable_executable;
}

bool failed_restore_rolls_back_and_keeps_island_published() {
    FakeKernel kernel{};
    g_kernel = &kernel;
    PreparedIsland island = make_prepared(kernel);
    kernel.fail_rx_calls = 3;
    const auto result = launcher::backend::install_count_island_with_ops(
        &island, kernel.site, kernel.old_word, &kFakeOps);
    return result == InstallError::kProtectionRestoreFailed && g_site_word == kernel.old_word &&
        island.host_may_reference && !island.published &&
        !island.host_page_may_be_wx && !kernel.writable_executable &&
        kernel.publish_calls == 2;
}

bool rollback_conflict_never_clobbers_competing_word() {
    FakeKernel kernel{};
    g_kernel = &kernel;
    PreparedIsland island = make_prepared(kernel);
    kernel.fail_rx_calls = 3;
    kernel.conflict_before_restore = true;
    const auto result = launcher::backend::install_count_island_with_ops(
        &island, kernel.site, kernel.old_word, &kFakeOps);
    return result == InstallError::kRollbackConflict && g_site_word != kernel.old_word &&
        island.host_may_reference && !island.published;
}

bool non_rx_host_page_is_rejected_before_permission_change() {
    FakeKernel kernel{};
    g_kernel = &kernel;
    PreparedIsland island = make_prepared(kernel);
    kernel.reject_page = true;
    const auto result = launcher::backend::install_count_island_with_ops(
        &island, kernel.site, kernel.old_word, &kFakeOps);
    return result == InstallError::kInvalidCallSitePlan && g_site_word == kernel.old_word &&
        kernel.protect_calls == 0 && !island.published && !island.host_may_reference;
}

bool persistent_restore_failure_is_reported_without_freeing_island() {
    FakeKernel kernel{};
    g_kernel = &kernel;
    PreparedIsland island = make_prepared(kernel);
    kernel.fail_rx_calls = 6;
    const auto result = launcher::backend::install_count_island_with_ops(
        &island, kernel.site, kernel.old_word, &kFakeOps);
    return result == InstallError::kHostPageLeftWritableExecutable &&
        g_site_word == kernel.old_word &&
        island.host_may_reference && !island.published &&
        island.host_page_may_be_wx && kernel.writable_executable;
}

bool silent_store_failure_is_not_published() {
    FakeKernel kernel{};
    g_kernel = &kernel;
    PreparedIsland island = make_prepared(kernel);
    kernel.silently_drop_store = true;
    const auto result = launcher::backend::install_count_island_with_ops(
        &island, kernel.site, kernel.old_word, &kFakeOps);
    return result == InstallError::kCallSiteChanged && g_site_word == kernel.old_word &&
        island.host_may_reference && !island.published &&
        !island.host_page_may_be_wx && !kernel.writable_executable;
}

bool call_site_overwrite_is_not_published() {
    FakeKernel kernel{};
    g_kernel = &kernel;
    PreparedIsland island = make_prepared(kernel);
    kernel.silently_drop_store = true;
    const auto result = launcher::backend::install_count_island_with_ops(
        &island, kernel.site, kernel.old_word, &kFakeOps);
    return result == InstallError::kCallSiteChanged && g_site_word == kernel.old_word &&
        island.host_may_reference && !island.published;
}

bool stale_site_after_successful_restore_is_not_published() {
    FakeKernel kernel{};
    g_kernel = &kernel;
    PreparedIsland island = make_prepared(kernel);
    kernel.keep_stale_after_successful_restore = true;
    const auto result = launcher::backend::install_count_island_with_ops(
        &island, kernel.site, kernel.old_word, &kFakeOps);
    return result == InstallError::kCallSiteChanged && g_site_word == kernel.old_word &&
        island.host_may_reference && !island.published &&
        !island.host_page_may_be_wx && !kernel.writable_executable;
}

bool concurrent_backend_install_is_rejected() {
    FakeKernel kernel{};
    g_kernel = &kernel;
    PreparedIsland island = make_prepared(kernel);
    g_nested_island = &island;
    g_nested_site = kernel.site;
    g_nested_old_word = kernel.old_word;
    g_nested_ops = &kFakeOps;
    g_nested_attempt = nested_install;
    const auto result = launcher::backend::install_count_island_with_ops(
        &island, kernel.site, kernel.old_word, &kFakeOps);
    g_nested_island = nullptr;
    g_nested_ops = nullptr;
    return result == InstallError::kNone && g_nested_result == InstallError::kInstallBusy &&
        g_site_word == kernel.new_word && !kernel.writable_executable;
}

}  // namespace

// Android CMake compiles this fault-injection harness as a contract object. The current
// prototype build does not execute test objects or link the backend into its shared library.
int main() {
    return success_publishes_one_word_and_restores_rx() &&
        writable_permission_failure_does_not_touch_site() && changed_site_is_not_overwritten() &&
        failed_restore_rolls_back_and_keeps_island_published() &&
        rollback_conflict_never_clobbers_competing_word() &&
        non_rx_host_page_is_rejected_before_permission_change() &&
        persistent_restore_failure_is_reported_without_freeing_island() &&
        silent_store_failure_is_not_published() &&
        call_site_overwrite_is_not_published() &&
        stale_site_after_successful_restore_is_not_published() &&
        concurrent_backend_install_is_rejected() ? 0 : 1;
}
