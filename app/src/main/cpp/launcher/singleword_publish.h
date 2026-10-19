/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#pragma once

#include <stddef.h>
#include <stdint.h>

// 四项菜单快捷功能共用的**单字发布协议**生产实现。
//
// 为什么是「单字」而不是 Dobby 的 24 字节足迹：
//   ARM DDI 0487 L.a B2.2.5 把 B/B.cond/BL/BRK/CBNZ/CBZ/HVC/ISB/NOP/SMC/SVC/
//   TBNZ/TBZ/TRCIT 列为「旧、新都属于本集合时，无需执行端同步，架构保证行为等价于
//   取到旧指令或新指令之一」的例外集合。四项功能的挂载调用边本身就是 `BL`，
//   所以「把 `BL 原目标` 换成 `BL 蹦床`」只需要一次对齐的 32 位存储，
//   不需要 mprotect+memcpy 一次覆盖多条指令的中间态。
//
// 本文件只包含缓存同步与单字提交原语。分配、重定位、封 RX 与调用点事务在
// count_backend.cpp 中实现，并由生产目标接入；临时权限窗口、失败回滚与并发执行
// 仍须 Gate 1/5 实机验收，安装许可与计划校验通过前不得放行。
// 本文件刻意不含任何 mmap/mprotect/dlopen，因此可以独立取证而不会造出写入口。
//
// 与 Python 侧审计的对应关系（两边必须同时成立，任一侧失效都直接失败）：
//   tools/launcher_gate5_residual_audit.py  编译本实现并核对机器码
//   tools/launcher_native_audit.py          concurrent_modification_kind 分类器
// 本文件里的 static_assert 是**编译期**证据：被包含进
// `launcher_singleword_publish_test.cpp`（原型构建的 `launcher_probe_contract` 目标）
// 后一旦编译不过，构建就失败，而不是等到运行期才发现选错了机器字。
// 注意该目标只在 `-PlauncherNativePrototype=true` 时存在，所以这条构建期检查
// 覆盖的是原型构建；普通构建本就不含桌面原生部分。
namespace launcher {
namespace singleword {

// ---------------------------------------------------------------------------
// 一、B2.2.5 例外集合分类
// ---------------------------------------------------------------------------

// 手册列出的成员里，TRCIT 的编码本实现**不独立解码**（生产实现从不产生也不
// 接受 TRCIT 作为新旧字），因此分类器对它只会落到 kOther，从而被 fail-closed 拒绝，
// 不会因为「认不出来就当通过」而放行。
enum class WordKind : uint32_t {
    kOther = 0,
    kB,
    kBl,
    kBCond,
    kBrk,
    kCbz,
    kCbnz,
    kHvc,
    kIsb,
    kNop,
    kSmc,
    kSvc,
    kTbz,
    kTbnz,
};

// 逐位与 tools/launcher_native_audit.py#concurrent_modification_kind 同构。
// 两处任一改动都必须同步，否则 Python 侧的分类器校验样本会失败。
constexpr WordKind classify(uint32_t word) {
    if ((word & 0x7C000000U) == 0x14000000U) return (word & 0x80000000U) ? WordKind::kBl : WordKind::kB;
    if ((word & 0xFF000000U) == 0x54000000U) return WordKind::kBCond;
    if (word == 0xD503201FU) return WordKind::kNop;
    if (word == 0xD5033FDFU) return WordKind::kIsb;
    if ((word & 0xFFE0001FU) == 0xD4200000U) return WordKind::kBrk;
    if ((word & 0xFFE0001FU) == 0xD4000001U) return WordKind::kSvc;
    if ((word & 0xFFE0001FU) == 0xD4000002U) return WordKind::kHvc;
    if ((word & 0xFFE0001FU) == 0xD4000003U) return WordKind::kSmc;
    if ((word & 0x7E000000U) == 0x34000000U) return (word & 0x01000000U) ? WordKind::kCbnz : WordKind::kCbz;
    if ((word & 0x7E000000U) == 0x36000000U) return (word & 0x01000000U) ? WordKind::kTbnz : WordKind::kTbz;
    return WordKind::kOther;
}

constexpr bool in_exception_set(uint32_t word) { return classify(word) != WordKind::kOther; }

// ---------------------------------------------------------------------------
// 二、BL 的编码与可达性
// ---------------------------------------------------------------------------

// `BL` 的 26 位有符号立即数（按 4 对齐）——可达范围是 ±2^27 字节。
// 这是编码硬约束，不是策略选择：蹦床必须落在调用点 ±128MiB 内。
constexpr int64_t kBranchReachBytes = INT64_C(1) << 27;

constexpr bool aligned4(uintptr_t address) { return (address & 3U) == 0U; }

constexpr bool branch_delta_ok(int64_t delta) {
    return delta % 4 == 0 && delta >= -kBranchReachBytes && delta < kBranchReachBytes;
}

// 编出 `BL target`；调用方必须先确认 branch_delta_ok。
constexpr uint32_t encode_bl(uintptr_t pc, uintptr_t target) {
    const int64_t delta = static_cast<int64_t>(target) - static_cast<int64_t>(pc);
    return 0x94000000U | (static_cast<uint32_t>(delta / 4) & 0x03FFFFFFU);
}

// 入口替换必须使用不写 x30 的 B：替换函数的 RET 要回到原调用者，
// 不能像 BL 那样先把返回地址改成“原函数第二条指令”。
constexpr uint32_t encode_b(uintptr_t pc, uintptr_t target) {
    const int64_t delta = static_cast<int64_t>(target) - static_cast<int64_t>(pc);
    return 0x14000000U | (static_cast<uint32_t>(delta / 4) & 0x03FFFFFFU);
}

constexpr bool is_bl_word(uint32_t word) { return (word & 0xFC000000U) == 0x94000000U; }

// 独立反解，避免只用编码函数自证。调用方必须先确认 is_bl_word；
// 对非 BL 字调用没有意义（这里不假装能给出答案）。
constexpr uintptr_t bl_target_of(uintptr_t pc, uint32_t word) {
    int64_t displacement = static_cast<int64_t>(word & 0x03FFFFFFU);
    if (displacement & 0x02000000) displacement -= 0x04000000;
    return static_cast<uintptr_t>(static_cast<int64_t>(pc) + displacement * 4);
}

// ---------------------------------------------------------------------------
// 三、提交计划与门禁
// ---------------------------------------------------------------------------

enum class Fault : uint32_t {
    kNone = 0,
    kEmptyTrampoline = 1,      // 蹦床长度为零：没有新代码可同步
    kSiteUnaligned = 2,        // 调用点未按 4 字节对齐：不是单次 32 位对齐存储
    kTrampolineUnaligned = 3,  // 蹦床未按 4 字节对齐
    kOldWordNotBl = 4,         // 当前那条指令不是 BL
    kOldWordNotEligible = 5,   // 旧字不在 B2.2.5 例外集合内
    kNewWordNotBl = 6,         // 新字不是 BL
    kNewWordNotEligible = 7,   // 新字不在 B2.2.5 例外集合内
    kTargetMismatch = 8,       // 旧字解出来的目标与记录的原目标不一致
    kTrampolineOutOfReach = 9, // 蹦床超出 BL 的 26 位立即数范围
    kOldWordDrifted = 10,      // 运行期发现现场字既不是旧值（已被别人改过）
    kNewWordTargetMismatch = 11, // 新字解出来的目标不是我们要挂的蹦床
};

// 一份待执行的单字替换。字段全是纯值，便于编译期断言与运行期复算。
struct CommitPlan {
    uintptr_t site;          // 调用点运行地址
    uintptr_t original;      // 该 BL 原本指向的目标
    uintptr_t trampoline;    // 我们自己的蹦床运行地址
    uint32_t expected_old;   // 调用点现场应当仍是这个字
    uint32_t new_word;       // 要写进去的那个字
    size_t trampoline_bytes; // 蹦床已写好的代码字节数
};

// 纯值门禁：不取内存、不写内存，因此可以在编译期对整张判定表做断言。
constexpr Fault plan_fault(const CommitPlan &plan) {
    if (plan.trampoline_bytes == 0) return Fault::kEmptyTrampoline;
    if (!aligned4(plan.site)) return Fault::kSiteUnaligned;
    if (!aligned4(plan.trampoline)) return Fault::kTrampolineUnaligned;
    if (!in_exception_set(plan.expected_old)) return Fault::kOldWordNotEligible;
    if (!in_exception_set(plan.new_word)) return Fault::kNewWordNotEligible;
    if (!is_bl_word(plan.expected_old)) return Fault::kOldWordNotBl;
    const uintptr_t old_target = bl_target_of(plan.site, plan.expected_old);
    if (old_target != plan.original) return Fault::kTargetMismatch;
    if (!is_bl_word(plan.new_word)) return Fault::kNewWordNotBl;
    // 可达性必须先于目标比较：超出 26 位立即数范围时，编码本身就会被截断，
    // 再拿截断后的字去比目标只会得到「指向别处」这个次要结论，掩盖真正原因。
    if (!branch_delta_ok(static_cast<int64_t>(plan.trampoline) - static_cast<int64_t>(plan.site))) {
        return Fault::kTrampolineOutOfReach;
    }
    if (bl_target_of(plan.site, plan.new_word) != plan.trampoline) {
        return Fault::kNewWordTargetMismatch;
    }
    return Fault::kNone;
}

// ---------------------------------------------------------------------------
// 四、两个原语
// ---------------------------------------------------------------------------

// 缓存同步：dc cvau → dsb ish → ic ivau → dsb ish。
//
// 依据 Arm 官方文章《Caches and Self-Modifying Code: Working with Threads》
// （Jacob Bramley, 2025-01-21）的**预取推测保护**：只要新代码在改分支**之前**
// 完成这条同步，任何取到新分支的执行线程必定也能取到同步后的新代码。
// 顺序颠倒（先提交后同步）会破坏该保证，由 tools/launcher_gate5_residual_audit.py
// 的 verify_publish_order 与故障对照一并拒绝。
//
// 两个循环步长都是 64 字节（缓存维护指令的最小粒度下界）；按 64 递增必然覆盖
// 整段，因为 arch 的缓存行长最大 2KB 但 dc/ic 作用于整个 cache line。
// 这里按 64 递增只保证每个 64 字节块都被触及一次，真实粒度由硬件决定。
__attribute__((always_inline)) inline void publish_code(void *code, size_t bytes) {
    const uintptr_t start = reinterpret_cast<uintptr_t>(code);
    const uintptr_t end = start + bytes;
    for (uintptr_t cursor = start; cursor < end; cursor += 64) {
        __asm__ __volatile__("dc cvau, %0" ::"r"(cursor) : "memory");
    }
    __asm__ __volatile__("dsb ish" ::: "memory");
    for (uintptr_t cursor = start; cursor < end; cursor += 64) {
        __asm__ __volatile__("ic ivau, %0" ::"r"(cursor) : "memory");
    }
    __asm__ __volatile__("dsb ish" ::: "memory");
}

// 提交：把调用点那**一个**对齐的 32 位字换成 `BL 蹦床`。
//
// 必须是一条 32 位对齐存储。写成 stp/str x/memcpy 都会产生「新旧混合执行」的
// 中间态，而 B2.2.5 的例外保证只对**单条**指令成立（官方文章明确警告：
// 多字修改下旧指令可能在新指令已被执行之后仍被执行，且没有时间上限）。
// tools/launcher_gate5_residual_audit.py 会反汇编本函数，要求它恰好是
// 「一条 32 位存储 + ret」；tools/launcher_singleword_commit_fault_audit.py
// 用六种真实错误实现证明该判定确实会拒绝多字写法。
__attribute__((always_inline)) inline void store_release(uint32_t *site, uint32_t word) {
    __atomic_store_n(site, word, __ATOMIC_RELEASE);
}

// ---------------------------------------------------------------------------
// 五、组合动作
// ---------------------------------------------------------------------------

// 先同步蹦床、再提交调用点。顺序是本协议不可交换的部分。
inline Fault publish_then_commit(const CommitPlan &plan) {
    const Fault fault = plan_fault(plan);
    if (fault != Fault::kNone) return fault;
    publish_code(reinterpret_cast<void *>(plan.trampoline), plan.trampoline_bytes);
    auto *site = reinterpret_cast<uint32_t *>(plan.site);
    // 现场字必须仍是记录的那个字。被别人改过（例如另一套模块先装了 hook）时
    // 直接放弃，绝不「按新字覆盖回去」——那会把别人的补丁抹掉而不留证据。
    if (__atomic_load_n(site, __ATOMIC_ACQUIRE) != plan.expected_old) return Fault::kOldWordDrifted;
    store_release(site, plan.new_word);
    return Fault::kNone;
}

// ---------------------------------------------------------------------------
// 六、编译期证据
// ---------------------------------------------------------------------------

// 分类器必须与 Python 侧样本一致：B、BL、B.cond、CBZ/CBNZ、TBZ/TBNZ、NOP、ISB、
// BRK、SVC、HVC、SMC 全部命中；代表性非例外字一律不命中。
static_assert(classify(0x14000000U) == WordKind::kB, "B 必须属于例外集合");
static_assert(classify(0x94000000U) == WordKind::kBl, "BL 必须属于例外集合");
static_assert(classify(0x5400000CU) == WordKind::kBCond, "B.cond 必须属于例外集合");
static_assert(classify(0x34000000U) == WordKind::kCbz, "CBZ 必须属于例外集合");
static_assert(classify(0x35000000U) == WordKind::kCbnz, "CBNZ 必须属于例外集合");
static_assert(classify(0xB4000000U) == WordKind::kCbz, "CBZ（64 位形式）必须属于例外集合");
static_assert(classify(0xB5000000U) == WordKind::kCbnz, "CBNZ（64 位形式）必须属于例外集合");
static_assert(classify(0x36000000U) == WordKind::kTbz, "TBZ 必须属于例外集合");
static_assert(classify(0x37000000U) == WordKind::kTbnz, "TBNZ 必须属于例外集合");
static_assert(classify(0xB6000000U) == WordKind::kTbz, "TBZ（64 位形式）必须属于例外集合");
static_assert(classify(0xB7000000U) == WordKind::kTbnz, "TBNZ（64 位形式）必须属于例外集合");
static_assert(classify(0xD503201FU) == WordKind::kNop, "NOP 必须属于例外集合");
static_assert(classify(0xD5033FDFU) == WordKind::kIsb, "ISB 必须属于例外集合");
static_assert(classify(0xD4200000U) == WordKind::kBrk, "BRK 必须属于例外集合");
static_assert(classify(0xD4000001U) == WordKind::kSvc, "SVC 必须属于例外集合");
static_assert(classify(0xD4000002U) == WordKind::kHvc, "HVC 必须属于例外集合");
static_assert(classify(0xD4000003U) == WordKind::kSmc, "SMC 必须属于例外集合");
// 宿主函数序言、mov、字面量取数、返回都不在集合内——这正是「改入口」路线
// 不能走、必须改调用点的原因。
static_assert(classify(0xA9BF79FDU) == WordKind::kOther, "stp 序言不得被当成例外成员");
static_assert(classify(0xAA0103E0U) == WordKind::kOther, "mov 不得被当成例外成员");
static_assert(classify(0x58000091U) == WordKind::kOther, "字面量取数不得被当成例外成员");
static_assert(classify(0xF85F83A1U) == WordKind::kOther, "ldur 不得被当成例外成员");
static_assert(classify(0x910082C0U) == WordKind::kOther, "add 不得被当成例外成员");
static_assert(classify(0xD65F03C0U) == WordKind::kOther, "ret 不得被当成例外成员");

// 可达性边界：正好 ±2^27 分别处于内侧与外内侧，不能有 off-by-one。
static_assert(branch_delta_ok(kBranchReachBytes - 4), "上界内侧必须可达");
static_assert(!branch_delta_ok(kBranchReachBytes), "上界本身必须不可达");
static_assert(branch_delta_ok(-kBranchReachBytes), "下界内侧必须可达");
static_assert(!branch_delta_ok(-kBranchReachBytes - 4), "下界外侧必须不可达");
static_assert(!branch_delta_ok(2), "未按 4 对齐的偏移必须拒绝");

// 编码与独立反解必须互逆。取一对跨越正负号位的偏移，确保符号扩展没写错。
namespace evidence {
constexpr uintptr_t kSite = 0x6EC6B53018ULL;
constexpr uintptr_t kOriginal = 0x6EC6B5B4F4ULL;
constexpr uintptr_t kTrampoline = 0x6EC7250000ULL;

constexpr uint32_t kOldWord = encode_bl(kSite, kOriginal);
constexpr uint32_t kNewWord = encode_bl(kSite, kTrampoline);

constexpr CommitPlan kPlan{kSite, kOriginal, kTrampoline, kOldWord, kNewWord, 64};

// 反解必须回到编码时用的目标。反解函数与编码函数是两段独立算术，
// 因此这是互逆性证据，不是用同一个函数自证。
static_assert(is_bl_word(kOldWord) && bl_target_of(kSite, kOldWord) == kOriginal,
    "旧字编解码必须互逆");
static_assert(is_bl_word(kNewWord) && bl_target_of(kSite, kNewWord) == kTrampoline,
    "新字编解码必须互逆");
static_assert(plan_fault(kPlan) == Fault::kNone, "合法计划必须通过门禁");

// 门禁的否定性证据：每一类越界都必须被**具体**拒绝，不是笼统失败。
static_assert(plan_fault(CommitPlan{kSite, kOriginal, kTrampoline, kOldWord, kNewWord, 0}) ==
    Fault::kEmptyTrampoline, "零长度蹦床必须被拒绝");
static_assert(plan_fault(CommitPlan{kSite + 2, kOriginal, kTrampoline, kOldWord, kNewWord, 64}) ==
    Fault::kSiteUnaligned, "未对齐调用点必须被拒绝");
static_assert(plan_fault(CommitPlan{kSite, kOriginal, kTrampoline + 2, kOldWord, kNewWord, 64}) ==
    Fault::kTrampolineUnaligned, "未对齐蹦床必须被拒绝");
static_assert(plan_fault(CommitPlan{kSite, kOriginal, kTrampoline, 0xA9BF79FDU, kNewWord, 64}) ==
    Fault::kOldWordNotEligible, "非例外集合的旧字必须被拒绝");
static_assert(plan_fault(CommitPlan{kSite, kOriginal, kTrampoline, kOldWord, 0xD65F03C0U, 64}) ==
    Fault::kNewWordNotEligible, "非例外集合的新字必须被拒绝");
static_assert(plan_fault(CommitPlan{kSite, kOriginal, kTrampoline, 0xD503201FU, kNewWord, 64}) ==
    Fault::kOldWordNotBl, "旧字不是 BL 必须被拒绝（即使它在例外集合内）");
static_assert(plan_fault(CommitPlan{kSite, kOriginal, kTrampoline, encode_bl(kSite, kOriginal + 4),
    kNewWord, 64}) == Fault::kTargetMismatch, "旧字指向别的目标必须被拒绝");
static_assert(plan_fault(CommitPlan{kSite, kOriginal, kTrampoline, kOldWord,
    encode_bl(kSite, kTrampoline + 4), 64}) == Fault::kNewWordTargetMismatch,
    "新字指向的不是蹦床必须被拒绝");
static_assert(plan_fault(CommitPlan{kSite, kOriginal, kSite + kBranchReachBytes, kOldWord,
    encode_bl(kSite, kSite + kBranchReachBytes), 64}) == Fault::kTrampolineOutOfReach,
    "超出 BL 立即数范围的蹦床必须被拒绝");

// 站点与蹦床相隔很远（跨段）时依旧必须闭合：本设备实测差值约 0x6E7B000，
// 远小于 ±128MiB，所以不走第二蹦床/长跳。
static_assert(branch_delta_ok(static_cast<int64_t>(kTrampoline) - static_cast<int64_t>(kSite)),
    "已取证实例的站点到蹦床距离必须可达");
}  // 命名空间 evidence

}  // 命名空间 singleword
}  // 命名空间 launcher
