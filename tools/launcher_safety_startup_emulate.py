#!/usr/bin/env python3
"""离线执行持久化后端；系统调用为内存替身，不访问设备或真实状态文件。"""

import argparse
import json
from pathlib import Path

from launcher_diagnostic_emulate import run_case, trial_entry
from launcher_native_audit import AuditError, ElfImage, digest


ENTRY = "launcher_safety_startup_trial"
CASES = dict(enumerate([
    "正常启动同步并读回",
    "达到阈值先提交熔断再拒绝",
    "已有熔断不写状态",
    "普通启动拒绝新恢复令牌且不写状态",
    "旧恢复令牌不能解锁",
    "状态缺失不初始化",
    "状态损坏不覆盖",
    "旧状态读错误",
    "旧状态提前到 EOF",
    "旧状态超长",
    "旧状态权限过宽",
    "旧状态属主不符",
    "用户目录打不开",
    "稳定锁缺失",
    "非等待锁竞争",
    "锁不是普通文件",
    "加锁后锁路径换 inode",
    "锁文件同步失败",
    "锁目录项同步失败",
    "残留临时文件不覆盖",
    "临时文件创建失败",
    "临时文件元信息读取失败",
    "临时文件权限不符",
    "写入错误",
    "写入零字节",
    "分段写入完整提交",
    "临时文件同步失败",
    "临时文件关闭失败",
    "发布前旧状态被替换",
    "发布前临时文件被替换",
    "发布前锁文件被替换",
    "原子替换失败",
    "替换后目录同步失败不回滚",
    "读回打不开文件",
    "读回内容损坏",
    "读回 inode 不符",
    "读回错误",
    "读回关闭失败",
    "锁关闭失败",
    "目录关闭失败",
    "入场快照过期",
    "持锁读状态后快照过期",
    "发布前快照过期",
    "提交后快照过期只拒绝许可",
    "墙上时钟读失败",
    "墙上时钟倒退",
    "提交后墙上时钟失败",
    "root 不入场",
    "入口版本数字仅作诊断",
    "真实 UID 不一致",
    "全关配置不入场",
    "同进程重复调用不重复计数",
    "失败后同进程也不重试",
    "持锁重入被进程记录拦截",
    "继承父进程记录被拒绝",
    "读状态后 PID 改变",
    "写临时文件后 PID 改变不发布",
    "发布后 UID 改变不发许可",
    "files 目录权限过宽",
    "多用户路径",
    "独立进程顺序启动累加并熔断",
    "上次遗留临时文件阻断下次事务",
    "最大整数状态编码读回",
    "旧版两列状态升级五列",
    "状态多个硬链接",
    "锁多个硬链接",
    "临时文件多个硬链接",
    "用户目录关闭失败",
    "包目录关闭失败",
    "旧状态关闭失败",
    "锁文件非空",
    "最大允许长度的旧状态",
    "回放快照不入场",
    "开机时钟读失败",
    "files 元信息读取失败",
    "替换后失败的新进程继续读取新状态",
    "关闭锁期间快照过期",
    "关闭目录期间墙上时钟倒退",
    "关闭目录期间 PID 改变",
    "普通启动拒绝饱和恢复令牌",
    "非零令牌不能重置未熔断计数",
    "相同非零令牌也不是普通启动许可",
    "旧非零令牌也不能混入普通启动",
    "零令牌保留本地恢复高水位并正常计数",
    "零令牌不能清除带高水位的熔断",
]))


def run_trial(path):
    raw = Path(path).read_bytes()
    image = ElfImage(raw)
    entry = trial_entry(image, ENTRY)
    for case in CASES:
        run_case(image, entry, case, CASES)
    return {"持久化场景": len(CASES), "结果": "全部通过", "试验库SHA256": digest(raw),
            "边界": "真实后端实现，文件系统与时钟为内存替身；不是掉电或实机并发验收",
            "生产入口已接入写入后端": False, "安装许可": False, "未关闭门槛": [1, 3, 5]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--elf", required=True, help="独立交叉编译试验库，不是原型 APK 中的库")
    args = parser.parse_args()
    try:
        print(json.dumps(run_trial(args.elf), ensure_ascii=False, indent=2))
    except (AuditError, OSError, ImportError) as error:
        print("持久化离线验证失败：" + str(error))
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
