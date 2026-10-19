/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#pragma once

#include <stdint.h>

namespace launcher {

// 安装事务状态只允许单向迁移：空闲 -> 在途 -> 成功/失败。失败也进入终态，
// 避免后端部分写入或异常返回后被第二次配置查询重复触碰宿主代码页。
constexpr uint32_t kInstallStateIdle = 0;
constexpr uint32_t kInstallStateInFlight = 1;
constexpr uint32_t kInstallStateInstalled = 2;
constexpr uint32_t kInstallStateFailed = 3;

inline bool claim_install_once(uint32_t *state) {
    if (state == nullptr) return false;
    uint32_t expected = kInstallStateIdle;
    return __atomic_compare_exchange_n(state, &expected, kInstallStateInFlight, false,
        __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE);
}

inline void finish_install(uint32_t *state, bool installed) {
    if (state == nullptr) return;
    __atomic_store_n(state, installed ? kInstallStateInstalled : kInstallStateFailed,
        __ATOMIC_RELEASE);
}

// original 槽是后端唯一的发布点。安装前清空旧值，安装成功后只接受非空值；
// 失败路径由调用方负责回滚，不能把旧中继地址继续暴露给蹦床。
inline void clear_original_slot(void **slot) {
    if (slot != nullptr) __atomic_store_n(reinterpret_cast<uintptr_t *>(slot), 0,
        __ATOMIC_RELEASE);
}

inline bool original_published(void *const *slot) {
    return slot != nullptr && __atomic_load_n(reinterpret_cast<const uintptr_t *>(slot),
        __ATOMIC_ACQUIRE) != 0;
}

}  // 命名空间 launcher
