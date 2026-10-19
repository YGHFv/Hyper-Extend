/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
// 映射与 ELF 全部是模拟器内的合成数据，执行真实发现路径，不读取设备内存。
#include "../../main/cpp/launcher/count_backend.cpp"

namespace mapping_trial {
alignas(4096) unsigned char elf[12288]{};
char maps[2048]{};
size_t used = 0, cursor[8]{};
bool live[8]{};
unsigned callbacks = 0, violations = 0;

void append(const char *text) { while (*text) maps[used++] = *text++; }
void hex(uintptr_t value) {
    char digits[16];
    size_t size = 0;
    do { digits[size++] = "0123456789abcdef"[value & 15U]; value >>= 4; } while (value);
    while (size != 0) maps[used++] = digits[--size];
}
int observe(dl_phdr_info *image, size_t, void *) {
    ++callbacks;
    if (image->dlpi_addr != reinterpret_cast<uintptr_t>(elf)) ++violations;
    return 0;
}
}
extern "C" int open(const char *, int flags, ...) {
    using namespace mapping_trial;
    if (flags != (O_RDONLY | O_CLOEXEC)) ++violations;
    for (int fd = 3; fd < 8; ++fd) if (!live[fd]) {
        live[fd] = true; cursor[fd] = 0; return fd;
    }
    ++violations; return -1;
}
extern "C" ssize_t read(int fd, void *out, size_t count) {
    using namespace mapping_trial;
    if (fd < 3 || fd >= 8 || !live[fd]) { ++violations; return -1; }
    if (count > used - cursor[fd]) count = used - cursor[fd];
    for (size_t i = 0; i < count; ++i) static_cast<char *>(out)[i] = maps[cursor[fd]++];
    return count;
}
extern "C" int close(int fd) {
    using namespace mapping_trial;
    if (fd < 3 || fd >= 8 || !live[fd]) { ++violations; return -1; }
    live[fd] = false;
    return 0;
}

extern "C" __attribute__((visibility("default")))
int launcher_runtime_mapping_trial(int code) {
    using namespace mapping_trial;
    auto *header = reinterpret_cast<Elf64_Ehdr *>(elf);
    header->e_ident[0] = 0x7f; header->e_ident[1] = 'E';
    header->e_ident[2] = 'L'; header->e_ident[3] = 'F';
    header->e_ident[EI_CLASS] = ELFCLASS64; header->e_ident[EI_DATA] = ELFDATA2LSB;
    header->e_type = ET_DYN; header->e_machine = EM_AARCH64; header->e_version = EV_CURRENT;
    header->e_ehsize = sizeof(*header); header->e_phoff = sizeof(*header);
    header->e_phnum = 4; header->e_phentsize = sizeof(Elf64_Phdr);
    auto *phdr = reinterpret_cast<Elf64_Phdr *>(elf + sizeof(*header));
    phdr[0].p_type = PT_LOAD; phdr[0].p_flags = PF_R;
    phdr[0].p_offset = 0;
    phdr[0].p_filesz = phdr[0].p_memsz = 512;
    phdr[1].p_type = PT_LOAD; phdr[1].p_flags = PF_R | PF_X;
    phdr[1].p_vaddr = 4096;
    phdr[1].p_offset = 4096;
    phdr[1].p_filesz = phdr[1].p_memsz = 256;
    phdr[2].p_type = PT_LOAD; phdr[2].p_flags = PF_R | PF_W;
    phdr[2].p_vaddr = 8192; phdr[2].p_offset = 8192;
    phdr[2].p_filesz = phdr[2].p_memsz = 256;
    phdr[3].p_type = PT_NOTE; phdr[3].p_vaddr = 256; phdr[3].p_filesz = 32;
    elf[256] = 4; elf[260] = 16; elf[264] = 3;
    elf[268] = 'G'; elf[269] = 'N'; elf[270] = 'U';
    for (size_t i = 0; i < 16; ++i) elf[272 + i] = static_cast<unsigned char>(i);
    if (code == 1) elf[272] ^= 1;
    if (code == 5) phdr[3].p_vaddr = 65536;
    if (code == 6) header->e_phoff = 65536;
    if (code == 7) phdr[1].p_memsz = 1;
    if (code == 8) phdr[1].p_offset = 0x7000;
    const uintptr_t base = reinterpret_cast<uintptr_t>(elf);
    hex(base); append("-"); hex(base + 4096);
    append(" r--p 00005000 00:31 99 /product/priv-app/MiuiHome/MiuiHome.apk\n");
    if (code != 2 && code != 9) {
        hex(base + 4096); append("-"); hex(base + 8192);
        append(code == 4 ? " rwxp " : " r-xp ");
        append(code == 3 ? "00007000" : "00006000");
        append(" 00:31 99 ");
        append("/product/priv-app/MiuiHome/MiuiHome.apk\n");
    }
    if (code != 2 && code != 9) {
        hex(base + 8192); append("-"); hex(base + 12288);
        append(code == 10 ? " rw-p " : " r--p ");
        append("00007000 00:31 99 /product/priv-app/MiuiHome/MiuiHome.apk\n");
    }
    if (code == 9) append("malformed\n");
    const bool read_ok = launcher::backend::visit_loaded_launcher_images(observe, nullptr);
    if (violations != 0) return __LINE__;
    for (bool held : live) if (held) return __LINE__;
    if (code == 0 || code == 1 || code == 5 || code == 10) {
        if (!read_ok || callbacks != 1 || launcher::backend::launcher_image_probe_status() != 15) return __LINE__;
    } else if (callbacks != 0) return __LINE__;
    return 0;
}
