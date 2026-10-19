#!/usr/bin/env python3
"""独立单分支候选的只读合同与有限状态策略审计；不实现或调用代码页安装。"""

import argparse
from collections import deque
from dataclasses import dataclass, replace
import json
import lzma
from pathlib import Path
import struct

from launcher_native_audit import (
    DEFAULT_CONTRACT, AuditError, ElfImage, digest, direct_branch, number, require, verify_contract,
)
from launcher_shortcut_count_emulate import (
    Arm64Object, BRANCH, BRANCH_SYMBOL, CLIP, KEEP, STATE_SYMBOL, relocate_word, verify_trial_site,
)


BRANCH_CONTRACT = DEFAULT_CONTRACT.with_name("launcher-count-branch-contract.json")
VALUES = (0, 5, 20)


def eligible_pair(address, old, new, size):
    # 仅认可本试点的 B.cond 到 B，不把整个架构例外集合扩大成可写地址白名单。
    return (type(address) is int and 0 <= address <= (1 << 64) - 4 and address % 4 == 0
            and type(size) is int and size == 4
            and type(old) is int and 0 <= old <= 0xffffffff and old & 0xff000010 == 0x54000000
            and type(new) is int and 0 <= new <= 0xffffffff and new & 0xfc000000 == 0x14000000)


def verify_island(obj, contract):
    require(contract["schemaVersion"] == 1 and
            contract["policy"] == "post-length-conditional-branch-offline-v1"
            and contract["installPermitted"] is False, "独立候选合同不符或声称允许安装")
    architecture = contract["architecture"]
    require((architecture["document"], architecture["section"], architecture["page"],
             architecture["instructionPair"]) ==
            ("ARM DDI 0487 L.a", "B2.2.5", 230, "B.cond -> B"), "架构规则引用不属于已审核依据")
    island = contract["island"]
    require(island["symbol"] == BRANCH_SYMBOL and len(obj.code) == island["size"]
            and digest(obj.code) == island["sha256"], "独立候选的代码大小、入口或摘要变化")
    require(obj.relocations == [tuple(item) for item in island["relocations"]], "独立候选的重定位发生变化")
    site = contract["site"]
    require((number(site["address"]), number(site["originalWord"]), site["writeBytes"],
             number(site["keepAddress"]), number(site["clipAddress"]), number(site["guardStart"])) ==
            (BRANCH, 0x540000ac, 4, KEEP, CLIP, 0x1147048), "候选挂载位置或语义路径变化")


def verify_site(image, contract):
    verify_trial_site(image)
    site = contract["site"]
    guard = bytes.fromhex(site["guardBytes"])
    start = number(site["guardStart"])
    require(len(guard) == 16 and image.code(start, len(guard)) == guard, "分支前置窗口与合同不符")
    require(direct_branch(number(site["originalWord"]), BRANCH) == ("B.cond/BC.cond", CLIP),
            "原条件分支目标不符")
    scanned = 0
    for segment in image.segments:
        if segment["type"] != 1 or not segment["flags"] & 1:
            continue
        require(segment["flags"] & 2 == 0 and segment["address"] % 4 == segment["size"] % 4 == 0,
                "执行段可写或未按指令对齐")
        raw = image.read(segment["offset"], segment["size"])
        for i, (word,) in enumerate(struct.iter_unpack("<I", raw)):
            edge = direct_branch(word, segment["address"] + i * 4)
            require(edge is None or not start <= edge[1] < KEEP, "发现直接分支跳入候选前置窗口")
        scanned += len(raw)
    return {"扫描执行字节数": scanned, "前置窗口直接入口数": 0, "指令对": "B.gt -> B",
            "限制": "不识别间接/异常入口；依赖已审核宿主合同，不代表运行期归属核验"}


def verify_layout(obj, code, data, page_size, load_bias=0):
    """仅计算布局及重定位约束，不分配内存、不检查当前进程地址是否空闲。"""
    require(type(page_size) is int and page_size in (4096, 16384), "只审计4K/16K页面布局")
    for address in (code, data, load_bias):
        require(type(address) is int and 0 <= address <= (1 << 64) - page_size
                and address % page_size == 0, "布局地址不按页对齐或越界")
    require(code != data and len(obj.code) <= page_size, "代码与数据必须处于独立页面")
    require(load_bias + CLIP < 1 << 64, "宿主地址重定位溢出")
    host_page = (load_bias + BRANCH) & ~(page_size - 1)
    require(code != host_page and data != host_page, "模型页面不能覆盖宿主挂载页")
    word = relocate_word(0x14000000, 282, load_bias + BRANCH, code)
    require(eligible_pair(load_bias + BRANCH, 0x540000ac, word, 4), "不符合单分支指令对要求")
    obj.materialize(code, {STATE_SYMBOL: data, "launcher_count_trial_keep": load_bias + KEEP,
                           "launcher_count_trial_clip": load_bias + CLIP})
    return word


@dataclass(frozen=True)
class Reader:
    pc: int = 0  # 0 待调用，1 已进入代码页，2 已读状态，3 已回到宿主
    sample: int = 0
    seen_new: bool = False


@dataclass(frozen=True)
class Publication:
    # 依次代表未分配、已分配、数据归零、代码写好、缓存同步、代码封为 RX。
    stage: int = 0
    alive: bool = False
    ever_published: bool = False
    patched: bool = False
    blocked: bool = False
    abandoned: bool = False
    value: int = 0
    history: int = 1
    readers: tuple = (Reader(), Reader())


def may_release(state):
    return not state.ever_published and all(reader.pc in (0, 3) for reader in state.readers)


def transitions(state, fault=None):
    """抽象事件的有限枚举；缓存同步等事件是假设，不是对真实处理器的模拟。"""
    if not state.abandoned and not state.ever_published:
        if state.stage < 5:
            yield f"准备阶段{state.stage + 1}", replace(state, stage=state.stage + 1, alive=True)
        if state.stage == 5 or fault == "early_publish" and state.stage == 3:
            yield "发布单条分支", replace(state, ever_published=True, patched=True)
        if state.stage > 0 and may_release(state):
            yield "发布前放弃并释放", replace(state, alive=False, abandoned=True)
    if state.ever_published and not state.blocked:
        for index, value in enumerate(VALUES):
            yield f"配置改为{value}", replace(state, value=value, history=state.history | (1 << index))
        yield "补丁丢失或恢复原指令后关闭", replace(state, patched=False, blocked=True, value=0)
        yield "发布后失败保留页面并关闭", replace(state, blocked=True, value=0)
    if (fault == "early_release" and state.ever_published and not state.patched
            and state.alive and all(reader.pc in (0, 3) for reader in state.readers)):
        yield "错误地因无活跃调用而释放", replace(state, alive=False)
    for index, reader in enumerate(state.readers):
        options = []
        if reader.pc == 0:
            label = "新后仍执行旧分支" if reader.seen_new else "执行原分支"
            options.append((label, replace(reader, pc=3, sample=0)))
            # 即使补丁已恢复、或此前执行过新指令，也不假定各核已丢弃旧取指。
            if state.ever_published:
                options.append(("进入曾发布的代码页", replace(reader, pc=1, seen_new=True)))
        elif reader.pc == 1:
            # 保守允许看到任何已发布过的单字值，不把 acquire 误当成绝对实时性保证。
            for bit, value in enumerate(VALUES):
                if state.history & (1 << bit):
                    options.append((f"读取状态{value}", replace(reader, pc=2, sample=value)))
        elif reader.pc == 2:
            options.append(("按本次读取值返回宿主", replace(reader, pc=3)))
        else:
            options.append(("开始下一次调用", replace(reader, pc=0, sample=0)))
        for label, changed in options:
            readers = list(state.readers)
            readers[index] = changed
            yield f"线程{index}:{label}", replace(state, readers=tuple(readers))


def invariant(state):
    if any(reader.pc in (1, 2) for reader in state.readers):
        if not state.alive:
            return "旧跳转进入已释放的代码/数据页"
        if state.stage < 5:
            return "新跳转进入未完成同步和封页的代码"
    if any(reader.sample not in VALUES or not state.history & (1 << VALUES.index(reader.sample))
           for reader in state.readers):
        return "出现本模型没有发布过的配置值"
    if state.blocked and state.value != 0:
        return "失败或补丁丢失后仍发布启用配置"
    return None


def explore(fault=None):
    require(fault in (None, "early_publish", "early_release"), "未知策略故障")
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
            if "新后仍执行旧分支" in event:
                witnessed.add("先新后旧")
            if not after.patched and after.ever_published and any(r.pc == 1 for r in after.readers):
                witnessed.add("恢复后旧跳转")
            if after.value == 0 and any(r.pc == 2 and r.sample != 0 for r in after.readers):
                witnessed.add("关闭时已有调用读到旧值")
            if after not in previous:
                require(len(previous) < 100000, "策略状态超过审计上限，不能报告通过")
                previous[after] = (state, event)
                pending.append(after)
    require(witnessed == {"先新后旧", "恢复后旧跳转", "关闭时已有调用读到旧值"}, "没有覆盖全部关键交错")
    return {"通过": True, "状态数": len(previous), "迁移数": edges, "关键交错": sorted(witnessed),
            "限制": "两个抽象执行者及0/5/20配置的有限状态策略检查，不是ARM硬件或herd7证明"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--elf", type=Path, required=True, help="只读的本地宿主ELF")
    parser.add_argument("--object", type=Path, required=True, help="独立分支候选的交叉汇编对象")
    args = parser.parse_args()
    try:
        image = ElfImage(args.elf.read_bytes())
        host = verify_contract(image, json.loads(DEFAULT_CONTRACT.read_text(encoding="utf-8")))
        contract = json.loads(BRANCH_CONTRACT.read_text(encoding="utf-8"))
        obj = Arm64Object(args.object.read_bytes(), BRANCH_SYMBOL)
        verify_island(obj, contract)
        site = verify_site(image, contract)
        for page_size in (4096, 16384):
            for bias in (0, 0x7000000000):
                verify_layout(obj, bias + 0x5000000, bias + 0x5000000 + page_size, page_size, bias)
        policy = explore()
        require(policy["通过"], "安全策略模型出现反例")
        controls = {fault: explore(fault) for fault in ("early_publish", "early_release")}
        require(all(not result["通过"] for result in controls.values()), "未检出预置的发布/回收故障")
        print(json.dumps({"宿主合同": host, "独立分支": site, "样例布局数": 4, "策略检查": policy,
                          "故障反例": controls, "安装许可": False,
                          "仍缺": ["实际内存属性/缓存维护/单字提交验收", "宿主映射生命周期与写入独占性",
                                   "Dart异步执行兼容性", "门槛3实机验收"]}, ensure_ascii=False, indent=2))
    except (ValueError, TypeError, OSError, KeyError, struct.error, lzma.LZMAError) as error:
        reason = str(error) if isinstance(error, AuditError) else type(error).__name__
        print(f"单分支审计失败：{reason}；安装许可=false")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
