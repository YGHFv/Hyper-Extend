#!/usr/bin/env python3
"""交叉编译并在 Unicorn 中执行调用点事务的故障注入 harness；不访问设备。"""

import argparse
import json
import os
import struct
import subprocess
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BACKEND = ROOT / "app/src/main/cpp/launcher/count_backend.cpp"
TEST = ROOT / "app/src/test/cpp/launcher_count_backend_test.cpp"
INCLUDE = ROOT / "app/src/main/cpp"
TARGET = "--target=aarch64-linux-android29"
FLAGS = (TARGET, "-fno-stack-protector", "-fno-exceptions", "-fno-rtti",
         "-mno-outline-atomics", "-ffunction-sections", "-fdata-sections",
         "-O1", "-std=c++17", "-I", str(INCLUDE))
CASES = (
    "成功：提交一个 BL 并恢复 RX",
    "RWX 权限申请失败：调用点保持不变",
    "权限切换后机器字漂移：拒绝覆盖并恢复 RX",
    "恢复 RX 首轮失败：回滚原 BL、保留岛页并重试恢复",
    "回滚 CAS 冲突：不覆盖竞争写入并保留岛页",
    "宿主页不是 RX：不开始权限切换",
    "恢复 RX 持续失败：报告错误、回滚 BL 但保留 RWX 页与岛页",
)


def find_clang(explicit):
    if explicit:
        path = Path(explicit)
        if path.is_file():
            return path
        raise RuntimeError("指定的 clang 不存在")
    candidates = []
    for variable in ("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT"):
        root = os.environ.get(variable)
        if root:
            candidates.extend(Path(root).glob("toolchains/llvm/prebuilt/*/bin/clang++.exe"))
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        candidates.extend(Path(sdk).glob("ndk/*/toolchains/llvm/prebuilt/*/bin/clang++.exe"))
    local = os.environ.get("LOCALAPPDATA")
    if local:
        candidates.extend(Path(local).glob("Android/Sdk/ndk/*/toolchains/llvm/prebuilt/*/bin/clang++.exe"))
    for path in reversed(sorted(candidates)):
        if path.is_file():
            return path
    raise RuntimeError("找不到 NDK clang++；请用 --clang 指定")


def compile_harness(clang, folder):
    backend_obj = folder / "count_backend.o"
    test_obj = folder / "count_backend_fault_test.o"
    executable = folder / "count_backend_fault_test.elf"
    for source, output in ((BACKEND, backend_obj), (TEST, test_obj)):
        result = subprocess.run([str(clang), *FLAGS, "-c", str(source), "-o", str(output)],
                                capture_output=True, text=True, check=False)
        if result.returncode:
            raise RuntimeError("ARM64 故障测试交叉编译失败：" + result.stderr.strip())
    command = [str(clang), TARGET, "-nostdlib", "-no-pie", "-Wl,--gc-sections",
               "-Wl,--no-undefined", "-Wl,-e,main", "-Wl,-Ttext=0x100000",
               str(test_obj), str(backend_obj), "-o", str(executable)]
    result = subprocess.run(command, capture_output=True, text=True, check=False)
    if result.returncode:
        raise RuntimeError("ARM64 故障测试链接失败：" + result.stderr.strip())
    return executable


def emulate(executable):
    try:
        import unicorn as uc
        from unicorn import arm64_const as reg
    except ImportError as error:
        raise RuntimeError("缺少 unicorn==2.1.4；请使用 launcher-count 隔离环境") from error

    image = executable.read_bytes()
    if len(image) < 64 or image[:7] != b"\x7fELF\x02\x01\x01":
        raise RuntimeError("交叉编译产物不是 ELF64 小端")
    kind, machine, _version, _entry_unused, phoff, _shoff, _flags, ehsize, phsize, phnum = (
        struct.unpack_from("<HHIQQQIHHH", image, 16))
    require = lambda condition, message: condition or (_ for _ in ()).throw(RuntimeError(message))
    require(kind == 2 and machine == 183 and ehsize == 64 and phsize == 56,
            "故障测试产物不是固定地址 ARM64 executable")
    entry = struct.unpack_from("<Q", image, 24)[0]
    machine_state = uc.Uc(uc.UC_ARCH_ARM64, uc.UC_MODE_ARM)
    page_size = 4096
    mapped = set()
    for index in range(phnum):
        p_type, p_flags, p_offset, p_vaddr, _paddr, p_filesz, p_memsz, _align = (
            struct.unpack_from("<IIQQQQQQ", image, phoff + index * phsize))
        if p_type != 1 or p_memsz == 0:
            continue
        start = p_vaddr & ~(page_size - 1)
        end = (p_vaddr + p_memsz + page_size - 1) & ~(page_size - 1)
        permission = ((uc.UC_PROT_READ if p_flags & 4 else 0) |
                      (uc.UC_PROT_WRITE if p_flags & 2 else 0) |
                      (uc.UC_PROT_EXEC if p_flags & 1 else 0))
        for page in range(start, end, page_size):
            if page not in mapped:
                machine_state.mem_map(page, page_size, permission)
                mapped.add(page)
        if p_filesz:
            machine_state.mem_write(p_vaddr, image[p_offset:p_offset + p_filesz])

    stack = 0x70000000
    stop = 0x71000000
    machine_state.mem_map(stack, 0x10000, uc.UC_PROT_READ | uc.UC_PROT_WRITE)
    machine_state.mem_map(stop, page_size, uc.UC_PROT_READ | uc.UC_PROT_EXEC)
    machine_state.reg_write(reg.UC_ARM64_REG_SP, stack + 0xF000)
    machine_state.reg_write(reg.UC_ARM64_REG_LR, stop)
    machine_state.emu_start(entry, stop, count=200000)
    require(machine_state.reg_read(reg.UC_ARM64_REG_PC) == stop,
            "故障注入 harness 未正常返回或超过指令预算")
    result = machine_state.reg_read(reg.UC_ARM64_REG_X0)
    require(result == 0, "至少一个故障注入场景失败，harness 返回 %d" % result)
    return {"场景数": len(CASES), "场景": list(CASES), "结果": "全部通过",
            "限制": "执行交叉编译后的实际 ARM64 事务代码；系统权限、缓存和真实并发由可控替身模拟，不构成实机验收"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--clang", help="NDK clang++.exe 路径；默认自动发现")
    args = parser.parse_args()
    try:
        clang = find_clang(args.clang)
        with tempfile.TemporaryDirectory(prefix="launcher-count-fault-") as name:
            result = emulate(compile_harness(clang, Path(name)))
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except (OSError, RuntimeError, struct.error) as error:
        print("调用点事务故障注入失败：%s" % error)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
