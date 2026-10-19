/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#pragma once

#include <link.h>
#include <stdint.h>
#include <stddef.h>

namespace launcher {

enum HookPoint : size_t {
    kCountCall, kSharePrimaryCall, kSharePrimaryEntry, kShareSecondaryCall,
    kShareSecondaryEntry, kFreeformCall, kShareValid, kFreeformValid,
    kHookPointCount,
};

struct HookPoints {
    uintptr_t address[kHookPointCount]{};
    uint32_t original[kHookPointCount]{};
    uintptr_t share_valid_exits[16]{};
    uint32_t share_valid_exit_words[16]{};
    size_t share_valid_exit_count = 0;
    uintptr_t freeform_valid_exits[16]{};
    uint32_t freeform_valid_exit_words[16]{};
    size_t freeform_valid_exit_count = 0;
};

namespace points {

struct Word {
    uint32_t value;
    uint32_t mask = UINT32_MAX;
};

// 只约束参数搬运、调用类型与结果消费；BL 位移、对象池槽位不作为版本指纹。
constexpr Word kCount[] = {
    {0xa9bf79fd}, {0xaa0f03fd}, {0xd10241ef}, {0xaa0103e0}, {0xf81f83a1},
    {0x94000000, 0xfc000000}, {0xaa0003e2}, {0xf85f83a1}, {0xf81f03a2},
    {0xf85ff020}, {0xd34c7c00}, {0xf90001e1},
};
constexpr Word kShare[] = {
    {0xa9bf79fd}, {0xaa0f03fd}, {0xd10041ef}, {0xaa0103e0}, {0xaa0203e3},
    {0xf81f83a2}, {0xaa1603e1}, {0x94000000, 0xfc000000}, {0xaa0003e1},
    {0xf85f83a3}, {0x91400362, 0xffc003ff}, {0xf9400042, 0xffc003ff},
    {0xf81f03a0}, {0x94000000, 0xfc000000}, {0xf85f03a1}, {0xf85f83a3},
    {0x91400362, 0xffc003ff}, {0xf9400042, 0xffc003ff},
};
// 两个分享闭包的入口都先建立 fp 帧，再从调用者帧取外层对象；外层对象 +7
// 指向阶段对象，阶段对象 +0xf 才是分享包名。主闭包还带免责声明分支，次闭包
// 直接分享。这里只固定跨版本稳定的取参、字段和调用结构，不把 BL 位移当作
// 版本指纹。
constexpr Word kSharePrimaryEntry[] = {
    {0xa9bf79fd}, {0xaa0f03fd}, {0xf9401ba0}, {0xb8400011, 0xffc003ff},
    {0x8b1c0021}, {0xf9400ba0}, {0x37180060},
};
constexpr Word kShareSecondaryEntry[] = {
    {0xa9bf79fd}, {0xaa0f03fd}, {0xf9400ba0}, {0xb8400011, 0xffc003ff},
    {0x8b1c0021}, {0xb8400010, 0xffc003ff}, {0x8b1c0000}, {0xb8400012, 0xffc003ff},
    {0x8b1c0042}, {0x6b16005f}, {0x54000001, 0xff00001f}, {0xaa1603e0},
    {0x14000002}, {0xb8400040, 0xffc003ff}, {0x8b1c0000},
};
constexpr Word kSharePrimaryCallTail[] = {{0xaa1603e0}, {0xaa1d03ef}, {0xa8c179fd}, {0xd65f03c0}};
constexpr Word kShareSecondaryCallTail[] = {
    {0xaa1603e0}, {0xaa1d03ef}, {0xa8c179fd}, {0xd65f03c0},
};
constexpr Word kShareValid[] = {
    {0xa9bf79fd}, {0xaa0f03fd}, {0xd10401ef}, {0xf81f83b6},
    {0xf81283a1}, {0xf81203a2}, {0xf81183a3}, {0xf81103a5}, {0xd2800041},
    {0x94000000, 0xfc000000}, {0xaa0003e1}, {0xf85183a0}, {0xf81083a1},
    {0xb800f020}, {0xf9400360, 0xffc003ff}, {0x94000000, 0xfc000000},
};
constexpr Word kFreeform[] = {
    {0xa9bf79fd}, {0xaa0f03fd}, {0xd100a1ef}, {0xf81f83b6},
    {0xf81f03a1}, {0xf81e83a2}, {0xf9400360, 0xffc003ff},
    {0x94000000, 0xfc000000}, {0xf85e83a0}, {0xb841f002}, {0x8b1c8042},
    {0xf81d83a2}, {0x6b16005f},
};
constexpr Word kFreeformValid[] = {
    {0xa9bf79fd}, {0xaa0f03fd}, {0xd10101ef}, {0xf81f83b6},
    {0xf81f03a1}, {0xaa0203f0}, {0xaa0103e2}, {0xaa1003e1},
    {0xf81e83a1}, {0xf81e03a3}, {0xf81d83a5},
    {0xf9400360, 0xffc003ff}, {0x94000000, 0xfc000000},
};
constexpr Word kAsyncInit[] = {
    {0xa9bf79fd}, {0xaa0f03fd}, {0xf9402b64}, {0xf81f8de0},
    {0xf940035e, 0xffc003ff}, {0xd63f03c0}, {0xaa1d03ef}, {0xa8c179fd},
    {0xf81f83a0}, {0xd65f03c0},
};
constexpr Word kAsyncReturn[] = {
    {0xf85f83a2}, {0xaa1d03ef}, {0xa8c179fd}, {0xa9bf79fd}, {0xaa0f03fd},
    {0xa9bf09e0}, {0xf940035e, 0xffc003ff}, {0xd63f03c0}, {0xaa1d03ef},
    {0xa8c179fd}, {0xd65f03c0},
};

inline uint32_t load(uintptr_t address) {
    return __atomic_load_n(reinterpret_cast<const uint32_t *>(address), __ATOMIC_ACQUIRE);
}

inline bool executable(const dl_phdr_info &image, uintptr_t address, size_t bytes) {
    if ((address & 3U) != 0 || bytes == 0 || address > UINTPTR_MAX - bytes) return false;
    size_t owners = 0;
    for (size_t i = 0; i < image.dlpi_phnum; ++i) {
        const auto &p = image.dlpi_phdr[i];
        if (p.p_type != PT_LOAD || p.p_flags != (PF_R | PF_X) ||
            p.p_filesz > p.p_memsz || p.p_vaddr > UINTPTR_MAX - p.p_filesz ||
            image.dlpi_addr > UINTPTR_MAX - (p.p_vaddr + p.p_filesz)) continue;
        const uintptr_t start = image.dlpi_addr + p.p_vaddr;
        if (start <= address && address + bytes <= start + p.p_filesz) ++owners;
    }
    return owners == 1;
}

template <size_t N>
inline bool match(const dl_phdr_info &image, uintptr_t at, const Word (&words)[N]) {
    if (!executable(image, at, N * 4)) return false;
    for (size_t i = 0; i < N; ++i)
        if ((load(at + i * 4) & words[i].mask) != words[i].value) return false;
    return true;
}

inline uintptr_t branch_target(uintptr_t at, uint32_t word) {
    if ((word & 0x7c000000U) != 0x14000000U) return 0;
    int64_t delta = static_cast<int64_t>(word & 0x03ffffffU);
    if ((delta & 0x02000000) != 0) delta -= 0x04000000;
    delta *= 4;
    if (delta < 0 && at < static_cast<uintptr_t>(-delta)) return 0;
    if (delta >= 0 && at > UINTPTR_MAX - static_cast<uintptr_t>(delta)) return 0;
    return delta < 0 ? at - static_cast<uintptr_t>(-delta) : at + static_cast<uintptr_t>(delta);
}

inline bool count_abi(const dl_phdr_info &image, uintptr_t start) {
    constexpr Word getter[] = {{0xa9bf79fd}, {0xaa0f03fd}, {0xd10021ef}, {0xf9403f40}};
    constexpr Word raw_return[] = {{0xd2800080}, {0xaa1d03ef}, {0xa8c179fd}, {0xd65f03c0}};
    constexpr Word comparison[] = {{0x93417c01}, {0xf85f03a3}, {0xeb03003f}};
    const uintptr_t target = branch_target(start + 20, load(start + 20));
    if (!match(image, target, getter)) return false;
    bool returns_raw = false, consumes_raw = false;
    for (size_t i = 16; i < 512 && executable(image, target + i, 16); i += 4) {
        if (load(target + i) == 0xa9bf79fdU) break;
        if (match(image, target + i, raw_return)) returns_raw = true;
    }
    for (size_t i = 48; i < 128 && executable(image, start + i, 12); i += 4) {
        if (load(start + i) == 0xd65f03c0U) break;
        if (match(image, start + i, comparison)) consumes_raw = true;
    }
    return returns_raw && consumes_raw;
}

template <size_t N>
inline bool function_contains(const dl_phdr_info &image, uintptr_t start, const Word (&words)[N]) {
    for (size_t i = 4; i < 2048 && executable(image, start + i, N * 4); i += 4) {
        if (load(start + i) == 0xa9bf79fdU) break;
        if (match(image, start + i, words)) return true;
    }
    return false;
}

template <size_t N>
inline bool collect_false_return_sites(const dl_phdr_info &image, uintptr_t start,
                                       uintptr_t (&sites)[N], uint32_t (&words)[N],
                                       size_t *count) {
    if (count == nullptr) return false;
    *count = 0;
    uintptr_t return_thunk = 0;
    for (size_t offset = 0; offset < 2048 && executable(image, start + offset, 8); offset += 4) {
        const uintptr_t at = start + offset;
        const uint32_t word = load(at);
        if (offset != 0 && word == 0xa9bf79fdU) break;
        if (word != 0x9100c2c0U) continue;  // ADD X0, X22, #0x30：Dart false
        const uint32_t next = load(at + 4);
        const uintptr_t target = branch_target(at + 4, next);
        if (target == 0 || (next & 0xfc000000U) != 0x14000000U ||
            !executable(image, target, 4)) continue;
        if (return_thunk != 0 && target != return_thunk) return false;
        return_thunk = target;
        if (*count == N) return false;
        sites[*count] = at;
        words[*count] = word;
        ++*count;
    }
    return *count != 0;
}

inline bool resolve(const dl_phdr_info &image, HookPoints *out) {
    if (out == nullptr || image.dlpi_phdr == nullptr || image.dlpi_phnum == 0 ||
        image.dlpi_phnum > 64) return false;
    for (size_t index = 0; index < kHookPointCount; ++index) {
        out->address[index] = 0;
        out->original[index] = 0;
    }
    for (size_t index = 0; index < 16; ++index) {
        out->share_valid_exits[index] = 0;
        out->share_valid_exit_words[index] = 0;
        out->freeform_valid_exits[index] = 0;
        out->freeform_valid_exit_words[index] = 0;
    }
    out->share_valid_exit_count = 0;
    out->freeform_valid_exit_count = 0;
    uintptr_t starts[7]{};
    size_t matches[7]{};
    size_t total = 0;
    for (size_t s = 0; s < image.dlpi_phnum; ++s) {
        const auto &p = image.dlpi_phdr[s];
        if (p.p_type != PT_LOAD || (p.p_flags & PF_X) == 0) continue;
        if (p.p_flags != (PF_R | PF_X) || (p.p_vaddr & 3U) != 0 ||
            image.dlpi_addr > UINTPTR_MAX - p.p_vaddr ||
            (p.p_filesz & 3U) != 0 || p.p_filesz > 64 * 1024 * 1024U - total ||
            !executable(image, image.dlpi_addr + p.p_vaddr, p.p_filesz)) return false;
        total += p.p_filesz;
        const uintptr_t start = image.dlpi_addr + p.p_vaddr;
        for (size_t i = 0; i < p.p_filesz; i += 4) {
            const uintptr_t at = start + i;
            if (load(at) != 0xa9bf79fdU) continue;
            // 小窗与多开的参数序言相同；再核对 Intent 标志与三参数状态检查，避免误挂同族函数。
            constexpr Word small_window_flags[] = {{0xd2a20400}, {0xf800f020}};
            constexpr Word running_state_args[] = {
                {0xf85f03a1}, {0xf85e83a2}, {0xf85e03a3},
                {0x94000000, 0xfc000000}, {0x14000000, 0xfc000000},
            };
            const bool found[] = {
                match(image, at, kCount) && count_abi(image, at),
                match(image, at, kShare),
                match(image, at, kFreeform) && function_contains(image, at, small_window_flags),
                match(image, at, kShareValid),
                match(image, at, kFreeformValid) && function_contains(image, at, running_state_args),
                match(image, at, kSharePrimaryEntry),
                match(image, at, kShareSecondaryEntry),
            };
            for (size_t n = 0; n < 7; ++n)
                if (found[n]) { starts[n] = at; ++matches[n]; }
        }
    }
    if (total == 0) return false;
    if (matches[0] == 1) out->address[kCountCall] = starts[0] + 20;
    size_t callers[3]{};
    for (size_t s = 0; s < image.dlpi_phnum; ++s) {
        const auto &p = image.dlpi_phdr[s];
        if (p.p_type != PT_LOAD || p.p_flags != (PF_R | PF_X)) continue;
        const uintptr_t start = image.dlpi_addr + p.p_vaddr;
        for (size_t i = 0; i < p.p_filesz; i += 4) {
            const uintptr_t at = start + i;
            const uint32_t word = load(at);
            if ((word & 0xfc000000U) != 0x94000000U) continue;
            const uintptr_t target = branch_target(at, word);
            if (matches[0] == 1 && target == branch_target(starts[0] + 20, load(starts[0] + 20)))
                ++callers[0];
            if (matches[1] == 1 && target == starts[1]) {
                ++callers[1];
                constexpr Word args[] = {{0xb840f020}, {0x8b1c8000}, {0xaa0003e1}};
                if (at < 12 || !match(image, at - 12, args) || !executable(image, at + 4, 16)) continue;
                if ((load(at + 4) & 0xfc000000U) == 0x14000000U)
                    out->address[kSharePrimaryCall] = at;
                if (match(image, at + 4, kSharePrimaryCallTail))
                    out->address[kShareSecondaryCall] = at;
            }
            if (matches[2] == 1 && target == starts[2]) {
                ++callers[2];
                constexpr Word args[] = {{0xf85903a1}, {0xaa0003e2}};
                constexpr Word result[] = {{0xaa0003e1}, {0xf81883a1}, {0x94000000, 0xfc000000}};
                if (at >= 8 && match(image, at - 8, args) && match(image, at + 4, result))
                    out->address[kFreeformCall] = at;
            }
        }
    }
    // 内部调用点与入口是两组独立证据：入口用于实际接管闭包，内部调用点只证明
    // 这个闭包确实通向 shareApk。主、次内部调用点必须各自唯一，不能因为两个
    // 闭包共用一个 shareApk 目标就把 caller 总数误当成重复。
    if (matches[1] != 1 || callers[1] != 2 ||
        out->address[kSharePrimaryCall] == 0 || out->address[kShareSecondaryCall] == 0) {
        out->address[kSharePrimaryCall] = 0;
        out->address[kShareSecondaryCall] = 0;
    }
    if (callers[0] != 1) out->address[kCountCall] = 0;
    if (callers[2] != 1) out->address[kFreeformCall] = 0;
    // 异步 isValid 的入口只用于识别函数；实际挂载点是其返回 Dart false 并跳入
    // 共享完成蹦床的指令。匹配失败时清空入口，安装侧必须 fail-closed。
    // points 命名空间内的 kShareValid/kFreeformValid 是模板数组；输出槽须显式
    // 限定到 HookPoint 枚举，避免数组下标解析成 Word[]。
    if (matches[3] == 1) {
        out->address[launcher::kShareValid] = starts[3];
        if (!collect_false_return_sites(image, starts[3], out->share_valid_exits,
                out->share_valid_exit_words, &out->share_valid_exit_count))
            out->address[launcher::kShareValid] = 0;
    }
    if (matches[4] == 1) {
        out->address[launcher::kFreeformValid] = starts[4];
        if (!collect_false_return_sites(image, starts[4], out->freeform_valid_exits,
                out->freeform_valid_exit_words, &out->freeform_valid_exit_count))
            out->address[launcher::kFreeformValid] = 0;
    }
    // 入口只在自身唯一命中和内部调用结构也完整时启用；否则安装计划 fail-closed。
    if (matches[5] == 1 && out->address[kSharePrimaryCall] != 0)
        out->address[launcher::kSharePrimaryEntry] = starts[5];
    if (matches[6] == 1 && out->address[kShareSecondaryCall] != 0)
        out->address[launcher::kShareSecondaryEntry] = starts[6];
    bool any = false;
    for (size_t i = 0; i < kHookPointCount; ++i) {
        if (out->address[i] == 0) continue;
        if (!executable(image, out->address[i], 4)) return false;
        out->original[i] = load(out->address[i]);
        any = true;
    }
    return any;
}

}  // 命名空间 points
}  // 命名空间 launcher
