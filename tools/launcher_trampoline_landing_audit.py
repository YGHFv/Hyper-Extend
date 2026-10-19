#!/usr/bin/env python3
"""单字调用点候选的「蹦床落点可达性」只读审计。

本工具只读取本地宿主 `libapp.so` 与该候选合同 JSON，外加一份**本机、不入库**的
进程映射快照（`/proc/<pid>/maps` 文本）：

- 不分配页面、不写内存、不生成 SO、不加载目标库、不接触设备；
- `installPermitted` 必须为 false，否则直接失败；
- 结论只覆盖「把调用点的 `BL` 改成 `BL 蹦床` 是否在架构上可编码并可达」，
  不构成安装许可，也不证明该空洞在运行期仍空闲。

已取证的宿主执行段位于 `base + 0x850000`（长度 `0x130ccf0`），运行基址由 ASLR
决定。`B`/`BL` 使用 26 位有符号立即数（偏移 ±2^27 字节、按 4 对齐），因此
「蹦床必须落在调用点 ±128MB 内」不是策略选择，而是编码硬约束。

本工具把这条硬约束落成机器断言：映射快照中必须存在**页对齐、非 W+X、长度不小于
合同下限**的未映射空洞，且蹦床首字与末字对全部候选调用点、原目标、调用点+4 三类
端点都能完成 `B`/`BL` 编码。同一份快照还要核对候选调用点落在**私有、可读可执行、
有文件支撑、路径属于已审核宿主包**的映射里——后端提交是 `mprotect` + `memcpy`，
只有 `MAP_PRIVATE` 文件映射才会把写入限制在本进程 COW 副本。空洞绝对地址与加载基址
属于本机运行期信息，只作为命令输入，**不写入合同、不入库**。
"""

import argparse
import json
import re
import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from launcher_native_audit import (AuditError, ElfImage, digest, direct_branch,  # noqa: E402
                                   number, require)


DEFAULT_CONTRACT = (Path(__file__).resolve().parents[1]
                    / "app/src/test/resources/launcher-trampoline-landing-contract.json")
POLICY = "menu-trampoline-landing-offline-v1"
# 26 位有符号立即数、按 4 对齐：可达偏移上限是 2^27，不是 2^27-4。
BRANCH_LIMIT = 1 << 27
MAP_PATTERN = re.compile(
    r"^(?P<start>[0-9a-f]+)-(?P<end>[0-9a-f]+) "
    r"(?P<perms>[rwxps-]{4}) (?P<offset>[0-9a-f]+) (?P<dev>\S+) (?P<inode>\S+)\s*(?P<path>.*)$")


def encode_b(pc, target):
    """把 B/BL 的 26 位有符号立即数编成机器字；超出可达范围直接失败。"""
    delta = target - pc
    require(delta % 4 == 0, "分支目标未按 4 字节对齐")
    require(-BRANCH_LIMIT <= delta < BRANCH_LIMIT, "分支目标超出 26 位有符号立即数范围")
    return 0x14000000 | ((delta // 4) & 0x03FFFFFF)


def decode_b(word, pc, link=False):
    """独立反解，避免只用同一个编码函数自证。"""
    opcode = 0x94000000 if link else 0x14000000
    require(word & 0xFC000000 == opcode, "机器字不是 B/BL")
    displacement = word & 0x03FFFFFF
    if displacement & 0x02000000:
        displacement -= 0x04000000
    return pc + displacement * 4


def parse_maps(text):
    """解析本机映射快照，返回连续区间列表与 W+X 段；不猜测或补齐缺失行。"""
    regions = []
    for line in text.splitlines():
        if not line.strip():
            continue
        match = MAP_PATTERN.match(line)
        require(match is not None, "映射快照存在无法解析的行")
        start, end = int(match["start"], 16), int(match["end"], 16)
        require(start < end, "映射区间为空或倒置")
        regions.append(dict(start=start, end=end, perms=match["perms"],
                            offset=int(match["offset"], 16), path=match["path"].strip()))
    require(regions, "映射快照为空")
    regions.sort(key=lambda item: item["start"])
    for left, right in zip(regions, regions[1:]):
        require(left["end"] <= right["start"], "映射快照存在重叠区间")
    return regions


def find_holes(regions):
    """相邻映射之间的未映射空洞；不把 W+X 段本身当作候选。"""
    holes = []
    for left, right in zip(regions, regions[1:]):
        if right["start"] > left["end"]:
            holes.append((left["end"], right["start"]))
    return holes


def verify_maps_requirements(regions, requirements):
    page = number(requirements["pageSize"])
    require(page > 0 and page & (page - 1) == 0, "合同页大小不是 2 的幂")
    for region in regions:
        if "w" in region["perms"] and "x" in region["perms"]:
            raise AuditError("映射快照出现可写可执行段，拒绝该快照：%#x" % region["start"])
    require(all(region["start"] % page == 0 and region["end"] % page == 0
                for region in regions), "映射快照存在未按页对齐的区间")
    holes = find_holes(regions)
    require(holes, "映射快照里没有未映射空洞")
    return holes


def verify_image(image, contract):
    expected = contract["image"]
    require(len(image.data) == expected["size"], "宿主文件长度与合同不符")
    require(digest(image.data) == expected["sha256"], "宿主文件摘要与合同不符")
    require(image.build_id() == expected["buildId"], "宿主 Build ID 与合同不符")


def verify_landing(sites, holes, requirements, base):
    minimum = number(requirements["minimumHoleBytes"])
    page = number(requirements["pageSize"])
    island = number(requirements["maximumIslandBytes"])
    require(minimum >= page, "合同最小空洞不得小于一页")
    require(0 < island < BRANCH_LIMIT and island % 16 == 0, "合同蹦床体积不受支持")
    # 运行期地址 = 基址 + 文件偏移；目标同理，两者都必须由调用点自身推出。
    normalized = [dict(site, runtime=base + number(site["offset"]),
                       target_address=base + number(site["target"])) for site in sites]
    # 蹦床必须同时对「调用点」「原目标」「返回地址」三类端点都可编码；
    # 只对调用点取范围会漏掉目标或接续点更远的情况。
    endpoints = sorted({value for site in normalized
                        for value in (site["runtime"], site["target_address"],
                                      site["runtime"] + 4)})
    usable = []
    for start, end in holes:
        # 空洞来自映射边界，天然页对齐；同时满足合同的 16 字节落点对齐要求。
        if end - start < max(minimum, island) or start % page or start % 16:
            continue
        # 蹦床落在 start，其内部指令地址取蹦床首字与末字两种最坏情况：
        #   1) 调用点 `BL 蹦床` 只用到蹦床起点；
        #   2) 蹦床内 `BL 原目标`（复现原调用）；
        #   3) 蹦床内 `B 接续点`（返回调用点+4）。
        # 三条都要对全部端点成立，因此对「start / start+island-4」一起取范围。
        if not all(-BRANCH_LIMIT <= start + delta - address < BRANCH_LIMIT
                   for address in endpoints for delta in (0, island - 4)):
            continue
        usable.append((start, end))
    require(usable, "映射快照里没有能同时服务全部调用点的可达空洞")
    usable.sort(key=lambda item: (item[0] - endpoints[0], item[0]))
    report = []
    for index, (start, end) in enumerate(usable):
        entries = []
        for site in normalized:
            address = site["runtime"]
            target = site["target_address"]
            # 三条必需编码都走真实的编码 + 独立反解，避免只算一个差值。
            call = encode_b(address, start) | 0x80000000
            require(decode_b(call, address, link=True) == start, "调用点 BL 不自洽")
            inner = encode_b(start, target) | 0x80000000
            require(decode_b(inner, start, link=True) == target, "蹦床内 BL 不自洽")
            back = encode_b(start + island - 4, address + 4)
            require(decode_b(back, start + island - 4) == address + 4, "蹦床返回 B 不自洽")
            entries.append({"功能": site["feature"], "调用点": hex(number(site["offset"])),
                            "跳入距离": hex(start - address),
                            "原目标距离": hex(target - start),
                            "返回距离": hex(address + 4 - (start + island - 4))})
        report.append({"候选": index + 1, "空洞长度": hex(end - start),
                       "按页对齐": start % page == 0, "落点": entries})
    return report


def verify_site_words(image, sites):
    """调用点必须是宿主里真实的、指向已固定目标的 BL，不能只写偏移。"""
    for site in sites:
        offset = number(site["offset"])
        require(offset % 4 == 0, "候选调用点未按 4 字节对齐")
        word = struct.unpack("<I", image.code(offset, 4))[0]
        require(word == number(site["word"]), "候选调用点机器字与合同不符：%#x" % offset)
        edge = direct_branch(word, offset)
        require(edge == ("BL", number(site["target"])),
                "候选调用点不是指向已固定目标的 BL：%#x" % offset)
        # 目标必须落在同一个宿主 ELF 的可执行文件映射里，拒绝指向段外地址。
        image.file_offset(number(site["target"]), 4, executable=True)
    return len(sites)


def verify_site_mappings(regions, sites, base, requirements):
    """候选调用点必须落在「私有、文件支撑、只执行」的映射里。

    这条不是风格要求：后端提交路径是 `mprotect` + `memcpy`。只有当代码段是
    `MAP_PRIVATE` 的文件映射时，写入才会落进本进程的 COW 副本；若它是共享映射或
    匿名可写段，含义完全不同。快照里 `r-xp` 的 `p` 就是私有位。
    """
    package_suffix = requirements["packagePathSuffix"]
    require(package_suffix and "/" not in package_suffix and not package_suffix.startswith("."),
            "合同宿主包路径后缀格式不正确")
    report = []
    paths = set()
    for site in sites:
        address = base + number(site["offset"])
        matches = [region for region in regions
                   if region["start"] <= address < region["end"]]
        require(len(matches) == 1, "调用点不在唯一映射内：%#x" % number(site["offset"]))
        region = matches[0]
        perms = region["perms"]
        # 快照的权限位是 `rwxp`/`rwxs` 形式，读写执行位可能出现在同一列，
        # 因此逐字符判断存在性，而不是按固定下标取值。
        require("x" in perms, "调用点所在映射不可执行：%#x" % number(site["offset"]))
        require("r" in perms, "调用点所在映射不可读：%#x" % number(site["offset"]))
        require(len(perms) == 4 and perms[3] == "p",
                "调用点所在映射不是私有映射，拒绝该候选：%#x"
                % number(site["offset"]))
        require("w" not in perms, "调用点所在映射已经可写，与待发布前提矛盾：%#x"
                % number(site["offset"]))
        require(region["path"], "调用点所在映射没有文件支撑：%#x" % number(site["offset"]))
        require(package_suffix in region["path"],
                "调用点所在映射不属于已审核宿主包路径：%#x" % number(site["offset"]))
        paths.add(region["path"])
        report.append({"功能": site["feature"], "调用点": hex(number(site["offset"])),
                       "权限": perms, "文件偏移": hex(region["offset"])})
    require(len(paths) == 1, "候选调用点不在同一个宿主模块里")
    return report


def verify_contract(image, contract, regions, base):
    require(contract["schemaVersion"] == 1, "合同版本不受支持")
    require(contract["policy"] == POLICY, "合同策略不是蹦床落点可达性候选")
    require(contract["installPermitted"] is False, "合同不得声明安装许可")
    verify_image(image, contract)
    requirements = contract["mapsRequirements"]
    holes = verify_maps_requirements(regions, requirements)
    sites = contract["sites"]
    require(sites, "合同不得省略候选调用点")
    verify_site_words(image, sites)
    mappings = verify_site_mappings(regions, sites, base, requirements)
    require(base % 0x1000 == 0 and base > 0, "宿主加载基址未按页对齐")
    report = verify_landing(sites, holes, requirements, base)
    gates = contract.get("notClosedGates") or []
    require(len(gates) >= 3, "未关闭门槛声明不足")
    for prefix in ("门槛1", "门槛3", "门槛5"):
        require(any(gate.startswith(prefix) for gate in gates),
                "必须显式保留 %s 未关闭" % prefix)
    return {"策略": POLICY, "快照区间数": len(regions), "未映射空洞数": len(holes),
            "调用点": len(sites), "调用点映射": mappings, "落点": report,
            "未关闭门槛": len(gates),
            "安装许可": False,
            "限制": "只证明「存在可达的页对齐空洞」这一编码前提；不证明该空洞在安装窗口内"
                    "仍空闲、不被分配器或其它模块占用，也不证明蹦床语义、缓存同步与"
                    "写入独占性。调用点映射检查只说明该次快照里代码段是私有文件映射，"
                    "不构成运行期不变的保证"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host-elf", type=Path, required=True, help="固定宿主 libapp.so")
    parser.add_argument("--maps", type=Path, required=True,
                        help="本机进程映射快照文本（不入库）")
    parser.add_argument("--base", required=True,
                        help="该快照对应的宿主加载基址（十六进制，本机运行期信息）")
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT,
                        help="蹦床落点可达性候选合同 JSON")
    args = parser.parse_args()
    try:
        image = ElfImage(args.host_elf.read_bytes())
        contract = json.loads(args.contract.read_text(encoding="utf-8"))
        verify_image(image, contract)
        base = int(args.base, 0)
        regions = parse_maps(args.maps.read_text(encoding="utf-8", errors="strict"))
        print(json.dumps(verify_contract(image, contract, regions, base),
                         ensure_ascii=False, indent=2))
    except (AuditError, OSError, ValueError, KeyError, UnicodeDecodeError) as error:
        print("蹦床落点可达性审计失败：%s" % error)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
