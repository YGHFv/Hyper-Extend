#!/usr/bin/env python3
"""四项功能「调用点返回语义」的只读静态审计。

单字替换蹦床把某条调用点的 `BL 原目标` 换成 `BL 蹦床`。蹦床转发给原目标之后，
**调用者会怎样使用这一次调用的返回值**决定了蹦床允不允许改写 `x0`。四项功能在这里
分成两类，必须分别固定，不能混为一谈：

  数量点：调用点之后立刻 `mov x2, x0`（`0x114701c = 0xaa0003e2`），把 getter 返回的
          **未装箱整数**送进裁剪窗口；因此数量点专用蹦床**就是要改写 `w0`**。
  其余三项：调用点之后是 `mov x1, x0`（把 Dart 对象返回值存进帧槽）或 `b <尾部>`
          （返回值被丢弃、由尾部换成 `null`）。因此这三项的蹦床**不得改写 `x0`**，
          否则会把 Dart 对象换成别的东西。

由此得到两条互相独立的断言：

1. 对每个调用点，静态上下文里 `x0` 的用法必须与合同声明一致（消费 / 丢弃）；
2. 叶蹦床 `launcher_menu_island` 的机器字里**不得出现任何写 `x0`** 的指令
   （`launcher_menu_island_arm64.S` 只应动 `x16`），否则三项功能的返回值会被破坏。

本工具只读：不安装、不写内存、不链接 SO、不接触设备。`installPermitted` 必须为
false，通过也**不等于**门槛 5 关闭，更不等于功能已经实现。
"""

import argparse
import json
import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from launcher_native_audit import AuditError, ElfImage, digest, number, require  # noqa: E402
from launcher_shortcut_count_emulate import Arm64Object  # noqa: E402


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CONTRACT = ROOT / "app/src/test/resources/launcher-menu-return-contract.json"
POLICY = "menu-call-site-return-contract-offline-v1"

# 寄存器字段按 A64 固定位置取：Rd = bits 0..4、Rn = bits 5..9、Rm = bits 16..20。
# 掩码只含解码所需位，避免把 `add x16, x16, #0` 误判成 `add x0`。
FORWARD_READS_X0 = (("mov xd, x0", 0xFFFFFFE0, 0xAA0003E0),
                    ("mov wd, w0", 0xFFFFFFE0, 0x2A0003E0))
# 「把某个值写进 x0」的指令类别；类别掩码不含 Rd，Rd 由寄存器字段统一判零。
WRITE_X0_FORMS = (
    ("orr/mov x0", 0xFFE0FC00, 0xAA000000),
    ("orr/mov w0", 0xFFE0FC00, 0x2A000000),
    ("ldr x0", 0xFFC00000, 0xF9400000),
    ("ldr w0", 0xFFC00000, 0xB9400000),
    ("ldur x0", 0xFFE00C00, 0xF8400000),
    ("ldur w0", 0xFFE00C00, 0xB8400000),
    ("add x0(imm)", 0xFF800000, 0x91000000),
    ("add w0(imm)", 0xFF800000, 0x11000000),
    ("add x0(reg)", 0xFF200000, 0x8B000000),
    ("add w0(reg)", 0xFF200000, 0x0B000000),
    ("movz x0", 0xFF800000, 0xD2800000),
    ("movz w0", 0xFF800000, 0x52800000),
    ("movn x0", 0xFF800000, 0x92800000),
    ("movn w0", 0xFF800000, 0x12800000),
)
# 丢弃返回值：无条件 `b <尾部>`，或把返回值换成 Dart 空对象（`mov x0, x22`）。
DISCARD_FORMS = (("b <尾部>", 0xFC000000, 0x14000000),
                 ("null 对象 mov x0, x22", 0xFFE0FC00, 0xAA000000))

# 叶蹦床允许出现的**全部**指令形态；每条都按定义域解码寄存器号并按合同核对。
# 这是白名单：不在表内的机器字一律拒绝，不靠「看起来没写 x0」放过。
ISLAND_FORMS = (
    ("adrp x16, #page", 0x9F000000, 0x90000000, "Rd"),
    ("add x16, x16, #lo12", 0xFFC00000, 0x91000000, "Rd"),
    ("ldr x16, [x16]", 0xFFC00000, 0xF9400000, "Rd"),
    ("br x16", 0xFFFFFC1F, 0xD61F0000, "Rn"),
)
X16 = 16


def forward_target(word):
    """若该机器字是「把 x0 原样转发到别的寄存器」，返回目标寄存器号。

    ORR 家族只保留 Rn/Rm，独立核对 Rd != 0、Rn == XZR(31)、Rm == 0(x0)。
    """
    for name, mask, value in FORWARD_READS_X0:
        if word & mask != value:
            continue
        rd, rn, rm = word & 31, (word >> 5) & 31, (word >> 16) & 31
        if rd != 0 and rn == 31 and rm == 0:
            return "%s（目标寄存器 %d）" % (name, rd)
    return None


def discard_form(word):
    """无条件分支、或把返回值换成 Dart 空对象（`mov x0, x22`）都算丢弃。"""
    for name, mask, value in DISCARD_FORMS:
        if word & mask != value:
            continue
        if name.startswith("null"):
            rd, rn, rm = word & 31, (word >> 5) & 31, (word >> 16) & 31
            if (rd, rn, rm) != (0, 31, 22):
                continue
        return name
    return None


def writes_x0(word):
    """该机器字是否把结果写进 x0（Rd == 0）。"""
    if word & 31 != 0:
        return None
    for name, mask, value in WRITE_X0_FORMS:
        if word & mask == value:
            return name
    return None


def decode_island(word):
    """按白名单解码叶蹦床的一条指令；返回 (形态名, 寄存器字段值)。"""
    for name, mask, value, field in ISLAND_FORMS:
        if word & mask == value:
            register = (word & 0x1F) if field == "Rd" else ((word >> 5) & 0x1F)
            return name, register
    return None, None


def classify_site(image, site):
    """读调用点接续那一条指令，判定返回值是被消费还是被丢弃。"""
    address = number(site["offset"])
    call = struct.unpack("<I", image.code(address, 4))[0]
    require(call & 0xFC000000 == 0x94000000, "调用点不是 BL：%#x" % address)
    continuation = struct.unpack("<I", image.code(address + 4, 4))[0]
    require(continuation == number(site["continuationWord"]),
            "调用点接续指令与合同不符：%#x" % address)
    forwarded = forward_target(continuation)
    discarded = discard_form(continuation)
    if forwarded is not None:
        return {"形态": "转发", "说明": forwarded}
    if discarded is not None:
        return {"形态": "丢弃", "说明": discarded}
    raise AuditError("调用点接续既不是转发 x0、也不是丢弃返回值，需人工确认：%#x" % address)


def verify_island_preserves_x0(obj, contract):
    """叶蹦床不得写 x0；这条是三项功能返回值不被破坏的前提。

    逐条按白名单解码（adrp / add / ldr / br，且寄存器必须是 x16），不使用
    「匹配不到写 x0 就放过」的写法——叶蹦床只允许动 x16 这一个通用寄存器。
    """
    island = contract["island"]
    require(len(obj.code) == island["size"] and digest(obj.code) == island["sha256"],
            "叶蹦床字节长度或摘要与合同不符")
    words = struct.unpack("<%dI" % (len(obj.code) // 4), obj.code)
    require(list(words) == [number(item) for item in island["words"]],
            "叶蹦床机器字与合同逐条不符")
    for index, word in enumerate(words):
        written = writes_x0(word)
        require(written is None,
                "叶蹦床第 %d 条在写 x0（%s），会破坏三项功能的 Dart 对象返回值：%#010x"
                % (index, written, word))
        name, register = decode_island(word)
        require(name is not None,
                "叶蹦床第 %d 条不在已审核的指令白名单内，必须人工确认：%#010x" % (index, word))
        require(register == X16,
                "叶蹦床第 %d 条操作的不是 x16（寄存器 %d）：%#010x" % (index, register, word))
    require(island["preservesX0"] is True, "合同必须显式声明叶蹦床不改写 x0")
    return {"符号": island["symbol"], "指令数": len(words), "写 x0 的指令数": 0,
            "只使用寄存器": "x16"}


def verify_contract(image, contract, island):
    require(contract["schemaVersion"] == 1 and contract["policy"] == POLICY,
            "合同版本或策略不符")
    require(contract["installPermitted"] is False, "合同不得声明安装许可")
    gates = contract.get("notClosedGates") or []
    require(any(gate.startswith("门槛5") for gate in gates), "必须显式保留门槛 5 未关闭")
    expected = contract["image"]
    require(len(image.data) == expected["size"], "宿主文件长度与合同不符")
    require(digest(image.data) == expected["sha256"], "宿主文件摘要与合同不符")
    require(image.build_id() == expected["buildId"], "宿主 Build ID 与合同不符")

    sites = contract["sites"]
    require(len(sites) == contract["counts"]["sites"], "调用点数量与合同不符")
    report = []
    for site in sites:
        actual = classify_site(image, site)
        require(actual["形态"] == site["returnUse"],
                "调用点的返回值用法与合同不符：%#x 期望 %s 实际 %s"
                % (number(site["offset"]), site["returnUse"], actual["形态"]))
        require(site["allowsTrampolineToWriteX0"] == (site["returnUse"] == "转发"
                and site.get("rewriteReturn") is True),
                "「蹦床能否改写 x0」与返回值用法不一致：%#x" % number(site["offset"]))
        report.append({"功能": site["feature"], "调用点": hex(number(site["offset"])),
                       "返回值用法": actual["形态"], "依据": actual["说明"],
                       "蹦床可改写 x0": site["allowsTrampolineToWriteX0"]})
    preserved = verify_island_preserves_x0(island, contract)
    rewritten = {item["功能"] for item in report if item["蹦床可改写 x0"]}
    require(rewritten == {"数量点"},
            "只有数量点允许改写 x0，其余三项必须原样转发返回值：%s" % sorted(rewritten))
    return {"策略": POLICY, "调用点": report, "叶蹦床": preserved,
            "允许改写 x0 的功能": sorted(rewritten), "安装许可": False,
            "限制": "只固定调用点返回值用法与叶蹦床不改写 x0；不证明蹦床已完成缓存同步、"
                    "宿主映射生命周期或安装窗口无并发执行。"}


def main():
    parser = argparse.ArgumentParser(description="调用点返回语义的只读静态审计")
    parser.add_argument("--host-elf", type=Path, required=True, help="固定宿主 libapp.so")
    parser.add_argument("--object", type=Path, required=True,
                        help="交叉汇编得到的叶蹦床对象（launcher_menu_island_arm64.S）")
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT,
                        help="返回语义合同 JSON")
    args = parser.parse_args()
    try:
        image = ElfImage(args.host_elf.read_bytes())
        contract = json.loads(args.contract.read_text(encoding="utf-8"))
        island = Arm64Object(args.object.read_bytes(), contract["island"]["symbol"])
        print(json.dumps(verify_contract(image, contract, island),
                         ensure_ascii=False, indent=2))
    except (AuditError, OSError, ValueError, KeyError, struct.error) as error:
        print("调用点返回语义审计失败：%s" % error)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
