#!/usr/bin/env python3
"""防砖一致性（Kotlin ↔ 原生）的离线只读审计。

本工具起因是早期「防砖」链路缺少跨侧一致性证据，目前逐项核对以下内容：

  * `app/src/main/cpp/launcher/safety_gate.h` 自称「与 Kotlin `BootFuseState` 同构」
    「与 Kotlin `BootFusePolicy.next` 同构」；两侧常量、字段顺序与阈值必须
    同时符合合同与独立基线，并通过真实编译期复算；
  * `hook/KillSwitch.kt` 与 `hook/BootLoopGuard.kt` 决定「进不去系统时能不能自救」，
    其测试引用必须同时包含属性字面量断言与真实 API 断言；
  * README 的逃生门恢复说明必须覆盖 `KillSwitch` 与 `BootLoopGuard` 两条属性；
  * `launcher/safety_startup.cpp` 已链入原型，`launcher/native_entry.cpp` 已可达地
    调用启动状态提交；结构接线与八项链路已由源码实测成立，安装计划非空校验
    支配唯一安装点，计划不完整即 fail-closed，不得用占位实参伪造安装调用。

本工具把上述四项变成可失败的只读断言，并明确区分两件事：

  * **审计器成功运行**：解析、比对、编译期复算全部按预期完成，结论可信；
  * **防砖已确认**：只有在「接线到位、逃生门有测试、文档覆盖齐全、两侧一致」
    全部成立才可能确认。完整链路验证器已实现：它从导出入口做可达性搜索，
    逐项核对快照门禁、提交控制流、安装支配、计划非空校验与失败阻断。
    当前 wired=true、structureWired=true；计划三段来源已接线：loader 的宿主映像解析、
    链入 SO 的蹦床入口和后端回填槽，均经过计划非空校验；loader 不能提供唯一合格
    映像时会 fail-closed。普通构建由显式实验安装开关保持关闭，安装许可=false，
    门槛 1/3/5 不关闭；防砖已确认=true 只是离线静态审计结论，不等于实机放行。

只读边界：不接触设备、不安装、不写仓库、不改任何生产安全行为；`installPermitted`
恒为 false，门槛 1/3/5 不销项。原生侧只用 NDK clang 做 `-fsyntax-only` 的编译期
常量求值（临时目录），不链接、不运行。

判定分三档，全部 fail-closed：

  * **硬失败**（退出码 1）：合同被改写、源码常量/字段/阈值漂移、两侧互相矛盾、
    合同声明的接线状态与源码不符、解析缺失或不唯一。一律报错，绝不静默放行。
  * **缺口**：当前仓库确实缺失的东西（未接线、无逃生门测试、README 覆盖不足）。
    如实报告，不掩盖，也不假装通过。
  * **未确认**：本工具能力之外的部分（例如 Kotlin 侧行为不在这里执行、缺少 NDK
    工具链时的编译期复算）。只标未确认，不标通过。

「防砖已确认」的每一条都必须绑定到真实证据，不能靠文本装饰翻绿：

  * 逃生门证据要求**同一个测试方法体**里同时出现「断言中的被投保属性字面量」与
    「对真实 API 的带期望值断言」；注释、只 include、单个字符串、属性名出现在
    非断言位置、以及两条证据分散在不同方法体/不同文件里都不算。扫描范围包含
    `tools/test_*.py`，不只是 Kotlin 测试目录；
  * 原生接线由完整链路验证器核对，逐项要求：提交后端调用、快照门禁、安装许可早退与安装调用四处位置都从导出入口可达且**自身可达**（不在恒假分支、无条件跳转后或未被调用的 lambda 内）。
    可达性不是「看见 `if (0)` 才算死」：验证器做三值常量求值 + 语句级可达性分析，
    `if constexpr (false)`、`#define NEVER 0` 后写 `if (NEVER)`、`for (; false;)`、
    `switch (1) { case 2: ... }`、`if (true) {...} else {...}` 的 else 分支、
    无 `break` 的 `while (true) {}` 之后、嵌套块 `return` 之后、可达 `goto` 与其
    目标标签之间，都被判为执行不到；导出入口自身只在死分支里调用整条链路同样不算接线。
    `do { ... } while (false)` 只是把语句包成块（块体必然执行一次），仍算可达。
    快照**用途**校验（`ordinary_snapshot_errors`）在提交之前，校验的是**同一个
    快照对象**、条件是「非零（出错）就早退」的正确极性、且不在恒定条件下；
    提交结果进入早退控制流且失败在**真分支**里返回；安装许可证据来自提交结果；
    安装许可判定本身进入真分支早退；同一计划的非空校验在许可之后支配安装点，
    校验失败必须早退；安装调用严格在其后、不在恒假分支/跳转之后、
    也不落在许可判定那条语句内部；真正调用已保存的 hook 指针；全文仅一处 hook
    调用点（含别名）。孤立函数、字符串诱饵、只 include、不进控制流的调用、
    丢弃返回值的门禁、`if (0)`/`while (0)`/`goto` 后的安装调用、上述各类恒假分支、
    `|| true` 或 `&& false` 这类恒定条件短路、条件极性反转、空真分支或
    `else` 续行、先取别名（含 `auto&` 与 typedef）再调用，以及自填 `true`
    冒充安全证据都不算证据；
  * README 覆盖要求恢复步骤落在「防砖 → 逃生门」小节内、且在对应属性名近邻；
  * 独立基线的**常量表与文件指针（`HOST`）都**纳入审计工具内嵌指纹：只改合同、
    只改基线（含把 `HOST` 指向别的文件）、或合同+源码+基线三者同改都会硬失败。
    但内嵌指纹是本仓库里的明文常量，属于「一致性绊线」而非防篡改：能改基线的人
    也能顺手改掉这个指纹，它不能当作不可伪造证据。

已知限制（如实记录，不由猜测补齐）：

  * 条件编译只删除能静态判定的 `#if 0` / `#if false` 分支。`#ifdef X` / `#ifndef X`
    按「可能编译」处理：本工具不读构建系统的宏定义集，因此把 `hook_func` 调用写在
    未定义宏的 `#ifdef` 里仍会被算作一处可达安装点。要收紧这一点，应先让审计器
    解析构建系统的宏定义集，而不是在这里猜。
  * 常量求值是表达式级的近似（整数、`true`/`false`、`#define 名字 数字`、`!`、
    比较、`&&`/`||`）。无法求值的条件一律按「可达」处理；这只是不漏放原则的
    保守一侧，不等于证明该分支真的会执行。
  * 支配与必然执行是**语句级**近似（`dominates` / `unconditionally_reached`）：
    不建模异常、并发、别名副作用与多线程时序，因此只回答「源码里有没有一条
    形态、顺序、极性都成立的接线」。
  * 早退判定（`live_abort_in_consequent`）要求 `return`/`throw` 直接成立，
    `break` 只有在同一层循环/`switch` 里同时包含安装点时才成立，`goto` 的标签
    必须在安装点之后，且真分支必须必然终止。
  * 守卫极性用符号化蕴含判定（`condition_implies_unsafe`）做真值表穷举：未知
    原子超过 8 个或表达式无法切分 token 时返回「不成立」，按保守拒绝处理——
    宁可把陌生写法误判成未接线，也不把被削弱的守卫判成已接线。
  * 提交结果被改写只在**词法**层面识别（成员赋值、整体赋值、`reset`/`clear`/
    `assign`）：变量若被换绑到别的对象不再追踪。
"""

import argparse
import ast
import glob
import json
import os
import itertools
import re
import shutil
import subprocess
import sys
import tempfile
import hashlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from launcher_native_audit import AuditError, require  # noqa: E402
import launcher_safety_parity_baseline as baseline  # noqa: E402


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CONTRACT = ROOT / "app/src/test/resources/launcher-safety-parity-contract.json"
POLICY = baseline.POLICY
SOURCES = baseline.HOST
# 合同与独立基线都在仓库里、都能被改写；为了不让「合同 + 源码 + 基线」三者同改就
# 静默翻绿，这里再钉一层审计工具自带的指纹：只有同时修改本文件才算真正改断言。
# `HOST` 必须一并纳入：否则只改基线里的文件指针（把被审计对象换成伪造文件）就能
# 不改合同、不改本文件地把单项检查翻绿。
BASELINE_KEYS = ("POLICY", "HOST", "FUSE", "TRIP_ORDER", "BODY_DIGESTS", "ESCAPE_GATES", "SCOPE",
                 "NATIVE_WIRING_BASELINE", "STRUCTURAL_WIRING_BASELINE",
                 "README_SECTION_HEADINGS", "README_REQUIRED_STEPS",
                 "KOTLIN_TEST_METHODS", "CASE_TABLES_SHA256")
BASELINE_FINGERPRINT = "14c326d0af33e311b77e5129dafbc0917d1d785e5b1f43ef749929aa88028175"
# `HOST` 的键名集合与顺序都被固定：增删键或改指针都会改变基线指纹。
HOST_KEYS = ("boot_fuse", "boot_fuse_test", "kill_switch", "boot_loop_guard", "scope_catalog",
             "launcher_identity", "launcher_native_scope",
             "safety_gate", "install_permit", "native_entry", "readme")
PROBE_TARGET = "aarch64-linux-android29"
PROBE_FLAGS = ("-std=c++17", "-fsyntax-only", "-Wall", "-Wextra", "-Werror", "-mno-outline-atomics")
# 逃生门引用只认「测试文件」：合同 JSON 是数据、主源码是实现，都不算对逃生门的验证。
REFERENCE_ROOTS = ("app/src/test", "app/src/launcherNativeTest")
REFERENCE_TOOL_GLOB = "tools/test_*.py"
REFERENCE_EXCLUDE = "safety_parity"
PROBE_TOKEN = re.compile(r"\b(?:SAFETY_PARITY|CASE|DECODE)_[A-Za-z0-9_]+")


# ---------------------------------------------------------------- 基线指纹

def baseline_payload(module=None):
    module = module or baseline
    return {key: getattr(module, key) for key in BASELINE_KEYS}


def baseline_fingerprint(module=None):
    """独立基线常量表的稳定摘要；改动常量表但不改本文件指纹即视为篡改。"""
    encoded = json.dumps(baseline_payload(module), ensure_ascii=False, sort_keys=True,
                         separators=(",", ":")).encode()
    return hashlib.sha256(encoded).hexdigest()


def verify_host_pointers(module=None, root=ROOT):
    """`HOST` 决定被审计的对象是哪些文件，必须自身可校验。

    只把 `HOST` 纳入指纹还不够：还要拒绝「键集合被增删」「值不是仓库内相对路径」
    「路径逃出仓库根」「指向不存在的文件」这几种形态，避免审计器静默换掉被审计对象。
    """
    module = module or baseline
    host = getattr(module, "HOST")
    require(isinstance(host, dict), "独立基线 HOST 不是映射")
    require(tuple(host) == HOST_KEYS,
            "独立基线 HOST 键集合或顺序被改写：期望 %s，实际 %s" % (list(HOST_KEYS), list(host)))
    base = Path(root).resolve()
    # 真实仓库根下必须逐项确认文件存在；受控实验（临时仓库副本/合成源码）只用相对路径
    # 与越界检查，避免把「未落盘到临时目录」误判成篡改。
    enforce_exists = base == ROOT
    for name, value in host.items():
        require(isinstance(value, str) and value.strip() != "",
                "独立基线 HOST[%s] 必须是非空字符串路径" % name)
        candidate = Path(value)
        # Windows 盘符前缀（`C:`）与 UNC/根斜杠在非 Windows 断言机上不一定会被
        # `Path.is_absolute()` 判为绝对路径，这里显式拒绝，保证跨平台口径一致。
        drive_style = re.match(r"^[A-Za-z]:", value) is not None or value.startswith(("\\\\", "/"))
        require(not candidate.is_absolute() and not drive_style and ".." not in candidate.parts,
                "独立基线 HOST[%s] 必须是仓库内相对路径，不得为绝对路径或含 ..：%r" % (name, value))
        try:
            resolved = (base / candidate).resolve()
        except OSError:
            raise AuditError("独立基线 HOST[%s] 路径无法解析" % name)
        require(base == resolved or base in resolved.parents,
                "独立基线 HOST[%s] 逃出仓库根：%r" % (name, value))
        require(not enforce_exists or resolved.is_file(),
                "独立基线 HOST[%s] 指向的文件不存在：%r" % (name, value))
    return {name: host[name] for name in HOST_KEYS}


def verify_baseline_integrity(module=None, root=ROOT):
    actual = baseline_fingerprint(module)
    require(actual == BASELINE_FINGERPRINT,
            "独立基线常量被改写（指纹不符）：需要同时复核审计工具与合同，不能只改数据翻绿")
    verify_host_pointers(module, root)
    return actual


# ---------------------------------------------------------------- 文本解析

def strip_comments(text):
    """去掉类 C 注释（含 Kotlin/Java），保留字符串字面量内容。

    不能直接按 `//` 切：`"persist.sys.hyperextend.disabled"` 这类字面量里可能出现
    单个斜杠，而后面还要精确抽取字符串。这里按引号状态逐字符扫描。
    """
    out = []
    index = 0
    size = len(text)
    while index < size:
        char = text[index]
        if char == "/" and index + 1 < size and text[index + 1] == "/":
            while index < size and text[index] != "\n":
                index += 1
            continue
        if char == "/" and index + 1 < size and text[index + 1] == "*":
            index += 2
            while index + 1 < size and not (text[index] == "*" and text[index + 1] == "/"):
                index += 1
            index = min(index + 2, size)
            continue
        if char in "\"'":
            quote = char
            out.append(char)
            index += 1
            while index < size:
                if text[index] == "\\" and index + 1 < size:
                    out.append(text[index])
                    out.append(text[index + 1])
                    index += 2
                    continue
                out.append(text[index])
                if text[index] == quote:
                    index += 1
                    break
                if text[index] == "\n":
                    break
                index += 1
            continue
        out.append(char)
        index += 1
    return "".join(out)


def single_match(pattern, text, label, flags=0):
    """要求恰好命中一次；0 次或多次都视为解析失败，不做「取第一个」的宽松猜测。"""
    found = re.findall(pattern, text, flags)
    require(len(found) == 1, "「%s」在源码中出现 %d 次（要求恰好 1 次）" % (label, len(found)))
    return found[0]


def count_matches(pattern, text):
    return len(re.findall(pattern, text))


def eval_int_literal(text, label):
    """只接受整数常量表达式（十进制、十六进制、`_` 与 `'` 分隔符、L/U 后缀）。"""
    normalized = text.strip()
    normalized = re.sub(r"(?<=[0-9a-fA-F])[uUlL]+\s*$", "", normalized)
    normalized = normalized.replace("_", "").replace("'", "")
    require(re.fullmatch(r"[0-9a-fA-FxX+\-*/() ]+", normalized) is not None,
            "「%s」不是可静态求值的整数常量：%r" % (label, text.strip()))
    try:
        tree = ast.parse(normalized, mode="eval")
    except SyntaxError as error:
        raise AuditError("「%s」整数常量语法错误" % label) from error

    def walk(node):
        if isinstance(node, ast.Expression):
            return walk(node.body)
        if isinstance(node, ast.Constant) and isinstance(node.value, int):
            return node.value
        if isinstance(node, ast.BinOp) and isinstance(node.op, (ast.Add, ast.Sub, ast.Mult)):
            left, right = walk(node.left), walk(node.right)
            return {ast.Add: lambda: left + right, ast.Sub: lambda: left - right,
                    ast.Mult: lambda: left * right}[type(node.op)]()
        if isinstance(node, ast.UnaryOp) and isinstance(node.op, ast.USub):
            return -walk(node.operand)
        raise AuditError("「%s」常量表达式含不允许的运算" % label)

    return walk(tree)


def block_at(text, index, label):
    """从 index 起找第一个 `{` 并返回配平的花括号块（含花括号）。

    按引号状态跳过字符串字面量与注释，避免把字符串里的花括号算进深度。
    这里只处理 `"`（本仓库 Kotlin 侧没有以 `'` 分隔的字符字面量出现在判定函数里）。
    """
    start = text.find("{", index)
    require(start >= 0, "「%s」缺少函数体" % label)
    depth = 0
    cursor = start
    while cursor < len(text):
        char = text[cursor]
        if char == "/" and cursor + 1 < len(text) and text[cursor + 1] == "/":
            while cursor < len(text) and text[cursor] != "\n":
                cursor += 1
            continue
        if char == "/" and cursor + 1 < len(text) and text[cursor + 1] == "*":
            cursor += 2
            while cursor + 1 < len(text) and not (text[cursor] == "*" and text[cursor + 1] == "/"):
                cursor += 1
            cursor = min(cursor + 2, len(text))
            continue
        if char == '"':
            cursor += 1
            while cursor < len(text) and text[cursor] != '"':
                cursor += 2 if text[cursor] == "\\" else 1
            cursor += 1
            continue
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return text[start:cursor + 1]
        cursor += 1
    raise AuditError("「%s」函数体括号不配平" % label)


def kotlin_try_catch_body(source, signature, label):
    """签名以 `= try` 结尾，返回紧随其后的 `catch (...) { ... }` 块。

    必须绑定到该函数自己的 `try` 与 `catch`：按「函数起点 + 固定长度」取窗口会把
    后面另一个函数的 catch 一起圈进来，那属于宽松匹配。
    """
    require(count_matches(re.escape(signature), source) == 1, "「%s」缺失或不唯一" % label)
    region = source[source.find(signature) + len(signature):]
    require(region.lstrip().startswith("{"), "「%s」的 `= try` 之后不是函数体" % label)
    try_block = block_at(region, 0, label)
    rest = region[region.find(try_block) + len(try_block):]
    head = rest.lstrip()
    require(head.startswith("catch"), "「%s」的 try 之后没有紧跟 catch" % label)
    return block_at(rest, rest.find("catch"), label)


def kotlin_string_constant(source, name, label):
    return single_match(r"\bconst\s+val\s+" + re.escape(name) + r"\s*=\s*\"([^\"]*)\"",
                        source, label)


def kotlin_long_constant(source, name, label):
    raw = single_match(r"\bconst\s+val\s+" + re.escape(name) + r"\s*=\s*([^\n]+?)\s*(?:\n|\Z)",
                       source, label)
    return eval_int_literal(raw, label)


def kotlin_string_list(source, name, label):
    body = single_match(r"\bval\s+" + re.escape(name) + r"\s*=\s*listOf\(([^)]*)\)",
                        source, label, re.S)
    values = re.findall(r'"([^"]*)"', body)
    require(values, "「%s」列表为空或不是字面量列表" % label)
    return values


def kotlin_catch_result(source, signature, label):
    """取出函数里 catch 兜底分支的最后一个表达式（`true`/`null`/`false`）。"""
    block = kotlin_try_catch_body(source, signature, label)
    lines = [line.strip() for line in block[1:-1].splitlines() if line.strip()]
    require(lines, "「%s」的 catch 分支为空" % label)
    return lines[-1]


# ---------------------------------------------------------------- 合同与源码装载

def load_sources(root=ROOT):
    return {key: (root / path).read_text(encoding="utf-8") for key, path in SOURCES.items()}


def kotlin_test_bodies(text):
    """把 Kotlin 测试文本切成 `(方法名, 方法体)` 列表。

    只认显式 `@Test fun name(...) { ... }` 标注的方法体。类体里的普通函数、属性初始化块
    与顶层代码都不采集：把「文件里出现过」当作证据，正是本工具要消除的宽松匹配。
    """
    bodies = []
    pattern = re.compile(r"@Test\b[^\n]*\bfun\s+(\w+)\s*\([^)]*\)\s*(?::[^={]+)?\{")
    for match in pattern.finditer(text):
        body = block_at(text, match.end() - 1, match.group(1))
        bodies.append((match.group(1), body))
    return bodies


def python_test_bodies(text):
    """把 Python 测试文本切成 `(测试函数名, 函数体)` 列表。

    只采集名字以 `test` 开头的函数体；解析失败（畸形语法）或无测试函数时返回空列表：
    宁可漏判也不把整个文件当成一条证据。
    """
    try:
        tree = ast.parse(text)
    except SyntaxError:
        return []
    lines = text.splitlines()
    bodies = []
    for node in ast.walk(tree):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name.startswith("test"):
            end = getattr(node, "end_lineno", None)
            if end is None:
                continue
            bodies.append((node.name, "\n".join(lines[node.lineno - 1:end])))
    return bodies


def scan_escape_references(root):
    """只采集剔除注释后的测试断言，并要求两类行为都有真实断言证据。

    单单让属性字面量出现在文件里（哪怕出现在断言中）不算证据：那可能是拿字面量当
    普通字符串比较，或只是把常量打印出来。证据按**测试方法体**聚合，每个方法体内部
    必须同时满足：

      * 至少一条断言行里出现被投保的属性字面量；
      * 至少一条断言行里出现该逃生门的真实 API 符号，并与期望字面量
        （`true`/`false`/`null`/`True`/`False`/`None`）比较。

    这样「只 include」「注释里写一句」「单个字符串」「README 里任意位置出现属性名」
    「字面量与 API 断言分散在两条互不相关的方法/文件里」都不再能把「逃生门测试引用」
    从缺口翻成通过。扫描来源包含 `app/src/test`、`app/src/launcherNativeTest` 与
    `tools/test_*.py`（后者是仓库里的 Python 单测，也属于机器断言来源）；本审计工具
    自己的测试文件（`REFERENCE_EXCLUDE`）被排除，避免工具用自身测试自证。
    """
    kill_property = baseline.ESCAPE_GATES["killSwitch"]["property"]
    guard_property = baseline.ESCAPE_GATES["bootLoopGuard"]["property"]
    files = []
    for base in REFERENCE_ROOTS:
        for path in sorted((root / base).rglob("*.kt")):
            if path.is_file():
                files.append(path)
    files.extend(sorted(Path(item) for item in glob.glob(str(root / REFERENCE_TOOL_GLOB))))
    evidence = []
    expectation = re.compile(r"\b(?:true|false|null|True|False|None)\b")
    assertion = re.compile(r"\b(?:assert\w*|assertThat|assertTrue|assertFalse)\s*\(")
    for path in files:
        if REFERENCE_EXCLUDE in path.name:
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except OSError:
            continue
        if path.suffix == ".py":
            bodies = python_test_bodies(text)
        else:
            bodies = kotlin_test_bodies(strip_comments(text))
        kill_bodies = []
        guard_bodies = []
        for name, body in bodies:
            lines = [line for line in body.splitlines() if assertion.search(line)]
            kill = (any(kill_property in line for line in lines)
                    and any(re.search(r"\bKillSwitch\.(?:reportOnce|isEngaged)\s*\(", line)
                            and expectation.search(line) for line in lines))
            guard = (any(guard_property in line for line in lines)
                     and any(re.search(r"\breadDisabledProperty\s*\(", line)
                             and expectation.search(line) for line in lines))
            if kill:
                kill_bodies.append(name)
            if guard:
                guard_bodies.append(name)
        if kill_bodies or guard_bodies:
            evidence.append({"文件": str(path.relative_to(root)).replace("\\", "/"),
                             "断言类型": [name for name, matched in (("KillSwitch", bool(kill_bodies)),
                                                                     ("BootLoopGuard", bool(guard_bodies))) if matched],
                             "覆盖行为类": [name for name, matched in (("全部 hook 不装/fail-closed", bool(kill_bodies)),
                                                                          ("读取失败跳过/属性为真跳过", bool(guard_bodies))) if matched],
                             "方法": {"KillSwitch": kill_bodies, "BootLoopGuard": guard_bodies}})
    categories = {name for item in evidence for name in item["断言类型"]}
    return {"证据": evidence, "完整": categories == {"KillSwitch", "BootLoopGuard"}}


def verify_contract(contract, root=ROOT):
    verify_baseline_integrity(root=root)
    require(SOURCES is baseline.HOST,
            "被审计文件指针表被换成了别的映射：SOURCES 必须是独立基线的 HOST 本身")
    require(contract["schemaVersion"] == 1 and contract["policy"] == POLICY,
            "合同版本或策略不符")
    require(contract["installPermitted"] is False, "合同不得声明安装许可")
    require(contract["notClosedGates"] == [1, 3, 5], "合同必须固定门槛 1、3、5 未关闭")
    require(contract["toolchain"]["target"] == PROBE_TARGET
            and tuple(contract["toolchain"]["flags"]) == PROBE_FLAGS,
            "合同记录的工具链参数与本工具实际使用的不一致，必须重新实测")
    for key in ("fuse", "cases", "decodeCases", "escapeGates", "nativeWiring",
                "experimentalInstallSwitch", "scope", "kotlinTestMethods",
                "tripComparisons", "verificationBoundary"):
        require(key in contract, "合同缺少必备小节：" + key)
    fuse = contract["fuse"]
    # 覆盖清单必须恰好是两条投保的逃生属性；这一条先判，才能给出精确的失败原因，
    # 而不是被后面的大基线比较笼统盖住。
    insured = {contract["escapeGates"]["killSwitch"]["property"],
               contract["escapeGates"]["bootLoopGuard"]["property"]}
    recorded = contract.get("readmeEscapedProperties")
    require(isinstance(recorded, list) and set(recorded) == insured and len(recorded) == len(insured),
            "覆盖清单与逃生门小节不一致：必须是 %s，合同写的是 %s"
            % (sorted(insured), recorded))
    expected = (
        fuse.get("windowMs") == baseline.FUSE["window_ms"]
        and fuse.get("minTimeMs") == baseline.FUSE["min_time_ms"]
        and fuse.get("kotlin", {}).get("stateFields") == baseline.FUSE["kotlin_state_fields"]
        and fuse.get("native", {}).get("stateFields") == baseline.FUSE["native_state_fields"]
        and fuse.get("fieldPairs") == baseline.FUSE["field_pairs"]
        and {re.sub(r"\\(.)", r"\1", key) for key in contract["tripComparisons"].get("kotlin", {})}
        == set(baseline.TRIP_ORDER["kotlin"])
        and {re.sub(r"\\(.)", r"\1", key) for key in contract["tripComparisons"].get("native", {})}
        == set(baseline.TRIP_ORDER["native"])
        and contract["escapeGates"]["killSwitch"]["property"] == baseline.ESCAPE_GATES["killSwitch"]["property"]
        and contract["escapeGates"]["killSwitch"]["files"] == baseline.ESCAPE_GATES["killSwitch"]["files"]
        and contract["escapeGates"]["bootLoopGuard"]["property"] == baseline.ESCAPE_GATES["bootLoopGuard"]["property"]
        and contract["escapeGates"]["bootLoopGuard"]["stateFile"] == baseline.ESCAPE_GATES["bootLoopGuard"]["stateFile"]
        and contract["escapeGates"]["bootLoopGuard"]["systemUiStateFile"] == baseline.ESCAPE_GATES["bootLoopGuard"]["systemUiStateFile"]
        and contract["scope"]["nativeToken"] == baseline.SCOPE["native_token"]
        and contract["scope"].get("identityConstant") == baseline.SCOPE["launcher_package_constant"]
        # 列表逐项比较，固定桌面在前、system_server 在后；不按集合或包含关系放行。
        and contract["scope"].get("scopeList") == baseline.SCOPE["scope_list_entries"]
        and contract["nativeWiring"]["wired"] is baseline.NATIVE_WIRING_BASELINE["wired"]
        and recorded == list(baseline.README_REQUIRED_STEPS)
        and contract["kotlinTestMethods"] == baseline.KOTLIN_TEST_METHODS
        and hashlib.sha256(json.dumps({"cases": contract["cases"], "decodeCases": contract["decodeCases"]},
                                      ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        == baseline.CASE_TABLES_SHA256
    )
    require(expected, "合同与独立基线不符")


# ---------------------------------------------------------------- 常量和阈值

def verify_fuse_constants(contract, sources):
    """Kotlin 常量、原生常量与合同三方必须逐个相等。"""
    fuse = contract["fuse"]
    window = kotlin_long_constant(sources["boot_fuse"], fuse["kotlin"]["windowConstant"],
                                  "Kotlin " + fuse["kotlin"]["windowConstant"])
    minimum = kotlin_long_constant(sources["boot_fuse"], fuse["kotlin"]["minTimeConstant"],
                                   "Kotlin " + fuse["kotlin"]["minTimeConstant"])
    native_window = eval_int_literal(single_match(
        r"\bconstexpr\s+int64_t\s+" + re.escape(fuse["native"]["windowConstant"]) + r"\s*=\s*([^;]+);",
        sources["safety_gate"], "原生 " + fuse["native"]["windowConstant"]), "原生熔断窗口")
    native_minimum = eval_int_literal(single_match(
        r"\bconstexpr\s+int64_t\s+" + re.escape(fuse["native"]["minTimeConstant"]) + r"\s*=\s*([^;]+);",
        sources["safety_gate"], "原生 " + fuse["native"]["minTimeConstant"]), "原生最小可信时间")
    require(window == baseline.FUSE["window_ms"] and native_window == baseline.FUSE["window_ms"],
            "熔断窗口常量与独立基线不符")
    require(minimum == baseline.FUSE["min_time_ms"] and native_minimum == baseline.FUSE["min_time_ms"],
            "最小可信时间常量与独立基线不符")
    return {"窗口毫秒": window, "最小可信时间毫秒": minimum, "两侧一致": True}


def verify_fuse_fields(contract, sources):
    """状态行的字段顺序决定磁盘格式；两侧顺序必须一致且与合同一致。"""
    fuse = contract["fuse"]
    block = single_match(r"data\s+class\s+BootFuseState\s*\((.*?)\)\s*\{",
                         sources["boot_fuse"], "Kotlin BootFuseState 定义", re.S)
    kotlin_fields = re.findall(r"\bval\s+(\w+)\s*:", block)
    native_block = single_match(r"struct\s+SafetyFuseState\s*\{(.*?)\};",
                                sources["safety_gate"], "原生 SafetyFuseState 定义", re.S)
    native_fields = re.findall(r"\b(?:int64_t|uint32_t|bool)\s+(\w+)\s*;", native_block)
    require(kotlin_fields == baseline.FUSE["kotlin_state_fields"],
            "Kotlin 熔断状态字段顺序与独立基线不符：%s" % kotlin_fields)
    require(native_fields == baseline.FUSE["native_state_fields"],
            "原生熔断状态字段顺序与独立基线不符：%s" % native_fields)
    require(len(kotlin_fields) == len(native_fields) == len(baseline.FUSE["field_pairs"]),
            "两侧熔断状态字段数量不一致")
    return {"字段对": baseline.FUSE["field_pairs"], "两侧一致": True}


def verify_trip_thresholds(contract, sources):
    """按独立基线要求的严格有序子序列验证判定结构。"""
    kotlin = strip_comments(sources["boot_fuse"])
    native = strip_comments(sources["safety_gate"])
    report = {}
    for side, text, rules in (("Kotlin", kotlin, baseline.TRIP_ORDER["kotlin"]),
                              ("原生", native, baseline.TRIP_ORDER["native"])):
        normalized = re.sub(r"\s+", " ", text)
        cursor = 0
        for expression in rules:
            match = re.search(re.escape(expression), normalized[cursor:])
            require(match is not None, "%s 熔断判定结构缺失或顺序不符：%s" % (side, expression))
            cursor += match.end()
            report["%s:%s" % (side, expression)] = True
    for key, source, signature in (
            ("kotlin_next", kotlin, "fun next("), ("kotlin_decode", kotlin, "fun decode(text: String)"),
            ("native_next", native, "constexpr SafetyFuseDecision safety_fuse_next(")):
        index = source.find(signature)
        require(index >= 0, "熔断函数缺失：%s" % key)
        digest = hashlib.sha256(re.sub(r"\s+", " ", block_at(source, index, key)).strip().encode()).hexdigest()
        require(digest == baseline.BODY_DIGESTS[key], "熔断函数体摘要与独立基线不符：%s" % key)
        report[key + "Sha256"] = digest
    return report


# ---------------------------------------------------------------- 原生编译期复算

def cpp_string(value, label):
    """探针里只放可直接嵌入 C++ 字符串字面量的可打印 ASCII，避免转义分支。"""
    require(re.fullmatch(r"[\x20-\x7e]*", value) is not None
            and '"' not in value and "\\" not in value,
            "「%s」含无法安全写入探针的字符" % label)
    return '"%s"' % value


def native_probe_source(contract, blocker_names):
    fuse = contract["fuse"]
    lines = [
        "// 由 tools/launcher_safety_parity_audit.py 生成，只用于编译期常量求值。",
        '#include "launcher/safety_gate.h"',
        "",
        "using namespace launcher;",
        "",
        'static_assert(kSafetyFuseWindowMs == %d, "SAFETY_PARITY_WINDOW");' % fuse["windowMs"],
        'static_assert(kSafetyMinTimeMs == %dLL, "SAFETY_PARITY_MINTIME");' % fuse["minTimeMs"],
        "",
    ]
    for case in contract["decodeCases"]:
        name = "decode_" + re.sub(r"\W", "_", case["id"])
        body = ["constexpr bool %s() {" % name, "    SafetyFuseState state{};"]
        literal = cpp_string(case["text"], "解码用例 " + case["id"])
        term = "decode_safety_fuse(%s, %d, state)" % (literal, len(case["text"]))
        if case["valid"]:
            body.append("    return %s" % term)
            for field, key in (("last_start_ms", "lastStartMs"), ("rapid_restarts", "rapidRestarts"),
                               ("last_reset_token", "resetToken"), ("window_start_ms", "windowStartMs")):
                body.append("        && state.%s == %dLL" % (field, case[key]))
            body.append("        && state.disabled == %s;" % ("true" if case["disabled"] else "false"))
        else:
            body.append("    return !%s;" % term)
        body.append("}")
        lines.extend(body)
        lines.append('static_assert(%s(), "DECODE_%s");' % (name, case["id"]))
        lines.append("")
    for case in contract["cases"]:
        name = re.sub(r"\W", "_", case["id"])
        previous = case["previous"]
        state = "SafetyFuseState{%dLL, %d, %s, %d, %dLL}" % (
            previous["lastStartMs"], previous["rapidRestarts"],
            "true" if previous["disabled"] else "false", previous["resetToken"],
            previous["windowStartMs"])
        lines.append("constexpr SafetyFuseState p_%s = %s;" % (name, state))
        lines.append("constexpr SafetyFuseDecision d_%s = safety_fuse_next(p_%s, %s, %dLL, %d, %s);" % (
            name, name, "true" if case["havePrevious"] else "false",
            case["nowMs"], case["token"], "true" if case["countRestart"] else "false"))
        lines.append('static_assert(d_%s.ok == %s, "CASE_ok_%s");'
                     % (name, "true" if case["ok"] else "false", case["id"]))
        if case.get("blocker"):
            require(case["blocker"] in blocker_names, "用例使用了未定义阻断位：" + case["blocker"])
            lines.append('static_assert((d_%s.blockers & %s) != 0, "CASE_blocker_%s");'
                         % (name, case["blocker"], case["id"]))
        else:
            lines.append('static_assert(d_%s.blockers == 0, "CASE_blocker_%s");' % (name, case["id"]))
        if case["ok"]:
            lines.append('static_assert(d_%s.next.disabled == %s, "CASE_disabled_%s");'
                         % (name, "true" if case["disabled"] else "false", case["id"]))
            lines.append('static_assert(d_%s.next.rapid_restarts == %d, "CASE_count_%s");'
                         % (name, case["rapidRestarts"], case["id"]))
            if case.get("stateUnchanged"):
                lines.append('static_assert(d_%s.next.last_start_ms == p_%s.last_start_ms'
                             ' && d_%s.next.window_start_ms == p_%s.window_start_ms'
                             ' && d_%s.next.last_reset_token == p_%s.last_reset_token,'
                             ' "CASE_keep_%s");' % (name, name, name, name, name, name, case["id"]))
            if case.get("nextWindowStartMs") is not None:
                lines.append('static_assert(d_%s.next.last_start_ms == %dLL'
                             ' && d_%s.next.window_start_ms == %dLL, "CASE_window_%s");'
                             % (name, case["nowMs"], name, case["nextWindowStartMs"], case["id"]))
        if "permitsInstall" in case:
            lines.append('static_assert(safety_permits_install(d_%s) == %s, "CASE_permits_%s");'
                         % (name, "true" if case["permitsInstall"] else "false", case["id"]))
        lines.append("")
    require(blocker_names, "原生安全阻断位枚举为空")
    return "\n".join(lines) + "\n"


def native_blocker_names(sources):
    block = single_match(r"enum\s+SafetyBlocker\s*:\s*uint32_t\s*\{(.*?)\};",
                         sources["safety_gate"], "原生安全阻断位枚举", re.S)
    names = re.findall(r"(\w+)\s*=\s*1U\s*<<\s*\d+", block)
    require(len(names) >= 4, "原生安全阻断位数量异常")
    return names


def find_clang(explicit, root=ROOT):
    """只读探测 NDK clang；找不到返回 None（编译期复算标记为未确认）。"""
    if explicit:
        path = Path(explicit)
        require(path.is_file(), "指定的 clang 不存在")
        return path
    found = shutil.which("clang")
    if found:
        return Path(found)
    candidates = []
    for variable in ("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT"):
        value = os.environ.get(variable)
        if value:
            candidates.append(Path(value))
    local = root / "local.properties"
    if local.is_file():
        match = re.search(r"^\s*sdk\.dir\s*=\s*(.+?)\s*$", local.read_text(encoding="utf-8"), re.M)
        if match:
            sdk = Path(match.group(1).replace("\\:", ":").replace("\\", "/"))
            candidates.extend(sorted((sdk / "ndk").glob("*")) if (sdk / "ndk").is_dir() else [])
    for candidate in candidates:
        prebuilt = candidate / "toolchains/llvm/prebuilt"
        for folder in sorted(glob.glob(str(prebuilt / "*"))):
            binary = Path(folder) / "bin"
            # 只认 clang 本体，不认 clang++ / clang-cl / clang-check；固定优先顺序便于复现。
            for name in ("clang.exe", "clang"):
                path = binary / name
                if path.is_file():
                    return path
    return None


def run_probe(clang, source_text, root=ROOT):
    """用真实 clang 做一次 `-fsyntax-only`，失败时只回报断言标记，不回显本地路径。"""
    with tempfile.TemporaryDirectory(prefix="safety-parity-") as folder:
        probe = Path(folder) / "probe.cpp"
        probe.write_text(source_text, encoding="utf-8")
        command = [str(clang), "--target=" + PROBE_TARGET, *PROBE_FLAGS,
                   "-I", str(root / "app/src/main/cpp"), str(probe)]
        result = subprocess.run(command, capture_output=True, text=True, check=False)
    if result.returncode != 0:
        tokens = PROBE_TOKEN.findall(result.stderr or "")
        require(tokens, "原生探针编译失败，且失败原因不是已知断言（编译器或头文件问题）")
        raise AuditError("原生编译期复算失败，命中断言：" + "、".join(sorted(set(tokens))))
    return {"编译器": Path(clang).name, "断言数": count_matches(r"static_assert\(", source_text)}


# ---------------------------------------------------------------- 逃生门与接线

def verify_scope_consistency(sources):
    """桌面令牌须匹配身份契约；注入作用域须恰好为桌面、system_server，顺序固定。

    早期版本把「原生 `kLauncherSafetyScope` 出现在主模块 `SCOPES` 的 `process`
    里」当成一致性判据 —— 这个判据本身是错的：主模块 `SCOPES` 是**界面侧重启宿主
    清单**，桌面（`com.miui.home`）不在其中，而真正声明桌面注入作用域的是
    `launcherNative` 的 `scope.list` 与它的 Kotlin 身份契约。

    正确判据（fail-closed，任一处不符即缺口）：

      * `kLauncherSafetyScope` 等于 `LauncherHostContract.PACKAGE`；
      * `launcherNative/resources/META-INF/xposed/scope.list` 逐项且按顺序等于
        基线清单：桌面 `com.miui.home` 在前、system_server `system` 在后；
        桌面项仍等于 `kLauncherSafetyScope`，多项、缺项、换序或空行干扰都报缺口。
    """
    scope = baseline.SCOPE
    native_scope = single_match(r"kLauncherSafetyScope\[\]\s*=\s*\"([^\"]+)\"",
                                sources["safety_gate"], "原生桌面安全作用域")
    require(native_scope == scope["native_token"], "原生桌面安全作用域漂移：%r" % native_scope)
    identity_constant = scope["launcher_package_constant"]
    identity_scope = kotlin_string_constant(sources["launcher_identity"], identity_constant,
                                            "launcherNative 身份契约 " + identity_constant)
    # 保留空行和行内空白，避免把格式干扰归一化成合格清单。
    entries = sources["launcher_native_scope"].splitlines()
    mismatches = []
    if identity_scope != native_scope:
        mismatches.append("LauncherHostContract.%s=%r" % (identity_constant, identity_scope))
    if entries != scope["scope_list_entries"]:
        mismatches.append("scope.list=%s" % entries)
    return {"跨侧一致": not mismatches, "原生令牌": native_scope, "身份契约": identity_scope,
            "scope.list": entries, "桌面项": entries[0] if entries else None,
            "system 项": entries[1] if len(entries) > 1 else None,
            "基线清单": list(scope["scope_list_entries"]), "不一致来源": "、".join(mismatches)}


def verify_escape_gates(contract, sources):
    gates = contract["escapeGates"]
    kill_switch = sources["kill_switch"]
    boot_loop = sources["boot_loop_guard"]
    kill_property = kotlin_string_constant(kill_switch, gates["killSwitch"]["propertyConstant"],
                                           "KillSwitch 逃生门属性")
    kill_files = kotlin_string_list(kill_switch, gates["killSwitch"]["filesConstant"],
                                    "KillSwitch 逃生门文件")
    guard_property = kotlin_string_constant(boot_loop, gates["bootLoopGuard"]["propertyConstant"],
                                            "BootLoopGuard 保护属性")
    guard_state = kotlin_string_constant(boot_loop, gates["bootLoopGuard"]["stateFileConstant"],
                                         "BootLoopGuard 状态文件")
    # 只定位熔断存储本身；主动重启消费记录等其他私有文件不能被误认为熔断文件。
    guard_ui = single_match(r"BootFuseStore\(\s*File\(\s*dataDir\s*,\s*\"([^\"]+)\"\s*\)\s*\)", boot_loop,
                            "BootLoopGuard SystemUI 状态文件")
    require(kill_property == gates["killSwitch"]["property"],
            "逃生门属性名漂移：%r" % kill_property)
    require(kill_files == gates["killSwitch"]["files"], "逃生门文件清单漂移：%s" % kill_files)
    require(guard_property == gates["bootLoopGuard"]["property"],
            "框架保护属性名漂移：%r" % guard_property)
    require(guard_state == gates["bootLoopGuard"]["stateFile"], "框架保护状态文件漂移：%r" % guard_state)
    require(guard_ui == gates["bootLoopGuard"]["systemUiStateFile"],
            "SystemUI 独立保护状态文件漂移：%r" % guard_ui)
    # 两条属性必须互不相同：若撞名，清一条会连带清另一条，恢复语义会被静默改变。
    require(kill_property != guard_property, "逃生门属性与框架保护属性冲突（同名）")
    require(not kill_property.startswith(guard_property) and not guard_property.startswith(kill_property),
            "两条保护属性互为前缀，语义容易混淆")
    require(kotlin_catch_result(kill_switch, gates["killSwitch"]["readSignature"],
                                "KillSwitch 属性读取") == "true",
            "KillSwitch 属性读取失败时不再 fail-closed（catch 兜底不是 true）")
    require(kotlin_catch_result(boot_loop, gates["bootLoopGuard"]["readSignature"],
                                "BootLoopGuard 属性读取") == "null",
            "BootLoopGuard 属性读取失败时不再返回 null")
    require(count_matches(r"readDisabledProperty\(\)\s*!=\s*false", boot_loop) == 2,
            "框架保护属性读取结果未被用作「非 false 即跳过」的 fail-closed 判定")
    return {"逃生门属性": kill_property, "逃生门文件": kill_files,
            "框架保护属性": guard_property, "框架保护状态文件": guard_state,
            "SystemUI 状态文件": guard_ui, "属性互不相同": True}


def verify_install_lock(contract, sources):
    """安装许可只能由现场证据决定，普通构建由显式实验开关保持关闭。"""
    switch = contract["experimentalInstallSwitch"]
    permit = sources["install_permit"]
    macro = "#if defined(%s)" % switch["macro"]
    enabled = "constexpr bool %s = true;" % switch["constant"]
    disabled = "constexpr bool %s = false;" % switch["constant"]
    require(count_matches(re.escape(macro), permit) == 1 and
            count_matches(re.escape(enabled), permit) == 1 and
            count_matches(re.escape(disabled), permit) == 1,
            "实验安装开关宏或常量分支缺失")
    require("kPendingAcceptanceBlockers" not in permit,
            "安装许可仍含已废弃的待验收阻断常量")
    permit_body = single_match(r"constexpr\s+bool\s+install_permitted\s*\([^)]*\)\s*\{(.*?)\n\}",
                               sources["install_permit"], "安装许可判定", re.S)
    require(re.search(r"permit_blockers\(\s*evidence\s*\)\s*==\s*0", permit_body) is not None,
            "安装许可不再是「阻断位恰好为 0」的判定")
    blockers = single_match(r"constexpr\s+uint32_t\s+permit_blockers\s*\([^)]*\)\s*\{(.*?)\n\}",
                            sources["install_permit"], "安装阻断位判定", re.S)
    require("evidence.entries_present" in blockers and
            "evidence.safety_verified" in blockers and
            "evidence.backend_verified" in blockers,
            "安装阻断位未覆盖入口、安全或后端现场证据")
    return {"实验安装开关": switch, "现场证据": ["entries_present", "safety_verified",
                                                  "backend_verified"]}


CPP_KEYWORDS = {"if", "for", "while", "switch", "catch", "return", "sizeof", "alignof",
                "decltype", "static_assert", "do", "else", "defined",
                # `if constexpr (false) {` 会被函数定义正则误当成名为 constexpr 的函数；
                # 一旦误认，调用图就会顺着这个假函数把死分支里的调用标成可达。
                "constexpr"}


def strip_inactive_preprocessor(text):
    """删除 `#if 0`（`#if false`）到对应 `#endif` 之间的未编译代码。

    这些行永远不会被编译；若留作证据，`#if 0` 包住的接线就能骗过只读断言。
    所有条件编译指令都入栈（`#if` / `#ifdef` / `#ifndef`），因此嵌套结构也能正确
    配平；只有确定为 `#if 0` / `#if false` 的分支才整体删除。
    """
    out = []
    inactive_stack = []  # 每层是否处于“不编译”状态
    for line in text.split("\n"):
        stripped = line.lstrip()
        if stripped.startswith("#if"):
            if stripped.startswith("#ifdef") or stripped.startswith("#ifndef"):
                inactive = False  # 无法静态判定，按“可能编译”处理，不删
            else:
                condition = stripped[len("#if"):].strip().rstrip("/").strip()
                inactive = condition in ("0", "false", "FALSE")
            inactive_stack.append(inactive)
            continue
        if stripped.startswith("#else") and inactive_stack:
            inactive_stack[-1] = not inactive_stack[-1]
            continue
        if stripped.startswith("#elif") and inactive_stack:
            inactive_stack[-1] = False if inactive_stack[-1] else True
            continue
        if stripped.startswith("#endif") and inactive_stack:
            inactive_stack.pop()
            continue
        if any(inactive_stack):
            continue
        out.append(line)
    return "\n".join(out)


def code_only(text):
    """剔除注释、字符串字面量与预处理指令行，只保留可扫描的结构字符。

    `#if 0` 内的代码不算证据；`#include` / `#define` 等指令行一并去掉，避免被
    语句级可达性分析误当成普通语句。宏常量另由 `macro_constants` 采集。
    """
    stripped = strip_comments(strip_inactive_preprocessor(text))
    stripped = "\n".join(line for line in stripped.split("\n") if not line.lstrip().startswith("#"))
    return re.sub(r'"(?:\\.|[^"\\])*"', '""', stripped)


# ------------------------------------------------------------------ 常量与可达性
#
# 只按字面量 `(0)` / `(false)` 判恒假分支是不够的：`if constexpr (false)`、
# `#define NEVER 0` 后写 `if (NEVER)`、`for (; false;)`、`switch (0) { case 1: }`
# 都能让安装调用永远执行不到，却仍被当成接线。这里补两件事：
#   1. 三值常量求值（真/假/未知），识别宏常量、`!`、`&&`、`||` 与比较；
#   2. 语句级可达性分析，覆盖恒假分支、恒真循环、恒真 if 的 else、
#      以及 `return`/`goto` 之后的代码。
# 判据仍然 fail-closed：识别不出就是「未知」，按可达处理，不凭猜测翻绿。

MACRO_DEFINE = re.compile(r"^[ \t]*#[ \t]*define[ \t]+([A-Za-z_]\w*)[ \t]+(.+?)[ \t]*$")


def macro_constants(text):
    """收集 `#define 名字 整数` 形式的常量；表达式宏一律不采集（宁可未知）。"""
    macros = {}
    for line in text.split("\n"):
        match = MACRO_DEFINE.match(line)
        if match is None:
            continue
        name, raw = match.group(1), match.group(2).strip()
        if raw == "true":
            macros[name] = 1
        elif raw == "false":
            macros[name] = 0
        elif re.fullmatch(r"0[xX][0-9a-fA-F]+[uUlL]*", raw):
            macros[name] = int(raw.rstrip("uUlL"), 16)
        elif re.fullmatch(r"[0-9]+[uUlL]*", raw):
            macros[name] = int(raw.rstrip("uUlL"))
    return macros


CONST_TOKEN = re.compile(r"\s*(?:([0-9]+[uUlL]*)|([A-Za-z_]\w*)|(&&|\|\||==|!=|<=|>=|[!()<>+\-]))")


def _const_tokens(condition):
    tokens = []
    index = 0
    while index < len(condition):
        match = CONST_TOKEN.match(condition, index)
        if match is None:
            return None
        tokens.append(match.group(1) or match.group(2) or match.group(3))
        index = match.end()
    return tokens


def _const_or_bits(left, right):
    if left == 1 or right == 1:
        return 1
    if left == 0 and right == 0:
        return 0
    return None


def _const_and_bits(left, right):
    if left == 0 or right == 0:
        return 0
    if left == 1 and right == 1:
        return 1
    return None


def _const_primary(tokens, index, macros):
    if index >= len(tokens):
        return None, index
    token = tokens[index]
    if token == "(":
        value, index = _const_or(tokens, index + 1, macros)
        if index < len(tokens) and tokens[index] == ")":
            return value, index + 1
        return None, index
    if token == "!":
        value, index = _const_primary(tokens, index + 1, macros)
        return (None if value is None else int(not value)), index
    if token in ("-", "+"):
        value, index = _const_primary(tokens, index + 1, macros)
        if value is None:
            return None, index
        return (-value if token == "-" else value), index
    if re.fullmatch(r"[0-9]+[uUlL]*", token):
        return int(token.rstrip("uUlL")), index + 1
    if token in ("true", "TRUE"):
        return 1, index + 1
    if token in ("false", "FALSE"):
        return 0, index + 1
    if re.fullmatch(r"[A-Za-z_]\w*", token):
        return macros.get(token), index + 1
    return None, index


def _const_compare(tokens, index, macros):
    value, index = _const_primary(tokens, index, macros)
    while index < len(tokens) and tokens[index] in ("<", ">", "<=", ">=", "==", "!="):
        operator = tokens[index]
        right, index = _const_primary(tokens, index + 1, macros)
        if value is None or right is None:
            value = None
            continue
        if operator == "<":
            value = int(value < right)
        elif operator == ">":
            value = int(value > right)
        elif operator == "<=":
            value = int(value <= right)
        elif operator == ">=":
            value = int(value >= right)
        elif operator == "==":
            value = int(value == right)
        else:
            value = int(value != right)
    return value, index


def _const_and_chain(tokens, index, macros):
    value, index = _const_compare(tokens, index, macros)
    while index < len(tokens) and tokens[index] == "&&":
        right, index = _const_compare(tokens, index + 1, macros)
        value = _const_and_bits(value, right)
    return value, index


def _const_or(tokens, index, macros):
    value, index = _const_and_chain(tokens, index, macros)
    while index < len(tokens) and tokens[index] == "||":
        right, index = _const_and_chain(tokens, index + 1, macros)
        value = _const_or_bits(value, right)
    return value, index


BOOL_TOKEN_OPERATORS = ("&&", "||", "==", "!=")


def _bool_tokens(text):
    """把布尔条件切成 token：运算符、括号、`!`，其余连续片段算一个原子。

    原子内部的括号（函数调用实参）不拆开，因此 `errors(a, b)` 是一个原子。
    无法切分时返回 None，让调用方退回更保守的判据。
    """
    tokens = []
    cursor = 0
    length = len(text)
    while cursor < length:
        char = text[cursor]
        if char.isspace():
            cursor += 1
            continue
        if text[cursor:cursor + 2] in BOOL_TOKEN_OPERATORS:
            tokens.append(text[cursor:cursor + 2])
            cursor += 2
            continue
        if char in "!()":
            tokens.append(char)
            cursor += 1
            continue
        start = cursor
        depth = 0
        while cursor < length:
            char = text[cursor]
            if char in "([":
                depth += 1
            elif char in ")]":
                if depth == 0:
                    break
                depth -= 1
            elif depth == 0 and (text[cursor:cursor + 2] in BOOL_TOKEN_OPERATORS
                                 or char in "!()"):
                break
            cursor += 1
        piece = text[start:cursor].strip()
        if not piece:
            return None
        tokens.append(piece)
    return tokens


def _parse_bool_expr(tokens):
    """把布尔 token 序列解析成语法树；不认识的写法返回 None。"""
    index = [0]

    def parse_primary():
        if index[0] >= len(tokens):
            return None
        token = tokens[index[0]]
        if token == "!":
            index[0] += 1
            value = parse_primary()
            return None if value is None else ("not", value)
        if token == "(":
            index[0] += 1
            value = parse_or()
            if value is None or index[0] >= len(tokens) or tokens[index[0]] != ")":
                return None
            index[0] += 1
            return value
        if token in BOOL_TOKEN_OPERATORS or token == ")":
            return None
        index[0] += 1
        return ("atom", token)

    def parse_equality():
        value = parse_primary()
        while value is not None and index[0] < len(tokens) and tokens[index[0]] in ("==", "!="):
            operator = tokens[index[0]]
            index[0] += 1
            right = parse_primary()
            if right is None:
                return None
            value = (operator, value, right)
        return value

    def parse_and():
        value = parse_equality()
        while value is not None and index[0] < len(tokens) and tokens[index[0]] == "&&":
            index[0] += 1
            right = parse_equality()
            if right is None:
                return None
            value = ("&&", value, right)
        return value

    def parse_or():
        value = parse_and()
        while value is not None and index[0] < len(tokens) and tokens[index[0]] == "||":
            index[0] += 1
            right = parse_and()
            if right is None:
                return None
            value = ("||", value, right)
        return value

    value = parse_or()
    if value is None or index[0] != len(tokens):
        return None
    return value


def _bool_literal(atom):
    """`true`/`false`/整数字面量（含 U/L 后缀）的布尔真值；其余返回 None。"""
    if atom in ("true", "TRUE"):
        return 1
    if atom in ("false", "FALSE"):
        return 0
    if re.fullmatch(r"[0-9]+[uUlL]*", atom):
        return 1 if int(atom.rstrip("uUlL")) != 0 else 0
    return None


def _bool_atoms(ast, found):
    if ast[0] == "atom":
        if _bool_literal(ast[1]) is None:
            found.add(ast[1])
        return
    if ast[0] == "not":
        _bool_atoms(ast[1], found)
        return
    _bool_atoms(ast[1], found)
    _bool_atoms(ast[2], found)


def _eval_bool(ast, env):
    kind = ast[0]
    if kind == "atom":
        literal = _bool_literal(ast[1])
        if literal is not None:
            return literal
        return env[ast[1]]
    if kind == "not":
        return 1 - _eval_bool(ast[1], env)
    left = _eval_bool(ast[1], env)
    right = _eval_bool(ast[2], env)
    if kind == "&&":
        return 1 if (left and right) else 0
    if kind == "||":
        return 1 if (left or right) else 0
    if kind == "==":
        return int(left == right)
    if kind == "!=":
        return int(left != right)
    raise ValueError(kind)


def constant_value(condition, macros=None):
    """对条件做三值求值：返回真实常量值，`None` 表示无法静态判定。

    返回值刻意**不做布尔压缩**：`switch (1) { case 2: ... }` 这类写法要求知道
    标签究竟是 2 还是 1，压缩成 0/1 会把不匹配的 case 误判成匹配。
    调用方若只关心真假，用 `value != 0` 判断即可。
    """
    if condition is None:
        return None
    tokens = _const_tokens(condition)
    if not tokens:
        return None
    value, index = _const_or(tokens, 0, macros or {})
    if index != len(tokens):
        return None
    return value


def short_circuit_value(condition):
    """短路常量：`x || true` / `true || x` 恒真、`x && false` / `false && x` 恒假。

    这是常量求值的兜底：token 级求值遇到 `ordinary_snapshot_errors(...)`、
    `result.committed` 这类未知子表达式会放弃，但只要一侧是短路常量，
    整个条件仍然是恒真的，必须照样判为常量条件。
    """
    if condition is None:
        return None
    if re.search(r"(?:\|\|\s*(?:true|1)\b)|(?:(?:true|1)\s*\|\|)", condition):
        return 1
    if re.search(r"(?:&&\s*(?:false|0)\b)|(?:(?:false|0)\s*&&)", condition):
        return 0
    return None


def constant_condition(condition, macros=None):
    """恒真/恒假条件：`x || true`、`x && false`、`if (0)`、`if (false)` 都算。"""
    return (constant_value(condition, macros) is not None
            or short_circuit_value(condition) is not None)


def _skip_ws_to(text, index, end):
    while index < end and text[index].isspace():
        index += 1
    return index


def _word_at(text, index, word):
    return text.startswith(word, index) and (
        index + len(word) >= len(text) or not (text[index + len(word)].isalnum() or text[index + len(word)] == "_"))


def _match_bracket(text, index, end, opener, closer):
    depth = 0
    cursor = index
    while cursor < end:
        if text[cursor] == opener:
            depth += 1
        elif text[cursor] == closer:
            depth -= 1
            if depth == 0:
                return cursor
        cursor += 1
    return -1


def _statement_end(text, index, end):
    """单条语句的结束下标：depth 0 处的 `;` 之后，或当前块结束。"""
    depth = 0
    cursor = index
    while cursor < end:
        char = text[cursor]
        if char in "([{":
            depth += 1
        elif char in ")]}":
            if depth == 0:
                return cursor
            depth -= 1
        elif char == ";" and depth == 0:
            return cursor + 1
        cursor += 1
    return cursor


def _parse_span(text, index, end):
    """把 `index` 起的一条语句解析成 `(start, end, is_block)` 与下一条起点。"""
    index = _skip_ws_to(text, index, end)
    if index >= end:
        return (index, index, False), index
    if text[index] == "{":
        close = _match_bracket(text, index, end, "{", "}")
        if close < 0:
            return (index + 1, end, True), end
        return (index + 1, close, True), close + 1
    _kind, _data, next_index = _parse_statement(text, index, end)
    return (index, next_index, False), next_index


def _parse_statement(text, index, end):
    """尽力而为地解析一条语句；无法识别时退化成「直到分号」的普通语句。"""
    index = _skip_ws_to(text, index, end)
    if index >= end:
        return "end", None, end
    char = text[index]
    if char == "{":
        close = _match_bracket(text, index, end, "{", "}")
        if close < 0:
            return "block", (index + 1, end), end
        return "block", (index + 1, close), close + 1
    if char == ";":
        return "empty", None, index + 1
    if char == "}":
        return "end", None, index
    for keyword in ("while", "for", "switch"):
        if not _word_at(text, index, keyword):
            continue
        open_paren = text.find("(", index + len(keyword), end)
        if open_paren < 0:
            return "plain", None, _statement_end(text, index, end)
        close = _match_bracket(text, open_paren, end, "(", ")")
        if close < 0:
            return "plain", None, _statement_end(text, index, end)
        condition = text[open_paren + 1:close]
        if keyword == "switch":
            body, next_index = _parse_span(text, close + 1, end)
            return "switch", (condition, body), next_index
        if keyword == "for":
            clauses = _split_top_level(condition, ";")
            condition = clauses[1] if len(clauses) > 1 else ""
        body, next_index = _parse_span(text, close + 1, end)
        return keyword, (condition, body, _span_has_break(text, body)), next_index
    if _word_at(text, index, "if"):
        cursor = _skip_ws_to(text, index + 2, end)
        if _word_at(text, cursor, "constexpr"):
            cursor = _skip_ws_to(text, cursor + 9, end)
        open_paren = text.find("(", cursor, end)
        if open_paren < 0:
            return "plain", None, _statement_end(text, index, end)
        close = _match_bracket(text, open_paren, end, "(", ")")
        if close < 0:
            return "plain", None, _statement_end(text, index, end)
        condition = text[open_paren + 1:close]
        then_span, next_index = _parse_span(text, close + 1, end)
        else_span = None
        after = _skip_ws_to(text, next_index, end)
        if _word_at(text, after, "else"):
            else_span, next_index = _parse_span(text, after + 4, end)
        return "if", (condition, then_span, else_span), next_index
    if _word_at(text, index, "do"):
        body, next_index = _parse_span(text, index + 2, end)
        condition = ""
        after = _skip_ws_to(text, next_index, end)
        if _word_at(text, after, "while"):
            open_paren = text.find("(", after + 5, end)
            close = _match_bracket(text, open_paren, end, "(", ")") if open_paren >= 0 else -1
            if close > 0:
                condition = text[open_paren + 1:close]
                next_index = close + 1
        tail = _skip_ws_to(text, next_index, end)
        if tail < end and text[tail] == ";":
            next_index = tail + 1
        return "do", (condition, body, _span_has_break(text, body)), next_index
    for keyword in ("return", "goto", "break", "continue", "throw"):
        if _word_at(text, index, keyword):
            return "jump", keyword, _statement_end(text, index, end)
    if _word_at(text, index, "case"):
        colon = _find_label_colon(text, index + 4, end)
        if colon >= 0:
            return "case", text[index + 4:colon], colon + 1
    if _word_at(text, index, "default"):
        colon = _find_label_colon(text, index + 7, end)
        if colon >= 0:
            return "default", None, colon + 1
    label = re.match(r"[A-Za-z_]\w*[ \t]*:", text[index:end])
    if label is not None:
        return "label", label.group(0)[:-1].strip(), index + label.end()
    return "plain", None, _statement_end(text, index, end)


def _find_label_colon(text, index, end):
    """找到 `case X:` / `default:` 里真正的那个冒号。

    括号内、`::` 作用域符、以及三元运算符的 `? ... :` 里的冒号都不算。
    """
    depth = 0
    ternary = 0
    cursor = index
    while cursor < end:
        char = text[cursor]
        if char in "([{":
            depth += 1
        elif char in ")]}":
            if depth == 0:
                return -1
            depth -= 1
        elif depth == 0 and char == "?":
            ternary += 1
        elif depth == 0 and char == ":":
            if cursor + 1 < end and text[cursor + 1] == ":":
                cursor += 2
                continue
            if ternary > 0:
                ternary -= 1
            else:
                return cursor
        cursor += 1
    return -1


def _split_top_level(text, separator):
    parts = []
    depth = 0
    start = 0
    for index, char in enumerate(text):
        if char in "([{":
            depth += 1
        elif char in ")]}":
            depth -= 1
        elif char == separator and depth == 0:
            parts.append(text[start:index])
            start = index + 1
    parts.append(text[start:])
    return parts


def _span_has_break(text, span):
    """该语句体内是否存在属于本层的 `break`（嵌套循环/switch 里的不算）。"""
    start, end, is_block = span
    if is_block:
        return _block_has_break(text, start, end)
    return _statement_has_break(text, start, end)


def _statement_has_break(text, start, end):
    kind, data, _next = _parse_statement(text, start, end)
    if kind in ("while", "for", "do", "switch"):
        return False  # 属于内层
    if kind == "jump":
        return data == "break"
    if kind == "block":
        return _block_has_break(text, data[0], data[1])
    if kind == "if":
        _condition, then_span, else_span = data
        if _span_has_break(text, then_span):
            return True
        return else_span is not None and _span_has_break(text, else_span)
    return False


def _block_has_break(text, start, end):
    cursor = start
    while True:
        cursor = _skip_ws_to(text, cursor, end)
        if cursor >= end:
            return False
        if _statement_has_break(text, cursor, end):
            return True
        _kind, _data, next_index = _parse_statement(text, cursor, end)
        if next_index <= cursor:
            return False
        cursor = next_index


def _terminates(text, span):
    """该语句是否必然以跳转结束（用于判断其后的语句是否可达）。"""
    if span is None:
        return False
    start, end, is_block = span
    if is_block:
        return _block_terminates(text, start, end)
    kind, data, _next = _parse_statement(text, start, end)
    if kind == "jump":
        return data in ("return", "throw", "goto", "continue")
    if kind == "block":
        return _block_terminates(text, data[0], data[1])
    if kind == "if":
        condition, then_span, else_span = data
        value = constant_value(condition)
        if value is None:
            value = short_circuit_value(condition)
        if value in (0, 1):
            # 常量条件下只有被选中的那一支会执行：它必然终止就等于整个语句必然终止。
            taken = then_span if value == 1 else else_span
            return taken is not None and _terminates(text, taken)
        if else_span is None:
            return False
        return _terminates(text, then_span) and _terminates(text, else_span)
    if kind in ("while", "for", "do"):
        condition, body_span, has_break = data
        if kind == "do":
            # do 循环至少执行一次块体：块体必然终止就等于整个语句必然终止。
            return not has_break and _terminates(text, body_span)
        value = 1 if (kind == "for" and condition.strip() == "") else constant_value(condition)
        if value is None:
            value = short_circuit_value(condition)
        # 只有「条件恒真且本层没有 break」的循环才会必然终止后面的代码。
        if value == 1 and not has_break:
            return True
    return False


def _block_terminates(text, start, end):
    """块是否必然以跳转结束。

    逐条扫描而不是只看最后一条：`{ return 2; (void)x; }` 里 `return` 之后的
    语句其实是死代码，块本身仍然必然终止。
    """
    cursor = start
    while True:
        cursor = _skip_ws_to(text, cursor, end)
        if cursor >= end:
            return False
        node = (cursor, end, False)
        _kind, _data, next_index = _parse_statement(text, cursor, end)
        if next_index <= cursor:
            return False
        if _terminates(text, node):
            return True
        cursor = next_index


def dead_ranges(body, macros=None):
    """函数体内永远执行不到的区间 `[(start, end)]`；位置是函数体文本下标。

    两趟：第一趟只找出**本身可达**的 `goto` 目标标签，第二趟才允许这些标签恢复
    可达。否则 `while (true) { } goto install; ... install: hook();`
    这种「跳转语句自己都执行不到」的写法会被误判成可达。
    """
    macros = macros or {}
    inner_start = body.find("{")
    inner_end = body.rfind("}")
    if inner_start < 0 or inner_end <= inner_start:
        return []
    live_labels = set()
    _walk_block(body, inner_start + 1, inner_end, True, macros, [], set(), live_labels)
    ranges = []
    _walk_block(body, inner_start + 1, inner_end, True, macros, ranges, live_labels)
    return _merge_ranges(ranges)


def _merge_ranges(ranges):
    merged = []
    for start, end in sorted(ranges):
        if merged and start <= merged[-1][1]:
            merged[-1] = (merged[-1][0], max(merged[-1][1], end))
        else:
            merged.append((start, end))
    return merged


def _walk_block(body, start, end, reachable, macros, ranges, labels=None, collect=None):
    labels = {} if labels is None else labels
    labels = set(labels)
    cursor = start
    while True:
        cursor = _skip_ws_to(body, cursor, end)
        if cursor >= end:
            return
        node_start = cursor
        kind, data, next_index = _parse_statement(body, cursor, end)
        if next_index <= cursor:
            return
        if not reachable:
            # `goto` 之后不可达，但被跳转到的标签处要恢复可达：否则
            # `goto install; ... install: hook_func(...)` 这种真实接线会被误判。
            if kind == "label" and data is not None and data in labels:
                reachable = True
            else:
                ranges.append((node_start, min(next_index, end)))
                cursor = next_index
                continue
        if kind == "block":
            _walk_block(body, data[0], data[1], True, macros, ranges, labels, collect)
            if _block_terminates(body, data[0], data[1]):
                reachable = False
        elif kind == "if":
            condition, then_span, else_span = data
            value = constant_value(condition, macros)
            if value is None:
                value = short_circuit_value(condition)
            _walk_span(body, then_span, value != 0, macros, ranges)
            if else_span is not None:
                _walk_span(body, else_span, value != 1, macros, ranges)
            if value in (0, 1):
                # 常量条件下只可能走被选中的那一支：它必然跳转时，其后的代码
                # 一定执行不到，与另一支是否可以结束无关。
                taken = then_span if value == 1 else else_span
                if _terminates(body, taken):
                    reachable = False
        elif kind in ("while", "for", "do"):
            condition, body_span, has_break = data
            if kind == "do":
                value = None
            elif kind == "for" and condition.strip() == "":
                value = 1
            else:
                value = constant_value(condition, macros)
                if value is None:
                    value = short_circuit_value(condition)
            # `do { ... } while (false)` 只是把一组语句包成一个块，块体必然执行一次；
            # 它不会让后面的代码不可达，也不能把块体判成不可达。
            _walk_span(body, body_span, value != 0 or kind == "do", macros, ranges)
            if value == 1 and not has_break:
                reachable = False
        elif kind == "switch":
            condition, body_span = data
            value = constant_value(condition, macros)
            _walk_switch(body, body_span, value, macros, ranges)
        elif kind == "jump":
            if data == "goto" and collect is not None:
                target = re.match(r"\s*goto\s+([A-Za-z_]\w*)", body[node_start:next_index])
                if target is not None:
                    collect.add(target.group(1))
            reachable = False
        elif kind == "label":
            if data is not None and data in labels:
                reachable = True
        cursor = next_index


def _walk_span(body, span, reachable, macros, ranges, labels=None):
    if span is None:
        return
    start, end, is_block = span
    if is_block:
        _walk_block(body, start, end, reachable, macros, ranges, labels)
    elif not reachable:
        ranges.append((start, end))
    else:
        _walk_block(body, start, end, True, macros, ranges, labels)


def _walk_switch(body, span, value, macros, ranges):
    """常量 switch：与常量不匹配的 case 段落不可达；无 default 且无匹配时整体不可达。"""
    start, end, _is_block = span
    sections = []
    cursor = start
    current = None
    while True:
        cursor = _skip_ws_to(body, cursor, end)
        if cursor >= end:
            break
        kind, data, next_index = _parse_statement(body, cursor, end)
        if next_index <= cursor:
            break
        if kind in ("case", "default"):
            sections.append([kind, data, cursor, end])
            current = sections[-1]
        elif current is not None:
            current[3] = next_index
        cursor = next_index
    if value is None:
        for _kind, _data, section_start, section_end in sections:
            _walk_block(body, section_start, section_end, True, macros, ranges)
        if not sections:
            _walk_block(body, start, end, True, macros, ranges)
        return
    matched = False
    for kind, data, section_start, section_end in sections:
        if kind == "default":
            matched = True
            _walk_block(body, section_start, section_end, True, macros, ranges)
            continue
        label = constant_value(data, macros)
        if label is None or label == value:
            # 标签无法静态求值（例如含三元运算符）时保守按可达处理：宁可漏判
            # 「这段不可达」，也不能把真实接线误判成死代码。
            matched = True
            _walk_block(body, section_start, section_end, True, macros, ranges)
        else:
            ranges.append((section_start, section_end))
    if not sections:
        _walk_block(body, start, end, False, macros, ranges)
    elif not matched:
        ranges.append((start, end))


def unreachable_at(body, dead, position):
    """位置是否永远执行不到：恒假分支、常量循环、恒真 if 的 else、跳转之后或未调用 lambda 内。"""
    if inside_uninvoked_lambda(body, position):
        return True
    for start, end in dead:
        if start <= position < end:
            return True
    return False


def function_spans(code):
    """返回 `[(name, body, start, end)]`；只认带花括号体、且不是控制语句的函数定义。"""
    spans = []
    pattern = re.compile(
        r"(?m)^[^\n{};#]*?\b([A-Za-z_]\w*)\s*\(([^;{}()]*)\)\s*(?:const\s*)?(?:noexcept\s*)?\{")
    for match in pattern.finditer(code):
        name = match.group(1)
        if name in CPP_KEYWORDS:
            continue
        body = block_at(code, match.end() - 1, name)
        start = match.end() - 1
        spans.append((name, body, start, start + len(body)))
    return spans


def reachable_functions(spans, entry_names, macros=None):
    """从导出入口做调用图搜索，只统计本文件内定义的函数。

    只跟随**调用点自身可达**的调用：写在 `if (0)` / `if constexpr (false)` /
    宏恒假条件 / 无条件跳转之后 / 未被调用的 lambda 里的调用不产生可达性，
    否则「入口什么都不做，只在一个死分支里写一句调用」又会翻绿。
    """
    macros = macros or {}
    by_name = {}
    for name, body, _start, _end in spans:
        by_name.setdefault(name, body)
    dead = {name: dead_ranges(body, macros) for name, body in by_name.items()}
    reachable = set()
    pending = [name for name in by_name if name in entry_names or name.startswith("Java_")]
    while pending:
        name = pending.pop()
        if name in reachable:
            continue
        reachable.add(name)
        for match in re.finditer(r"\b([A-Za-z_]\w*)\s*\(", by_name[name]):
            callee = match.group(1)
            if callee not in by_name or callee in reachable:
                continue
            if unreachable_at(by_name[name], dead[name], match.start()):
                continue
            pending.append(callee)
    return reachable, by_name


def hook_call_sites(text):
    """所有可能触发安装的调用点：直接调用 hook_func，以及先取别名再调用。"""
    # 允许 `entries->hook_func(...)` 与 `(entries->hook_func)(...)` 两种调用形态。
    direct = [match.start()
              for match in re.finditer(r"(?:->|\.)\s*hook_func\s*\)?\s*\(", text)]
    aliases = set(re.findall(r"\b(\w+)\s*=\s*[^;=]*\bhook_func\b[^;]*;", text))
    aliased = [match.start() for name in aliases
               for match in re.finditer(r"\b" + re.escape(name) + r"\s*\(", text)]
    return direct, sorted(set(aliased))


def split_fields(text):
    """按顶层逗号切分花括号初始化列表，忽略括号/花括号内的逗号。"""
    fields = []
    depth = 0
    start = 0
    for index, char in enumerate(text):
        if char in "([{":
            depth += 1
        elif char in ")]}":
            depth -= 1
        elif char == "," and depth == 0:
            fields.append(text[start:index])
            start = index + 1
    tail = text[start:]
    if tail.strip():
        fields.append(tail)
    return fields


# 恒定条件：`x || true` / `true || x` 让早退恒成立或恒不成立，`x && false` / `false && x`
# 让早退永远不触发。左右两侧的常量都要认。
CONSTANT_CONDITION = re.compile(
    r"(?:\|\|\s*(?:true|1)\b)|(?:(?:true|1)\s*\|\|)|"
    r"(?:&&\s*(?:false|0)\b)|(?:(?:false|0)\s*&&)")

# 门禁必须「非零（出错）就早退」。接受 `errors(...) != 0` 与直接把非零当真的
# `errors(...)`；拒绝 `== 0`、`!errors(...)`，以及被额外条件削弱的写法——
# 这些统一由 condition_implies_unsafe 做符号化蕴含判定。

# 把「被替换成 UNSAFE 的那个表达式」与 0/false/true 的比较归一成布尔取反关系。
UNSAFE_REWRITES = (
    (r"\bUNSAFE\s*!=\s*(?:0|false)[uUlL]*\b", "UNSAFE"),
    (r"\b(?:0|false)[uUlL]*\s*!=\s*UNSAFE\b", "UNSAFE"),
    (r"\bUNSAFE\s*==\s*(?:0|false)[uUlL]*\b", "!UNSAFE"),
    (r"\b(?:0|false)[uUlL]*\s*==\s*UNSAFE\b", "!UNSAFE"),
    (r"\bUNSAFE\s*!=\s*(?:1|true)[uUlL]*\b", "!UNSAFE"),
    (r"\b(?:1|true)[uUlL]*\s*!=\s*UNSAFE\b", "!UNSAFE"),
    (r"\bUNSAFE\s*==\s*(?:1|true)[uUlL]*\b", "UNSAFE"),
    (r"\b(?:1|true)[uUlL]*\s*==\s*UNSAFE\b", "UNSAFE"),
    (r"\bUNSAFE\s*>=\s*1[uUlL]*\b", "UNSAFE"),
    (r"\bUNSAFE\s*>\s*0[uUlL]*\b", "UNSAFE"),
    (r"\bUNSAFE\s*<=\s*0[uUlL]*\b", "!UNSAFE"),
    (r"\bUNSAFE\s*<\s*1[uUlL]*\b", "!UNSAFE"),
)


def symbolise_call(text, name):
    """把 `name(...)`（括号配平）整体替换成 `UNSAFE`；返回 (新文本, 次数)。"""
    pieces = []
    cursor = 0
    replaced = 0
    for match in re.finditer(r"(?:[A-Za-z_]\w*::)*" + re.escape(name) + r"\s*\(", text):
        if match.start() < cursor:
            continue
        open_paren = text.find("(", match.start())
        close = _match_bracket(text, open_paren, len(text), "(", ")")
        if close < 0:
            continue
        pieces.append(text[cursor:match.start()])
        pieces.append("UNSAFE")
        cursor = close + 1
        replaced += 1
    pieces.append(text[cursor:])
    return "".join(pieces), replaced


class _ConditionSite:
    """只借 `re.Match` 的 `.start()` 接口传递 `if (...)` 的位置。

    守卫判定改成遍历 `if_conditions` 后不再有 `re.Match` 对象，但下游仍按
    `controlled.start()` 读取位置，用这个薄包装避免大范围改动。
    """

    def __init__(self, position):
        self._position = position

    def start(self):
        return self._position


def if_conditions(body):
    """函数体内所有 `if (...)` 的 `(位置, 条件文本)`。"""
    found = []
    for match in re.finditer(r"\bif\b\s*\(", body):
        condition = condition_text(body, match.start())
        if condition is not None:
            found.append((match.start(), condition))
    return found


def condition_implies_unsafe(condition, expression=None, unsafe_when_zero=False,
                             macros=None, call_name=None):
    """条件为真是否被「不安全状态」蕴含（不安全时一定早退）。

    做法是把不安全谓词替换成布尔符号 `UNSAFE`（`call_name(...)` 整体替换，
    或用 `expression` 正则匹配的片段替换），把与 0/false/true 的比较归一成
    布尔取反，再对剩下的未知原子穷举真值表：只要存在一种「不安全成立、条件
    却是假」的赋值，就不算早退。

    `unsafe_when_zero` 用于 `install_permitted(...)` 这类「返回 0 才不安全」的
    谓词；门禁类 `xxx_errors(...)` 则是「非零才不安全」。

    返回 True/False；无法解析时返回 None，由调用方保守拒绝。
    """
    if not condition:
        return None
    if call_name is not None:
        rewritten, replaced = symbolise_call(condition, call_name)
    elif expression:
        rewritten, replaced = re.subn(expression, "UNSAFE", condition)
    else:
        return None
    if not replaced:
        return None
    for pattern, replacement in UNSAFE_REWRITES:
        rewritten = re.sub(pattern, replacement, rewritten)
    tokens = _bool_tokens(rewritten)
    if not tokens:
        return None
    ast = _parse_bool_expr(tokens)
    if ast is None:
        return None
    atoms = set()
    _bool_atoms(ast, atoms)
    if "UNSAFE" not in atoms:
        return None
    unknown = sorted(atoms - {"UNSAFE"})
    if len(unknown) > 8:
        return None
    target = 0 if unsafe_when_zero else 1
    for combination in itertools.product((0, 1), repeat=len(unknown)):
        env = dict(zip(unknown, combination))
        env["UNSAFE"] = target
        if _eval_bool(ast, env) != 1:
            return False
    return True


def references_commit(expression, variable):
    """字段表达式是否引用了提交结果变量，且没有自填布尔常量冒充安全证据。"""
    return (re.search(r"\bsafety_evidence\s*\([^;]*\b" + variable + r"\b", expression) is not None
            and re.search(r"\btrue\b|\bfalse\b", expression) is None)


def call_arguments(text, call_at):
    """返回位置 `call_at` 起始的调用括号内参数列表；无法定位时返回 None。"""
    open_paren = text.find("(", call_at)
    if open_paren < 0:
        return None
    depth = 0
    for index in range(open_paren, len(text)):
        if text[index] == "(":
            depth += 1
        elif text[index] == ")":
            depth -= 1
            if depth == 0:
                return text[open_paren + 1:index]
    return None


def condition_text(text, if_at):
    """返回 `if (...)` 里括号配平的条件文本；无法定位时返回 None。"""
    open_paren = text.find("(", if_at)
    if open_paren < 0:
        return None
    depth = 0
    for index in range(open_paren, len(text)):
        if text[index] == "(":
            depth += 1
        elif text[index] == ")":
            depth -= 1
            if depth == 0:
                return text[open_paren + 1:index]
    return None


LAMBDA_INTRO = re.compile(
    r"\[[^\]\n]*\]\s*\([^)\n]*\)\s*(?:mutable\s*)?(?:->\s*[\w:<>,\s*&]+)?\{")


def lambda_spans(body):
    """返回本函数体内每个 lambda 的 (body_start, body_end, invoked)。"""
    spans = []
    for match in LAMBDA_INTRO.finditer(body):
        open_brace = match.end() - 1
        depth = 0
        close_brace = None
        for index in range(open_brace, len(body)):
            if body[index] == "{":
                depth += 1
            elif body[index] == "}":
                depth -= 1
                if depth == 0:
                    close_brace = index
                    break
        if close_brace is None:
            continue
        cursor = close_brace + 1
        while cursor < len(body) and body[cursor].isspace():
            cursor += 1
        invoked = cursor < len(body) and body[cursor] == "("
        spans.append((open_brace, close_brace, invoked))
    return spans


def inside_uninvoked_lambda(body, position):
    """位置是否落在未被立即调用的 lambda 体内（即永远不会执行到）。"""
    for start, end, invoked in lambda_spans(body):
        if start <= position <= end and not invoked:
            return True
    return False


def install_inside_guard_consequent(text, permit_at, hook_at):
    """hook 调用是否落在「安装许可判定」的真分支里。

    例如把安装写进「许可失败」的真分支（`if (!permit) { hook(); return; }`），
    安装点虽在许可之后，却在**错误的分支**里，必须拒绝。

    只针对许可判定那条 `if` 的**真分支**：外层把许可判定与安装一起包进
    `do { ... } while (false)`、单独一层 `{ ... }` 之类的普通块是正常写法，
    不能因为「同处一个顶层语句」就拒绝——那种判据会把真实接线误判成未接线。
    """
    if hook_at is None:
        return False
    span = if_consequent_span(text, permit_at)
    if span is None:
        return False
    start, end, _braced = span
    return start <= hook_at < end


def goto_target_position(body, node_text):
    """`goto 名字;` 的目标标签在函数体里的位置；找不到返回 None。"""
    match = re.match(r"\s*goto\s+([A-Za-z_]\w*)", node_text)
    if match is None:
        return None
    label = match.group(1)
    for found in re.finditer(r"(?<![\w:])" + re.escape(label) + r"\s*:(?!:)", body):
        return found.start()
    return None


def enclosing_containers(body, position):
    """包含 position 的所有循环 / `switch` 块体区间 `(start, end)`。"""
    found = []
    _collect_enclosing(body, body.find("{") + 1, body.rfind("}"), position, found)
    return found


def _collect_enclosing(body, start, end, position, found):
    cursor = start
    while True:
        cursor = _skip_ws_to(body, cursor, end)
        if cursor >= end:
            return
        kind, data, next_index = _parse_statement(body, cursor, end)
        if next_index <= cursor:
            return
        if kind in ("while", "for", "do", "switch"):
            body_span = data[1]
            bstart, bend, _is_block = body_span
            if bstart <= position < bend:
                found.append((bstart, bend))
                _collect_enclosing(body, bstart, bend, position, found)
                return
        elif kind == "if":
            _condition, then_span, else_span = data
            for span in (then_span, else_span):
                if span is None:
                    continue
                s, e, _is_block = span
                if s <= position < e:
                    _collect_enclosing(body, s, e, position, found)
                    return
        elif kind == "block":
            if data[0] <= position < data[1]:
                _collect_enclosing(body, data[0], data[1], position, found)
                return
        cursor = next_index


def _control_regions(body):
    """函数体内所有可能「有条件执行」的语句区间：`[(start, end, 种类, 必然执行)]`。

    `必然执行` 为真表示该区间里的语句在该语句被到达时一定会执行：
    常量条件选中的那一支、无 `break` 的恒真循环块体、`do` 循环的块体。
    无法静态判定的条件一律按「不必然」处理（保守一侧）。
    """
    regions = []
    _collect_regions(body, body.find("{") + 1, body.rfind("}"), regions)
    return regions


def _collect_regions(body, start, end, regions):
    cursor = start
    while True:
        cursor = _skip_ws_to(body, cursor, end)
        if cursor >= end:
            return
        kind, data, next_index = _parse_statement(body, cursor, end)
        if next_index <= cursor:
            return
        if kind == "if":
            condition, then_span, else_span = data
            value = constant_value(condition)
            if value is None:
                value = short_circuit_value(condition)
            for span, guaranteed in ((then_span, value == 1), (else_span, value == 0)):
                if span is None:
                    continue
                s, e, is_block = span
                regions.append((s, e, "条件分支", bool(guaranteed)))
                if is_block:
                    _collect_regions(body, s, e, regions)
        elif kind in ("while", "for", "do"):
            condition, body_span, has_break = data
            if kind == "do":
                # `do` 的块体至少执行一次，无论后续条件如何。
                guaranteed = True
            else:
                if kind == "for" and condition.strip() == "":
                    value = 1
                else:
                    value = constant_value(condition)
                    if value is None:
                        value = short_circuit_value(condition)
                # 恒真且本层没有 break 的循环块体必然执行；有界循环则不一定。
                guaranteed = value == 1 and not has_break
            s, e, is_block = body_span
            regions.append((s, e, "循环块体", bool(guaranteed)))
            if is_block:
                _collect_regions(body, s, e, regions)
        elif kind == "switch":
            condition, body_span = data
            s, e, is_block = body_span
            if is_block:
                value = constant_value(condition)
                if value is None:
                    value = short_circuit_value(condition)
                sections = _switch_sections(body, s, e)
                matched = False
                if value is not None:
                    matched = any(kind_text != "default"
                                  and constant_value(label) == value
                                  for _s, _e, kind_text, label in sections)
                for section_start, section_end, kind_text, label in sections:
                    # 常量 switch 里只有「唯一会拿到控制权的那一段」是必然执行；
                    # 其余段落要么不会执行，要么只能靠 fallthrough 到达。
                    guaranteed = False
                    if value is not None:
                        if kind_text == "default":
                            guaranteed = not matched
                        else:
                            guaranteed = constant_value(label) == value
                    regions.append((section_start, section_end, "switch 段落",
                                    bool(guaranteed)))
                _collect_regions(body, s, e, regions)
        elif kind == "block":
            _collect_regions(body, data[0], data[1], regions)
        cursor = next_index


def _switch_sections(body, start, end):
    """`[(段落起点, 段落终点, 种类, 标签)]`：每个 `case` / `default` 到下一个标签之间。"""
    sections = []
    cursor = start
    while True:
        cursor = _skip_ws_to(body, cursor, end)
        if cursor >= end:
            break
        kind, data, next_index = _parse_statement(body, cursor, end)
        if next_index <= cursor:
            break
        if kind in ("case", "default"):
            sections.append([cursor, end, kind, data])
        elif sections:
            sections[-1][1] = next_index
        cursor = next_index
    return [tuple(section) for section in sections]


def dominates(body, anchor_at, target_at):
    """`anchor_at` 是否**无条件**先于 `target_at` 执行。

    只要锚点落在某个「不一定执行」的条件区间里、而目标在那个区间之外，
    就存在一条不经过锚点也能到达目标的路径，锚点不构成支配。
    """
    # 锚点或目标不存在（没找到安装点、安装点不可达）时不能被当成「支配成立」，
    # 否则调用方拿 None 去比较会崩，或者把缺失环节当成已核验通过。
    if anchor_at is None or target_at is None:
        return False
    if anchor_at > target_at:
        return False
    for start, end, _kind, guaranteed in _control_regions(body):
        if start <= anchor_at < end and not (start <= target_at < end) and not guaranteed:
            return False
    if jumped_over(body, anchor_at, target_at):
        return False
    return True


def unconditionally_reached(body, position):
    """位置是否落在「必然执行」的区域内（不在任何有条件的区间里）。"""
    return all(guaranteed for start, end, _kind, guaranteed in _control_regions(body)
               if start <= position < end)


def result_overwritten(body, start, end, variable):
    """提交结果变量在 `[start, end)` 内是否被重新赋值（含成员赋值）。

    `const auto result = commit_safety_startup_once(...)` 之后若能再写
    `result.committed = true;`（或整体重赋值、`reset()`），守卫读到的就不是提交
    的真实结果，等于把「提交失败放行」伪装成有守卫。
    """
    piece = body[start:end]
    name = re.escape(variable)
    # 只认「写入」形态：成员赋值、整体赋值、以及明文的重置方法。
    # 不把 `result.safety_ready()` 这类只读调用当写入，避免把正例判成假绿。
    for pattern in (r"(?<![\w.])" + name + r"\s*\.\s*\w+\s*=(?!=)",
                    r"(?<![\w.])" + name + r"\s*=(?!=)",
                    r"(?<![\w.])" + name + r"\s*\.\s*(?:reset|clear|assign)\s*\("):
        if re.search(pattern, piece) is not None:
            return True
    return False


def live_abort_in_consequent(body, dead, if_at, hook_at):
    """真分支是否**必然**以可达、且真的跳过安装的早退结束。

    三个都不够的宽松写法各有反例：

      * 只断言「真分支文本里出现过 return」：`if (errors) { if (0) return 1; }`
        里的 return 永远执行不到，等于没有早退；
      * 只断言「存在一条可达早退」：`if (errors) { if (revision > 0) return 1; }`
        在条件不成立时会继续往下走到安装，等于不安全时放行；
      * 把任何 `break`/`goto` 都当早退：函数体里一条裸 `break`（不在于任何
        循环 / `switch` 里）或 `goto` 跳到安装之前的标签，都不会让执行离开
        安装路径。

    因此要求：真分支自身**必然终止**；跳转文本本身可达；且跳转真的跳过安装：
    `return` 直接成立；`break` 必须处在一个同时包含安装调用的循环 / `switch` 里；
    `goto` 的目标标签必须在安装调用**之后**。无花括号单语句分支同样处理。
    """
    span = if_consequent_span(body, if_at)
    if span is None:
        return False
    start, end, braced = span
    if braced:
        # 花括号分支：要求整个块必然终止，而不是「块里某一条语句是 return」。
        if not _block_terminates(body, start, end):
            return False
    jump = re.search(r"\b(?:return|throw|break|goto)\b", body[start:end])
    if jump is None or unreachable_at(body, dead, start + jump.start()):
        return False
    keyword = jump.group(0)
    if keyword in ("return", "throw"):
        return True
    if keyword == "break":
        if hook_at is None:
            return False
        return any(span_start <= hook_at < span_end
                   for span_start, span_end in enclosing_containers(body, start + jump.start()))
    if keyword == "goto":
        if hook_at is None:
            return False
        target = goto_target_position(body, body[start + jump.start():end])
        return target is not None and target > hook_at
    return False


def if_consequent_span(text, if_at):
    """真分支的 `(start, end, braced)`；`braced` 表示分支是否带花括号块。"""
    open_paren = text.find("(", if_at)
    if open_paren < 0:
        return None
    close_paren = _match_bracket(text, open_paren, len(text), "(", ")")
    if close_paren < 0:
        return None
    cursor = _skip_ws_to(text, close_paren + 1, len(text))
    if cursor >= len(text):
        return None
    if text[cursor] == "{":
        close = _match_bracket(text, cursor, len(text), "{", "}")
        return (cursor + 1, close, True) if close > 0 else None
    end = text.find(";", cursor)
    return (cursor, end + 1, False) if end >= 0 else None


def jumped_over(body, anchor_at, target_at):
    """是否存在一条 `goto` 绕过 `anchor_at`，落到锚点之后的代码。

    门禁写成 `if (rev) goto skip; if (errors(...)) return 1; skip: commit(...)`
    时，锚点位置仍在目标之前、也不在任何条件区间里，`dominates` 会误判成
    「支配成立」。这里补一层：**函数体内任意位置**的 `goto`，只要它自身可达、
    出现在锚点之前，而目标标签落在锚点之后，就说明存在一条不经过锚点也能
    到达锚点之后代码的路径。

    `goto` 自己位于锚点之后时不算绕过：执行到它必须已经先经过锚点，属于
    「锚点先执行、再按结果跳过安装」的合法早退（例如
    `if (!permit) goto denied; ... hook(); denied:`）。跳转目标落在锚点之后、
    但已经越过 `target_at` 时也不算绕过——那条路径根本不经过目标，属于
    fail-closed 的提前退出，目标是否可达由 `dead_ranges` 单独判定。

    旧实现只扫描 `[anchor_at, target_at)` 区间、并要求目标 `>= target_at`，
    于是「锚点之前一条 `if (rev) goto 锚点之后;`」可以整条绕过门禁、提交或
    安装许可而不被察觉，属于假绿路径；本轮把扫描范围扩到整个函数体，并把
    判据改成「跳转点在锚点之前、目标在锚点之后」。
    """
    if anchor_at is None or target_at is None or anchor_at >= target_at:
        return False
    dead = dead_ranges(body)
    for found in re.finditer(r"\bgoto\s+([A-Za-z_]\w*)\s*;", body):
        position = found.start()
        # 自己执行不到的 `goto`（恒假分支里、无条件跳转之后）不构成绕过路径。
        if unreachable_at(body, dead, position):
            continue
        # 跳转点已在锚点之后：执行到它必然先经过锚点，属于合法早退。
        if position >= anchor_at:
            continue
        target = goto_target_position(body, found.group(0))
        if target is not None and anchor_at < target <= target_at:
            return True
    return False

def if_consequent(text, if_at):
    """`if (...)` 真分支的文本；无花括号的单语句分支返回该语句文本。

    这是 `if_consequent_span` 的文本视图，供需要直接看分支内容的判据使用。
    """
    span = if_consequent_span(text, if_at)
    if span is None:
        return None
    start, end, _braced = span
    return text[start:end]


INSTALL_PLAN_GATE = re.compile(
    r"(?:resolve_launcher_install_plan\s*\(([^()]*)\)|([A-Za-z_]\w*)\s*\.\s*ready\s*\(\s*\))")


def verify_native_wiring(contract, sources, enforce_contract=True):
    """完整链路验证器：只认从导出入口可达、且同一函数体内逐项成立的证据。

    要求（缺任一项都不翻绿）：

      * `entry_reachable`：提交后端调用从导出入口（`native_init` / `JNI_OnLoad` /
        `Java_*`）可达，且提交调用自身可达（不在恒假分支、无条件跳转之后或未被
        调用的 lambda 里）；
      * `snapshot_gated`：快照**用途**校验（`ordinary_snapshot_errors`）在提交之前、
        自身可达、支配提交点，校验的每一处都必须是即将提交的那个快照对象，条件是
        「非零（出错）就早退」的正确极性，且早退落在必然可达的真分支里；
      * `commit_result_controlled`：提交结果进入早退控制流——守卫自身可达、被提交点
        支配、又支配安装点，提交结果在守卫之前没有被改写，守卫极性正确；
      * `failure_blocks_install`：提交失败在真分支里**真的**早退，而不是空分支或
        从 `else` 继续；
      * `install_dominated`：安装许可证据来自提交结果（不得自填 `true` 冒充），
        许可判定自身进入真分支早退、且支配安装调用；
      * `install_plan_gated`：安装许可之后，对安装点同一计划对象执行
        `resolve_launcher_install_plan` / `plan.ready` 非空校验；校验支配唯一
        安装点且失败必然早退，拒绝极性反转、常量短路或运行期条件包裹；
      * `install_path_present`：安装调用严格位于许可判定之后，自身可达、落在必然
        执行的位置，且不落在许可判定的真分支内部；
      * `install_path_exclusive`：全文只有一处直接安装点，且不存在「先取别名再调用」
        的第二处。

    合同声明的接线状态必须与实测一致，否则硬失败。它是静态文本 / 结构绊线，
    不是编译器或运行期证明。当前八项链路实测成立，计划三段来源已接线并由同一
    非空计划校验收敛；loader 解析失败仍 fail-closed。普通构建的实验安装开关保持关闭，
    静态防砖确认不等于安装许可或关闭门槛 1/3/5。
    """
    wiring = contract["nativeWiring"]
    required = baseline.NATIVE_WIRING_BASELINE
    raw = sources["native_entry"]
    # 宏与 `#include` 只存在于预处理指令行，`code_only` 会把它们删掉，因此从原文取。
    macros = macro_constants(raw)
    includes = set(re.findall(r'#include\s+"([^"]+)"', raw))
    entry = code_only(raw)
    symbols = required["required_entry_symbols"]
    commit = required["required_commit_symbol"]
    spans = function_spans(entry)
    reachable, _by_name = reachable_functions(spans, {"native_init", "JNI_OnLoad"}, macros)
    calls = [symbol for symbol in symbols if re.search(r"\b" + re.escape(symbol) + r"\s*\(", entry)]
    links = {name: False for name in required["required_links"]}
    direct_sites, aliased_sites = hook_call_sites(entry)
    for name, body, _start, _end in spans:
        if name not in reachable:
            continue
        found = re.search(r"\b" + re.escape(commit) + r"\s*\(", body)
        if found is None:
            continue
        commit_at = found.start()
        dead = dead_ranges(body, macros)
        # 安装点位置在这里就定位：早退是否真的跳过安装，要靠它判断。
        hook_at = None
        for match in re.finditer(r"(?:->|\.)\s*hook_func\s*\)?\s*\(", body):
            hook_at = match.start()
            break
        # 提交调用本身必须可达：写在 if (0) / while (0) 里或无条件跳转之后都不算。
        links["entry_reachable"] = not unreachable_at(body, dead, commit_at)
        if not links["entry_reachable"]:
            break
        # 提交调用实际收到的对象标识符；门禁必须校验其中即将被提交的快照。
        commit_arguments = call_arguments(body, commit_at) or ""
        submit_objects = set(re.findall(r"\b([A-Za-z_]\w*)\b",
                                        commit_arguments.replace("::", " ")))
        # 快照门禁必须在提交之前，并且：
        #   * 用**用途校验**（ordinary_snapshot_errors，拒绝恢复令牌），不是底层形态校验；
        #   * 校验收到的快照与即将提交的是**同一个对象**（防止拿无关/未初始化快照敷衍）；
        #   * 条件是「非零（出错）就早退」的极性，且不是 `0 && ...` 之类的恒定条件；
        #   * 早退确实落在必然可达的真分支里。
        gate_ok = False
        for gate_at, condition in if_conditions(body):
            if gate_at >= commit_at or "ordinary_snapshot_errors" not in condition:
                continue
            if unreachable_at(body, dead, gate_at):
                continue
            if not dominates(body, gate_at, commit_at):
                continue
            arguments = [call_arguments(condition, match.start())
                         for match in re.finditer(r"\bordinary_snapshot_errors\s*\(", condition)]
            checked = [(piece or "").split(",")[0].strip() for piece in arguments]
            # 条件里每一处用途校验都必须校验**即将提交的那个快照**。
            if not checked or any(one not in submit_objects for one in checked):
                continue
            if constant_condition(condition, macros):
                continue
            if condition_implies_unsafe(condition, call_name="ordinary_snapshot_errors",
                                        macros=macros) is not True:
                continue
            if not live_abort_in_consequent(body, dead, gate_at, hook_at):
                continue
            gate_ok = True
            break
        links["snapshot_gated"] = gate_ok
        assigned = re.search(r"(?:const\s+)?(?:auto|[\w:]+(?:\s*[&*])?)\s+(\w+)\s*=\s*"
                             r"(?:[\w:]+::)?" + re.escape(commit) + r"\s*\(", body)
        if assigned is None:
            break
        variable = re.escape(assigned.group(1))
        guard = re.compile(
            r"(?<![:\w.])!\s*" + variable
            + r"\s*\.\s*(?:committed\b|safety_ready\s*\(\s*\))"
            + r"|(?<![:\w.])!\s*\(\s*" + variable
            + r"\s*\.\s*(?:committed\b|safety_ready\s*\(\s*\))\s*\)"
            + r"|(?<![!\w.])" + variable
            + r"\s*\.\s*committed\s*(?:==\s*false\b|!=\s*true\b)"
            + r"|(?<![!\w.])(?:false\s*==|true\s*!=)\s*" + variable
            + r"\s*\.\s*committed\b")
        # 提交结果守卫自身必须可达、必须被提交点支配；若函数里能找到安装点，
        # 还要求守卫支配安装点，否则守卫可能落在安装之后，形同没拦。
        controlled = None
        controlled_ok = False
        for if_at, condition in if_conditions(body):
            if guard.search(condition) is None:
                continue
            if if_at <= commit_at or unreachable_at(body, dead, if_at):
                continue
            if not dominates(body, commit_at, if_at):
                continue
            if not dominates(body, if_at, hook_at):
                continue
            if result_overwritten(body, commit_at, if_at, variable):
                continue
            if constant_condition(condition, macros):
                continue
            if condition_implies_unsafe(condition, expression=guard.pattern) is not True:
                continue
            controlled = _ConditionSite(if_at)
            controlled_ok = True
            break
        links["commit_result_controlled"] = controlled_ok
        # 失败必须**在真分支里早退**：写成 `if (!committed) {} else return;`
        # 会把「提交失败继续执行」伪装成早退。
        failure_ok = False
        if controlled is not None and controlled.start() > commit_at:
            failure_ok = live_abort_in_consequent(body, dead, controlled.start(), hook_at)
        links["failure_blocks_install"] = failure_ok
        # 安装许可证据必须来自提交结果，不得自填 true 冒充：
        #   * 花括号按位初始化时，引用提交结果的字段必须恰好是第 3 个（safety_verified）；
        #   * 指定初始化或赋值写法时，`safety_verified` 必须引用提交结果变量。
        # 两种写法都保留，避免把“证据来自提交结果”变成无法满足的死条件。
        evidence_ok = False
        initializer = re.search(r"PermitEvidence\s+\w+\s*\{(.*?)\}\s*;", body, re.S)
        if initializer is not None:
            piece = initializer.group(1)
            designated = re.search(r"\.\s*safety_verified\s*=\s*([^,}]*)", piece)
            if designated is not None:
                evidence_ok = references_commit(designated.group(1), variable)
            else:
                fields = split_fields(piece)
                evidence_ok = len(fields) == 4 and references_commit(fields[2], variable)
        if not evidence_ok:
            assignment = re.search(r"\.\s*safety_verified\s*=\s*([^;]*);", body)
            if assignment is not None:
                evidence_ok = references_commit(assignment.group(1), variable)
        # 证据字段在初始化之后再被赋值（`evidence.safety_verified = true;`）会让
        # 前面那处引用提交结果的初始化形同装饰：后面这处赋值才是真正生效的值。
        # 只在「引用提交结果的初始化」之后才追究，避免把指定初始化写法误杀。
        if evidence_ok and initializer is not None:
            for later in re.finditer(
                    r"(?<![\w.])([A-Za-z_]\w*)\s*\.\s*safety_verified\s*=\s*([^;]*);",
                    body[initializer.end():]):
                if references_commit(later.group(2), variable):
                    continue
                evidence_ok = False
                break
        # 安装许可早退必须落在 `if (!install_permitted(...))` 的**真分支**里：
        # 写成 `... {} else return;` 会让「不安全」时反而继续走到安装，形同放行。
        install_guard = None
        install_ok = False
        for if_at, condition in if_conditions(body):
            if if_at <= commit_at or unreachable_at(body, dead, if_at):
                continue
            if "install_permitted" not in condition:
                continue
            if not dominates(body, if_at, hook_at):
                continue
            if constant_condition(condition, macros):
                continue
            if condition_implies_unsafe(condition, call_name="install_permitted",
                                        unsafe_when_zero=True, macros=macros) is not True:
                continue
            if not live_abort_in_consequent(body, dead, if_at, hook_at):
                continue
            install_guard = _ConditionSite(if_at)
            install_ok = True
            break
        links["install_dominated"] = evidence_ok and install_ok
        # 计划校验必须位于许可之后、支配安装点，且检查安装点实际使用的同一对象。
        plan_ok = False
        # 仅为接收者提取归一已有的 `(plan.hook_func)(...)` 等价调用形态；
        # 支配与可达性仍按原文位置判断。
        receiver_text = re.sub(r"(\.\s*hook_func)\s*\)\s*\(", r"\1(", body)
        receiver = re.search(r"(?P<recv>[A-Za-z_]\w*)\s*\.\s*hook_func\s*\(", receiver_text)
        if hook_at is not None and receiver is not None:
            for if_at, cond in if_conditions(body):
                gate = INSTALL_PLAN_GATE.search(cond)
                if gate is None or unreachable_at(body, dead, if_at):
                    continue
                if install_guard is None or not dominates(body, install_guard.start(), if_at):
                    continue
                if not dominates(body, if_at, hook_at):
                    continue
                if constant_condition(cond, macros):
                    continue
                if condition_implies_unsafe(
                        cond, expression=INSTALL_PLAN_GATE.pattern,
                        unsafe_when_zero=True, macros=macros) is not True:
                    continue
                if not live_abort_in_consequent(body, dead, if_at, hook_at):
                    continue
                identifier = (gate.group(1) or gate.group(2) or "").strip()
                if identifier != receiver.group("recv"):
                    continue
                plan_ok = True
                break
        links["install_plan_gated"] = plan_ok
        # 安装调用必须严格位于「安装许可早退」之后，且不在不可达/恒假分支里。
        # 安装点还必须落在「必然执行」的位置：写进有界循环或运行期 `if` 分支里，
        # 都存在一条不安装也能走到底的路径，不能算接线。
        present = False
        if hook_at is not None and install_guard is not None and hook_at > install_guard.start():
            present = (not unreachable_at(body, dead, hook_at)
                       and unconditionally_reached(body, hook_at))
        # 安装若落在「安装许可判定」所在的那条语句内部（例如许可失败的真分支），
        # 即便位置在许可之后，也属于错误分支，必须拒绝。
        if (install_guard is not None
                and install_inside_guard_consequent(body, install_guard.start(), hook_at)):
            present = False
        links["install_path_present"] = present
        # 安装路径必须全文唯一：直接调用只算一处，且不存在「取别名再调用」的第二处。
        links["install_path_exclusive"] = len(direct_sites) == 1 and not aliased_sites
        break
    actual = all(links.values())
    if enforce_contract:
        require(wiring["wired"] is actual,
                "合同声明的接线状态与实测不一致：wired=%r，实测=%r；不得只改合同或基线"
                % (wiring["wired"], actual))
    return {"合同声明接线": wiring["wired"], "实际接线": actual,
            "引用头文件": sorted(includes), "调用点": calls, "完整链路": links,
            "可达函数": sorted(reachable), "直接安装点": len(direct_sites),
            "别名安装点": len(aliased_sites),
            "待补证据": [one for one, ok in links.items() if not ok]}


def verify_structural_wiring(contract, sources):
    """核对源码实测结构接线与实验安装开关；静态链路成立不等于实机放行。"""
    wiring = contract["nativeWiring"]
    require("structuralWiring" in wiring, "合同缺少 structuralWiring")
    struct = wiring["structuralWiring"]
    required = baseline.STRUCTURAL_WIRING_BASELINE
    fields = {
        "structureWired": "structure_wired",
        "feasibilityVerified": "feasibility_verified",
        "experimentalInstallSwitch": "experimental_install_switch",
        "feasibilityExports": "feasibility_exports",
        "feasibilityImports": "feasibility_imports",
        "readonlyImports": "readonly_imports",
        "newImports": "new_imports",
        "newWhitelistEntries": "new_whitelist_entries",
    }
    for contract_key, baseline_key in fields.items():
        require(struct.get(contract_key) == required[baseline_key],
                "结构接线字段与独立基线不一致：%s" % contract_key)
    measured = verify_native_wiring(contract, sources, enforce_contract=False)["实际接线"]
    require(struct["structureWired"] is measured,
            "结构接线状态与源码实测不一致：structureWired=%r，实测=%r"
            % (struct["structureWired"], measured))
    require(wiring["wired"] is measured,
            "合同声明的接线状态与实测不一致（源码实测）：wired=%r，实测=%r"
            % (wiring["wired"], measured))
    require(struct["experimentalInstallSwitch"] is True, "实验安装开关必须显式存在")
    verify_install_lock(contract, sources)
    return {"结构接线": struct["structureWired"], "实测接线": measured,
            "可行性已实测": struct["feasibilityVerified"],
            "实验安装开关": struct["experimentalInstallSwitch"],
            "新增导入": list(struct["newImports"])}

# ---------------------------------------------------------------- 主流程

def verify_parity_contract(contract, sources=None, compiler=None, root=ROOT,
                           probe_enabled=True):
    """执行全部只读断言；防砖确认仅指离线静态审计，不授予安装许可或关闭门槛。"""
    verify_contract(contract, root=root)
    if sources is None:
        sources = load_sources(root)
    items = []
    gaps = []

    def record(name, status, note, evidence=None):
        items.append({"项": name, "状态": status, "说明": note, "证据": evidence or {},
                      "依据来自": "独立基线/实测"})
        if status == "缺口":
            gaps.append(name)
        return status == "已核验通过"

    record("合同自检", "已核验通过", "安装许可 false、门槛 1/3/5 未关闭、工具链参数与工具一致",
           {"installPermitted": False, "notClosedGates": contract["notClosedGates"]})
    record("独立基线完整性", "已核验通过",
           "基线常量表与文件指针表 HOST 的摘要都与审计工具内嵌指纹一致，且 HOST 键集合、"
           "路径范围、文件存在性都逐项校验；只改合同或只改基线（含把 HOST 指向伪造文件）"
           "都会硬失败。本指纹是仓库内明文「一致性绊线」，不是防篡改机制",
           {"基线指纹": BASELINE_FINGERPRINT, "被审计文件": dict(baseline.HOST)})
    record("熔断常量三方一致", "已核验通过", "Kotlin 常量、原生常量与合同逐项相等",
           verify_fuse_constants(contract, sources))
    record("熔断状态字段顺序", "已核验通过", "两侧状态行字段顺序与合同一致，磁盘格式不会静默漂移",
           verify_fuse_fields(contract, sources))
    record("熔断阈值与计数上限", "已核验通过",
           "判定式按独立基线要求的有序子序列逐条匹配，且三个熔断函数体摘要与基线一致",
           verify_trip_thresholds(contract, sources))

    blocker_names = native_blocker_names(sources)
    probe = native_probe_source(contract, blocker_names)
    if compiler is None:
        clang = find_clang(None, root) if probe_enabled else None
        compiler = (lambda text: run_probe(clang, text, root)) if clang else None
    if compiler is None:
        record("原生判定编译期复算", "未确认",
               "未找到可用的 NDK clang，原生常量与边界行为未在本机复算", {"断言数": 0})
    else:
        record("原生判定编译期复算", "已核验通过",
               "真实编译器对常量、解码器与边界行为逐条求值",
               compiler(probe))

    methods = baseline.KOTLIN_TEST_METHODS
    test_text = strip_comments(sources["boot_fuse_test"])
    missing = []
    for name in methods:
        matches = list(re.finditer(r"fun\s+" + re.escape(name) + r"\s*\([^)]*\)\s*\{", test_text))
        if len(matches) != 1:
            missing.append(name)
            continue
        body = block_at(test_text, matches[0].start(), name)
        if not re.search(r"\bassert\w*\s*\(", body) or body.strip() in ("{}", "{ }", "{\n}"):
            missing.append(name)
    require(not missing, "Kotlin 侧熔断用例缺失或不唯一：%s" % missing)
    record("Kotlin 侧用例对应", "已核验通过",
           "合同用例表逐条对应 Kotlin 既有单测；本工具不执行 JUnit，行为等价结论见限制",
           {"用例": methods})

    scope = verify_scope_consistency(sources)
    if scope["跨侧一致"]:
        record("作用域令牌与注入作用域一致", "已核验通过",
               "原生 kLauncherSafetyScope 等于 launcherNative 身份契约的 PACKAGE 与 "
               "scope.list 的桌面项；注入作用域恰好为桌面 com.miui.home 与 "
               "system_server system 两个宿主，顺序固定；此处只确认作用域一致性，"
               "不代替原生入口的完整接线核验",
               scope)
    else:
        record("作用域令牌与注入作用域一致", "缺口",
               "桌面令牌与 launcherNative 身份契约不一致，或注入作用域不满足"
               "桌面 + system_server 恰好两个宿主、顺序固定的要求：%s"
               % scope["不一致来源"], scope)

    record("逃生门属性与文件", "已核验通过", "属性/文件、fail-closed 读取与两条属性互不相同",
           verify_escape_gates(contract, sources))

    hits = scan_escape_references(root)
    if hits["完整"]:
        record("逃生门测试引用", "已核验通过",
               "有测试方法体内部同时给出属性字面量断言与真实 API 带期望值断言；"
               "扫描来源为 app/src/test、app/src/launcherNativeTest 与 tools/test_*.py",
               {"引用证据": hits["证据"], "扫描来源": list(REFERENCE_ROOTS) + [REFERENCE_TOOL_GLOB],
                "依据来自": "实测"})
    else:
        record("逃生门测试引用", "未确认",
               "未找到同时覆盖两类逃生门行为的真实断言（要求同一测试方法体内既有属性字面量"
               "断言、又有真实 API 带期望值断言）",
               {"引用证据": hits["证据"], "扫描来源": list(REFERENCE_ROOTS) + [REFERENCE_TOOL_GLOB],
                "依据来自": "实测"})
        gaps.append("逃生门测试引用")

    readme = sources["readme"]
    required = list(baseline.README_REQUIRED_STEPS)
    # 覆盖清单不允许被缩水：它必须恰好等于逃生门小节里声明的两条投保属性。
    expected = {contract["escapeGates"]["killSwitch"]["property"],
                contract["escapeGates"]["bootLoopGuard"]["property"]}
    require(set(required) == expected and len(required) == len(expected),
            "README 覆盖清单与逃生门小节不一致：必须是 %s，合同写的是 %s"
            % (sorted(expected), sorted(required)))
    headings = baseline.README_SECTION_HEADINGS
    start = readme.find(headings["start"])
    section = ""
    if start >= 0:
        end = readme.find("\n" + headings["end_prefix"], start + len(headings["start"]))
        section = readme[start:] if end < 0 else readme[start:start + end + 1]
    section = section if headings["anchor"] in section else ""
    uncovered = []
    for prop, steps in baseline.README_REQUIRED_STEPS.items():
        match = re.search(re.escape(prop) + r"(.{0,700})", section, re.S)
        if not match or any(step not in match.group(1) for step in steps):
            uncovered.append(prop)
    if uncovered:
        record("README 逃生属性覆盖", "缺口",
               "README 的恢复说明未覆盖：%s；用户按文档恢复不到 BootLoopGuard 自己的保护属性"
               % "、".join(uncovered),
               {"已覆盖": [name for name in required if name not in uncovered],
                "未覆盖": uncovered})
    else:
        record("README 逃生属性覆盖", "已核验通过", "恢复说明覆盖全部投保的逃生属性",
               {"已覆盖": required})

    structural = verify_structural_wiring(contract, sources)
    record("原生结构接线可行性（离线实测，不等于已放行）", "已核验通过",
           "safety_startup.cpp 已链入原型，入口已可达地调用启动状态提交；"
           "结构接线与八项链路已由源码实测成立，structureWired=true、wired=true；"
           "计划三段来源已接线并经非空校验；loader 解析失败仍 fail-closed；"
           "普通构建由显式实验安装开关保持关闭，安装许可=false、门槛 1/3/5 不关闭；"
           "防砖确认只是离线静态结论，不等于实机放行", structural)
    wiring = verify_native_wiring(contract, sources)
    if wiring["实际接线"]:
        record("原生入口安全门禁接线", "已核验通过",
               "原生入口从导出入口可达地提交启动状态，八项链路均已核验；"
               "提交结果与安装许可支配安装路径，install_plan_gated 要求同一计划"
               "非空校验支配唯一安装点，计划不完整即 fail-closed；不构成实机放行",
               wiring)
    else:
        record("原生入口安全门禁接线", "未确认",
               "完整链路验证器实测仍有证据缺失，完整接线不成立：%s"
               % "、".join(wiring["待补证据"]), wiring)
        gaps.append("原生入口安全门禁接线")

    record("实验安装开关与现场证据", "已核验通过",
           "普通构建保持关闭，许可只由入口、安全和后端现场证据的阻断位为零决定",
           verify_install_lock(contract, sources))

    confirmed = all(item["状态"] == "已核验通过" for item in items)
    return {
        "策略": POLICY,
        "基线摘要": {"模块": "launcher_safety_parity_baseline", "函数体摘要": baseline.BODY_DIGESTS,
                     "用例表摘要": baseline.CASE_TABLES_SHA256},
        "审计器已成功运行": True,
        "防砖已确认": confirmed,
        "安装许可": False,
        "未关闭门槛": contract["notClosedGates"],
        "检查项": items,
        "缺口": gaps,
        "未确认": [item["项"] for item in items if item["状态"] == "未确认"],
        "限制": contract["verificationBoundary"],
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description="防砖一致性（Kotlin ↔ 原生）的离线只读审计")
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT, help="一致性合同 JSON")
    parser.add_argument("--clang", help="NDK clang.exe；缺省自动探测，找不到则只标记未确认")
    parser.add_argument("--no-toolchain", action="store_true", help="显式跳过原生编译期复算")
    args = parser.parse_args(argv)
    try:
        contract = json.loads(args.contract.read_text(encoding="utf-8"))
        clang = None if args.no_toolchain else find_clang(args.clang)
        result = verify_parity_contract(
            contract,
            compiler=(lambda text: run_probe(clang, text)) if clang else None,
            probe_enabled=not args.no_toolchain,
        )
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except (AuditError, OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
        # 文件系统异常可能带本机绝对路径；只回报错误类型或工具自己构造的说明。
        message = type(error).__name__ if isinstance(error, OSError) else str(error)
        print("防砖一致性审计失败：" + message)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
