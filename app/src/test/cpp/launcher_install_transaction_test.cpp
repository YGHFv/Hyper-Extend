/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#include "launcher/install_transaction.h"

using namespace launcher;

static_assert(kInstallStateIdle == 0, "初始状态必须为 0");
static_assert(kInstallStateInFlight == 1, "在途状态必须为 1");
static_assert(kInstallStateInstalled == 2, "成功状态必须为 2");
static_assert(kInstallStateFailed == 3, "失败状态必须为 3");

int main() {
    uint32_t state = kInstallStateIdle;
    if (!claim_install_once(&state) || state != kInstallStateInFlight) return 1;
    if (claim_install_once(&state)) return 2;
    finish_install(&state, false);
    if (state != kInstallStateFailed || claim_install_once(&state)) return 3;

    void *original = reinterpret_cast<void *>(0x1000);
    clear_original_slot(&original);
    if (original != nullptr || original_published(&original)) return 4;
    __atomic_store_n(reinterpret_cast<uintptr_t *>(&original), 0x2000,
        __ATOMIC_RELEASE);
    if (!original_published(&original)) return 5;

    state = kInstallStateIdle;
    if (!claim_install_once(&state)) return 6;
    finish_install(&state, true);
    if (state != kInstallStateInstalled || claim_install_once(&state)) return 7;
    if (claim_install_once(nullptr) || original_published(nullptr)) return 8;
    clear_original_slot(nullptr);
    finish_install(nullptr, true);
    return 0;
}
