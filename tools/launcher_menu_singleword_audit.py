#!/usr/bin/env python3
"""四项菜单功能「单字调用点替换」候选的只读静态审计。

本工具只读取本地宿主 `libapp.so` 与该候选合同 JSON：

- 不接入安装路径、不生成 SO、不加载目标库、不接触设备；
- `installPermitted` 必须为 false，否则直接失败；
- 只描述「单个 32 位机器字」层面的并发替换资格，不构成整体安装许可。

它更正早期交接里的一处表述：四项功能的**调用边本身是 `BL`**，而 `BL` 属于
ARM DDI 0487 L.a B2.2.5「Concurrent modification and execution of instructions」
列出的无同步并发修改例外集合（该节原文把 `B, B.cond, BL, BRK, CBNZ, CBZ, HVC,
ISB, NOP, SMC, SVC, TBNZ, TBZ, TRCIT` 一并列出，官方博客的经典例子正是
`BL old` 改成 `BL new`）。真正不在该集合内的是被调目标函数的序言 `stp`。

因此出现一条与 24 字节 Dobby 足迹相互独立的候选：把**调用点那一条 `BL`**改成
指向自有蹦床的 `BL`。旧的与新的机器字都属于例外集合，只需一个对齐的 32 位存储。
这不等于门槛 5 通过：蹦床必须复现原调用语义、新代码要先完成缓存同步、宿主映射
生命周期与写入独占性仍未验收。
"""

import argparse
import json
import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from launcher_native_audit import (AuditError, ElfImage, concurrent_modification_kind,  # noqa: E402
                                   digest, direct_branch, number, require)


DEFAULT_CONTRACT = Path(__file__).resolve().parents[1] / "app/src/test/resources/launcher-menu-singleword-eligibility-contract.json"
POLICY = "menu-singleword-eligibility-offline-v1"
ARCHITECTURE = ("ARM DDI 0487 L.a", "B2.2.5", 230,
                "20d6ab39200e3d0c84cfc5948c6d3983a7a1867c11c9babe1ee4c2ee311d5368")
# 手册列出但本工具无法独立解码的成员：一旦在候选机器字上出现，必须拒绝而不是当作通过。
UNDECODED_MEMBERS = ("TRCIT",)
ELIGIBLE_NAMES = ("B", "B.cond", "BL", "BRK", "CBNZ", "CBZ", "HVC", "ISB",
                  "NOP", "SMC", "SVC", "TBNZ", "TBZ", "TRCIT")
# 用于验证分类器本身的编码样本：(机器字, 期望类别)。空项代表不属于例外集合。
CLASSIFIER_SAMPLES = (
    (0x14000000, "B"), (0x94000000, "BL"), (0x5400000C, "B.cond"),
    (0x34000000, "CBZ"), (0x35000000, "CBNZ"), (0xB4000000, "CBZ"), (0xB5000000, "CBNZ"),
    (0x36000000, "TBZ"), (0x37000000, "TBNZ"), (0xB6000000, "TBZ"), (0xB7000000, "TBNZ"),
    (0xD503201F, "NOP"), (0xD5033FDF, "ISB"), (0xD4200000, "BRK"),
    (0xD4000001, "SVC"), (0xD4000002, "HVC"), (0xD4000003, "SMC"),
    (0xA9BF79FD, None), (0xAA0103E0, None), (0x58000091, None),
    (0xF85F83A1, None), (0x910082C0, None), (0xD65F03C0, None),
)


def word_at(image, address):
    return struct.unpack("<I", image.code(address, 4))[0]


def executable_segments(image):
    for segment in image.segments:
        if segment["type"] != 1 or not segment["flags"] & 1 or not segment["size"]:
            continue
        require(not segment["flags"] & 2, "出现可写可执行段，宿主不属于已审核映像")
        require(segment["address"] % 4 == 0 and segment["size"] % 4 == 0,
                "执行段未按指令对齐")
        yield segment


def scan_executable(image, watched):
    """单次遍历可执行文件段，返回 {地址: [(来源地址, 类别)]} 与扫描字节数。"""
    incoming = {address: [] for address in watched}
    scanned = 0
    for segment in executable_segments(image):
        raw = image.code(segment["address"], segment["size"])
        scanned += len(raw)
        for index, (word,) in enumerate(struct.iter_unpack("<I", raw)):
            source = segment["address"] + index * 4
            edge = direct_branch(word, source)
            if edge is not None and edge[1] in incoming:
                incoming[edge[1]].append((source, edge[0]))
    return incoming, scanned


def pointer_references(image, address):
    hits = 0
    for width, fmt in ((8, "<Q"), (4, "<I")):
        pattern = struct.pack(fmt, address & ((1 << (width * 8)) - 1))
        cursor = 0
        while True:
            found = image.data.find(pattern, cursor)
            if found < 0:
                break
            hits += 1
            cursor = found + 1
    return hits


def verify_architecture(contract):
    """核对架构引用与分类器本身：任一编码分类错误都直接失败。"""
    architecture = contract["architecture"]
    require((architecture["document"], architecture["section"], architecture["page"],
             architecture["documentSha256"]) == ARCHITECTURE,
            "架构依据不是已审核的 ARM DDI 0487 L.a B2.2.5")
    require(tuple(architecture["eligibleSet"]) == ELIGIBLE_NAMES,
            "例外集合声明与手册列出的指令名不一致")
    require(architecture["undecodedMembers"] == list(UNDECODED_MEMBERS),
            "未解码成员声明与工具实现不一致")
    for word, expected in CLASSIFIER_SAMPLES:
        actual = concurrent_modification_kind(word)
        require(actual == expected,
                "例外集合分类器错误：0x%08x 期望 %s 实际 %s" % (word, expected, actual))
    return {"架构": "%s %s" % (ARCHITECTURE[0], ARCHITECTURE[1]),
            "例外集合": list(ELIGIBLE_NAMES), "分类器校验样本": len(CLASSIFIER_SAMPLES),
            "未解码成员": list(UNDECODED_MEMBERS)}


def verify_publication_requirements(contract):
    """要求合同显式保留返回地址语义与三项未关闭门槛，禁止静默放行。"""
    requirements = contract.get("controlFlowRequirements") or []
    require(len(requirements) >= 5, "控制流/发布要求不足，不能作为候选依据")
    joined = " ".join(requirements)
    for keyword in ("returnAddress", "x30", "缓存同步", "32 位"):
        require(keyword in joined, "控制流/发布要求缺少关键约束：%s" % keyword)
    gates = contract.get("notClosedGates") or []
    require(len(gates) >= 3, "未关闭门槛声明不足")
    require(any(gate.startswith("门槛1") for gate in gates), "必须显式保留门槛 1 未关闭")
    require(any(gate.startswith("门槛5") for gate in gates), "必须显式保留门槛 5 未关闭")
    return {"控制流要求数": len(requirements), "未关闭门槛数": len(gates)}


def verify_publication_policy():
    """复用通用单字发布有限状态模型，并核对预置故障仍被检出。

    模型只覆盖「单条对齐 32 位提交 + 代码页永久保留 + 关闭只改状态」这一策略，
    不模拟硬件缓存、宿主映射或 Dart 运行时；它证明的是策略层无危险交错。
    """
    from launcher_branch_publication_audit import explore
    result = explore()
    require(result["通过"], "单字提交策略模型出现反例")
    controls = {fault: explore(fault) for fault in ("early_publish", "early_release")}
    require(all(not item["通过"] for item in controls.values()),
            "未检出预置的提前发布或提前回收故障")
    return {"状态数": result["状态数"], "迁移数": result["迁移数"],
            "关键交错": result["关键交错"], "故障对照": sorted(controls),
            "限制": "策略模型不是硬件缓存或宿主映射证明"}


def verify_image(image, contract):
    expected = contract["image"]
    require(len(image.data) == expected["size"], "宿主文件长度与合同不符")
    require(digest(image.data) == expected["sha256"], "宿主文件摘要与合同不符")
    require(image.build_id() == expected["buildId"], "宿主 Build ID 与合同不符")


def verify_contract(image, contract):
    require(contract["schemaVersion"] == 1, "合同版本不受支持")
    require(contract["policy"] == POLICY, "合同策略不是单字资格候选")
    require(contract["installPermitted"] is False, "合同不得声明安装许可")
    architecture = verify_architecture(contract)
    requirements = verify_publication_requirements(contract)
    publication = verify_publication_policy()
    verify_image(image, contract)

    symbols = image.debug_image().functions()
    table = {}
    for symbol in symbols:
        table.setdefault(symbol["name"], []).append(symbol)

    targets = contract["targets"]
    sites = contract["sites"]
    require(targets and sites, "合同不得省略目标或调用点")
    watched = [number(site["address"]) for site in sites]
    incoming, scanned = scan_executable(image, watched)

    target_report = []
    for expected in targets:
        matches = table.get(expected["name"], [])
        require(len(matches) == 1, "目标符号不唯一：%s" % expected["name"])
        symbol = matches[0]
        address, size = number(expected["address"]), expected["size"]
        require(symbol["address"] == address and symbol["size"] == size and size > 0,
                "目标函数范围与符号表不符：%s" % expected["name"])
        require(digest(image.code(address, size)) == expected["sha256"],
                "目标函数摘要不符：%s" % expected["name"])
        entry = word_at(image, address)
        require(entry == number(expected["entryWord"]), "目标入口机器字不符：%s" % expected["name"])
        kind = concurrent_modification_kind(entry)
        # 目标序言不在例外集合，正是「改调用点而不是改函数入口」的理由。
        require(kind is None, "目标入口落在例外集合内，本候选的替代前提不成立：%s" % expected["name"])
        require(expected["entryEligible"] is False, "合同不得声称目标入口可并发替换")
        require(expected["entryKind"] == "非例外集合（STP）", "目标入口类别描述不符")
        callers = [site for site in sites if number(site["target"]) == address]
        require(len(callers) == expected["callers"], "目标的调用点数量与合同不符：%s" % expected["name"])
        require(pointer_references(image, address) == expected["pointerRefs"],
                "目标函数的指针引用数发生变化：%s" % expected["name"])
        target_report.append({"目标": expected["name"], "地址": hex(address),
                              "入口类别": "非例外集合", "调用点数": len(callers)})

    site_report = []
    declared = {number(site["address"]): site for site in sites}
    require(len(declared) == len(sites), "合同存在重复调用点")
    for site in sites:
        address = number(site["address"])
        word = word_at(image, address)
        require(word == number(site["word"]), "调用点机器字不符：0x%x" % address)
        edge = direct_branch(word, address)
        require(edge == ("BL", number(site["target"])), "调用点不是指向目标的 BL：0x%x" % address)
        require(site["kind"] == "BL", "合同未把调用点声明为 BL")
        # 新旧机器字都属于手册例外集合，才允许只改这一个对齐 32 位字。
        require(concurrent_modification_kind(word) == "BL", "调用点机器字不属于例外集合")
        require(site["eligible"] is True, "合同未声明该调用点在例外集合内")
        require(not incoming[address], "调用点自身被其它分支跳入，拒绝该候选：0x%x" % address)
        # `BL` 会把返回地址写进 x30。改成 BL 蹦床后硬件写入的是蹦床内地址，
        # 因此蹦床必须显式把 x30 恢复成「调用点+4」，接续指令也必须逐字固定，
        # 否则返回后会落到不同指令上。这里只固定原接续指令，不假装蹦床已实现。
        require(number(site["returnAddress"]) == address + 4, "调用点的 BL 返回地址不是调用点+4")
        require(word_at(image, address + 4) == number(site["continuationWord"]),
                "调用点接续指令与合同不符：0x%x" % address)
        site_report.append({"功能": site["feature"], "地址": hex(address), "类别": "BL",
                            "目标": site["targetName"], "分支入口数": 0,
                            "返回地址": hex(address + 4)})

    return {"架构": architecture, "目标": target_report, "调用点": site_report,
            "扫描执行字节数": scanned, "安装许可": False,
            "发布要求": requirements, "发布策略": publication,
            "限制": "只固定单字替换资格与直接调用边；不证明安装窗口无并发执行、"
                    "不证明新蹦床已完成缓存同步、不审核蹦床语义"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host-elf", type=Path, required=True, help="固定宿主 libapp.so")
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT,
                        help="单字调用点资格候选合同 JSON")
    args = parser.parse_args()
    try:
        image = ElfImage(args.host_elf.read_bytes())
        contract = json.loads(args.contract.read_text(encoding="utf-8"))
        print(json.dumps(verify_contract(image, contract), ensure_ascii=False, indent=2))
    except (AuditError, OSError, struct.error, KeyError) as error:
        print("单字调用点资格审计失败：%s" % error)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
