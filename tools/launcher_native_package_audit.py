#!/usr/bin/env python3
"""检查原生诊断原型的 APK 打包边界；原型同时承载正式 Java 作用域和桌面原生作用域，
普通构建不得夹带原型入口或扩展桌面作用域。
"""

import argparse
import json
import struct
import zipfile

from launcher_native_audit import AuditError, ElfImage, require


LIBRARY = "libHyperExtendLauncherNative.so"
ENTRY = "META-INF/xposed/native_init.list"
SCOPE = "META-INF/xposed/scope.list"
LAUNCHER_SCOPE = "com.miui.home"
SYSTEM_SCOPE = "system"
PROTOTYPE_SCOPES = [LAUNCHER_SCOPE, SYSTEM_SCOPE]
JAVA_SCOPES = [
    "com.android.systemui", "system", "com.android.settings", "com.miui.securitycenter",
    "com.xiaomi.scanner", "com.miui.tsmclient", "com.miui.screenshot", "com.milink.service",
    "com.miui.mishare.connectivity", "com.miui.misound",
]
MERGED_PROTOTYPE_SCOPES = JAVA_SCOPES + [LAUNCHER_SCOPE]
BRIDGE = b"Lio/github/YGHFv/HyperExtend/hook/launcher/LauncherNativeBridge;"
PROTOTYPE_PREFIX = b"Lio/github/YGHFv/HyperExtend/hook/launcher/"
JNI_PREFIX = "Java_io_github_YGHFv_HyperExtend_hook_launcher_LauncherNativeBridge_"
EXPORTS = {"native_init", "JNI_OnLoad", "zn_module", JNI_PREFIX + "nativeStatus", JNI_PREFIX + "nativeApiVersion",
           JNI_PREFIX + "nativeBeginConfiguration", JNI_PREFIX + "nativeCheckConfiguration",
           JNI_PREFIX + "nativeQueryConfiguration", JNI_PREFIX + "nativeChannelStatus",
           JNI_PREFIX + "nativeSnapshotRevision", JNI_PREFIX + "nativeSnapshotFeatures"}
# 单调时钟用于配置租期，墙上时钟用于熔断；open/read/close/readlink 读取身份，
# openat/fstat 逐级打开并核对现成本地熔断文件。
# （__open_2、__openat_2、__read_chk、__write_chk 是 _FORTIFY_SOURCE 包装）；
# 运行期仅显式打开 libbinder_ndk.so 并 dlsym，不增加 DT_NEEDED。新增的 pthread 调度仅用于
# 特化后单次后台查询，liblog 仅输出协议诊断；线程创建的身份门禁还由离线调用链测试检查。
#
# 接线 safety_startup.cpp 后，原型不再是「纯只读」：它会在宿主私有目录里做
# 「临时文件 + fsync + renameat」原子发布。新增的合法导入集中在 PERSISTENCE_IMPORTS，
# 全部由真实 NDK（aarch64-linux-android，-O2，_FORTIFY_SOURCE=2）把
# native_entry.cpp 与 safety_startup.cpp 一起交叉编译后逐字解析 .dynsym 实测得到。
# 该后端只写包目录内已核对的文件，绝不删除、截断、改写内存或触碰加载器；
# 那些破坏性写与内存/加载器符号仍在 FORBIDDEN 里硬拒。
#
# 注意：类初始化缓存用无锁原子手工实现，所以这里**不应**出现 __cxa_guard_*；
# 一旦出现就说明有人把函数内的静态局部量（带守卫）写了回来，必须拒绝。

# 持久化后端接线后新增的合法导入；每一项都对应 safety_startup.cpp 里一处已审核的用法：
#   __errno            取 errno 判定 ENOENT 等错误码
#   fstatat            逐级核对锁/旧状态/临时文件的 inode，不跟随符号链接
#   flock              独占锁竞争立即失败，不等待
#   fsync              临时文件与目录落盘
#   renameat           临时文件原子替换为状态文件（唯一发布手段，不删除旧文件）
#   write/__write_chk  写临时文件（_FORTIFY_SOURCE 下是 __write_chk）
PERSISTENCE_IMPORTS = {"write", "__write_chk", "fsync", "flock", "renameat", "fstatat", "__errno"}
# Binder NDK 仅从固定 SONAME 按需加载；不扩大为任意库加载，也不形成 DT_NEEDED。
DYNAMIC_IMPORTS = {"dlopen"}
# 只读核对同应用组可写目录的访问 ACL；不允许设置、删除 xattr 或修改目录权限。
DIRECTORY_READ_IMPORTS = {"fgetxattr"}
# 实验后台线程有界等待宿主 ELF 就绪，不在加载回调或界面线程中等待。
STARTUP_WAIT_IMPORTS = {"nanosleep"}
# 蹦床页分配与封页仅用于原型阶段的替换页准备，且仍受 install_permitted 支配；
# 在原型库中允许 mmap/mprotect/munmap 三个符号，普通构建与旧白名单仍不得出现其余越界符号。
TRAMPOLINE_IMPORTS = {"mmap", "mprotect", "munmap"}
# native_entry.cpp 对 /proc/self/exe 的有界 readlink 会被 FORTIFY 改写为 __readlink_chk；
# 编译器为值对象复制生成的 memcpy 仅是 libc 复制例程，不开放 mprotect 等映射/权限修改接口。
IMPORTS = {"__cxa_finalize", "__cxa_atexit", "__register_atfork", "getpid", "clock_gettime", "sysconf",
           "__stack_chk_fail", "open", "__open_2", "openat", "__openat_2", "fstat",
           "read", "__read_chk", "close", "readlink", "__readlink_chk", "memcpy",
           "dlsym", "getuid", "geteuid", "pthread_attr_init", "pthread_attr_setdetachstate",
           "pthread_attr_destroy", "pthread_create", "__android_log_print"} | PERSISTENCE_IMPORTS | DYNAMIC_IMPORTS | TRAMPOLINE_IMPORTS | DIRECTORY_READ_IMPORTS | STARTUP_WAIT_IMPORTS
# 只允许出现在 DT_NEEDED 里的库。binder 不在其中：它只允许经 dlsym 解析。
ALLOWED_NEEDED = {"libm.so", "libdl.so", "libc.so", "liblog.so"}
# 明确禁止的导入：带守卫的静态初始化、内存改写、等待线程与属性探测。
FORBIDDEN_PREFIXES = ("__cxa_guard_",)
# 原子替换之外的破坏性写：持久化后端只发布状态，从不删除或截断已核对的文件，
# 因此这些符号属于越界面，必须与内存改写/加载器符号一样硬拒。
DESTRUCTIVE_IMPORTS = {"unlink", "unlinkat", "ftruncate", "truncate"}
DIRECTORY_WRITE_IMPORTS = {
    "chmod", "fchmod", "fchmodat", "chown", "fchown", "fchownat",
    "setxattr", "fsetxattr", "lsetxattr", "removexattr", "fremovexattr", "lremovexattr",
}
FORBIDDEN_IMPORTS = {"pthread_join", "pthread_cancel", "__system_property_get",
                     "getauxval", "__cxa_guard_acquire", "__cxa_guard_release"} | DESTRUCTIVE_IMPORTS | DIRECTORY_WRITE_IMPORTS
# 白名单与禁止表不得重叠：一旦重叠，禁止项会被白名单提前放行，审计形同虚设。
require(not (IMPORTS & FORBIDDEN_IMPORTS), f"导入白名单与禁止表重叠：{sorted(IMPORTS & FORBIDDEN_IMPORTS)}")
require(not any(name.startswith(FORBIDDEN_PREFIXES) for name in IMPORTS), "导入白名单含被禁止前缀")


def dynamic_needed(image, dynamic):
    """从 .dynamic 段读出 DT_NEEDED 指向的库名；只读取，不改写。"""
    strings = image.section_data(image.sections[dynamic["link"]])
    names, offset, size = set(), dynamic["offset"], dynamic["size"]
    require(size % 16 == 0, "动态段条目大小不符")
    for index in range(size // 16):
        tag, value = struct.unpack_from("<qQ", image.data, offset + index * 16)
        if tag == 0:
            break
        if tag == 1:
            names.add(image.string(strings, value))
    return names


def verify_library(raw):
    image = ElfImage(raw)
    # 原型不需要加载期构造器；防止编译器辅助库绕过惰性入口做额外探测。
    require(not any(s["name"] in (".init_array", ".preinit_array", ".ctors") and s["size"]
                    for s in image.sections), "原生原型含未审核的加载期构造器")
    # binder 仅在配置查询时显式 dlopen 并 dlsym，因此绝不能出现 libbinder/libbinder_ndk
    # 的 DT_NEEDED；一旦被链上，加载期行为就不再受本文件控制。
    dynamic = [s for s in image.sections if s["type"] == 6]
    require(len(dynamic) == 1, "原生原型必须恰好有一个动态段")
    needed = dynamic_needed(image, dynamic[0])
    require(needed <= ALLOWED_NEEDED, f"原生原型出现未审核的动态依赖：{sorted(needed - ALLOWED_NEEDED)}")
    table = image.section(".dynsym")
    require(table["type"] == 11 and table["entry_size"] == 24 and table["size"] % 24 == 0, "动态符号表格式不符")
    require(table["link"] < len(image.sections), "动态符号字符串表索引越界")
    strings_section = image.sections[table["link"]]
    require(strings_section["type"] == 3, "动态符号字符串表类型不符")
    strings = image.section_data(strings_section)
    imports, exports = set(), set()
    for off in range(table["offset"], table["offset"] + table["size"], 24):
        name, info, other, section, _, _ = struct.unpack_from("<IBBHQQ", raw, off)
        symbol = image.string(strings, name)
        if not symbol:
            continue
        if section == 0:
            imports.add(symbol)
        elif info >> 4 in (1, 2) and other & 3 == 0:
            exports.add(symbol)
    require(exports == EXPORTS, "原生诊断导出与 JNI/框架入口不一致")
    unexpected_imports = imports - IMPORTS
    require(not unexpected_imports and {"getpid", "clock_gettime"} <= imports,
            f"原生诊断出现未审核的导入：{sorted(unexpected_imports)}")
    # 带守卫的静态初始化会引入 __cxa_guard_*；内存改写、等待线程与属性探测同样禁止。
    forbidden = {name for name in imports
                 if name in FORBIDDEN_IMPORTS or name.startswith(FORBIDDEN_PREFIXES)}
    require(not forbidden, f"原生原型出现被禁止的导入：{sorted(forbidden)}")
    return {"导出数": len(exports), "导入数": len(imports)}


def verify_package(apk, prototype):
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "APK 含重复条目")
        libraries = [name for name in names if name.endswith("/" + LIBRARY)]
        bridge_present, prototype_present = False, False
        for name in names:
            if name.startswith("classes") and name.endswith(".dex"):
                dex = archive.read(name)
                bridge_present |= BRIDGE in dex
                prototype_present |= PROTOTYPE_PREFIX in dex
        scopes = [line.strip() for line in archive.read(SCOPE).decode("utf-8").splitlines()]
        if not prototype:
            require(LAUNCHER_SCOPE not in scopes, "普通构建不得扩展正式桌面作用域")
            require(not libraries and ENTRY not in names and not prototype_present, "普通构建夹带原生诊断原型")
            return {"构建": "普通", "原型入口": "未打包"}
        require(libraries == ["lib/arm64-v8a/" + LIBRARY], "原型库缺失、重复或架构不符")
        require(archive.read(ENTRY).decode("utf-8").strip() == LIBRARY, "原生声明与产物库名不符")
        # 原型必须保留正式 Java 作用域，否则 SystemUI/MiSound 音量入口会被裁掉；
        # 桌面项只在原型追加，普通构建仍不能注入桌面。
        require(scopes == MERGED_PROTOTYPE_SCOPES,
                "原型作用域必须是正式 Java 作用域并追加桌面，顺序固定且不得重复")
        require(bridge_present, "JNI 桥名称未保留在 DEX 中")
        return {"构建": "诊断原型", "原生库": verify_library(archive.read(libraries[0])),
                "说明": "仅打包与导入导出校验，不是设备运行验收"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, help="本地模块 APK，不会安装")
    parser.add_argument("--prototype", action="store_true", help="要求包含诊断原型；默认要求不含原型")
    args = parser.parse_args()
    try:
        print(json.dumps(verify_package(args.apk, args.prototype), ensure_ascii=False, indent=2))
    except (ValueError, KeyError, OSError, struct.error, zipfile.BadZipFile) as error:
        reason = str(error) if isinstance(error, AuditError) else type(error).__name__
        print(f"打包校验失败：{reason}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
