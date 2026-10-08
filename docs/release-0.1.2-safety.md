# 0.1.2 Release 防砖核验与安装

日期：2026-10-09。版本 `0.1.2` / versionCode `3`。

## 检查中发现并修正

- 原自动熔断只统计 system_server，无法覆盖 SystemUI 独立崩溃循环。新增 SystemUI 私有状态文件，两个宿主独立统计；同一五分钟窗口三次重启（第四次启动）触发持久熔断。
- 原窗口每次启动向后滑动，不能严格表示“同一五分钟”。新增 windowStartAt，兼容读取旧四字段状态；已熔断状态不会因时间过去自动解除。
- 原状态写入可能留下截断文件。改为同目录临时文件、文件同步后替换；坏状态、IO 失败、回拨/不可信时钟均跳过 Hook。
- 原 HookRuntime 异常兜底可能在宿主已执行后再次调用 proceed。新增单次调用结果保护，包含 null/void 和异常结果；模块后处理失败保留宿主结果，宿主异常不重试、不伪造成功。
- API 102 使用 PASSTHROUGH，由模块自己的保护层处理拦截器错误，避免框架再次重放原调用；四个 proceed/proceedWith 重载均代理到单次调用保护。
- 设置类型异常默认关闭；紧急停用属性读取失败也跳过 Hook。文件标记受 SELinux/存储权限影响，恢复说明优先使用 root 属性命令。
- 更新恢复说明。没有取消防砖开关、清除设备已有保护状态或批量启用新功能。

## 验证

- `:app:testDebugUnitTest :app:assembleRelease` 成功：130 项测试，0 失败、错误或跳过。
- 新增 21 项测试覆盖独立存储、阈值/时间边界、一次性解除、旧状态兼容、坏文件/写入失败、单次宿主调用、空结果/原异常、默认关闭与主子开关联动。
- `git diff --check` 通过，只有已有 CRLF 规范化提示。
- R8 已开启，APK `debuggable=false`；Xposed 入口、作用域与许可文件存在，热重载仍关闭。
- 沿用设备既有 Android Debug 证书签名以支持无损覆盖；这是项目当前签名策略下的 Release 构建，不是新建独立发布密钥签名。
- APK 大小：2,490,970 bytes。
- APK SHA-256：`5d6a2dcf3dced6f41291c88667febbda8b3268965941acab0cdee6c83fd87d59`。
- 签名证书 SHA-256：`344981218ad8fc329b462725d64493ad01dabedaf84f2a4a75a37d814053fd94`，与已安装旧版相同。

产物：`app/build/outputs/apk/release/HyperExtend-0.1.2-release.apk`，同时保留标准 `app-release.apk`。

## 安装结果

- 连接设备：Xiaomi 25098PN5AC / pandora，SDK 37，root 可用。
- 安装前备份旧 APK 和本地设置到忽略目录 `.workbuddy/tmp/release-safety/`，不在仓库提交用户配置。
- `adb install -r` 返回 Success；系统包管理器确认 `0.1.2` / `3`，设备 APK SHA-256 与本地完全一致。
- 覆盖后逐键核对本地设置未变化；未清数据，未开启新迁移功能，未更改 LSPosed 作用域。
- 启动模块 SettingsActivity 返回 Status: ok，模块进程存在。
- 安装后 25 秒观察 SystemUI 与 system_server PID 未变化，boot_completed 为 1，紧急停用属性与框架熔断属性保持原空值。

## 验证边界

没有重启 SystemUI、system_server 或整机；当前已运行宿主仍可能使用旧代码，完整加载新版需用户择时重启设备。
没有人为制造开机循环、写入熔断状态或进行恢复模式破坏性演练。自动熔断的策略/持久化由本地测试验证，不能据此承诺绝对不会变砖。
此安装与健康观察不替代音量入口及其他迁移功能的真机验收；默认关闭的新功能应逐项启用验证。
