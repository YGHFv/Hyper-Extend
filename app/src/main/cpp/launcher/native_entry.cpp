/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#include "native_api.h"
#include "probe_state.h"
#include "binder_abi.h"
#include "channel.h"
#include "config_preflight.h"
#include "dart_abi.h"
#include "host_identity.h"
#include "diagnostic_probe.h"
#include "install_permit.h"
#include "install_transaction.h"
#include "count_backend.h"
#include "singleword_publish.h"
#include "safety_startup.h"
#include "maintenance_wire.h"
#include "entry_trace.h"
#include "directory_policy.h"
#include "host_image_contract.h"
#include "hook_points.h"
#include "startup_wait.h"
#include "batch_apply.h"
#include "install_batch_policy.h"
#include "dart_string.h"
#include "share_wire.h"
#include "share_bridge.h"

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <link.h>
#include <pthread.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

namespace launcher {

// 提交结果须通过后端的完整状态检查才能成为安全证据。
static bool safety_evidence(const SafetyStartupResult &result) {
    return result.safety_ready();
}

}  // 命名空间 launcher

namespace {

struct LauncherHookSpec {
    void *target;
    void *replacement;
    void **backup_slot;
    bool direct;
    uint32_t expected_word;
    launcher::backend::PreparedIsland *island;
};

namespace launcher_bridge {
bool share_bridge_consume(uint32_t point, uintptr_t package_tagged, uint32_t *result);
}

}  // 命名空间

// 与 ARM64 蹦床逐字对应的数据槽。状态默认为关闭；original 只允许后端在
// 成功建立中继后回填，不能用任意地址或空值伪造可执行计划。
extern "C" {
__attribute__((visibility("hidden"))) alignas(4) uint32_t launcher_shortcut_count_state = 0;
__attribute__((visibility("hidden"))) alignas(8) void *launcher_shortcut_count_original = nullptr;
// 岛式蹦床独立预留原 getter 与调用点+4 槽；当前没有生产安装路径填充或读取它们。
__attribute__((visibility("hidden"))) alignas(8) void *launcher_shortcut_count_island_original = nullptr;
__attribute__((visibility("hidden"))) alignas(8) void *launcher_shortcut_count_island_continuation = nullptr;
__attribute__((visibility("hidden"))) alignas(8) void *launcher_menu_original_primary = nullptr;
__attribute__((visibility("hidden"))) alignas(8) void *launcher_menu_original_share_secondary = nullptr;
__attribute__((visibility("hidden"))) alignas(8) void *launcher_menu_continuation_primary = nullptr;
__attribute__((visibility("hidden"))) alignas(8) void *launcher_menu_continuation_secondary = nullptr;
__attribute__((visibility("hidden"))) alignas(8) void *launcher_menu_original_freeform = nullptr;
__attribute__((visibility("hidden"))) alignas(8) void *launcher_menu_original_share_valid = nullptr;
__attribute__((visibility("hidden"))) alignas(8) void *launcher_menu_original_freeform_valid = nullptr;
// 两个可见性适配器共用的功能位；只有对应功能的所有代码点都已发布并通过
// 回读后才置位。0 表示完全透传宿主原逻辑。
__attribute__((visibility("hidden"))) alignas(4) uint32_t launcher_menu_feature_state = 0;
// 入口岛页的原闭包续接地址。与内部调用点槽分开，避免入口和旧调用点计划
// 同时存在时互相覆盖。
__attribute__((visibility("hidden"))) alignas(8) void *launcher_menu_entry_original_primary = nullptr;
__attribute__((visibility("hidden"))) alignas(8) void *launcher_menu_entry_original_secondary = nullptr;
void launcher_shortcut_count_continuation();
void launcher_shortcut_count_island();
void launcher_menu_primary_island();
void launcher_menu_primary_trace();
void launcher_menu_secondary_island();
void launcher_menu_secondary_trace();
void launcher_menu_share_entry_primary();
void launcher_menu_share_entry_secondary();
void launcher_menu_freeform_island();
void launcher_menu_share_valid_override();
void launcher_menu_freeform_valid_override();
void launcher_menu_share_valid_exit();
void launcher_menu_freeform_valid_exit();
__attribute__((visibility("hidden"), noinline)) void launcher_share_trace() {
    // 保留一个无参符号供旧离线对象链接；真实调用点使用下面的带参入口。
    launcher::experimental_entry_trace("share_apk_entered", static_cast<uint32_t>(getpid()));
}

__attribute__((visibility("hidden"), noinline))
uint32_t launcher_share_bridge(uint32_t point, uintptr_t package_tagged) {
    const char *stage = point == launcher::kSharePointPrimary
        ? "share_apk_primary_entered" : "share_apk_secondary_entered";
    launcher::experimental_entry_trace(stage, static_cast<uint32_t>(package_tagged));
    char package[launcher::kDartMaxPackageNameLength + 1]{};
    if (!launcher::copy_package_name(package_tagged, package, sizeof(package))) {
        launcher::experimental_entry_trace("share_bridge_package_rejected",
            static_cast<uint32_t>(package_tagged));
        return launcher::kShareBridgeInvalidPackage;
    }
    launcher::experimental_entry_trace("share_bridge_package_ok", static_cast<uint32_t>(point));
    uint32_t result = launcher::kShareBridgeTransactFailed;
    if (!launcher_bridge::share_bridge_consume(point, package_tagged, &result)) {
        result = launcher::kShareBridgeInvalidPackage;
    }
    launcher::experimental_entry_trace(
        result == launcher::kShareBridgeAccepted
            ? "share_bridge_acked" : "share_bridge_failed",
        result);
    return result;
}

// 入口桥只回答“是否已经收到 queued ACK”。0 表示可以消费这次点击；
// 任何非零值都由汇编恢复完整现场，尾跳透明原闭包，避免桥失败吞掉点击。
__attribute__((visibility("hidden"), noinline))
uint32_t launcher_share_entry_bridge(uint32_t point, uintptr_t package_tagged) {
    if (!launcher::share_point_supported(point)) {
        launcher::experimental_entry_trace("share_entry_invalid_point", point);
        return launcher::kShareBridgeInvalidPoint;
    }
    const char *entered = point == launcher::kSharePointPrimary
        ? "share_entry_primary_entered" : "share_entry_secondary_entered";
    launcher::experimental_entry_trace(entered, static_cast<uint32_t>(package_tagged));
    char package[launcher::kDartMaxPackageNameLength + 1]{};
    if (!launcher::copy_package_name(package_tagged, package, sizeof(package))) {
        launcher::experimental_entry_trace("share_entry_package_rejected", point);
        return launcher::kShareBridgeInvalidPackage;
    }
    launcher::experimental_entry_trace("share_entry_package_ok", point);
    const uint32_t result = launcher_share_bridge(point, package_tagged);
    if (result == launcher::kShareBridgeAccepted) {
        launcher::experimental_entry_trace("share_entry_bridge_acked", point);
        return 0;
    }
    launcher::experimental_entry_trace("share_entry_bridge_failed", result);
    return result;
}
}

namespace {

// 使用编译器的无锁原子，不依赖 C++ 运行库，也不引入 fork 后可能继承的互斥锁。
static_assert(__atomic_always_lock_free(sizeof(uint64_t), nullptr), "诊断记录需要无锁原子");
alignas(8) uint64_t g_callback_record = 0;
uint32_t g_jni_pid = 0;
alignas(8) uint64_t g_config_revision = 0;
// native_init 到达时保存的框架 ABI。保存指针只是诊断，不等于安装许可。
alignas(8) uint64_t g_hook_func = 0;
alignas(8) uint64_t g_unhook_func = 0;
// fork 后只重建本进程的诊断代际，不安装宿主 hook；子进程改名后由既有模块回调
// 再做身份判断。该状态只用于避免把父进程的 PID、Binder 和快照高水位带入子进程。
alignas(4) uint32_t g_atfork_state = 0;
// 一次性安装闸门：许可通过后，安装成功或失败都不允许本进程重复触碰宿主。
alignas(4) uint32_t g_hook_install_state = 0;
// 成功发布后故意不释放：宿主线程可能在任意时刻仍通过调用点进入岛页。
launcher::backend::PreparedIsland g_count_island{};
launcher::backend::PreparedIsland g_share_primary_island{};
launcher::backend::PreparedIsland g_share_secondary_island{};
launcher::backend::PreparedIsland g_share_entry_primary_island{};
launcher::backend::PreparedIsland g_share_entry_secondary_island{};
launcher::backend::PreparedIsland g_freeform_island{};
launcher::backend::PreparedIsland g_share_valid_island{};
launcher::backend::PreparedIsland g_freeform_valid_island{};
launcher::backend::PreparedIsland g_share_valid_exit_islands[16]{};
launcher::backend::PreparedIsland g_freeform_valid_exit_islands[16]{};
uint32_t g_install_features = 0;
// 最近一次事务的原始结果，供 JNI 只读诊断。它不参与任何安装判定。
uint32_t g_channel_status = launcher::kChannelNotQueried;
// Rust 桌面宿主可能没有预先加载 Binder NDK；保留显式加载句柄，避免函数指针失效。
void *g_binder_ndk_handle = nullptr;
// 最近一次成功采纳的快照修订号与四个功能位，供诊断核对。
// 修订号同时充当跨调用的防回放高水位：下一次事务必须看到严格更大的序号才采纳，
// 否则一份被截获的旧回复可以在租期内反复冒充当前配置。
alignas(8) uint64_t g_snapshot_record = 0;
// 只有在快照被完整采纳（guarded_install_once 返回 0）后才启用预加载属性改写。
// 它不依赖代码页蹦床，因此单独记录，避免「快照已写、安装被拒」时提前生效。
alignas(4) uint32_t g_prestart_enabled = 0;
// 所有查询共用不等待的闸门，防止原生 worker 与人工 JNI 查询并发采纳旧回复。
// owner 一旦确定就不重置；fork 后不能沿用父进程的 Binder 类和防回放状态。
uint32_t g_channel_owner = 0;
uint32_t g_channel_busy = 0;
alignas(8) uint64_t g_diagnostic_record = 0;
// 宿主映像晚于 native_init 出现时，保留有限次数的延迟重试；不能让一次过早探测
// 永久消耗本进程唯一机会，也不能无界创建线程。
alignas(4) uint32_t g_host_retry_attempts = 0;
// 模块加载回调可能因多个宿主库加载而重复触发；每个进程只记录一次到达证据。
alignas(4) uint32_t g_module_loaded_logged = 0;
// 下面三项只用于复现 Rust 桌面的 fork -> 特化信号，不参与功能安装门禁。
using SystemPropertyGet = int (*)(const char *, char *);
using SetProcessName = void (*)(const char *);
SystemPropertyGet g_original_system_property_get = nullptr;
SetProcessName g_original_setprogname = nullptr;
alignas(4) uint32_t g_property_observer_installed = 0;
alignas(4) uint32_t g_process_name_observer_installed = 0;
alignas(4) uint32_t g_specialization_logged = 0;
alignas(4) uint32_t g_entry_pid = 0;

launcher::HookPoints g_host_points{};
uint32_t g_host_points_pid = 0;
LauncherHookSpec g_share_valid_specs[16]{};
LauncherHookSpec g_freeform_valid_specs[16]{};
uint32_t g_share_valid_words[16]{};
uint32_t g_freeform_valid_words[16]{};
bool g_share_valid_unique[16]{};
bool g_freeform_valid_unique[16]{};

// 只读预检与配置采纳分开记录；任何一次预检都不能代替启动状态的持久化提交。
enum SafetyPreflightBlocker : uint32_t {
    kPreflightNotRun = 1U << 0,
    kPreflightChannelRejected = 1U << 1,
    kPreflightIdentityRejected = 1U << 2,
    kPreflightSnapshotRejected = 1U << 3,
    kPreflightNoFeatures = 1U << 4,
    kPreflightLocalUnreadable = 1U << 5,
    kPreflightFuseRejected = 1U << 6,
    kPreflightPersistencePending = 1U << 7,
    kPreflightInstallDenied = 1U << 8,
    kPreflightUnsupportedFeature = 1U << 9,
};
alignas(8) uint64_t g_safety_preflight_record = 0;

struct MaintenanceGrantCarrier {
    launcher::SafetyMaintenanceGrant grant{};
    launcher::SafetyMaintenanceOperation operation{};
};

uint32_t read_bounded_file(const char *path, char *buffer, size_t limit) {
    if (path == nullptr || buffer == nullptr || limit == 0) return 0;
    const int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    size_t size = 0;
    while (size < limit) {
        const ssize_t count = read(fd, buffer + size, limit - size);
        if (count <= 0) break;
        size += static_cast<size_t>(count);
    }
    close(fd);
    return static_cast<uint32_t>(size);
}

uint32_t host_identity_now() {
    char cmdline[256]{};
    char exe[256]{};
    const uint32_t cmd_size = read_bounded_file("/proc/self/cmdline", cmdline, sizeof(cmdline));
    const ssize_t exe_size = readlink("/proc/self/exe", exe, sizeof(exe) - 1);
    return launcher::classify_host(cmdline, cmd_size, exe,
        exe_size > 0 ? static_cast<size_t>(exe_size) : 0);
}

uint32_t current_pid() {
    return static_cast<uint32_t>(getpid());
}

launcher::DiagnosticContext diagnostic_context_now() {
    return {current_pid(), static_cast<uint32_t>(getuid()), static_cast<uint32_t>(geteuid()),
        host_identity_now(), __atomic_load_n(&g_callback_record, __ATOMIC_ACQUIRE)};
}

// 不使用 strcmp：产物审计把导入表限制在已核准的一小组符号里，而这个比较只有
// 十几个字节，自带一个足够。返回 true 表示 descriptor 与期望逐字相同。
bool descriptor_matches(const char *descriptor, const char *expected) {
    if (descriptor == nullptr || expected == nullptr) return false;
    for (;;) {
        if (*descriptor != *expected) return false;
        if (*descriptor == '\0') return true;
        descriptor++;
        expected++;
    }
}

void maybe_schedule_diagnostic();
void launcher_child_adopted(bool name_signal = false);
uint32_t host_identity_now();
uint32_t current_pid();
bool launcher_loaded_image_name(const char *name);

bool lifecycle_is_spawner() {
    char executable[256]{};
    const ssize_t size = readlink("/proc/self/exe", executable, sizeof(executable) - 1);
    return size > 0 && launcher::fixed_eq(executable, static_cast<size_t>(size),
        launcher::kSpawnerExe);
}

bool lifecycle_is_launcher_child(bool name_signal = false) {
    // setprogname 的参数比 /proc/self/cmdline 更早可靠；HYOS 可能在
    // setprogname 返回前才刷新 proc cmdline。属性/库回调仍使用后者复核。
    if (name_signal) {
        return !lifecycle_is_spawner() || current_pid() != g_entry_pid || getuid() >= 10000U;
    }
    if (!launcher::host_is_specialized_launcher(host_identity_now())) return false;
    // spawner 的 exe 在 fork 后仍保持不变；PID 或应用 UID 用于排除 fork 前的根入口。
    return !lifecycle_is_spawner() || current_pid() != g_entry_pid || getuid() >= 10000U;
}

void lifecycle_setprogname(const char *name) {
    if (g_original_setprogname != nullptr) g_original_setprogname(name);
    if (name != nullptr && launcher::fixed_eq(name, launcher::fixed_len(name),
            launcher::kLauncherPackage)) {
        // 不等待 proc cmdline 改名，直接使用参考项目采用的 setprogname 信号。
        launcher_child_adopted(true);
    }
}

bool is_prestart_property(const char *name) {
    return descriptor_matches(name, "persist.sys.usap_pool_enabled") ||
        descriptor_matches(name, "persist.sys.dynamic_usap_enabled") ||
        descriptor_matches(name, "persist.sys.prestart.proc") ||
        descriptor_matches(name, "persist.sys.prestart.feedback.enable") ||
        descriptor_matches(name, "persist.sys.launch_response_optimization.enable");
}

int lifecycle_property_get(const char *name, char *value) {
    // 某些 HYOS 版本在 setprogname 前先读取属性，因此两条信号都触发同一幂等采纳。
    launcher_child_adopted();
    // 预加载只在快照完整采纳后改写；未取得快照或功能未启用时完全透传宿主行为。
    // 值固定为 "false"，与上游 DisablePrestart 的布尔结果一致。
    if (name != nullptr && value != nullptr &&
        __atomic_load_n(&g_prestart_enabled, __ATOMIC_ACQUIRE) != 0 &&
        is_prestart_property(name)) {
        value[0] = 'f';
        value[1] = 'a';
        value[2] = 'l';
        value[3] = 's';
        value[4] = 'e';
        value[5] = '\0';
        return 5;
    }
    return g_original_system_property_get != nullptr
        ? g_original_system_property_get(name, value) : 0;
}

void install_lifecycle_observer(launcher::HookFunction hook, void *target, void *replacement,
                                void **original, uint32_t *state, const char *label) {
    if (hook == nullptr || target == nullptr || state == nullptr) return;
    uint32_t expected = 0;
    if (!__atomic_compare_exchange_n(state, &expected, 1U, false,
            __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) return;
    if (hook(target, replacement, original) != 0) {
        __atomic_store_n(state, 0U, __ATOMIC_RELEASE);
        __android_log_print(ANDROID_LOG_WARN, "HELauncherProbe",
            "lifecycle observer failed: %s; install_permitted=false", label);
    }
}

void install_lifecycle_observers() {
    const auto hook = reinterpret_cast<launcher::HookFunction>(
        __atomic_load_n(&g_hook_func, __ATOMIC_ACQUIRE));
    if (hook == nullptr || (!lifecycle_is_spawner() && !lifecycle_is_launcher_child())) return;
    install_lifecycle_observer(hook, dlsym(RTLD_DEFAULT, "__system_property_get"),
        reinterpret_cast<void *>(lifecycle_property_get),
        reinterpret_cast<void **>(&g_original_system_property_get),
        &g_property_observer_installed, "property");
    install_lifecycle_observer(hook, dlsym(RTLD_DEFAULT, "setprogname"),
        reinterpret_cast<void *>(lifecycle_setprogname),
        reinterpret_cast<void **>(&g_original_setprogname),
        &g_process_name_observer_installed, "process-name");
    __android_log_print(ANDROID_LOG_INFO, "HELauncherProbe",
        "lifecycle observers registered; spawner=%d; install_permitted=false",
        lifecycle_is_spawner());
}

void atfork_child_reset() {
    const uint64_t inherited = __atomic_load_n(&g_callback_record, __ATOMIC_ACQUIRE);
    const uint32_t version = static_cast<uint32_t>(inherited);
    if ((inherited & launcher::kEntriesPresent) == 0) return;
    // pthread_atfork 的 child 回调不读取宿主身份：此时 cmdline 仍可能是 usap64。
    // 只先建立本进程代际，待框架的模块加载回调在改名后触发诊断调度。
    __atomic_store_n(&g_callback_record,
        launcher::callback_record(current_pid(), version, true), __ATOMIC_RELEASE);
    __atomic_store_n(&g_jni_pid, 0U, __ATOMIC_RELEASE);
    __atomic_store_n(&g_channel_owner, 0U, __ATOMIC_RELEASE);
    __atomic_store_n(&g_channel_busy, 0U, __ATOMIC_RELEASE);
    __atomic_store_n(&g_module_loaded_logged, 0U, __ATOMIC_RELEASE);
    __atomic_store_n(&g_channel_status, launcher::kChannelNotQueried, __ATOMIC_RELEASE);
    __atomic_store_n(&g_snapshot_record, 0U, __ATOMIC_RELEASE);
    __atomic_store_n(&g_prestart_enabled, 0U, __ATOMIC_RELEASE);
    __atomic_store_n(&g_safety_preflight_record, 0U, __ATOMIC_RELEASE);
    __atomic_store_n(&g_diagnostic_record, 0U, __ATOMIC_RELEASE);
    __atomic_store_n(&g_specialization_logged, 0U, __ATOMIC_RELEASE);
}

bool install_atfork_reset() {
    uint32_t expected = 0;
    if (!__atomic_compare_exchange_n(&g_atfork_state, &expected, 1U, false,
            __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) return expected == 2U;
    if (pthread_atfork(nullptr, nullptr, atfork_child_reset) != 0) {
        __atomic_store_n(&g_atfork_state, 3U, __ATOMIC_RELEASE);
        __android_log_print(ANDROID_LOG_WARN, "HELauncherProbe",
            "fork child reset unavailable; install_permitted=false");
        return false;
    }
    __atomic_store_n(&g_atfork_state, 2U, __ATOMIC_RELEASE);
    __android_log_print(ANDROID_LOG_INFO, "HELauncherProbe",
        "fork child reset registered; install_permitted=false");
    return true;
}

struct HostImageProbe {
    launcher::HookPoints points{};
    uint32_t matches = 0;
};

bool launcher_loaded_image_name(const char *name) {
    if (name == nullptr) return false;
    constexpr char suffix[] = "libapp.so";
    const size_t length = launcher::fixed_len(name);
    const size_t suffix_size = sizeof(suffix) - 1;
    return length >= suffix_size && descriptor_matches(name + length - suffix_size, suffix);
}

int host_image_callback(struct dl_phdr_info *info, size_t, void *opaque) {
    if (info == nullptr || opaque == nullptr) return 0;
    auto *probe = static_cast<HostImageProbe *>(opaque);
    launcher::HookPoints points{};
    if (launcher::points::resolve(*info, &points)) {
        probe->points = points;
        ++probe->matches;
    }
    return 0;
}

bool resolve_host_points() {
    if (g_host_points_pid == current_pid()) return true;
    HostImageProbe probe{};
    // 统一走真实文件映射核验；不以 APK 版本、build-id、整库摘要或固定 RVA 作许可。
    if (!launcher::backend::visit_loaded_launcher_images(host_image_callback, &probe) ||
        probe.matches != 1) return false;
    g_host_points = probe.points;
    g_host_points_pid = current_pid();
    return true;
}

bool configuration_session_ready(uint32_t pid) {
    return launcher::configuration_probe_ready(pid,
        __atomic_load_n(&g_jni_pid, __ATOMIC_ACQUIRE),
        __atomic_load_n(&g_callback_record, __ATOMIC_ACQUIRE));
}

// 开机单调时钟的**整秒**。
//
// 必须返回秒而不是毫秒，理由见 snapshot_wire.h：回复体里的时间字段是 int32_t，
// 而开机毫秒数在 uptime 超过约 25 天后就会越过 int32 上界，让整条通道
// 在没有故障的情况下永久失效。返回 -1 表示读取失败，调用方按「不安全时钟」拒绝。
int64_t elapsed_seconds() {
    timespec now{};
    if (clock_gettime(CLOCK_BOOTTIME, &now) != 0 || now.tv_sec < 0 || now.tv_nsec < 0 ||
        now.tv_nsec >= 1000000000) return -1;
    return static_cast<int64_t>(now.tv_sec);
}

// 开机单调时钟的毫秒数，只给 JNI 配置预检用。
//
// 那条路径的字段全是 64 位整数（config_preflight.h 的 kConfigLeaseMs = 30000），
// 不存在 int32 溢出问题，保留毫秒精度与既有断言、既有测试完全一致。
// 通道快照必须用 elapsed_seconds()，两者不要互串。
int64_t elapsed_ms() {
    timespec now{};
    if (clock_gettime(CLOCK_BOOTTIME, &now) != 0 || now.tv_sec < 0 || now.tv_nsec < 0 ||
        now.tv_nsec >= 1000000000 || now.tv_sec > (INT64_MAX - 999) / 1000) return -1;
    return static_cast<int64_t>(now.tv_sec) * 1000 + now.tv_nsec / 1000000;
}

int64_t wall_time_ms() {
    timespec now{};
    if (clock_gettime(CLOCK_REALTIME, &now) != 0 || now.tv_sec < 0 || now.tv_nsec < 0 ||
        now.tv_nsec >= 1000000000 || now.tv_sec > (INT64_MAX - 999) / 1000) return -1;
    return static_cast<int64_t>(now.tv_sec) * 1000 + now.tv_nsec / 1000000;
}

bool private_directory(int fd, uint32_t uid,
                       launcher::DirectoryRole role = launcher::DirectoryRole::kPackage) {
    return launcher::private_directory_blockers(fd, uid, role) == 0;
}

// 只打开现成的 CE 私有目录，不初始化、不恢复、不创建文件。
// /data/user/0 可以是系统维护的别名；其下的包目录、files 与状态文件逐级拒绝符号链接。
bool read_local_safety_fuse(uint32_t uid, launcher::SafetyFuseState &state) {
    char user_path[32] = "/data/user/";
    char digits[10]{};
    uint32_t user = uid / 100000U;
    size_t count = 0, cursor = sizeof("/data/user/") - 1;
    do {
        digits[count++] = static_cast<char>('0' + user % 10U);
        user /= 10U;
    } while (user != 0);
    while (count > 0) user_path[cursor++] = digits[--count];
    user_path[cursor] = '\0';
    // O_PATH 仅要求路径搜索权限，不要求列出系统管理的用户根目录。
    const int user_fd = open(user_path, O_PATH | O_DIRECTORY | O_CLOEXEC);
    if (user_fd < 0) return false;
    const int package_fd = openat(user_fd, launcher::kLauncherSafetyScope,
        O_PATH | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    const bool user_closed = close(user_fd) == 0;
    if (package_fd < 0) return false;
    if (!user_closed || !private_directory(package_fd, uid)) {
        close(package_fd);
        return false;
    }
    const int files_fd = openat(package_fd, "files",
        O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    const bool package_closed = close(package_fd) == 0;
    if (files_fd < 0) return false;
    if (!package_closed || !private_directory(files_fd, uid, launcher::DirectoryRole::kFiles)) {
        close(files_fd);
        return false;
    }
    const int state_fd = openat(files_fd, "hyperextend_launcher_bootguard",
        O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
    const bool files_closed = close(files_fd) == 0;
    if (state_fd < 0) return false;
    char text[128]{};
    struct stat info{};
    bool ok = files_closed && fstat(state_fd, &info) == 0 && S_ISREG(info.st_mode) &&
        info.st_uid == uid && (info.st_mode & 0077) == 0 && info.st_nlink == 1 &&
        info.st_size > 0 && info.st_size < static_cast<off_t>(sizeof(text));
    size_t size = 0;
    while (ok && size < static_cast<size_t>(info.st_size)) {
        const ssize_t got = read(state_fd, text + size, static_cast<size_t>(info.st_size) - size);
        if (got <= 0) ok = false;
        else size += static_cast<size_t>(got);
    }
    char tail = 0;
    if (ok) ok = read(state_fd, &tail, 1) == 0;
    const bool closed = close(state_fd) == 0;
    return ok && closed && launcher::decode_safety_fuse(text, static_cast<uint32_t>(size), state);
}

uint32_t safety_preflight_now(const launcher::Snapshot &snapshot, uint32_t last_revision,
                              bool *first_start) {
    if (first_start != nullptr) *first_start = false;
    const auto context = diagnostic_context_now();
    if (!launcher::diagnostic_context_ready(context)) return kPreflightIdentityRejected;
    if (launcher::ordinary_snapshot_errors(snapshot, last_revision, elapsed_seconds()) != 0) {
        return kPreflightSnapshotRejected;
    }
    if (!launcher::snapshot_has_features(snapshot)) return kPreflightNoFeatures;
    launcher::SafetyFuseState local{};
    const bool have_local = read_local_safety_fuse(context.uid, local);
    if (!have_local) {
        // 状态文件不存在与状态损坏必须分开：只有锁、状态和临时文件全部缺失
        // 才是首次正常启动。这里先确认现场形态，安装链稍后会做同一判定的
        // 原子提交；不能先把首次启动写成失败再继续安装。
        const bool absent = launcher::launcher_safety_paths_absent(context.uid);
        if (first_start != nullptr) *first_start = absent;
        if (!absent) {
            return kPreflightPersistencePending | kPreflightLocalUnreadable;
        }
        // 首次启动没有旧状态可计算，真正的安全结论由同一次 commit 产生。
        return 0;
    }
    // 文件读取后重新取时钟，不能让 I/O 消耗的时间延长快照租期。
    const int64_t boot_now_s = elapsed_seconds();
    const int64_t wall_now_ms = wall_time_ms();
    const auto fuse = launcher::safety_fuse_next(local, have_local, wall_now_ms,
        snapshot.safety_reset_token, true);
    const bool safe = launcher::snapshot_safety_permits(snapshot, last_revision,
        boot_now_s, wall_now_ms, local, have_local, true);
    // 预检只表达“当前已有状态是否允许继续”；持久化待提交是后续事务的阶段信息，
    // 不能在已有安全状态时永久保留，否则 evidence 必然带上 0x80 并拒绝安装。
    uint32_t blockers = 0;
    if (!have_local) blockers |= kPreflightLocalUnreadable;
    if (!safe) {
        if (!launcher::safety_permits_install(fuse)) blockers |= kPreflightFuseRejected;
        if (launcher::ordinary_snapshot_errors(snapshot, last_revision, boot_now_s) != 0) {
            blockers |= kPreflightSnapshotRejected;
        }
    }
    const auto current = diagnostic_context_now();
    if (!launcher::diagnostic_context_ready(current) || current.pid != context.pid ||
        current.uid != context.uid) blockers |= kPreflightIdentityRejected;
    // 即使计算结果安全，未提交 next 也绝不提供安全许可。
    const launcher::PermitEvidence evidence{current.identity,
        launcher::diagnostic_context_ready(current), safe && blockers == 0, true};
    if (!launcher::install_permitted(evidence)) return blockers | kPreflightInstallDenied;
    return blockers;
}

// 唯一安装点的受控计划。三个实参各自来自一个真实来源，类型就是 hook_func 的真实
// 签名（native_api.h 的 HookFunction）。三项必须同时非空且成组有效，否则不构成
// 可执行的安装计划。
struct LauncherInstallPlan {
    launcher::HookFunction hook_func = nullptr;
    void *target = nullptr;        // 宿主映像解析出的调用点运行地址
    void *replacement = nullptr;
    void **backup_slot = nullptr;
    uintptr_t original = 0;        // 调用点 BL 解出的原 getter 地址
    uintptr_t continuation = 0;    // 调用点后一条指令，供岛页恢复 x30
    uint32_t expected_old = 0;     // 安装前必须仍是这条 BL
    uint32_t features = 0;         // 本次快照选择的功能集合；新任务位不得进入计划
    bool count_first = false;
    bool direct_entry = false;

    bool ready() const {
        return hook_func != nullptr && target != nullptr && replacement != nullptr &&
            backup_slot != nullptr && original != 0 && continuation != 0 &&
            (direct_entry ? expected_old != 0 : launcher::singleword::is_bl_word(expected_old)) &&
            features != 0;
    }
};

bool menu_feature_requested(uint32_t features, int32_t bit) {
    return (features & static_cast<uint32_t>(bit)) != 0;
}

void *menu_site_for(launcher::HookPoint point) {
    if (!resolve_host_points() || point >= launcher::kHookPointCount) return nullptr;
    const uintptr_t address = g_host_points.address[point];
    if (address == 0 || launcher::points::load(address) != g_host_points.original[point]) return nullptr;
    return reinterpret_cast<void *>(address);
}

// 数量上限的参数和返回值消费已经由同一次现场解析核对。
void *host_call_site_address() {
    return menu_site_for(launcher::kCountCall);
}

constexpr uint32_t kCountIslandTemplate[] = {
    0x100000beU, 0x90000010U, 0x91000210U, 0xf9400211U,
    0xd61f0220U, 0x90000010U, 0x91000210U, 0x88dffe11U,
    0x51001630U, 0x71003e1fU, 0x54000048U, 0x2a1103e0U,
    0x90000010U, 0x91000210U, 0xf940021eU, 0xd61f03c0U,
};

bool decode_call_site(void *site, uintptr_t *original, uint32_t *word) {
    if (site == nullptr || original == nullptr || word == nullptr) return false;
    const auto address = reinterpret_cast<uintptr_t>(site);
    if ((address & 3U) != 0) return false;
    const auto current = __atomic_load_n(reinterpret_cast<const uint32_t *>(site),
        __ATOMIC_ACQUIRE);
    if (!launcher::singleword::is_bl_word(current)) return false;
    *word = current;
    *original = launcher::singleword::bl_target_of(address, current);
    return *original != 0;
}

constexpr uint32_t kMenuIslandTemplate[] = {
    0x90000010U, 0x91000210U, 0xf9400210U, 0xd61f0200U,
};
constexpr uint32_t kMenuContinuationTemplate[] = {
    0x58000091U, 0xaa1103feU, 0x58000090U, 0xd61f0200U,
};

void discard_unpublished_batch() {
    (void)launcher::backend::discard_unpublished_island(&g_count_island);
    (void)launcher::backend::discard_unpublished_island(&g_share_primary_island);
    (void)launcher::backend::discard_unpublished_island(&g_share_secondary_island);
    (void)launcher::backend::discard_unpublished_island(&g_share_entry_primary_island);
    (void)launcher::backend::discard_unpublished_island(&g_share_entry_secondary_island);
    (void)launcher::backend::discard_unpublished_island(&g_freeform_island);
    (void)launcher::backend::discard_unpublished_island(&g_share_valid_island);
    (void)launcher::backend::discard_unpublished_island(&g_freeform_valid_island);
    for (auto &island : g_share_valid_exit_islands)
        (void)launcher::backend::discard_unpublished_island(&island);
    for (auto &island : g_freeform_valid_exit_islands)
        (void)launcher::backend::discard_unpublished_island(&island);
}

bool any_batch_island_published() {
    // host_may_reference 在开始写宿主调用点后立即置位，早于 published。
    // 所有岛页必须统一按该状态保护，否则部分写入或 RX 恢复失败时，
    // 失败路径会误清 original 槽，留下可达但无法回跳的蹦床。
    if (g_count_island.host_may_reference ||
        g_share_primary_island.host_may_reference ||
        g_share_secondary_island.host_may_reference ||
        g_share_entry_primary_island.host_may_reference ||
        g_share_entry_secondary_island.host_may_reference ||
        g_freeform_island.host_may_reference ||
        g_share_valid_island.host_may_reference ||
        g_freeform_valid_island.host_may_reference) return true;
    for (const auto &island : g_share_valid_exit_islands)
        if (island.host_may_reference) return true;
    for (const auto &island : g_freeform_valid_exit_islands)
        if (island.host_may_reference) return true;
    return false;
}

enum class InstalledPoint : uint32_t {
    kUnknown = 0,
    kCount,
    kSharePrimary,
    kShareSecondary,
    kSharePrimaryEntry,
    kShareSecondaryEntry,
    kFreeform,
    kShareValid,
    kFreeformValid,
};

constexpr const char *install_stage_name(bool preparing, InstalledPoint point) {
    if (preparing) {
        switch (point) {
        case InstalledPoint::kCount: return "prepare_count";
        case InstalledPoint::kSharePrimary: return "prepare_share_primary";
        case InstalledPoint::kShareSecondary: return "prepare_share_secondary";
        case InstalledPoint::kSharePrimaryEntry: return "prepare_share_primary_entry";
        case InstalledPoint::kShareSecondaryEntry: return "prepare_share_secondary_entry";
        case InstalledPoint::kFreeform: return "prepare_freeform";
        case InstalledPoint::kShareValid: return "prepare_share_valid";
        case InstalledPoint::kFreeformValid: return "prepare_freeform_valid";
        default: return "prepare_unknown";
        }
    }
    switch (point) {
    case InstalledPoint::kCount: return "publish_count";
    case InstalledPoint::kSharePrimary: return "publish_share_primary";
    case InstalledPoint::kShareSecondary: return "publish_share_secondary";
    case InstalledPoint::kSharePrimaryEntry: return "publish_share_primary_entry";
    case InstalledPoint::kShareSecondaryEntry: return "publish_share_secondary_entry";
    case InstalledPoint::kFreeform: return "publish_freeform";
    case InstalledPoint::kShareValid: return "publish_share_valid";
    case InstalledPoint::kFreeformValid: return "publish_freeform_valid";
    default: return "publish_unknown";
    }
}

// 分享动作由主、次两个闭包入口蹦床接管：保存调用者寄存器后，把闭包外层对象中
// 的 tagged 包名交给原生分享桥，经 Binder 转给 system_server 排队。ACK 成功时由
// 入口蹦床直接以 Dart null 返回，避免再次执行宿主闭包；桥失败时才透明尾跳原闭包。

bool hook_spec_ready(const LauncherHookSpec &spec) {
    return spec.target != nullptr && spec.replacement != nullptr &&
        (spec.direct || spec.backup_slot != nullptr);
}

launcher::backend::PreparedIsland *island_for_spec(const LauncherHookSpec &spec) {
    if (spec.island != nullptr) return spec.island;
    if (spec.replacement == reinterpret_cast<void *>(&launcher_shortcut_count_island)) {
        return &g_count_island;
    }
    if (spec.replacement == reinterpret_cast<void *>(&launcher_menu_primary_island) ||
        spec.replacement == reinterpret_cast<void *>(&launcher_menu_primary_trace)) {
        return &g_share_primary_island;
    }
    if (spec.replacement == reinterpret_cast<void *>(&launcher_menu_secondary_island) ||
        spec.replacement == reinterpret_cast<void *>(&launcher_menu_secondary_trace)) {
        return &g_share_secondary_island;
    }
    if (spec.replacement == reinterpret_cast<void *>(&launcher_menu_share_entry_primary)) {
        return &g_share_entry_primary_island;
    }
    if (spec.replacement == reinterpret_cast<void *>(&launcher_menu_share_entry_secondary)) {
        return &g_share_entry_secondary_island;
    }
    if (spec.replacement == reinterpret_cast<void *>(&launcher_menu_freeform_island)) {
        return &g_freeform_island;
    }
    if (spec.replacement == reinterpret_cast<void *>(&launcher_menu_share_valid_override)) {
        return &g_share_valid_island;
    }
    if (spec.replacement == reinterpret_cast<void *>(&launcher_menu_freeform_valid_override)) {
        return &g_freeform_valid_island;
    }
    return nullptr;
}

bool transparent_entry_spec(const LauncherHookSpec &spec) {
    return spec.replacement == reinterpret_cast<void *>(&launcher_menu_share_entry_primary) ||
        spec.replacement == reinterpret_cast<void *>(&launcher_menu_share_entry_secondary) ||
        spec.replacement == reinterpret_cast<void *>(&launcher_menu_share_valid_override) ||
        spec.replacement == reinterpret_cast<void *>(&launcher_menu_freeform_valid_override);
}

launcher::HookPoint transparent_entry_point(const LauncherHookSpec &spec) {
    if (spec.replacement == reinterpret_cast<void *>(&launcher_menu_share_entry_primary))
        return launcher::kSharePrimaryEntry;
    if (spec.replacement == reinterpret_cast<void *>(&launcher_menu_share_entry_secondary))
        return launcher::kShareSecondaryEntry;
    if (spec.replacement == reinterpret_cast<void *>(&launcher_menu_share_valid_override))
        return launcher::kShareValid;
    if (spec.replacement == reinterpret_cast<void *>(&launcher_menu_freeform_valid_override))
        return launcher::kFreeformValid;
    return launcher::kHookPointCount;
}

launcher::backend::PrepareError prepare_launcher_spec(
    const LauncherHookSpec &spec, uint32_t old_word,
    launcher::backend::PreparedIsland *island) {
    if (spec.replacement == reinterpret_cast<void *>(&launcher_menu_share_valid_exit) ||
        spec.replacement == reinterpret_cast<void *>(&launcher_menu_freeform_valid_exit)) {
        return launcher::backend::prepare_validity_exit_island(
            reinterpret_cast<uintptr_t>(spec.target),
            reinterpret_cast<uintptr_t>(spec.replacement), island);
    }
    if (spec.direct) {
        return launcher::backend::prepare_direct_island(
            reinterpret_cast<uintptr_t>(spec.target),
            reinterpret_cast<uintptr_t>(spec.replacement), island);
    }
    if (transparent_entry_spec(spec)) {
        uint32_t prologue[6]{};
        for (size_t index = 0; index < 6; ++index) {
            prologue[index] = __atomic_load_n(reinterpret_cast<const uint32_t *>(
                reinterpret_cast<uintptr_t>(spec.target) + index * sizeof(uint32_t)),
                __ATOMIC_ACQUIRE);
        }
        uintptr_t *original_slot = nullptr;
        if (spec.replacement == reinterpret_cast<void *>(&launcher_menu_share_entry_primary)) {
            original_slot = reinterpret_cast<uintptr_t *>(&launcher_menu_entry_original_primary);
        } else if (spec.replacement ==
                reinterpret_cast<void *>(&launcher_menu_share_entry_secondary)) {
            original_slot = reinterpret_cast<uintptr_t *>(&launcher_menu_entry_original_secondary);
        } else if (spec.replacement ==
                reinterpret_cast<void *>(&launcher_menu_share_valid_override)) {
            original_slot = reinterpret_cast<uintptr_t *>(&launcher_menu_original_share_valid);
        } else {
            original_slot = reinterpret_cast<uintptr_t *>(&launcher_menu_original_freeform_valid);
        }
        return launcher::backend::prepare_transparent_entry_island(
            reinterpret_cast<uintptr_t>(spec.target),
            reinterpret_cast<uintptr_t>(spec.replacement), prologue, 6,
            original_slot, island);
    }
    uintptr_t original = 0;
    original = launcher::singleword::bl_target_of(
        reinterpret_cast<uintptr_t>(spec.target), old_word);
    if (island == nullptr || original == 0) {
        return launcher::backend::PrepareError::kInvalidArgument;
    }
    if (spec.replacement == reinterpret_cast<void *>(&launcher_shortcut_count_island)) {
        return launcher::backend::prepare_count_island(
            reinterpret_cast<uintptr_t>(spec.target), original,
            reinterpret_cast<uintptr_t>(spec.target) + sizeof(uint32_t),
            kCountIslandTemplate, sizeof(kCountIslandTemplate) / sizeof(uint32_t),
            reinterpret_cast<uintptr_t *>(&launcher_shortcut_count_original),
            &launcher_shortcut_count_state,
            reinterpret_cast<uintptr_t *>(&launcher_shortcut_count_island_continuation), island);
    }
    const bool share_primary = spec.replacement ==
        reinterpret_cast<void *>(&launcher_menu_primary_island) ||
        spec.replacement == reinterpret_cast<void *>(&launcher_menu_primary_trace);
    const bool share_secondary = spec.replacement ==
        reinterpret_cast<void *>(&launcher_menu_secondary_island) ||
        spec.replacement == reinterpret_cast<void *>(&launcher_menu_secondary_trace);
    if (share_primary || share_secondary) {
        auto *continuation_slot = reinterpret_cast<uintptr_t *>(share_primary
            ? &launcher_menu_continuation_primary : &launcher_menu_continuation_secondary);
        const bool trace = spec.replacement == reinterpret_cast<void *>(&launcher_menu_primary_trace) ||
            spec.replacement == reinterpret_cast<void *>(&launcher_menu_secondary_trace);
        const uintptr_t island_target = trace
            ? reinterpret_cast<uintptr_t>(spec.replacement) : original;
        return launcher::backend::prepare_menu_island_with_target(
            reinterpret_cast<uintptr_t>(spec.target), original, island_target,
            reinterpret_cast<uintptr_t>(spec.target) + sizeof(uint32_t),
            kMenuContinuationTemplate,
            sizeof(kMenuContinuationTemplate) / sizeof(uint32_t),
            reinterpret_cast<uintptr_t *>(spec.backup_slot), continuation_slot, island);
    }
    return launcher::backend::prepare_menu_island(
        reinterpret_cast<uintptr_t>(spec.target), original, kMenuIslandTemplate,
        sizeof(kMenuIslandTemplate) / sizeof(uint32_t),
        reinterpret_cast<uintptr_t *>(spec.backup_slot), island);
}

launcher::backend::InstallError install_launcher_spec(
    const LauncherHookSpec &spec, uint32_t old_word,
    launcher::backend::PreparedIsland *island) {
    if (island == nullptr) return launcher::backend::InstallError::kInvalidPreparedIsland;
    if (spec.direct) {
        return launcher::backend::install_direct_island(
            island, reinterpret_cast<uintptr_t>(spec.target), old_word);
    }
    if (transparent_entry_spec(spec)) {
        // 入口替换写单条 B；这里没有 BL 建立的 x30，不能走调用点 BL 校验。
        return launcher::backend::install_direct_island(
            island, reinterpret_cast<uintptr_t>(spec.target), old_word);
    }
    return spec.replacement == reinterpret_cast<void *>(&launcher_shortcut_count_island)
        ? launcher::backend::install_count_island(
            island, reinterpret_cast<uintptr_t>(spec.target), old_word)
        : launcher::backend::install_menu_island(
            island, reinterpret_cast<uintptr_t>(spec.target), old_word);
}

uint32_t points_installed() {
    const bool share_actions = g_share_entry_primary_island.published &&
        g_share_entry_secondary_island.published;
    const bool freeform = g_freeform_island.published;
    bool share_validity = g_host_points.share_valid_exit_count != 0;
    for (size_t index = 0; index < g_host_points.share_valid_exit_count; ++index)
        share_validity = share_validity && g_share_valid_exit_islands[index].published;
    bool freeform_validity = g_host_points.freeform_valid_exit_count != 0;
    for (size_t index = 0; index < g_host_points.freeform_valid_exit_count; ++index)
        freeform_validity = freeform_validity && g_freeform_valid_exit_islands[index].published;
    return (g_count_island.published ? launcher::batch_part_bit(
                launcher::InstallBatchPart::kCount) : 0U) |
        (share_actions ? launcher::batch_part_bit(
            launcher::InstallBatchPart::kShareActions) : 0U) |
        (freeform ? launcher::batch_part_bit(
            launcher::InstallBatchPart::kFreeform) : 0U) |
        (share_validity ? launcher::batch_part_bit(
            launcher::InstallBatchPart::kShareValidity) : 0U) |
        (freeform_validity ? launcher::batch_part_bit(
            launcher::InstallBatchPart::kFreeformValidity) : 0U);
}

uint32_t publish_installed_feature_state(const launcher::Snapshot &snapshot) {
    const uint32_t installed = points_installed();
    const uint32_t count_bit = launcher::batch_part_bit(launcher::InstallBatchPart::kCount);
    const uint32_t share_bit = launcher::batch_part_bit(
        launcher::InstallBatchPart::kShareActions);
    const uint32_t freeform_bit = launcher::batch_part_bit(
        launcher::InstallBatchPart::kFreeform);
    const bool count_ready = (installed & count_bit) == count_bit &&
        menu_feature_requested(snapshot.features, launcher::kSnapshotFeatureCount);
    __atomic_store_n(&launcher_shortcut_count_state,
        launcher::shortcut_count_state(count_ready, static_cast<uint32_t>(snapshot.count_limit)),
        __ATOMIC_RELEASE);

    uint32_t menu_state = 0;
    const uint32_t share_validity_bit = launcher::batch_part_bit(
        launcher::InstallBatchPart::kShareValidity);
    const uint32_t freeform_validity_bit = launcher::batch_part_bit(
        launcher::InstallBatchPart::kFreeformValidity);
    if ((installed & (share_bit | share_validity_bit)) ==
            (share_bit | share_validity_bit) &&
        menu_feature_requested(snapshot.features, launcher::kSnapshotFeatureShare)) {
        menu_state |= static_cast<uint32_t>(launcher::kSnapshotFeatureShare);
    }
    if ((installed & (freeform_bit | freeform_validity_bit)) ==
            (freeform_bit | freeform_validity_bit) &&
        menu_feature_requested(snapshot.features, launcher::kSnapshotFeatureFreeform)) {
        menu_state |= static_cast<uint32_t>(launcher::kSnapshotFeatureFreeform);
    }
    __atomic_store_n(&launcher_menu_feature_state, menu_state, __ATOMIC_RELEASE);
    launcher::experimental_entry_trace("launcher_menu_feature_state", menu_state);
    return menu_state;
}

bool prepare_specs(const LauncherHookSpec *specs, uint32_t *old_words, bool *unique,
                   size_t size, const char *preflight_stage) {
    for (size_t index = 0; index < size; ++index) {
        if (!hook_spec_ready(specs[index])) continue;
        unique[index] = true;
        for (size_t prior = 0; prior < index; ++prior) {
            if (unique[prior] && specs[prior].target == specs[index].target) {
                unique[index] = false;
                break;
            }
        }
        if (!unique[index]) continue;
        {
            launcher::clear_original_slot(specs[index].backup_slot);
            // 入口岛页的 original 是透明原函数中继，不是调用点 BL 的目标；
            // 其地址由 prepare_transparent_entry_island 填写，不能先做调用点解码。
            if (transparent_entry_spec(specs[index])) {
                old_words[index] = __atomic_load_n(
                    reinterpret_cast<const uint32_t *>(specs[index].target), __ATOMIC_ACQUIRE);
                const launcher::HookPoint point = transparent_entry_point(specs[index]);
                if (point >= launcher::kHookPointCount ||
                    old_words[index] != g_host_points.original[point]) {
                    launcher::experimental_entry_trace(preflight_stage, 3U);
                    return false;
                }
                continue;
            }
            if (specs[index].direct) {
                const uint32_t current = __atomic_load_n(
                    reinterpret_cast<const uint32_t *>(specs[index].target),
                    __ATOMIC_ACQUIRE);
                if (specs[index].expected_word == 0 ||
                    current != specs[index].expected_word) {
                    launcher::experimental_entry_trace(preflight_stage, 3U);
                    return false;
                }
                old_words[index] = current;
                continue;
            }
            uintptr_t original = 0;
            if (!decode_call_site(specs[index].target, &original, &old_words[index])) {
                launcher::experimental_entry_trace(preflight_stage, 2U);
                return false;
            }
        }
    }
    return true;
}

bool required_specs_ready(const LauncherHookSpec *specs, const bool *required,
                          size_t size) {
    for (size_t index = 0; index < size; ++index) {
        if (required[index] && !hook_spec_ready(specs[index])) return false;
    }
    return true;
}

InstalledPoint installed_point_for(const LauncherHookSpec &spec) {
    if (spec.target == nullptr) return InstalledPoint::kUnknown;
    if (spec.replacement == reinterpret_cast<void *>(&launcher_shortcut_count_island)) {
        return InstalledPoint::kCount;
    }
    if (spec.replacement == reinterpret_cast<void *>(&launcher_menu_primary_trace)) {
        return InstalledPoint::kSharePrimary;
    }
    if (spec.replacement == reinterpret_cast<void *>(&launcher_menu_secondary_trace)) {
        return InstalledPoint::kShareSecondary;
    }
    if (spec.replacement == reinterpret_cast<void *>(&launcher_menu_share_entry_primary)) {
        return InstalledPoint::kSharePrimaryEntry;
    }
    if (spec.replacement == reinterpret_cast<void *>(&launcher_menu_share_entry_secondary)) {
        return InstalledPoint::kShareSecondaryEntry;
    }
    if (spec.replacement == reinterpret_cast<void *>(&launcher_menu_freeform_island)) {
        return InstalledPoint::kFreeform;
    }
    if (spec.replacement == reinterpret_cast<void *>(&launcher_menu_share_valid_override)) {
        return InstalledPoint::kShareValid;
    }
    if (spec.replacement == reinterpret_cast<void *>(&launcher_menu_freeform_valid_override)) {
        return InstalledPoint::kFreeformValid;
    }
    if (spec.replacement == reinterpret_cast<void *>(&launcher_menu_share_valid_exit)) {
        return InstalledPoint::kShareValid;
    }
    if (spec.replacement == reinterpret_cast<void *>(&launcher_menu_freeform_valid_exit)) {
        return InstalledPoint::kFreeformValid;
    }
    return InstalledPoint::kUnknown;
}

template <size_t Size>
bool apply_specs(const LauncherHookSpec *specs, uint32_t *old_words,
                 const bool (&unique)[Size], launcher::InstallBatchPart part,
                 launcher::InstallBatchOutcome *outcome) {
    if (!launcher::batch_has_unique(unique, Size)) return true;
    outcome->parts_attempted += 1;
    const bool ok = launcher::apply_unique_batch(unique,
        [&](size_t index) {
            const auto result = prepare_launcher_spec(
                specs[index], old_words[index], island_for_spec(specs[index]));
            if (result == launcher::backend::PrepareError::kNone) return true;
            outcome->failed_part = static_cast<uint32_t>(part);
            outcome->failed_stage = 1;
            outcome->error_code = static_cast<uint32_t>(result);
            launcher::experimental_entry_trace(
                install_stage_name(true, installed_point_for(specs[index])),
                static_cast<uint32_t>(result));
            return false;
        },
        [&](size_t index) {
            const auto result = install_launcher_spec(
                specs[index], old_words[index], island_for_spec(specs[index]));
            if (result == launcher::backend::InstallError::kNone) {
                outcome->published += 1;
                return true;
            }
            outcome->failed_part = static_cast<uint32_t>(part);
            outcome->failed_stage = 2;
            outcome->error_code = static_cast<uint32_t>(result);
            launcher::experimental_entry_trace(
                install_stage_name(false, installed_point_for(specs[index])),
                static_cast<uint32_t>(result));
            return false;
        },
        [] { discard_unpublished_batch(); },
        [&](size_t index, bool preparing, bool success) {
            if (success) return;
            const uint32_t error_code = launcher::record_batch_failure(
                outcome, part, preparing, 0U, true);
            launcher::experimental_entry_trace(
                install_stage_name(preparing, installed_point_for(specs[index])),
                error_code);
        });
    if (ok) outcome->parts_succeeded += 1;
    return ok;
}

int install_launcher_batch(void *first_target, void *first_replacement, void **first_backup_slot) {
    if (!hook_spec_ready({first_target, first_replacement, first_backup_slot, false, 0, nullptr})) {
        return -1;
    }
    const uint32_t features = g_install_features;
    const bool need_count = menu_feature_requested(features, launcher::kSnapshotFeatureCount);
    const bool need_share = menu_feature_requested(features, launcher::kSnapshotFeatureShare);
    const bool need_freeform = menu_feature_requested(features, launcher::kSnapshotFeatureFreeform);
    void *count_target = need_count ? menu_site_for(launcher::kCountCall) : nullptr;
    // 点击闭包在调用 shareApk 前仍可能被免责声明/状态分支短路，因此不能把
    // shareApk 的 BL 当成唯一运行入口。入口蹦床先取得包名并在 ACK 后消费闭包，
    // 这样既覆盖短路分支，也不会再落入原 shareApk 造成重复请求。
    void *share_primary_target = need_share
        ? menu_site_for(launcher::kSharePrimaryEntry) : nullptr;
    void *share_secondary_target = need_share
        ? menu_site_for(launcher::kShareSecondaryEntry) : nullptr;
    void *freeform_target = need_freeform
        ? menu_site_for(launcher::kFreeformCall) : nullptr;
    const LauncherHookSpec count_specs[] = {
        {count_target,
            reinterpret_cast<void *>(&launcher_shortcut_count_island),
            &launcher_shortcut_count_original, false, 0, nullptr},
    };
    const LauncherHookSpec share_action_specs[] = {
        {share_primary_target,
            reinterpret_cast<void *>(&launcher_menu_share_entry_primary),
            &launcher_menu_entry_original_primary, false, 0, nullptr},
        {share_secondary_target,
            reinterpret_cast<void *>(&launcher_menu_share_entry_secondary),
            &launcher_menu_entry_original_secondary, false, 0, nullptr},
    };
    const LauncherHookSpec freeform_specs[] = {
        {freeform_target,
            reinterpret_cast<void *>(&launcher_menu_freeform_island),
            &launcher_menu_original_freeform, false, 0, nullptr},
    };
    const size_t share_valid_count = need_share
        ? g_host_points.share_valid_exit_count : 0;
    const size_t freeform_valid_count = need_freeform
        ? g_host_points.freeform_valid_exit_count : 0;
    if (share_valid_count > 16 || freeform_valid_count > 16) return -1;
    bool share_valid_specs_ready = true;
    bool freeform_valid_specs_ready = true;
    for (size_t index = 0; index < share_valid_count; ++index) {
        g_share_valid_specs[index] = {
            reinterpret_cast<void *>(g_host_points.share_valid_exits[index]),
            reinterpret_cast<void *>(&launcher_menu_share_valid_exit), nullptr, true,
            g_host_points.share_valid_exit_words[index],
            &g_share_valid_exit_islands[index]};
        share_valid_specs_ready = share_valid_specs_ready &&
            hook_spec_ready(g_share_valid_specs[index]);
    }
    for (size_t index = 0; index < freeform_valid_count; ++index) {
        g_freeform_valid_specs[index] = {
            reinterpret_cast<void *>(g_host_points.freeform_valid_exits[index]),
            reinterpret_cast<void *>(&launcher_menu_freeform_valid_exit), nullptr, true,
            g_host_points.freeform_valid_exit_words[index],
            &g_freeform_valid_exit_islands[index]};
        freeform_valid_specs_ready = freeform_valid_specs_ready &&
            hook_spec_ready(g_freeform_valid_specs[index]);
    }
    const bool count_required[] = {
        need_count,
    };
    const bool share_action_required[] = {
        need_share,
        need_share,
    };
    const bool freeform_required[] = {
        need_freeform,
    };
    if (!required_specs_ready(count_specs, count_required, 1) ||
        !required_specs_ready(share_action_specs, share_action_required, 2) ||
        !required_specs_ready(freeform_specs, freeform_required, 1) ||
        (need_share && share_valid_count == 0) ||
        (need_freeform && freeform_valid_count == 0) ||
        !share_valid_specs_ready || !freeform_valid_specs_ready) {
        discard_unpublished_batch();
        return -1;
    }
    // 每个功能只由自己的调用点发布。历史实现把数量点当作“主计划”，随后又
    // 在数量组里按同一地址去重，导致数量蹦床从未挂载，却因为该组没有唯一项
    // 而返回成功。这里不再引入一个会与功能组重叠的 first_target。
    uint32_t count_words[1]{}, action_words[2]{};
    uint32_t freeform_words[1];
    bool count_unique[1], action_unique[2];
    bool freeform_unique[1];
    count_words[0] = 0;
    action_words[0] = action_words[1] = 0;
    freeform_words[0] = 0;
    count_unique[0] = false;
    action_unique[0] = action_unique[1] = false;
    freeform_unique[0] = false;
    if (!prepare_specs(count_specs, count_words, count_unique, 1, "preflight_count") ||
        !prepare_specs(share_action_specs, action_words, action_unique, 2, "preflight_share_actions") ||
        !prepare_specs(freeform_specs, freeform_words, freeform_unique, 1, "preflight_freeform") ||
        !prepare_specs(g_share_valid_specs, g_share_valid_words, g_share_valid_unique, 16,
            "preflight_share_valid") ||
        !prepare_specs(g_freeform_valid_specs, g_freeform_valid_words,
            g_freeform_valid_unique, 16,
            "preflight_freeform_valid")) {
        discard_unpublished_batch();
        return -1;
    }
    launcher::InstallBatchOutcome outcome{};
    const bool count_ok = apply_specs(count_specs, count_words, count_unique,
        launcher::InstallBatchPart::kCount, &outcome);
    const bool actions_ok = !need_share || apply_specs(share_action_specs, action_words,
        action_unique, launcher::InstallBatchPart::kShareActions, &outcome);
    const bool freeform_ok = !need_freeform || apply_specs(freeform_specs, freeform_words,
        freeform_unique, launcher::InstallBatchPart::kFreeform, &outcome);
    const bool share_valid_ok = !need_share || apply_specs(g_share_valid_specs,
        g_share_valid_words, g_share_valid_unique, launcher::InstallBatchPart::kShareValidity,
        &outcome);
    const bool freeform_valid_ok = !need_freeform || apply_specs(g_freeform_valid_specs,
        g_freeform_valid_words, g_freeform_valid_unique,
        launcher::InstallBatchPart::kFreeformValidity, &outcome);
    launcher::experimental_entry_trace("install_batch_parts",
        outcome.parts_attempted | (outcome.parts_succeeded << 8) |
        (outcome.published << 16) |
        ((outcome.failed_part & 0xffU) << 24));
    launcher::experimental_entry_trace("install_batch_failure",
        (outcome.failed_stage << 8) | (outcome.error_code & 0xffU));
    launcher::experimental_entry_trace("install_batch_published", points_installed());
    const uint32_t requested_bits = launcher::required_batch_bits(features,
        launcher::kSnapshotFeatureCount, launcher::kSnapshotFeatureShare,
        launcher::kSnapshotFeatureFreeform);
    const bool requested_published =
        (requested_bits & points_installed()) == requested_bits;
    return count_ok && actions_ok && freeform_ok && share_valid_ok && freeform_valid_ok &&
        requested_published ? 0 : -1;
}

// 受控安装计划的解析：把三段来源收敛成一个计划并做非空校验。任何一段缺失都返回
// false（fail-closed）；调用方不得据此用 nullptr 拼装实参，也不得在计划不完整时
// 到达安装点。
bool resolve_launcher_install_plan(LauncherInstallPlan &plan) {
    void *site = nullptr;
    void *replacement = nullptr;
    void **backup_slot = nullptr;
    if (menu_feature_requested(g_install_features, launcher::kSnapshotFeatureCount)) {
        site = host_call_site_address();
        replacement = reinterpret_cast<void *>(&launcher_shortcut_count_island);
        backup_slot = &launcher_shortcut_count_original;
    } else if (menu_feature_requested(g_install_features, launcher::kSnapshotFeatureShare)) {
        // shareApk 的 BL 只覆盖闭包内部的正常路径；免责声明和状态检查会在到达
        // 该 BL 之前返回。入口计划才是实际点击的完整接入点。
        site = menu_site_for(launcher::kSharePrimaryEntry);
        replacement = reinterpret_cast<void *>(&launcher_menu_share_entry_primary);
        backup_slot = &launcher_menu_entry_original_primary;
    } else if (menu_feature_requested(g_install_features, launcher::kSnapshotFeatureFreeform)) {
        site = menu_site_for(launcher::kFreeformCall);
        replacement = reinterpret_cast<void *>(&launcher_menu_freeform_island);
        backup_slot = &launcher_menu_original_freeform;
    }
    uintptr_t original = 0;
    uint32_t expected_old = 0;
    const bool entry_patch = replacement ==
        reinterpret_cast<void *>(&launcher_menu_share_entry_primary);
    if (entry_patch) {
        expected_old = g_host_points.original[launcher::kSharePrimaryEntry];
        original = reinterpret_cast<uintptr_t>(site);
        if (site == nullptr || expected_old == 0) {
            plan = {};
            return false;
        }
    } else if (!decode_call_site(site, &original, &expected_old)) {
        plan = {};
        return false;
    }
    plan = LauncherInstallPlan{install_launcher_batch, site, replacement, backup_slot, original,
        reinterpret_cast<uintptr_t>(site) + sizeof(uint32_t),
        expected_old, g_install_features, true, entry_patch};
    return plan.ready();
}

// 结构接线的唯一链路：用途门禁 → 持久化提交 → 提交结果守卫 → 安装许可 → 安装计划 → 安装点。
//
// 这里是 safety_startup.cpp 接进原生入口的那一处：取到合格快照后先把启动状态提交
// 落盘，再由提交结果产生安全证据，最后由 install_permitted 裁定是否允许安装。
//
// 全库只有这一处安装点，且它受两层约束：`install_permitted` 支配它（许可未通过
// 就在这里早退），安装计划必须包含经过机器字校验的调用点、原目标和接续点。
// 真正的代码页事务由 count_backend 完成：先生成近距岛页并封 RX，再把调用点的单条
// BL 原子提交；失败时不发布状态，已发布的岛页永不释放。
uint32_t guarded_install_once(const launcher::Snapshot &snapshot, uint32_t last_revision,
                              bool *persistence_committed = nullptr,
                              MaintenanceGrantCarrier *request = nullptr) {
    if (persistence_committed != nullptr) *persistence_committed = false;
    // 普通配置不得携带恢复令牌：用途校验非零（不安全）立即早退，且校验的必须
    // 就是即将提交的那一份快照。
    if (launcher::ordinary_snapshot_errors(snapshot, last_revision, elapsed_seconds()) != 0) {
        return kPreflightSnapshotRejected;
    }
    // 生产计划按快照一次性选择数量、分享 APK 或小窗调用点；新任务仍未接入。
    // 含有未实现功能位时必须整包拒绝，不能静默降级成部分安装。
    if (!launcher::snapshot_features_supported_by_production(snapshot.features) &&
        (request == nullptr || request->operation != launcher::SafetyMaintenanceOperation::kRearmKnown)) {
        return kPreflightUnsupportedFeature;
    }
    __atomic_store_n(&g_install_features, static_cast<uint32_t>(snapshot.features),
        __ATOMIC_RELEASE);
    const auto context = diagnostic_context_now();
    const launcher::StartupRestartGrant *restart_ptr = nullptr;
    #if !defined(LAUNCHER_DIAGNOSTIC_ENTRY_TRIAL) || defined(LAUNCHER_RESTART_ENTRY_TRIAL)
    launcher::StartupRestartGrant restart{};
    if (request != nullptr) {
        const auto &grant = request->grant;
        const bool recover = request->operation == launcher::SafetyMaintenanceOperation::kRearmKnown;
        if (!recover && (request->operation != launcher::SafetyMaintenanceOperation::kArmAbsent ||
            !launcher::is_planned_restart_token(grant.reset_token))) return kPreflightSnapshotRejected;
        restart = {
            grant.pid, grant.uid, grant.issued_s, grant.expires_s, grant.reset_token, recover};
        restart_ptr = &restart;
    }
    #else
    if (request != nullptr) return kPreflightSnapshotRejected;
    #endif
    // 所有来源共用唯一提交点和不可改写的结果，不允许某个分支绕过落盘守卫。
    const launcher::SafetyStartupResult result =
        launcher::commit_safety_startup_once(context, snapshot, last_revision, restart_ptr);
    if (restart_ptr != nullptr)
        launcher::experimental_entry_trace(restart_ptr->recover ? "explicit_recovery" : "planned_restart", 1U);
    // 预检位不能解释持久化失败；分别记录后端原因与发布/提交，避免把部分发布误报为无写入。
    launcher::experimental_entry_trace("startup_blockers", result.blockers);
    launcher::experimental_entry_trace("startup_publication",
        (result.published ? 1U : 0U) | (result.committed ? 2U : 0U));
    if (persistence_committed != nullptr) *persistence_committed = result.committed;
    if (!result.committed) return kPreflightPersistencePending;
    if (!launcher::snapshot_has_features(snapshot)) return kPreflightNoFeatures;
    if (!launcher::snapshot_features_supported_by_production(snapshot.features)) return kPreflightUnsupportedFeature;
    const launcher::PermitEvidence evidence{context.identity,
        launcher::diagnostic_context_ready(context), launcher::safety_evidence(result), true};
    // 安装许可支配唯一的安装点：许可未通过就在这里早退，后面不会执行安装调用。
    if (!launcher::install_permitted(evidence)) return kPreflightInstallDenied;
    // 只启用「禁止预加载」时没有需要改写的代码页调用点：属性观察器已由
    // install_lifecycle_observers 安装，快照采纳与安全提交完成后即可生效。
    if ((snapshot.features & launcher::kSnapshotMenuFeatures) == 0) {
        (void)publish_installed_feature_state(snapshot);
        return 0;
    }
    if (launcher::kExperimentalInstallEnabled) {
        __android_log_print(ANDROID_LOG_WARN, "HELauncherInstall",
            "实验安装许可通过；开始验证桌面代码页与蹦床计划");
    }
    LauncherInstallPlan plan{};
    // 计划解析必须拿到非空、彼此有效的宿主目标地址、替换入口与备份槽，否则在这里
    // fail-closed 返回，绝不把 nullptr 拼成实参继续往下走。
    // 计划不完整时不会到达下面的唯一安装调用。
    if (!resolve_launcher_install_plan(plan)) {
        if (launcher::kExperimentalInstallEnabled) {
            __android_log_print(ANDROID_LOG_WARN, "HELauncherInstall",
                "实验安装拒绝：桌面调用点或安装计划校验失败");
        }
        return kPreflightInstallDenied;
    }
    if (!launcher::claim_install_once(&g_hook_install_state)) {
        return kPreflightInstallDenied;
    }
    // 安装事务开始前清掉任何遗留中继地址；后端随后必须重新发布同一个槽。
    // 这样一次失败的准备阶段不会把旧地址误当成本次安装的成功证据。
    launcher::clear_original_slot(plan.backup_slot);
    const int hook_result = plan.hook_func(plan.target, plan.replacement, plan.backup_slot);
    // hook 返回成功还不够：数量蹦床会在进入后立刻从 original 槽取地址，
    // 因此只有后端确实回填了非空、可调用的中继，才允许发布配置状态字。
    if (hook_result != 0 || !launcher::original_published(plan.backup_slot)) {
        if (launcher::kExperimentalInstallEnabled) {
            __android_log_print(ANDROID_LOG_ERROR, "HELauncherInstall",
                "实验安装失败：后端返回或原始中继未发布");
        }
        // 后端一旦把岛页发布到宿主调用点，就不能再清空 original：即使返回错误，
        // 竞争冲突也可能让调用点仍指向该岛页；保留槽位是避免可达蹦床跳入空地址
        // 的最后一道保护。未发布的失败路径才允许清空槽位。
        if (!any_batch_island_published()) launcher::clear_original_slot(plan.backup_slot);
        // 菜单功能按各自完整发布位启用。一个功能批次失败时，仍允许其它已完整发布
        // 的功能显示；不完整的分享/小窗位保持关闭，适配器继续透传宿主原结果。
        (void)publish_installed_feature_state(snapshot);
        launcher::finish_install(&g_hook_install_state, false);
        return kPreflightInstallDenied;
    }
    // 实机验收必须同时看到功能快照和调用点当前机器字；仅凭后端返回 0
    // 不能证明宿主代码页已经改写，尤其是桌面重启后映像可能再次生成。
    launcher::experimental_entry_trace("install_features", g_install_features);
    constexpr const char *hook_stage[launcher::kHookPointCount] = {
        "hook_word_count", "hook_word_share_primary", "hook_word_share_primary_entry",
        "hook_word_share_secondary", "hook_word_share_secondary_entry",
        "hook_word_freeform", "hook_word_share_valid", "hook_word_freeform_valid",
    };
    for (size_t index = 0; index < launcher::kHookPointCount; ++index) {
        const uintptr_t address = g_host_points.address[index];
        if (address == 0) continue;
        const uint32_t word = __atomic_load_n(
            reinterpret_cast<const uint32_t *>(address), __ATOMIC_ACQUIRE);
        launcher::experimental_entry_trace(hook_stage[index], word);
    }
    // 只按各功能完整发布且回读成功的批次发布状态位。
    (void)publish_installed_feature_state(snapshot);
    launcher::finish_install(&g_hook_install_state, true);
    if (launcher::kExperimentalInstallEnabled) {
        __android_log_print(ANDROID_LOG_WARN, "HELauncherInstall",
            "实验安装完成：快捷菜单代码页已发布；仅用于本地测试");
    }
    return 0;
}

// 只读取框架提供的 window 服务并同步取回配置快照。
//
// 每次事务单独取得并释放 AIBinder 句柄，只缓存关联类。
// 整个函数不写宿主状态、不创建线程、不碰系统属性。

// 事务回调：本进程只发出事务、从不应答别人，所以任何到达这里的 onTransact
// 都必须回 UNKNOWN，绝不假装成功。onCreate / onDestroy 无状态。
void *binder_on_create(void *) {
    return nullptr;
}

void binder_on_destroy(void *) {}

launcher::binder::status_t binder_on_transact(launcher::binder::AIBinder *,
    launcher::binder::transaction_t, const launcher::binder::AParcel *,
    launcher::binder::AParcel *) {
    return launcher::binder::kStatusUnknownTransaction;
}

// 先查宿主作用域；若宿主没有加载 Binder NDK，再从显式库句柄查找。
void *resolve_binder_symbol(const char *name, uint32_t bit, uint32_t *default_mask,
                            uint32_t *next_mask, uint32_t *binder_mask) {
    void *symbol = dlsym(RTLD_DEFAULT, name);
    if (symbol != nullptr) {
        if (default_mask != nullptr) *default_mask |= bit;
        return symbol;
    }
    symbol = dlsym(RTLD_NEXT, name);
    if (symbol != nullptr) {
        if (next_mask != nullptr) *next_mask |= bit;
        return symbol;
    }
    void *handle = __atomic_load_n(&g_binder_ndk_handle, __ATOMIC_ACQUIRE);
    if (handle != nullptr) {
        symbol = dlsym(handle, name);
        if (symbol != nullptr && binder_mask != nullptr) *binder_mask |= bit;
    }
    return symbol;
}

void *load_binder_ndk() {
    void *handle = __atomic_load_n(&g_binder_ndk_handle, __ATOMIC_ACQUIRE);
    if (handle != nullptr) return handle;
    void *candidate = dlopen("libbinder_ndk.so", RTLD_NOW | RTLD_LOCAL);
    if (candidate == nullptr) return nullptr;
    void *expected = nullptr;
    if (!__atomic_compare_exchange_n(&g_binder_ndk_handle, &expected, candidate, false,
            __ATOMIC_RELEASE, __ATOMIC_ACQUIRE)) return expected;
    return candidate;
}

// 我们自己为 window 句柄定义的那个类的缓存。
//
// 用命名空间级的无锁原子，而不是函数内的 `static` 局部量：带函数调用的局部静态
// 初始化会生成守卫变量，需要 `__cxa_guard_acquire/release`，而产物审计把导入
// 限制在已核准的一小组符号里，多出这两个就走不过门禁。这里手工做
// compare-exchange，竞争时两个线程可能都定义了类，只保留先发布的那一个
// （另一个不释放：进程内一次性、体积极小，换来的是无锁且无额外导入）。
alignas(8) uint64_t g_window_class = 0;

launcher::binder::AIBinder_Class *window_manager_class(launcher::binder::ClassDefine define) {
    uint64_t cached = __atomic_load_n(&g_window_class, __ATOMIC_ACQUIRE);
    if (cached != 0) return reinterpret_cast<launcher::binder::AIBinder_Class *>(cached);
    auto *created = define(launcher::binder::kWindowManagerDescriptor,
        binder_on_create, binder_on_destroy, binder_on_transact);
    if (created == nullptr) return nullptr;
    uint64_t expected = 0;
    if (!__atomic_compare_exchange_n(&g_window_class, &expected,
            reinterpret_cast<uint64_t>(created), false, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
        return reinterpret_cast<launcher::binder::AIBinder_Class *>(expected);
    }
    return created;
}

uint32_t query_snapshot(launcher::Snapshot &out, uint32_t &snapshot_blockers) {
    namespace bind = launcher::binder;
    // 不新增 DT_NEEDED 依赖，也不含 API 29 门禁的头文件；只在运行期加载 Binder NDK。
    // 任一必需符号缺失都按「本进程没有可用 binder」处理，绝不降级成猜测。
    uint32_t default_mask = 0;
    uint32_t next_mask = 0;
    uint32_t binder_mask = 0;
    if (load_binder_ndk() == nullptr) {
            __android_log_print(ANDROID_LOG_WARN, "HELauncherProbe",
                "Binder NDK 加载失败；install_permitted=false");
    }
    const auto check_service = reinterpret_cast<bind::CheckService>(resolve_binder_symbol(
        bind::kSymbolCheckService, 1U << 0, &default_mask, &next_mask, &binder_mask));
    const auto prepare = reinterpret_cast<bind::PrepareTransaction>(resolve_binder_symbol(
        bind::kSymbolPrepareTransaction, 1U << 1, &default_mask, &next_mask, &binder_mask));
    const auto transact = reinterpret_cast<bind::Transact>(resolve_binder_symbol(
        bind::kSymbolTransact, 1U << 2, &default_mask, &next_mask, &binder_mask));
    const auto read_int32 = reinterpret_cast<bind::ReadInt32>(resolve_binder_symbol(
        bind::kSymbolReadInt32, 1U << 3, &default_mask, &next_mask, &binder_mask));
    const auto parcel_delete = reinterpret_cast<bind::ParcelDelete>(resolve_binder_symbol(
        bind::kSymbolParcelDelete, 1U << 4, &default_mask, &next_mask, &binder_mask));
    const auto dec_strong = reinterpret_cast<bind::DecStrong>(resolve_binder_symbol(
        bind::kSymbolDecStrong, 1U << 5, &default_mask, &next_mask, &binder_mask));
    if (check_service == nullptr || prepare == nullptr || transact == nullptr ||
        read_int32 == nullptr || parcel_delete == nullptr || dec_strong == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, "HELauncherProbe",
            "Binder符号解析失败：default=0x%x next=0x%x binder=0x%x；channel=0x1；install_permitted=false",
            default_mask, next_mask, binder_mask);
        return launcher::kChannelNoBinder;
    }
    // 类关联所需的符号单独判定：它们不是「能不能用 binder」的前提，
    // 只有确实需要补关联（远端句柄）时才要求存在，缺了走 class_required 分支。
    const auto get_class = reinterpret_cast<bind::GetClass>(resolve_binder_symbol(
        bind::kSymbolGetClass, 1U << 6, &default_mask, &next_mask, &binder_mask));
    const auto class_descriptor = reinterpret_cast<bind::ClassGetDescriptor>(resolve_binder_symbol(
        bind::kSymbolClassGetDescriptor, 1U << 7, &default_mask, &next_mask, &binder_mask));
    const auto class_define = reinterpret_cast<bind::ClassDefine>(resolve_binder_symbol(
        bind::kSymbolClassDefine, 1U << 8, &default_mask, &next_mask, &binder_mask));
    const auto associate_class = reinterpret_cast<bind::AssociateClass>(resolve_binder_symbol(
        bind::kSymbolAssociateClass, 1U << 9, &default_mask, &next_mask, &binder_mask));
    bind::AIBinder *window = check_service("window");
    if (window == nullptr) return launcher::kChannelNoWindowService;
    // 远端句柄在 NDK 侧没有关联类，`prepareTransaction` 会因此失败。这里先
    // 按描述符核对：已关联且描述符相符就直接用；否则自己定义并关联一个。
    bool associated = false;
    if (get_class != nullptr && class_descriptor != nullptr) {
        const bind::AIBinder_Class *existing = get_class(window);
        if (existing != nullptr) {
            associated = descriptor_matches(class_descriptor(existing),
                bind::kWindowManagerDescriptor);
        } else if (class_define != nullptr && associate_class != nullptr) {
            bind::AIBinder_Class *window_class = window_manager_class(class_define);
            associated = window_class != nullptr && associate_class(window, window_class);
        }
    }
    if (!associated) {
        dec_strong(window);
        return launcher::kChannelNoWindowService;
    }
    bind::AParcel *input = nullptr;
    uint32_t blockers = launcher::kChannelTransactFailed;
    if (prepare(window, &input) == bind::kStatusOk && input != nullptr) {
        bind::AParcel *output = nullptr;
        const bind::status_t status = transact(window, launcher::kChannelTransaction,
            &input, &output, 0);
        if (status == bind::kStatusOk && output != nullptr) {
            // 回复体是 10 个 int32；读取顺序必须与 snapshot_wire.h 的字段顺序逐字一致。
            int32_t words[launcher::kSnapshotReplyWords]{};
            bool complete = true;
            for (int index = 0; index < launcher::kSnapshotReplyWords && complete; index++) {
                complete = read_int32(output, &words[index]) == bind::kStatusOk;
            }
            if (!complete) {
                blockers = launcher::kChannelShortReply;
            } else {
                // 传入上一次已采纳的序号作为防回放高水位：同一份回复不能重放，
                // 旧序号即使在租期内也必须被拒。
                const uint32_t last_revision = launcher::snapshot_record_revision(
                    __atomic_load_n(&g_snapshot_record, __ATOMIC_ACQUIRE));
                const int64_t now = elapsed_seconds();
                const auto decision = launcher::channel_decision(words,
                    launcher::kSnapshotReplyWords, last_revision, now);
                out = decision.snapshot;
                blockers = decision.blockers;
                snapshot_blockers = launcher::snapshot_errors(out, last_revision, now);
            }
        }
        if (output != nullptr) parcel_delete(output);
    }
    // transact 消耗并置空 input；prepare 失败但留下的句柄也必须释放。
    if (input != nullptr) parcel_delete(input);
    dec_strong(window);
    return blockers;
}

// 维护授权与普通快照分离查询。请求体仍为空，事务码决定仅允许的操作；服务端还要
// 看到设置页写入的短时请求才会返回非零材料。没有请求时只返回 false，不进入维护后端。
#if !defined(LAUNCHER_DIAGNOSTIC_ENTRY_TRIAL) || defined(LAUNCHER_RESTART_ENTRY_TRIAL)
bool query_maintenance_grant(MaintenanceGrantCarrier &carrier) {
    namespace bind = launcher::binder;
    uint32_t default_mask = 0;
    uint32_t next_mask = 0;
    uint32_t binder_mask = 0;
    if (load_binder_ndk() == nullptr) return false;
    const auto check_service = reinterpret_cast<bind::CheckService>(resolve_binder_symbol(
        bind::kSymbolCheckService, 1U << 0, &default_mask, &next_mask, &binder_mask));
    const auto prepare = reinterpret_cast<bind::PrepareTransaction>(resolve_binder_symbol(
        bind::kSymbolPrepareTransaction, 1U << 1, &default_mask, &next_mask, &binder_mask));
    const auto transact = reinterpret_cast<bind::Transact>(resolve_binder_symbol(
        bind::kSymbolTransact, 1U << 2, &default_mask, &next_mask, &binder_mask));
    const auto read_int32 = reinterpret_cast<bind::ReadInt32>(resolve_binder_symbol(
        bind::kSymbolReadInt32, 1U << 3, &default_mask, &next_mask, &binder_mask));
    const auto parcel_delete = reinterpret_cast<bind::ParcelDelete>(resolve_binder_symbol(
        bind::kSymbolParcelDelete, 1U << 4, &default_mask, &next_mask, &binder_mask));
    const auto dec_strong = reinterpret_cast<bind::DecStrong>(resolve_binder_symbol(
        bind::kSymbolDecStrong, 1U << 5, &default_mask, &next_mask, &binder_mask));
    if (check_service == nullptr || prepare == nullptr || transact == nullptr ||
        read_int32 == nullptr || parcel_delete == nullptr || dec_strong == nullptr) return false;
    const auto get_class = reinterpret_cast<bind::GetClass>(resolve_binder_symbol(
        bind::kSymbolGetClass, 1U << 6, &default_mask, &next_mask, &binder_mask));
    const auto class_descriptor = reinterpret_cast<bind::ClassGetDescriptor>(resolve_binder_symbol(
        bind::kSymbolClassGetDescriptor, 1U << 7, &default_mask, &next_mask, &binder_mask));
    const auto class_define = reinterpret_cast<bind::ClassDefine>(resolve_binder_symbol(
        bind::kSymbolClassDefine, 1U << 8, &default_mask, &next_mask, &binder_mask));
    const auto associate_class = reinterpret_cast<bind::AssociateClass>(resolve_binder_symbol(
        bind::kSymbolAssociateClass, 1U << 9, &default_mask, &next_mask, &binder_mask));
    bind::AIBinder *window = check_service("window");
    if (window == nullptr) return false;
    bool associated = false;
    if (get_class != nullptr && class_descriptor != nullptr) {
        const bind::AIBinder_Class *existing = get_class(window);
        if (existing != nullptr) {
            associated = descriptor_matches(class_descriptor(existing),
                bind::kWindowManagerDescriptor);
        } else if (class_define != nullptr && associate_class != nullptr) {
            bind::AIBinder_Class *window_class = window_manager_class(class_define);
            associated = window_class != nullptr && associate_class(window, window_class);
        }
    }
    if (!associated) {
        dec_strong(window);
        return false;
    }
    bind::AParcel *input = nullptr;
    bind::AParcel *output = nullptr;
    bool valid = false;
    if (prepare(window, &input) == bind::kStatusOk && input != nullptr) {
        if (transact(window, launcher::kMaintenanceTransaction, &input, &output, 0) == bind::kStatusOk && output != nullptr) {
            int32_t words[launcher::kMaintenanceReplyWords]{};
            bool complete = true;
            for (int index = 0; index < launcher::kMaintenanceReplyWords && complete; ++index) {
                complete = read_int32(output, &words[index]) == bind::kStatusOk;
            }
            if (complete) {
                const launcher::MaintenanceGrantWire wire{
                    words[0], words[1], words[2], words[3], words[4], words[5], words[6], words[7]};
                const auto wire_operation = static_cast<launcher::SafetyMaintenanceOperation>(wire.operation);
                if (launcher::maintenance_operation_supported(wire.operation) &&
                    launcher::maintenance_grant_shape(wire, wire_operation)) {
                    carrier.grant = launcher::maintenance_grant_from_wire(wire);
                    carrier.operation = wire_operation;
                    // 调用方还须核对快照令牌，并按明确的授权操作分流。
                    valid = carrier.grant.pid == current_pid() &&
                        carrier.grant.uid == static_cast<uint32_t>(getuid());
                }
            }
        }
    }
    if (output != nullptr) parcel_delete(output);
    if (input != nullptr) parcel_delete(input);
    dec_strong(window);
    return valid;
}
#endif

// 分享桥的 Binder 事务只发送一个协议号和包名。system_server 回复“已排队”
// 后立即返回，慢路径由 system_server 后台线程转发给模块 Provider。
uint32_t share_transaction_once(uint32_t point, const char *package_name) {
    namespace bind = launcher::binder;
    if (!launcher::share_point_supported(point) || package_name == nullptr ||
        launcher::package_name_char(package_name[0], true) == false) {
        return launcher::kShareBridgeInvalidPackage;
    }
    if (load_binder_ndk() == nullptr) return launcher::kShareBridgeNoBinder;
    uint32_t default_mask = 0, next_mask = 0, binder_mask = 0;
    const auto check_service = reinterpret_cast<bind::CheckService>(resolve_binder_symbol(
        bind::kSymbolCheckService, 1U << 0, &default_mask, &next_mask, &binder_mask));
    const auto prepare = reinterpret_cast<bind::PrepareTransaction>(resolve_binder_symbol(
        bind::kSymbolPrepareTransaction, 1U << 1, &default_mask, &next_mask, &binder_mask));
    const auto transact = reinterpret_cast<bind::Transact>(resolve_binder_symbol(
        bind::kSymbolTransact, 1U << 2, &default_mask, &next_mask, &binder_mask));
    const auto write_int32 = reinterpret_cast<bind::WriteInt32>(resolve_binder_symbol(
        bind::kSymbolWriteInt32, 1U << 3, &default_mask, &next_mask, &binder_mask));
    const auto write_string = reinterpret_cast<bind::WriteString>(resolve_binder_symbol(
        bind::kSymbolWriteString, 1U << 4, &default_mask, &next_mask, &binder_mask));
    const auto read_int32 = reinterpret_cast<bind::ReadInt32>(resolve_binder_symbol(
        bind::kSymbolReadInt32, 1U << 5, &default_mask, &next_mask, &binder_mask));
    const auto parcel_delete = reinterpret_cast<bind::ParcelDelete>(resolve_binder_symbol(
        bind::kSymbolParcelDelete, 1U << 6, &default_mask, &next_mask, &binder_mask));
    const auto dec_strong = reinterpret_cast<bind::DecStrong>(resolve_binder_symbol(
        bind::kSymbolDecStrong, 1U << 7, &default_mask, &next_mask, &binder_mask));
    if (check_service == nullptr || prepare == nullptr || transact == nullptr ||
        write_int32 == nullptr || write_string == nullptr || read_int32 == nullptr ||
        parcel_delete == nullptr || dec_strong == nullptr) {
        return launcher::kShareBridgeNoBinder;
    }
    const auto get_class = reinterpret_cast<bind::GetClass>(resolve_binder_symbol(
        bind::kSymbolGetClass, 1U << 8, &default_mask, &next_mask, &binder_mask));
    const auto class_descriptor = reinterpret_cast<bind::ClassGetDescriptor>(resolve_binder_symbol(
        bind::kSymbolClassGetDescriptor, 1U << 9, &default_mask, &next_mask, &binder_mask));
    const auto class_define = reinterpret_cast<bind::ClassDefine>(resolve_binder_symbol(
        bind::kSymbolClassDefine, 1U << 10, &default_mask, &next_mask, &binder_mask));
    const auto associate_class = reinterpret_cast<bind::AssociateClass>(resolve_binder_symbol(
        bind::kSymbolAssociateClass, 1U << 11, &default_mask, &next_mask, &binder_mask));
    bind::AIBinder *window = check_service("window");
    if (window == nullptr) return launcher::kShareBridgeNoService;
    bool associated = false;
    if (get_class != nullptr && class_descriptor != nullptr) {
        const bind::AIBinder_Class *existing = get_class(window);
        if (existing != nullptr) {
            associated = descriptor_matches(class_descriptor(existing),
                bind::kWindowManagerDescriptor);
        } else if (class_define != nullptr && associate_class != nullptr) {
            bind::AIBinder_Class *created = window_manager_class(class_define);
            associated = created != nullptr && associate_class(window, created);
        }
    }
    if (!associated) {
        dec_strong(window);
        return launcher::kShareBridgeNoService;
    }
    const size_t length = launcher::fixed_len(package_name);
    if (length == 0 || length > launcher::kDartMaxPackageNameLength) {
        dec_strong(window);
        return launcher::kShareBridgeInvalidPackage;
    }
    bind::AParcel *input = nullptr;
    uint32_t result = launcher::kShareBridgeTransactFailed;
    if (prepare(window, &input) == bind::kStatusOk && input != nullptr) {
        const bool wrote = write_int32(input, launcher::kShareProtocol) == bind::kStatusOk &&
            write_int32(input, static_cast<int32_t>(point)) == bind::kStatusOk &&
            write_string(input, package_name, static_cast<int32_t>(length)) == bind::kStatusOk;
        bind::AParcel *output = nullptr;
        const bind::status_t status = wrote
            ? transact(window, launcher::kShareTransaction, &input, &output, 0)
            : bind::kStatusOk;
        if (!wrote) {
            result = launcher::kShareBridgeTransactFailed;
        } else if (status != bind::kStatusOk || output == nullptr) {
            result = launcher::kShareBridgeTransactFailed;
        } else {
            int32_t ack = 0, protocol = 0;
            const bool complete = read_int32(output, &ack) == bind::kStatusOk &&
                read_int32(output, &protocol) == bind::kStatusOk;
            result = !complete ? launcher::kShareBridgeShortReply :
                launcher::share_reply_queued(ack, protocol)
                    ? launcher::kShareBridgeAccepted
                    : launcher::kShareBridgeRejected;
        }
        if (output != nullptr) parcel_delete(output);
    }
    if (input != nullptr) parcel_delete(input);
    dec_strong(window);
    return result;
}

namespace launcher_bridge {

uint32_t share_bridge_submit(uint32_t point, uintptr_t package_tagged) {
    (void)package_tagged;
    char package[launcher::kDartMaxPackageNameLength + 1]{};
    if (!launcher::copy_package_name(package_tagged, package, sizeof(package))) {
        return launcher::kShareBridgeInvalidPackage;
    }
    return share_transaction_once(point, package);
}

bool share_bridge_consume(uint32_t point, uintptr_t package_tagged, uint32_t *result) {
    if (result == nullptr) return false;
    *result = share_bridge_submit(point, package_tagged);
    return true;
}

}  // 命名空间 launcher_bridge

uint32_t query_configuration_once(launcher::Snapshot &snapshot, uint32_t &snapshot_blockers,
                                  uint32_t *preflight_result = nullptr,
                                  bool *persistence_committed = nullptr) {
    if (preflight_result != nullptr) *preflight_result = kPreflightChannelRejected;
    const uint32_t pid = current_pid();
    uint32_t owner = 0;
    if (!__atomic_compare_exchange_n(&g_channel_owner, &owner, pid, false,
            __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE) && owner != pid) {
        return launcher::kChannelWrongProcess;
    }
    if (__atomic_exchange_n(&g_channel_busy, 1, __ATOMIC_ACQ_REL) != 0) {
        return launcher::kChannelBusy;
    }
    __atomic_store_n(&g_channel_status, launcher::kChannelBusy, __ATOMIC_RELEASE);
    __atomic_store_n(&g_safety_preflight_record,
        (static_cast<uint64_t>(pid) << 32) | kPreflightNotRun, __ATOMIC_RELEASE);
    // 预检必须使用采纳前的高水位，不能把刚采纳的同一快照误判为回放。
    const uint32_t previous_revision = launcher::snapshot_record_revision(
        __atomic_load_n(&g_snapshot_record, __ATOMIC_ACQUIRE));
    const uint32_t blockers = query_snapshot(snapshot, snapshot_blockers);
    uint32_t safety_blockers = blockers == 0
        ? safety_preflight_now(snapshot, previous_revision, nullptr) : kPreflightChannelRejected;
    if (preflight_result != nullptr) *preflight_result = safety_blockers;
    __atomic_store_n(&g_safety_preflight_record,
        (static_cast<uint64_t>(pid) << 32) | safety_blockers, __ATOMIC_RELEASE);
    if (blockers == 0) {
        MaintenanceGrantCarrier *request_ptr = nullptr;
        #if !defined(LAUNCHER_DIAGNOSTIC_ENTRY_TRIAL) || defined(LAUNCHER_RESTART_ENTRY_TRIAL)
        MaintenanceGrantCarrier request{};
        #ifndef LAUNCHER_DIAGNOSTIC_ENTRY_TRIAL
        launcher::StartupRestartGrant local{};
        const int local_request = launcher::consume_root_restart_request(diagnostic_context_now(), local);
        if (local_request < 0) {
            launcher::experimental_entry_trace("restart_request_rejected", 1U);
            if (preflight_result != nullptr) *preflight_result = kPreflightSnapshotRejected;
            __atomic_store_n(&g_channel_status, blockers, __ATOMIC_RELEASE);
            __atomic_store_n(&g_channel_busy, 0, __ATOMIC_RELEASE);
            return blockers;
        }
        if (local_request == 1) {
            request.operation = local.recover ? launcher::SafetyMaintenanceOperation::kRearmKnown :
                launcher::SafetyMaintenanceOperation::kArmAbsent;
            request.grant = {local.pid, local.uid, request.operation,
                local.issued_s, local.expires_s, local.token};
            request_ptr = &request;
            // 本地请求由 root 文件所有权及原子消费认证，优先于旧端点未消费的历史令牌。
            snapshot.safety_reset_token = 0;
            launcher::experimental_entry_trace("root_restart_request", 1U);
        }
        #endif
        if (snapshot.safety_reset_token != 0) {
            const bool received = query_maintenance_grant(request);
            const bool planned = request.operation == launcher::SafetyMaintenanceOperation::kArmAbsent &&
                launcher::is_planned_restart_token(request.grant.reset_token);
            const bool recovery = request.operation == launcher::SafetyMaintenanceOperation::kRearmKnown;
            if (!received || request.grant.reset_token != snapshot.safety_reset_token ||
                (!planned && !recovery)) {
                if (preflight_result != nullptr) *preflight_result = kPreflightSnapshotRejected;
                __atomic_store_n(&g_channel_status, blockers, __ATOMIC_RELEASE);
                __atomic_store_n(&g_channel_busy, 0, __ATOMIC_RELEASE);
                return blockers;
            }
            // 只有独立事务已认证并消费相同令牌后，才能转回普通配置。
            request_ptr = &request;
            snapshot.safety_reset_token = 0;
        }
        #endif
        __atomic_store_n(&g_snapshot_record, launcher::snapshot_record(
            static_cast<uint32_t>(snapshot.revision), static_cast<uint32_t>(snapshot.features)),
            __ATOMIC_RELEASE);
        // 结构接线：只有取到满足字节契约的快照才进入唯一安装链路；
        // 快照门禁与安装许可共同决定这里是否进入唯一安装链路。
        const uint32_t install_result =
            guarded_install_once(snapshot, previous_revision, persistence_committed, request_ptr);
        launcher::experimental_entry_trace("install_result", install_result);
        // 只有整份快照被采纳后才启用预加载属性改写；任何安装/安全拒绝都保持透传。
        __atomic_store_n(&g_prestart_enabled,
            (install_result == 0 && launcher::snapshot_prestart_enabled(snapshot.features))
                ? 1U : 0U,
            __ATOMIC_RELEASE);
    }
    __atomic_store_n(&g_channel_status, blockers, __ATOMIC_RELEASE);
    __atomic_store_n(&g_channel_busy, 0, __ATOMIC_RELEASE);
    return blockers;
}

uint64_t current_snapshot_record() {
    if (__atomic_load_n(&g_channel_owner, __ATOMIC_ACQUIRE) != current_pid()) return 0;
    return __atomic_load_n(&g_snapshot_record, __ATOMIC_ACQUIRE);
}

void *diagnostic_worker(void *) {
    const auto context = diagnostic_context_now();
    launcher::experimental_entry_trace("worker_identity", context.identity);
    uint64_t expected = launcher::diagnostic_record(context.pid, launcher::kDiagnosticQueued);
    if (!launcher::diagnostic_context_ready(context)) {
        __atomic_compare_exchange_n(&g_diagnostic_record, &expected,
            launcher::diagnostic_record(context.pid, launcher::kDiagnosticIdentityRejected),
            false, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE);
        __android_log_print(ANDROID_LOG_WARN, "HELauncherProbe",
            "只读诊断：工作线程身份复核失败；install_permitted=false");
        return nullptr;
    }
    if (!__atomic_compare_exchange_n(&g_diagnostic_record, &expected,
            launcher::diagnostic_transition(expected, context.pid,
                launcher::kDiagnosticQueued, launcher::kDiagnosticRunning),
            false, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) return nullptr;
    // 无论是否启用实验安装，都必须先等现场 libapp.so 映射并完成全部生产点解析。
    // Rust 桌面会先加载运行时库，真正代码页稍后才出现；如果把一次过早的探测
    // 记成 Finished，就会消耗本进程唯一一次机会，表现为“刚生效，重启后又失效”。
    const auto ready = launcher::wait_for_host(
        [&context] {
            const auto now = diagnostic_context_now();
            return launcher::diagnostic_context_ready(now) && now.pid == context.pid &&
                now.uid == context.uid && now.euid == context.euid;
        },
        [] { return resolve_host_points(); },
        [] {
            const timespec delay{0, 50 * 1000 * 1000};
            return nanosleep(&delay, nullptr) == 0 || errno == EINTR;
        });
    const uint32_t probe = launcher::backend::launcher_image_probe_status();
    launcher::experimental_entry_trace("host_ready", static_cast<uint32_t>(ready));
    launcher::experimental_entry_trace("host_image_probe", probe);
    if (ready != launcher::StartupWaitResult::kReady) {
        __android_log_print(ANDROID_LOG_WARN, "HELauncherInstall",
            "宿主 libapp.so 尚未就绪或身份失效 ready=%u probe=0x%x；"
            "本次不消耗挂载机会，等待后续加载回调重试",
            static_cast<uint32_t>(ready), probe);
        // 身份失效与临时未映射都不能消耗本进程唯一一次机会：桌面会先在运行时库
        // 中完成特化，再加载 libapp.so，两种信号到达顺序并不固定。
        uint32_t attempt = __atomic_fetch_add(&g_host_retry_attempts, 1U, __ATOMIC_ACQ_REL) + 1U;
        __atomic_store_n(&g_diagnostic_record, 0, __ATOMIC_RELEASE);
        if (attempt <= 5U) {
            // 给 libapp.so 的后续加载留出窗口，再重新走同一身份/ELF 参数校验。
            const timespec delay{2, 0};
            (void)nanosleep(&delay, nullptr);
            maybe_schedule_diagnostic();
        } else {
            __android_log_print(ANDROID_LOG_WARN, "HELauncherInstall",
                "宿主映像在有限重试后仍未就绪；不再创建线程");
        }
        return nullptr;
    }
    __atomic_store_n(&g_host_retry_attempts, 0U, __ATOMIC_RELEASE);
    // 不记录 PID、UID、路径或设置令牌；仅输出固定阶段和协议字段。
    __android_log_print(ANDROID_LOG_INFO, "HELauncherProbe",
        "只读诊断：开始单次快照查询；入口已到达；install_permitted=false");
    launcher::Snapshot snapshot{};
    uint32_t snapshot_blockers = 0;
    uint32_t safety_blockers = kPreflightNotRun;
    bool persistence_committed = false;
    const uint32_t blockers = query_configuration_once(snapshot, snapshot_blockers,
        &safety_blockers, &persistence_committed);
    launcher::experimental_entry_trace("worker_channel", blockers);
    launcher::experimental_entry_trace("worker_snapshot", snapshot_blockers);
    launcher::experimental_entry_trace("worker_safety", safety_blockers);
    if (launcher::kExperimentalInstallEnabled) {
        const uint32_t features = snapshot.features;
        const uint32_t required_parts = launcher::required_batch_bits(features,
            launcher::kSnapshotFeatureCount, launcher::kSnapshotFeatureShare,
            launcher::kSnapshotFeatureFreeform);
        const bool installed = required_parts != 0 &&
            (points_installed() & required_parts) == required_parts;
        __android_log_print(ANDROID_LOG_INFO, "HELauncherInstall",
            "流程结束：channel=0x%x snapshot=0x%x revision=%d features=%d persisted=%s hooks_active=%s",
            blockers, snapshot_blockers, snapshot.revision, snapshot.features,
            persistence_committed ? "true" : "false", installed ? "true" : "false");
    } else {
    __android_log_print(ANDROID_LOG_INFO, "HELauncherProbe",
        "只读诊断：查询完成 channel=0x%x snapshot=0x%x revision=%d features=%d limit=%d armed=%d；install_permitted=false",
        blockers, snapshot_blockers, snapshot.revision, snapshot.features,
        snapshot.count_limit, snapshot.safety_armed);
    // 单独的标签，不把配置采纳日志升级为熔断持久化或门槛 3 的验收证据。
    __android_log_print(ANDROID_LOG_INFO, "HELauncherSafety",
        "只读安全预检：blockers=0x%x；persisted=%s；install_permitted=false",
        safety_blockers, persistence_committed ? "true" : "false");
    }
    __atomic_store_n(&g_diagnostic_record,
        launcher::diagnostic_record(context.pid, launcher::kDiagnosticFinished), __ATOMIC_RELEASE);
    return nullptr;
}

void maybe_schedule_diagnostic() {
    const auto context = diagnostic_context_now();
    uint64_t expected = __atomic_load_n(&g_diagnostic_record, __ATOMIC_ACQUIRE);
    if (!launcher::diagnostic_can_schedule(context, expected)) return;
    if (!__atomic_compare_exchange_n(&g_diagnostic_record, &expected,
            launcher::diagnostic_record(context.pid, launcher::kDiagnosticQueued),
            false, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) return;
    pthread_attr_t attributes{};
    bool created = false;
    if (pthread_attr_init(&attributes) == 0) {
        if (pthread_attr_setdetachstate(&attributes, PTHREAD_CREATE_DETACHED) == 0) {
            pthread_t thread{};
            created = pthread_create(&thread, &attributes, diagnostic_worker, nullptr) == 0;
        }
        pthread_attr_destroy(&attributes);
    }
    if (!created) {
        // 失败也消耗本进程这一次调度机会，避免高频加载回调反复尝试建线程。
        __atomic_store_n(&g_diagnostic_record,
            launcher::diagnostic_record(context.pid, launcher::kDiagnosticThreadFailed), __ATOMIC_RELEASE);
        __android_log_print(ANDROID_LOG_WARN, "HELauncherProbe",
            "只读诊断：工作线程创建失败，不重试；install_permitted=false");
    }
}

void launcher_child_adopted(bool name_signal) {
    if (!lifecycle_is_launcher_child(name_signal)) return;
    uint32_t expected = 0;
    if (__atomic_compare_exchange_n(&g_specialization_logged, &expected, 1U, false,
            __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
        if (launcher::kExperimentalInstallEnabled)
            launcher::experimental_entry_trace("specialized_child", host_identity_now());
        __android_log_print(ANDROID_LOG_INFO, "HELauncherProbe",
            "specialized launcher child observed; install_permitted=false");
    }
    maybe_schedule_diagnostic();
}

void on_module_loaded(const char *name, void *) {
    // 仅借用框架现有通知重新核对身份；不改 setprogname/property，不解析库名，
    // 不调用 hook_func，不等待 worker，也不在加载锁内同步访问 Binder。
    // 该记录用于区分「LSPosed 已发现模块」和「native_init 返回的回调确实被调用」；
    // 不把回调到达当作安装许可或宿主验收证据。
    uint32_t expected = 0;
    if (__atomic_compare_exchange_n(&g_module_loaded_logged, &expected, 1U, false,
            __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
        if (launcher::kExperimentalInstallEnabled)
            launcher::experimental_entry_trace("module_loaded", host_identity_now());
        __android_log_print(ANDROID_LOG_INFO, "HELauncherProbe",
            "module_loaded callback reached; install_permitted=false");
    }
    // Rust 桌面有版本不会按预期触发 setprogname/property 的先后顺序；libapp.so
    // 加载回调是参考项目保留的第三条兜底信号，仍须通过桌面身份门禁。
    if (name != nullptr && launcher_loaded_image_name(name)) launcher_child_adopted();
    maybe_schedule_diagnostic();
}

}  // 匿名命名空间

extern "C" __attribute__((visibility("default"), used))
launcher::ModuleLoadedCallback native_init(const launcher::NativeApiEntries *entries) {
    launcher::experimental_entry_trace("native_init_abi", entries != nullptr ? entries->version : 0U);
    if (launcher::kExperimentalInstallEnabled)
        launcher::experimental_entry_trace("native_init_identity", host_identity_now());
    // 该日志只确认框架确实调用了 native_init；不记录地址，也不改变安装门禁。
    __android_log_print(ANDROID_LOG_INFO, "HELauncherProbe",
        "native_init reached; entries=%s abi=%u; install_permitted=false",
        entries != nullptr ? "present" : "null", entries != nullptr ? entries->version : 0U);
    // 记录版本与两个指针，供后续只读诊断；保存指针不等于安装许可。
    const auto record = launcher::callback_record(current_pid(), entries ? entries->version : 0, entries != nullptr);
    __atomic_store_n(&g_callback_record, record, __ATOMIC_RELEASE);
    // 不按框架版本号筛选；入口函数指针和现场 Hook 点参数合同才是兼容性依据。
    const bool supported = launcher::native_entries_ready(entries);
    __atomic_store_n(&g_hook_func, supported ? reinterpret_cast<uint64_t>(entries->hook_func) : 0,
        __ATOMIC_RELEASE);
    __atomic_store_n(&g_unhook_func, supported ? reinterpret_cast<uint64_t>(entries->unhook_func) : 0,
        __ATOMIC_RELEASE);
    if (!supported) return nullptr;
    install_atfork_reset();
    g_entry_pid = current_pid();
    install_lifecycle_observers();
    maybe_schedule_diagnostic();
    return on_module_loaded;
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *, void *) {
    launcher::experimental_entry_trace("jni_onload", 0U);
    __atomic_store_n(&g_jni_pid, current_pid(), __ATOMIC_RELEASE);
    __android_log_print(ANDROID_LOG_INFO, "HELauncherProbe",
        "JNI_OnLoad reached; install_permitted=false");
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_YGHFv_HyperExtend_hook_launcher_LauncherNativeBridge_nativeStatus(JNIEnv *, jobject) {
    const uint32_t identity = host_identity_now();
    const uint32_t stored = (__atomic_load_n(&g_hook_func, __ATOMIC_ACQUIRE) != 0 ? launcher::kHookPointerStored : 0U) |
        (__atomic_load_n(&g_unhook_func, __ATOMIC_ACQUIRE) != 0 ? launcher::kUnhookPointerStored : 0U);
    const uint32_t host = (identity == launcher::kIdentityLauncherChild ? launcher::kHostSpecializedLauncher : 0U) |
        (identity == launcher::kIdentitySpawnerOther ? launcher::kHostSpawnerOther : 0U) |
        (identity == launcher::kIdentityUsapPool ? launcher::kHostUsapPool : 0U) |
        (identity == launcher::kIdentityUnknown ? launcher::kHostUnknown : 0U);
    return static_cast<jint>(launcher::probe_status(current_pid(),
        __atomic_load_n(&g_jni_pid, __ATOMIC_ACQUIRE),
        __atomic_load_n(&g_callback_record, __ATOMIC_ACQUIRE)) | stored | host);
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_YGHFv_HyperExtend_hook_launcher_LauncherNativeBridge_nativeApiVersion(JNIEnv *, jobject) {
    return launcher::observed_version(current_pid(), __atomic_load_n(&g_callback_record, __ATOMIC_ACQUIRE));
}

// 只在显式诊断调用里执行一次同步事务。它不安装任何 hook，也不构成安装许可：
// 返回值是 channel.h 的阻断位，0 只表示「这一次取到了满足字节契约的配置」。
extern "C" JNIEXPORT jint JNICALL
Java_io_github_YGHFv_HyperExtend_hook_launcher_LauncherNativeBridge_nativeQueryConfiguration(JNIEnv *, jobject) {
    launcher::Snapshot snapshot{};
    uint32_t snapshot_blockers = 0;
    return static_cast<jint>(query_configuration_once(snapshot, snapshot_blockers));
}

// 未查询、在途与失败均为非零；fork 继承的成功不能当成本进程已验收。
extern "C" JNIEXPORT jint JNICALL
Java_io_github_YGHFv_HyperExtend_hook_launcher_LauncherNativeBridge_nativeChannelStatus(JNIEnv *, jobject) {
    const uint32_t owner = __atomic_load_n(&g_channel_owner, __ATOMIC_ACQUIRE);
    if (owner != 0 && owner != current_pid()) return launcher::kChannelWrongProcess;
    return static_cast<jint>(__atomic_load_n(&g_channel_status, __ATOMIC_ACQUIRE));
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_YGHFv_HyperExtend_hook_launcher_LauncherNativeBridge_nativeSnapshotRevision(JNIEnv *, jobject) {
    return static_cast<jlong>(launcher::snapshot_record_revision(current_snapshot_record()));
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_YGHFv_HyperExtend_hook_launcher_LauncherNativeBridge_nativeSnapshotFeatures(JNIEnv *, jobject) {
    return static_cast<jlong>(launcher::snapshot_record_features(current_snapshot_record()));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_YGHFv_HyperExtend_hook_launcher_LauncherNativeBridge_nativeBeginConfiguration(JNIEnv *, jobject) {
    const uint32_t pid = current_pid();
    if (!configuration_session_ready(pid)) return JNI_FALSE;
    auto previous = __atomic_load_n(&g_config_revision, __ATOMIC_ACQUIRE);
    if (launcher::revision_owner(previous) == pid) return JNI_TRUE;
    // 子进程需要自己收到 JNI 和原生回调才能开始；同一进程不能重置防回放高水位。
    return __atomic_compare_exchange_n(&g_config_revision, &previous, launcher::revision_record(pid, 0),
        false, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_YGHFv_HyperExtend_hook_launcher_LauncherNativeBridge_nativeCheckConfiguration(
    JNIEnv *env, jobject, jlongArray words) {
    using namespace launcher;
    constexpr uint32_t blocked = kUnverifiedPermissions;
    if (words == nullptr || env->GetArrayLength(words) != kConfigWords) return blocked | kMalformedConfig;
    jlong values[kConfigWords]{};
    env->GetLongArrayRegion(words, 0, kConfigWords, values);
    if (env->ExceptionCheck()) return blocked | kMalformedConfig;
    const ConfigPacket packet{values[0], values[1], values[2], values[3], values[4], values[5], values[6]};
    const uint32_t pid = current_pid();
    auto previous = __atomic_load_n(&g_config_revision, __ATOMIC_ACQUIRE);
    if (!configuration_session_ready(pid) || revision_owner(previous) != pid) return blocked | kSessionUnavailable;
    const auto errors = config_errors(packet, pid, static_cast<uint32_t>(previous), elapsed_ms());
    // 全关配置也消耗修订号，避免旧的启用包在关闭后再次被接收；从不保留功能配置。
    if ((errors & ~kNoFeatures) != 0) return blocked | errors;
    if (!__atomic_compare_exchange_n(&g_config_revision, &previous,
        revision_record(pid, static_cast<uint32_t>(packet.revision)), false,
        __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) return blocked | kConcurrentUpdate;
    return blocked | errors;
}
