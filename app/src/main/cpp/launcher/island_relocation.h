/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#pragma once

#include <stddef.h>
#include <stdint.h>

namespace launcher {
namespace island {

enum class RelocationError : uint32_t {
    kNone = 0,
    kBadInstruction,
    kOutOfRange,
    kBadTemplateSize,
    kUnalignedIsland,
    kAddressOverflow,
};

struct RelocationResult {
    RelocationError error;
    uint32_t instruction;

    constexpr bool ok() const { return error == RelocationError::kNone; }
};

// 根据副本运行地址重新编码 ADRP x16。
constexpr RelocationResult relocate_adrp_x16(uint32_t instruction, uintptr_t pc,
                                              uintptr_t target) {
    if ((instruction & 0x9F00001FU) != 0x90000010U) {
        return {RelocationError::kBadInstruction, instruction};
    }
    const uintptr_t pc_page = pc >> 12;
    const uintptr_t target_page = target >> 12;
    uint64_t page_delta = 0;
    if (target_page >= pc_page) {
        page_delta = static_cast<uint64_t>(target_page - pc_page);
        if (page_delta >= (UINT64_C(1) << 20)) {
            return {RelocationError::kOutOfRange, instruction};
        }
    } else {
        page_delta = static_cast<uint64_t>(pc_page - target_page);
        if (page_delta > (UINT64_C(1) << 20)) {
            return {RelocationError::kOutOfRange, instruction};
        }
        page_delta = (UINT64_C(1) << 21) - page_delta;
    }
    const uint32_t immediate = static_cast<uint32_t>(page_delta) & 0x1FFFFFU;
    const uint32_t encoded = (instruction & ~0x60FFFFE0U) |
        ((immediate & 0x3U) << 29) | ((immediate >> 2) << 5);
    return {RelocationError::kNone, encoded};
}

// 重新编码岛式蹦床 `add x16, x16, #imm` 指令的低 12 位。
constexpr RelocationResult relocate_add_lo12_x16(uint32_t instruction, uintptr_t target) {
    if ((instruction & 0xFFC003FFU) != 0x91000210U) {
        return {RelocationError::kBadInstruction, instruction};
    }
    const uint32_t encoded = (instruction & ~0x003FFC00U) |
        (static_cast<uint32_t>(target & 0xFFFU) << 10);
    return {RelocationError::kNone, encoded};
}

// 重定位固定 64 字节岛式蹦床模板中的六条 ADRP/ADD 指令。
// 先验证全部编码，再修改暂存缓冲区，避免失败时留下部分结果。
constexpr RelocationError relocate_count_island(uint32_t *words, size_t word_count,
    uintptr_t island_address, uintptr_t original_slot, uintptr_t state_slot,
    uintptr_t continuation_slot) {
    if (words == nullptr || word_count != 16) return RelocationError::kBadTemplateSize;
    if ((island_address & 3U) != 0) return RelocationError::kUnalignedIsland;
    if (island_address > UINTPTR_MAX - 52U) return RelocationError::kAddressOverflow;

    constexpr size_t offsets[] = {4, 8, 20, 24, 48, 52};
    const uintptr_t targets[] = {original_slot, original_slot, state_slot, state_slot,
                                 continuation_slot, continuation_slot};
    RelocationResult encoded[6]{};
    for (size_t index = 0; index < 6; ++index) {
        const size_t offset = offsets[index];
        const uintptr_t pc = island_address + offset;
        encoded[index] = (index & 1U) == 0
            ? relocate_adrp_x16(words[offset / 4], pc, targets[index])
            : relocate_add_lo12_x16(words[offset / 4], targets[index]);
        if (!encoded[index].ok()) return encoded[index].error;
    }
    for (size_t index = 0; index < 6; ++index) {
        words[offsets[index] / 4] = encoded[index].instruction;
    }
    return RelocationError::kNone;
}

}  // namespace island
}  // namespace launcher
