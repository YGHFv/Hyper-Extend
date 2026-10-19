#!/usr/bin/env python3
"""门槛 5 残余项 (c) 的**真实工具链故障对照**审计。

残余项 (c) 要求「提交调用点恰好是一条对齐 32 位存储」。上一份工具只用**合成机器字**
证明判定会拒绝多字存储；本工具补上更强的证据：把几种常见错误实现真的用 NDK clang
编译出来，用**同一套判定函数**去跑，要求每一个都被拒绝。

错误实现（全部由 `app/src/test/cpp/launcher_singleword_commit_faults.cpp` 提供，
不参与 Android 构建）：

    分两条普通存储写两个字      → 必须被「提交函数恰好两条指令」拒绝
    一条 STP 写两个字           → 必须被「不是单条 32 位存储」拒绝
    64 位存储覆盖两个字         → 必须被「恰好两条指令」拒绝
    退化成 memcpy               → 必须被「恰好两条指令」拒绝
    缺 ic ivau 的同步序列       → 必须被「缺少 ic ivau」拒绝
    先提交后同步                → 必须被「未保证先同步后提交」拒绝

只读：交叉编译在临时目录里进行，不写仓库、不链接 SO、不接触设备。
`installPermitted` 必须为 false；通过也**不等于**门槛 5 关闭。
"""

import argparse
import json
import struct
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from launcher_native_audit import AuditError, require  # noqa: E402
from launcher_gate5_residual_audit import (TRIAL_FLAGS, compile_trial, find_toolchain,  # noqa: E402
                                           parse_rel_object, verify_commit_symbol,
                                           verify_publish_order, verify_sync_symbol)


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CONTRACT = ROOT / "app/src/test/resources/launcher-gate5-fault-contract.json"
DEFAULT_FAULTS = ROOT / "app/src/test/cpp/launcher_singleword_commit_faults.cpp"
POLICY = "gate5-commit-faults-offline-v1"


def expect_rejected(action, kind, label, keyword):
    """要求 action 抛 AuditError，且信息里含 keyword（否则说明是别的原因失败）。"""
    try:
        result = action()
    except AuditError as error:
        require(keyword in str(error),
                "%s 被拒绝的原因不是预期：期望含「%s」，实际「%s」" % (label, keyword, error))
        return {"对照": label, "判定": kind, "命中关键字": keyword, "实际信息": str(error)}
    raise AuditError("%s 未被拒绝，反而返回：%r" % (label, result))


def expect_accepted(action, label):
    action()
    return label


def verify_faults(clang, objdump, source, expectations):
    with tempfile.TemporaryDirectory(prefix="gate5-faults-") as folder:
        obj = Path(folder) / "faults.o"
        _, listing = compile_trial(clang, objdump, source, obj)
        require(len(listing) > 0, "反汇编输出为空")
        words = parse_rel_object(obj.read_bytes(), tuple(expectations))
    report = []
    for symbol, spec in expectations.items():
        code = words[symbol]
        require(len(code) == spec["instructions"],
                "%s 的实际指令数与合同不符：期望 %d，实际 %d"
                % (symbol, spec["instructions"], len(code)))
        subject = spec["subject"]
        label = spec["label"]
        if subject == "commit":
            action = lambda code=code, symbol=symbol: verify_commit_symbol(code, symbol)
        elif subject == "sync":
            action = lambda code=code, symbol=symbol: verify_sync_symbol(code, symbol)
        elif subject == "order":
            action = lambda code=code, symbol=symbol: verify_publish_order(code, symbol)
        else:
            raise AuditError("未知的判定对象：%s" % subject)
        report.append(expect_rejected(action, subject, label, spec["keyword"]))
    return report


def verify_contract(contract):
    require(contract["schemaVersion"] == 1 and contract["policy"] == POLICY,
            "合同版本或策略不符")
    require(contract["installPermitted"] is False, "合同不得声明安装许可")
    gates = contract.get("notClosedGates") or []
    require(any(gate.startswith("门槛5") for gate in gates), "必须显式保留门槛 5 未关闭")
    expectations = contract["functions"]
    require(len(expectations) == contract["counts"]["faults"],
            "故障对照数量与合同不符：%d vs %d" % (len(expectations), contract["counts"]["faults"]))
    toolchain = contract["toolchain"]
    require(toolchain["flags"] == " ".join(TRIAL_FLAGS),
            "合同记录的编译参数与工具实际使用的参数不一致，必须重新实测")
    require("clang version" in toolchain["clang"], "合同必须记录实测使用的 clang 版本")
    subjects = {spec["subject"] for spec in expectations.values()}
    require(subjects == {"commit", "sync", "order"},
            "故障对照必须覆盖提交、同步、顺序三类判定：%s" % sorted(subjects))
    return expectations


def main():
    parser = argparse.ArgumentParser(description="单字提交的故障对照只读审计")
    parser.add_argument("--clang", help="NDK clang.exe；缺省用 PATH 上的 clang")
    parser.add_argument("--faults", type=Path, default=DEFAULT_FAULTS, help="故障对照源")
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT,
                        help="故障对照合同 JSON")
    args = parser.parse_args()
    try:
        contract = json.loads(args.contract.read_text(encoding="utf-8"))
        expectations = verify_contract(contract)
        clang, objdump = find_toolchain(args.clang)
        report = verify_faults(clang, objdump, args.faults, expectations)
        print(json.dumps({"策略": POLICY, "编译参数": " ".join(TRIAL_FLAGS),
                          "故障对照": report, "安装许可": False,
                          "限制": "只用真实工具链输出证明判定会拒绝错误实现；不证明正确实现"
                                  "就一定安全，也不覆盖实机安装与运行期竞争。"},
                         ensure_ascii=False, indent=2))
    except (AuditError, OSError, ValueError, KeyError, struct.error) as error:
        print("单字提交故障对照审计失败：%s" % error)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
