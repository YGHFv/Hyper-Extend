#!/usr/bin/env python3
"""离线执行生产 Hook 点解析器；不按版本、build-id 或摘要签发兼容许可。"""

import argparse
import json
import struct
import subprocess
import tempfile
from pathlib import Path

from launcher_native_audit import ElfImage, require
from launcher_diagnostic_emulate import trial_entry
from launcher_safety_parity_audit import find_clang

ROOT = Path(__file__).resolve().parents[1]
NAMES = ("数量调用", "分享主调用", "分享次调用", "小窗调用",
         "分享可见性入口", "小窗可见性入口")
ACTION_INDEXES = (0, 1, 3, 5, 6, 7)


def compile_resolver(directory):
    clang = find_clang(None, ROOT)
    require(clang is not None, "需要本机 NDK 编译真实解析器")
    output = Path(directory) / "hook-points.so"
    command = [
        str(clang), "--target=aarch64-linux-android26", "-std=c++17", "-O2",
        "-Wall", "-Wextra", "-Werror", "-fno-exceptions", "-fno-rtti",
        "-fno-stack-protector", "-fvisibility=hidden", "-mno-outline-atomics",
        "-fno-builtin", "-shared", "-nostdlib", "-Wl,-Bsymbolic", "-Wl,--no-undefined",
        str(ROOT / "app/src/test/cpp/launcher_hook_points_trial.cpp"), "-o", str(output),
    ]
    result = subprocess.run(command, capture_output=True)
    require(result.returncode == 0, result.stderr.decode("utf-8", "replace"))
    return ElfImage(output.read_bytes())


def resolve(trial, host):
    import unicorn as uc
    from unicorn import arm64_const as reg

    machine = uc.Uc(uc.UC_ARCH_ARM64, uc.UC_MODE_ARM)
    base, stack, stop, source = 0x1000000, 0x2000000, 0x3000000, 0x10000000
    machine.mem_map(base, 0x100000)
    for segment in trial.segments:
        if segment["type"] == 1 and segment["size"]:
            machine.mem_write(base + segment["address"], trial.read(segment["offset"], segment["size"]))
    for section in trial.sections:
        if section["type"] != 4 or not section["flags"] & 2:
            continue
        for at in range(section["offset"], section["offset"] + section["size"], 24):
            address, info, addend = struct.unpack_from("<QQq", trial.data, at)
            require(info == 1027, "试验不得有外部重定位")
            machine.mem_write(base + address, struct.pack("<Q", base + addend))
    extent = max(s["address"] + s["memory"] for s in host.segments if s["type"] == 1)
    require(extent <= 128 * 1024 * 1024, "宿主映像超过离线试验上限")
    machine.mem_map(source, (extent + 4095) & ~4095)
    for segment in host.segments:
        if segment["type"] == 1 and segment["size"]:
            machine.mem_write(source + segment["address"], host.read(segment["offset"], segment["size"]))
    machine.mem_map(stack, 0x10000)
    machine.mem_map(stop, 4096)
    machine.reg_write(reg.UC_ARM64_REG_SP, stack + 0xff00)
    machine.reg_write(reg.UC_ARM64_REG_X0, source)
    machine.reg_write(reg.UC_ARM64_REG_X1, stack)
    machine.reg_write(reg.UC_ARM64_REG_LR, stop)
    machine.emu_start(base + trial_entry(trial, "launcher_hook_points_trial"), stop,
                      timeout=120_000_000, count=1_000_000_000)
    require(machine.reg_read(reg.UC_ARM64_REG_PC) == stop, "解析器超出离线预算")
    values = struct.unpack("<8Q8I", machine.mem_read(stack, 96))
    share_count = struct.unpack("<Q", machine.mem_read(stack + 288, 8))[0]
    share_sites = struct.unpack("<16Q", machine.mem_read(stack + 96, 128))
    share_words = struct.unpack("<16I", machine.mem_read(stack + 224, 64))
    freeform_count = struct.unpack("<Q", machine.mem_read(stack + 488, 8))[0]
    freeform_sites = struct.unpack("<16Q", machine.mem_read(stack + 296, 128))
    freeform_words = struct.unpack("<16I", machine.mem_read(stack + 424, 64))
    mask = machine.reg_read(reg.UC_ARM64_REG_X0)
    return {
        "mask": mask,
        "points": {name: {
            "address": hex(values[index]) if values[index] else None,
            "word": hex(values[8 + index]),
        } for name, index in zip(NAMES, ACTION_INDEXES)},
        "shareValidityExits": [
            {"address": hex(share_sites[index]), "word": hex(share_words[index])}
            for index in range(share_count)],
        "freeformValidityExits": [
            {"address": hex(freeform_sites[index]), "word": hex(freeform_words[index])}
            for index in range(freeform_count)],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--elf", type=Path, required=True)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="launcher-points-") as directory:
        result = resolve(compile_resolver(directory), ElfImage(args.elf.read_bytes()))
    print(json.dumps(result, ensure_ascii=False, indent=2))
    # 生产安装必须同时拿到数量、分享主/次动作、小窗动作、分享可见性与
    # 小窗可见性入口。分享闭包入口只保留诊断，不进入生产 mask。
    # 生产槽位编号保留在 HookPoint 枚举中（两个诊断入口占 2/4），
    # 因此完整生产掩码是 0xFF 去掉 0x14，即 0xEB。
    required = 0xEB
    entry_bits = 0x14
    production = (
        (result["mask"] & required) == required
        and (result["mask"] & entry_bits) == 0
        and all(result["points"][name]["address"] is not None for name in NAMES)
    )
    return 0 if production else 1


if __name__ == "__main__":
    raise SystemExit(main())
