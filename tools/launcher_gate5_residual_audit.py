#!/usr/bin/env python3
"""门槛 5 三项残余的只读取证审计。

门槛 5 = 「安装瞬间若还有其它线程正执行目标函数，后端是否保证跳转补丁与中继发布
对并发线程可见」。本仓库已把它从「24 字节多字覆盖不可原子」收窄成
**「单条对齐 32 位提交 + 预发布蹦床」**（依据：ARM DDI 0487 L.a B2.2.5 例外集合
与 Arm 官方文章《Caches and Self-Modifying Code: Working with Threads》的
预取推测保护，见 `launcher_singleword_publication_audit.py`）。

收窄之后仍有**三项必须取证**的残余，本工具逐项给出机器可查的证据：

(a) 蹦床页内存属性与写入独占性
    进程级映射快照里不得存在任何可写可执行段；调用点文件页按设备号、inode、
    文件偏移在全进程查别名，不能仅检查同一虚拟地址。快照里须存在同一实体
    「RX 代码页紧邻 RW 数据页」的既有先例；不代表未来新页分配必然获准。

(b) 宿主映像映射生命周期
    仅核对生命周期相关的静态结构：无可写可执行 PT_LOAD，动态段无
    NEEDED/INIT/FINI/INIT_ARRAY/FINI_ARRAY/RELA/REL/JMPREL/PLTGOT。由留存 APK 的
    未压缩 ELF 条目独立确定文件起点，逐片核对全部文件 PT_LOAD 与六条调用点。
    这些证据不证明未来不会改页、卸载或重映射。

(c) 提交确为单条对齐 32 位存储（针对**生产实现**）
    用真实 NDK 工具链交叉编译 `app/src/main/cpp/launcher/singleword_publish.cpp`，
    反汇编后要求 `launcher_commit_call_site` 恰好是「一条 32 位存储 + ret」，
    且缓存同步函数严格是 dc cv* → dsb ish → ic iv* → dsb ish 且不含任何数据存储。

    2026-10-13 升级：本项原先编译的是测试目录里的等价写法
    （`app/src/test/cpp/launcher_singleword_commit_trial.cpp`），只能证明「协议这么写
    会得到单条存储」。现在编译的是**真正要安装的那份实现**，因此机器码证据与产物
    一一对应；合同里的 `trialSource` 固定该路径，工具会核对它没有被换成别的文件。
    同一份实现的协议判定还由 `app/src/test/cpp/launcher_singleword_publish_test.cpp`
    在正式 Android 构建里做编译期断言。

本工具只读：不写内存、不分配页面、不链接 SO、不接触设备。`installPermitted` 必须为
    false，全部通过也**不等于**门槛 5 关闭——它只增加静态取证，
是否放行仍须独立审核实机安装实现与运行期复核。
"""

import argparse
import io
import json
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from launcher_native_audit import AuditError, ElfImage, digest, number, require  # noqa: E402


MAP_PATTERN = re.compile(
    r"^(?P<start>[0-9a-f]+)-(?P<end>[0-9a-f]+)\s+"
    r"(?P<perms>[r-][w-][x-][ps])\s+(?P<offset>[0-9a-f]+)\s+"
    r"(?P<dev>[0-9a-f]+:[0-9a-f]+)\s+(?P<inode>[0-9]+)\s*(?P<path>.*)$")


def parse_maps(text):
    """解析映射快照，保留 inode 与路径（写入独占与同一映像判定都要用）。"""
    regions = []
    for line in text.splitlines():
        if not line.strip():
            continue
        match = MAP_PATTERN.match(line)
        require(match is not None, "映射快照存在无法解析的行")
        start, end = int(match["start"], 16), int(match["end"], 16)
        require(0 <= start < end <= 1 << 64, "映射区间为空、倒置或越界")
        # 匿名映射的 inode 必须是 0，文件映射的 inode 不能是 0；两者必须一致。
        dev = ":".join(format(int(part, 16), "02x") for part in match["dev"].split(":"))
        inode = str(int(match["inode"]))
        require((inode == "0") == (dev == "00:00"),
                "匿名/文件属性与 inode、设备号不一致")
        file_offset = int(match["offset"], 16)
        require(file_offset + end - start <= 1 << 64, "映射文件区间越界")
        regions.append(dict(start=start, end=end, perms=match["perms"],
                            offset=file_offset, dev=dev,
                            inode=inode, path=match["path"].strip()))
    require(regions, "映射快照为空")
    regions.sort(key=lambda item: item["start"])
    for left, right in zip(regions, regions[1:]):
        require(left["end"] <= right["start"], "映射快照存在重叠区间：%#x" % left["end"])
    return regions


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CONTRACT = ROOT / "app/src/test/resources/launcher-gate5-residual-contract.json"
DEFAULT_TRIAL = ROOT / "app/src/main/cpp/launcher/singleword_publish.cpp"
POLICY = "gate5-residual-offline-v1"

RET = 0xD65F03C0
DSB_ISH = 0xD5033B9F

# 单条对齐 32 位存储；多字存储（stp 等）不在表内，遇到即拒绝。
COMMIT_STORE_MASKS = (("stlr w", 0xFFFFFC00, 0x889FFC00, "RELEASE 32 位"),
                      ("str w", 0xFFC00000, 0xB9000000, "普通对齐 32 位"),
                      ("stur w", 0xFFE00C00, 0xB8000000, "未缩放 32 位"))
DC_MASKS = (("dc cvau", 0xFFFFFFE0, 0xD50B7B20), ("dc cvac", 0xFFFFFFE0, 0xD50B7E20),
            ("dc cvap", 0xFFFFFFE0, 0xD50B7C20))
IC_MASKS = (("ic ivau", 0xFFFFFFE0, 0xD50B7520),)


def commit_store(word):
    for name, mask, value, note in COMMIT_STORE_MASKS:
        if word & mask == value:
            return {"指令": name, "语义": note}
    return None


def any_data_store(word):
    """是否是写内存的数据存储（含 STP/LDP 等多字与 64 位形式）。"""
    if word & 0x3A000000 == 0x28000000 or word & 0x3B000000 == 0x28000000:
        return "stp/stnp"
    for mask, value, name in ((0xFFC00000, 0xB9000000, "str w"), (0xFFE00C00, 0xB8000000, "stur w"),
                              (0xFFFFFC00, 0x889FFC00, "stlr w"), (0xFFC00000, 0xF9000000, "str x"),
                              (0xFFE00C00, 0xF8000000, "stur x"), (0xFFFFFC00, 0xC89FFC00, "stlr x")):
        if word & mask == value:
            return name
    return None


def dc_kind(word):
    for name, mask, value in DC_MASKS:
        if word & mask == value:
            return name
    return None


def ic_kind(word):
    for name, mask, value in IC_MASKS:
        if word & mask == value:
            return name
    return None


def verify_site_words(image, sites):
    """调用点必须仍是合同固定的 `BL 原目标`；任何漂移都直接拒绝，不靠运行期兜底。"""
    seen = set()
    report = []
    for site in sites:
        address = number(site["offset"])
        require(address not in seen, "候选调用点重复：%#x" % address)
        seen.add(address)
        word, = struct.unpack("<I", image.code(address, 4))
        require(word == number(site["word"]), "调用点机器字与合同不符：%#x" % address)
        require(word & 0xFC000000 == 0x94000000, "调用点不是 BL：%#x" % address)
        displacement = word & 0x03FFFFFF
        if displacement & 0x02000000:
            displacement -= 0x04000000
        require(address + displacement * 4 == number(site["target"]),
                "调用点目标与合同不符：%#x" % address)
        report.append({"功能": site["feature"], "偏移": hex(address), "机器字": hex(word),
                       "目标": site["target"]})
    return report


def verify_commit_symbol(words, symbol):
    """提交函数必须是「一条 32 位存储 + ret」。"""
    require(len(words) == 2, "%s 必须恰好两条指令，实际 %d 条" % (symbol, len(words)))
    require(words[1] == RET, "%s 第二条指令不是 ret：%#010x" % (symbol, words[1]))
    kind = commit_store(words[0])
    require(kind is not None,
            "%s 不是单条 32 位存储（可能是多字覆盖）：%#010x" % (symbol, words[0]))
    return {"符号": symbol, "指令数": len(words), "存储": kind["指令"],
            "存储机器字": hex(words[0]), "语义": kind["语义"]}


def verify_sync_symbol(words, symbol):
    """缓存同步函数必须严格是 dc → dsb ish → ic → dsb ish，且不含任何数据存储。"""
    for index, word in enumerate(words):
        store = any_data_store(word)
        require(store is None, "%s 第 %d 条出现数据存储 %s：%#010x" % (symbol, index, store, word))
    dc_index = next((index for index, word in enumerate(words) if dc_kind(word)), None)
    require(dc_index is not None, "%s 缺少 dc cv* 缓存维护" % symbol)
    dsb1 = next((index for index, word in enumerate(words) if index > dc_index and word == DSB_ISH), None)
    require(dsb1 is not None, "%s 的 dc 之后缺少 dsb ish" % symbol)
    ic_index = next((index for index, word in enumerate(words) if index > dsb1 and ic_kind(word)), None)
    require(ic_index is not None, "%s 的 dsb 之后缺少 ic ivau" % symbol)
    dsb2 = next((index for index, word in enumerate(words) if index > ic_index and word == DSB_ISH), None)
    require(dsb2 is not None, "%s 的 ic 之后缺少第二个 dsb ish" % symbol)
    return {"符号": symbol, "指令数": len(words), "缓存维护": dc_kind(words[dc_index]),
            "指令同步": ic_kind(words[ic_index]), "顺序": "dc → dsb ish → ic → dsb ish"}


def parse_rel_object(data, wanted):
    """从 ET_REL 对象里取出指定函数符号的机器字；只接受单一 .text 段、隐藏全局函数。"""
    require(len(data) >= 64 and data[:7] == b"\x7fELF\x02\x01\x01", "试验对象不是 ELF64 小端")
    kind, machine, version, _, phoff, shoff, _, ehsize, _, phnum, shsize, shnum, names = (
        struct.unpack_from("<HHIQQQIHHHHHH", data, 16))
    require((kind, machine, version, ehsize, phoff, phnum, shsize) == (1, 183, 1, 64, 0, 0, 64),
            "试验对象不是无程序段的 ARM64 可重定位文件")
    require(0 < names < shnum < 4096, "试验对象节表编号无效")
    sections = [struct.unpack_from("<IIQQQQIIQQ", data, shoff + i * 64) for i in range(shnum)]
    require(sections[names][1] == 3, "试验对象缺少节名字符串表")
    strings = data[sections[names][4]:sections[names][4] + sections[names][5]]
    named = {}
    for index, section in enumerate(sections):
        end = strings.find(b"\0", section[0])
        require(end >= 0, "试验对象节名缺结束符")
        named[strings[section[0]:end].decode()] = index
    require(all(name in named for name in (".text", ".symtab")), "试验对象缺少 .text 或 .symtab")
    text = sections[named[".text"]]
    require(text[1] == 1 and text[5] % 4 == 0 and 0 < text[5] <= 8192, "试验对象 .text 段不受支持")
    code = data[text[4]:text[4] + text[5]]
    table = sections[named[".symtab"]]
    require(table[1] == 2 and table[9] == 24 and table[5] % 24 == 0
            and table[6] < shnum and sections[table[6]][1] == 3, "试验对象符号表无效")
    names_blob = data[sections[table[6]][4]:sections[table[6]][4] + sections[table[6]][5]]
    found = {}
    for offset in range(table[4], table[4] + table[5], 24):
        name, info, other, section, value, size = struct.unpack_from("<IBBHQQ", data, offset)
        end = names_blob.find(b"\0", name)
        require(end >= 0, "试验对象符号名缺结束符")
        name = names_blob[name:end].decode()
        if name not in wanted:
            continue
        require(info & 15 == 2 and info >> 4 == 1 and other & 3 == 2,
                "试验函数 %s 不是隐藏的全局函数（STV_HIDDEN）" % name)
        require(section == named[".text"] and size % 4 == 0 and value + size <= len(code),
                "试验函数 %s 的代码范围非法" % name)
        require(name not in found, "试验函数 %s 在符号表里出现多次" % name)
        found[name] = list(struct.unpack_from("<%dI" % (size // 4), code, value))
    require(set(found) == set(wanted), "试验对象缺少函数：%s" % sorted(set(wanted) - set(found)))
    return found


def find_toolchain(explicit):
    """定位 NDK clang / objdump；只读探测，不修改环境。"""
    if explicit:
        clang = Path(explicit)
        require(clang.is_file(), "指定的 clang 不存在：%s" % clang)
    else:
        clang = shutil.which("clang")
        require(clang is not None, "未找到 clang；请用 --clang 指定 NDK clang.exe")
        clang = Path(clang)
    objdump = clang.with_name("llvm-objdump.exe" if sys.platform == "win32" else "llvm-objdump")
    require(objdump.is_file(), "同目录未找到 llvm-objdump：%s" % objdump)
    return clang, objdump


TRIAL_FLAGS = ("--target=aarch64-linux-android29", "-O3", "-std=c++17", "-c",
               "-Wall", "-Wextra", "-Werror", "-fno-exceptions", "-fno-rtti",
               "-fvisibility=hidden", "-mno-outline-atomics")


def compile_trial(clang, objdump, source, output):
    """用真实工具链交叉编译并反汇编；参数固定，避免不同优化档产生不同结论。"""
    command = [str(clang), *TRIAL_FLAGS, str(source), "-o", str(output)]
    result = subprocess.run(command, capture_output=True, text=True, check=False)
    require(result.returncode == 0, "试验源交叉编译失败：%s" % (result.stderr or "").strip())
    listing = subprocess.run([str(objdump), "-d", "--no-show-raw-insn", str(output)],
                             capture_output=True, text=True, check=False)
    require(listing.returncode == 0, "试验对象反汇编失败")
    return result.stdout, listing.stdout


def verify_commit_trial(clang, objdump, source, symbols):
    """编译试验源，核对提交/同步/组合顺序三段机器码。"""
    with tempfile.TemporaryDirectory(prefix="gate5-commit-") as folder:
        obj = Path(folder) / "trial.o"
        stdout, listing = compile_trial(clang, objdump, source, obj)
        require(len(listing) > 0, "反汇编输出为空")
        words = parse_rel_object(obj.read_bytes(), tuple(symbols))
    commit = verify_commit_symbol(words[symbols[0]], symbols[0])
    sync = verify_sync_symbol(words[symbols[1]], symbols[1])
    order = verify_publish_order(words[symbols[2]], symbols[2])
    # 报告里记仓库相对路径（正斜杠），与合同 trialSource 字段同一写法，便于逐字比对。
    return {"源码": Path(source).resolve().relative_to(ROOT).as_posix(),
            "编译参数": " ".join(TRIAL_FLAGS), "提交函数": commit, "缓存同步": sync,
            "组合顺序": order}


def verify_publish_order(combined, symbol):
    """组合函数必须「先缓存同步、再提交」，且提交只有一条 32 位存储。

    单独拆出来是为了让故障审计能用**同一套判定**去跑真实的错误实现，
    而不是只在合成机器字上自证。
    """
    stores = [index for index, word in enumerate(combined) if any_data_store(word)]
    require(stores, "%s 没有任何提交存储" % symbol)
    for index in stores:
        require(commit_store(combined[index]) is not None,
                "%s 第 %d 条不是单条 32 位存储：%#010x" % (symbol, index, combined[index]))
    require(len({combined[index] for index in stores}) == 1,
            "%s 的多条存储不是同一条指令：%s" % (symbol, [hex(combined[i]) for i in stores]))
    dc_index = next((index for index, word in enumerate(combined) if dc_kind(word)), None)
    ic_index = next((index for index, word in enumerate(combined) if ic_kind(word)), None)
    require(dc_index is not None and ic_index is not None, "%s 缺少缓存同步序列" % symbol)
    require(dc_index < ic_index < min(stores),
            "%s 未保证「先缓存同步、再提交」：dc=%d ic=%d store=%d"
            % (symbol, dc_index, ic_index, min(stores)))
    return {"先同步后提交": True, "dc 下标": dc_index, "ic 下标": ic_index, "存储下标": stores}


def verify_page_properties(regions, requirements, runtime_sites):
    """残余项 (a)：无 W+X 段、调用点页写入独占、存在 RX 紧邻 RW 的先例。"""
    page = page_size(requirements)
    wx = [region for region in regions if "w" in region["perms"] and "x" in region["perms"]]
    require(not wx, "映射快照出现可写可执行段：%s" % [hex(region["start"]) for region in wx[:4]])

    exclusivity = []
    for site in runtime_sites:
        address = site["runtime"]
        covering = [region for region in regions if region["start"] <= address < region["end"]]
        require(len(covering) == 1, "调用点没有唯一的所属映射：%#x" % address)
        owner = covering[0]
        page_start = address & ~(page - 1)
        page_end = page_start + page
        overlapping = [region for region in regions
                       if region["start"] < page_end and page_start < region["end"]]
        writable = [region for region in overlapping if "w" in region["perms"]]
        require(not writable, "调用点所在页同时可写，不满足写入独占：%#x" % address)
        require(owner["inode"] != "0", "调用点所在页没有文件支撑")
        file_start = owner["offset"] + page_start - owner["start"]
        # 别名可能位于完全不同的虚拟地址，甚至有不同路径或权限；按文件实体与偏移相交判定。
        aliases = [region for region in regions if file_identity(region) == file_identity(owner)
                   and region["offset"] < file_start + page
                   and file_start < region["offset"] + region["end"] - region["start"]]
        require(not any("w" in region["perms"] for region in aliases),
                "调用点文件页存在可写别名：%#x" % address)
        require(len(aliases) == 1, "调用点所在页在进程里出现多个映射实体：%#x" % address)
        exclusivity.append({"调用点": site["feature"], "运行地址": hex(address),
                            "权限": owner["perms"], "同页映射数": len(aliases)})

    precedent = None
    ordered = sorted(regions, key=lambda region: region["start"])
    for left, right in zip(ordered, ordered[1:]):
        if left["end"] != right["start"]:
            continue
        left_exec = left["perms"].startswith("r-x")
        right_data = right["perms"].startswith("rw-")
        # 同一实体：文件映射靠 inode+路径判定，匿名具名映射（如 [anon:...]）靠标签判定。
        same_image = bool(left["path"]) and file_identity(left) == file_identity(right) \
            and left["path"] == right["path"]
        if left_exec and right_data and same_image:
            precedent = {"起始": hex(left["start"]), "RX 区间": hex(left["end"] - left["start"]),
                         "RW 区间": hex(right["end"] - right["start"]), "来源": left["path"],
                         "说明": "同一模块的 RX 代码页紧邻 RW 数据页"}
            break
    require(precedent is not None, "映射快照里找不到「RX 代码页紧邻 RW 数据页」的既有先例")
    return {"W+X 段数": 0, "调用点写入独占": exclusivity, "页属性先例": precedent}


DYNAMIC_TAGS = {
    0x00000001: "NEEDED", 0x0000000C: "INIT", 0x0000000D: "FINI",
    0x00000019: "INIT_ARRAY", 0x0000001A: "INIT_ARRAYSZ",
    0x0000001B: "FINI_ARRAY", 0x0000001C: "FINI_ARRAYSZ",
    0x00000007: "RELA", 0x00000011: "REL", 0x00000017: "JMPREL",
    0x00000003: "PLTGOT",
}


def read_dynamic(image):
    """解析 PT_DYNAMIC；只接受本工具关心的标记，其余原样忽略。"""
    entries = []
    for segment in image.segments:
        if segment["type"] != 2:
            continue
        raw = image.read(segment["offset"], segment["size"])
        require(len(raw) % 16 == 0, "动态段长度不是 16 的整数倍")
        for offset in range(0, len(raw), 16):
            tag, value = struct.unpack_from("<QQ", raw, offset)
            entries.append((tag, value))
    require(entries, "宿主缺少 PT_DYNAMIC")
    return entries


def verify_host_lifecycle(image, contract, sites):
    """残余项 (b)：宿主不可写可执行、无卸载期钩子、调用点页是直接文件映射。"""
    for segment in image.segments:
        if segment["type"] != 1:
            continue
        require(not (segment["flags"] & 1 and segment["flags"] & 2),
                "宿主 PT_LOAD 同时可写可执行：%s" % hex(segment["address"]))
    dynamic = read_dynamic(image)
    present = sorted({DYNAMIC_TAGS[tag] for tag, _ in dynamic if tag in DYNAMIC_TAGS})
    require(not present, "宿主动态段含改址或卸载期钩子标记：%s" % present)
    require(any(tag == 0x00000006 for tag, _ in dynamic), "宿主动态段缺少 SYMTAB")
    require(any(tag == 0x00000005 for tag in [t for t, _ in dynamic]), "宿主动态段缺少 STRTAB")

    mappings = []
    for site in sites:
        address = number(site["offset"])
        segment = None
        for candidate in image.segments:
            if candidate["type"] == 1 and candidate["address"] <= address \
                    and address + 4 <= candidate["address"] + candidate["size"]:
                require(segment is None, "调用点落入多个 PT_LOAD：%#x" % address)
                segment = candidate
        require(segment is not None, "调用点不在任何 PT_LOAD 里：%#x" % address)
        require(segment["flags"] & 1, "调用点所在 PT_LOAD 不可执行：%#x" % address)
        require(not segment["flags"] & 2, "调用点所在 PT_LOAD 可写：%#x" % address)
        mappings.append({"功能": site["feature"], "偏移": hex(address),
                         "段地址": hex(segment["address"]), "段文件偏移": hex(segment["offset"])})
    return {"可写可执行 PT_LOAD": 0, "改址与卸载钩子": present, "调用点所属段": mappings}


def page_size(requirements):
    page = number(requirements["pageSize"])
    require(type(page) is int and page >= 4096 and page & (page - 1) == 0,
            "页大小必须是至少 4096 的二次幂")
    return page


def file_identity(region):
    return region["dev"], region["inode"]


def host_apk_path(path, package):
    """限定包目录边界与 base.apk，不能把包名前缀或已删除映射当作宿主。"""
    parts = path.split("/")
    return (len(parts) >= 4 and path.startswith("/data/app/") and parts[-1] == "base.apk"
            and (parts[-2] == package or parts[-2].startswith(package + "-")))


def verify_apk_entry(apk_data, image, requirements):
    """用留存 APK 的未压缩条目独立证明 ELF 起始文件偏移，不从 maps 反推该偏移。"""
    name = "lib/arm64-v8a/libapp.so"
    with zipfile.ZipFile(io.BytesIO(apk_data)) as archive:
        entries = [entry for entry in archive.infolist() if entry.filename == name]
        require(len(entries) == 1, "APK 必须只有一个宿主 ELF 条目")
        entry = entries[0]
        require(entry.compress_type == zipfile.ZIP_STORED and not entry.flag_bits & 9,
                "宿主 ELF 条目必须未压缩、未加密且无数据描述符")
        require(entry.file_size == entry.compress_size == len(image.data), "APK 内宿主长度不符")
        header = struct.unpack_from("<IHHHHHIIIHH", apk_data, entry.header_offset)
        require(header[0] == 0x04034B50 and header[2] == entry.flag_bits
                and header[3] == entry.compress_type and header[6] == entry.CRC
                and header[7] == header[8] == entry.file_size, "APK 本地条目头与目录不一致")
        name_start = entry.header_offset + 30
        require(apk_data[name_start:name_start + header[9]] == name.encode("ascii"),
                "APK 本地条目名与目录不一致")
        offset = name_start + header[9] + header[10]
        require(offset % page_size(requirements) == 0, "APK 内宿主起始偏移未按页对齐")
        require(archive.read(entry) == image.data
                and apk_data[offset:offset + len(image.data)] == image.data,
                "APK 内宿主内容与固定 ELF 不符")
    return offset


def verify_map_geometry(regions, requirements):
    page = page_size(requirements)
    require(regions, "映射快照为空")
    for region in regions:
        require(all(region[field] % page == 0 for field in ("start", "end", "offset")),
                "映射边界或文件偏移未按页对齐")


def verify_load_mappings(image, regions, requirements, base, elf_file_offset):
    """逐段、逐片核对全部文件支撑 PT_LOAD；允许同一段被 maps 拆分，不接受缺页。"""
    page = page_size(requirements)
    identities = set()
    loads = [segment for segment in image.segments if segment["type"] == 1 and segment["size"]]
    require(loads, "宿主缺少文件支撑 PT_LOAD")
    for segment in loads:
        begin = base + (segment["address"] & ~(page - 1))
        end = base + ((segment["address"] + segment["size"] + page - 1) & ~(page - 1))
        cursor = begin
        while cursor < end:
            covering = [region for region in regions if region["start"] <= cursor < region["end"]]
            require(len(covering) == 1, "文件 PT_LOAD 映射缺页或不唯一")
            region = covering[0]
            require(host_apk_path(region["path"], requirements["packagePathSuffix"])
                    and region["inode"] != "0", "文件 PT_LOAD 映射不是宿主 base.apk")
            actual = region["offset"] + cursor - region["start"]
            expected = elf_file_offset + segment["offset"] + cursor - base - segment["address"]
            require(actual == expected, "文件 PT_LOAD 映射偏移与 APK/ELF 不一致")
            require(region["perms"][0] == "r" and region["perms"][3] == "p"
                    and ("x" in region["perms"]) == bool(segment["flags"] & 1)
                    and ("w" not in region["perms"] or segment["flags"] & 2),
                    "文件 PT_LOAD 映射权限与 ELF 不一致")
            identities.add((*file_identity(region), region["path"]))
            cursor = min(end, region["end"])
    require(len(identities) == 1, "文件 PT_LOAD 不在同一个宿主映像里")
    return len(loads)


def derive_load_base(image, regions, requirements, elf_file_offset):
    """从 APK 内偏移与可执行段产生候选，再用全部文件 PT_LOAD 排除缺页与歧义。"""
    page = page_size(requirements)
    executable = [segment for segment in image.segments
                  if segment["type"] == 1 and segment["flags"] & 1 and segment["size"]]
    require(executable, "宿主缺少可执行 PT_LOAD")
    anchor = min(executable, key=lambda segment: segment["address"])
    offset = elf_file_offset + (anchor["offset"] & ~(page - 1))
    candidates = {region["start"] - (anchor["address"] & ~(page - 1)) for region in regions
                  if region["offset"] == offset and region["perms"] == "r-xp"
                  and host_apk_path(region["path"], requirements["packagePathSuffix"])}
    valid = []
    for base in sorted(candidates):
        if base <= 0 or base % page:
            continue
        try:
            verify_load_mappings(image, regions, requirements, base, elf_file_offset)
        except AuditError:
            continue
        valid.append(base)
    require(len(valid) == 1, "无法唯一推导加载基址：完整 PT_LOAD 候选数=%d" % len(valid))
    return valid[0]


def verify_runtime_mappings(regions, requirements, sites, base, image, elf_file_offset):
    """调用点必须是同一 base.apk 的私有文件映射，文件偏移逐字节匹配 APK 条目与 ELF。"""
    page = page_size(requirements)
    require(type(base) is int and 0 < base < 1 << 64 and base % page == 0, "加载基址非法或未按页对齐")
    suffix = requirements["packagePathSuffix"]
    observed = []
    inodes = set()
    deltas = set()
    for site in sites:
        offset = number(site["offset"])
        address = base + offset
        covering = [region for region in regions if region["start"] <= address
                    and address + 4 <= region["end"]]
        require(len(covering) == 1, "调用点没有唯一映射：%#x" % address)
        region = covering[0]
        require(region["path"], "调用点所在映射没有文件支撑：%#x" % address)
        require(host_apk_path(region["path"], suffix) and region["inode"] != "0",
                "调用点映射路径不属于已审核宿主包")
        require("p" in region["perms"] and "s" not in region["perms"],
                "调用点映射不是私有映射：%#x" % address)
        require("r" in region["perms"] and "x" in region["perms"],
                "调用点映射不是可读可执行：%#x" % address)
        require("w" not in region["perms"], "调用点映射可写：%#x" % address)
        require(region["offset"] % page == 0, "调用点映射文件偏移未按页对齐")
        actual = region["offset"] + address - region["start"]
        expected = elf_file_offset + image.file_offset(offset, 4, executable=True)
        require(actual == expected, "调用点映射文件偏移与 APK/ELF 不一致：%#x" % address)
        delta = actual - image.file_offset(offset, 4, executable=True)
        inodes.add((*file_identity(region), region["path"]))
        deltas.add(delta)
        observed.append({"功能": site["feature"], "运行地址": hex(address), "路径": region["path"],
                         "权限": region["perms"], "映射偏移": hex(region["offset"]),
                         "inode": region["inode"], "偏移差值": hex(delta)})
    require(len(inodes) == 1, "调用点不在同一个宿主映像里：%s" % sorted(inodes))
    require(len(deltas) == 1,
            "调用点所在映射偏移与 ELF 虚拟地址不一致：%s" % sorted(hex(item) for item in deltas))
    return observed, next(iter(deltas))


def verify_trial_source(contract, trial_source):
    """残余项 (c) 的证据必须来自**生产实现**，不能是测试目录里的等价写法。

    固定路径、而不是「随便哪个能编出单条存储的文件」：否则把证据来源换回替身
    （甚至换成另一份错误写法所对应的语法）也能一路通过。三条断言分别挡住
    「合同被改」「命令行指向别处」「指向测试目录」，任一条命中都直接失败。
    """
    declared = contract.get("trialSource")
    require(declared == DEFAULT_TRIAL.relative_to(ROOT).as_posix(),
            "合同的 trialSource 必须固定在生产实现上：%r" % declared)
    require(Path(trial_source).resolve() == DEFAULT_TRIAL.resolve(),
            "被编译的提交实现不是合同固定的生产实现：%s" % trial_source)
    require(not Path(trial_source).name.endswith("_trial.cpp")
            and "test" not in Path(trial_source).parts,
            "残余项 (c) 不接受测试目录里的替身实现：%s" % trial_source)
    return declared


def verify_no_product_write_path(cmake_path=None):
    """确认生产目标已完整接入代码页事务实现。

    这里的名字沿用旧接口，调用方仍可复用，但语义已从「禁止写入口」升级为
    「写入口与后端必须成组接入」。只链接单字提交而缺少岛页后端同样拒绝；
    阶段锁仍由 install_permit.h 独立保持，接入实现不等于授予安装许可。
    """
    cmake = Path(cmake_path) if cmake_path else ROOT / "app/src/main/cpp/CMakeLists.txt"
    require(cmake.is_file(), "找不到原生 CMakeLists：%s" % cmake)
    lines = cmake.read_text(encoding="utf-8").splitlines()
    # 按 CMake 命令的括号范围归属源文件，避免 add_library 跨多行时用固定行窗口
    # 把生产目标误判为未知目标。
    targets = {}
    index = 0
    while index < len(lines):
        line = lines[index].split("#", 1)[0]
        match = re.match(r"\s*add_library\s*\(\s*([A-Za-z0-9_.+-]+)\b", line)
        if match is None:
            index += 1
            continue
        target = match.group(1)
        command = [line]
        balance = line.count("(") - line.count(")")
        index += 1
        while balance > 0 and index < len(lines):
            next_line = lines[index].split("#", 1)[0]
            command.append(next_line)
            balance += next_line.count("(") - next_line.count(")")
            index += 1
        require(balance == 0, "CMake add_library 命令括号未闭合：%s" % target)
        targets[target] = " ".join(command)

    production = targets.get("HyperExtendLauncherNative", "")
    require("launcher/singleword_publish.cpp" in production,
            "生产目标未接入单字提交实现")
    require("launcher/count_backend.cpp" in production,
            "生产目标未接入蹦床后端实现")
    offenders = [(target, source) for target, source in targets.items()
                 if target != "HyperExtendLauncherNative"
                 and "launcher/singleword_publish.cpp" in source]
    require(not offenders, "单字提交实现被接入未知目标：%s" % offenders)
    return {"源码": cmake.name, "命中": 0,
            "说明": "单字提交与蹦床后端已进入生产目标；阶段锁仍独立阻断安装"}


def verify_evidence_contract(image, contract):
    """两条审计入口共用身份与阻断合同，快照回放不能绕过宿主校验。"""
    require(contract["schemaVersion"] == 1 and contract["policy"] == POLICY,
            "合同版本或策略不符")
    require(contract["installPermitted"] is False, "合同不得声明安装许可")
    gates = contract.get("notClosedGates") or []
    require(any(gate.startswith("门槛3") for gate in gates), "必须显式保留门槛 3 未关闭")
    require(any(gate.startswith("门槛5") for gate in gates), "必须显式保留门槛 5 未关闭")
    expected = contract["image"]
    require(len(image.data) == expected["size"], "宿主文件长度与合同不符")
    require(digest(image.data) == expected["sha256"], "宿主文件摘要与合同不符")
    require(image.build_id() == expected["buildId"], "宿主 Build ID 与合同不符")

    sites = contract["sites"]
    require(sites and len(sites) == contract["counts"]["sites"], "调用点数量与合同不符")
    verify_site_words(image, sites)
    return sites


def verify_mapping_evidence(image, contract, regions, elf_file_offset, base=None):
    """可独立回放的 (a)/(b)；不运行编译器，也不把历史快照当成运行期保证。"""
    sites = verify_evidence_contract(image, contract)
    requirements = contract["mapsRequirements"]
    verify_map_geometry(regions, requirements)
    if base is None:
        base = derive_load_base(image, regions, requirements, elf_file_offset)
    observed, delta = verify_runtime_mappings(regions, requirements, sites, base, image, elf_file_offset)
    runtime_sites = [dict(site, runtime=base + number(site["offset"])) for site in sites]
    page_report = verify_page_properties(regions, requirements, runtime_sites)
    lifecycle = verify_host_lifecycle(image, contract, sites)
    load_count = verify_load_mappings(image, regions, requirements, base, elf_file_offset)
    return {"加载基址": hex(base), "残余项(a) 蹦床页内存属性与写入独占性": page_report,
            "残余项(b) 宿主映像映射生命周期": {"宿主": lifecycle, "调用点运行期映射": observed,
                                               "已核对文件 PT_LOAD": load_count,
                                               "APK 内 ELF 起始偏移": hex(delta)}}


def verify_contract(image, contract, regions, base, clang, objdump, trial_source, elf_file_offset):
    verify_trial_source(contract, trial_source)
    write_path = verify_no_product_write_path()
    mapping = verify_mapping_evidence(image, contract, regions, elf_file_offset, base)
    trial = verify_commit_trial(clang, objdump, trial_source, contract["trialSymbols"])
    gates = contract["notClosedGates"]
    open_gates = sorted({int(gate[2]) for gate in gates if gate.startswith("门槛") and gate[2].isdigit()})
    return {"策略": POLICY, **mapping,
            "残余项(c) 提交确为单条对齐 32 位存储": trial,
            "安装路径隔离": write_path,
            "安装许可": False,
            "未关闭门槛": open_gates,
            "限制": "只读静态证据：编译器侧证明提交是单条存储，映射/ELF/APK 侧只核对快照属性与文件归属；"
                    "仍不覆盖实机安装实现、运行期竞争或其它线程的实际执行时机，"
                    "因此不构成门槛 5 关闭，也不构成安装许可。"}


def main():
    parser = argparse.ArgumentParser(description="门槛 5 三项残余的只读取证审计")
    parser.add_argument("--host-elf", type=Path, required=True, help="固定宿主 libapp.so")
    parser.add_argument("--host-apk", type=Path, required=True, help="已留存在本机的宿主 APK，用于独立校验条目偏移")
    parser.add_argument("--maps", type=Path, required=True, help="本机 /proc/<pid>/maps 快照（不入库）")
    parser.add_argument("--base", help="可选基址断言；省略时由 APK 偏移与全部文件 PT_LOAD 唯一推导")
    parser.add_argument("--clang", help="NDK clang.exe；缺省用 PATH 上的 clang")
    parser.add_argument("--trial", type=Path, default=DEFAULT_TRIAL, help="提交协议试验源")
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT,
                        help="门槛 5 残余项合同 JSON")
    args = parser.parse_args()
    try:
        image = ElfImage(args.host_elf.read_bytes())
        contract = json.loads(args.contract.read_text(encoding="utf-8"))
        regions = parse_maps(args.maps.read_text(encoding="utf-8", errors="strict"))
        verify_evidence_contract(image, contract)
        elf_file_offset = verify_apk_entry(args.host_apk.read_bytes(), image, contract["mapsRequirements"])
        clang, objdump = find_toolchain(args.clang)
        report = verify_contract(image, contract, regions, int(args.base, 0) if args.base else None,
                                 clang, objdump, args.trial, elf_file_offset)
        print(json.dumps(report, ensure_ascii=False, indent=2))
    except (AuditError, OSError, ValueError, KeyError, TypeError, struct.error, zipfile.BadZipFile) as error:
        print("门槛 5 残余项审计失败：%s" % error)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
