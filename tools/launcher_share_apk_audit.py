#!/usr/bin/env python3
"""「分享 APK」桌面原生入口试点的只读静态合同校验。

本工具只读取本地宿主 `libapp.so` 与合同 JSON：

- 不接入安装路径、不生成 SO、不加载目标库、不接触设备；
- 不把「入口替换候选」表述为已验收实现；
- `installPermitted` 必须为 false，否则直接失败。

已取证的判据（详见 `docs/launcher-shortcut-handoff-2026-10-13.md` 与
`app/src/test/resources/launcher-share-apk-pilot-contract.json`）：

1. Dart AOT 布尔编码：`x22` 是空对象基址，`x22+#0x20` 是真值对象、
`x22+#0x30` 是假值对象。多条独立目击都成立时才认这一编码。
2. `ValidShareAppShortcutMenu.shareApk` 是同步函数：末指令为 `ret`，
   成功路径返回 Dart null（`mov x0, x22`），调用链直达
   `ActivityManagerImpl.startActivity`。
3. `ValidShareAppShortcutMenu.isValid` 的两条可达出口都在 `x0` 装入
   Dart 假值对象，因此本轮不把它当作可见性挂载点，也不允许据此改写
   上游可见性链。该函数仍有一处本 CFG 之外的续体入口，判为未建模，
   不展开推论。

限制：只扫描 `.text` 的直接 `B`/`BL`，不覆盖间接调用、异常入口或运行期
生成的代码；不证明安装窗口内不存在并发执行。门槛 1、5 关闭前不得把本
合同的结论当作安装许可。
"""

import argparse
import json
import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from launcher_native_audit import (AuditError, ElfImage, digest, direct_branch,  # noqa: E402
                                   is_position_independent, number, require)


DEFAULT_CONTRACT = Path(__file__).resolve().parents[1] / "app/src/test/resources/launcher-share-apk-pilot-contract.json"
POLICY = "share-apk-entry-pilot-offline-v1"
COVERAGE_BYTES = 24


def word_at(image, address):
    return struct.unpack("<I", image.code(address, 4))[0]


def decode_add_x22(word):
    """解出 `ADD Xd, X22, #imm`（64 位、无移位）；不匹配返回 None。"""
    if word & 0xFF800000 != 0x91000000 or (word >> 22) & 3 or (word >> 5) & 31 != 22:
        return None
    return word & 31, (word >> 10) & 0xFFF


def decode_mov_from_x22(word):
    """解出 `MOV Xd, X22`（即 `ORR Xd, XZR, X22`）；不匹配返回 None。"""
    if word & 0xFFE0FFE0 != 0xAA0003E0 or (word >> 16) & 31 != 22:
        return None
    return word & 31


def decode_ldr_literal_x17(word):
    """解出远距补丁头的 `LDR X17, #imm`（64 位字面量，正偏移）；不匹配返回 None。"""
    if word & 0xFF000000 != 0x58000000 or word & 31 != 17:
        return None
    return ((word >> 5) & 0x7FFFF) * 4


def decode_frame_slot(word):
    """解出以 `x29` 为基址的 64 位 `LDUR/STUR` 槽访问；不匹配返回 None。"""
    if word & 0xFFC00000 == 0xF8400000:
        kind = "read"
    elif word & 0xFFC00000 == 0xF8000000:
        kind = "write"
    else:
        return None
    if (word >> 5) & 31 != 29:
        return None
    offset = (word >> 12) & 0x1FF
    if offset & 0x100:
        offset -= 0x200
    return kind, word & 31, offset


def slot_name(offset):
    return "0x%x" % offset if offset >= 0 else "-0x%x" % -offset


def group_frame_slots(image, start, size):
    """按槽偏移分组，返回 `{"-0xf0": {"writes": [...], "reads": [...]}}`。"""
    grouped = {}
    for relative in range(0, size, 4):
        address = start + relative
        decoded = decode_frame_slot(word_at(image, address))
        if decoded is None:
            continue
        kind, _, offset = decoded
        bucket = grouped.setdefault(slot_name(offset), {"writes": [], "reads": []})
        bucket["writes" if kind == "write" else "reads"].append(hex(address))
    return grouped


def executable_words(image):
    """遍历可执行的只读文件段，产出 (地址, 32 位字)。"""
    found = False
    for segment in image.segments:
        if segment["type"] != 1 or not segment["flags"] & 1:
            continue
        require(not segment["flags"] & 2, "出现可写可执行段，宿主不属于已审核映像")
        require(segment["address"] % 4 == 0 and segment["size"] % 4 == 0,
                "执行段未按指令对齐")
        raw = image.read(segment["offset"], segment["size"])
        found = True
        for index, (word,) in enumerate(struct.iter_unpack("<I", raw)):
            yield segment["address"] + index * 4, word
    require(found, "映像缺少可执行文件段")


def branches_into(image, low, high, include_low=True):
    """收集直接跳入 [low, high) 的指令；include_low=False 时排除入口本身。"""
    out = []
    for address, word in executable_words(image):
        edge = direct_branch(word, address)
        if edge is None:
            continue
        kind, target = edge
        inside = low <= target < high if include_low else low < target < high
        if inside:
            out.append({"address": hex(address), "kind": kind,
                        "target": hex(target), "word": "0x%08x" % word})
    return out


def pointer_references(image, address):
    """扫描全部文件字节中该地址的 64 位与 32 位截断编码。"""
    hits = []
    for width, fmt in ((8, "<Q"), (4, "<I")):
        pattern = struct.pack(fmt, address & ((1 << (width * 8)) - 1))
        cursor = 0
        while True:
            found = image.data.find(pattern, cursor)
            if found < 0:
                break
            hits.append({"width": width, "fileOffset": hex(found)})
            cursor = found + 1
    return hits


def count_tail_calls(image, table, target):
    """统计以 `B target` 作为函数末指令的函数个数（共享返回蹦床的复用面）。"""
    ends = {}
    for name, matches in table.items():
        for match in matches:
            if 0 < match["size"] <= 20000:
                ends[match["address"] + match["size"] - 4] = name
    count = 0
    for address, word in executable_words(image):
        if address in ends and word == (0x14000000 | (((target - address) // 4) & 0x03FFFFFF)):
            count += 1
    return count


def symbol_table(image):
    symbols = image.debug_image().functions()
    table = {}
    for symbol in symbols:
        table.setdefault(symbol["name"], []).append(symbol)
    return table


def require_function(image, table, expected, label):
    matches = table.get(expected["name"], [])
    require(len(matches) == 1, "%s 的宿主符号不唯一：%s" % (label, expected["name"]))
    symbol = matches[0]
    address, size = number(expected["address"]), expected["size"]
    require(symbol["address"] == address and symbol["size"] == size and size > 0,
            "%s 的函数范围与符号表不符：%s" % (label, expected["name"]))
    require(digest(image.code(address, size)) == expected["sha256"],
            "%s 的函数内容摘要不符：%s" % (label, expected["name"]))


def require_word(image, address, expected, label):
    actual = "0x%08x" % word_at(image, address)
    require(number(expected) == word_at(image, address),
            "%s 机器字不符：0x%x 期望 %s 实际 %s" % (label, address, expected, actual))


def verify_image(image, contract):
    expected = contract["image"]
    require(len(image.data) == expected["size"], "宿主文件长度与合同不符")
    require(digest(image.data) == expected["sha256"], "宿主文件摘要与合同不符")
    require(image.build_id() == expected["buildId"], "宿主 Build ID 与合同不符")


def verify_dart_boolean_encoding(image, contract):
    """核验布尔编码与全部目击；任一处不成立即拒绝。"""
    encoding = contract["dartBooleanEncoding"]
    true_word, false_word = number(encoding["trueWord"]), number(encoding["falseWord"])
    require(decode_add_x22(true_word) == (0, 0x20), "合同声明的真值编码不是 add x0, x22, #0x20")
    require(decode_add_x22(false_word) == (0, 0x30), "合同声明的假值编码不是 add x0, x22, #0x30")
    require(encoding["trueMeaning"] != encoding["falseMeaning"], "真值与假值语义不得相同")
    thunk = encoding["sharedReturnThunk"]
    thunk_address = number(thunk["address"])
    require(thunk_address % 4 == 0 and thunk_address > 0, "共享返回蹦床地址无效")
    require_word(image, thunk_address, thunk["headWord"], "共享返回蹦床首字")
    require(isinstance(thunk["tailUserCount"], int) and thunk["tailUserCount"] >= 2,
            "共享返回蹦床必须被多个函数复用，否则不能否定「末指令即异步」")
    actual_count = count_tail_calls(image, symbol_table(image), thunk_address)
    require(actual_count == thunk["tailUserCount"],
            "共享返回蹦床的复用计数不符：期望 %d 实际 %d" % (thunk["tailUserCount"], actual_count))
    checked = 0
    table = symbol_table(image)
    for witness in encoding["witnesses"]:
        if "function" in witness:
            require_function(image, table, witness["function"], "布尔目击")
        for check in witness["checks"]:
            address = number(check["address"])
            require_word(image, address, check["word"], "布尔目击 %s" % witness["name"])
            decoded = decode_add_x22(word_at(image, address))
            if check["role"] == "true":
                require(decoded is not None and decoded[1] == 0x20,
                        "布尔目击 %s 的真值操作数不符" % witness["name"])
            elif check["role"] == "false":
                require(decoded is not None and decoded[1] == 0x30,
                        "布尔目击 %s 的假值操作数不符" % witness["name"])
            elif check["role"] != "other":
                raise AuditError("布尔目击出现未知角色：%s" % check["role"])
            checked += 1
    require(checked >= 8, "布尔编码目击不足，不能据此断定真/假值对象")
    return {"真值编码": "0x%08x" % true_word, "假值编码": "0x%08x" % false_word,
            "目击数": checked, "共享返回蹦床": hex(thunk_address),
            "共享蹦床复用计数": thunk["tailUserCount"]}


def verify_far_patch_template(contract):
    """核对远距补丁模板四个机器字、字面量槽与文字描述三者互相一致。

    这里必须解出 `LDR` 的 imm19 并换算成字节偏移，再与 `literalSlot` 比较；
    只检查操作数是 x17 会漏掉「声明槽位与实际编码不符」的合同缺陷。
    """
    template = contract["farPatchTemplate"]
    require(template["coverageBytes"] == COVERAGE_BYTES,
            "远距补丁模板的覆盖长度不是已审核的 24 字节")
    words = [number(word) for word in template["words"]]
    require(len(words) == 4, "远距补丁模板必须恰好是 4 个 32 位机器字加末尾字面量")
    displacement = decode_ldr_literal_x17(words[0])
    require(displacement is not None, "模板首条不是 `ldr x17, #imm`")
    require(words[1] == 0xD61F0220, "模板第二条不是 `br x17`")
    require(words[2] == words[3] == 0xD503201F, "模板填充不是 NOP")
    slot = template["literalSlot"]
    require(slot == COVERAGE_BYTES - 8,
            "字面量槽位不是覆盖窗口末尾的 8 字节")
    require(displacement == slot,
            "模板 LDR 的 imm19 位移（%d）与声明的字面量槽位（%d）不一致" % (displacement, slot))
    require(displacement >= 4 and displacement + 8 <= COVERAGE_BYTES,
            "字面量槽位越出覆盖窗口")
    expected = ["ldr x17, #%d" % displacement, "br x17", "nop", "nop", "<8 字节岛地址字面量>"]
    if "mnemonics" in template:
        require(list(template["mnemonics"]) == expected,
                "模板助记符描述与机器字不符：期望 %s" % expected)
    return {"覆盖字节": COVERAGE_BYTES, "模板字": ["0x%08x" % word for word in words],
            "字面量槽位": slot, "LDR 位移": displacement}


def verify_pilot(image, contract):
    """核验分享 APK 入口候选：函数摘要、覆盖窗口、入口集合与同步证据。"""
    pilot = contract["pilot"]
    require(pilot["feature"] == "分享 APK", "试点功能不是分享 APK")
    require(pilot["installPermitted"] is False, "试点不得声明安装许可")
    table = symbol_table(image)
    require_function(image, table, pilot["target"], "入口候选")
    target = number(pilot["target"]["address"])

    window = pilot["window"]
    require(number(window["address"]) == target, "覆盖窗口起点不是目标函数入口")
    require(window["coverageBytes"] == COVERAGE_BYTES,
            "覆盖长度必须是已审核的 24 字节，不允许自动调整")
    raw = image.code(target, COVERAGE_BYTES)
    require(window["bytes"] == raw.hex(), "覆盖窗口字节与合同不符")
    require(window["sha256"] == digest(raw), "覆盖窗口摘要与合同不符")
    require([number(word) for word in window["words"]] == list(struct.unpack("<6I", raw)),
            "覆盖窗口机器字与合同不符")
    require(all(is_position_independent(word) for (word,) in struct.iter_unpack("<I", raw)),
            "覆盖窗口含位置相关指令，复制进中继后会偏离目标")
    require(window["positionIndependent"] is True, "合同未声明覆盖窗口位置无关")
    resume = number(window["resumeAddress"])
    require(resume == target + COVERAGE_BYTES, "恢复点不是覆盖窗口末尾")
    require(image.code(resume, 12).hex() == window["resumeBytes"], "恢复点后续字节与合同不符")

    entries = branches_into(image, target, target + 4)
    declared = [{"address": item["address"], "kind": item["kind"],
                 "target": item["target"], "word": item["word"]}
                for item in pilot["directEntries"]]
    require(entries == declared, "目标函数的直接入口集合发生变化")
    require(len(entries) == 2 and all(item["kind"] == "BL" for item in entries),
            "目标函数的直接入口必须恰好是两条 BL")
    interior = branches_into(image, target, target + COVERAGE_BYTES, include_low=False)
    require(interior == pilot["windowInteriorEntries"], "覆盖窗口内部出现直接入口")
    require(not interior, "覆盖窗口内部不得存在外部直接入口")
    require(pointer_references(image, target) == pilot["pointerReferences"],
            "目标函数的指针引用集合发生变化")
    require(not pilot["pointerReferences"], "目标函数出现静态指针引用，需重新审核入口假设")

    for site in pilot["callSites"]:
        address = number(site["address"])
        require_word(image, address, site["word"], "调用点")
        edge = direct_branch(word_at(image, address), address)
        require(edge == ("BL", target), "调用点不是指向目标函数的 BL")

    evidence = pilot["synchronousEvidence"]
    last = target + pilot["target"]["size"] - 4
    require(word_at(image, last) == 0xD65F03C0, "函数末指令不是 ret")
    require(evidence["lastInstruction"] == "ret", "合同中声明的末指令不是 ret")
    for address in evidence["epilogueWords"]:
        require_word(image, number(address), evidence["epilogueWords"][address], "同步收尾指令")
    null_return = number(evidence["nullReturnAddress"])
    require_word(image, null_return, evidence["nullReturnWord"], "成功路径返回")
    require(decode_mov_from_x22(word_at(image, null_return)) == 0,
            "成功路径不是 `mov x0, x22`（Dart null）")
    require(len(evidence["callees"]) >= 5, "同步调用链证据不足")
    for callee in evidence["callees"]:
        address = number(callee["address"])
        edge = direct_branch(word_at(image, address), address)
        require(edge == ("BL", number(callee["target"])), "同步调用链调用点不符")
    return {"入口": hex(target), "覆盖字节": COVERAGE_BYTES, "恢复点": hex(resume),
            "直接入口数": len(entries), "窗口内部入口数": len(interior),
            "指针引用数": len(pilot["pointerReferences"]),
            "安装许可": False}


def verify_is_valid_finding(image, contract):
    """核验 `isValid` 的否定性结论：两条可达出口都返回 Dart 假值。"""
    finding = contract["isValidFinding"]
    require(finding["name"] == "ValidShareAppShortcutMenu.isValid", "被审函数不是 isValid")
    table = symbol_table(image)
    require_function(image, table, finding["function"], "isValid")
    encoding = contract["dartBooleanEncoding"]
    false_word = number(encoding["falseWord"])
    thunk_address = number(encoding["sharedReturnThunk"]["address"])
    for exit_item in finding["exits"]:
        address = number(exit_item["address"])
        require_word(image, address, exit_item["word"], "isValid 出口")
        require(word_at(image, address) == false_word, "isValid 出口未返回 Dart 假值对象")
        nxt = exit_item["next"]
        require_word(image, number(nxt["address"]), nxt["word"], "isValid 出口续接")
        edge = direct_branch(word_at(image, number(nxt["address"])), number(nxt["address"]))
        require(edge == ("B", thunk_address), "isValid 出口未走共享返回蹦床")
    for target in finding["interiorBranchTargets"]:
        address = number(target["address"])
        require_word(image, address, target["word"], "isValid 内部跳转")
        edge = direct_branch(word_at(image, address), address)
        require(edge == ("B", number(target["target"])), "isValid 内部跳转目标不符")
    computed = finding["boolComputation"]
    require_word(image, number(computed["x16"]["address"]), computed["x16"]["word"], "布尔计算 x16")
    require_word(image, number(computed["x17"]["address"]), computed["x17"]["word"], "布尔计算 x17")
    require_word(image, number(computed["csel"]["address"]), computed["csel"]["word"], "布尔计算 csel")
    x16 = decode_add_x22(word_at(image, number(computed["x16"]["address"])))
    x17 = decode_add_x22(word_at(image, number(computed["x17"]["address"])))
    require(x16 is not None and x16[0] == 16 and x16[1] == 0x20,
            "csel 的真值分支不是 `add x16, x22, #0x20`")
    require(x17 is not None and x17[0] == 17 and x17[1] == 0x30,
            "csel 的假值分支不是 `add x17, x22, #0x30`")
    require(word_at(image, number(finding["resumeEntry"]["address"])) ==
            number(finding["resumeEntry"]["word"]), "续体入口机器字不符")
    frame = finding["frameMaterialize"]
    require_word(image, number(frame["address"]), frame["word"], "帧实体化调用")
    require(direct_branch(word_at(image, number(frame["address"])), number(frame["address"])) ==
            ("BL", number(frame["target"])), "帧实体化调用目标不符")

    function = finding["function"]
    actual = group_frame_slots(image, number(function["address"]), function["size"])
    expected = {key: {"writes": value["writes"], "reads": value["reads"]}
                for key, value in finding["frameSlots"].items()}
    require(actual == expected, "isValid 的帧槽读写集合发生变化")
    dead = [key for key, value in actual.items() if value["writes"] and not value["reads"]]
    require("-0xf0" in dead, "isValid 的 0xf0 槽不再是写后未读，结论前提被推翻")
    return {"出口数": len(finding["exits"]), "写后未读槽": sorted(dead),
            "结论": finding["conclusion"], "安装许可": False}


def verify_share_apk_contract(image, contract):
    """总入口；返回可打印的字典。任何一处不符都抛 `AuditError`。"""
    require(contract["schemaVersion"] == 1, "合同版本不受支持")
    require(contract["policy"] == POLICY, "合同策略不是分享 APK 入口试点")
    require(contract["installPermitted"] is False, "合同不得声明安装许可")
    verify_image(image, contract)
    result = {"布尔编码": verify_dart_boolean_encoding(image, contract),
              "远距补丁模板": verify_far_patch_template(contract),
              "入口试点": verify_pilot(image, contract),
              "isValid 结论": verify_is_valid_finding(image, contract),
              "安装许可": False,
              "限制": "仅静态只读校对；不覆盖间接/异常入口，不证明并发安装安全；"
                      "门槛 1、5 关闭前不得接入安装路径"}
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host-elf", type=Path, required=True, help="固定宿主 libapp.so")
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT,
                        help="分享 APK 入口试点合同 JSON")
    args = parser.parse_args()
    try:
        image = ElfImage(args.host_elf.read_bytes())
        contract = json.loads(args.contract.read_text(encoding="utf-8"))
        print(json.dumps(verify_share_apk_contract(image, contract),
                         ensure_ascii=False, indent=2))
    except (AuditError, OSError, struct.error, KeyError) as error:
        print("分享 APK 入口试点审计失败：%s" % error)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
