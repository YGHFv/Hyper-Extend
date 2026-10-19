#!/usr/bin/env python3
"""核对桌面分享的固定阶段链、单次请求日志额度与最终 APK 字符串；不安装、不连接设备。"""

import argparse
import json
import re
from pathlib import Path

from launcher_native_audit import AuditError, digest, require


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CONTRACT = ROOT / "app/src/test/resources/launcher-share-stage-chain-contract.json"
POLICY = "share-stage-chain-source-v1"
SOURCES = {
    "native": "app/src/main/cpp/launcher/native_entry.cpp",
    "trampoline": "app/src/main/cpp/launcher/launcher_menu_arm64.S",
    "wire": "app/src/main/cpp/launcher/share_wire.h",
    "endpoint": "app/src/main/java/io/github/YGHFv/HyperExtend/hook/feature/launcher/LauncherSnapshotEndpoint.kt",
    "systemServer": "app/src/main/java/io/github/YGHFv/HyperExtend/hook/feature/launcher/LauncherShareEndpoint.kt",
    "provider": "app/src/main/java/io/github/YGHFv/HyperExtend/core/LauncherShareProvider.kt",
    "activity": "app/src/main/java/io/github/YGHFv/HyperExtend/ui/SettingsActivity.kt",
}


def numeric_constant(source, name):
    matches = re.findall(
        r"\b" + re.escape(name) + r"\s*=\s*(0x[0-9a-fA-F]+|\d+)[UL]*\s*[;\n]",
        source,
    )
    require(len(matches) == 1, "源码常量缺失或不唯一：" + name)
    return int(matches[0], 0)


def verify_action_hook_plan(contract, native_source):
    """核对动作点与异步 false 结果出口分别发布。"""
    plan = contract["actionHookPlan"]
    require(plan["validityReplacementsEnabled"] is True,
            "异步可见性 false 出口必须进入生产安装计划")
    require(plan["validityReplacementKind"] ==
            "async-false-result-exit-island",
            "异步可见性必须在 false 结果出口适配")
    require(plan["requiredActions"] == [
        "kSharePrimaryCall", "kShareSecondaryCall",
    ], "动作安装合同必须包含分享主、次调用点")
    require(plan["actionIslands"] == [
        "launcher_menu_primary_trace", "launcher_menu_secondary_trace",
    ], "动作安装合同必须绑定主、次分享蹦床")
    require(plan["requiredValidityPoints"] == ["kShareValid", "kFreeformValid"],
            "分享和小窗的异步可见性识别点必须齐全")
    require(plan["validityExitIslands"] == [
        "launcher_menu_share_valid_exit", "launcher_menu_freeform_valid_exit",
    ], "异步 false 出口必须绑定功能隔离的续接蹦床")

    share_definition = re.search(
        r"const bool share_actions = ([^;]+);", native_source)
    require(share_definition is not None and all(
        name in share_definition.group(1) for name in (
            "g_share_primary_island.published",
            "g_share_secondary_island.published",
        )), "分享主、次动作岛页必须分别发布")
    freeform_definition = re.search(
        r"const bool freeform = ([^;]+);", native_source)
    require(freeform_definition is not None and all(
        name in freeform_definition.group(1) for name in (
            "g_freeform_island.published",
        )), "小窗安装完成判定必须要求动作岛页已发布")
    require("menu_site_for(launcher::kSharePrimaryCall)" in native_source
            and "menu_site_for(launcher::kShareSecondaryCall)" in native_source,
            "安装计划必须解析分享主、次调用点")
    require("reinterpret_cast<void *>(&launcher_menu_primary_trace)" in native_source
            and "reinterpret_cast<void *>(&launcher_menu_secondary_trace)" in native_source,
            "安装计划必须把主、次调用点接到对应分享蹦床")
    install_body = native_source.split("int install_launcher_batch(", 1)[1].split(
        "// 受控安装计划的解析", 1)[0]
    require("g_share_valid_specs" in install_body
            and "g_freeform_valid_specs" in install_body
            and "launcher_menu_share_valid_exit" in install_body
            and "launcher_menu_freeform_valid_exit" in install_body
            and "InstallBatchPart::kShareValidity" in install_body
            and "InstallBatchPart::kFreeformValidity" in install_body,
            "异步 false 出口必须按功能独立发布")
    return {
        "分享主次动作": "必须发布",
        "有效性替换": "最终 false 结果出口按功能位适配",
        "可见性识别点": plan["requiredValidityPoints"],
    }


def verify_contract(contract, sources=None):
    """固定阶段字面量；任何一段改名都必须同步审核，不能静默失去实机证据。"""
    require(contract["schemaVersion"] == 1 and contract["policy"] == POLICY,
            "阶段链合同版本或策略不符")
    require(contract["installPermitted"] is False
            and contract["notClosedGates"] == [1, 3, 5],
            "阶段链合同不得放行安装或关闭门槛")
    require(contract["limits"] == {"snapshotReport": 8, "shareReportPerRequest": 8},
            "日志额度合同漂移")
    texts = sources if sources is not None else {
        key: (ROOT / path).read_text(encoding="utf-8") for key, path in SOURCES.items()
    }
    verify_action_hook_plan(contract, texts["native"])
    # 分享日志必须走自己的计数和输出函数；只改字面量而不改额度不算修复。
    endpoint = texts["endpoint"]
    require(numeric_constant(endpoint, "REPORT_LIMIT") == contract["limits"]["snapshotReport"]
            and numeric_constant(endpoint, "SHARE_REPORT_LIMIT") ==
            contract["limits"]["shareReportPerRequest"], "日志额度与源码不符")
    server_share = texts["systemServer"]
    require("private class Reporter" in server_share
            and "private val reported = AtomicInteger(0)" in server_share
            and "if (reported.getAndIncrement() < REPORT_LIMIT)" in server_share
            and "val reporter = Reporter()" in server_share
            and "forward(packageName, reporter)" in server_share
            and 'ModuleLog.info("launcher share: ${message()}")' in server_share,
            "system_server 分享日志没有使用单次请求计数器")
    require("private val reported = AtomicInteger(0)" in endpoint
            and "private val shareReported = AtomicInteger(0)" in endpoint
            and "shareReported.getAndIncrement() < SHARE_REPORT_LIMIT" in endpoint,
            "端点分享日志没有使用独立计数器")
    require(not re.search(r"report(?:Once|Share)\(\s*\"launcher endpoint: share ",
                          endpoint),
            "端点源码不得把前缀重复写进分享消息")
    share_calls = re.findall(r"report(?:Once|Share)\(\s*\"([^\"]+)", endpoint)
    share_calls = [text for text in share_calls if text.startswith(
        ("share ", "launcher endpoint: share "))]
    require(len(share_calls) == 3 and all(
        text.startswith(("share queued ", "share rejected ")) for text in share_calls
    ), "分享日志调用集合不符")
    require(not re.search(r"reportOnce\(\s*\"share ", endpoint),
            "分享日志不能继续共用普通快照额度")
    require(share_calls == [
        'share queued point=$point',
        'share rejected uid=$uid pid=$pid reason=${it.javaClass.simpleName}',
        'share rejected caller uid=$uid pid=$pid',
    ], "分享日志调用集合或顺序不符")
    for text in contract["endpoint"]:
        if text.startswith("reportShare("):
            require(text.removeprefix("reportShare(") in endpoint,
                    "端点阶段缺失：" + text)
        else:
            require(text in endpoint, "端点阶段或独立额度缺失：" + text)
    require('"' + contract["endpointMessage"] + '"' not in endpoint,
            "端点源码不得把前缀重复写进消息；前缀由 reportShare 统一添加")
    require('ModuleLog.info("launcher endpoint: $message")' in endpoint,
            "端点日志前缀不由 reportShare 统一添加")
    for key, group in (("native", "nativeEntry"), ("trampoline", "trampoline"),
                       ("systemServer", "systemServer"), ("provider", "provider"),
                       ("activity", "activity")):
        for text in contract[group]:
            if key == "native" and text in (
                    "launcher_share_entry_bridge",
                    "share_entry_primary_entered",
                    "share_entry_secondary_entered",
                    "share_entry_package_ok",
                    "share_entry_bridge_acked"):
                require('"' + text + '"' in texts[key], "原阶段字符串缺失：" + text)
            else:
                require(text in texts[key], "阶段链字符串缺失：" + text)
    wire = contract["wire"]
    require(numeric_constant(texts["wire"], "kShareTransaction") ==
            int(wire["transaction"], 0)
            and numeric_constant(texts["wire"], "kShareProtocol") == wire["protocol"]
            and numeric_constant(texts["wire"], "kShareQueuedAck") ==
            int(wire["queuedAck"], 0), "分享线路协议与源码不符")
    require("require(point == LauncherSnapshotWire.SHARE_POINT_PRIMARY ||" in endpoint
            and "point == LauncherSnapshotWire.SHARE_POINT_SECONDARY" in endpoint,
            "端点未同时接受主、次分享点位")
    return {
        "策略": POLICY,
        "源码文件数": len(SOURCES),
        "分享日志额度": contract["limits"]["shareReportPerRequest"],
        "分享日志计数范围": "每次请求",
        "安装许可": False,
        "门槛3已关闭": False,
        "未关闭门槛": [1, 3, 5],
        "有效性替换已启用": contract["actionHookPlan"][
            "validityReplacementsEnabled"],
        "有效性替换层级": contract["actionHookPlan"]["validityReplacementKind"],
        "限制": contract["verificationBoundary"],
    }


def packaged_strings(apk):
    """只读最终 APK；DEX 与原生库分开报告，不能用预算 class 代替打包结果。"""
    import zipfile

    required = {
        "dex": [
            "share queued point=", "share rejected uid=",
            "apk resolved package=",
            "launcher share provider: begin package=",
            "launcher share provider: file ready package=",
            "provider accepted package=",
            "activity launched package=",
            "launcher share: ",
            "launcher share activity: uri received",
            "launcher share activity: chooser started",
        ],
        "native": [
            "hook_word_share_primary", "hook_word_share_secondary",
            "share_entry_primary_entered", "share_entry_secondary_entered",
            "share_entry_package_ok", "share_entry_bridge_acked",
            "share_entry_bridge_failed",
        ],
    }
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        dex_files = [name for name in names if name.startswith("classes") and
                     name.endswith(".dex")]
        libraries = [name for name in names if name.endswith(
            "/libHyperExtendLauncherNative.so")]
        require(len(dex_files) == 1, "最终 APK 的 DEX 数量不符")
        require(len(libraries) == 1, "最终 APK 的原生库数量不符")
        dex = archive.read(dex_files[0])
        native = archive.read(libraries[0])
    for text in required["dex"]:
        require(text.encode("utf-8") in dex, "最终 DEX 缺少阶段字符串：" + text)
    for text in required["native"]:
        require(text.encode("utf-8") in native, "最终原生库缺少阶段字符串：" + text)
    return {"DEX": dex_files[0], "原生库": libraries[0],
            "DEX 阶段数": len(required["dex"]), "原生阶段数": len(required["native"])}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT)
    parser.add_argument("--apk", type=Path, help="可选：同时核对最终 Release APK")
    args = parser.parse_args(argv)
    try:
        raw = args.contract.read_bytes()
        result = verify_contract(json.loads(raw))
        if args.apk is not None:
            result["打包核对"] = packaged_strings(args.apk)
        result["合同摘要"] = digest(raw)
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except (AuditError, OSError, ValueError, KeyError, TypeError) as error:
        message = type(error).__name__ if isinstance(error, OSError) else str(error)
        print("分享阶段链核对失败：" + message)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
