# 本仓库的协作约定

以下约定对本仓库的所有改动生效，优先于模型的默认行为。

## 1. 语言

所有回复、提交信息、代码注释与文档一律使用**中文**。

## 2. 默认禁止上传与同步

**默认不执行任何上传、推送、同步、发布操作。**

`git push`、`gh release`、上传构建产物等一律需要用户**当次显式要求**才可以执行；
用户没有明确说「推送」「上传」「发布」时，一律只在本地提交，不得触碰远端。

## 3. 禁止操作设备

只允许**读取日志**与**通过 MT MCP 只读分析安装包**。禁止对用户设备执行任何写操作，
包括但不限于 `adb shell input/am/wm/svc/pm/screencap/uiautomator`、`killall`、
`force-stop`、清数据、改设置、重启 SystemUI。

例外：用户明确说「安装」时，才允许执行安装。

## 4. 修 bug 必须代码取证

结论必须落到具体类/方法/字段/指令位置，禁止只凭现象猜测原因。

## 5. 不得提交个人信息

仓库是公开的。文档与脚本里**禁止**出现设备序列号、整机型号、内网地址、账号等
与代码逻辑无关的个人信息。

提交前先跑：

```bash
python tools/redact_personal_info.py --check
```

输出必须是 `0 files, 0 occurrences`。发现命中时执行 `python tools/redact_personal_info.py`
就地替换为占位符。

要清理的值放在**本地、不入库**的 `.redact-patterns.txt`（格式见
`.redact-patterns.example.txt`，该文件已在 `.gitignore` 中）。不要把真实设备序列号、
型号或内网地址写进任何会被提交的文件 —— 包括这个脱敏脚本本身。

注意：宿主构建指纹（如 `NotificationManagementTargets.FINGERPRINT`）是代码逻辑的一部分，
被 hook 用作门禁逐字比较，**必须保留**，不要脱敏。
