/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
// 模拟器提供 ELF 映像；执行与生产完全相同的解析器，不运行宿主指令。
#include "../../main/cpp/launcher/hook_points.h"

extern "C" void *memset(void *p, int value, size_t size) {
    for (size_t i = 0; i < size; ++i) static_cast<unsigned char *>(p)[i] = value;
    return p;
}
extern "C" void *memcpy(void *p, const void *q, size_t size) {
    for (size_t i = 0; i < size; ++i)
        static_cast<unsigned char *>(p)[i] = static_cast<const unsigned char *>(q)[i];
    return p;
}

extern "C" __attribute__((visibility("default")))
uint32_t launcher_hook_points_trial(uintptr_t base, launcher::HookPoints *out) {
    const auto *header = reinterpret_cast<const Elf64_Ehdr *>(base);
    dl_phdr_info image{};
    image.dlpi_addr = base;
    image.dlpi_phdr = reinterpret_cast<const Elf64_Phdr *>(base + header->e_phoff);
    image.dlpi_phnum = header->e_phnum;
    if (!launcher::points::resolve(image, out)) return 0;
    uint32_t mask = 0;
    for (size_t i = 0; i < launcher::kHookPointCount; ++i) {
        // 两个闭包入口只保留诊断地址，不属于生产安装 mask。
        if (i == launcher::kSharePrimaryEntry || i == launcher::kShareSecondaryEntry)
            continue;
        if (out->address[i] != 0) mask |= 1U << i;
    }
    return mask;
}
