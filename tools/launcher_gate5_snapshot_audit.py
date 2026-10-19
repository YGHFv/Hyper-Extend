#!/usr/bin/env python3
"""门槛 5 映射快照的本地封存与回放；只读留存材料，不连接设备、不产生安装许可。"""

import argparse
import copy
import json
import os
import struct
import zipfile
from pathlib import Path

from launcher_gate5_residual_audit import (
    DEFAULT_CONTRACT, ROOT, file_identity, number, page_size, parse_maps,
    verify_apk_entry, verify_evidence_contract, verify_mapping_evidence,
    verify_no_product_write_path,
)
from launcher_native_audit import AuditError, ElfImage, digest, require


POLICY = "gate5-snapshot-replay-v1"
LOCAL_ROOT = ROOT / ".workbuddy"
DEFAULT_MANIFEST = LOCAL_ROOT / "launcher-gate5-snapshots.json"
BOUNDARY = ("只回放历史文件与拒绝对照，不证明快照完整采集、当前映射、实际内存类型、"
            "未来写入独占、卸载行为或线程时序；未执行残余项(c)，不关闭门槛 1、3、5。")


def negative_controls(image, contract, regions, elf_file_offset, base):
    """在内存副本上注入坏快照，每项必须由真实审计函数按预期原因拒绝。"""
    page = page_size(contract["mapsRequirements"])
    address = base + number(contract["sites"][0]["offset"])
    owner_index = next(index for index, region in enumerate(regions)
                       if region["start"] <= address < region["end"])
    owner = regions[owner_index]
    file_page = owner["offset"] + (address & ~(page - 1)) - owner["start"]
    # 只构造离线地址，不分配页面、不读取或写入进程内存。
    spare = max(region["end"] for region in regions) + page
    require(spare + page <= 1 << 64, "快照没有可用于合成对照的地址范围")
    results = []

    def expect(label, changed, reason, candidate_base=base):
        try:
            verify_mapping_evidence(image, contract, changed, elf_file_offset, candidate_base)
        except AuditError as error:
            require(reason in str(error), "拒绝对照「%s」在非预期位置失败：%s" % (label, error))
            results.append({"对照": label, "已拒绝": True})
        else:
            raise AuditError("拒绝对照「%s」被错误放行" % label)

    changed = copy.deepcopy(regions)
    changed[owner_index]["perms"] = "rwxp"
    expect("调用点改成 W+X", changed, "可写")

    changed = copy.deepcopy(regions)
    del changed[owner_index]
    expect("删除调用点映射", changed, "调用点没有唯一映射")

    changed = copy.deepcopy(regions)
    changed[owner_index]["offset"] += page
    expect("调用点文件偏移漂移一页", changed, "文件偏移与 APK/ELF 不一致")

    for perms, label, reason in (("rw-p", "远处可写文件页别名", "可写别名"),
                                 ("r--p", "远处只读文件页别名", "多个映射实体")):
        changed = copy.deepcopy(regions)
        changed.append(dict(owner, start=spare, end=spare + page, offset=file_page,
                            perms=perms, path="/data/local/tmp/alias.apk"))
        expect(label, changed, reason)

    changed = copy.deepcopy(regions)
    for region in changed:
        if file_identity(region) == file_identity(owner):
            region["path"] = "/data/app/%s.spoof/base.apk" % contract["mapsRequirements"]["packagePathSuffix"]
    expect("相似包名冒充宿主", changed, "已审核宿主包")

    changed = copy.deepcopy(regions)
    for left, right in zip(changed, changed[1:]):
        if (left["end"] == right["start"] and left["perms"].startswith("r-x")
                and right["perms"].startswith("rw-")):
            right["path"] = "[anon:已移除先例]"
    expect("移除 RX/RW 同实体先例", changed, "既有先例")

    changed = copy.deepcopy(regions)
    # 使用 ELF 最低文件段而非任意无关映射，证明不止检查六条调用点所在页。
    first = min(segment["address"] for segment in image.segments
                if segment["type"] == 1 and segment["size"])
    start = base + (first & ~(page - 1))
    changed = [region for region in changed if not region["start"] <= start < region["end"]]
    expect("删除非调用点的文件 PT_LOAD 页", changed, "文件 PT_LOAD 映射缺页")

    expect("显式基址漂移一页", regions, "调用点", base + page)
    return results


def audit_snapshot(image, contract, elf_file_offset, data, index):
    regions = parse_maps(data.decode("utf-8"))
    evidence = verify_mapping_evidence(image, contract, regions, elf_file_offset)
    base = int(evidence["加载基址"], 0)
    controls = negative_controls(image, contract, regions, elf_file_offset, base)
    # 汇总只输出序号、摘要与计数；路径、基址、inode 不进入可分享报告。
    return {"快照序号": index, "快照摘要": digest(data), "映射段数": len(regions),
            "调用点数": len(contract["sites"]),
            "文件 PT_LOAD 数": evidence["残余项(b) 宿主映像映射生命周期"]["已核对文件 PT_LOAD"],
            "W+X 段数": evidence["残余项(a) 蹦床页内存属性与写入独占性"]["W+X 段数"],
            "基址来源": "APK 条目偏移 + 全部文件 PT_LOAD 唯一候选",
            "拒绝对照": controls}


def pinned_record(path, data, parent):
    path = Path(path).resolve()
    try:
        value = Path(os.path.relpath(path, parent)).as_posix()
    except ValueError:
        value = path.as_posix()
    return {"path": value, "sha256": digest(data)}


def read_pinned(record, parent, label):
    require(isinstance(record, dict) and isinstance(record.get("path"), str)
            and record["path"], "%s 缺少本机文件路径" % label)
    data = (parent / record["path"]).read_bytes()
    require(record.get("sha256") == digest(data), "%s 摘要漂移，拒绝静默替换材料" % label)
    return data


def materialize(host_data, apk_data, contract_data):
    contract = json.loads(contract_data)
    image = ElfImage(host_data)
    verify_evidence_contract(image, contract)
    offset = verify_apk_entry(apk_data, image, contract["mapsRequirements"])
    verify_no_product_write_path()
    return image, contract, offset


def report(snapshots):
    return {"策略": POLICY, "快照数": len(snapshots), "快照回放": snapshots,
            "安装许可": False, "未关闭门槛": [3, 5], "限制": BOUNDARY}


def record_manifest(output, host_elf, host_apk, maps_paths, contract_path=DEFAULT_CONTRACT):
    output = Path(output).resolve()
    require(output.is_relative_to(LOCAL_ROOT.resolve()), "含本机路径的清单只能封存在 .workbuddy 内")
    require(not output.exists(), "本地清单已存在；请回放或使用新文件名，不覆盖旧证据")
    require(maps_paths, "至少需要一份映射快照")
    host_data, apk_data, contract_data = (Path(path).read_bytes()
                                        for path in (host_elf, host_apk, contract_path))
    image, contract, offset = materialize(host_data, apk_data, contract_data)
    records, snapshots = [], []
    for index, path in enumerate(maps_paths, 1):
        data = Path(path).read_bytes()
        require(digest(data) not in {record["sha256"] for record in records}, "不接受重复快照")
        snapshots.append(audit_snapshot(image, contract, offset, data, index))
        records.append(pinned_record(path, data, output.parent))
    manifest = {"schemaVersion": 1, "policy": POLICY, "installPermitted": False,
                "notClosedGates": [3, 5], "contractSha256": digest(contract_data),
                "hostElf": pinned_record(host_elf, host_data, output.parent),
                "hostApk": pinned_record(host_apk, apk_data, output.parent), "snapshots": records}
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("x", encoding="utf-8") as stream:
        stream.write(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    return report(snapshots)


def replay_manifest(path, contract_path=DEFAULT_CONTRACT):
    path = Path(path).resolve()
    manifest = json.loads(path.read_text(encoding="utf-8"))
    require(manifest["schemaVersion"] == 1 and manifest["policy"] == POLICY, "快照清单版本或策略不符")
    require(manifest["installPermitted"] is False and manifest["notClosedGates"] == [3, 5],
            "快照清单不得改变安装许可或未关闭门槛")
    contract_data = Path(contract_path).read_bytes()
    require(manifest["contractSha256"] == digest(contract_data), "残余合同摘要漂移，需重新审核并封存")
    host_data = read_pinned(manifest["hostElf"], path.parent, "宿主 ELF")
    apk_data = read_pinned(manifest["hostApk"], path.parent, "宿主 APK")
    image, contract, offset = materialize(host_data, apk_data, contract_data)
    entries = manifest["snapshots"]
    require(isinstance(entries, list) and entries, "快照清单不能为空")
    snapshots, seen = [], set()
    for index, entry in enumerate(entries, 1):
        data = read_pinned(entry, path.parent, "映射快照")
        require(digest(data) not in seen, "不接受重复快照")
        seen.add(digest(data))
        snapshots.append(audit_snapshot(image, contract, offset, data, index))
    return report(snapshots)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    record = sub.add_parser("record", help="首次审核并封存材料路径与摘要，只写本机忽略目录")
    record.add_argument("--host-elf", type=Path, required=True)
    record.add_argument("--host-apk", type=Path, required=True)
    record.add_argument("--maps", type=Path, action="append", required=True, help="可重复指定多份完整快照")
    record.add_argument("--output", type=Path, default=DEFAULT_MANIFEST)
    replay = sub.add_parser("replay", help="一键回放，不再手工输入快照路径或基址")
    replay.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    for command in (record, replay):
        command.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT)
    args = parser.parse_args(argv)
    try:
        if args.command == "record":
            result = record_manifest(args.output, args.host_elf, args.host_apk, args.maps, args.contract)
        else:
            result = replay_manifest(args.manifest, args.contract)
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except (AuditError, OSError, ValueError, KeyError, TypeError, struct.error, zipfile.BadZipFile) as error:
        print("门槛 5 快照回归失败：%s" % error)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
