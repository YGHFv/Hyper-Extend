"""防砖一致性审计的合成回归；不把合成通过当成实机防砖确认。

这里的每一类反例都对应审查中确认过的「文本装饰即可翻绿」漏洞：
只加 include、注释里写字面量、单字符串、README 任意位置出现属性名、
调换判定顺序、空壳测试方法、合同与源码同改等，都必须被 fail-closed 拒绝。
"""

import hashlib
import io
import json
import re
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import launcher_safety_parity_audit as audit
from launcher_native_audit import AuditError


def contract():
    return json.loads(audit.DEFAULT_CONTRACT.read_text(encoding="utf-8"))


def case_tables_digest(value):
    return hashlib.sha256(json.dumps(
        {"cases": value["cases"], "decodeCases": value["decodeCases"]},
        ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


KOTLIN_FUSE = """\
internal data class BootFuseState(
    val lastStartAt: Long = 0,
    val rapidRestarts: Int = 0,
    val disabled: Boolean = false,
    val lastResetToken: Long = 0,
    val windowStartAt: Long = 0,
) {
    companion object {
        fun decode(text: String): BootFuseState? {
            val p = text.trim().split(',')
            if (p.size !in 2..5) return null
            return BootFuseState(last, count, disabled || count >= 3, reset, window)
        }
    }
}

internal object BootFusePolicy {
    const val WINDOW_MS = 5 * 60 * 1000L
    const val MIN_TIME = 1_600_000_000_000L

    fun next(previous: BootFuseState?, now: Long, resetToken: Long, countRestart: Boolean): BootFuseState? {
        if (previous == null || now < MIN_TIME) return null
        if (resetToken > previous.lastResetToken) return previous
        if (previous.disabled) return previous
        if (now < previous.lastStartAt) return null
        if (!countRestart) return previous
        val sameWindow = previous.lastStartAt > 0 && now - previous.windowStartAt <= WINDOW_MS
        val count = if (sameWindow) (previous.rapidRestarts + 1).coerceAtMost(3) else 0
        return BootFuseState(now, count, count >= 3, previous.lastResetToken,
            if (sameWindow) previous.windowStartAt else now)
    }
}
"""


def kotlin_fuse_test(body="assertEquals(true, BootFusePolicy.next(null, 0L, 0L, true) == null)"):
    return "\n".join("    @Test fun %s() { %s }" % (name, body)
                     for name in contract()["kotlinTestMethods"])


KOTLIN_FUSE_TEST = kotlin_fuse_test()

KOTLIN_KILL_SWITCH = """\
internal object KillSwitch {
    private const val PROPERTY_NAME = "persist.sys.hyperextend.disabled"
    private val FLAG_FILES = listOf(
        "/data/local/tmp/hyperextend.disabled",
        "/sdcard/HyperExtend/disable",
    )
    private fun propertyFlag(): Boolean = try {
        val value = read()
        value?.trim() != ""
    } catch (failure: Throwable) {
        true
    }
}
"""

KOTLIN_BOOT_LOOP_GUARD = """\
internal object BootLoopGuard {
    private const val STATE_FILE = "/data/system/hyperextend_bootguard"
    private const val DISABLED_PROPERTY = "persist.sys.hyperextend.framework_disabled"
    fun shouldSkip(): Boolean {
        if (readDisabledProperty() != false || next.disabled) return true
        return false
    }
    private fun checkSystemUi(): Boolean {
        if (readDisabledProperty() != false) return true
        val store = BootFuseStore(File(dataDir, "files/hyperextend_systemui_bootguard"))
        return store.read() != null
    }
    private fun readDisabledProperty(): Boolean? = try {
        readProperty()
    } catch (t: Throwable) {
        null
    }
}
"""

NATIVE_SAFETY_GATE = """\
namespace launcher {

constexpr char kLauncherSafetyScope[] = "com.miui.home";

enum SafetyBlocker : uint32_t {
    kSafetyStateUnreadable = 1U << 0,
    kSafetyFuseDisabled = 1U << 1,
    kSafetyUnsafeClock = 1U << 2,
    kSafetyNotArmed = 1U << 3,
};

struct SafetyFuseState {
    int64_t last_start_ms;
    uint32_t rapid_restarts;
    bool disabled;
    int64_t last_reset_token;
    int64_t window_start_ms;
};

constexpr int64_t kSafetyFuseWindowMs = 5 * 60 * 1000;
constexpr int64_t kSafetyMinTimeMs = 1'600'000'000'000;

constexpr bool decode_safety_fuse(const char *text, uint32_t size, SafetyFuseState &out) {
    const uint32_t count = 0;
    out = SafetyFuseState{0, count, count >= 3, 0, 0};
    return size != 0 && text != nullptr;
}

constexpr SafetyFuseDecision safety_fuse_next(const SafetyFuseState &previous, bool have_previous,
    int64_t now, int64_t reset_token, bool count_restart) {
    if (!have_previous || now < kSafetyMinTimeMs) return {};
    if (reset_token > previous.last_reset_token) return {};
    if (previous.disabled) return {};
    if (now < previous.last_start_ms) return {};
    if (!count_restart) return {};
    const bool same_window = previous.last_start_ms > 0 && now - previous.window_start_ms <= kSafetyFuseWindowMs;
    const uint32_t count = same_window ? (previous.rapid_restarts + 1 > 3 ? 3 : previous.rapid_restarts + 1) : 0;
    const bool tripped = count >= 3;
    return {true, SafetyFuseState{now, count, tripped, previous.last_reset_token, now}, 0};
}

constexpr SafetyFuseDecision safety_fuse_next(const SafetyFuseState &previous, bool have_previous,
    int64_t now, int64_t reset_token, bool count_restart, bool extra) {
    return now < kSafetyMinTimeMs ? previous : previous;
}

}  // namespace launcher
"""

NATIVE_INSTALL_PERMIT = """\
namespace launcher {

#if defined(LAUNCHER_NATIVE_EXPERIMENTAL_INSTALL)
constexpr bool kExperimentalInstallEnabled = true;
#else
constexpr bool kExperimentalInstallEnabled = false;
#endif

constexpr uint32_t permit_blockers(const PermitEvidence &evidence) {
    return (host_is_specialized_launcher(evidence.host_identity) ? 0U : kPermitHostUnverified) |
        (evidence.entries_present ? 0U : kPermitEntriesUnverified) |
        (evidence.safety_verified ? 0U : kPermitSafetyUnverified) |
        (evidence.backend_verified ? 0U : kPermitBackendUnverified);
}

constexpr bool install_permitted(const PermitEvidence &evidence) {
    return permit_blockers(evidence) == 0;
}

}  // namespace launcher
"""

# 旧审计器会误认的孤立早退：没有真实入口、持久化或安装支配关系。
NATIVE_ENTRY_ISOLATED_GUARD = """\
#include "native_api.h"
#include "install_permit.h"

bool check(const PermitEvidence &evidence, const SafetyFuseState &previous) {
    const bool permitted = launcher::install_permitted(evidence);
    if (!permitted) return false;
    safety_fuse_next(previous, true, 0, 0, false);
    return true;
}
"""

NATIVE_ENTRY_UNWIRED = """\
#include "native_api.h"
#include "probe_state.h"

void check() { }
"""

# 接线到位的合成形态：从导出入口可达地提交状态，并由提交结果支配安装路径。
# 只用于证明验证器会在真正接线时翻绿，不是仓库里的真实接线。
NATIVE_ENTRY_WIRED = """\
#include "native_api.h"
#include "install_permit.h"
#include "safety_startup.h"

struct LauncherInstallPlan {
    HookFunction hook_func;
    void *target;
    void *replacement;
    void **backup_slot;
    bool ready() const;
};
bool resolve_launcher_install_plan(LauncherInstallPlan &plan);

static uint32_t safety_commit_now(const Snapshot &snapshot, uint32_t revision) {
    if (launcher::ordinary_snapshot_errors(snapshot, revision, 0) != 0) return 1;
    const auto context = diagnostic_context_now();
    const auto result = launcher::commit_safety_startup_once(context, snapshot, revision);
    if (!result.committed) return 2;
    const launcher::PermitEvidence evidence{context.identity, true,
        launcher::safety_evidence(result), true};
    if (!launcher::install_permitted(evidence)) return 3;
    LauncherInstallPlan plan{};
    if (!resolve_launcher_install_plan(plan)) return 4;
    plan.hook_func(plan.target, plan.replacement, plan.backup_slot);
    return 0;
}

extern "C" ModuleLoadedCallback native_init(const NativeApiEntries *entries) {
    Snapshot snapshot{};
    if (safety_commit_now(snapshot, 0) != 0) return nullptr;
    return nullptr;
}
"""

# 每一项缺失都必须单独拒绝：下面的变体各自只破坏一个环节。
NATIVE_ENTRY_WIRED_NO_GATE = NATIVE_ENTRY_WIRED.replace(
    "    if (launcher::ordinary_snapshot_errors(snapshot, revision, 0) != 0) return 1;\n", "")
NATIVE_ENTRY_WIRED_NO_COMMIT = NATIVE_ENTRY_WIRED.replace(
    "    if (!result.committed) return 2;\n", "")
NATIVE_ENTRY_WIRED_NO_DOMINANCE = NATIVE_ENTRY_WIRED.replace(
    "launcher::safety_evidence(result), true};", "true, true};")
NATIVE_ENTRY_WIRED_NO_INSTALL = NATIVE_ENTRY_WIRED.replace(
    "    plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n", "")

# 计划校验必须独立成立，不能用许可守卫或校验其他对象代替。
NATIVE_ENTRY_WIRED_NO_PLAN_GATE = NATIVE_ENTRY_WIRED.replace(
    "    if (!resolve_launcher_install_plan(plan)) return 4;\n", "")
NATIVE_ENTRY_WIRED_PLAN_GATE_AFTER_INSTALL = NATIVE_ENTRY_WIRED_NO_PLAN_GATE.replace(
    "    plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n",
    "    plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n"
    "    if (!resolve_launcher_install_plan(plan)) return 4;\n")
NATIVE_ENTRY_WIRED_PLAN_GATE_REVERSED = NATIVE_ENTRY_WIRED.replace(
    "if (!resolve_launcher_install_plan(plan))", "if (resolve_launcher_install_plan(plan))")
NATIVE_ENTRY_WIRED_PLAN_GATE_CONSTANT = NATIVE_ENTRY_WIRED.replace(
    "if (!resolve_launcher_install_plan(plan))", "if (!resolve_launcher_install_plan(plan) || true)")
NATIVE_ENTRY_WIRED_PLAN_GATE_BEFORE_PERMIT = NATIVE_ENTRY_WIRED.replace(
    "    if (!launcher::install_permitted(evidence)) return 3;\n"
    "    LauncherInstallPlan plan{};\n"
    "    if (!resolve_launcher_install_plan(plan)) return 4;\n",
    "    LauncherInstallPlan plan{};\n"
    "    if (!resolve_launcher_install_plan(plan)) return 4;\n"
    "    if (!launcher::install_permitted(evidence)) return 3;\n")
NATIVE_ENTRY_WIRED_PLAN_GATE_OTHER_OBJECT = NATIVE_ENTRY_WIRED.replace(
    "    if (!resolve_launcher_install_plan(plan)) return 4;",
    "    LauncherInstallPlan other{};\n"
    "    if (!resolve_launcher_install_plan(other)) return 4;")
NATIVE_ENTRY_WIRED_PLAN_GATE_RUNTIME = NATIVE_ENTRY_WIRED.replace(
    "    if (!resolve_launcher_install_plan(plan)) return 4;",
    "    if (revision > 0) { if (!resolve_launcher_install_plan(plan)) return 4; }")
NATIVE_ENTRY_WIRED_PLAN_READY_GATE = NATIVE_ENTRY_WIRED.replace(
    "if (!resolve_launcher_install_plan(plan))", "if (!plan.ready())")

# 顺序诱饵：形态上都有提交与安装，但语义上违反“门禁在前、安装在后”。
# 快照门禁放在提交之后。
NATIVE_ENTRY_WIRED_GATE_AFTER_COMMIT = NATIVE_ENTRY_WIRED.replace(
    "    if (launcher::ordinary_snapshot_errors(snapshot, revision, 0) != 0) return 1;\n"
    "    const auto context = diagnostic_context_now();\n"
    "    const auto result = launcher::commit_safety_startup_once(context, snapshot, revision);\n"
    "    if (!result.committed) return 2;\n",
    "    const auto context = diagnostic_context_now();\n"
    "    const auto result = launcher::commit_safety_startup_once(context, snapshot, revision);\n"
    "    if (!result.committed) return 2;\n"
    "    if (launcher::ordinary_snapshot_errors(snapshot, revision, 0) != 0) return 1;\n")

# 安装许可证据在提交之前就被计算。
NATIVE_ENTRY_WIRED_INSTALL_BEFORE_COMMIT = NATIVE_ENTRY_WIRED.replace(
    "    const launcher::PermitEvidence evidence{context.identity, true,\n"
    "        launcher::safety_evidence(result), true};\n",
    "    const launcher::PermitEvidence evidence{context.identity, true, true, true};\n")

# 提交失败不早退（只记录结果）。
NATIVE_ENTRY_WIRED_NO_EARLY_RETURN = NATIVE_ENTRY_WIRED.replace(
    "    if (!result.committed) return 2;\n", "    (void)result.committed;\n")

# 绕过接线：另开一处 hook 调用点，不受提交结果支配。
NATIVE_ENTRY_WIRED_EXTRA_INSTALL = NATIVE_ENTRY_WIRED.replace(
    "extern \"C\" ModuleLoadedCallback native_init",
    "static void bypass(const NativeApiEntries *entries) {\n"
    "    entries->hook_func(nullptr, nullptr);\n"
    "}\n\n"
    "extern \"C\" ModuleLoadedCallback native_init")

# 下面是「形态像接线、语义上却绕过控制流」的诱饵。旧验证器只按出现位置配对，
# 这些写法全都能骗过它；每一条都必须被单独拒绝。
# A. 安装调用被挪到「安装许可早退」之前，未受许可支配。
NATIVE_ENTRY_WIRED_INSTALL_BEFORE_PERMIT = NATIVE_ENTRY_WIRED.replace(
    "    if (!launcher::install_permitted(evidence)) return 3;\n"
    "    LauncherInstallPlan plan{};\n"
    "    if (!resolve_launcher_install_plan(plan)) return 4;\n"
    "    plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n",
    "    LauncherInstallPlan plan{};\n"
    "    if (!resolve_launcher_install_plan(plan)) return 4;\n"
    "    plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n"
    "    if (!launcher::install_permitted(evidence)) return 3;\n")

# B. 快照门禁调用返回值被丢弃，等于没有门禁。
NATIVE_ENTRY_WIRED_GATE_RESULT_DROPPED = NATIVE_ENTRY_WIRED.replace(
    "    if (launcher::ordinary_snapshot_errors(snapshot, revision, 0) != 0) return 1;\n",
    "    launcher::ordinary_snapshot_errors(snapshot, revision, 0);\n")

# C. 安装许可证据自填 true，只用一处「悬空引用」保留 safety_evidence(result) 字样。
NATIVE_ENTRY_WIRED_SELF_ASSERTED_EVIDENCE = NATIVE_ENTRY_WIRED.replace(
    "        launcher::safety_evidence(result), true};",
    "        true, true};\n    (void)launcher::safety_evidence(result);")

# D. 安装调用落在恒假分支里。
NATIVE_ENTRY_WIRED_HOOK_IN_DEAD_BRANCH = NATIVE_ENTRY_WIRED.replace(
    "    plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n",
    "    if (0) plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n")

# E. 安装调用落在无条件 return 之后，永远执行不到。
NATIVE_ENTRY_WIRED_HOOK_AFTER_RETURN = NATIVE_ENTRY_WIRED.replace(
    "    if (!resolve_launcher_install_plan(plan)) return 4;\n",
    "    return 9;\n    if (!resolve_launcher_install_plan(plan)) return 4;\n")

# F. 先取 hook_func 别名再调用，绕过「全文仅一处直接调用」的唯一性判据。
NATIVE_ENTRY_WIRED_ALIASED_INSTALL = NATIVE_ENTRY_WIRED.replace(
    "extern \"C\" ModuleLoadedCallback native_init",
    "static void bypass(const NativeApiEntries *entries) {\n"
    "    auto fn = entries->hook_func;\n"
    "    fn(nullptr, nullptr);\n"
    "}\n\n"
    "extern \"C\" ModuleLoadedCallback native_init")

# G. 安装许可判定返回值被丢弃，判定不进控制流。
NATIVE_ENTRY_WIRED_PERMIT_RESULT_DROPPED = NATIVE_ENTRY_WIRED.replace(
    "    if (!launcher::install_permitted(evidence)) return 3;\n",
    "    (void)launcher::install_permitted(evidence);\n")

# I. 安装许可早退条件被短路成恒真：不安全时也会走到安装。
NATIVE_ENTRY_WIRED_PERMIT_ALWAYS_RETURNS = NATIVE_ENTRY_WIRED.replace(
    "    if (!launcher::install_permitted(evidence)) return 3;\n",
    "    if (!launcher::install_permitted(evidence) || true) return 3;\n")

# J. 提交结果守卫被短路成恒假：提交失败也不再早退。
NATIVE_ENTRY_WIRED_COMMIT_GUARD_CONSTANT = NATIVE_ENTRY_WIRED.replace(
    "    if (!result.committed) return 2;\n",
    "    if (!result.committed && false) return 2;\n")

# K. 快照门禁误用底层形态校验（不拒绝恢复用途），不算用途门禁。
NATIVE_ENTRY_WIRED_WRONG_GATE = NATIVE_ENTRY_WIRED.replace(
    "launcher::ordinary_snapshot_errors", "launcher::snapshot_errors")

# L. 安装调用落在 `while (0)` 恒假循环里。
NATIVE_ENTRY_WIRED_HOOK_IN_FALSE_LOOP = NATIVE_ENTRY_WIRED.replace(
    "    plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n",
    "    while (0) plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n")

# M. 安装调用落在可达 `goto` 与它的目标标签之间，永远执行不到。
#    注意标签必须放在安装调用**之后**：若把标签放在安装调用之前，那段代码就是
#    `goto` 的正常落点，属于真实可达接线，不能再当反例。
NATIVE_ENTRY_WIRED_HOOK_AFTER_GOTO = NATIVE_ENTRY_WIRED.replace(
    "    if (!resolve_launcher_install_plan(plan)) return 4;\n",
    "    goto end;\n    if (!resolve_launcher_install_plan(plan)) return 4;\n").replace(
    "    plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n    return 0;\n",
    "    plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n    end:\n    return 0;\n")

# N. 安全证据字段位置错：`safety_verified` 必须恰好是 PermitEvidence 的第 3 个字段。
NATIVE_ENTRY_WIRED_EVIDENCE_FIELD_SWAP = NATIVE_ENTRY_WIRED.replace(
    "    const launcher::PermitEvidence evidence{context.identity, true,\n"
    "        launcher::safety_evidence(result), true};",
    "    const launcher::PermitEvidence evidence{context.identity,\n"
    "        launcher::safety_evidence(result), true, true};")

# O. 证据改用指定初始化赋值（.safety_verified = safety_evidence(result)）的正向形态：
#    验证器不能只认花括号按位初始化，否则真实接线会被死条件卡住。
NATIVE_ENTRY_WIRED_DESIGNATED_EVIDENCE = NATIVE_ENTRY_WIRED.replace(
    "    const launcher::PermitEvidence evidence{context.identity, true,\n"
    "        launcher::safety_evidence(result), true};",
    "    launcher::PermitEvidence evidence{context.identity, true, false, true};\n"
    "    evidence.safety_verified = launcher::safety_evidence(result);")

# P. 指定初始化但安全证据自填 true，必须仍然拒绝。
NATIVE_ENTRY_WIRED_DESIGNATED_SELF_ASSERTED = NATIVE_ENTRY_WIRED.replace(
    "    const launcher::PermitEvidence evidence{context.identity, true,\n"
    "        launcher::safety_evidence(result), true};",
    "    launcher::PermitEvidence evidence{context.identity, true, true, true};\n"
    "    evidence.safety_verified = true;")

# Q. 快照门禁极性反转（==0 就早退）：成功反而阻断，不安全却放行。
NATIVE_ENTRY_WIRED_GATE_POLARITY = NATIVE_ENTRY_WIRED.replace(
    "    if (launcher::ordinary_snapshot_errors(snapshot, revision, 0) != 0) return 1;\n",
    "    if (launcher::ordinary_snapshot_errors(snapshot, revision, 0) == 0) return 1;\n")

# R. 快照门禁被 `0 &&` 短路成恒假，永不触发。
NATIVE_ENTRY_WIRED_GATE_CONSTANT = NATIVE_ENTRY_WIRED.replace(
    "    if (launcher::ordinary_snapshot_errors(snapshot, revision, 0) != 0) return 1;\n",
    "    if (0 && launcher::ordinary_snapshot_errors(snapshot, revision, 0) != 0) return 1;\n")

# S. 快照门禁校验的是另一个快照对象，与即将提交的不是同一个。
NATIVE_ENTRY_WIRED_GATE_OTHER_SNAPSHOT = NATIVE_ENTRY_WIRED.replace(
    "    const auto context = diagnostic_context_now();",
    "    Snapshot other{};\n    const auto context = diagnostic_context_now();").replace(
    "ordinary_snapshot_errors(snapshot, revision, 0)",
    "ordinary_snapshot_errors(other, revision, 0)")

# T. 提交守卫极性反转：提交成功反而早退。
NATIVE_ENTRY_WIRED_COMMIT_GUARD_POLARITY = NATIVE_ENTRY_WIRED.replace(
    "    if (!result.committed) return 2;\n", "    if (result.committed) return 2;\n")

# U. 提交守卫被 `true ||` 短路成恒真，永远早退。
NATIVE_ENTRY_WIRED_COMMIT_GUARD_TRUE = NATIVE_ENTRY_WIRED.replace(
    "    if (!result.committed) return 2;\n", "    if (true || !result.committed) return 2;\n")

# V. 安装被写进「安装许可失败」的真分支：位置在许可之后，却属于错误分支。
NATIVE_ENTRY_WIRED_INSTALL_IN_DENIED_BRANCH = NATIVE_ENTRY_WIRED.replace(
    "    if (!launcher::install_permitted(evidence)) return 3;\n"
    "    LauncherInstallPlan plan{};\n"
    "    if (!resolve_launcher_install_plan(plan)) return 4;\n"
    "    plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n",
    "    LauncherInstallPlan plan{};\n"
    "    if (!launcher::install_permitted(evidence)) {\n"
    "        plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n"
    "        return 3;\n"
    "    }\n")

# W. 提交失败的真分支为空、改从 else 继续：`if (!committed) {} else return;`。
NATIVE_ENTRY_WIRED_COMMIT_FAILURE_EMPTY = NATIVE_ENTRY_WIRED.replace(
    "    if (!result.committed) return 2;\n",
    "    if (!result.committed) {}\n")

# X. 提交失败时既早退又走 else 继续（真分支丢空）。
NATIVE_ENTRY_WIRED_COMMIT_FAILURE_ELSE = NATIVE_ENTRY_WIRED.replace(
    "    if (!result.committed) return 2;\n",
    "    if (!result.committed) {} else return 2;\n")

# Y. 唯一一次提交发生在 `if (0)` 恒假分支里：提交本身不可达。
NATIVE_ENTRY_WIRED_COMMIT_IN_DEAD_BRANCH = NATIVE_ENTRY_WIRED.replace(
    "    const auto context = diagnostic_context_now();\n"
    "    const auto result = launcher::commit_safety_startup_once(context, snapshot, revision);\n"
    "    if (!result.committed) return 2;\n",
    "    const auto context = diagnostic_context_now();\n"
    "    if (0) {\n"
    "        const auto result = launcher::commit_safety_startup_once(context, snapshot, revision);\n"
    "        if (!result.committed) return 2;\n"
    "    }\n")

# Z. 完整链路整条写在一个不可达函数里，导出入口什么都不做。
NATIVE_ENTRY_WIRED_ONLY_UNREACHABLE = """\
#include "native_api.h"
#include "install_permit.h"
#include "safety_startup.h"

static uint32_t dead_commit(const Snapshot &snapshot, uint32_t revision) {
    if (launcher::ordinary_snapshot_errors(snapshot, revision, 0) != 0) return 1;
    const auto context = diagnostic_context_now();
    const auto result = launcher::commit_safety_startup_once(context, snapshot, revision);
    if (!result.committed) return 2;
    const launcher::PermitEvidence evidence{context.identity, true,
        launcher::safety_evidence(result), true};
    if (!launcher::install_permitted(evidence)) return 3;
    plan.hook_func(plan.target, plan.replacement, plan.backup_slot);
    return 0;
}

extern "C" ModuleLoadedCallback native_init(const NativeApiEntries *entries) {
    return nullptr;
}
"""

# AA. 安装调用只在**未被调用的** lambda 里，永远不会执行。
NATIVE_ENTRY_WIRED_HOOK_IN_UNUSED_LAMBDA = NATIVE_ENTRY_WIRED.replace(
    "    plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n",
    "    auto pending = [&]() { plan.hook_func(plan.target, plan.replacement, plan.backup_slot); };\n"
    "    (void)pending;\n")

# AB. 提交结果用**显式类型**声明（正例，不该误判为未接线）。
NATIVE_ENTRY_WIRED_EXPLICIT_RESULT_TYPE = NATIVE_ENTRY_WIRED.replace(
    "    const auto result = launcher::commit_safety_startup_once(context, snapshot, revision);",
    "    const launcher::SafetyStartupResult result =\n"
    "        launcher::commit_safety_startup_once(context, snapshot, revision);")

# AC. 安装调用写在**立即调用**的 lambda 里（正例，应当算接线）。
NATIVE_ENTRY_WIRED_IMMEDIATE_LAMBDA = NATIVE_ENTRY_WIRED.replace(
    "    plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n",
    "    [&]() { plan.hook_func(plan.target, plan.replacement, plan.backup_slot); }();\n")

# AD. 快照门禁唯一一次出现在 `if (0)` 恒假分支里。
NATIVE_ENTRY_WIRED_GATE_IN_DEAD_BRANCH = NATIVE_ENTRY_WIRED.replace(
    "    if (launcher::ordinary_snapshot_errors(snapshot, revision, 0) != 0) return 1;\n",
    "    if (0) { if (launcher::ordinary_snapshot_errors(snapshot, revision, 0) != 0) return 1; }\n")

# AE. 安装许可早退唯一一次出现在 `if (0)` 恒假分支里。
NATIVE_ENTRY_WIRED_PERMIT_IN_DEAD_BRANCH = NATIVE_ENTRY_WIRED.replace(
    "    if (!launcher::install_permitted(evidence)) return 3;\n",
    "    if (0) { if (!launcher::install_permitted(evidence)) return 3; }\n")

# AF. 提交守卫改用 safety_ready() 变体（正例，应当算接线）。
NATIVE_ENTRY_WIRED_SAFETY_READY_GUARD = NATIVE_ENTRY_WIRED.replace(
    "    if (!result.committed) return 2;\n", "    if (!result.safety_ready()) return 2;\n")

# AG. 快照门禁用等价 truthy 写法 `if (errors(...))`（正例，不该误判）。
NATIVE_ENTRY_WIRED_GATE_TRUTHY = NATIVE_ENTRY_WIRED.replace(
    "    if (launcher::ordinary_snapshot_errors(snapshot, revision, 0) != 0) return 1;\n",
    "    if (launcher::ordinary_snapshot_errors(snapshot, revision, 0)) return 1;\n")

# AH. 快照门禁取反 `!errors(...)`（极性错误，必须拒绝）。
NATIVE_ENTRY_WIRED_GATE_NEGATED = NATIVE_ENTRY_WIRED.replace(
    "    if (launcher::ordinary_snapshot_errors(snapshot, revision, 0) != 0) return 1;\n",
    "    if (!launcher::ordinary_snapshot_errors(snapshot, revision, 0)) return 1;\n")

# AI. 整条链路被 `if constexpr (false)` 包住：编译期就丢掉，必须拒绝。
NATIVE_ENTRY_WIRED_IN_IF_CONSTEXPR_FALSE = NATIVE_ENTRY_WIRED.replace(
    "    if (launcher::ordinary_snapshot_errors(snapshot, revision, 0) != 0) return 1;\n"
    "    const auto context = diagnostic_context_now();\n"
    "    const auto result = launcher::commit_safety_startup_once(context, snapshot, revision);\n"
    "    if (!result.committed) return 2;\n",
    "    if constexpr (false) {\n"
    "        if (launcher::ordinary_snapshot_errors(snapshot, revision, 0) != 0) return 1;\n"
    "        const auto context = diagnostic_context_now();\n"
    "        const auto result = launcher::commit_safety_startup_once(context, snapshot, revision);\n"
    "        if (!result.committed) return 2;\n"
    "    }\n")

# AJ. 安装调用被 `if constexpr (false)` 包住。
NATIVE_ENTRY_WIRED_INSTALL_IN_IF_CONSTEXPR_FALSE = NATIVE_ENTRY_WIRED.replace(
    "    plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n",
    "    if constexpr (false) { plan.hook_func(plan.target, plan.replacement, plan.backup_slot); }\n")

# AK. 安装调用被 `for (; false;)` 恒假循环包住。
NATIVE_ENTRY_WIRED_INSTALL_IN_FALSE_FOR = NATIVE_ENTRY_WIRED.replace(
    "    plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n",
    "    for (; false;) { plan.hook_func(plan.target, plan.replacement, plan.backup_slot); }\n")

# AL. 安装调用被 `if (true) {...} else {...}` 的 else 分支包住。
NATIVE_ENTRY_WIRED_INSTALL_IN_DEAD_ELSE = NATIVE_ENTRY_WIRED.replace(
    "    plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n",
    "    if (true) { return 0; } else { plan.hook_func(plan.target, plan.replacement, plan.backup_slot); }\n")

# AM. 安装调用被常量 switch 的不匹配分支包住（switch(1) / case 2）。
NATIVE_ENTRY_WIRED_INSTALL_IN_SWITCH_MISS = NATIVE_ENTRY_WIRED.replace(
    "    plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n",
    "    switch (1) { case 2: plan.hook_func(plan.target, plan.replacement, plan.backup_slot); }\n")

# AN. 恒假分支条件来自宏定义（`#define NEVER 0` 后写 `if (NEVER)`）。
NATIVE_ENTRY_WIRED_MACRO_FALSE_GATE = "#define NEVER 0\n" + NATIVE_ENTRY_WIRED.replace(
    "    if (launcher::ordinary_snapshot_errors(snapshot, revision, 0) != 0) return 1;\n",
    "    if (NEVER) { if (launcher::ordinary_snapshot_errors(snapshot, revision, 0) != 0) return 1; }\n")

# AO. 安装调用落在 `while (true) { }` 之后：循环没有 break，后面的代码永远执行不到。
NATIVE_ENTRY_WIRED_INSTALL_AFTER_INFINITE_LOOP = NATIVE_ENTRY_WIRED.replace(
    "    plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n",
    "    while (true) { }\n    plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n")

# AP. 导出入口只在 `if (0)` 里调用整条链路：入口本身不再可达，
#     不能靠「函数体里写过调用」就算接线。
NATIVE_ENTRY_WIRED_CALL_SITE_IN_DEAD_BRANCH = NATIVE_ENTRY_WIRED.replace(
    "    if (safety_commit_now(snapshot, 0) != 0) return nullptr;\n",
    "    if (0) { safety_commit_now(snapshot, 0); }\n")

# AQ. 导出入口在 `if constexpr (false)` 里调用整条链路。
NATIVE_ENTRY_WIRED_CALL_SITE_IN_IF_CONSTEXPR_FALSE = NATIVE_ENTRY_WIRED.replace(
    "    if (safety_commit_now(snapshot, 0) != 0) return nullptr;\n",
    "    if constexpr (false) { safety_commit_now(snapshot, 0); }\n")

# AR. 导出入口经「只被恒假条件调用的中间函数」间接调用整条链路。
NATIVE_ENTRY_WIRED_VIA_DEAD_HELPER = NATIVE_ENTRY_WIRED.replace(
    "extern \"C\" ModuleLoadedCallback native_init",
    "static uint32_t dead_helper(const Snapshot &snapshot, uint32_t revision) {\n"
    "    return safety_commit_now(snapshot, revision);\n"
    "}\n\n"
    "extern \"C\" ModuleLoadedCallback native_init").replace(
    "    if (safety_commit_now(snapshot, 0) != 0) return nullptr;\n",
    "    if (1 == 2) { dead_helper(snapshot, 0); }\n")

# AS. 安装段整体包进 `do { ... } while (false)`（宏展开常见形态，正例，不得误判）。
NATIVE_ENTRY_WIRED_INSTALL_IN_DO_WHILE = NATIVE_ENTRY_WIRED.replace(
    "    if (!launcher::install_permitted(evidence)) return 3;\n"
    "    LauncherInstallPlan plan{};\n"
    "    if (!resolve_launcher_install_plan(plan)) return 4;\n"
    "    plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n",
    "    do {\n"
    "        if (!launcher::install_permitted(evidence)) return 3;\n"
    "        LauncherInstallPlan plan{};\n"
    "        if (!resolve_launcher_install_plan(plan)) return 4;\n"
    "        plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n"
    "    } while (false);\n")

# AT. 安装写进「许可失败」的**无花括号**单语句真分支，仍属错误分支。
NATIVE_ENTRY_WIRED_INSTALL_IN_BRACELESS_DENIED_BRANCH = NATIVE_ENTRY_WIRED.replace(
    "    if (!launcher::install_permitted(evidence)) return 3;\n"
    "    LauncherInstallPlan plan{};\n"
    "    if (!resolve_launcher_install_plan(plan)) return 4;\n"
    "    plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n",
    "    LauncherInstallPlan plan{};\n"
    "    if (!launcher::install_permitted(evidence)) plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n")

# AU. `goto` 落在安装调用之前：这是真实可达接线（正例，不得误判）。
NATIVE_ENTRY_WIRED_REACHABLE_GOTO = NATIVE_ENTRY_WIRED.replace(
    "    plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n",
    "    goto install;\n    install:\n    plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n")

# AV. 常量 switch 的匹配分支里安装（正例）。
NATIVE_ENTRY_WIRED_SWITCH_MATCH = NATIVE_ENTRY_WIRED.replace(
    "    plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n",
    "    switch (1) { case 1: plan.hook_func(plan.target, plan.replacement, plan.backup_slot); }\n")

# AW. 常量 switch 走到 default 里安装（正例）。
NATIVE_ENTRY_WIRED_SWITCH_DEFAULT = NATIVE_ENTRY_WIRED.replace(
    "    plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n",
    "    switch (2) { case 1: break; default: plan.hook_func(plan.target, plan.replacement, plan.backup_slot); }\n")

# AX. 嵌套块里的无条件 return：其后代码不可达，必须拒绝。
NATIVE_ENTRY_WIRED_INSTALL_AFTER_NESTED_RETURN = NATIVE_ENTRY_WIRED.replace(
    "    plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n",
    "    { return 9; }\n    plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n")

# AY. `if (1) { return; }` 之后的安装不可达；`if (1) { ... }` 之后则可达。
NATIVE_ENTRY_WIRED_INSTALL_AFTER_CONST_TRUE_RETURN = NATIVE_ENTRY_WIRED.replace(
    "    plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n",
    "    if (1) { return 1; }\n    plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n")

# AZ. `if (0) {} else { return; }` 之后不可达。
NATIVE_ENTRY_WIRED_INSTALL_AFTER_CONST_FALSE_ELSE_RETURN = NATIVE_ENTRY_WIRED.replace(
    "    plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n",
    "    if (0) { (void)revision; } else { return 1; }\n    plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n")

# BA. 门禁早退写在恒假分支里：真分支里出现过 return，但那个 return 执行不到。
NATIVE_ENTRY_WIRED_GATE_ABORT_IN_DEAD_BRANCH = NATIVE_ENTRY_WIRED.replace(
    "    if (launcher::ordinary_snapshot_errors(snapshot, revision, 0) != 0) return 1;\n",
    "    if (launcher::ordinary_snapshot_errors(snapshot, revision, 0) != 0) { if (0) return 1; }\n")

# BB. 提交失败早退写在恒假分支里。
NATIVE_ENTRY_WIRED_COMMIT_ABORT_IN_DEAD_BRANCH = NATIVE_ENTRY_WIRED.replace(
    "    if (!result.committed) return 2;\n",
    "    if (!result.committed) { if (0) { return 2; } }\n")

# BC. 许可早退写在恒假分支里。
NATIVE_ENTRY_WIRED_PERMIT_ABORT_IN_DEAD_BRANCH = NATIVE_ENTRY_WIRED.replace(
    "    if (!launcher::install_permitted(evidence)) return 3;\n",
    "    if (!launcher::install_permitted(evidence)) { if (0) return 3; }\n")

# BD. 早退被嵌套的运行期条件削弱：不安全时可能继续走到安装。
NATIVE_ENTRY_WIRED_ABORT_WEAKENED_BY_RUNTIME_CONDITION = NATIVE_ENTRY_WIRED.replace(
    "    if (!result.committed) return 2;\n",
    "    if (!result.committed) { if (revision > 0) return 2; }\n")

# BE. 裸 `break` 当作早退：它不在任何循环里，不会离开安装路径。
NATIVE_ENTRY_WIRED_ABORT_BY_BARE_BREAK = NATIVE_ENTRY_WIRED.replace(
    "    if (!launcher::install_permitted(evidence)) return 3;\n",
    "    if (!launcher::install_permitted(evidence)) break;\n")

# BF. `goto` 回到安装之前的标签：没有跳过安装。
NATIVE_ENTRY_WIRED_ABORT_BY_GOTO_BACKWARD = NATIVE_ENTRY_WIRED.replace(
    "    Snapshot snapshot{};\n", "start_again:\n    Snapshot snapshot{};\n").replace(
    "    if (!launcher::install_permitted(evidence)) return 3;\n",
    "    if (!launcher::install_permitted(evidence)) goto start_again;\n")

# BG. 早退真分支里用嵌套常量 `if (1) { return; }` —— 必然终止（正例）。
NATIVE_ENTRY_WIRED_ABORT_IN_CONST_TRUE_BLOCK = NATIVE_ENTRY_WIRED.replace(
    "    if (!result.committed) return 2;\n",
    "    if (!result.committed) { if (1) { return 2; } }\n")

# BH. 早退真分支里 `return` 之后还有语句（死代码）—— 块仍必然终止（正例）。
NATIVE_ENTRY_WIRED_ABORT_WITH_TRAILING_STATEMENT = NATIVE_ENTRY_WIRED.replace(
    "    if (!result.committed) return 2;\n",
    "    if (!result.committed) { return 2; (void)revision; }\n")

# BI. `goto` 跳到安装调用之后的标签 —— 确实跳过安装（正例）。
NATIVE_ENTRY_WIRED_ABORT_BY_GOTO_FORWARD = NATIVE_ENTRY_WIRED.replace(
    "    if (!launcher::install_permitted(evidence)) return 3;\n"
    "    LauncherInstallPlan plan{};\n"
    "    if (!resolve_launcher_install_plan(plan)) return 4;\n"
    "    plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n",
    "    if (!launcher::install_permitted(evidence)) goto denied;\n"
    "    LauncherInstallPlan plan{};\n"
    "    if (!resolve_launcher_install_plan(plan)) return 4;\n"
    "    plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n"
    "    denied:\n")

# BJ. `do { ... } while (false)` 里用 `break` 跳过安装 —— 真实早退（正例）。
NATIVE_ENTRY_WIRED_ABORT_BY_BREAK_IN_DO_WHILE = NATIVE_ENTRY_WIRED.replace(
    "    if (!launcher::install_permitted(evidence)) return 3;\n"
    "    LauncherInstallPlan plan{};\n"
    "    if (!resolve_launcher_install_plan(plan)) return 4;\n"
    "    plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n",
    "    do {\n"
    "        if (!launcher::install_permitted(evidence)) break;\n"
    "        LauncherInstallPlan plan{};\n"
    "        if (!resolve_launcher_install_plan(plan)) break;\n"
    "        plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n"
    "    } while (false);\n")

# BK. 许可判定被运行期条件包住：条件为假时判定根本不执行，安装照走。
NATIVE_ENTRY_WIRED_PERMIT_UNDER_RUNTIME_CONDITION = NATIVE_ENTRY_WIRED.replace(
    "    if (!launcher::install_permitted(evidence)) return 3;\n",
    "    if (revision > 0) { if (!launcher::install_permitted(evidence)) return 3; }\n")

# BL. 快照门禁被运行期条件包住：判定可能整条被跳过。
NATIVE_ENTRY_WIRED_GATE_UNDER_RUNTIME_CONDITION = NATIVE_ENTRY_WIRED.replace(
    "    if (launcher::ordinary_snapshot_errors(snapshot, revision, 0) != 0) return 1;\n",
    "    if (revision > 0) { if (launcher::ordinary_snapshot_errors(snapshot, revision, 0) != 0) return 1; }\n")

# BM. 提交结果守卫被运行期条件包住：提交失败时可能不拦。
NATIVE_ENTRY_WIRED_COMMIT_GUARD_UNDER_RUNTIME_CONDITION = NATIVE_ENTRY_WIRED.replace(
    "    if (!result.committed) return 2;\n",
    "    if (revision > 0) { if (!result.committed) return 2; }\n")

# BN. 提交调用本身被运行期条件包住：条件为假时根本不提交。
NATIVE_ENTRY_WIRED_COMMIT_UNDER_RUNTIME_CONDITION = NATIVE_ENTRY_WIRED.replace(
    "    const auto context = diagnostic_context_now();\n"
    "    const auto result = launcher::commit_safety_startup_once(context, snapshot, revision);\n",
    "    const auto context = diagnostic_context_now();\n"
    "    launcher::SafetyStartupResult result{};\n"
    "    if (revision > 0) { result = launcher::commit_safety_startup_once(context, snapshot, revision); }\n")

# BO. 安装点被运行期 `if` 包住：存在一条不安装也能走到底的路径。
NATIVE_ENTRY_WIRED_INSTALL_UNDER_RUNTIME_CONDITION = NATIVE_ENTRY_WIRED.replace(
    "    plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n",
    "    if (revision > 0) { plan.hook_func(plan.target, plan.replacement, plan.backup_slot); }\n")

# BP. 安装点被有界循环包住：循环可能一次都不执行。
NATIVE_ENTRY_WIRED_INSTALL_IN_BOUNDED_LOOP = NATIVE_ENTRY_WIRED.replace(
    "    plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n",
    "    while (revision > 0) { plan.hook_func(plan.target, plan.replacement, plan.backup_slot); break; }\n")

# BQ. 提交结果变量在守卫之前被改写成 committed=true：守卫读不到真实结果。
NATIVE_ENTRY_WIRED_COMMIT_RESULT_OVERWRITTEN = NATIVE_ENTRY_WIRED.replace(
    "    const auto result = launcher::commit_safety_startup_once(context, snapshot, revision);\n",
    "    auto result = launcher::commit_safety_startup_once(context, snapshot, revision);\n"
    "    result.committed = true;\n")

# BR. 提交结果变量在守卫之前被整体重置。
NATIVE_ENTRY_WIRED_COMMIT_RESULT_RESET = NATIVE_ENTRY_WIRED.replace(
    "    const auto result = launcher::commit_safety_startup_once(context, snapshot, revision);\n"
    "    if (!result.committed) return 2;\n",
    "    auto result = launcher::commit_safety_startup_once(context, snapshot, revision);\n"
    "    result.reset();\n"
    "    if (!result.committed) return 2;\n")

# BS. 提交结果只被只读引用（正例）：不得把只读调用当成写入而误杀。
NATIVE_ENTRY_WIRED_COMMIT_RESULT_READ_ONLY = NATIVE_ENTRY_WIRED.replace(
    "    if (!result.committed) return 2;\n",
    "    if (!result.safety_ready()) return 2;\n")

# BT. 快照门禁被 `&&` 额外条件削弱：只在某些情况下才早退。
NATIVE_ENTRY_WIRED_GATE_WEAKENED_BY_AND = NATIVE_ENTRY_WIRED.replace(
    "    if (launcher::ordinary_snapshot_errors(snapshot, revision, 0) != 0) return 1;\n",
    "    if (launcher::ordinary_snapshot_errors(snapshot, revision, 0) != 0 && revision > 0) return 1;\n")

# BU. 快照门禁双重取反：错误时反而放行。
NATIVE_ENTRY_WIRED_GATE_DOUBLE_NEGATED = NATIVE_ENTRY_WIRED.replace(
    "    if (launcher::ordinary_snapshot_errors(snapshot, revision, 0) != 0) return 1;\n",
    "    if (!(launcher::ordinary_snapshot_errors(snapshot, revision, 0) != 0)) return 1;\n")

# BV. 提交守卫被 `&&` 额外条件削弱。
NATIVE_ENTRY_WIRED_COMMIT_GUARD_WEAKENED_BY_AND = NATIVE_ENTRY_WIRED.replace(
    "    if (!result.committed) return 2;\n",
    "    if (!result.committed && revision > 0) return 2;\n")

# BW. 提交守卫双重取反：提交失败反而继续。
NATIVE_ENTRY_WIRED_COMMIT_GUARD_DOUBLE_NEGATED = NATIVE_ENTRY_WIRED.replace(
    "    if (!result.committed) return 2;\n",
    "    if (!(!result.committed)) return 2;\n")

# BX. 许可判定被 `&&` 额外条件削弱。
NATIVE_ENTRY_WIRED_PERMIT_WEAKENED_BY_AND = NATIVE_ENTRY_WIRED.replace(
    "    if (!launcher::install_permitted(evidence)) return 3;\n",
    "    if (!launcher::install_permitted(evidence) && revision > 0) return 3;\n")

# BY. 许可判定写成 `== false` 等价极性（正例，不该误判）。
NATIVE_ENTRY_WIRED_PERMIT_EQUAL_FALSE = NATIVE_ENTRY_WIRED.replace(
    "    if (!launcher::install_permitted(evidence)) return 3;\n",
    "    if (launcher::install_permitted(evidence) == false) return 3;\n")

# BZ. 提交守卫 `result.committed == false` 等价极性（正例，不该误判）。
NATIVE_ENTRY_WIRED_COMMIT_GUARD_EQUAL_FALSE = NATIVE_ENTRY_WIRED.replace(
    "    if (!result.committed) return 2;\n",
    "    if (result.committed == false) return 2;\n")

# CA. 提交守卫被恒真合取 `&& true` 包裹（正例，语义未变）。
NATIVE_ENTRY_WIRED_COMMIT_GUARD_AND_TRUE = NATIVE_ENTRY_WIRED.replace(
    "    if (!result.committed) return 2;\n",
    "    if (!result.committed && true) return 2;\n")

# CB. 快照门禁被 `||` 加强：仍然「出错必早退」（正例，不该误判）。
NATIVE_ENTRY_WIRED_GATE_OR_STRONGER = NATIVE_ENTRY_WIRED.replace(
    "    if (launcher::ordinary_snapshot_errors(snapshot, revision, 0) != 0) return 1;\n",
    "    if (launcher::ordinary_snapshot_errors(snapshot, revision, 0) != 0 || revision > 99) return 1;\n")

# CC. 门禁条件里夹带对其他对象的用途校验（只看第一个实参会把这段判绿）。
NATIVE_ENTRY_WIRED_GATE_OTHER_OBJECT_IN_OR = NATIVE_ENTRY_WIRED.replace(
    "    if (launcher::ordinary_snapshot_errors(snapshot, revision, 0) != 0) return 1;\n",
    "    if (launcher::ordinary_snapshot_errors(snapshot, revision, 0) != 0\n        || launcher::ordinary_snapshot_errors(other, revision, 0) != 0) return 1;\n")

# CD. 安装调用写成 `(plan.hook_func)(...)` 等价形态（正例，不得误杀）。
NATIVE_ENTRY_WIRED_INSTALL_PARENTHESISED = NATIVE_ENTRY_WIRED.replace(
    "    plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n",
    "    (plan.hook_func)(plan.target, plan.replacement, plan.backup_slot);\n")

# CE. 早退用 `throw` 离开安装路径（正例，不得误杀）。
NATIVE_ENTRY_WIRED_ABORT_BY_THROW = NATIVE_ENTRY_WIRED.replace(
    "    if (!result.committed) return 2;\n",
    "    if (!result.committed) throw 2;\n")

# CF. 提交守卫写成括号包裹的取反 `!(result.committed)`（正例，不得误杀）。
NATIVE_ENTRY_WIRED_COMMIT_GUARD_PARENTHESISED = NATIVE_ENTRY_WIRED.replace(
    "    if (!result.committed) return 2;\n",
    "    if (!(result.committed)) return 2;\n")

# CG. 许可判定夹带额外合取条件：许可失败时可能不早退。
NATIVE_ENTRY_WIRED_PERMIT_OR_EXTRA = NATIVE_ENTRY_WIRED.replace(
    "    if (!launcher::install_permitted(evidence)) return 3;\n",
    "    if (!launcher::install_permitted(evidence) && !trusted_context) return 3;\n")

# CH. 门禁**之前**一条运行期 `goto` 直接落到安装点：整条链路（门禁、提交、
#     提交守卫、安装许可）都被绕过。旧 `jumped_over` 只扫描锚点之后、
#     且要求目标在 target 之后，这类写法会翻绿。
NATIVE_ENTRY_WIRED_GOTO_BEFORE_GATE_TO_INSTALL = NATIVE_ENTRY_WIRED.replace(
    "    if (launcher::ordinary_snapshot_errors(snapshot, revision, 0) != 0) return 1;\n",
    "    if (revision > 0) goto install_now;\n"
    "    if (launcher::ordinary_snapshot_errors(snapshot, revision, 0) != 0) return 1;\n").replace(
    "    plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n",
    "    install_now:\n    plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n")

# CI. 快照门禁**之后**、提交**之前**的运行期 `goto` 落到安装点：跳过提交、
#     提交守卫与安装许可。
NATIVE_ENTRY_WIRED_GOTO_BEFORE_COMMIT_TO_INSTALL = NATIVE_ENTRY_WIRED.replace(
    "    const auto context = diagnostic_context_now();\n",
    "    if (revision > 0) goto install_now;\n    const auto context = diagnostic_context_now();\n").replace(
    "    plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n",
    "    install_now:\n    plan.hook_func(plan.target, plan.replacement, plan.backup_slot);\n")

# CJ. 提交守卫**之前**的运行期 `goto` 跳过守卫：提交失败也能走到安装。
NATIVE_ENTRY_WIRED_GOTO_BEFORE_COMMIT_GUARD = NATIVE_ENTRY_WIRED.replace(
    "    if (!result.committed) return 2;\n",
    "    if (revision > 0) goto after_guard;\n"
    "    if (!result.committed) return 2;\n    after_guard:\n")

# CK. 安装许可判定**之前**的运行期 `goto` 跳过许可：未许可也能走到安装。
NATIVE_ENTRY_WIRED_GOTO_BEFORE_PERMIT = NATIVE_ENTRY_WIRED.replace(
    "    if (!launcher::install_permitted(evidence)) return 3;\n",
    "    if (revision > 0) goto after_permit;\n"
    "    if (!launcher::install_permitted(evidence)) return 3;\n    after_permit:\n")

# 只 include 头文件、没有任何调用点。
NATIVE_ENTRY_INCLUDE_ONLY = """\
#include "native_api.h"
#include "install_permit.h"

void check() { }
"""

# 有调用，但返回值被丢弃、不影响控制流。
NATIVE_ENTRY_CALL_WITHOUT_CONTROL = """\
#include "native_api.h"
#include "install_permit.h"

void check(const PermitEvidence &evidence) {
    install_permitted(evidence);
}
"""

README_HEADING = "## 防砖"

README = """\
## 防砖

### 逃生门

异常状态下按下列步骤恢复：

- `persist.sys.hyperextend.disabled`：先 `setprop` 设回 `0`，再删除已设置的文件标记 `/data/local/tmp/hyperextend.disabled`。
"""

# 覆盖两条投保属性，且恢复步骤落在各自属性名的近邻窗口内。
README_COVERED = README + """\
- `persist.sys.hyperextend.framework_disabled`：先 `setprop` 设回 `0`，再删除状态文件 `/data/system/hyperextend_bootguard`。
"""

# 属性名只出现在别的章节里，不构成逃生门恢复说明。
README_OUT_OF_SECTION = README.replace("### 逃生门", "### 说明") + """\

## 变更记录

- `persist.sys.hyperextend.disabled` 设回，删除已设置的文件标记。
- `persist.sys.hyperextend.framework_disabled` 设回，删除状态文件。
"""

SCOPE_CATALOG_WITH_HOME = """\
val SCOPES: List<HyperScope> = listOf(
    HyperScope(id = "system_server", process = "system"),
    HyperScope(id = "home", process = "com.miui.home"),
)
"""

SCOPE_CATALOG_WITHOUT_HOME = """\
val SCOPES: List<HyperScope> = listOf(
    HyperScope(id = "system_server", process = "system"),
)
"""

# 桌面作用域的真实来源：launcherNative 的身份契约与注入作用域声明。
# 主模块 SCOPES 里**本来就没有**桌面进程名，这不是缺口，真正的判据不在这里。
LAUNCHER_IDENTITY = """\
internal object LauncherHostContract {
    const val PACKAGE = "com.miui.home"
    const val VERSION = 801027726L
}
"""

LAUNCHER_IDENTITY_MISMATCH = """\
internal object LauncherHostContract {
    const val PACKAGE = "com.other.home"
}
"""

LAUNCHER_NATIVE_SCOPE = "com.miui.home\nsystem\n"
LAUNCHER_NATIVE_SCOPE_MISMATCH = "com.other.home\nsystem\n"
LAUNCHER_NATIVE_SCOPE_EXTRA = "com.miui.home\nsystem\ncom.other.home\n"

# 真证据：字面量 + 对两类行为的真实断言。
EVIDENCE_COMPLETE = """\
class SafetyEscapeGateTest {
    @Test fun killSwitchEngagesEveryHookFallback() {
        assertEquals(true, KillSwitch.reportOnce())
        assertEquals(true, KillSwitch.isEngaged())
        assertEquals("persist.sys.hyperextend.disabled", KillSwitch.PROPERTY_NAME)
    }

    @Test fun bootLoopGuardFailsClosed() {
        assertEquals(null, BootLoopGuard.readDisabledProperty())
        assertEquals("persist.sys.hyperextend.framework_disabled", BootLoopGuard.DISABLED_PROPERTY)
    }
}
"""

# 诱饵一：只把属性字面量塞进无行为含义的断言。
EVIDENCE_SINGLE_STRING = """\
class DecoyLiteralTest {
    @Test fun onlyLiteralsExist() {
        assertEquals("persist.sys.hyperextend.disabled", a)
        assertEquals("persist.sys.hyperextend.framework_disabled", b)
    }
}
"""

# 诱饵二：字面量与断言只出现在注释里。
EVIDENCE_COMMENT_ONLY = """\
class DecoyCommentTest {
    // assertEquals(true, KillSwitch.reportOnce())
    // assertEquals(null, BootLoopGuard.readDisabledProperty())
}
"""

# 诱饵三：调用真的出现在断言行里，但期望的是任意无关值，且没有属性字面量。
EVIDENCE_NO_LITERAL = """\
class DecoyNoLiteralTest {
    @Test fun callsWithoutInsuredLiterals() {
        assertEquals(true, KillSwitch.reportOnce())
        assertEquals(null, BootLoopGuard.readDisabledProperty())
    }
}
"""

# 诱饵四：属性字面量与对真实 API 的断言各自成立，但分散在两个不同的 `@Test` 方法里。
# 文件级扫描会误判为「完整证据」，断言级会正确拒绝。
EVIDENCE_SPLIT_ASSERTIONS = """\
class DecoySplitTest {
    @Test fun onlyLiteralAssertions() {
        assertEquals("persist.sys.hyperextend.disabled", a)
        assertEquals("persist.sys.hyperextend.framework_disabled", b)
    }

    @Test fun onlyApiAssertions() {
        assertEquals(true, KillSwitch.reportOnce())
        assertEquals(null, BootLoopGuard.readDisabledProperty())
    }
}
"""

# 诱饵五：属性名只出现在非断言行（赋值），API 断言仍在，但两者不在同一断言证据里。
EVIDENCE_LITERAL_NOT_ASSERTED = """\
class DecoyNonAssertionTest {
    @Test fun literalIsNotAsserted() {
        val name = "persist.sys.hyperextend.disabled"
        val other = "persist.sys.hyperextend.framework_disabled"
        assertEquals(true, KillSwitch.reportOnce())
        assertEquals(null, BootLoopGuard.readDisabledProperty())
    }
}
"""

# 合规的 Python 测试方法体：同一函数内既有属性字面量断言，又有真实 API 带期望值断言。
PYTHON_EVIDENCE = '''\
import unittest


class SafetyEscapeGatePythonTest(unittest.TestCase):
    def test_kill_switch_property_and_engagement(self):
        self.assertEqual("persist.sys.hyperextend.disabled", KillSwitch.PROPERTY_NAME)
        self.assertEqual(True, KillSwitch.reportOnce())

    def test_boot_loop_guard_property_and_read(self):
        self.assertEqual("persist.sys.hyperextend.framework_disabled", BootLoopGuard.DISABLED_PROPERTY)
        self.assertEqual(None, BootLoopGuard.readDisabledProperty())
'''

# 诱饵六：Kotlin 文件里出现「方法外的类初始化断言」，不属于任何 `@Test` 方法体。
EVIDENCE_OUTSIDE_TEST_METHOD = """\
class DecoyInitBlockTest {
    init {
        assertEquals("persist.sys.hyperextend.disabled", KillSwitch.PROPERTY_NAME)
        assertEquals("persist.sys.hyperextend.framework_disabled", BootLoopGuard.DISABLED_PROPERTY)
        assertEquals(true, KillSwitch.reportOnce())
        assertEquals(null, BootLoopGuard.readDisabledProperty())
    }
}
"""


def sources(**overrides):
    """一份满足全部解析约束的最小合成仓库；覆盖项用来制造受控漂移。"""
    values = {
        "boot_fuse": KOTLIN_FUSE,
        "boot_fuse_test": KOTLIN_FUSE_TEST,
        "kill_switch": KOTLIN_KILL_SWITCH,
        "boot_loop_guard": KOTLIN_BOOT_LOOP_GUARD,
        "scope_catalog": SCOPE_CATALOG_WITH_HOME,
        "launcher_identity": LAUNCHER_IDENTITY,
        "launcher_native_scope": LAUNCHER_NATIVE_SCOPE,
        "safety_gate": NATIVE_SAFETY_GATE,
        "install_permit": NATIVE_INSTALL_PERMIT,
        "native_entry": NATIVE_ENTRY_WIRED,
        "readme": README,
    }
    values.update(overrides)
    return values


def evidence_root(texts):
    """建一个只含测试文件的临时仓库根，用于真实逃生门引用扫描。"""
    temporary = tempfile.TemporaryDirectory()
    base = Path(temporary.name) / "app/src/test/java/io/github/YGHFv/HyperExtend/hook"
    base.mkdir(parents=True)
    for name, text in texts.items():
        (base / (name + ".kt")).write_text(text, encoding="utf-8")
    return temporary


def jailed_root(overrides=None, decoys=None, include_escape_evidence=True):
    """把真实仓库源码复制进临时目录，再叠加诱饵文件。

    这是「真实仓库形状」的受控实验：源码逐字来自 `audit.ROOT`，诱饵只写在临时目录里，
    工作区不受影响。默认保留真实逃生门证据；反例可显式移出该证据，
    用于证明文本装饰无法把缺口翻成通过。
    """
    temporary = tempfile.TemporaryDirectory()
    root = Path(temporary.name)
    relatives = list(audit.baseline.HOST.values())
    if include_escape_evidence:
        relatives.append("app/src/test/java/io/github/YGHFv/HyperExtend/hook/EscapeGateTest.kt")
    for relative in relatives:
        source = audit.ROOT / relative
        target = root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(source.read_text(encoding="utf-8"), encoding="utf-8")
    for relative, text in (overrides or {}).items():
        target = root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")
    for relative, text in (decoys or {}).items():
        target = root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")
    temporary.jail_root = root  # 让调用方拿到路径；目录随对象回收
    return temporary


def audit_jail(jail, compiler=None, contract_value=None):
    """对临时仓库根跑一次真实审计（源码来自仓库副本，非合成夹具）。"""
    if compiler is None:
        compiler = lambda text: {"编译器": "合成",
                                 "断言数": audit.count_matches(r"static_assert\(", text)}
    return audit.verify_parity_contract(
        contract_value or contract(), compiler=compiler, root=jail.jail_root)


def audit_sources(texts=None, contract_value=None, compiler=None, root=None,
                  baseline_patches=None, probe_enabled=True, use_compiler=True,
                  refresh_fingerprint=True):
    """跑一次审计；缺省用合成源码与假的编译期复算。

    `root` 缺省指向一个没有逃生门测试文件的空目录，因此「逃生门测试引用」按未确认
    处理；`baseline_patches` 用于显式改写独立基线，验证「合同与源码同改」也会被拒。
    """
    if compiler is None and use_compiler:
        compiler = lambda text: {"编译器": "合成", "断言数": audit.count_matches(r"static_assert\(", text)}
    selected = texts if texts is not None else sources()
    signatures = {"kotlin_next": ("boot_fuse", "fun next("),
                  "kotlin_decode": ("boot_fuse", "fun decode(text: String)"),
                  "native_next": ("safety_gate", "constexpr SafetyFuseDecision safety_fuse_next(")}
    original_digests = audit.baseline.BODY_DIGESTS
    test_digests = {}
    for key, (source_key, signature) in signatures.items():
        text = audit.strip_comments(selected[source_key])
        test_digests[key] = hashlib.sha256(
            re.sub(r"\s+", " ", audit.block_at(text, text.find(signature), key)).strip().encode()
        ).hexdigest()
    audit.baseline.BODY_DIGESTS = test_digests
    originals = {}
    for name, value in (baseline_patches or {}).items():
        originals[name] = getattr(audit.baseline, name)
        setattr(audit.baseline, name, value)
    original_fingerprint = audit.BASELINE_FINGERPRINT
    if refresh_fingerprint:
        # 合成夹具会同步改写函数体摘要与基线常量；刷新内嵌指纹以便命中后续更具体的
        # 守卫。需要验证「基线同改」的用例会显式传 refresh_fingerprint=False。
        audit.BASELINE_FINGERPRINT = audit.baseline_fingerprint()
    if root is None:
        empty = tempfile.TemporaryDirectory()
        root = Path(empty.name)
    else:
        empty = None
    try:
        return audit.verify_parity_contract(
            contract_value or contract(), sources=selected, compiler=compiler, root=root,
            probe_enabled=probe_enabled)
    finally:
        audit.baseline.BODY_DIGESTS = original_digests
        audit.BASELINE_FINGERPRINT = original_fingerprint
        for name, value in originals.items():
            setattr(audit.baseline, name, value)
        if empty is not None:
            empty.cleanup()


class PositiveTest(unittest.TestCase):
    def test_scope_list_has_exactly_two_ordered_hosts(self):
        result = audit_sources()
        self.assertNotIn("作用域令牌与注入作用域一致", result["缺口"])
        item = [entry for entry in result["检查项"]
                if entry["项"] == "作用域令牌与注入作用域一致"][0]
        self.assertEqual("已核验通过", item["状态"])
        evidence = item["证据"]
        self.assertEqual(["com.miui.home", "system"], evidence["scope.list"])
        self.assertEqual(evidence["scope.list"], evidence["基线清单"])
        self.assertEqual("com.miui.home", evidence["桌面项"])
        self.assertEqual("system", evidence["system 项"])
        self.assertEqual(evidence["桌面项"], evidence["原生令牌"])
        self.assertEqual(evidence["桌面项"], evidence["身份契约"])
        self.assertFalse(result["安装许可"])
        self.assertEqual([1, 3, 5], result["未关闭门槛"])

    def test_synthetic_contract_passes_and_is_not_marked_confirmed(self):
        result = audit_sources()
        self.assertTrue(result["审计器已成功运行"])
        self.assertFalse(result["安装许可"])
        self.assertEqual([1, 3, 5], result["未关闭门槛"])
        # 原生已接线，但合成仓库逃生门无引用、README 覆盖不足仍是如实缺口。
        self.assertFalse(result["防砖已确认"])
        self.assertNotIn("原生入口安全门禁接线", result["缺口"])
        self.assertIn("逃生门测试引用", result["缺口"])
        self.assertIn("README 逃生属性覆盖", result["缺口"])
        self.assertNotIn("作用域令牌与注入作用域一致", result["缺口"])

    def test_isolated_guard_cannot_confirm_even_when_other_evidence_is_complete(self):
        temporary = evidence_root({"SafetyEscapeGateTest": EVIDENCE_COMPLETE})
        try:
            with self.assertRaisesRegex(AuditError, "结构接线状态与源码实测不一致"):
                audit_sources(
                    sources(native_entry=NATIVE_ENTRY_ISOLATED_GUARD, readme=README_COVERED),
                    root=Path(temporary.name),
                )
        finally:
            temporary.cleanup()
        result = audit.verify_native_wiring(
            contract(), {"native_entry": NATIVE_ENTRY_ISOLATED_GUARD}, enforce_contract=False)
        self.assertFalse(result["实际接线"])
        self.assertIn("entry_reachable", result["待补证据"])
        self.assertIn("install_plan_gated", result["待补证据"])

    def test_probe_source_reflects_decode_and_case_tables(self):
        text = audit.native_probe_source(contract(), ["kSafetyStateUnreadable", "kSafetyFuseDisabled",
                                                      "kSafetyUnsafeClock", "kSafetyNotArmed"])
        self.assertIn('static_assert(kSafetyFuseWindowMs == 300000, "SAFETY_PARITY_WINDOW");', text)
        self.assertIn("return !decode_safety_fuse(\"\", 0, state);", text)
        self.assertIn('static_assert(decode_count_three_trips(), "DECODE_count_three_trips");', text)
        self.assertIn('static_assert(d_no_previous.ok == false, "CASE_ok_no_previous");', text)
        self.assertIn('static_assert((d_no_previous.blockers & kSafetyNotArmed) != 0', text)
        self.assertIn('static_assert(d_third_restart_trips.next.disabled == true', text)


class DriftTest(unittest.TestCase):
    def test_kotlin_window_constant_drift_is_rejected(self):
        texts = sources(boot_fuse=KOTLIN_FUSE.replace("5 * 60 * 1000L", "10 * 60 * 1000L"))
        with self.assertRaisesRegex(AuditError, "熔断窗口常量与独立基线不符"):
            audit_sources(texts)

    def test_native_window_constant_drift_is_rejected(self):
        texts = sources(safety_gate=NATIVE_SAFETY_GATE.replace(
            "kSafetyFuseWindowMs = 5 * 60 * 1000", "kSafetyFuseWindowMs = 10 * 60 * 1000"))
        with self.assertRaisesRegex(AuditError, "熔断窗口常量与独立基线不符"):
            audit_sources(texts)

    def test_min_time_constant_drift_is_rejected(self):
        texts = sources(boot_fuse=KOTLIN_FUSE.replace("1_600_000_000_000L", "1_700_000_000_000L"))
        with self.assertRaisesRegex(AuditError, "最小可信时间常量与独立基线不符"):
            audit_sources(texts)

    def test_kotlin_field_order_drift_is_rejected(self):
        swapped = KOTLIN_FUSE.replace(
            "    val disabled: Boolean = false,\n    val lastResetToken: Long = 0,",
            "    val lastResetToken: Long = 0,\n    val disabled: Boolean = false,")
        with self.assertRaisesRegex(AuditError, "字段顺序与独立基线不符"):
            audit_sources(sources(boot_fuse=swapped))

    def test_native_field_order_drift_is_rejected(self):
        swapped = NATIVE_SAFETY_GATE.replace(
            "    bool disabled;\n    int64_t last_reset_token;",
            "    int64_t last_reset_token;\n    bool disabled;")
        with self.assertRaisesRegex(AuditError, "字段顺序与独立基线不符"):
            audit_sources(sources(safety_gate=swapped))

    def test_kotlin_threshold_drift_is_rejected(self):
        texts = sources(boot_fuse=KOTLIN_FUSE.replace("coerceAtMost(3)", "coerceAtMost(4)"))
        with self.assertRaisesRegex(AuditError, "熔断判定结构缺失或顺序不符"):
            audit_sources(texts)

    def test_native_threshold_drift_is_rejected(self):
        texts = sources(safety_gate=NATIVE_SAFETY_GATE.replace(
            "previous.rapid_restarts + 1 > 3 ? 3", "previous.rapid_restarts + 1 > 5 ? 5"))
        with self.assertRaisesRegex(AuditError, "熔断判定结构缺失或顺序不符"):
            audit_sources(texts)

    def test_reordered_kotlin_guards_are_rejected(self):
        swapped = KOTLIN_FUSE.replace(
            "        if (resetToken > previous.lastResetToken) return previous\n"
            "        if (previous.disabled) return previous",
            "        if (previous.disabled) return previous\n"
            "        if (resetToken > previous.lastResetToken) return previous")
        with self.assertRaisesRegex(AuditError, "顺序不符"):
            audit_sources(sources(boot_fuse=swapped))

    def test_reordered_native_guards_are_rejected(self):
        swapped = NATIVE_SAFETY_GATE.replace(
            "    if (reset_token > previous.last_reset_token) return {};\n"
            "    if (previous.disabled) return {};",
            "    if (previous.disabled) return {};\n"
            "    if (reset_token > previous.last_reset_token) return {};")
        with self.assertRaisesRegex(AuditError, "顺序不符"):
            audit_sources(sources(safety_gate=swapped))

    def test_scope_token_drift_is_rejected(self):
        texts = sources(safety_gate=NATIVE_SAFETY_GATE.replace(
            'kLauncherSafetyScope[] = "com.miui.home"', 'kLauncherSafetyScope[] = "com.other.home"'))
        with self.assertRaisesRegex(AuditError, "桌面安全作用域漂移"):
            audit_sources(texts)

    def test_scope_not_in_main_catalog_is_not_the_criterion(self):
        """主模块 SCOPES 里没有桌面进程名是**正常**的：它只列界面侧可重启宿主。"""
        result = audit_sources(sources(scope_catalog=SCOPE_CATALOG_WITHOUT_HOME))
        self.assertNotIn("作用域令牌与注入作用域一致", result["缺口"])

    def test_scope_identity_mismatch_is_a_gap(self):
        result = audit_sources(sources(launcher_identity=LAUNCHER_IDENTITY_MISMATCH))
        self.assertIn("作用域令牌与注入作用域一致", result["缺口"])
        item = [entry for entry in result["检查项"]
                if entry["项"] == "作用域令牌与注入作用域一致"][0]
        self.assertIn("LauncherHostContract.PACKAGE", item["证据"]["不一致来源"])

    def test_scope_list_mismatch_is_a_gap(self):
        result = audit_sources(sources(launcher_native_scope=LAUNCHER_NATIVE_SCOPE_MISMATCH))
        self.assertIn("作用域令牌与注入作用域一致", result["缺口"])
        item = [entry for entry in result["检查项"]
                if entry["项"] == "作用域令牌与注入作用域一致"][0]
        self.assertIn("scope.list", item["证据"]["不一致来源"])

    def test_scope_list_with_extra_entry_is_a_gap(self):
        """注入作用域多一项就是多注入一个进程，必须按缺口报告而不是取「包含」。"""
        result = audit_sources(sources(launcher_native_scope=LAUNCHER_NATIVE_SCOPE_EXTRA))
        self.assertIn("作用域令牌与注入作用域一致", result["缺口"])

    def test_scope_list_reversed_order_is_a_gap(self):
        result = audit_sources(sources(launcher_native_scope="system\ncom.miui.home\n"))
        self.assertIn("作用域令牌与注入作用域一致", result["缺口"])
        item = [entry for entry in result["检查项"]
                if entry["项"] == "作用域令牌与注入作用域一致"][0]
        self.assertEqual("system", item["证据"]["桌面项"])
        self.assertEqual("com.miui.home", item["证据"]["system 项"])

    def test_scope_list_missing_system_is_a_gap(self):
        result = audit_sources(sources(launcher_native_scope="com.miui.home\n"))
        self.assertIn("作用域令牌与注入作用域一致", result["缺口"])

    def test_scope_list_missing_launcher_is_a_gap(self):
        result = audit_sources(sources(launcher_native_scope="system\n"))
        self.assertIn("作用域令牌与注入作用域一致", result["缺口"])

    def test_scope_list_blank_line_interference_is_a_gap(self):
        for text in ("\ncom.miui.home\nsystem\n",
                     "com.miui.home\n\nsystem\n",
                     "com.miui.home\nsystem\n\n",
                     "com.miui.home\n \t\nsystem\n",
                     ""):
            with self.subTest(scope_list=text):
                result = audit_sources(sources(launcher_native_scope=text))
                self.assertIn("作用域令牌与注入作用域一致", result["缺口"])
                item = [entry for entry in result["检查项"]
                        if entry["项"] == "作用域令牌与注入作用域一致"][0]
                self.assertEqual(text.splitlines(), item["证据"]["scope.list"])
                self.assertIn("scope.list", item["证据"]["不一致来源"])

    def test_scope_list_entry_whitespace_is_a_gap(self):
        for text in (" com.miui.home\nsystem\n", "com.miui.home\nsystem \n"):
            with self.subTest(scope_list=text):
                result = audit_sources(sources(launcher_native_scope=text))
                self.assertIn("作用域令牌与注入作用域一致", result["缺口"])

    def test_legacy_scope_criterion_is_gone(self):
        """旧判据已删除：主 SCOPES 里出现桌面进程名不能顶替 launcherNative 的来源。

        受控形态：主 SCOPES 里**确实**有 `com.miui.home`（旧判据会判通过），但
        launcherNative 的注入作用域缺失该项 —— 新判据仍必须报缺口。
        """
        result = audit_sources(sources(
            scope_catalog=SCOPE_CATALOG_WITH_HOME,
            launcher_native_scope=LAUNCHER_NATIVE_SCOPE_MISMATCH,
        ))
        self.assertIn("作用域令牌与注入作用域一致", result["缺口"])

    def test_unwired_entry_when_contract_says_wired_is_rejected(self):
        value = contract()
        value["nativeWiring"]["wired"] = True
        with self.assertRaisesRegex(AuditError, "结构接线状态与源码实测不一致"):
            audit_sources(sources(native_entry=NATIVE_ENTRY_UNWIRED), contract_value=value)

    def test_isolated_guard_function_is_not_native_wiring(self):
        """孤立函数里的提交/授权写法不算接线：它从导出入口不可达。"""
        result = audit.verify_native_wiring(contract(), {"native_entry": NATIVE_ENTRY_ISOLATED_GUARD}, enforce_contract=False)
        self.assertFalse(result["实际接线"])
        self.assertEqual([], result["可达函数"])
        self.assertIn("entry_reachable", result["待补证据"])

    def test_contract_and_baseline_flip_cannot_fake_wiring(self):
        """合同与基线同改 wired=true，但源码没接线，必须硬失败。"""
        value = contract()
        value["nativeWiring"]["wired"] = True
        changed = dict(audit.baseline.NATIVE_WIRING_BASELINE, wired=True)
        with self.assertRaisesRegex(AuditError, "结构接线状态与源码实测不一致"):
            audit_sources(sources(native_entry=NATIVE_ENTRY_ISOLATED_GUARD), contract_value=value,
                          baseline_patches={"NATIVE_WIRING_BASELINE": changed})

    def test_string_literal_guard_is_not_a_call(self):
        result = audit.verify_native_wiring(contract(), {
            "native_entry": 'const char *decoy = "if (!install_permitted(evidence)) return false;";'}, enforce_contract=False)
        self.assertEqual([], result["调用点"])
        self.assertFalse(result["实际接线"])
        self.assertIn("entry_reachable", result["待补证据"])


class EscapeGateTest(unittest.TestCase):
    def test_property_collision_is_rejected(self):
        texts = sources(boot_loop_guard=KOTLIN_BOOT_LOOP_GUARD.replace(
            '"persist.sys.hyperextend.framework_disabled"', '"persist.sys.hyperextend.disabled"'))
        with self.assertRaisesRegex(AuditError, "属性名漂移"):
            audit_sources(texts)

    def test_kill_switch_losing_fail_closed_is_rejected(self):
        texts = sources(kill_switch=KOTLIN_KILL_SWITCH.replace(
            "    } catch (failure: Throwable) {\n        true\n    }",
            "    } catch (failure: Throwable) {\n        false\n    }"))
        with self.assertRaisesRegex(AuditError, "fail-closed"):
            audit_sources(texts)

    def test_boot_loop_guard_losing_fail_closed_is_rejected(self):
        texts = sources(boot_loop_guard=KOTLIN_BOOT_LOOP_GUARD.replace(
            "    } catch (t: Throwable) {\n        null\n    }",
            "    } catch (t: Throwable) {\n        false\n    }"))
        with self.assertRaisesRegex(AuditError, "不再返回 null"):
            audit_sources(texts)

    def test_missing_fail_closed_comparison_is_rejected(self):
        texts = sources(boot_loop_guard=KOTLIN_BOOT_LOOP_GUARD.replace(
            "readDisabledProperty() != false", "readDisabledProperty() == true"))
        with self.assertRaisesRegex(AuditError, "fail-closed"):
            audit_sources(texts)

    def test_readme_missing_escape_property_is_a_gap(self):
        result = audit_sources()
        self.assertIn("README 逃生属性覆盖", result["缺口"])
        item = [entry for entry in result["检查项"] if entry["项"] == "README 逃生属性覆盖"][0]
        self.assertEqual(["persist.sys.hyperextend.framework_disabled"], item["证据"]["未覆盖"])

    def test_readme_property_outside_escape_section_is_a_gap(self):
        result = audit_sources(sources(readme=README_OUT_OF_SECTION))
        item = [entry for entry in result["检查项"] if entry["项"] == "README 逃生属性覆盖"][0]
        self.assertEqual("缺口", item["状态"])
        self.assertEqual(sorted(["persist.sys.hyperextend.disabled",
                                 "persist.sys.hyperextend.framework_disabled"]),
                         sorted(item["证据"]["未覆盖"]))

    def test_readme_coverage_list_cannot_be_trimmed(self):
        value = contract()
        value["readmeEscapedProperties"] = ["persist.sys.hyperextend.disabled"]
        with self.assertRaisesRegex(AuditError, "覆盖清单与逃生门小节不一致"):
            audit_sources(contract_value=value)

    def test_readme_coverage_list_cannot_gain_unknown_property(self):
        value = contract()
        value["readmeEscapedProperties"].append("persist.sys.hyperextend.imaginary")
        with self.assertRaisesRegex(AuditError, "覆盖清单与逃生门小节不一致"):
            audit_sources(contract_value=value)

    def test_real_assertions_are_reported_as_evidence(self):
        temporary = evidence_root({"SafetyEscapeGateTest": EVIDENCE_COMPLETE})
        try:
            result = audit_sources(root=Path(temporary.name))
        finally:
            temporary.cleanup()
        item = [entry for entry in result["检查项"] if entry["项"] == "逃生门测试引用"][0]
        self.assertEqual("已核验通过", item["状态"])
        self.assertNotIn("逃生门测试引用", result["缺口"])


class InstallPlanGateTest(unittest.TestCase):
    def assert_plan_gate_rejected(self, text):
        self.assertNotEqual(NATIVE_ENTRY_WIRED, text)
        result = audit.verify_native_wiring(
            contract(), {"native_entry": text}, enforce_contract=False)
        self.assertFalse(result["实际接线"])
        self.assertIs(result["完整链路"]["install_plan_gated"], False)
        self.assertIn("install_plan_gated", result["待补证据"])

    def test_missing_plan_gate_is_rejected(self):
        self.assert_plan_gate_rejected(NATIVE_ENTRY_WIRED_NO_PLAN_GATE)

    def test_plan_gate_after_install_is_rejected(self):
        self.assert_plan_gate_rejected(NATIVE_ENTRY_WIRED_PLAN_GATE_AFTER_INSTALL)

    def test_reversed_plan_gate_is_rejected(self):
        self.assert_plan_gate_rejected(NATIVE_ENTRY_WIRED_PLAN_GATE_REVERSED)

    def test_constant_short_circuit_plan_gate_is_rejected(self):
        self.assert_plan_gate_rejected(NATIVE_ENTRY_WIRED_PLAN_GATE_CONSTANT)

    def test_plan_gate_before_permit_is_rejected(self):
        self.assert_plan_gate_rejected(NATIVE_ENTRY_WIRED_PLAN_GATE_BEFORE_PERMIT)

    def test_other_plan_object_is_rejected(self):
        self.assert_plan_gate_rejected(NATIVE_ENTRY_WIRED_PLAN_GATE_OTHER_OBJECT)

    def test_runtime_wrapped_plan_gate_is_rejected(self):
        self.assert_plan_gate_rejected(NATIVE_ENTRY_WIRED_PLAN_GATE_RUNTIME)

    def test_ready_gate_is_accepted(self):
        result = audit.verify_native_wiring(
            contract(), {"native_entry": NATIVE_ENTRY_WIRED_PLAN_READY_GATE})
        self.assertTrue(result["实际接线"])
        self.assertIs(result["完整链路"]["install_plan_gated"], True)
        self.assertEqual([], result["待补证据"])

    def test_missing_install_site_keeps_plan_gate_false(self):
        self.assert_plan_gate_rejected(NATIVE_ENTRY_WIRED_NO_INSTALL)

    def test_unreachable_plan_gate_is_rejected(self):
        self.assert_plan_gate_rejected(NATIVE_ENTRY_WIRED.replace(
            "if (!resolve_launcher_install_plan(plan)) return 4;",
            "if (false) { if (!resolve_launcher_install_plan(plan)) return 4; }"))

    def test_plan_gate_without_live_abort_is_rejected(self):
        self.assert_plan_gate_rejected(NATIVE_ENTRY_WIRED.replace(
            "if (!resolve_launcher_install_plan(plan)) return 4;",
            "if (!resolve_launcher_install_plan(plan)) { if (false) return 4; }"))


class BaselineRegressionTest(unittest.TestCase):
    def test_embedded_fingerprint_matches_loaded_baseline(self):
        """工具内嵌指纹必须与基线模块同步，否则任何运行都会先失败。"""
        self.assertEqual(audit.BASELINE_FINGERPRINT, audit.baseline_fingerprint())

    def test_contract_and_source_cannot_jointly_redefine_window(self):
        value = contract()
        value["fuse"]["windowMs"] = 600000
        with self.assertRaisesRegex(AuditError, "独立基线"):
            audit.verify_contract(value)

    def test_contract_and_baseline_edit_without_tool_change_is_rejected(self):
        """合同与基线同改、但不改审计工具内嵌指纹，必须被拒。"""
        value = contract()
        value["fuse"]["windowMs"] = 600000
        edited = dict(audit.baseline.FUSE)
        edited["window_ms"] = 600000
        with self.assertRaisesRegex(AuditError, "独立基线常量被改写"):
            audit_sources(contract_value=value,
                          baseline_patches={"FUSE": edited},
                          refresh_fingerprint=False)

    def test_baseline_case_table_edit_without_tool_change_is_rejected(self):
        with self.assertRaisesRegex(AuditError, "独立基线常量被改写"):
            audit_sources(baseline_patches={"CASE_TABLES_SHA256": "0" * 64},
                          refresh_fingerprint=False)

    def test_include_only_is_not_native_wiring(self):
        value = contract()
        result = audit.verify_native_wiring(value, {"native_entry": NATIVE_ENTRY_INCLUDE_ONLY}, enforce_contract=False)
        self.assertFalse(result["实际接线"])
        self.assertEqual([], result["调用点"])

    def test_call_without_control_flow_is_not_native_wiring(self):
        value = contract()
        result = audit.verify_native_wiring(value, {"native_entry": NATIVE_ENTRY_CALL_WITHOUT_CONTROL}, enforce_contract=False)
        self.assertFalse(result["实际接线"])
        self.assertIn("entry_reachable", result["待补证据"])

    def test_complete_wiring_fixture_is_actually_verified(self):
        """完整链路验证器必须能在真正接线时翻绿，否则它只是永久假绿的装饰。"""
        value = contract()
        value["nativeWiring"]["wired"] = True
        result = audit.verify_native_wiring(value, {"native_entry": NATIVE_ENTRY_WIRED})
        self.assertEqual([], result["待补证据"])
        self.assertTrue(result["实际接线"])

    def test_extra_install_site_is_rejected(self):
        """完整接线之外另开一处 hook 调用点，必须被单独拒绝。"""
        value = contract()
        result = audit.verify_native_wiring(value, {"native_entry": NATIVE_ENTRY_WIRED_EXTRA_INSTALL}, enforce_contract=False)
        self.assertFalse(result["实际接线"])
        self.assertIn("install_path_exclusive", result["待补证据"])

    def test_ordering_violations_are_rejected(self):
        """形态完整但顺序错的写法也不算接线：门禁在后、安装在前、失败不早退。"""
        value = contract()
        cases = [
            (NATIVE_ENTRY_WIRED_GATE_AFTER_COMMIT, "snapshot_gated"),
            (NATIVE_ENTRY_WIRED_INSTALL_BEFORE_COMMIT, "install_dominated"),
            (NATIVE_ENTRY_WIRED_NO_EARLY_RETURN, "commit_result_controlled"),
        ]
        for text, missing in cases:
            with self.subTest(missing=missing):
                result = audit.verify_native_wiring(value, {"native_entry": text}, enforce_contract=False)
                self.assertFalse(result["实际接线"])
                self.assertIn(missing, result["待补证据"])

    def test_each_missing_link_is_rejected_separately(self):
        """任一环节缺失都必须导致实测为未接线，不能靠其他环节补上。"""
        value = contract()
        cases = [
            (NATIVE_ENTRY_WIRED_NO_GATE, "snapshot_gated"),
            (NATIVE_ENTRY_WIRED_NO_COMMIT, "commit_result_controlled"),
            (NATIVE_ENTRY_WIRED_NO_DOMINANCE, "install_dominated"),
            (NATIVE_ENTRY_WIRED_NO_INSTALL, "install_path_present"),
        ]
        for text, missing in cases:
            with self.subTest(missing=missing):
                result = audit.verify_native_wiring(value, {"native_entry": text}, enforce_contract=False)
                self.assertFalse(result["实际接线"])
                self.assertIn(missing, result["待补证据"])

    def test_control_flow_decoys_are_rejected(self):
        """形态齐全但语义绕过控制流的写法都不算接线，且各自给出对应的待补证据。"""
        value = contract()
        cases = [
            (NATIVE_ENTRY_WIRED_INSTALL_BEFORE_PERMIT, "install_path_present",
             "安装调用早于安装许可早退"),
            (NATIVE_ENTRY_WIRED_GATE_RESULT_DROPPED, "snapshot_gated",
             "快照门禁返回值被丢弃"),
            (NATIVE_ENTRY_WIRED_SELF_ASSERTED_EVIDENCE, "install_dominated",
             "安全证据被自填 true 冒充"),
            (NATIVE_ENTRY_WIRED_HOOK_IN_DEAD_BRANCH, "install_path_present",
             "安装调用在恒假分支里"),
            (NATIVE_ENTRY_WIRED_HOOK_AFTER_RETURN, "install_path_present",
             "安装调用在无条件 return 之后"),
            (NATIVE_ENTRY_WIRED_PERMIT_RESULT_DROPPED, "install_dominated",
             "安装许可判定结果被丢弃"),
        ]
        for text, missing, label in cases:
            with self.subTest(case=label):
                result = audit.verify_native_wiring(value, {"native_entry": text}, enforce_contract=False)
                self.assertFalse(result["实际接线"])
                self.assertIn(missing, result["待补证据"])

    def test_deeper_decoys_are_rejected(self):
        """再深一层：恒定条件短路、误用底层门禁、恒假循环/goto、证据字段错位都要拒绝。"""
        value = contract()
        cases = [
            (NATIVE_ENTRY_WIRED_PERMIT_ALWAYS_RETURNS, "install_dominated",
             "安装许可早退被 || true 短路成恒真"),
            (NATIVE_ENTRY_WIRED_COMMIT_GUARD_CONSTANT, "commit_result_controlled",
             "提交守卫被 && false 短路成恒假"),
            (NATIVE_ENTRY_WIRED_WRONG_GATE, "snapshot_gated",
             "门禁误用底层 snapshot_errors"),
            (NATIVE_ENTRY_WIRED_HOOK_IN_FALSE_LOOP, "install_path_present",
             "安装调用在 while(0) 里"),
            (NATIVE_ENTRY_WIRED_HOOK_AFTER_GOTO, "install_path_present",
             "安装调用在 goto 之后"),
            (NATIVE_ENTRY_WIRED_EVIDENCE_FIELD_SWAP, "install_dominated",
             "安全证据字段错位"),
        ]
        for text, missing, label in cases:
            with self.subTest(case=label):
                result = audit.verify_native_wiring(value, {"native_entry": text}, enforce_contract=False)
                self.assertFalse(result["实际接线"])
                self.assertIn(missing, result["待补证据"])

    def test_gate_polarity_accepts_truthy_and_rejects_negation(self):
        """`!= 0`、truthy、`!= 0U` 都是等价正例；`== 0`、`!errors(...)` 必须拒绝。"""
        value = contract()
        value["nativeWiring"]["wired"] = True
        ok = audit.verify_native_wiring(value, {"native_entry": NATIVE_ENTRY_WIRED_GATE_TRUTHY})
        self.assertTrue(ok["实际接线"])
        value["nativeWiring"]["wired"] = False
        bad = audit.verify_native_wiring(value, {"native_entry": NATIVE_ENTRY_WIRED_GATE_NEGATED})
        self.assertFalse(bad["实际接线"])
        self.assertIn("snapshot_gated", bad["待补证据"])

    def test_gate_and_permit_must_be_reachable(self):
        """门禁与安装许可早退只有落在可达代码里才算数。"""
        value = contract()
        cases = [
            (NATIVE_ENTRY_WIRED_GATE_IN_DEAD_BRANCH, "snapshot_gated", "门禁只在 if(0) 里"),
            (NATIVE_ENTRY_WIRED_PERMIT_IN_DEAD_BRANCH, "install_dominated", "许可早退只在 if(0) 里"),
        ]
        for text, missing, label in cases:
            with self.subTest(case=label):
                result = audit.verify_native_wiring(value, {"native_entry": text}, enforce_contract=False)
                self.assertFalse(result["实际接线"])
                self.assertIn(missing, result["待补证据"])

    def test_safety_ready_guard_variant_is_accepted(self):
        """守卫写成 `!result.safety_ready()` 是等价正例，不能被误判为未接线。"""
        value = contract()
        value["nativeWiring"]["wired"] = True
        result = audit.verify_native_wiring(
            value, {"native_entry": NATIVE_ENTRY_WIRED_SAFETY_READY_GUARD})
        self.assertTrue(result["实际接线"])
        self.assertEqual([], result["待补证据"])

    def test_lambda_scope_is_respected(self):
        """未调用 lambda 里的安装不算接线；立即调用 lambda 与显式类型声明都不该误判。"""
        value = contract()
        bad = audit.verify_native_wiring(
            value, {"native_entry": NATIVE_ENTRY_WIRED_HOOK_IN_UNUSED_LAMBDA}, enforce_contract=False)
        self.assertFalse(bad["实际接线"])
        self.assertIn("install_path_present", bad["待补证据"])
        value["nativeWiring"]["wired"] = True
        for text, label in [(NATIVE_ENTRY_WIRED_EXPLICIT_RESULT_TYPE, "显式类型提交结果"),
                            (NATIVE_ENTRY_WIRED_IMMEDIATE_LAMBDA, "立即调用 lambda")]:
            with self.subTest(case=label):
                result = audit.verify_native_wiring(value, {"native_entry": text})
                self.assertTrue(result["实际接线"])
                self.assertEqual([], result["待补证据"])

    def test_commit_must_itself_be_reachable(self):
        """提交调用本身必须可达：只在恒假分支里提交、或整条链路写在不可达函数里都要拒绝。"""
        value = contract()
        cases = [
            (NATIVE_ENTRY_WIRED_COMMIT_IN_DEAD_BRANCH, "entry_reachable", "提交只在 if(0) 里"),
            (NATIVE_ENTRY_WIRED_ONLY_UNREACHABLE, "entry_reachable", "完整链路只在不可达函数里"),
        ]
        for text, missing, label in cases:
            with self.subTest(case=label):
                result = audit.verify_native_wiring(value, {"native_entry": text}, enforce_contract=False)
                self.assertFalse(result["实际接线"])
                self.assertIn(missing, result["待补证据"])

    def test_commit_failure_must_return_in_truthy_branch(self):
        """提交失败的早退必须落在真分支；空真分支或从 else 继续都不算阻断安装。"""
        value = contract()
        for text, label in [(NATIVE_ENTRY_WIRED_COMMIT_FAILURE_EMPTY, "失败真分支为空"),
                            (NATIVE_ENTRY_WIRED_COMMIT_FAILURE_ELSE, "失败从 else 继续")]:
            with self.subTest(case=label):
                result = audit.verify_native_wiring(value, {"native_entry": text}, enforce_contract=False)
                self.assertFalse(result["实际接线"])
                self.assertIn("failure_blocks_install", result["待补证据"])

    def test_install_inside_denied_branch_is_rejected(self):
        """安装写进「许可失败」的真分支属于错误分支，即便位置在许可判定之后也拒绝。"""
        value = contract()
        result = audit.verify_native_wiring(
            value, {"native_entry": NATIVE_ENTRY_WIRED_INSTALL_IN_DENIED_BRANCH}, enforce_contract=False)
        self.assertFalse(result["实际接线"])
        self.assertIn("install_path_present", result["待补证据"])

    def test_gate_and_commit_guard_polarity_decoys(self):
        """门禁/提交守卫的极性与恒定条件都必须正确，否则不算接线。"""
        value = contract()
        cases = [
            (NATIVE_ENTRY_WIRED_GATE_POLARITY, "snapshot_gated", "门禁极性反转"),
            (NATIVE_ENTRY_WIRED_GATE_CONSTANT, "snapshot_gated", "门禁被 0 && 短路"),
            (NATIVE_ENTRY_WIRED_GATE_OTHER_SNAPSHOT, "snapshot_gated", "门禁校验另一快照"),
            (NATIVE_ENTRY_WIRED_COMMIT_GUARD_POLARITY, "commit_result_controlled", "提交守卫极性反转"),
            (NATIVE_ENTRY_WIRED_COMMIT_GUARD_TRUE, "commit_result_controlled", "提交守卫被 true || 短路"),
        ]
        for text, missing, label in cases:
            with self.subTest(case=label):
                result = audit.verify_native_wiring(value, {"native_entry": text}, enforce_contract=False)
                self.assertFalse(result["实际接线"])
                self.assertIn(missing, result["待补证据"])

    def test_designated_and_positional_evidence_both_recognized(self):
        """证据“来自提交结果”必须同时认按位与指定初始化，且自填 true 仍被拒绝。"""
        value = contract()
        value["nativeWiring"]["wired"] = True
        ok = audit.verify_native_wiring(
            value, {"native_entry": NATIVE_ENTRY_WIRED_DESIGNATED_EVIDENCE})
        self.assertTrue(ok["实际接线"])
        self.assertEqual([], ok["待补证据"])
        value["nativeWiring"]["wired"] = False
        bad = audit.verify_native_wiring(
            value, {"native_entry": NATIVE_ENTRY_WIRED_DESIGNATED_SELF_ASSERTED})
        self.assertFalse(bad["实际接线"])
        self.assertIn("install_dominated", bad["待补证据"])

    def test_aliased_install_call_is_not_unique(self):
        """先取 hook_func 别名再调用，同样绕过「全文仅一处调用点」的唯一性要求。"""
        value = contract()
        result = audit.verify_native_wiring(
            value, {"native_entry": NATIVE_ENTRY_WIRED_ALIASED_INSTALL}, enforce_contract=False)
        self.assertFalse(result["实际接线"])
        self.assertIn("install_path_exclusive", result["待补证据"])
        self.assertGreater(result["别名安装点"], 0)

    def test_dead_branch_variants_are_rejected(self):
        """恒假分支的写法远不止 `if (0)`：常量循环、if constexpr、宏、switch、
        恒真 if 的 else、以及入口只在死分支里调用整条链路，都必须拒绝。"""
        cases = [
            (NATIVE_ENTRY_WIRED_IN_IF_CONSTEXPR_FALSE, "if constexpr(false) 包住整条链路"),
            (NATIVE_ENTRY_WIRED_INSTALL_IN_IF_CONSTEXPR_FALSE, "if constexpr(false) 包住安装"),
            (NATIVE_ENTRY_WIRED_INSTALL_IN_FALSE_FOR, "for (; false;) 包住安装"),
            (NATIVE_ENTRY_WIRED_INSTALL_IN_DEAD_ELSE, "恒真 if 的 else 分支里安装"),
            (NATIVE_ENTRY_WIRED_INSTALL_IN_SWITCH_MISS, "switch 不匹配分支里安装"),
            (NATIVE_ENTRY_WIRED_MACRO_FALSE_GATE, "宏恒假条件包住门禁"),
            (NATIVE_ENTRY_WIRED_INSTALL_AFTER_INFINITE_LOOP, "无 break 的 while(true) 之后安装"),
            (NATIVE_ENTRY_WIRED_CALL_SITE_IN_DEAD_BRANCH, "入口只在 if(0) 里调用链路"),
            (NATIVE_ENTRY_WIRED_CALL_SITE_IN_IF_CONSTEXPR_FALSE, "入口只在 if constexpr(false) 里调用链路"),
            (NATIVE_ENTRY_WIRED_VIA_DEAD_HELPER, "链路只经恒假条件调用的中间函数到达"),
        ]
        for text, label in cases:
            with self.subTest(case=label):
                result = audit.verify_native_wiring(contract(), {"native_entry": text}, enforce_contract=False)
                self.assertFalse(result["实际接线"])
                self.assertTrue(result["待补证据"])

    def test_do_while_zero_envelope_is_not_a_dead_branch(self):
        """`do { ... } while (false)` 只是把一组语句包成块，块体必然执行一次：
        包在里面的真实接线必须翻绿；写进许可失败真分支的安装仍必须拒绝。"""
        value = contract()
        value["nativeWiring"]["wired"] = True
        ok = audit.verify_native_wiring(
            value, {"native_entry": NATIVE_ENTRY_WIRED_INSTALL_IN_DO_WHILE})
        self.assertTrue(ok["实际接线"])
        self.assertEqual([], ok["待补证据"])
        value["nativeWiring"]["wired"] = False
        bad = audit.verify_native_wiring(
            value, {"native_entry": NATIVE_ENTRY_WIRED_INSTALL_IN_BRACELESS_DENIED_BRANCH})
        self.assertFalse(bad["实际接线"])
        self.assertIn("install_path_present", bad["待补证据"])

    def test_reachability_edge_cases(self):
        """可达性边界：标签、常量 switch、嵌套 return、常量 if 的后续语句。"""
        rejected = [
            (NATIVE_ENTRY_WIRED_HOOK_AFTER_GOTO, "可达 goto 与其标签之间的安装"),
            (NATIVE_ENTRY_WIRED_INSTALL_AFTER_NESTED_RETURN, "嵌套块 return 之后安装"),
            (NATIVE_ENTRY_WIRED_INSTALL_AFTER_CONST_TRUE_RETURN, "if(1) return 之后安装"),
            (NATIVE_ENTRY_WIRED_INSTALL_AFTER_CONST_FALSE_ELSE_RETURN, "if(0)else return 之后安装"),
        ]
        for text, label in rejected:
            with self.subTest(case=label):
                result = audit.verify_native_wiring(contract(), {"native_entry": text}, enforce_contract=False)
                self.assertFalse(result["实际接线"], label)
        accepted = [
            (NATIVE_ENTRY_WIRED_REACHABLE_GOTO, "goto 落到安装调用之前"),
            (NATIVE_ENTRY_WIRED_SWITCH_MATCH, "常量 switch 匹配分支里安装"),
            (NATIVE_ENTRY_WIRED_SWITCH_DEFAULT, "常量 switch 的 default 里安装"),
            (NATIVE_ENTRY_WIRED_INSTALL_IN_DO_WHILE, "do{...}while(false) 包裹接线"),
        ]
        value = contract()
        value["nativeWiring"]["wired"] = True
        for text, label in accepted:
            with self.subTest(case=label):
                result = audit.verify_native_wiring(value, {"native_entry": text})
                self.assertTrue(result["实际接线"], label)
                self.assertEqual([], result["待补证据"], label)

    def test_early_abort_must_be_live_and_really_skip_install(self):
        """早退判据不能只找 `return` 文本：它必须自身可达、真分支必然终止，
        且跳转真的让执行离开安装路径。"""
        rejected = [
            (NATIVE_ENTRY_WIRED_GATE_ABORT_IN_DEAD_BRANCH, "门禁早退在恒假分支"),
            (NATIVE_ENTRY_WIRED_COMMIT_ABORT_IN_DEAD_BRANCH, "提交失败早退在恒假分支"),
            (NATIVE_ENTRY_WIRED_PERMIT_ABORT_IN_DEAD_BRANCH, "许可早退在恒假分支"),
            (NATIVE_ENTRY_WIRED_ABORT_WEAKENED_BY_RUNTIME_CONDITION, "早退被运行期条件削弱"),
            (NATIVE_ENTRY_WIRED_ABORT_BY_BARE_BREAK, "裸 break 不算早退"),
            (NATIVE_ENTRY_WIRED_ABORT_BY_GOTO_BACKWARD, "goto 回跳没跳过安装"),
            (NATIVE_ENTRY_WIRED_PERMIT_UNDER_RUNTIME_CONDITION, "许可判定被运行期条件包住"),
            (NATIVE_ENTRY_WIRED_GATE_UNDER_RUNTIME_CONDITION, "门禁被运行期条件包住"),
            (NATIVE_ENTRY_WIRED_COMMIT_GUARD_UNDER_RUNTIME_CONDITION, "提交守卫被运行期条件包住"),
            (NATIVE_ENTRY_WIRED_COMMIT_UNDER_RUNTIME_CONDITION, "提交调用被运行期条件包住"),
        ]
        for text, label in rejected:
            with self.subTest(case=label):
                result = audit.verify_native_wiring(contract(), {"native_entry": text}, enforce_contract=False)
                self.assertFalse(result["实际接线"], label)
        accepted = [
            (NATIVE_ENTRY_WIRED_ABORT_IN_CONST_TRUE_BLOCK, "嵌套 if(1){return;} 必然终止"),
            (NATIVE_ENTRY_WIRED_ABORT_WITH_TRAILING_STATEMENT, "return 后死代码仍必然终止"),
            (NATIVE_ENTRY_WIRED_ABORT_BY_GOTO_FORWARD, "goto 跳到安装之后"),
            (NATIVE_ENTRY_WIRED_ABORT_BY_BREAK_IN_DO_WHILE, "do-while(0) 里 break 跳过安装"),
        ]
        value = contract()
        value["nativeWiring"]["wired"] = True
        for text, label in accepted:
            with self.subTest(case=label):
                result = audit.verify_native_wiring(value, {"native_entry": text})
                self.assertTrue(result["实际接线"], label)
                self.assertEqual([], result["待补证据"], label)

    def test_install_must_be_unconditionally_reached(self):
        """安装点必须落在必然执行的位置：包进运行期 if / 有界循环都存在
        一条不安装也能走到底的路径，不能算接线。"""
        rejected = [
            (NATIVE_ENTRY_WIRED_INSTALL_UNDER_RUNTIME_CONDITION, "安装点被运行期 if 包住"),
            (NATIVE_ENTRY_WIRED_INSTALL_IN_BOUNDED_LOOP, "安装点被有界循环包住"),
        ]
        value = contract()
        value["nativeWiring"]["wired"] = False
        for text, label in rejected:
            with self.subTest(case=label):
                result = audit.verify_native_wiring(value, {"native_entry": text})
                self.assertFalse(result["实际接线"], label)
                self.assertIn("install_path_present", result["待补证据"], label)
        accepted = [(NATIVE_ENTRY_WIRED_COMMIT_RESULT_READ_ONLY, "只读引用提交结果")]
        value = contract()
        value["nativeWiring"]["wired"] = True
        for text, label in accepted:
            with self.subTest(case=label):
                result = audit.verify_native_wiring(value, {"native_entry": text})
                self.assertTrue(result["实际接线"], label)

    def test_commit_result_cannot_be_rewritten_before_guard(self):
        """`result.committed = true;` 这类写入让守卫读不到真实提交结果，
        等于把「提交失败放行」伪装成有守卫。"""
        rejected = [
            (NATIVE_ENTRY_WIRED_COMMIT_RESULT_OVERWRITTEN, "提交结果被改写为 true"),
            (NATIVE_ENTRY_WIRED_COMMIT_RESULT_RESET, "提交结果被重置"),
        ]
        value = contract()
        value["nativeWiring"]["wired"] = False
        for text, label in rejected:
            with self.subTest(case=label):
                result = audit.verify_native_wiring(value, {"native_entry": text})
                self.assertFalse(result["实际接线"], label)
                self.assertIn("commit_result_controlled", result["待补证据"], label)

    def test_goto_before_anchor_cannot_bypass_chain(self):
        """锚点**之前**的运行期 `goto` 落到锚点之后，等于整条绕过该环节。

        旧 `jumped_over` 只扫描 `[anchor_at, target_at)` 且要求目标在
        `target_at` 之后，于是「门禁前一条 `if (rev) goto 安装点;`」会把
        整条链路（门禁 / 提交 / 提交守卫 / 安装许可）绕过去却仍翻绿。
        """
        cases = [
            (NATIVE_ENTRY_WIRED_GOTO_BEFORE_GATE_TO_INSTALL, "install_path_present",
             "门禁前的 goto 直达安装点"),
            (NATIVE_ENTRY_WIRED_GOTO_BEFORE_COMMIT_TO_INSTALL, "install_dominated",
             "提交前的 goto 直达安装点"),
            (NATIVE_ENTRY_WIRED_GOTO_BEFORE_COMMIT_GUARD, "commit_result_controlled",
             "提交守卫前的 goto 跳过守卫"),
            (NATIVE_ENTRY_WIRED_GOTO_BEFORE_PERMIT, "install_dominated",
             "安装许可前的 goto 跳过许可"),
        ]
        value = contract()
        value["nativeWiring"]["wired"] = False
        for text, missing, label in cases:
            with self.subTest(case=label):
                result = audit.verify_native_wiring(value, {"native_entry": text})
                self.assertFalse(result["实际接线"], label)
                self.assertIn(missing, result["待补证据"], label)

    def test_polarity_must_hold_under_extra_conditions(self):
        """极性判据要能识破「额外条件削弱」与「双重取反」：
        `!committed && revision > 0` 在提交失败时可能不早退，等于放行。"""
        rejected = [
            (NATIVE_ENTRY_WIRED_GATE_WEAKENED_BY_AND, "门禁被 && 削弱"),
            (NATIVE_ENTRY_WIRED_GATE_DOUBLE_NEGATED, "门禁双重取反"),
            (NATIVE_ENTRY_WIRED_COMMIT_GUARD_WEAKENED_BY_AND, "提交守卫被 && 削弱"),
            (NATIVE_ENTRY_WIRED_COMMIT_GUARD_DOUBLE_NEGATED, "提交守卫双重取反"),
            (NATIVE_ENTRY_WIRED_PERMIT_WEAKENED_BY_AND, "许可判定被 && 削弱"),
            (NATIVE_ENTRY_WIRED_GATE_OTHER_OBJECT_IN_OR, "门禁夹带其他对象的用途校验"),
            (NATIVE_ENTRY_WIRED_PERMIT_OR_EXTRA, "许可判定被额外合取条件削弱"),
        ]
        value = contract()
        value["nativeWiring"]["wired"] = False
        for text, label in rejected:
            with self.subTest(case=label):
                result = audit.verify_native_wiring(value, {"native_entry": text})
                self.assertFalse(result["实际接线"], label)
        accepted = [
            (NATIVE_ENTRY_WIRED_PERMIT_EQUAL_FALSE, "许可写成 == false"),
            (NATIVE_ENTRY_WIRED_COMMIT_GUARD_EQUAL_FALSE, "提交守卫写成 == false"),
            (NATIVE_ENTRY_WIRED_COMMIT_GUARD_AND_TRUE, "提交守卫 && true"),
            (NATIVE_ENTRY_WIRED_GATE_OR_STRONGER, "门禁被 || 加强"),
            (NATIVE_ENTRY_WIRED_INSTALL_PARENTHESISED, "安装点带括号"),
            (NATIVE_ENTRY_WIRED_ABORT_BY_THROW, "早退用 throw"),
            (NATIVE_ENTRY_WIRED_COMMIT_GUARD_PARENTHESISED, "守卫取反带括号"),
        ]
        value = contract()
        value["nativeWiring"]["wired"] = True
        for text, label in accepted:
            with self.subTest(case=label):
                result = audit.verify_native_wiring(value, {"native_entry": text})
                self.assertTrue(result["实际接线"], label)
                self.assertEqual([], result["待补证据"], label)

    def test_constant_evaluator_does_not_compress_values(self):
        """`switch` 标签必须保留真实常量：把 2 压缩成 1 会把不匹配的 case 当成匹配。"""
        self.assertEqual(0, audit.constant_value("0"))
        self.assertEqual(2, audit.constant_value("2"))
        self.assertEqual(1, audit.constant_value("true"))
        self.assertEqual(0, audit.constant_value("NEVER", {"NEVER": 0}))
        self.assertIsNone(audit.constant_value("some_variable"))
        self.assertEqual(1, audit.constant_value("x || true"))
        self.assertEqual(0, audit.constant_value("x && false"))
        self.assertTrue(audit.constant_condition("errors() || true"))
        self.assertTrue(audit.constant_condition("errors() && false"))
        self.assertFalse(audit.constant_condition("!result.committed"))

    def test_contract_cannot_claim_wiring_the_source_lacks(self):
        """合同声明 wired=true、源码也没接线时，必须硬失败。"""
        value = contract()
        value["nativeWiring"]["wired"] = True
        with self.assertRaisesRegex(AuditError, "与实测不一致"):
            audit.verify_native_wiring(value, {"native_entry": NATIVE_ENTRY_UNWIRED})

    def test_comment_only_reference_is_not_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "tools").mkdir()
            (root / "tools/test_decoy.py").write_text(
                '# "persist.sys.hyperextend.disabled"\n# assertNull(BootLoopGuard.readDisabledProperty())\n',
                encoding="utf-8")
            result = audit.scan_escape_references(root)
            self.assertFalse(result["完整"])
            self.assertEqual([], result["证据"])

    def test_single_string_reference_is_not_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            base = root / "app/src/test/java/io"
            base.mkdir(parents=True)
            (base / "DecoyLiteralTest.kt").write_text(EVIDENCE_SINGLE_STRING, encoding="utf-8")
            result = audit.scan_escape_references(root)
            self.assertFalse(result["完整"])

    def test_comment_only_kotlin_reference_is_not_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            base = root / "app/src/test/java/io"
            base.mkdir(parents=True)
            (base / "DecoyCommentTest.kt").write_text(EVIDENCE_COMMENT_ONLY, encoding="utf-8")
            result = audit.scan_escape_references(root)
            self.assertFalse(result["完整"])

    def test_api_call_without_insured_literal_is_not_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            base = root / "app/src/test/java/io"
            base.mkdir(parents=True)
            (base / "DecoyNoLiteralTest.kt").write_text(EVIDENCE_NO_LITERAL, encoding="utf-8")
            result = audit.scan_escape_references(root)
            self.assertFalse(result["完整"])
            self.assertEqual([], result["证据"])


class EscapeReferenceAssertionLevelTest(unittest.TestCase):
    """逃生门证据必须落在同一个测试方法体内：字面量断言与 API 断言不能被拆开。"""

    def test_split_assertions_across_methods_are_not_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            base = root / "app/src/test/java/io"
            base.mkdir(parents=True)
            (base / "DecoySplitTest.kt").write_text(EVIDENCE_SPLIT_ASSERTIONS, encoding="utf-8")
            result = audit.scan_escape_references(root)
            self.assertFalse(result["完整"])
            self.assertEqual([], result["证据"])

    def test_literal_outside_assertion_is_not_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            base = root / "app/src/test/java/io"
            base.mkdir(parents=True)
            (base / "DecoyNonAssertionTest.kt").write_text(
                EVIDENCE_LITERAL_NOT_ASSERTED, encoding="utf-8")
            result = audit.scan_escape_references(root)
            self.assertFalse(result["完整"])
            self.assertEqual([], result["证据"])

    def test_assertion_outside_test_method_is_not_evidence(self):
        """只有显式 `@Test fun` 的方法体才算证据，类初始化块/普通函数里的断言不算。"""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            base = root / "app/src/test/java/io"
            base.mkdir(parents=True)
            (base / "DecoyInitBlockTest.kt").write_text(
                EVIDENCE_OUTSIDE_TEST_METHOD, encoding="utf-8")
            result = audit.scan_escape_references(root)
            self.assertFalse(result["完整"])
            self.assertEqual([], result["证据"])

    def test_python_module_level_assertions_are_not_evidence(self):
        module_level = "assert \"persist.sys.hyperextend.disabled\" == KillSwitch.PROPERTY_NAME\n" \
                       "assert KillSwitch.reportOnce() == True\n"
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "tools").mkdir()
            (root / "tools/test_module_level_escape.py").write_text(module_level, encoding="utf-8")
            result = audit.scan_escape_references(root)
            self.assertFalse(result["完整"])
            self.assertEqual([], result["证据"])

    def test_python_test_body_is_accepted_evidence(self):
        """`tools/test_*.py` 也是合法证据来源，但同样要求方法体内部自洽。"""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "tools").mkdir()
            (root / "tools/test_decoy_escape_gate.py").write_text(PYTHON_EVIDENCE, encoding="utf-8")
            result = audit.scan_escape_references(root)
            self.assertTrue(result["完整"])
            methods = {name for item in result["证据"] for name in item["方法"]["KillSwitch"]}
            self.assertIn("test_kill_switch_property_and_engagement", methods)

    def test_python_split_bodies_are_not_evidence(self):
        split = PYTHON_EVIDENCE.replace(
            "        self.assertEqual(\"persist.sys.hyperextend.disabled\", KillSwitch.PROPERTY_NAME)\n",
            "")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "tools").mkdir()
            (root / "tools/test_split_escape_gate.py").write_text(split, encoding="utf-8")
            result = audit.scan_escape_references(root)
            self.assertFalse(result["完整"])


class HostPointerTest(unittest.TestCase):
    """`HOST`（文件指针表）必须自身可校验，不能只靠常量表指纹。"""

    def test_pristine_copy_reports_the_same_gaps_as_workspace(self):
        jail = jailed_root()
        try:
            result = audit.verify_host_pointers(root=jail.jail_root)
        finally:
            jail.cleanup()
        self.assertEqual(list(audit.HOST_KEYS), list(result))

    def test_readme_pointer_redirected_to_forged_doc_is_rejected(self):
        """审查复现路径：只改基线 HOST['readme']，不动合同、不动工具。"""
        forged = dict(audit.baseline.HOST)
        forged["readme"] = "app/src/test/resources/forged-readme.md"
        with self.assertRaisesRegex(AuditError, r"HOST\[readme\]"):
            audit.verify_host_pointers(type("M", (), {"HOST": forged})())

    def test_host_pointer_change_is_rejected_by_fingerprint(self):
        forged = dict(audit.baseline.HOST)
        forged["readme"] = "app/src/test/resources/forged-readme.md"
        with self.assertRaisesRegex(AuditError, "独立基线常量被改写"):
            audit_sources(baseline_patches={"HOST": forged}, refresh_fingerprint=False)

    def test_host_key_added_or_removed_is_rejected(self):
        truncated = {key: value for key, value in audit.baseline.HOST.items() if key != "readme"}
        with self.assertRaisesRegex(AuditError, "HOST 键集合或顺序被改写"):
            audit.verify_host_pointers(type("M", (), {"HOST": truncated})())
        extended = dict(audit.baseline.HOST)
        extended["extra"] = "README.md"
        with self.assertRaisesRegex(AuditError, "HOST 键集合或顺序被改写"):
            audit.verify_host_pointers(type("M", (), {"HOST": extended})())

    def test_host_absolute_or_escaping_path_is_rejected(self):
        absolute = dict(audit.baseline.HOST)
        absolute["readme"] = "C:/Windows/win.ini"
        with self.assertRaisesRegex(AuditError, "仓库内相对路径"):
            audit.verify_host_pointers(type("M", (), {"HOST": absolute})())
        escaping = dict(audit.baseline.HOST)
        escaping["readme"] = "../../README.md"
        with self.assertRaisesRegex(AuditError, "仓库内相对路径"):
            audit.verify_host_pointers(type("M", (), {"HOST": escaping})())

    def test_host_missing_file_is_rejected(self):
        missing = dict(audit.baseline.HOST)
        missing["readme"] = "app/src/test/resources/no-such-file.md"
        with self.assertRaisesRegex(AuditError, "文件不存在"):
            audit.verify_host_pointers(type("M", (), {"HOST": missing})())

    def test_new_launcher_host_keys_must_exist(self):
        """本轮新增的两个指针也必须真实存在，缺一个就硬失败。"""
        for key in ("launcher_identity", "launcher_native_scope"):
            missing = dict(audit.baseline.HOST)
            missing[key] = "app/src/launcherNative/resources/META-INF/xposed/no-such.list"
            with self.assertRaisesRegex(AuditError, "文件不存在"):
                audit.verify_host_pointers(type("M", (), {"HOST": missing})())

    def test_fingerprint_out_of_sync_is_rejected(self):
        """基线更新但工具内嵌指纹未同步时必须硬失败（本轮重算指纹的守卫）。"""
        with self.assertRaisesRegex(AuditError, "独立基线常量被改写"):
            audit_sources(baseline_patches={"SCOPE": dict(audit.baseline.SCOPE,
                                                          scope_list_entries=["com.other.home"])},
                          refresh_fingerprint=False)

    def test_sources_alias_must_point_at_baseline_host(self):
        """`SOURCES` 是活别名；若被换成别的映射，必须硬失败。"""
        original = audit.SOURCES
        try:
            audit.SOURCES = dict(original)
            with self.assertRaisesRegex(AuditError, "被审计文件指针表"):
                audit.verify_contract(contract())
        finally:
            audit.SOURCES = original


    def test_forged_readme_redirect_is_rejected_end_to_end(self):
        """审查复现路径端到端版：只把 HOST['readme'] 指向伪造文档（不改指纹）。"""
        forged = "app/src/test/resources/forged-readme.md"
        jail = jailed_root(decoys={forged: README_COVERED})
        original_host = audit.baseline.HOST
        redirected = dict(original_host)
        redirected["readme"] = forged
        try:
            audit.baseline.HOST = redirected
            with self.assertRaisesRegex(AuditError, "独立基线常量被改写"):
                audit.verify_parity_contract(
                    contract(), root=jail.jail_root,
                    compiler=lambda text: {"编译器": "合成",
                                           "断言数": audit.count_matches(r"static_assert\(", text)})
        finally:
            audit.baseline.HOST = original_host
            jail.cleanup()



class RealRepoShapeJailTest(unittest.TestCase):
    """在真实源码副本上验证「文本装饰翻绿」，工作区不受影响。"""

    def test_pristine_copy_reports_the_same_gaps_as_workspace(self):
        jail = jailed_root()
        try:
            scope_path = jail.jail_root / audit.baseline.HOST["launcher_native_scope"]
            self.assertEqual(["com.miui.home", "system"],
                             scope_path.read_text(encoding="utf-8").splitlines())
            result = audit_jail(jail)
        finally:
            jail.cleanup()
        self.assertTrue(result["审计器已成功运行"])
        self.assertTrue(result["防砖已确认"])
        self.assertEqual([1, 3, 5], result["未关闭门槛"])
        # 真实副本保留完整接线与逃生门证据，静态确认不关闭安装门槛。
        self.assertNotIn("逃生门测试引用", result["缺口"])
        self.assertNotIn("原生入口安全门禁接线", result["缺口"])
        self.assertNotIn("README 逃生属性覆盖", result["缺口"])
        self.assertNotIn("作用域令牌与注入作用域一致", result["缺口"])
        self.assertEqual([], result["缺口"])
        self.assertEqual([], result["未确认"])
        self.assertFalse(result["安装许可"])

    def test_comment_decoy_cannot_flip_escape_gate_reference(self):
        decoy = ("app/src/test/java/io/github/YGHFv/HyperExtend/hook/DecoySafetyTest.kt",
                 EVIDENCE_COMMENT_ONLY)
        jail = jailed_root(decoys={decoy[0]: decoy[1]}, include_escape_evidence=False)
        try:
            result = audit_jail(jail)
        finally:
            jail.cleanup()
        self.assertIn("逃生门测试引用", result["缺口"])
        self.assertFalse(result["防砖已确认"])

    def test_literal_only_decoy_cannot_flip_escape_gate_reference(self):
        decoy = ("app/src/test/java/io/github/YGHFv/HyperExtend/hook/DecoyLiteralTest.kt",
                 EVIDENCE_SINGLE_STRING)
        jail = jailed_root(decoys={decoy[0]: decoy[1]}, include_escape_evidence=False)
        try:
            result = audit_jail(jail)
        finally:
            jail.cleanup()
        self.assertIn("逃生门测试引用", result["缺口"])
        self.assertFalse(result["防砖已确认"])

    def test_notest_literal_in_readme_cannot_flip_coverage(self):
        """属性名只出现在「防砖/逃生门」章节之外时，仍必须报缺口。"""
        base = (audit.ROOT / "README.md").read_text(encoding="utf-8")
        # 整段移除本轮补写的框架保护恢复步骤，还原成「只覆盖逃生门属性」的形状，
        # 再把框架属性名塞到文档末尾（章节外），证明章节外出现属性名不能顶替恢复步骤。
        stripped = re.sub(r"\n框架启动保护是\*\*另一条独立的持久标记\*\*.*?对应一侧就不装 Hook。\n",
                          "\n", base, flags=re.S)
        jail = jailed_root(overrides={
            "README.md": stripped + "\n\npersist.sys.hyperextend.framework_disabled\n"})
        try:
            result = audit_jail(jail)
        finally:
            jail.cleanup()
        item = [entry for entry in result["检查项"] if entry["项"] == "README 逃生属性覆盖"][0]
        self.assertEqual("缺口", item["状态"])
        self.assertIn("persist.sys.hyperextend.framework_disabled", item["证据"]["未覆盖"])

    def test_real_escape_gate_test_is_recognized_as_complete_evidence(self):
        """真实仓库新增的 EscapeGateTest.kt 必须被审计识别为完整逃生门证据。"""
        source = (audit.ROOT / "app/src/test/java/io/github/YGHFv/HyperExtend/hook/EscapeGateTest.kt")
        relative = "app/src/test/java/io/github/YGHFv/HyperExtend/hook/EscapeGateTest.kt"
        jail = jailed_root(decoys={relative: source.read_text(encoding="utf-8")})
        try:
            result = audit_jail(jail)
        finally:
            jail.cleanup()
        item = [entry for entry in result["检查项"] if entry["项"] == "逃生门测试引用"][0]
        self.assertEqual("已核验通过", item["状态"])
        self.assertNotIn("逃生门测试引用", result["缺口"])

    def test_real_escape_gate_test_is_only_remaining_kotlin_gap_closure(self):
        """把新增单测移出扫描范围，逃生门引用缺口必须如实回来。"""
        jail = jailed_root(include_escape_evidence=False)
        try:
            result = audit_jail(jail)
        finally:
            jail.cleanup()
        self.assertIn("逃生门测试引用", result["缺口"])

    def test_real_repo_with_complete_wiring_can_be_confirmed(self):
        """真实入口已具备完整静态接线，无需替换源码或基线即可离线确认。"""
        jail = jailed_root()
        try:
            result = audit_jail(jail)
        finally:
            jail.cleanup()
        self.assertTrue(result["审计器已成功运行"])
        self.assertEqual([], result["缺口"])
        self.assertEqual([], result["未确认"])
        self.assertTrue(result["防砖已确认"])
        self.assertFalse(result["安装许可"])

    def test_include_only_decoy_cannot_flip_native_wiring(self):
        entry = '#include "launcher/install_permit.h"\n' + NATIVE_ENTRY_UNWIRED
        jail = jailed_root(overrides={audit.baseline.HOST["native_entry"]: entry})
        try:
            result = audit.verify_native_wiring(
                contract(), audit.load_sources(jail.jail_root), enforce_contract=False)
            with self.assertRaisesRegex(AuditError, "结构接线状态与源码实测不一致"):
                audit_jail(jail)
        finally:
            jail.cleanup()
        self.assertIn("install_plan_gated", result["待补证据"])
        self.assertFalse(result["实际接线"])


class StructuralWiringTest(unittest.TestCase):
    def test_real_contract_records_structure_wired(self):
        value = contract()
        self.assertIs(value["nativeWiring"]["structuralWiring"]["structureWired"], True)
        self.assertIs(value["nativeWiring"]["wired"], True)
        self.assertIs(value["installPermitted"], False)
        self.assertEqual([1, 3, 5], value["notClosedGates"])
        self.assertIs(value["nativeWiring"]["structuralWiring"]["experimentalInstallSwitch"], True)
        # 生产入口八项静态链路已成立；普通构建仍由实验安装开关保持关闭。
        entry = (audit.ROOT / audit.baseline.HOST["native_entry"]).read_text(encoding="utf-8")
        wiring = audit.verify_native_wiring(value, {"native_entry": entry})
        self.assertEqual({
            "entry_reachable": True,
            "snapshot_gated": True,
            "commit_result_controlled": True,
            "install_dominated": True,
            "install_plan_gated": True,
            "failure_blocks_install": True,
            "install_path_present": True,
            "install_path_exclusive": True,
        }, wiring["完整链路"])
        self.assertIs(wiring["实际接线"], True)
        self.assertEqual([], wiring["待补证据"])
        self.assertEqual(1, wiring["直接安装点"])
        self.assertEqual(0, wiring["别名安装点"])

    def test_contract_structural_wiring_matches_baseline(self):
        struct = contract()["nativeWiring"]["structuralWiring"]
        mapping = {
            "structureWired": "structure_wired",
            "feasibilityVerified": "feasibility_verified",
            "experimentalInstallSwitch": "experimental_install_switch",
            "feasibilityExports": "feasibility_exports",
            "feasibilityImports": "feasibility_imports",
            "readonlyImports": "readonly_imports",
            "newImports": "new_imports",
            "newWhitelistEntries": "new_whitelist_entries",
        }
        for contract_key, baseline_key in mapping.items():
            with self.subTest(field=contract_key):
                self.assertEqual(audit.baseline.STRUCTURAL_WIRING_BASELINE[baseline_key],
                                 struct[contract_key])

    def test_structural_wiring_true_but_source_unwired_is_rejected(self):
        value = contract()
        value["nativeWiring"]["structuralWiring"]["structureWired"] = True
        value["nativeWiring"]["wired"] = True
        native_baseline = dict(audit.baseline.NATIVE_WIRING_BASELINE, wired=True)
        structural_baseline = dict(audit.baseline.STRUCTURAL_WIRING_BASELINE,
                                   structure_wired=True)
        with self.assertRaisesRegex(AuditError, "结构接线状态与源码实测不一致|与实测不一致"):
            audit_sources(texts=dict(sources(), native_entry=NATIVE_ENTRY_UNWIRED),
                          contract_value=value,
                          baseline_patches={
                              "NATIVE_WIRING_BASELINE": native_baseline,
                              "STRUCTURAL_WIRING_BASELINE": structural_baseline,
                          })

    def test_experimental_install_switch_is_explicit(self):
        result = audit.verify_parity_contract(contract(), root=audit.ROOT,
                                              probe_enabled=False)
        self.assertIs(result["安装许可"], False)
        self.assertEqual([1, 3, 5], result["未关闭门槛"])
        self.assertIs(result["防砖已确认"], False)
        self.assertEqual([], result["缺口"])
        self.assertEqual(["原生判定编译期复算"], result["未确认"])
        self.assertIs(contract()["nativeWiring"]["structuralWiring"]["experimentalInstallSwitch"], True)

    def test_structural_evidence_does_not_close_gate(self):
        result = audit.verify_parity_contract(contract(), root=audit.ROOT,
                                              probe_enabled=False)
        item = next(entry for entry in result["检查项"]
                    if entry["项"] == "原生结构接线可行性（离线实测，不等于已放行）")
        self.assertEqual("已核验通过", item["状态"])
        self.assertNotIn("原生入口安全门禁接线", result["缺口"])
        self.assertIs(result["防砖已确认"], False)
        self.assertEqual(["__errno", "__write_chk", "flock", "fstatat", "fsync",
                          "openat", "renameat"], item["证据"]["新增导入"])

class ContractTamperTest(unittest.TestCase):
    def test_scope_list_shape_or_order_is_rejected(self):
        for entries in (["system", "com.miui.home"],
                        ["com.miui.home"], ["system"],
                        ["com.miui.home", "system", "com.other.home"],
                        ["com.miui.home", "", "system"],
                        "com.miui.home\nsystem\n", None):
            with self.subTest(scope_list=entries):
                value = contract()
                value["scope"]["scopeList"] = entries
                with self.assertRaisesRegex(AuditError, "合同与独立基线不符"):
                    audit_sources(contract_value=value)

    def test_install_permitted_true_is_rejected(self):
        value = contract()
        value["installPermitted"] = True
        with self.assertRaisesRegex(AuditError, "不得声明安装许可"):
            audit_sources(contract_value=value)

    def test_not_closed_gates_tamper_is_rejected(self):
        value = contract()
        value["notClosedGates"] = [3, 5]
        with self.assertRaisesRegex(AuditError, "门槛 1、3、5"):
            audit_sources(contract_value=value)

    def test_toolchain_flags_tamper_is_rejected(self):
        value = contract()
        value["toolchain"]["flags"] = ["-O0"]
        with self.assertRaisesRegex(AuditError, "工具链参数"):
            audit_sources(contract_value=value)

    def test_missing_section_is_rejected(self):
        value = contract()
        del value["escapeGates"]
        with self.assertRaisesRegex(AuditError, "缺少必备小节"):
            audit_sources(contract_value=value)

    def test_case_table_tamper_is_rejected_by_digest(self):
        value = contract()
        value["cases"][0]["blocker"] = "kSafetyImaginary"
        with self.assertRaisesRegex(AuditError, "独立基线"):
            audit_sources(contract_value=value)

    def test_undefined_blocker_in_case_is_rejected(self):
        value = contract()
        value["cases"][0]["blocker"] = "kSafetyImaginary"
        # 先让用例表摘要自洽，确保命中的是「阻断裂举」这条守卫而非摘要。
        with self.assertRaisesRegex(AuditError, "未定义阻断位"):
            audit_sources(contract_value=value,
                          baseline_patches={"CASE_TABLES_SHA256": case_tables_digest(value)})

    def test_kotlin_test_method_digest_cannot_be_redefined(self):
        value = contract()
        value["kotlinTestMethods"] = value["kotlinTestMethods"][:1]
        with self.assertRaisesRegex(AuditError, "独立基线"):
            audit_sources(contract_value=value)

    def test_empty_kotlin_test_body_is_rejected(self):
        texts = sources(boot_fuse_test=kotlin_fuse_test(body=""))
        with self.assertRaisesRegex(AuditError, "用例缺失或不唯一"):
            audit_sources(texts)

    def test_missing_kotlin_case_is_rejected(self):
        texts = sources(boot_fuse_test="    @Test fun other() { assertTrue(true) }")
        with self.assertRaisesRegex(AuditError, "用例缺失或不唯一"):
            audit_sources(texts)

    def test_experimental_install_switch_tamper_is_rejected(self):
        texts = sources(install_permit=NATIVE_INSTALL_PERMIT.replace(
            "constexpr bool kExperimentalInstallEnabled = false;",
            "constexpr bool kExperimentalInstallEnabled = true;"))
        with self.assertRaisesRegex(AuditError, "实验安装开关宏或常量分支缺失"):
            audit_sources(texts)


class ProbeTest(unittest.TestCase):
    def test_missing_clang_is_reported_as_unconfirmed(self):
        result = audit_sources(probe_enabled=False, use_compiler=False)
        self.assertFalse(result["防砖已确认"])
        self.assertIn("原生判定编译期复算", result["未确认"])

    def test_probe_failure_maps_to_expected_token(self):
        def failing(text):
            raise AuditError("原生编译期复算失败，命中断言：CASE_third_restart_trips")
        with self.assertRaisesRegex(AuditError, "CASE_third_restart_trips"):
            audit_sources(compiler=failing)

    def test_real_clang_if_present_reports_assertion_count(self):
        clang = audit.find_clang(None)
        if clang is None:
            # 无工具链时只允许「未确认」，绝不允许把缺工具链当成通过。
            result = audit_sources(probe_enabled=False, use_compiler=False)
            self.assertIn("原生判定编译期复算", result["未确认"])
            return
        result = audit_sources(compiler=lambda text: audit.run_probe(clang, text))
        item = [entry for entry in result["检查项"] if entry["项"] == "原生判定编译期复算"][0]
        self.assertEqual("已核验通过", item["状态"])
        self.assertGreater(item["证据"]["断言数"], 0)

    def test_real_clang_rejects_unsatisfied_assertion(self):
        clang = audit.find_clang(None)
        if clang is None:
            self.assertIsNone(audit.find_clang(None))
            return
        broken = audit.native_probe_source(contract(), audit.native_blocker_names(
            sources())).replace("== 300000", "== 1")
        with self.assertRaisesRegex(AuditError, "命中断言"):
            audit.run_probe(clang, broken)


class CliTest(unittest.TestCase):
    def test_cli_outputs_json_and_does_not_leak_temporary_path(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "contract.json"
            path.write_text(json.dumps(contract(), ensure_ascii=False), encoding="utf-8")
            with patch("sys.stdout", new_callable=io.StringIO) as output:
                status = audit.main(["--contract", str(path), "--no-toolchain"])
            text = output.getvalue()
            self.assertEqual(0, status)
            self.assertNotIn(folder, text)
            result = json.loads(text)
            self.assertFalse(result["安装许可"])
            self.assertFalse(result["防砖已确认"])
            self.assertEqual([1, 3, 5], result["未关闭门槛"])
            self.assertEqual([], result["缺口"])
            self.assertEqual(["原生判定编译期复算"], result["未确认"])

    def test_cli_missing_contract_returns_failure_without_leaking_path(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "missing.json"
            with patch("sys.stdout", new_callable=io.StringIO) as output:
                status = audit.main(["--contract", str(path)])
            text = output.getvalue()
            self.assertEqual(1, status)
            self.assertNotIn(folder, text)

    def test_no_toolchain_flag_marks_probe_unconfirmed(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "contract.json"
            path.write_text(json.dumps(contract(), ensure_ascii=False), encoding="utf-8")
            with patch("sys.stdout", new_callable=io.StringIO) as output:
                status = audit.main(["--contract", str(path), "--no-toolchain"])
            self.assertEqual(0, status)
            result = json.loads(output.getvalue())
            self.assertEqual(["原生判定编译期复算"], result["未确认"])
            self.assertEqual([], result["缺口"])
            self.assertFalse(result["防砖已确认"])

    def test_cli_tampered_contract_returns_failure(self):
        with tempfile.TemporaryDirectory() as folder:
            value = contract()
            value["installPermitted"] = True
            path = Path(folder) / "contract.json"
            path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
            with patch("sys.stdout", new_callable=io.StringIO) as output:
                status = audit.main(["--contract", str(path), "--no-toolchain"])
            self.assertEqual(1, status)
            self.assertNotIn(folder, output.getvalue())


if __name__ == "__main__":
    unittest.main()
