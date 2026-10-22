/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#include "count_backend.h"
#include "host_image_contract.h"

#include "island_relocation.h"
#include "singleword_publish.h"

#include <fcntl.h>
#include <sys/mman.h>
#include <unistd.h>

namespace launcher {
namespace backend {
namespace {

constexpr size_t kIslandBytes = 64;
constexpr size_t kDirectIslandBytes = 24;
constexpr size_t kValidityExitIslandBytes = 32;
constexpr size_t kMenuContinuationBytes = 32;
constexpr uintptr_t kSearchStride = 64U * 1024U;
constexpr uintptr_t kSearchLimit = static_cast<uintptr_t>(1) << 27;
// 只串行化本后端自己的事务；它不能替代对其它模块或宿主线程的实机协调。
bool g_install_busy = false;
uint32_t g_image_probe = 0;

class InstallGuard {
public:
    InstallGuard() : held_(!__atomic_test_and_set(&g_install_busy, __ATOMIC_ACQUIRE)) {}
    ~InstallGuard() {
        if (held_) __atomic_clear(&g_install_busy, __ATOMIC_RELEASE);
    }
    InstallGuard(const InstallGuard &) = delete;
    InstallGuard &operator=(const InstallGuard &) = delete;

    bool held() const { return held_; }

private:
    bool held_;
};

bool valid_page_size(long value) {
    return value >= 4096 && value <= (1L << 20) &&
        (static_cast<unsigned long>(value) & (static_cast<unsigned long>(value) - 1UL)) == 0;
}

bool host_call_page_is_rx(uintptr_t page_start, size_t page_size);

bool restore_rx(void *page, size_t page_size, const InstallOps &ops) {
    for (uint32_t attempt = 0; attempt < 3; ++attempt) {
        if (ops.protect(page, page_size, PROT_READ | PROT_EXEC) == 0) return true;
    }
    return false;
}

long real_page_size() { return sysconf(_SC_PAGESIZE); }
bool real_page_is_rx(uintptr_t start, size_t size) { return host_call_page_is_rx(start, size); }
int real_protect(void *page, size_t size, int protection) {
    return mprotect(page, size, protection);
}
uint32_t real_load_word(const uint32_t *site) {
    return __atomic_load_n(site, __ATOMIC_ACQUIRE);
}
void real_store_word(uint32_t *site, uint32_t word) {
    singleword::store_release(site, word);
}
bool real_compare_exchange_word(uint32_t *site, uint32_t *expected, uint32_t desired) {
    return __atomic_compare_exchange_n(site, expected, desired, false,
        __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE);
}
void real_publish_code(void *address, size_t bytes) {
    singleword::publish_code(address, bytes);
}

const InstallOps kRealInstallOps{real_page_size, real_page_is_rx, real_protect,
    real_load_word, real_store_word, real_compare_exchange_word, real_publish_code};

bool parse_hex(const char *begin, const char *end, uintptr_t *value) {
    if (begin == nullptr || end == nullptr || value == nullptr || begin == end) return false;
    uintptr_t result = 0;
    for (const char *cursor = begin; cursor != end; ++cursor) {
        const char c = *cursor;
        uint32_t digit = 0;
        if (c >= '0' && c <= '9') digit = static_cast<uint32_t>(c - '0');
        else if (c >= 'a' && c <= 'f') digit = static_cast<uint32_t>(c - 'a' + 10);
        else if (c >= 'A' && c <= 'F') digit = static_cast<uint32_t>(c - 'A' + 10);
        else return false;
        if (result > (UINTPTR_MAX - digit) / 16U) return false;
        result = result * 16U + digit;
    }
    *value = result;
    return true;
}

bool parse_decimal(const char *begin, const char *end, uint64_t *value) {
    if (begin == nullptr || end == nullptr || value == nullptr || begin == end) return false;
    uint64_t result = 0;
    for (const char *cursor = begin; cursor != end; ++cursor) {
        if (*cursor < '0' || *cursor > '9') return false;
        const uint32_t digit = static_cast<uint32_t>(*cursor - '0');
        if (result > (UINT64_MAX - digit) / 10U) return false;
        result = result * 10U + digit;
    }
    *value = result;
    return true;
}

struct MapEntry {
    uintptr_t start;
    uintptr_t end;
    uintptr_t file_offset;
    uint64_t device;
    uint64_t inode;
    char permissions[4];
    const char *path;
    size_t path_size;
};

bool parse_maps_line(const char *line, size_t length, MapEntry *entry) {
    if (line == nullptr || length == 0 || entry == nullptr) return false;
    const char *limit = line + length;
    const char *dash = line;
    while (dash < limit && *dash != '-') ++dash;
    const char *space = dash;
    while (space < limit && *space != ' ') ++space;
    uintptr_t start = 0, end = 0;
    if (dash == limit || space == limit || !parse_hex(line, dash, &start) ||
        !parse_hex(dash + 1, space, &end) || start >= end ||
        static_cast<size_t>(limit - space) < 6) return false;
    const char *permissions = space + 1;
    if (permissions[4] != ' ') return false;
    const char *offset_start = permissions + 5;
    const char *offset_end = offset_start;
    while (offset_end < limit && *offset_end != ' ') ++offset_end;
    if (offset_end == limit) return false;
    uintptr_t file_offset = 0;
    if (!parse_hex(offset_start, offset_end, &file_offset)) return false;
    const char *device_start = offset_end + 1;
    const char *device_end = device_start;
    while (device_end < limit && *device_end != ' ') ++device_end;
    if (device_end == limit) return false;
    const char *colon = device_start;
    while (colon < device_end && *colon != ':') ++colon;
    uintptr_t major = 0, minor = 0;
    if (colon == device_end || !parse_hex(device_start, colon, &major) ||
        !parse_hex(colon + 1, device_end, &minor) || major > UINT32_MAX || minor > UINT32_MAX) {
        return false;
    }
    const char *inode_start = device_end + 1;
    const char *inode_end = inode_start;
    while (inode_end < limit && *inode_end != ' ') ++inode_end;
    uint64_t inode = 0;
    if (inode_end == limit || !parse_decimal(inode_start, inode_end, &inode)) return false;
    const char *path = inode_end;
    while (path < limit && *path == ' ') ++path;
    entry->start = start;
    entry->end = end;
    entry->file_offset = file_offset;
    entry->device = (static_cast<uint64_t>(major) << 32) | minor;
    entry->inode = inode;
    for (size_t i = 0; i < sizeof(entry->permissions); ++i) entry->permissions[i] = permissions[i];
    entry->path = path;
    entry->path_size = static_cast<size_t>(limit - path);
    return true;
}

template <typename Visitor>
bool visit_process_maps(Visitor visitor) {
    const int fd = open("/proc/self/maps", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    char chunk[256];
    char line[1024];
    size_t line_size = 0;
    bool ok = true;
    for (;;) {
        const ssize_t count = read(fd, chunk, sizeof(chunk));
        if (count < 0) { ok = false; break; }
        if (count == 0) break;
        for (ssize_t i = 0; i < count; ++i) {
            const char c = chunk[i];
            if (c == '\n') {
                MapEntry entry{};
                if (!parse_maps_line(line, line_size, &entry) || !visitor(entry)) {
                    ok = false;
                    break;
                }
                line_size = 0;
            } else if (line_size < sizeof(line)) {
                line[line_size++] = c;
            } else {
                ok = false;
                break;
            }
        }
        if (!ok) break;
    }
    if (ok && line_size != 0) {
        MapEntry entry{};
        ok = parse_maps_line(line, line_size, &entry) && visitor(entry);
    }
    const bool closed = close(fd) == 0;
    return ok && closed;
}

bool host_path(const MapEntry &entry) {
    // 内核 maps 通常只显示 APK 容器名，不包含链接器的 !/lib/... 条目后缀。
    // 这里只校验页面所属文件；入口另行核对 Hook 点 ABI、PT_LOAD 与每个调用点机器字。
    return launcher_apk_path(entry.path, entry.path_size) ||
        launcher_library_path(entry.path, entry.path_size);
}

struct HostMapProbe {
    uintptr_t page_start;
    uintptr_t page_end;
    MapEntry owner{};
    uint32_t owners = 0;
};

bool mapped_file_offset(const MapEntry &entry, uintptr_t address, uintptr_t *file_offset) {
    if (file_offset == nullptr || address < entry.start || address >= entry.end) return false;
    if (entry.file_offset > UINTPTR_MAX - (address - entry.start)) return false;
    *file_offset = entry.file_offset + (address - entry.start);
    return true;
}

bool find_host_page(const MapEntry &entry, void *opaque) {
    auto *probe = static_cast<HostMapProbe *>(opaque);
    if (entry.start > probe->page_start || entry.end < probe->page_end) return true;
    ++probe->owners;
    if (probe->owners != 1 || entry.permissions[0] != 'r' || entry.permissions[1] == 'w' ||
        entry.permissions[2] != 'x' || entry.permissions[3] != 'p' ||
        entry.inode == 0 || !host_path(entry)) return true;
    probe->owner = entry;
    return true;
}

struct AliasProbe {
    const MapEntry *owner;
    uintptr_t file_start;
    uintptr_t file_end;
    uint32_t aliases = 0;
    bool writable = false;
};

bool reject_writable_alias(const MapEntry &entry, void *opaque) {
    auto *probe = static_cast<AliasProbe *>(opaque);
    if (entry.device != probe->owner->device || entry.inode != probe->owner->inode ||
        entry.file_offset > UINTPTR_MAX - (entry.end - entry.start)) return true;
    const uintptr_t mapped_end = entry.file_offset + (entry.end - entry.start);
    if (entry.file_offset >= probe->file_end || mapped_end <= probe->file_start) return true;
    ++probe->aliases;
    if (entry.permissions[1] == 'w') probe->writable = true;
    return true;
}

bool host_call_page_is_rx(uintptr_t page_start, size_t page_size) {
    if (page_start > UINTPTR_MAX - page_size) return false;
    HostMapProbe probe{page_start, page_start + page_size, {}, 0};
    if (!visit_process_maps([&probe](const MapEntry &entry) { return find_host_page(entry, &probe); }) ||
        probe.owners != 1 || probe.owner.inode == 0) return false;
    uintptr_t file_start = 0;
    if (!mapped_file_offset(probe.owner, page_start, &file_start)) return false;
    if (file_start > UINTPTR_MAX - page_size) return false;
    AliasProbe aliases{&probe.owner, file_start, file_start + page_size, 0, false};
    return visit_process_maps([&aliases](const MapEntry &entry) {
        return reject_writable_alias(entry, &aliases);
    }) && aliases.aliases == 1 && !aliases.writable;
}

void *map_near(uintptr_t site, size_t page_size) {
    for (uintptr_t distance = kSearchStride; distance < kSearchLimit; distance += kSearchStride) {
        const uintptr_t candidates[] = {
            site <= UINTPTR_MAX - distance ? (site + distance) & ~(page_size - 1U) : 0,
            site > distance ? (site - distance) & ~(page_size - 1U) : 0,
        };
        for (uintptr_t hint : candidates) {
            if (hint == 0) continue;
            void *mapping = mmap(reinterpret_cast<void *>(hint), page_size,
                PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
            if (mapping == MAP_FAILED) continue;
            const uintptr_t address = reinterpret_cast<uintptr_t>(mapping);
            const int64_t delta = static_cast<int64_t>(address) - static_cast<int64_t>(site);
            if (singleword::aligned4(address) && singleword::branch_delta_ok(delta)) return mapping;
            munmap(mapping, page_size);
        }
    }
    return MAP_FAILED;
}

}  // namespace

uint32_t launcher_image_probe_status() {
    return __atomic_load_n(&g_image_probe, __ATOMIC_ACQUIRE);
}

bool visit_loaded_launcher_images(int (*callback)(dl_phdr_info *, size_t, void *), void *opaque) {
    if (callback == nullptr) return false;
    const bool read = visit_process_maps([&](const MapEntry &owner) {
        if (!host_path(owner)) return true;
        __atomic_fetch_or(&g_image_probe, 1U, __ATOMIC_RELAXED);
        if (owner.inode == 0 || owner.permissions[0] != 'r' ||
            owner.permissions[1] == 'w' || owner.permissions[3] != 'p' ||
            owner.end - owner.start < sizeof(ElfW(Ehdr))) return true;
        const auto *header = reinterpret_cast<const ElfW(Ehdr) *>(owner.start);
        if (header->e_ident[0] != 0x7f || header->e_ident[1] != 'E' ||
            header->e_ident[2] != 'L' || header->e_ident[3] != 'F' ||
            header->e_ident[EI_CLASS] != ELFCLASS64 || header->e_ident[EI_DATA] != ELFDATA2LSB ||
            header->e_type != ET_DYN || header->e_machine != EM_AARCH64 ||
            header->e_version != EV_CURRENT || header->e_ehsize != sizeof(ElfW(Ehdr)) ||
            header->e_phentsize != sizeof(ElfW(Phdr)) || header->e_phnum == 0 ||
            header->e_phnum > 16 || (header->e_phoff & 7U) != 0 ||
            header->e_phoff > owner.end - owner.start ||
            header->e_phnum * sizeof(ElfW(Phdr)) > owner.end - owner.start - header->e_phoff) return true;
        __atomic_fetch_or(&g_image_probe, 2U, __ATOMIC_RELAXED);
        dl_phdr_info image{};
        image.dlpi_addr = owner.start;
        image.dlpi_name = "libapp.so";
        image.dlpi_phdr = reinterpret_cast<const ElfW(Phdr) *>(owner.start + header->e_phoff);
        image.dlpi_phnum = header->e_phnum;
        bool header_load = false;
        for (size_t i = 0; i < image.dlpi_phnum; ++i) {
            const auto &p = image.dlpi_phdr[i];
            if (p.p_type == PT_LOAD && p.p_offset == 0 && p.p_vaddr == 0 &&
                (p.p_flags & PF_R) != 0 && p.p_filesz >= header->e_phoff +
                    header->e_phnum * sizeof(ElfW(Phdr))) header_load = true;
        }
        if (!header_load) return true;
        __atomic_fetch_or(&g_image_probe, 4U, __ATOMIC_RELAXED);
        for (size_t i = 0; i < image.dlpi_phnum; ++i) {
            const auto &p = image.dlpi_phdr[i];
            if (p.p_type != PT_LOAD || p.p_filesz == 0) continue;
            if (p.p_filesz > p.p_memsz || p.p_vaddr > UINTPTR_MAX - p.p_filesz ||
                owner.start > UINTPTR_MAX - (p.p_vaddr + p.p_filesz) ||
                owner.file_offset > UINTPTR_MAX - p.p_offset ||
                owner.file_offset + p.p_offset > UINTPTR_MAX - p.p_filesz) return true;
            const uintptr_t begin = owner.start + p.p_vaddr, end = begin + p.p_filesz;
            uintptr_t cursor = begin;
            // 同一文件的每个 PT_LOAD 字节都必须存在，且虚拟地址与 APK 条目偏移逐段相符。
            const bool checked = visit_process_maps([&](const MapEntry &part) {
                if (part.start > cursor || part.end <= cursor || cursor >= end) return true;
                if (part.inode != owner.inode || part.device != owner.device ||
                    ((p.p_flags & PF_R) != 0 && part.permissions[0] != 'r') ||
                    ((p.p_flags & PF_W) == 0 && part.permissions[1] == 'w') ||
                    part.permissions[3] != 'p' ||
                    ((p.p_flags & PF_X) != 0 && part.permissions[2] != 'x') ||
                    part.file_offset > UINTPTR_MAX - (cursor - part.start) ||
                    part.file_offset + cursor - part.start !=
                        owner.file_offset + p.p_offset + cursor - begin) return false;
                cursor = part.end < end ? part.end : end;
                return true;
            });
            if (!checked || cursor != end) return true;
        }
        __atomic_fetch_or(&g_image_probe, 8U, __ATOMIC_RELAXED);
        callback(&image, sizeof(image), opaque);
        return true;
    });
    if (!read) __atomic_fetch_or(&g_image_probe, 16U, __ATOMIC_RELAXED);
    return read;
}

PrepareError prepare_count_island(uintptr_t site, uintptr_t original,
    uintptr_t continuation, const uint32_t *template_words, size_t template_word_count,
    uintptr_t *original_slot, uint32_t *state_slot, uintptr_t *continuation_slot,
    PreparedIsland *out) {
    if (out == nullptr || site == 0 || site > UINTPTR_MAX - 4U || original == 0 || continuation == 0 ||
        (site & 3U) != 0 || continuation != site + 4U ||
        !singleword::branch_delta_ok(static_cast<int64_t>(original) - static_cast<int64_t>(site)) ||
        template_words == nullptr || template_word_count != 16 || original_slot == nullptr ||
        state_slot == nullptr || continuation_slot == nullptr ||
        static_cast<void *>(original_slot) == static_cast<void *>(state_slot) ||
        static_cast<void *>(original_slot) == static_cast<void *>(continuation_slot) ||
        static_cast<void *>(state_slot) == static_cast<void *>(continuation_slot) ||
        (reinterpret_cast<uintptr_t>(original_slot) & 7U) != 0 ||
        (reinterpret_cast<uintptr_t>(state_slot) & 3U) != 0 ||
        (reinterpret_cast<uintptr_t>(continuation_slot) & 7U) != 0) {
        return PrepareError::kInvalidArgument;
    }
    if (__atomic_load_n(state_slot, __ATOMIC_ACQUIRE) != 0) return PrepareError::kInvalidArgument;
    *out = {nullptr, 0, 0, original, continuation, original_slot, continuation_slot,
        false, false, false, false, false};
    const long page_value = sysconf(_SC_PAGESIZE);
    if (!valid_page_size(page_value)) return PrepareError::kPageSizeUnavailable;
    const size_t page_size = static_cast<size_t>(page_value);
    void *mapping = map_near(site, page_size);
    if (mapping == MAP_FAILED) return PrepareError::kNoNearMapping;
    const uintptr_t island_address = reinterpret_cast<uintptr_t>(mapping);
    out->mapping = mapping;
    out->mapping_size = page_size;
    out->entry = island_address;

    uint32_t staged[16];
    for (size_t i = 0; i < 16; ++i) staged[i] = template_words[i];
    const auto relocated = island::relocate_count_island(staged, 16, island_address,
        reinterpret_cast<uintptr_t>(original_slot),
        reinterpret_cast<uintptr_t>(state_slot),
        reinterpret_cast<uintptr_t>(continuation_slot));
    if (relocated != island::RelocationError::kNone) {
        if (munmap(mapping, page_size) == 0) *out = {};
        return PrepareError::kRelocationFailed;
    }

    auto *destination = static_cast<uint32_t *>(mapping);
    for (size_t i = 0; i < 16; ++i) destination[i] = staged[i];
    singleword::publish_code(mapping, kIslandBytes);
    if (mprotect(mapping, page_size, PROT_READ | PROT_EXEC) != 0) {
        if (munmap(mapping, page_size) == 0) *out = {};
        return PrepareError::kSealFailed;
    }

    // 槽位在任何宿主分支可达之前发布；状态字段本身仍应保持关闭。
    __atomic_store_n(original_slot, original, __ATOMIC_RELEASE);
    __atomic_store_n(continuation_slot, continuation, __ATOMIC_RELEASE);
    out->sealed = true;
    out->slots_published = true;
    return PrepareError::kNone;
}

bool discard_unpublished_island(PreparedIsland *island) {
    if (island == nullptr || island->mapping == nullptr || island->mapping_size == 0 ||
        island->published || island->host_may_reference || island->host_page_may_be_wx ||
        island->entry != reinterpret_cast<uintptr_t>(island->mapping)) return false;
    bool original_cleared = false;
    bool continuation_cleared = false;
    if (island->slots_published) {
        uintptr_t expected_original = island->original;
        original_cleared = __atomic_compare_exchange_n(island->original_slot,
            &expected_original, 0, false, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE);
        if (!original_cleared) return false;
        if (island->continuation_slot != nullptr) {
            uintptr_t expected_continuation = island->continuation;
            continuation_cleared = __atomic_compare_exchange_n(island->continuation_slot,
                &expected_continuation, 0, false, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE);
            if (!continuation_cleared) {
                uintptr_t empty = 0;
                __atomic_compare_exchange_n(island->original_slot, &empty, island->original,
                    false, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE);
                return false;
            }
        }
    }
    if (munmap(island->mapping, island->mapping_size) != 0) {
        uintptr_t empty_original = 0;
        uintptr_t empty_continuation = 0;
        if (original_cleared) __atomic_compare_exchange_n(island->original_slot,
            &empty_original, island->original, false, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE);
        if (continuation_cleared) __atomic_compare_exchange_n(island->continuation_slot,
            &empty_continuation, island->continuation, false, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE);
        return false;
    }
    island->mapping = nullptr;
    island->mapping_size = 0;
    island->entry = 0;
    island->original_slot = nullptr;
    island->continuation_slot = nullptr;
    island->sealed = false;
    island->slots_published = false;
    island->host_page_may_be_wx = false;
    return true;
}

PrepareError prepare_menu_island(uintptr_t site, uintptr_t original,
    const uint32_t *template_words, size_t template_word_count,
    uintptr_t *original_slot, PreparedIsland *out) {
    if (out == nullptr || site == 0 || original == 0 ||
        (site & 3U) != 0 || template_words == nullptr || template_word_count != 4 ||
        original_slot == nullptr || (reinterpret_cast<uintptr_t>(original_slot) & 7U) != 0 ||
        __atomic_load_n(original_slot, __ATOMIC_ACQUIRE) != 0 ||
        !singleword::branch_delta_ok(static_cast<int64_t>(original) - static_cast<int64_t>(site))) {
        return PrepareError::kInvalidArgument;
    }
    *out = {nullptr, 0, 0, original, 0, original_slot, nullptr,
        false, false, false, false, false};
    const long page_value = sysconf(_SC_PAGESIZE);
    if (!valid_page_size(page_value)) return PrepareError::kPageSizeUnavailable;
    const size_t page_size = static_cast<size_t>(page_value);
    void *mapping = map_near(site, page_size);
    if (mapping == MAP_FAILED) return PrepareError::kNoNearMapping;
    const uintptr_t island_address = reinterpret_cast<uintptr_t>(mapping);
    out->mapping = mapping;
    out->mapping_size = page_size;
    out->entry = island_address;

    uint32_t staged[4];
    for (size_t i = 0; i < 4; ++i) staged[i] = template_words[i];
    const auto first = island::relocate_adrp_x16(staged[0], island_address,
        reinterpret_cast<uintptr_t>(original_slot));
    const auto second = island::relocate_add_lo12_x16(staged[1],
        reinterpret_cast<uintptr_t>(original_slot));
    if (!first.ok() || !second.ok()) {
        (void)munmap(mapping, page_size);
        *out = {};
        return PrepareError::kRelocationFailed;
    }
    staged[0] = first.instruction;
    staged[1] = second.instruction;
    auto *destination = static_cast<uint32_t *>(mapping);
    for (size_t i = 0; i < 4; ++i) destination[i] = staged[i];
    singleword::publish_code(mapping, sizeof(staged));
    if (mprotect(mapping, page_size, PROT_READ | PROT_EXEC) != 0) {
        (void)munmap(mapping, page_size);
        *out = {};
        return PrepareError::kSealFailed;
    }
    __atomic_store_n(original_slot, original, __ATOMIC_RELEASE);
    out->sealed = true;
    out->slots_published = true;
    return PrepareError::kNone;
}

PrepareError prepare_menu_island_with_continuation(uintptr_t site, uintptr_t original,
    uintptr_t continuation, const uint32_t *template_words, size_t template_word_count,
    uintptr_t *original_slot, uintptr_t *continuation_slot, PreparedIsland *out) {
    return prepare_menu_island_with_target(site, original, original, continuation,
        template_words, template_word_count, original_slot, continuation_slot, out);
}

PrepareError prepare_menu_island_with_target(uintptr_t site, uintptr_t original,
    uintptr_t target, uintptr_t continuation, const uint32_t *template_words,
    size_t template_word_count, uintptr_t *original_slot, uintptr_t *continuation_slot,
    PreparedIsland *out) {
    if (out == nullptr || site == 0 || original == 0 || target == 0 || continuation == 0 ||
        (site & 3U) != 0 || template_words == nullptr || template_word_count != 4 ||
        original_slot == nullptr || continuation_slot == nullptr ||
        (reinterpret_cast<uintptr_t>(original_slot) & 7U) != 0 ||
        __atomic_load_n(original_slot, __ATOMIC_ACQUIRE) != 0 ||
        __atomic_load_n(continuation_slot, __ATOMIC_ACQUIRE) != 0) {
        return PrepareError::kInvalidArgument;
    }
    *out = {nullptr, 0, 0, original, continuation, original_slot, continuation_slot,
        false, false, false, false, false};
    const long page_value = sysconf(_SC_PAGESIZE);
    if (!valid_page_size(page_value)) return PrepareError::kPageSizeUnavailable;
    const size_t page_size = static_cast<size_t>(page_value);
    void *mapping = map_near(site, page_size);
    if (mapping == MAP_FAILED) return PrepareError::kNoNearMapping;
    out->mapping = mapping;
    out->mapping_size = page_size;
    out->entry = reinterpret_cast<uintptr_t>(mapping);
    auto *destination = static_cast<uint32_t *>(mapping);
    for (size_t i = 0; i < 4; ++i) destination[i] = template_words[i];
    // 先把调用点后的返回地址写入 x30，再跳到目标；目标返回时才能回到宿主。
    *reinterpret_cast<uintptr_t *>(destination + 4) = continuation;
    *reinterpret_cast<uintptr_t *>(destination + 6) = target;
    singleword::publish_code(mapping, kMenuContinuationBytes);
    if (mprotect(mapping, page_size, PROT_READ | PROT_EXEC) != 0) {
        (void)munmap(mapping, page_size);
        *out = {};
        return PrepareError::kSealFailed;
    }
    __atomic_store_n(original_slot, original, __ATOMIC_RELEASE);
    __atomic_store_n(continuation_slot, continuation, __ATOMIC_RELEASE);
    out->sealed = true;
    out->slots_published = true;
    return PrepareError::kNone;
}

PrepareError prepare_direct_island(uintptr_t site, uintptr_t replacement,
    PreparedIsland *out) {
    if (out == nullptr || site == 0 || replacement == 0 || (site & 3U) != 0 ||
        (replacement & 3U) != 0) {
        return PrepareError::kInvalidArgument;
    }
    *out = {nullptr, 0, 0, replacement, 0, nullptr, nullptr,
        false, false, false, false, false};
    const long page_value = sysconf(_SC_PAGESIZE);
    if (!valid_page_size(page_value)) return PrepareError::kPageSizeUnavailable;
    const size_t page_size = static_cast<size_t>(page_value);
    void *mapping = map_near(site, page_size);
    if (mapping == MAP_FAILED) return PrepareError::kNoNearMapping;
    const uintptr_t island_address = reinterpret_cast<uintptr_t>(mapping);
    out->mapping = mapping;
    out->mapping_size = page_size;
    out->entry = island_address;

    // ldr x16, #16; br x16; nop; nop; .quad replacement
    const uint32_t words[] = {0x58000090U, 0xd61f0200U, 0xd503201fU, 0xd503201fU};
    auto *destination = static_cast<uint32_t *>(mapping);
    for (size_t i = 0; i < 4; ++i) destination[i] = words[i];
    *reinterpret_cast<uintptr_t *>(destination + 4) = replacement;
    singleword::publish_code(mapping, kDirectIslandBytes);
    if (mprotect(mapping, page_size, PROT_READ | PROT_EXEC) != 0) {
        (void)munmap(mapping, page_size);
        *out = {};
        return PrepareError::kSealFailed;
    }
    out->sealed = true;
    return PrepareError::kNone;
}

PrepareError prepare_validity_exit_island(uintptr_t site, uintptr_t replacement,
    PreparedIsland *out) {
    if (out == nullptr || site == 0 || replacement == 0 || (site & 3U) != 0 ||
        (replacement & 3U) != 0 || site > UINTPTR_MAX - sizeof(uint32_t)) {
        return PrepareError::kInvalidArgument;
    }
    *out = {nullptr, 0, 0, replacement, 0, nullptr, nullptr,
        false, false, false, false, false};
    const long page_value = sysconf(_SC_PAGESIZE);
    if (!valid_page_size(page_value)) return PrepareError::kPageSizeUnavailable;
    const size_t page_size = static_cast<size_t>(page_value);
    void *mapping = map_near(site, page_size);
    if (mapping == MAP_FAILED) return PrepareError::kNoNearMapping;
    out->mapping = mapping;
    out->mapping_size = page_size;
    out->entry = reinterpret_cast<uintptr_t>(mapping);

    // ADR X17,+24 让替换入口取到续接地址槽；远跳转使用只读字面量。
    auto *destination = static_cast<uint32_t *>(mapping);
    constexpr uint32_t adr_x17_plus_24 = 0x10000000U | (6U << 5) | 17U;
    const uint32_t ldr_x16_plus_12 = 0x58000010U | (3U << 5);
    destination[0] = adr_x17_plus_24;
    destination[1] = ldr_x16_plus_12;
    destination[2] = 0xd61f0200U;  // BR X16
    destination[3] = 0xd503201fU;
    *reinterpret_cast<uintptr_t *>(destination + 4) = replacement;
    *reinterpret_cast<uintptr_t *>(destination + 6) = site + sizeof(uint32_t);
    singleword::publish_code(mapping, kValidityExitIslandBytes);
    if (mprotect(mapping, page_size, PROT_READ | PROT_EXEC) != 0) {
        (void)munmap(mapping, page_size);
        *out = {};
        return PrepareError::kSealFailed;
    }
    out->sealed = true;
    return PrepareError::kNone;
}

PrepareError prepare_transparent_entry_island(uintptr_t site, uintptr_t replacement,
    const uint32_t *prologue, size_t prologue_word_count,
    uintptr_t *original_trampoline_slot, PreparedIsland *out) {
    constexpr size_t kPrologueWords = 6;
    constexpr size_t kTrampolineOffset = 32;
    constexpr size_t kCodeBytes = 64;
    if (out == nullptr || site == 0 || replacement == 0 || prologue == nullptr ||
        prologue_word_count != kPrologueWords || original_trampoline_slot == nullptr ||
        (site & 3U) != 0 || (replacement & 3U) != 0 ||
        (reinterpret_cast<uintptr_t>(original_trampoline_slot) & 7U) != 0 ||
        __atomic_load_n(original_trampoline_slot, __ATOMIC_ACQUIRE) != 0 ||
        prologue[0] != 0xa9bf79fdU) {
        return PrepareError::kInvalidArgument;
    }
    *out = {nullptr, 0, 0, site, site + kPrologueWords * sizeof(uint32_t),
        original_trampoline_slot, nullptr, false, false, false, false, false};
    const long page_value = sysconf(_SC_PAGESIZE);
    if (!valid_page_size(page_value) || static_cast<size_t>(page_value) < kCodeBytes)
        return PrepareError::kPageSizeUnavailable;
    const size_t page_size = static_cast<size_t>(page_value);
    void *mapping = map_near(site, page_size);
    if (mapping == MAP_FAILED) return PrepareError::kNoNearMapping;
    const uintptr_t island_address = reinterpret_cast<uintptr_t>(mapping);
    out->mapping = mapping;
    out->mapping_size = page_size;
    out->entry = island_address;

    // 前 24 字节是绝对跳转岛；+32 处是原函数的 6 条序言与回跳。
    auto *destination = static_cast<uint32_t *>(mapping);
    destination[0] = 0x58000090U;  // ldr x16, #16
    destination[1] = 0xd61f0200U;  // br x16
    destination[2] = 0xd503201fU;
    destination[3] = 0xd503201fU;
    *reinterpret_cast<uintptr_t *>(destination + 4) = replacement;
    for (size_t i = 0; i < kPrologueWords; ++i)
        destination[kTrampolineOffset / sizeof(uint32_t) + i] = prologue[i];
    // 入口闭包是用 `stp x29, x30, [x15, #-16]!` 建立 Dart 帧。复制到岛页后
    // 该序言仍会写宿主 Dart 栈，因此续接必须跳回“原闭包第 6 条之后”，不能
    // 执行新复制出的 6 条指令。
    const uintptr_t branch_at = island_address + kTrampolineOffset +
        kPrologueWords * sizeof(uint32_t);
    const uintptr_t resume_at = site + kPrologueWords * sizeof(uint32_t);
    const int64_t delta = static_cast<int64_t>(resume_at) - static_cast<int64_t>(branch_at);
    if (!singleword::branch_delta_ok(delta)) {
        (void)munmap(mapping, page_size);
        *out = {};
        return PrepareError::kRelocationFailed;
    }
    destination[(kTrampolineOffset + kPrologueWords * sizeof(uint32_t)) / sizeof(uint32_t)] =
        singleword::encode_b(branch_at, resume_at);
    singleword::publish_code(mapping, kCodeBytes);
    if (mprotect(mapping, page_size, PROT_READ | PROT_EXEC) != 0) {
        (void)munmap(mapping, page_size);
        *out = {};
        return PrepareError::kSealFailed;
    }
    __atomic_store_n(original_trampoline_slot,
        // 入口蹦床已经执行原闭包前两条 Dart 序言；回退只能从复制序言的
        // 第三条开始，否则会重复压入 Dart 帧并破坏 x15/x29。
        island_address + kTrampolineOffset + 2 * sizeof(uint32_t), __ATOMIC_RELEASE);
    out->sealed = true;
    out->slots_published = true;
    return PrepareError::kNone;
}

static InstallError install_prepared_island_with_ops(PreparedIsland *island, uintptr_t site,
    uint32_t expected_old_word, const InstallOps *provided_ops) {
    if (provided_ops == nullptr || provided_ops->page_size == nullptr ||
        provided_ops->page_is_rx == nullptr || provided_ops->protect == nullptr ||
        provided_ops->load_word == nullptr || provided_ops->store_word == nullptr ||
        provided_ops->compare_exchange_word == nullptr || provided_ops->publish_code == nullptr) {
        return InstallError::kInvalidCallSitePlan;
    }
    InstallGuard guard;
    if (!guard.held()) return InstallError::kInstallBusy;
    const InstallOps &ops = *provided_ops;
    if (island == nullptr || island->mapping == nullptr || !island->sealed ||
        island->slots_published == false || island->published ||
        island->host_may_reference || island->entry == 0 ||
        island->mapping_size == 0 || island->host_page_may_be_wx ||
        island->entry != reinterpret_cast<uintptr_t>(island->mapping)) {
        return InstallError::kInvalidPreparedIsland;
    }
    if (site == 0 || (site & 3U) != 0 || island->original == 0 ||
        expected_old_word != singleword::encode_bl(site, island->original)) {
        return InstallError::kInvalidCallSitePlan;
    }
    const singleword::CommitPlan plan{site, island->original, island->entry,
        expected_old_word, singleword::encode_bl(site, island->entry), kIslandBytes};
    if (singleword::plan_fault(plan) != singleword::Fault::kNone ||
        ops.load_word(reinterpret_cast<const uint32_t *>(site)) != expected_old_word) {
        return InstallError::kCallSiteChanged;
    }

    const long page_value = ops.page_size();
    if (!valid_page_size(page_value)) return InstallError::kInvalidCallSitePlan;
    const uintptr_t page_mask = static_cast<uintptr_t>(page_value) - 1U;
    const uintptr_t page_start = site & ~page_mask;
    void *const page = reinterpret_cast<void *>(page_start);
    const size_t page_size = static_cast<size_t>(page_value);
    if (!ops.page_is_rx(page_start, page_size)) return InstallError::kInvalidCallSitePlan;
    if (ops.protect(page, page_size, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        return InstallError::kWritableProtectionFailed;
    }
    island->host_page_may_be_wx = true;

    // Recheck under the temporary permission change; never overwrite a competing hook.
    if (ops.load_word(reinterpret_cast<const uint32_t *>(site)) != expected_old_word) {
        if (!restore_rx(page, page_size, ops)) {
            return InstallError::kHostPageLeftWritableExecutable;
        }
        island->host_page_may_be_wx = false;
        return InstallError::kCallSiteChanged;
    }

    // From this point an executing thread may have observed the island. Never unmap it,
    // even if publication must be rolled back.
    island->host_may_reference = true;
    ops.store_word(reinterpret_cast<uint32_t *>(site), plan.new_word);
    ops.publish_code(reinterpret_cast<void *>(site), sizeof(uint32_t));
    if (restore_rx(page, page_size, ops)) {
        island->host_page_may_be_wx = false;
        if (ops.load_word(reinterpret_cast<const uint32_t *>(site)) != plan.new_word)
            return InstallError::kCallSiteChanged;
        island->published = true;
        return InstallError::kNone;
    }

    uint32_t expected_new = plan.new_word;
    if (!ops.compare_exchange_word(reinterpret_cast<uint32_t *>(site), &expected_new,
            expected_old_word)) {
        if (restore_rx(page, page_size, ops)) {
            island->host_page_may_be_wx = false;
            return InstallError::kRollbackConflict;
        }
        return InstallError::kRollbackConflictAndWritableExecutable;
    }
    ops.publish_code(reinterpret_cast<void *>(site), sizeof(uint32_t));
    if (restore_rx(page, page_size, ops)) {
        island->host_page_may_be_wx = false;
        return InstallError::kProtectionRestoreFailed;
    }
    return InstallError::kHostPageLeftWritableExecutable;
}

InstallError install_count_island(PreparedIsland *island, uintptr_t site,
    uint32_t expected_old_word) {
    return install_prepared_island_with_ops(island, site, expected_old_word, &kRealInstallOps);
}

InstallError install_count_island_with_ops(PreparedIsland *island, uintptr_t site,
    uint32_t expected_old_word, const InstallOps *provided_ops) {
    return install_prepared_island_with_ops(island, site, expected_old_word, provided_ops);
}

InstallError install_menu_island(PreparedIsland *island, uintptr_t site,
    uint32_t expected_old_word) {
    return install_prepared_island_with_ops(island, site, expected_old_word, &kRealInstallOps);
}

InstallError install_direct_island(PreparedIsland *island, uintptr_t site,
    uint32_t expected_old_word) {
    if (island == nullptr || island->original == 0) {
        return InstallError::kInvalidPreparedIsland;
    }
    const uint32_t expected_branch = singleword::encode_b(site, island->entry);
    if (site == 0 || (site & 3U) != 0 || island->entry == 0 ||
        !singleword::branch_delta_ok(static_cast<int64_t>(island->entry) -
            static_cast<int64_t>(site)) ||
        !singleword::in_exception_set(expected_branch) ||
        singleword::classify(expected_branch) != singleword::WordKind::kB) {
        return InstallError::kInvalidCallSitePlan;
    }
    // 直接替换也使用同一权限、竞态重检、缓存同步和 RX 恢复事务；这里只把
    // 旧字校验从“原始 BL”放宽为调用点合同中固定的入口序言字。
    InstallGuard guard;
    if (!guard.held()) return InstallError::kInstallBusy;
    if (!island->sealed || island->published || island->host_may_reference ||
        island->host_page_may_be_wx ||
        island->mapping == nullptr ||
        island->mapping_size == 0 || island->entry != reinterpret_cast<uintptr_t>(island->mapping)) {
        return InstallError::kInvalidPreparedIsland;
    }
    const long page_value = kRealInstallOps.page_size();
    if (!valid_page_size(page_value) || kRealInstallOps.load_word(
            reinterpret_cast<const uint32_t *>(site)) != expected_old_word ||
        !kRealInstallOps.page_is_rx(site & ~(static_cast<uintptr_t>(page_value) - 1U),
            static_cast<size_t>(page_value))) {
        return InstallError::kCallSiteChanged;
    }
    const uintptr_t page_start = site & ~(static_cast<uintptr_t>(page_value) - 1U);
    void *const page = reinterpret_cast<void *>(page_start);
    if (kRealInstallOps.protect(page, static_cast<size_t>(page_value),
            PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        return InstallError::kWritableProtectionFailed;
    }
    island->host_page_may_be_wx = true;
    if (kRealInstallOps.load_word(reinterpret_cast<const uint32_t *>(site)) != expected_old_word) {
        if (!restore_rx(page, static_cast<size_t>(page_value), kRealInstallOps)) {
            return InstallError::kHostPageLeftWritableExecutable;
        }
        island->host_page_may_be_wx = false;
        return InstallError::kCallSiteChanged;
    }
    island->host_may_reference = true;
    kRealInstallOps.store_word(reinterpret_cast<uint32_t *>(site), expected_branch);
    kRealInstallOps.publish_code(reinterpret_cast<void *>(site), sizeof(uint32_t));
    if (restore_rx(page, static_cast<size_t>(page_value), kRealInstallOps)) {
        island->host_page_may_be_wx = false;
        // 只回读不足以证明写入成功：若宿主代码页仍保留旧字，必须明确报告
        // 失败，不能把“日志已发布”当成调用点已改写。岛页已可能被引用，
        // 因此无论回读结果如何都保留 host_may_reference 和映射。
        if (kRealInstallOps.load_word(reinterpret_cast<const uint32_t *>(site)) !=
            expected_branch) {
            return InstallError::kCallSiteChanged;
        }
        island->published = true;
        return InstallError::kNone;
    }
    return InstallError::kProtectionRestoreFailed;
}

}  // namespace backend
}  // namespace launcher
