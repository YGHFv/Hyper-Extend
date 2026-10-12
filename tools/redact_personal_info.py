#!/usr/bin/env python3
"""把仓库里与代码逻辑无关的个人信息替换成占位符。

## 为什么需要这个脚本

这个仓库是公开的，而开发过程写下的取证文档、安装记录里带上了真实设备与网络信息：
设备序列号、整机型号、内网 MCP 地址。它们既不是模块逻辑的一部分，也不该出现在
公开仓库里，所以统一替换成稳定的占位符，保留文档的可读性与可复现性。

## 保留什么

只替换「换掉之后语义完全不变」的值。刻意**不动**：

* 构建指纹（`Xiaomi/pandora/...`）：它是 `NotificationManagementTargets.FINGERPRINT`，
  被 `AudioMuteHooks` / `FlashlightStrengthHooks` / `SunlightTileHooks` /
  `NotificationManagementHooks` 当作宿主门禁逐字比较，属于代码逻辑；
* MT MCP 工作区 ID 与 `/storage/emulated/0/MT2/mcp` 路径：是分析环境描述，不指向个人。

## 模式从哪来

要清理的值本身**不能写在这个脚本里**：脚本是要入库的公开文件，若把设备序列号写进去，
等于换个地方继续泄露（脚本自扫描时也会命中自己）。所以模式放在**本地、不入库**的
`.redact-patterns.txt` 里（已加进 `.gitignore`），格式见 `.redact-patterns.example.txt`。

这份文件不存在时脚本直接跳过，视为「本机没有需要清理的值」—— 这也是全新克隆的正常状态。

用法：
    python tools/redact_personal_info.py --check   # 只报告，不写入
    python tools/redact_personal_info.py           # 就地替换
"""

from __future__ import annotations

import argparse
import pathlib
import subprocess
import sys

# 本地模式文件。故意不入库，避免把要清理的值写进公开仓库。
PATTERN_FILE = pathlib.Path(".redact-patterns.txt")

# 解析失败或文件缺失时的说明，供调用方区分「没命中」和「没配置」。
NO_PATTERNS = "未找到 %s，未执行扫描。" % PATTERN_FILE


def load_patterns(path: pathlib.Path = PATTERN_FILE) -> dict[str, str]:
    """读取 `<原始值><制表符><占位符>` 形式的模式表；缺文件返回空表。"""
    if not path.is_file():
        return {}
    patterns: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        original, _, placeholder = line.partition("\t")
        original, placeholder = original.strip(), placeholder.strip()
        if original and placeholder:
            patterns[original] = placeholder
    return patterns


def tracked_files() -> list[pathlib.Path]:
    """只处理 Git 跟踪的普通文件；二进制与构建产物不在扫描范围内。"""
    out = subprocess.run(
        ["git", "ls-files", "-z"], capture_output=True, check=True
    ).stdout.decode("utf-8")
    files = []
    for name in out.split("\0"):
        if not name:
            continue
        path = pathlib.Path(name)
        if path.is_file():
            files.append(path)
    return files


def redact(text: str, patterns: dict[str, str]) -> tuple[str, dict[str, int]]:
    hits: dict[str, int] = {}
    for original, placeholder in patterns.items():
        count = text.count(original)
        if count:
            hits[original] = count
            text = text.replace(original, placeholder)
    return text, hits


def process(path: pathlib.Path, patterns: dict[str, str], write: bool) -> dict[str, int]:
    raw = path.read_bytes()
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError:
        return {}
    updated, hits = redact(text, patterns)
    if hits and write:
        path.write_bytes(updated.encode("utf-8"))
    return hits


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="只报告命中，不写入文件")
    args = parser.parse_args()
    write = not args.check

    patterns = load_patterns()
    if not patterns:
        print(NO_PATTERNS)
        return 0

    total: dict[str, int] = {}
    touched: list[str] = []
    for path in tracked_files():
        hits = process(path, patterns, write)
        if not hits:
            continue
        touched.append(path.as_posix())
        for original, count in hits.items():
            total[original] = total.get(original, 0) + count

    for name in sorted(touched):
        print(name)
    print(f"---- {len(touched)} files, {sum(total.values())} occurrences ----")
    for original, count in sorted(total.items(), key=lambda kv: -kv[1]):
        # 只回显占位符，不回显原始值本身，避免把敏感内容打印进终端日志。
        print(f"{patterns[original]}: {count}")
    if not touched:
        print("没有需要脱敏的内容。")
    elif not write:
        print("（--check：未写入）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
