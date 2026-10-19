#!/usr/bin/env python3
"""「单字调用点提交 + 预发布蹦床」发布协议的只读策略审计。

本工具回答的是门槛 5 的一个具体问题：**改调用点那一条 `BL` 的发布协议，在还有
其它线程可能正在执行目标函数时，是否安全。**它不安装任何东西、不分配页面、
不写内存、不接触设备，`installPermitted` 必须为 false。

依据（两份，缺一不可，工具会逐项核对合同里的引用）：

1. ARM DDI 0487 L.a 第 B2.2.5 节（第 230 页）「Concurrent modification and
   execution of instructions」：`BL` 属于无同步并发修改例外集合；旧字与新字都在
   集合内时，架构只保证「取到旧或新之一」，不保证多字之间的一致性。
2. Arm Community 官方文章《Caches and Self-Modifying Code: Working with Threads》
   （Jacob Bramley，2025-01-21）：给出「预取推测保护」——新代码先完成
   `dc cvau / dsb ish / ic ivau / dsb ish`，**再**改那一条分支，则任何取到新分支的
   线程一定也能取到已同步的新代码；并明确警告多字修改会出现新旧混合执行、
   且旧指令可能在新指令已被执行之后仍被执行（没有时间上限）。

协议模型有三个同时进行的角色：准备蹦床页的作者线程、两条调用线程，以及一个
「关闭/启用」状态字。要求正确协议下不存在危险交错，并对三处预置故障各给出一条
反例：`early_commit`（未同步就提交）、`free_live_page`（发布后回收蹦床页）、
`multi_word_commit`（把提交做成多字覆盖）。模型只覆盖策略层，**不是** ARM 硬件、
herd7 或实机证明。
"""

import argparse
from collections import deque
from dataclasses import dataclass, replace
import json
import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from launcher_native_audit import (AuditError, ElfImage, concurrent_modification_kind,  # noqa: E402
                                   digest, direct_branch, number, require)


DEFAULT_CONTRACT = (Path(__file__).resolve().parents[1]
                    / "app/src/test/resources/launcher-singleword-publication-contract.json")
POLICY = "singleword-call-site-publication-offline-v1"
FAULTS = ("early_commit", "free_live_page", "multi_word_commit")
WITNESSES = ("由旧字进入调用", "由新字进入蹦床", "关闭后已有调用仍在蹦床内", "启用后已有调用读到新值")
STATE_VALUES = (0, 5, 20)
PAGE_UNALLOCATED, PAGE_ALLOCATED, PAGE_CODE_WRITTEN, PAGE_SYNCED, PAGE_RX, PAGE_PUBLISHED = range(6)


def load_host(image, contract):
    expected = contract["image"]
    require(len(image.data) == expected["size"], "宿主文件长度与合同不符")
    require(digest(image.data) == expected["sha256"], "宿主文件摘要与合同不符")
    require(image.build_id() == expected["buildId"], "宿主 Build ID 与合同不符")


def verify_architecture(contract):
    architecture = contract["architecture"]
    manual = architecture["manual"]
    require((manual["document"], manual["section"], manual["page"], manual["documentSha256"])
            == ("ARM DDI 0487 L.a", "B2.2.5", 230,
                "20d6ab39200e3d0c84cfc5948c6d3983a7a1867c11c9babe1ee4c2ee311d5368"),
            "手册引用不是已审核的 ARM DDI 0487 L.a B2.2.5")
    article = architecture["article"]
    require((article["title"], article["author"], article["date"])
            == ("Caches and Self-Modifying Code: Working with Threads",
                "Jacob Bramley", "2025-01-21"),
            "官方文章引用与工具实现不一致")
    # Arm Community 博客已迁到 developer.arm.com/community/，两个前缀都视为权威来源。
    require(article["url"].startswith(("https://community.arm.com/",
                                       "https://developer.arm.com/community/")),
            "官方文章链接不是 Arm Community 权威来源")
    require(article["establishes"] == "prefetch-speculation-protection",
            "必须显式声明该文章用于论证预取推测保护")
    require("预取推测保护" in article["note"] and "dc cvau" in article["note"]
            and "ic ivau" in article["note"] and "多字" in article["note"],
            "文章说明必须点明预取推测保护与缓存同步序列")
    return {"手册": "%s %s（第 %d 页）" % (manual["document"], manual["section"], manual["page"]),
            "官方文章": article["title"], "论证": article["establishes"]}


def verify_sites(image, contract):
    """重新核对 6 条调用点：单字 BL、目标与新旧字都可用例外集合描述。"""
    sites = contract["sites"]
    require(len(sites) == contract["counts"]["sites"], "调用点数量与合同不符")
    seen = set()
    report = []
    for site in sites:
        address = number(site["address"])
        require(address not in seen, "候选调用点重复：%#x" % address)
        seen.add(address)
        word = struct.unpack("<I", image.code(address, 4))[0]
        require(word == number(site["word"]), "调用点机器字与合同不符：%#x" % address)
        require(concurrent_modification_kind(word) == "BL", "调用点不是例外集合内的 BL")
        require(direct_branch(word, address) == ("BL", number(site["target"])),
                "调用点不是指向既定目标的 BL：%#x" % address)
        require(site["writeBytes"] == 4 and site["aligned"] is True, "提交必须是一个对齐的 32 位字")
        require(site["replacementKind"] == "BL", "替换字必须仍是例外集合内的 BL")
        require(number(site["returnAddress"]) == address + 4, "返回地址必须是调用点+4")
        report.append({"功能": site["feature"], "地址": hex(address),
                       "旧字": "BL 原目标", "新字": "BL 蹦床", "写入字节": 4})
    return report


@dataclass(frozen=True)
class Reader:
    pc: int = 0      # 0 未进入调用点，1 已进入蹦床，2 已回到调用点+4
    sample: int = 0  # 在被蹦床改写后观察到的上限值


@dataclass(frozen=True)
class Publication:
    page: int = PAGE_UNALLOCATED
    replaced: bool = False
    freed: bool = False
    torn: bool = False
    value: int = 0
    ever_new: bool = False
    readers: tuple = (Reader(), Reader())


def transitions(state, fault=None):
    """抽象事件枚举；缓存同步与单字提交是策略前提，不是对处理器的模拟。"""
    if not state.freed:
        if state.page < PAGE_RX:
            yield "蹦床页推进到%s" % ("已分配", "代码已写", "缓存已同步", "已封 RX")[state.page], replace(
                state, page=state.page + 1)
        if state.page == PAGE_RX and not state.replaced:
            yield "提交单字 BL 蹦床", replace(state, page=PAGE_PUBLISHED, replaced=True)
        # 故障对照：代码页只写完但尚未缓存同步就提交。这里**不改** page，
        # 让不变量仍能看出「已发布但未同步」——这正是故障要暴露的中间态。
        if fault == "early_commit" and state.page == PAGE_CODE_WRITTEN and not state.replaced:
            yield "提交单字 BL 蹦床（尚未缓存同步）", replace(state, replaced=True)
        if fault == "multi_word_commit" and state.page == PAGE_RX and not state.replaced:
            yield "以多字覆盖方式提交", replace(
                state, page=PAGE_PUBLISHED, replaced=True, torn=True)
        if state.replaced:
            for value in STATE_VALUES:
                if value != state.value:
                    yield "配置改为 %d（只写状态字）" % value, replace(state, value=value)
        if fault == "free_live_page" and state.page == PAGE_PUBLISHED:
            yield "发布后回收蹦床页", replace(state, freed=True)
    for index, reader in enumerate(state.readers):
        if reader.pc == 0:
            yield f"调用线程{index}:走旧字（原目标）", _move(state, index, 2, 0)
            if state.replaced:
                yield f"调用线程{index}:走新字（蹦床）", _move(state, index, 1, 0)
        elif reader.pc == 1:
            for value in STATE_VALUES:
                yield f"调用线程{index}:蹦床读到 %d" % value, _move(state, index, 1, value)
            yield f"调用线程{index}:蹦床返回调用点+4", _move(state, index, 2, reader.sample)
        else:
            yield f"调用线程{index}:下一次调用", _move(state, index, 0, 0)


def _move(state, index, pc, sample):
    readers = list(state.readers)
    readers[index] = Reader(pc=pc, sample=sample)
    return replace(state, readers=tuple(readers), ever_new=state.ever_new or pc == 1)


def invariant(state):
    if state.torn:
        return "多字覆盖不能保证逐字一致，出现新旧混合执行"
    for reader in state.readers:
        if reader.pc == 1:
            if state.freed:
                return "旧跳转进入已回收的蹦床页"
            if state.page < PAGE_SYNCED:
                return "新跳转进入尚未缓存同步的蹦床"
    for reader in state.readers:
        if reader.sample not in STATE_VALUES:
            return "观察到模型没有发布过的配置值"
        if reader.pc == 1 and reader.sample != 0 and not state.replaced:
            return "未提交调用点时却声称蹦床生效"
    return None


def explore(fault=None):
    require(fault in (None,) + FAULTS, "未知策略故障")
    initial = Publication()
    pending = deque([initial])
    previous = {initial: None}
    edges = 0
    witnessed = set()
    while pending:
        state = pending.popleft()
        reason = invariant(state)
        if reason:
            path = []
            cursor = state
            while previous[cursor] is not None:
                cursor, event = previous[cursor]
                path.append(event)
            return {"通过": False, "状态数": len(previous), "原因": reason, "反例": path[::-1]}
        for event, after in transitions(state, fault):
            edges += 1
            if "走旧字" in event:
                witnessed.add("由旧字进入调用")
            if "走新字" in event:
                witnessed.add("由新字进入蹦床")
            if after.value == 0 and any(r.pc == 1 for r in after.readers):
                witnessed.add("关闭后已有调用仍在蹦床内")
            if after.value in (5, 20) and any(r.pc == 1 for r in after.readers):
                witnessed.add("启用后已有调用读到新值")
            if after not in previous:
                require(len(previous) < 100000, "策略状态超过审计上限，不能报告通过")
                previous[after] = (state, event)
                pending.append(after)
    require(witnessed == set(WITNESSES), "没有覆盖全部关键交错：%s" % sorted(witnessed))
    return {"通过": True, "状态数": len(previous), "迁移数": edges,
            "关键交错": sorted(witnessed),
            "限制": "两个抽象调用线程 + 作者线程的有限状态策略检查，不是 ARM 硬件或 herd7 证明"}


def verify_contract(image, contract):
    require(contract["schemaVersion"] == 1 and contract["policy"] == POLICY,
            "合同版本或策略不符")
    require(contract["installPermitted"] is False, "合同不得声明安装许可")
    gates = contract.get("notClosedGates") or []
    require(any(gate.startswith("门槛3") for gate in gates), "必须显式保留门槛 3 未关闭")
    require(not any(gate.startswith("门槛5：本路线") and "已关闭" in gate for gate in gates),
            "本工具不关闭门槛 5，合同不得声称该路线已关闭门槛 5")
    protocol = contract["protocol"]
    require(protocol["commitWords"] == 1 and protocol["commitBits"] == 32,
            "协议必须固定为单条对齐 32 位提交")
    require(protocol["pageRetention"] == "process-lifetime",
            "蹦床页必须永久保留到进程结束，不得回收")
    require(protocol["disable"] == "state-word-only",
            "关闭只能改状态字，不得回写调用点机器字")
    require(protocol["trampolinePublishedBeforeCommit"] is True,
            "必须先发布蹦床页再提交调用点")
    load_host(image, contract)
    architecture = verify_architecture(contract)
    sites = verify_sites(image, contract)
    policy = explore()
    require(policy["通过"], "正确协议下出现反例")
    controls = {}
    for fault in FAULTS:
        result = explore(fault)
        require(not result["通过"], "未检出预置故障：%s" % fault)
        controls[fault] = result["原因"]
    return {"架构依据": architecture, "调用点": sites, "策略检查": policy,
            "故障反例": controls, "安装许可": False,
            "仍缺实机项": ["蹦床页实际内存属性与写入独占性", "宿主映像映射生命周期",
                           "提交确为单条对齐 32 位存储", "门槛3实机验收"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host-elf", type=Path, required=True, help="固定宿主 libapp.so")
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT,
                        help="单字发布协议合同 JSON")
    args = parser.parse_args()
    try:
        image = ElfImage(args.host_elf.read_bytes())
        contract = json.loads(args.contract.read_text(encoding="utf-8"))
        print(json.dumps(verify_contract(image, contract), ensure_ascii=False, indent=2))
    except (AuditError, OSError, ValueError, KeyError, struct.error) as error:
        print("单字发布协议审计失败：%s" % error)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
