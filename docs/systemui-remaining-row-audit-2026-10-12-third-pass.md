# 剩余行审计 · 第三轮（2026-10-12）

范围：在第二轮只读复核之后，把**仍然「有载体但未迁移」的 10 行**重新过一遍，
重点回答两个问题：

1. 第二轮列出的 6 条图标行（wifi / hotspot / airplane / data_saver / volume / no_sim），
   到底有没有**本地消费者**？
2. 3 条 HD 行与 `battery_detail_at_right` 是否也能用同一条通用适配器覆盖？

**本轮没有改任何行的状态名**（仍是 `audited_carrier_found_not_migrated`，
仍是「未迁移」），也没有编译、安装、重启宿主或执行任何设备功能动作。
下面所有结论都来自 MT MCP 只读工作区检查（class outline / smali 文本 / dex 字符串检索）
加真机只读 `adb`/`su` 节点检视，**不构成真机验收**。

## 结论一：6 条图标行有真实消费者，但它只解决「能不能生效」，不解决「效果对不对」

第二轮把「共享黑名单语义 = 隐藏」和「`ignoredSlots` 消费链」都证实了，但留下的
`openItemsForNextPass` 是「本地适配器只驱动 `ignoredSlots`，宿主同时用
`IconManager.mBlockList`」。第三轮把本地适配器的实际安装点读全（`IconBlockListTargets.kt`
+ `IconBlockListHooks.kt`）后确认：它**不是只驱动一条路径**，而是同时挂在

- **生产侧**：`HomeStatusBarIconBlockListBinder$bind$1$1$1.emit`（宿主活体黑名单来源）、
  `ControlCenterFakeStatusIcons` / `ControlCenterStatusBarIcon` 的 `onAttachedToWindow` /
  `onFinishInflate`、`KeyguardStatusBarViewController.onViewAttached`、
  `MiuiQSHeaderView.onAttachedToWindow`、`MiuiPhoneStatusBarView.updateCutoutLocation`；
- **消费侧**：`MiuiStatusIconContainer.setIgnoredSlots` / `addIgnoredSlots`。

也就是说，本地卡片写出的槽位名会同时进入宿主自己的黑名单路径和容器的 `ignoredSlots` 路径。
本地卡片为这六行序列化的字面量在本机是匹配的：
`wifi`/`demo_wifi`、`hotspot`、`airplane`、`data_saver`、`volume`（发布者拼写）、
`no_sim`/`nosim`。

**仍然没被证明的**是运行期结果：两条可见性路径在 hook 跑完之后是否一致，
以及模式 11/12 在现代 wifi/移动绑定上的表现。这两件事**只能真机验收**，
所以本轮**没有**把它们标成 implemented，只把 `determination` / `boundary` 从
「没有适配器」更正为「有消费者、待验收」，并保留 `openItemsForNextPass`。

## 结论二：3 条 HD 行**不能**被通用适配器覆盖

`new_hd` / `small_hd` / `big_hd` 上游写的是字段
（`smallHdVisible` / `volteVisibleCn` / `volteVisibleGlobal`）。本机只剩
`volteVisibleGlobal`，而且已经搬进 `MiuiCellularIconVM` 的 per-SIM
`ReadonlyStateFlow`（外加 `HDController.hideVolteMap`）。

这些 flow 的**身份**与上游字段名不是一回事，通用黑名单适配器完全不碰它们。
所以这三行仍是「缺一个自包含的 flow 适配器」——**不是**「共用一条已迁移路径」。
本轮没有为它们写任何开关。

## 结论三：`battery_detail_at_right` 仍缺原生右侧槽位

第二轮结论不变：`MiuiStatusBatteryContainer` / `MiuiBatteryMeterView` 只按具名字段
measure/layout，`MiuiStatusIconContainer` 只摆 `StatusIconDisplayable` holder，
所以「往右边塞一个普通子视图」不会被布局。左侧适配器已实现，右侧独立槽位仍无载体。

## 本轮对既有实现的额外核实（只读）

- **手电筒「亮度条实时控制」**：`MiuiBrightnessController.onChanged(ToggleSliderBase;ZZIZ)V`、
  `mExternalChange:Z`、`mMinimumBacklight:F`、`mMaximumBacklight:F`、
  `BrightnessUtils.convertGammaToLinearFloat(IFF)F`、`MiuiFlashlightControllerImpl`
  的 `mCameraManager` / `mCameraId` / `mFlashlightEnabled` 在 202602260 上**逐条存在**
  （见 `docs/flashlight-live-control-evidence.json`）。
- **上游写死的 sysfs 节点在本机全部不存在**（`/sys/class/flashlight_core`、
  `/sys/class/leds/flashlight`、`led:torch_0`、`led:switch_0`、`spotlight` 都不在；
  本机只有 `yellow:flash-0` / `white:flash-1` / `amber:flash-2`）。
  宿主自己的 `FLASH_DEVICES` 常量指向的 `/sys/class/leds/flashlight/brightness` 与
  `/sys/class/leds/spotlight/brightness` 同样不存在，所以本机走的是
  `CameraManager.turnOnTorchWithStrengthLevel`，不是节点写入。
- **5G 样式**：上游 `FiveGTile.java` 的 mode 1/2/3 与本模块
  `FiveGTileSettings`（样式一/样式二/内置移动网络磁贴）逐档对应；
  上游四张图标按字节比对是「两张图、每张 on/off 同哈希」，本模块
  `ic_qs_five_g_style1.xml` / `ic_qs_five_g.xml` 与上游 v1/v2 哈希一致
  （见 `docs/five-g-style-evidence.json`）。

## 仍未完成

- 6 条图标行：需要真机确认两条可见性路径一致、模式 11/12 正确。
- 3 条 HD 行：需要按 flow 身份写自包含适配器。
- `battery_detail_at_right`：需要真正的原生右侧槽位。
- 11 条歌词行依赖第三方 SuperLyric 模块，本项目不自造歌词来源。

真机安装、宿主重启与功能验收均**未执行**
（`runtimeAccepted=false` / `installed=false` / `hostRestarted=false` /
`deviceFeatureActionsExecuted=false`）。
