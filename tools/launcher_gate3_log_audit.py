#!/usr/bin/env python3
"""门槛 3 双端日志的离线核对；不采集设备日志、不改变配置、不产生安装许可。"""

import argparse
from datetime import datetime
import json
import re
from pathlib import Path

from launcher_native_audit import AuditError, digest, number, require


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CONTRACT = ROOT / "app/src/test/resources/launcher-gate3-log-contract.json"
POLICY = "gate3-readonly-log-window-v1"
JAVA = "app/src/main/java/io/github/YGHFv/HyperExtend/"
CPP = "app/src/main/cpp/launcher/"
SOURCES = {
    "native": CPP + "native_entry.cpp",
    "abi": CPP + "native_api.h",
    "channel": CPP + "channel.h",
    "snapshot": CPP + "snapshot_wire.h",
    "permit": CPP + "install_permit.h",
    "endpoint": JAVA + "hook/feature/launcher/LauncherSnapshotEndpoint.kt",
    "wire": JAVA + "hook/feature/launcher/LauncherSnapshotPolicy.kt",
    "log": JAVA + "core/ModuleLog.kt",
    "entry": JAVA + "hook/HyperXposedEntry.kt",
}
ATTACH = re.compile(r"==== attach pid=(\d+) \((\d+) buffered\) ====")
FILE_LINE = re.compile(
    r"(?P<stamp>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}) (?P<level>[DIWE]) (?P<message>.*)")
THREADTIME = re.compile(
    r"(?P<stamp>(?:\d{4}-)?\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3,6})\s+"
    r"(?P<pid>\d+)\s+(?P<tid>\d+)\s+(?P<level>[VDIWEF])\s+"
    r"(?P<tag>[^:\s]+)\s*:\s?(?P<message>.*)")
CHANNEL_REASONS = {
    1: "Binder 必需符号不可用",
    2: "window 服务或接口类关联失败，单凭此位不能区分",
    4: "事务准备、事务本身或输出句柄失败，单凭此位不能区分",
    8: "回复字段不足",
    32: "尚未查询不是工作线程完成结果",
    64: "查询重入或在途，未取得新快照",
    128: "通道状态属于其它进程",
}
SNAPSHOT_REASONS = {
    1: "确认码不符", 2: "协议不符", 4: "字段畸形",
    8: "修订号未递增", 16: "时钟或租期异常", 32: "快照到期", 64: "未武装",
}
SERVER_BLOCKERS = (
    "launcher snapshot endpoint unavailable", "system_server hooks skipped",
    "system_server settings unavailable", "system_server hook install aborted",
    "framework lacks PROP_CAP_SYSTEM", "SAFE MODE [", "framework hook fuse engaged",
)


def numeric_constant(source, name):
    matches = re.findall(r"\b" + re.escape(name) + r"\s*=\s*(0x[0-9a-fA-F]+|\d+)[UL]*\s*[;\n]", source)
    require(len(matches) == 1, "源码常量缺失或不唯一：" + name)
    return int(matches[0], 0)


def enum_bits(source, enum):
    match = re.search(r"\benum " + enum + r"\s*:\s*uint32_t\s*\{(.*?)\};", source, re.S)
    require(match is not None, "源码缺少阻断位枚举：" + enum)
    return {name: 1 << int(bit) for name, bit in re.findall(r"(\w+)\s*=\s*1U\s*<<\s*(\d+)", match[1])}


def verify_log_contract(contract, sources=None):
    """锁定已有源码的日志字面量和状态位，格式变动必须同步审核离线判定。"""
    require(contract["schemaVersion"] == 1 and contract["policy"] == POLICY, "日志合同版本或策略不符")
    require(contract["installPermitted"] is False and contract["gate3Closed"] is False
            and contract["notClosedGates"] == [1, 3, 5], "日志合同不得放行安装或关闭门槛")
    require(number(contract["transaction"]) == 0x00484541
            and contract["endpointReportLimit"] == 8 and contract["countLimit"] == [5, 20],
            "日志合同的已审核协议常量漂移")
    require(set(contract["nativeTemplates"]) == {"start", "done", "identityRejected", "threadFailed"},
            "原生日志模板集合不符")
    require(set(contract["endpointTemplates"]) ==
            {"installed", "served", "rejected", "replyFailed", "safeMode", "missingClass", "missingMethod"},
            "端点日志模板集合不符")
    texts = sources if sources is not None else {
        key: (ROOT / path).read_text(encoding="utf-8") for key, path in SOURCES.items()}
    require(contract["channelBits"] == enum_bits(texts["channel"], "ChannelBlocker")
            and sorted(contract["channelBits"].values()) == [1 << bit for bit in range(8)],
            "通道阻断位与源码不符")
    require(contract["snapshotBits"] == enum_bits(texts["snapshot"], "SnapshotBlocker")
            and sorted(contract["snapshotBits"].values()) == [1 << bit for bit in range(7)],
            "快照阻断位与源码不符")
    for key, name, expected in (
        ("channel", "kChannelTransaction", number(contract["transaction"])),
        ("wire", "TRANSACTION", number(contract["transaction"])),
        ("snapshot", "kSnapshotCountLimitMin", contract["countLimit"][0]),
        ("snapshot", "kSnapshotCountLimitMax", contract["countLimit"][1]),
        ("endpoint", "REPORT_LIMIT", contract["endpointReportLimit"]),
    ):
        require(numeric_constant(texts[key], name) == expected, "协议常量与源码不符：" + name)
    # 只核对入口合同存在，不把框架版本数字变成兼容性门禁。
    require("constexpr bool native_entries_ready(" in texts["abi"]
            and "entries->hook_func != nullptr" in texts["abi"]
            and "unhook_func 缺失不能把整个挂载链拒绝" in texts["abi"],
            "原生入口合同与源码不符")
    for group, key in (("nativeTemplates", "native"), ("endpointTemplates", "endpoint")):
        for name, template in contract[group].items():
            require('"' + template + '"' in texts[key], "日志模板与源码不符：" + name)
    require('"' + contract["safetyTemplate"] + '"' in texts["native"],
            "安全持久化日志模板与源码不符")
    require('const val TAG = "HyperExtend"' in texts["log"]
            and '"==== attach pid=${Process.myPid()} (${backlog.size} buffered) ====\\n"' in texts["log"],
            "服务端日志会话格式漂移")
    require('"module loaded: process=${param.processName} systemServer=${param.isSystemServer} "' in texts["entry"]
            and '"system_server starting: ${capabilitySummary()}"' in texts["entry"],
            "服务端身份日志格式漂移")
    require("kPendingAcceptanceBlockers" not in texts["permit"],
            "日志工具仍依赖已删除的待验收阶段锁常量")
    require("constexpr bool install_permitted(" in texts["permit"]
            and "permit_blockers(evidence) == 0" in texts["permit"]
            and "LAUNCHER_NATIVE_EXPERIMENTAL_INSTALL" in texts["permit"],
            "安装许可判定与现场证据合同漂移")
    return {"源码文件数": len(SOURCES), "模板数": 11, "事务码": contract["transaction"],
            "入口合同": "函数指针非空，版本数字仅诊断",
            "安装许可": False, "门槛3已关闭": False, "未关闭门槛": [1, 3, 5]}


def append_window(before, after, label):
    """保守要求同源日志只追加；轮转、裁剪、冻结或换格式不能偷偷当成新证据。"""
    require(not before or before.endswith("\n"), label + "基线末行不完整")
    require(after.endswith("\n"), label + "后置日志末行不完整")
    require(after.startswith(before), label + "不是基线的追加版本，可能轮转、裁剪或换源")
    require(len(after) > len(before), label + "没有新增日志，不能使用旧结果")
    return len(before.splitlines())


def template_pattern(template, fields):
    pattern = re.escape(template)
    for token, name, expression in fields:
        require(re.escape(token) in pattern, "日志模板缺少字段：" + name)
        pattern = pattern.replace(re.escape(token), "(?P<%s>%s)" % (name, expression), 1)
    return re.compile(pattern)


def verify_timestamp(stamp, fractional=False):
    # 缺年份时只用闰年校验日历形状；不猜实际年份，不以墙上时钟复算快照租期。
    stamp = " ".join(stamp.split())
    if len(stamp.split()[0]) == 5:
        stamp = "2000-" + stamp
    try:
        datetime.strptime(stamp, "%Y-%m-%d %H:%M:%S" + (".%f" if fractional else ""))
    except ValueError as error:
        raise AuditError("日志时间字段不是有效的日历格式") from error


def native_patterns(contract):
    done = template_pattern(contract["nativeTemplates"]["done"], [
        ("%x", "channel", r"[0-9a-f]+"), ("%x", "snapshot", r"[0-9a-f]+"),
        ("%d", "revision", r"-?\d+"), ("%d", "features", r"-?\d+"),
        ("%d", "limit", r"-?\d+"), ("%d", "armed", r"-?\d+"),
    ])
    return {key: re.compile(re.escape(value)) if key != "done" else done
            for key, value in contract["nativeTemplates"].items()}


def parse_native(text, first_new_line, contract):
    patterns = native_patterns(contract)
    events = []
    for line_number, line in enumerate(text.splitlines(), 1):
        if line_number <= first_new_line:
            continue
        match = THREADTIME.fullmatch(line.strip())
        if match is None:
            require("HELauncherProbe" not in line and "只读诊断：" not in line,
                    "原生日志第 %d 行缺少完整 threadtime 元数据" % line_number)
            continue
        if match["tag"] != "HELauncherProbe":
            continue
        verify_timestamp(match["stamp"], fractional=True)
        found = [(kind, pattern.fullmatch(match["message"])) for kind, pattern in patterns.items()]
        found = [(kind, result) for kind, result in found if result is not None]
        require(len(found) == 1, "原生日志第 %d 行不符合锁定模板" % line_number)
        kind, result = found[0]
        level = "I" if kind in ("start", "done") else "W"
        require(match["level"] == level and int(match["pid"]) > 0 and int(match["tid"]) > 0,
                "原生日志级别或进程/线程元数据不符")
        values = {key: int(value, 16 if key in ("channel", "snapshot") else 10)
                  for key, value in result.groupdict().items()}
        events.append(dict(kind=kind, line=line_number, pid=int(match["pid"]), tid=int(match["tid"]),
                           values=values))
    return events


def endpoint_patterns(contract):
    templates = contract["endpointTemplates"]
    installed = template_pattern(templates["installed"], [
        ("$ok", "ok", "true|false"), ("${LauncherSnapshotWire.TRANSACTION}", "transaction", r"\d+")])
    served = template_pattern("launcher endpoint: " + templates["served"], [
        ("${snapshot.revision}", "revision", r"-?\d+"),
        ("${snapshot.features}", "features", r"-?\d+"),
        ("${snapshot.countLimit}", "limit", r"-?\d+")])
    rejected = template_pattern("launcher endpoint: " + templates["rejected"], [
        ("$uid", "uid", r"\d+"), ("$pid", "pid", r"\d+")])
    return {"installed": installed, "served": served, "rejected": rejected}


def parse_server(text, contract):
    """只用最后一个 attach 会话，不跨 system_server 重启拼接修订号。"""
    lines = text.splitlines()
    headers = [(index, ATTACH.fullmatch(line)) for index, line in enumerate(lines, 1)]
    headers = [(index, match) for index, match in headers if match is not None]
    require(headers, "服务端缺少完整 attach 会话头，无法区分历史进程")
    start, header = headers[-1]
    require(int(header[1]) > 0, "服务端会话进程字段无效")
    patterns = endpoint_patterns(contract)
    events = []
    identity = starting = False
    for line_number, line in enumerate(lines[start:], start + 1):
        require(not line.startswith("==== attach"), "服务端含不完整会话头")
        match = FILE_LINE.fullmatch(line)
        if match is None:
            require(not any(marker in line for marker in
                            ("launcher endpoint:", "[entry]", "framework hook fuse:", *SERVER_BLOCKERS)),
                    "服务端第 %d 行缺少完整文件日志格式" % line_number)
            continue
        level, message = match["level"], match["message"]
        verify_timestamp(match["stamp"])
        if message.startswith("[entry] module loaded:"):
            require(level == "I" and not identity and message.startswith(
                "[entry] module loaded: process=system_server systemServer=true "),
                "服务端模块身份缺失、冲突或重复")
            identity = True
        if message.startswith("[entry] system_server starting:"):
            require(level == "I" and identity and not starting, "服务端启动日志级别不符、缺失前置身份或重复")
            starting = True
        if message.startswith("launcher endpoint:"):
            found = [(kind, pattern.fullmatch(message)) for kind, pattern in patterns.items()]
            found = [(kind, result) for kind, result in found if result is not None]
            require(len(found) == 1, "服务端端点失败或未知日志，拒绝作为成功证据（行 %d）" % line_number)
            kind, result = found[0]
            require(identity and starting and level == "I", "端点日志缺少前置身份/启动记录或级别不符")
            events.append(dict(kind=kind, line=line_number, values=result.groupdict()))
        if (message.startswith(SERVER_BLOCKERS)
                or (message.startswith("framework hook fuse:") and level in ("W", "E"))):
            raise AuditError("服务端当前会话含安全阻断或入口失败（行 %d）" % line_number)
    require(identity and starting, "服务端当前会话缺少身份或启动证据")
    return events


def check_snapshot(values, expected):
    """仅解释日志暴露的字段，不伪造日志没有记录的时钟、确认码与恢复令牌。"""
    channel, snapshot = values["channel"], values["snapshot"]
    require(channel in {0, *CHANNEL_REASONS, 16}, "未知或不可能的通道阻断位组合")
    require(0 <= snapshot <= 0x7F, "未知快照阻断位")
    require(all(-(1 << 31) <= values[key] < 1 << 31 for key in ("revision", "features", "limit", "armed")),
            "日志字段超出 int32")
    if channel in CHANNEL_REASONS:
        require(snapshot == 0 and all(values[key] == 0 for key in ("revision", "features", "limit", "armed")),
                "没有完整回复的通道结果却带有快照字段")
        raise AuditError("通道未获得可核对快照：" + CHANNEL_REASONS[channel])
    require((channel == 0) == (snapshot == 0), "channel 与 snapshot 阻断位相互矛盾")
    if channel == 16 and snapshot != 64:
        reasons = [text for bit, text in SNAPSHOT_REASONS.items() if snapshot & bit]
        raise AuditError("快照未通过：" + "、".join(reasons))
    require(values["revision"] > 0 and 0 <= values["features"] <= 15
            and 5 <= values["limit"] <= 20 and values["armed"] in (0, 1), "快照可见字段畸形")
    if channel == 0:
        require(values["armed"] == 1, "已采纳日志却未武装")
        observed = "accepted"
    else:
        require(values["armed"] == 0 and values["features"] == 0, "未武装日志与主开关门禁矛盾")
        observed = "unarmed"
    require(observed == expected, "观测状态与要求不符，不自动改变验收场景")
    return observed


def audit_logs(before_server, after_server, before_launcher, after_launcher, contract, expected="unarmed"):
    require(expected in ("unarmed", "accepted"), "未知日志验收场景")
    verify_log_contract(contract)
    server_boundary = append_window(before_server, after_server, "服务端")
    native_boundary = append_window(before_launcher, after_launcher, "原生端")
    native = parse_native(after_launcher, native_boundary, contract)
    require(not any(event["kind"] in ("identityRejected", "threadFailed") for event in native),
            "原生工作线程身份或创建失败，不重试")
    require([event["kind"] for event in native] == ["start", "done"],
            "新增窗口必须只有一组开始/完成；缺日志、重复查询或只有开始都属于证据不足，不能断言超时")
    start, done = native
    require((start["pid"], start["tid"]) == (done["pid"], done["tid"]),
            "原生开始与完成不属于同一进程/线程，禁止拼接")
    observed = check_snapshot(done["values"], expected)
    server = parse_server(after_server, contract)
    installed = [event for event in server if event["kind"] == "installed"]
    require(len(installed) == 1 and installed[0]["values"]["ok"] == "true",
            "服务端当前会话没有唯一的端点安装成功记录")
    require(int(installed[0]["values"]["transaction"]) == number(contract["transaction"]), "端点事务码不符")
    responses = [event for event in server if event["kind"] in ("served", "rejected")]
    require(len(responses) <= contract["endpointReportLimit"], "服务端应答日志超过源码限额，可能混入多会话")
    require(all(event["line"] > installed[0]["line"] for event in responses), "端点应答早于安装记录，顺序不可信")
    rejected = [event for event in responses if event["kind"] == "rejected"
                and event["line"] > server_boundary and int(event["values"]["pid"]) == done["pid"]]
    require(not rejected, "服务端新增窗口拒绝了同一原生进程的调用")
    served = [event for event in responses if event["kind"] == "served"]
    require(all(0 <= int(event["values"]["features"]) <= 15
                and 5 <= int(event["values"]["limit"]) <= 20 for event in served),
            "服务端应答可见字段越界")
    revisions = [int(event["values"]["revision"]) for event in served]
    require(all(0 < revision < 1 << 31 for revision in revisions)
            and all(left < right for left, right in zip(revisions, revisions[1:])),
            "当前服务端会话修订号未严格递增，不能跨会话或回绕匹配")
    wanted = {key: done["values"][key] for key in ("revision", "features", "limit")}
    matching = [event for event in served if event["line"] > server_boundary
                and {key: int(value) for key, value in event["values"].items()} == wanted]
    require(len(matching) == 1, "服务端新增窗口缺少唯一对应的 served 字段；可能缺日志或触及 8 次限额")
    return {"策略": POLICY, "日志核对通过": True,
            "观测": "双端字段一致，合法快照未武装" if observed == "unarmed" else "双端字段一致，本次快照已采纳",
            "原生新增事件数": len(native), "端点应答日志数": len(responses),
            "匹配字段": wanted, "channel": hex(done["values"]["channel"]),
            "snapshot": hex(done["values"]["snapshot"]),
            "证据行": {"端点安装": installed[0]["line"], "端点应答": matching[0]["line"],
                       "原生开始": start["line"], "原生完成": done["line"]},
            "安装许可": False, "门槛3已关闭": False, "未关闭门槛": [1, 3, 5],
            "限制": contract["verificationBoundary"]}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    check = commands.add_parser("check-contract", help="无需设备材料，核对日志合同与生产源码")
    audit = commands.add_parser("audit", help="核对两端安装前后留存日志的新增窗口")
    for item in (check, audit):
        item.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT)
    for name in ("before-server", "after-server", "before-launcher", "after-launcher"):
        audit.add_argument("--" + name, type=Path, required=True)
    audit.add_argument("--expect", choices=("unarmed", "accepted"), default="unarmed",
                       help="默认核对未武装；已采纳场景只读分析，不会启用功能")
    args = parser.parse_args(argv)
    try:
        raw_contract = args.contract.read_bytes()
        contract = json.loads(raw_contract)
        if args.command == "check-contract":
            result = verify_log_contract(contract)
        else:
            names = ("before_server", "after_server", "before_launcher", "after_launcher")
            raw = {name: getattr(args, name).read_bytes() for name in names}
            texts = {name: data.decode("utf-8") for name, data in raw.items()}
            result = audit_logs(**texts, contract=contract, expected=args.expect)
            result["日志摘要"] = {name: digest(data) for name, data in raw.items()}
        result["合同摘要"] = digest(raw_contract)
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except (AuditError, OSError, ValueError, KeyError, TypeError) as error:
        # 文件系统异常可能带有本机绝对路径；终端只给错误类别，不回显输入内容。
        message = type(error).__name__ if isinstance(error, OSError) else str(error)
        print("门槛 3 日志核对失败：" + message)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
