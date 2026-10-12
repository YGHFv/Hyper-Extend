# 剩余行审计（2026-10-12）

范围：上一批 14 项迁移后，`docs/systemui-upstream-inventory.json`（337 条）里**再也没有载体映射**
的那批行——132 条 `needs_audit_existing_partial` 中的 27 条 `system_ui_status_bar*`，
加上唯一一条 `pending`（`prefs_key_system_ui_move_log_to_miui`）。
逐条结论与 MT 只读证据见 `docs/systemui-remaining-row-audit-2026-10-12.json`。

**本轮没有编译、运行测试、安装、重启宿主、热重载或执行任何设备功能动作。**
下面所有结论都来自 MT MCP 只读工作区检查（class outline、smali 文本、dex 字符串检索、
资源读取）加参考源码比对，**不构成真机验收**。

## 结论分布

| 结论 | 条数 | 含义 |
| --- | --- | --- |
| `audited_carrier_found_not_migrated` | 10 | 当前宿主确实存在 MT 验证过的载体，但本地还没有适配 |
| `audited_external_dependency` | 11 | 上游行为依赖第三方 SuperLyric 模块，本项目不依赖它 |
| `audited_no_current_carrier` | 3 | 上游 hook 靶点在当前宿主没有生产者/消费者 |
| `audited_test_action_only` | 2 | 调试用「点一下发一条」动作行，不保存状态 |
| `audited_not_a_feature` | 2 | 上游本来就隐藏/从未被读取的行 |
| 合计 | 28 | 132 条中的 27 条状态栏行 + 1 条 `pending` |

## 关键判定

**有载体但未迁移（10 条）**

- `icon_data_saver`：上游 `DataSaverIcon.java` 直接改 `PhoneStatusBarPolicy.onDataSaverChanged(boolean)`
  的入参；该签名在 202602260 上**原样存在**，是最干净的一条可迁移项。
- `icon_wifi` / `icon_hotspot` / `icon_airplane` / `icon_volume`：上游改的是
  `MiuiIconManagerUtils.RIGHT_BLOCK_LIST` / `CONTROL_CENTER_BLOCK_LIST`。两个列表仍在，且**语义已确定**
  （见下面的更正说明）：成员资格 = 隐藏。WIFI 槽位由 `IconManager.addHolder` 建
  `ModernStatusBarWifiView`（dex xref 只有 `addHolder` 与 `DemoStatusIcons.addModernWifiView` 两个调用者），
  热点槽由 `PhoneStatusBarPolicy` 发布（`mSlotHotspot` + `PhoneStatusBarPolicy$3.onHotspotChanged`），
  飞行模式由 `MiuiStatusBarSignalPolicy.updateAirplaneModeIcon(Z)` 发布，响铃静音槽由
  `PhoneStatusBarPolicy.mSlotMute` 发布（值为字面量 `"volume"`）。
- `icon_mobile_network_signal_no_card`：真实载体是 `MiuiStatusBarSignalPolicy`
  （`mSlotNoSim` 取自资源 `0x7f141032` = `"no_sim"`，`updateNoSimIcon(String)` + `mLastShowNoSim`），
  并且 `MiuiStatusIconContainer.onLayout` 确实用 `StatusIconDisplayable.getSlot()` + `ignoredSlots.contains`
  决定跳过——这条链现在是**已证实**而非待定。
- `icon_new_hd` / `icon_small_hd` / `icon_big_hd`：上游写的字段
  （`smallHdVisible` / `volteVisibleCn` / `volteVisibleGlobal`）只剩 `volteVisibleGlobal`
  还在，且已搬进 `MiuiCellularIconVM` 的 per-SIM `ReadonlyStateFlow`，另有
  `HDController.hideVolteMap`。需要按 flow 身份重做适配，不是改个字段名。
- `statusbar_battery_detail_at_right`：`MiuiStatusBatteryContainer` /
  `MiuiBatteryMeterView` 只按具名字段 measure/layout，`MiuiStatusIconContainer` 只放
  `StatusIconDisplayable` holder，所以「往右边塞一个普通子视图」在 202602260 上不会被布局——
  需要真正的原生右侧槽位适配。

**无载体（3 条）**

- `hide_icon_speakerphone`、`hide_icon_call_record`：`"speakerphone"` 只出现在
  `DemoStatusIcons` / `DemoModeFragment` 的演示模式派发里，`"call_record"` 与 `"record"`
  在整包 dex 里 **0 命中**。没有可隐藏的生产者，因此没有写 hook。
- `move_log_to_miui`：`statusbar_gestures` / `GestureRecorder` / `mGestureRec` / `move_log`
  在 SystemUI 与 framework DEX 两个工作区都 0 命中。上游只是搬文件位置，当前宿主没有这个
  手势日志文件，所以无处可搬。

**外部依赖（11 条）**

歌词页整页（`statusbar_music_*` + `music_tip`）由第三方 **SuperLyric** 模块通过
`com.hchen.superlyricapi.SuperLyricData` 驱动（歌词文本、滚动速率、延迟、垫宽都在回调里）。
本项目不依赖该模块，`superlyric` 在当前宿主镜像里也 0 命中；宿主侧只有
`com.android.systemui.statusbar.widget.FocusedTextView`。要自包含重做就得自己造歌词来源，
不属于本次迁移范围。**不提供空开关。**

**非功能行（4 条）**

- `strong_toast_test_short_text` / `_long_text`：上游 `FocusNotificationSettings.java` 只挂
  点击监听发一条 `MiuiStringToast`，不保存状态。
- `big_mobile_network_type`：上游 XML `android:isPreferenceVisible="false"`，源码对它 0 引用。
- `statusbar_music_tip`：`android:enabled="false"` 的纯文字提示条。

## 生成器接线

`tools/systemui_inventory.py` 新增 `REMAINING_ROW_AUDIT` 常量与应用块：只吸收
**既没有 `target`、状态仍是 `needs_audit_existing_partial`/`pending`** 的行，并在产物里写入
`remainingRowAudit`、`hostCarrier`、`boundary`、`determination`。已映射的行不受影响。
回归断言见 `tools/test_systemui_inventory_audit.py`（钉住常量集合 = 证据 JSON = 28 条，
并断言状态名不含任何「已实现」状态）。

## 第二轮更正（同一次只读复核）

第一版证据里有三处结论（外加一处原本列为「不可静态判定」的问题）经 `mt_apk_dex_xref` 入边引用、
`mt_apk_resource_read` 取值与精确大小写 `dex_strings` 检索后**被修正**；下列更正已写回 JSON，
第一版原话保留在 `.workbuddy/tmp/bak/audit.json.bak` 便于对照。

- **`icon_volume`**：原文「`"volume"` 字面量在整包 dex 里不存在」**是错的**。
  `PhoneStatusBarPolicy.<init>` 用 `const-string/jumbo p1, "volume"` 给 `mSlotMute` 赋值，
  `MiuiPhoneStatusBarPolicy.updateVolumeZen()` 用它调 `setIcon` / `setIconVisibility`。
  精确检索 `"volume"` 得 17 条命中、4 条入边。共享列表里用的是**另一个拼写** `"mute"`，
  这才是「上游列表路径失效」的真实原因，而不是槽位名消失。
- **`icon_airplane`**：原文把生产者写成 `MiuiPhoneStatusBarPolicy.updateAirplaneModeIcon(Z)`，
  但该符号**不存在**。真实所有者是 `MiuiStatusBarSignalPolicy.updateAirplaneModeIcon(Z)`，
  dex xref 入边只有 `StatusBarSignalPolicy$$ExternalSyntheticLambda1.accept` 与
  `MiuiMobileIconsInteractorImpl$satellite$2.invokeSuspend`。
- **`icon_hotspot`**：原文「状态栏侧唯一出现是演示模式派发」不准确；
  `PhoneStatusBarPolicy` 自己发布状态栏热点槽（`mSlotHotspot`、`onHotspotChanged`）。
- **共享列表语义**：第一版把「成员资格=隐藏还是放右侧」列为不可静态判定，现已证实为**隐藏**：
  `IconManager.addHolder` 对成员槽位强制 `visible=true` 后建视图，`refreshIconGroup` 逐子视图算
  `setBlocked(强制 || mBlockList.contains(slot) || ...)`，`MiuiStatusIconContainer.onLayout/onMeasure`
  只对 `isIconBlocked()==false` 的图标布点。列表是普通可变 `ArrayList`，
  `setBlockList` 的活体来源是 `HomeStatusBarIconBlockListBinder` 的 `emit`。

这几处更正**没有改变任何状态名**（仍是 `audited_carrier_found_not_migrated`，仍未写适配器），
只是把「载体的名字/所有者/语义」改对。

**注意**：在**当前工作区**目录里直接重跑该生成器会触发历史验证门控，把 31 行已审状态降级
（`docs/remaining5-resolution-2026-10-12.json` 已记录过同一原因：生成门槛依赖验证清单逐文件
哈希匹配）。本次只把生成器与证据落库，**没有**重写 `docs/systemui-upstream-inventory.json`。

## 未完成

- 10 条「有载体但未迁移」仍需要一个当前宿主适配器才能真正落地；本审计只定位载体与边界，
  不宣称任何一条已实现。
- 六条图标行（wifi/hotspot/airplane/volume/data_saver/no_sim）另有一个**待验证**问题：本地
  `IconBlockListHooks` 只驱动 `MiuiStatusIconContainer.ignoredSlots`，而宿主同时用
  `IconManager.mBlockList` 驱动 `setBlocked`；槽位字面量在本机是匹配的（如 `"volume"`、`"no_sim"`），
  但两条可见性路径在本地 hook 后是否仍一致、模式 11/12 对现代 wifi/移动绑定的表现，都不是本次
  只读检查能定的。已作为 `openItemsForNextPass` 记入 JSON，**未**据此提升任何状态。
- 真机安装、宿主重启与功能验收（`runtimeAccepted=false` / `installed=false` /
  `hostRestarted=false` / `deviceFeatureActionsExecuted=false`）全部仍未做。
