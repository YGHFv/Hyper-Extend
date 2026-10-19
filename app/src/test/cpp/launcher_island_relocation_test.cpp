/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#include "launcher/island_relocation.h"

using launcher::island::RelocationError;

constexpr uint32_t kAdrpX16 = 0x90000010U;
constexpr uint32_t kAddX16X16 = 0x91000210U;

static_assert(launcher::island::relocate_adrp_x16(kAdrpX16, 0x10000000U,
    0x10001000U).instruction == 0xB0000010U, "正向 ADRP 页偏移必须编码正确");
static_assert(launcher::island::relocate_adrp_x16(kAdrpX16, 0x10001000U,
    0x10000000U).instruction == 0xF0FFFFF0U, "负向 ADRP 页偏移必须符号扩展正确");
static_assert(launcher::island::relocate_adrp_x16(kAdrpX16, 0x10000000U,
    0x10000000U + (UINT64_C(1) << 32) - 0x1000U).ok(),
    "ADRP 正向边界内最后一页必须可达");
static_assert(launcher::island::relocate_adrp_x16(kAdrpX16, 0x10000000U,
    0x10000000U + (UINT64_C(1) << 32)).error == RelocationError::kOutOfRange,
    "ADRP 正向范围外必须拒绝");
static_assert(launcher::island::relocate_adrp_x16(0xD503201FU, 0x10000000U,
    0x10001000U).error == RelocationError::kBadInstruction,
    "非 ADRP 指令必须拒绝重定位");
static_assert(launcher::island::relocate_add_lo12_x16(kAddX16X16,
    0x12345678U).instruction == 0x9119E210U, "ADD 低 12 位必须正确重编码");
static_assert(launcher::island::relocate_add_lo12_x16(0x91000000U,
    0x12345678U).error == RelocationError::kBadInstruction,
    "目标寄存器不符的 ADD 必须拒绝重定位");

constexpr bool kIslandRelocatesAllSlots() {
    uint32_t words[16] = {
        0x100000BEU, 0x90000010U, 0x91000210U, 0xF9400211U,
        0xD61F0220U, 0x90000010U, 0x91000210U, 0x88DFFE11U,
        0x51001630U, 0x71003E1FU, 0x54000048U, 0x2A1103E0U,
        0x90000010U, 0x91000210U, 0xF940021EU, 0xD61F03C0U,
    };
    if (launcher::island::relocate_count_island(words, 16, 0x10000000U,
            0x10001000U, 0x10002000U, 0x10003000U) != RelocationError::kNone) return false;
    return words[1] == 0xB0000010U && words[2] == 0x91000210U &&
        words[5] == 0xD0000010U && words[6] == 0x91000210U &&
        words[12] == 0xF0000010U && words[13] == 0x91000210U;
}

static_assert(kIslandRelocatesAllSlots(), "岛式蹦床的三组数据槽必须按运行地址重定位");

constexpr bool kFailedRelocationDoesNotPartiallyModifyBuffer() {
    uint32_t words[16] = {
        0x100000BEU, 0x90000010U, 0x91000210U, 0xF9400211U,
        0xD61F0220U, 0x90000010U, 0x91000210U, 0x88DFFE11U,
        0x51001630U, 0x71003E1FU, 0x54000048U, 0x2A1103E0U,
        0x90000010U, 0x91000210U, 0xF940021EU, 0xD61F03C0U,
    };
    const uint32_t before = words[1];
    const auto result = launcher::island::relocate_count_island(words, 16,
        0x10000000U, 0x10001000U, 0x10002000U,
        0x10000000U + (UINT64_C(1) << 32));
    return result == RelocationError::kOutOfRange && words[1] == before;
}

static_assert(kFailedRelocationDoesNotPartiallyModifyBuffer(),
    "任一槽超出 ADRP 范围时不得留下部分重定位结果");

int main() {
    return 0;
}
