/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
// 只在离线 ARM64 模拟器中执行：直接包含生产入口，以受控替身代替系统、线程与 Binder。
// 不链接进 APK，不模拟真正的调度器、加载锁、Binder 驱动或 SELinux。
#define LAUNCHER_DIAGNOSTIC_ENTRY_TRIAL
#include "../../main/cpp/launcher/native_entry.cpp"
#include <stdarg.h>

namespace launcher {
bool launcher_safety_paths_absent(uint32_t) { return false; }

namespace backend {
// 给真实 points::resolve 提供一份最小 ARM64 映像。这样诊断入口仍执行生产解析器，
// 而不是把“宿主已就绪”直接伪造为成功。
alignas(4096) uint32_t host_code[0x4000 / sizeof(uint32_t)]{};

__attribute__((visibility("default"))) uint32_t branch_word(
    uintptr_t from, uintptr_t to, bool link) {
    const int64_t words = static_cast<int64_t>(to - from) / 4;
    return (link ? 0x94000000U : 0x14000000U) |
        (static_cast<uint32_t>(words) & 0x03ffffffU);
}

__attribute__((visibility("default"))) void put_words(
    uint32_t offset, const uint32_t *words, size_t count) {
    for (size_t i = 0; i < count; ++i) host_code[offset / 4 + i] = words[i];
}

__attribute__((visibility("default"))) void make_host_image() {
    constexpr uintptr_t count = 0x100, getter = 0x300, share = 0x500;
    constexpr uintptr_t freeform = 0x900, share_valid = 0xd00;
    constexpr uintptr_t freeform_valid = 0x1100, init = 0x1500, ret = 0x1700;
    constexpr uintptr_t primary = 0x1900, secondary = 0x1a00, caller = 0x1b00;
    const uintptr_t base = reinterpret_cast<uintptr_t>(host_code);

    constexpr uint32_t count_words[] = {
        0xa9bf79fd, 0xaa0f03fd, 0xd10241ef, 0xaa0103e0, 0xf81f83a1,
        0x94000000, 0xaa0003e2, 0xf85f83a1, 0xf81f03a2, 0xf85ff020,
        0xd34c7c00, 0xf90001e1, 0x93417c01, 0xf85f03a3, 0xeb03003f, 0xd65f03c0,
    };
    constexpr uint32_t getter_words[] = {
        0xa9bf79fd, 0xaa0f03fd, 0xd10021ef, 0xf9403f40,
        0xd2800080, 0xaa1d03ef, 0xa8c179fd, 0xd65f03c0,
    };
    constexpr uint32_t share_words[] = {
        0xa9bf79fd, 0xaa0f03fd, 0xd10041ef, 0xaa0103e0, 0xaa0203e3,
        0xf81f83a2, 0xaa1603e1, 0x94000000, 0xaa0003e1, 0xf85f83a3,
        0x91400362, 0xf9400042, 0xf81f03a0, 0x94000000, 0xf85f03a1,
        0xf85f83a3, 0x91400362, 0xf9400042,
    };
    constexpr uint32_t freeform_words[] = {
        0xa9bf79fd, 0xaa0f03fd, 0xd100a1ef, 0xf81f83b6, 0xf81f03a1,
        0xf81e83a2, 0xf9400360, 0x94000000, 0xf85e83a0, 0xb841f002,
        0x8b1c8042, 0xf81d83a2, 0x6b16005f, 0xd2a20400, 0xf800f020,
    };
    constexpr uint32_t share_valid_words[] = {
        0xa9bf79fd, 0xaa0f03fd, 0xd10401ef, 0xf81f83b6, 0xf81283a1,
        0xf81203a2, 0xf81183a3, 0xf81103a5, 0xd2800041, 0x94000000,
        0xaa0003e1, 0xf85183a0, 0xf81083a1, 0xb800f020, 0xf9400360,
        0x94000000, 0xf85083a0, 0x9100c2c0,
    };
    constexpr uint32_t freeform_valid_words[] = {
        0xa9bf79fd, 0xaa0f03fd, 0xd10101ef, 0xf81f83b6, 0xf81f03a1,
        0xaa0203f0, 0xaa0103e2, 0xaa1003e1, 0xf81e83a1, 0xf81e03a3,
        0xf81d83a5, 0xf9400360, 0x94000000, 0xf85e83a1, 0x9100c2c0,
        0x00000000, 0xf85f03a1, 0xf85e83a2, 0xf85e03a3, 0x94000000,
        0x14000000,
    };
    constexpr uint32_t init_words[] = {
        0xa9bf79fd, 0xaa0f03fd, 0xf9402b64, 0xf81f8de0, 0xf9430b5e,
        0xd63f03c0, 0xaa1d03ef, 0xa8c179fd, 0xf81f83a0, 0xd65f03c0,
    };
    constexpr uint32_t return_words[] = {
        0xf85f83a2, 0xaa1d03ef, 0xa8c179fd, 0xa9bf79fd, 0xaa0f03fd,
        0xa9bf09e0, 0xf9431b5e, 0xd63f03c0, 0xaa1d03ef, 0xa8c179fd, 0xd65f03c0,
    };
    constexpr uint32_t caller_args[] = {0xb840f020, 0x8b1c8000, 0xaa0003e1};
    constexpr uint32_t secondary_tail[] = {0xaa1603e0, 0xaa1d03ef, 0xa8c179fd, 0xd65f03c0};
    constexpr uint32_t freeform_args[] = {0xf85903a1, 0xaa0003e2};
    constexpr uint32_t freeform_result[] = {0xaa0003e1, 0xf81883a1, 0x94000000};

    put_words(count, count_words, sizeof(count_words) / sizeof(count_words[0]));
    host_code[(count + 20) / 4] = branch_word(base + count + 20, base + getter, true);
    put_words(getter, getter_words, sizeof(getter_words) / sizeof(getter_words[0]));
    put_words(share, share_words, sizeof(share_words) / sizeof(share_words[0]));
    put_words(freeform, freeform_words, sizeof(freeform_words) / sizeof(freeform_words[0]));
    put_words(share_valid, share_valid_words, sizeof(share_valid_words) / sizeof(share_valid_words[0]));
    host_code[(share_valid + 60) / 4] = branch_word(base + share_valid + 60, base + init, true);
    host_code[(share_valid + 72) / 4] = branch_word(base + share_valid + 72, base + ret, false);
    put_words(freeform_valid, freeform_valid_words,
        sizeof(freeform_valid_words) / sizeof(freeform_valid_words[0]));
    host_code[(freeform_valid + 48) / 4] =
        branch_word(base + freeform_valid + 48, base + init, true);
    host_code[(freeform_valid + 60) / 4] =
        branch_word(base + freeform_valid + 60, base + ret, false);
    put_words(init, init_words, sizeof(init_words) / sizeof(init_words[0]));
    put_words(ret, return_words, sizeof(return_words) / sizeof(return_words[0]));

    put_words(primary - 12, caller_args, 3);
    host_code[primary / 4] = branch_word(base + primary, base + share, true);
    host_code[(primary + 4) / 4] =
        branch_word(base + primary + 4, base + primary + 32, false);
    put_words(secondary - 12, caller_args, 3);
    host_code[secondary / 4] = branch_word(base + secondary, base + share, true);
    put_words(secondary + 4, secondary_tail, 4);

    put_words(caller - 8, freeform_args, 2);
    host_code[caller / 4] = branch_word(base + caller, base + freeform, true);
    put_words(caller + 4, freeform_result, 3);
}

bool visit_loaded_launcher_images(int (*callback)(dl_phdr_info *, size_t, void *), void *opaque) {
    make_host_image();
    Elf64_Phdr phdr{};
    phdr.p_type = PT_LOAD;
    phdr.p_flags = PF_R | PF_X;
    phdr.p_vaddr = 0;
    phdr.p_memsz = sizeof(host_code);
    phdr.p_filesz = sizeof(host_code);
    phdr.p_align = 4096;
    dl_phdr_info image{};
    image.dlpi_addr = reinterpret_cast<uintptr_t>(host_code);
    image.dlpi_phdr = &phdr;
    image.dlpi_phnum = 1;
    if (callback == nullptr) return false;
    callback(&image, 0, opaque);
    return true;
}
uint32_t launcher_image_probe_status() { return 0; }
}  // 命名空间 backend

#ifdef LAUNCHER_RESTART_ENTRY_TRIAL
uint32_t restart_commit_calls = 0;
bool restart_grant_present = false;
StartupRestartGrant captured_restart{};
#endif
// 受控替身：返回未发布、未提交，使 guarded_install_once 早退，不增加文件操作。
// 真实持久化后端由 launcher_safety_startup_trial 覆盖，此处不构成落盘成功证据。
SafetyStartupResult commit_safety_startup_once(const DiagnosticContext &,
    const Snapshot &, uint32_t, const StartupRestartGrant *restart) {
    #ifdef LAUNCHER_RESTART_ENTRY_TRIAL
    ++restart_commit_calls;
    restart_grant_present = restart != nullptr;
    if (restart != nullptr) captured_restart = *restart;
    #else
    (void)restart;
    #endif
    // 诊断入口试验不执行真实持久化；预检阶段仍需看到“修复未完成”，
    // 才与生产安装闸门保持同一失败关闭形状。
    return {0, false, false, {}};
}

SafetyMaintenanceResult maintain_safety_once(const DiagnosticContext &,
    SafetyMaintenanceOperation, SafetyMaintenanceAuthorizer, void *) {
    return {0, false, false, {}};
}
}  // 命名空间 launcher

extern "C" int nanosleep(const timespec *, timespec *) { return 0; }

namespace trial {
namespace bind = launcher::binder;
uint32_t pid = 42, uid = 10001, euid = 10001;
const char *cmdline = launcher::kLauncherPackage;
int scenario = 0, creates = 0, transactions = 0, logs = 0, deletes = 0, releases = 0;
int binder_loads = 0, binder_failures = 0;
int consumed = 0, read_index = 0, violations = 0, attr_destroyed = 0;
uint32_t nested_status = 0;
uint32_t logged_safety = 0;
bool file_read = false;
bool app_group_files = false;
int directory_errno = 0;
int safety_opens = 0, safety_reads = 0, safety_closes = 0, live_fds = 0;
size_t safety_offset = 0;
const char *safety_text = "1700000000000,0,0,0,1700000000000";
void *(*worker)(void *) = nullptr;
int32_t words[launcher::kSnapshotReplyWords] = {
    launcher::kSnapshotAck, launcher::kSnapshotProtocol, 7, 1000, 1030, 1, 8, 1, 0, 0};
#ifdef LAUNCHER_RESTART_ENTRY_TRIAL
bool reading_maintenance = false;
int32_t maintenance_words[8] = {launcher::kMaintenanceAck, 1, 1, 42, 10001, 1000, 1030,
    static_cast<int32_t>(launcher::kPlannedRestartTokenTag + 7)};
#endif

void copy(char *out, const char *in, size_t size) {
    for (size_t i = 0; i < size; ++i) out[i] = in[i];
}
int forbidden_hook(void *, void *, void **) {
    ++violations;
    return -1;
}
int observer_hook(void *, void *, void **) { return 0; }
int forbidden_unhook(void *) {
    ++violations;
    return -1;
}
launcher::NativeApiEntries entries{2, observer_hook, forbidden_unhook};

bind::AIBinder *check_service(const char *name) {
    if (!descriptor_matches(name, "window")) ++violations;
    return scenario == 13 ? nullptr : reinterpret_cast<bind::AIBinder *>(0x1000);
}
const bind::AIBinder_Class *get_class(bind::AIBinder *) {
    return scenario == 14 ? reinterpret_cast<bind::AIBinder_Class *>(0x2000) : nullptr;
}
const char *descriptor(const bind::AIBinder_Class *) { return "错误接口"; }
bind::AIBinder_Class *define_class(const char *name, void *(*)(void *), void (*)(void *),
    bind::status_t (*)(bind::AIBinder *, bind::transaction_t, const bind::AParcel *, bind::AParcel *)) {
    if (!descriptor_matches(name, bind::kWindowManagerDescriptor)) ++violations;
    return reinterpret_cast<bind::AIBinder_Class *>(0x2000);
}
bool associate(bind::AIBinder *, const bind::AIBinder_Class *) { return scenario != 15; }
bind::status_t prepare(bind::AIBinder *, bind::AParcel **input) {
    *input = reinterpret_cast<bind::AParcel *>(0x3000);
    return scenario == 16 ? -1 : bind::kStatusOk;
}
bind::status_t transact(bind::AIBinder *, bind::transaction_t code,
    bind::AParcel **input, bind::AParcel **output, uint32_t flags) {
    ++transactions;
    #ifdef LAUNCHER_RESTART_ENTRY_TRIAL
    reading_maintenance = code == launcher::kMaintenanceTransaction;
    read_index = 0;
    if ((!reading_maintenance && code != launcher::kChannelTransaction) ||
        flags != 0 || *input == nullptr) ++violations;
    #else
    if (code != launcher::kChannelTransaction || flags != 0 || *input == nullptr) ++violations;
    #endif
    if (g_channel_status != launcher::kChannelBusy) ++violations;
    if (scenario == 21) {
        launcher::Snapshot snapshot{};
        uint32_t errors = 0;
        nested_status = query_configuration_once(snapshot, errors);
    }
    *input = nullptr;
    ++consumed;
    *output = scenario == 18 ? nullptr : reinterpret_cast<bind::AParcel *>(0x4000);
    return scenario == 17 ? -1 : bind::kStatusOk;
}
bind::status_t read_int32(const bind::AParcel *, int32_t *value) {
    #ifdef LAUNCHER_RESTART_ENTRY_TRIAL
    if (reading_maintenance) {
        if (scenario == 19 && read_index == 4) return -1;
        *value = maintenance_words[read_index++];
        return bind::kStatusOk;
    }
    #endif
    if (scenario == 19 && read_index == 4) return -1;
    *value = words[(read_index++) % launcher::kSnapshotReplyWords];
    return bind::kStatusOk;
}
void delete_parcel(bind::AParcel *) { ++deletes; }
void release(bind::AIBinder *) { ++releases; }
}  // 命名空间 trial

extern "C" pid_t getpid() { return static_cast<pid_t>(trial::pid); }
extern "C" uid_t getuid() {
    return trial::uid;
}
extern "C" uid_t geteuid() { return trial::euid; }
extern "C" int open(const char *path, int flags, ...) {
    using namespace trial;
    if (descriptor_matches(path, "/proc/self/cmdline")) {
        if (flags != (O_RDONLY | O_CLOEXEC)) ++violations;
        file_read = false;
        return 7;
    }
    ++safety_opens;
    if (!descriptor_matches(path, scenario == 59 ? "/data/user/12" : "/data/user/0") ||
        flags != (O_PATH | O_DIRECTORY | O_CLOEXEC) || transactions == 0 ||
        g_channel_busy != 1) ++violations;
    if (scenario == 65) return -1;
    ++live_fds;
    safety_offset = 0;
    return 8;
}
extern "C" int openat(int directory, const char *path, int flags, ...) {
    using namespace trial;
    const char *expected = directory == 8 ? launcher::kLauncherSafetyScope :
        directory == 9 ? "files" : "hyperextend_launcher_bootguard";
    const int expected_flags = directory == 10
        ? O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK
        : (directory == 9 ? O_RDONLY : O_PATH) | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW;
    if (directory < 8 || directory > 10 || !descriptor_matches(path, expected) ||
        flags != expected_flags) ++violations;
    if ((directory == 10 && (scenario == 43 || scenario == 51)) ||
        (directory == 9 && scenario == 64)) return -1;
    ++live_fds;
    return directory + 1;
}
extern "C" int fstat(int fd, struct stat *out) {
    using namespace trial;
    if (fd < 9 || fd > 11) ++violations;
    out->st_mode = fd == 11 ? S_IFREG | 0600 : S_IFDIR | 0700;
    out->st_uid = uid;
    out->st_gid = uid;
    if (fd == 10 && app_group_files) out->st_mode = S_IFDIR | 0771;
    out->st_nlink = 1;
    out->st_size = static_cast<off_t>(launcher::fixed_len(safety_text));
    if (fd == 11) {
        if (scenario == 47) out->st_size = 128;
        if (scenario == 48) out->st_uid = uid + 1;
        if (scenario == 49) out->st_mode |= 0040;
        if (scenario == 50) out->st_mode = S_IFIFO | 0600;
        if (scenario == 52) return -1;
        if (scenario == 69) out->st_nlink = 2;
        if (scenario == 70) out->st_size = 0;
    } else {
        if (scenario == 62) out->st_uid = uid + 1;
        if (scenario == 63) { out->st_mode |= 0020; ++out->st_gid; }
    }
    return 0;
}
extern "C" int *__errno() { return &trial::directory_errno; }
extern "C" ssize_t fgetxattr(int fd, const char *name, void *value, size_t size) {
    using namespace trial;
    if (fd != 10 || !app_group_files || live_fds != 1 ||
        !descriptor_matches(name, "system.posix_acl_access") || value != nullptr || size != 0) {
        ++violations;
    }
    directory_errno = ENODATA;
    return -1;
}
extern "C" ssize_t read(int fd, void *out, size_t size) {
    using namespace trial;
    if (fd == 11) {
        ++safety_reads;
        if (scenario == 45) return -1;
        const size_t length = launcher::fixed_len(safety_text);
        if (scenario == 46 && safety_offset >= length - 1) return 0;
        if (safety_offset == length) {
            if (scenario == 60) { *static_cast<char *>(out) = 'x'; return 1; }
            return 0;
        }
        size_t got = length - safety_offset;
        if (got > size) got = size;
        if (scenario == 46 && got > 1) --got;
        if (scenario == 61) got = 1;
        copy(static_cast<char *>(out), safety_text + safety_offset, got);
        safety_offset += got;
        return static_cast<ssize_t>(got);
    }
    if (fd != 7) ++violations;
    if (trial::file_read) return 0;
    trial::file_read = true;
    const size_t length = launcher::fixed_len(trial::cmdline) + 1;
    if (size < length) return -1;
    trial::copy(static_cast<char *>(out), trial::cmdline, length);
    return static_cast<ssize_t>(length);
}
extern "C" int close(int fd) {
    if (fd >= 8 && fd <= 11) --trial::live_fds;
    else if (fd != 7) ++trial::violations;
    if (fd == 11) {
        ++trial::safety_closes;
        if (trial::scenario == 53) return -1;
    }
    return 0;
}
extern "C" ssize_t readlink(const char *, char *out, size_t size) {
    const size_t length = launcher::fixed_len(launcher::kSpawnerExe);
    if (size < length) return -1;
    trial::copy(out, launcher::kSpawnerExe, length);
    return static_cast<ssize_t>(length);
}
extern "C" int clock_gettime(clockid_t clock, timespec *out) {
    using namespace trial;
    out->tv_nsec = 0;
    if (clock == CLOCK_BOOTTIME) {
        out->tv_sec = scenario == 54 && safety_reads > 0 ? 1030 : 1000;
        return 0;
    }
    if (clock != CLOCK_REALTIME) ++violations;
    out->tv_sec = 1700000001;
    if (scenario == 55) return -1;
    if (scenario == 56) out->tv_sec = 1699999999;
    if (scenario == 57) out->tv_nsec = 1000000000;
    if (scenario == 58) out->tv_sec = INT64_MAX / 1000 + 1;
    return 0;
}
extern "C" int pthread_attr_init(pthread_attr_t *) { return trial::scenario == 8 ? -1 : 0; }
extern "C" int pthread_attr_setdetachstate(pthread_attr_t *, int state) {
    if (state != PTHREAD_CREATE_DETACHED) ++trial::violations;
    return trial::scenario == 9 ? -1 : 0;
}
extern "C" int pthread_attr_destroy(pthread_attr_t *) { ++trial::attr_destroyed; return 0; }
extern "C" int pthread_create(pthread_t *, const pthread_attr_t *, void *(*entry)(void *), void *) {
    ++trial::creates;
    if (trial::scenario == 10) return -1;
    trial::worker = entry;
    return 0;
}
extern "C" int pthread_atfork(void (*)(void), void (*)(void), void (*child)(void)) {
    if (child == nullptr) ++trial::violations;
    return 0;
}
extern "C" int __android_log_print(int, const char *tag, const char *format, ...) {
    if (descriptor_matches(format,
            "Binder符号解析失败：default=0x%x next=0x%x binder=0x%x；channel=0x1；install_permitted=false")) {
        ++trial::binder_failures;
    }
    if (descriptor_matches(tag, "HELauncherSafety")) {
        if (!descriptor_matches(format,
                "只读安全预检：blockers=0x%x；persisted=%s；install_permitted=false")) ++trial::violations;
        va_list values;
        va_start(values, format);
        trial::logged_safety = va_arg(values, unsigned int);
        va_end(values);
    } else if (!descriptor_matches(tag, "HELauncherProbe")) {
        ++trial::violations;
    }
    ++trial::logs;
    return 0;
}
extern "C" void *dlsym(void *handle, const char *name) {
    using namespace trial;
    if (scenario == 76 && (handle == RTLD_DEFAULT || handle == RTLD_NEXT)) return nullptr;
    if (scenario == 12) return nullptr;
    if (scenario == 29 && handle == RTLD_DEFAULT) return nullptr;
    if (descriptor_matches(name, "__system_property_get") ||
        descriptor_matches(name, "setprogname")) return reinterpret_cast<void *>(0x5000);
    const char *names[] = {bind::kSymbolCheckService, bind::kSymbolPrepareTransaction,
        bind::kSymbolTransact, bind::kSymbolReadInt32, bind::kSymbolParcelDelete, bind::kSymbolDecStrong,
        bind::kSymbolGetClass, bind::kSymbolClassGetDescriptor, bind::kSymbolClassDefine,
        bind::kSymbolAssociateClass};
    void *functions[] = {reinterpret_cast<void *>(check_service), reinterpret_cast<void *>(prepare),
        reinterpret_cast<void *>(transact), reinterpret_cast<void *>(read_int32),
        reinterpret_cast<void *>(delete_parcel), reinterpret_cast<void *>(release),
        reinterpret_cast<void *>(get_class), reinterpret_cast<void *>(descriptor),
        reinterpret_cast<void *>(define_class), reinterpret_cast<void *>(associate)};
    for (int i = 0; i < 10; ++i) {
        if (descriptor_matches(name, names[i])) {
            // 模拟一个必须从显式句柄取得的符号集；指定故障场景仍让目标符号全路径缺失。
            return (scenario >= 30 && scenario <= 39 && scenario == 30 + i)
                ? nullptr : functions[i];
        }
    }
    ++violations;
    return nullptr;
}
extern "C" void *dlopen(const char *name, int) {
    ++trial::binder_loads;
    if (!descriptor_matches(name, "libbinder_ndk.so")) ++trial::violations;
    return trial::scenario == 12 ? nullptr : reinterpret_cast<void *>(0xb000);
}
extern "C" void *memset(void *out, int value, size_t size) {
    for (size_t i = 0; i < size; ++i) static_cast<unsigned char *>(out)[i] = static_cast<unsigned char>(value);
    return out;
}
extern "C" void *memcpy(void *out, const void *in, size_t size) {
    trial::copy(static_cast<char *>(out), static_cast<const char *>(in), size);
    return out;
}

// 返回非零为失败位置；每个场景在全新的模拟器地址空间中执行。
#define CHECK(condition) do { if (!(condition)) return __LINE__; } while (false)
extern "C" __attribute__((visibility("default")))
int launcher_diagnostic_trial(int scenario) {
    using namespace launcher;
    using namespace trial;
    trial::scenario = scenario;
    CHECK(g_channel_status == kChannelNotQueried && g_snapshot_record == 0);
    if (scenario == 1) uid = euid = 0;
    if (scenario == 2) cmdline = "usap64";
    if (scenario == 3) cmdline = "其他进程";
    if (scenario == 4) entries.version = 3;
    if (scenario == 5) euid = 0;
    if (scenario == 6) uid = euid = 99000;
    if (scenario == 26 || scenario == 27) { uid = euid = 0; cmdline = "usap64"; }
    if (scenario == 20) words[7] = 0;
    if (scenario == 40 || scenario == 42) safety_text = "1700000000000,3,1,0,1700000000000";
    if (scenario == 41) safety_text = "1700000000000,2,0,0,1700000000000";
    if (scenario == 42) words[8] = 1;
    if (scenario == 71) {
        words[8] = INT32_MAX;
        safety_text = "1700000000000,3,1,0,1700000000000";
    }
    if (scenario == 72 || scenario == 73) words[8] = 1;
    if (scenario == 73) safety_text = "1700000000000,1,0,1,1700000000000";
    if (scenario == 74) safety_text = "1700000000000,0,0,2147483647,1700000000000";
    if (scenario == 75) safety_text = "1700000000000,3,1,2147483647,1700000000000";
    if (scenario == 44) safety_text = "1700000000000,0,0,0,1700000000000x";
    if (scenario == 59) uid = euid = 1210001;
    if (scenario == 66) words[5] = 0;
    if (scenario == 67) {
        g_snapshot_record = snapshot_record(7, 1);
        g_channel_owner = pid;
    }
    const auto callback = native_init(scenario == 7 ? nullptr : &entries);
    CHECK(transactions == 0 && safety_opens == 0 &&
        g_snapshot_record == (scenario == 67 ? snapshot_record(7, 1) : 0));
    if (scenario == 26 || scenario == 27) {
        CHECK(creates == 0 && callback != nullptr);
        uid = euid = 10001;
        cmdline = launcher::kLauncherPackage;
        if (scenario == 27) {
            ++pid;
            callback(nullptr, nullptr);
            CHECK(creates == 0);
            // 仅继承父进程回调不够，必须本进程重新收到 native_init。
            CHECK(native_init(&entries) != nullptr);
        } else {
            callback(nullptr, nullptr);
        }
    }
    if (scenario >= 1 && scenario <= 7 && scenario != 4) {
        CHECK(creates == 0 && worker == nullptr && g_diagnostic_record == 0);
        if (scenario == 7) CHECK(callback == nullptr && g_hook_func == 0);
        CHECK(violations == 0);
        return 0;
    }
    // 场景 4 只改变框架版本数字；入口、线程和后续诊断必须与合法快照完全相同。
    if (scenario == 4) CHECK(callback != nullptr && g_hook_func != 0);
    CHECK(callback != nullptr);
    for (int i = 0; i < 16; ++i) callback(nullptr, nullptr);
    if (scenario >= 8 && scenario <= 10) {
        CHECK(worker == nullptr && g_diagnostic_record == diagnostic_record(pid, kDiagnosticThreadFailed));
        CHECK(creates == (scenario == 10 ? 1 : 0));
        CHECK(attr_destroyed == (scenario == 8 ? 0 : 1));
        CHECK(violations == 0);
        return 0;
    }
    CHECK(creates == 1 && worker != nullptr && attr_destroyed == 1);
    if (scenario == 11) uid = euid = 0;
    if (scenario == 22) ++pid;
    worker(nullptr);
    for (int i = 0; i < 16; ++i) callback(nullptr, nullptr);
    CHECK(creates == 1 && violations == 0 && live_fds == 0);
    if (scenario == 11 || scenario == 22) {
        CHECK(transactions == 0 && g_channel_status == kChannelNotQueried);
        CHECK(g_diagnostic_record == diagnostic_record(42,
            scenario == 11 ? kDiagnosticIdentityRejected : kDiagnosticQueued));
        return 0;
    }
    CHECK(g_diagnostic_record == diagnostic_record(pid, kDiagnosticFinished));
    if (binder_failures != (scenario == 12 || (scenario >= 30 && scenario <= 35) ? 1 : 0)) {
        return 3000 + binder_failures;
    }
    if (binder_loads != 1) return 2000 + binder_loads;
    const int expected_logs = (scenario == 27 || scenario == 12) ? 9 :
        (scenario >= 30 && scenario <= 35) ? 8 : 7;
    if (logs != expected_logs) return 1000 + logs;
    uint32_t expected = 0;
    if (scenario == 12 || (scenario >= 30 && scenario <= 35)) expected = kChannelNoBinder;
    if (scenario == 13 || scenario == 14 || scenario == 15 || (scenario >= 36 && scenario <= 39)) {
        expected = kChannelNoWindowService;
    }
    if (scenario >= 16 && scenario <= 18) expected = kChannelTransactFailed;
    if (scenario == 19) expected = kChannelShortReply;
    if (scenario == 20) expected = kChannelSnapshotRejected;
    if (scenario == 67) expected = kChannelSnapshotRejected;
    CHECK(g_channel_status == expected);
    if (expected != 0 && scenario != 67) CHECK(g_snapshot_record == 0);
    else CHECK(g_snapshot_record == snapshot_record(7, scenario == 66 ? 0 : 1));
    const uint32_t safety = static_cast<uint32_t>(g_safety_preflight_record);
    CHECK(logged_safety == safety);
    CHECK(static_cast<uint32_t>(g_safety_preflight_record >> 32) == pid);
    if (expected != 0) {
        CHECK(safety == kPreflightChannelRejected && safety_opens == 0);
    } else if (scenario == 66) {
        CHECK(safety == kPreflightNoFeatures && safety_opens == 0);
    } else if (scenario == 42 || (scenario >= 71 && scenario <= 73)) {
        CHECK(safety == kPreflightSnapshotRejected && safety_opens == 0);
    } else {
        // 正常路径由安全结论直接放行；安全结论拒绝时才由预检追加安装拒绝位。
        uint32_t safety_expected = 0;
        const bool unreadable = (scenario >= 43 && scenario <= 53) || scenario == 60 ||
            (scenario >= 62 && scenario <= 65) || scenario == 69 || scenario == 70;
        if (unreadable) {
            safety_expected = kPreflightPersistencePending | kPreflightLocalUnreadable;
        }
        if (scenario == 40 || scenario == 41 || (scenario >= 55 && scenario <= 58) || scenario == 75) {
            safety_expected = kPreflightFuseRejected | kPreflightInstallDenied;
        }
        if (scenario == 68) {
            safety_expected = kPreflightIdentityRejected | kPreflightInstallDenied;
        }
        if (scenario == 54) {
            safety_expected = kPreflightSnapshotRejected | kPreflightInstallDenied;
        }
        if (safety != safety_expected) return safety;
        if (safety_opens != 1) return 70002U;
    }
    const bool acquired = expected != kChannelNoBinder && scenario != 13;
    CHECK(releases == (acquired ? 1 : 0));
    CHECK(consumed == transactions);
    CHECK(deletes == (scenario == 16 || (transactions == 1 && scenario != 18) ? 1 : 0));
    if (scenario == 21) CHECK(nested_status == kChannelBusy && transactions == 1);
    if (scenario == 28) {
        CHECK(native_init(&entries) != nullptr);
        CHECK(creates == 1 && transactions == 1);
    }
    if (scenario == 23 || scenario == 24 || scenario == 25) {
        Snapshot snapshot{};
        uint32_t errors = 0;
        if (scenario == 23) ++pid;
        if (scenario == 25) { words[2] = 8; words[5] = 12; }
        const uint32_t again = query_configuration_once(snapshot, errors);
        CHECK(again == (scenario == 23 ? kChannelWrongProcess :
            scenario == 24 ? kChannelSnapshotRejected : 0U));
        CHECK(g_snapshot_record == (scenario == 25 ? snapshot_record(8, 12) : snapshot_record(7, 1)));
        if (scenario == 23) CHECK(current_snapshot_record() == 0 && transactions == 1);
        if (scenario == 24) CHECK(static_cast<uint32_t>(g_safety_preflight_record) == kPreflightChannelRejected);
        if (scenario == 25) {
            CHECK(static_cast<uint32_t>(g_safety_preflight_record) == 0);
            CHECK(safety_opens == 2 && live_fds == 0);
        }
    }
    return 0;
}
