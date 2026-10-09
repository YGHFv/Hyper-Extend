# SystemUI OS4 迁移进度

目标：迁移西米露系统界面作用域的所有适用子项。**尚未完成全量迁移**；不能以旧类名不存在或工作量大为理由认定不适用。

## 2026-10-09 后续安装与推送

按用户请求已推送修复提交 `42d49da`，并于 13:25:22 覆盖安装 Release 0.1.3 / 4。
设备 APK 哈希与本地一致，安装前后 SystemUI、MiSound、截屏和 system_server PID 不变。
未重启、未热重载、未改开关或作用域；真机功能验收仍待进行。以下“未部署”为源码修复阶段历史状态。

## 2026-10-09 修复检查点：0.1.3 源码，未部署

六类确认缺陷已修复；补齐分应用面板模糊接口兼容、普通/语义媒体按钮缩放、Settings 旧控制中心入口和通知会话 ID；漫游/活动标记提前到绑定前替换，状态栏双击改为完整手势判断。
详细逐项证据、实现差异和验证结果见 [0.1.3 修复记录](migration-repair-0.1.3.md)。未安装 APK、未重启宿主、未更改功能开关或作用域。以下警告及后续批次为历史记录，设备上的旧 APK 风险仍未通过更新消除。

## 2026-10-09 完整性复查警告（0.1.2 历史）

安装并推送 `beb0fda` 后复查发现：非默认网速样式违反宿主双元素数组契约，存在 SystemUI 崩溃风险；截屏隐藏状态栏缺少可靠恢复与发送者认证；网速刷新间隔、电池位置交换、时钟位置与秒级刷新也有明确缺陷。**这些问题尚未修复，请勿启用非默认网速样式或重启宿主加载它们。**

逐项证据、28 个 SystemUI 入口和 7 个状态栏功能组的审查结果、其他模块的有限审查范围及修复顺序，见 [完整性审查](migration-completeness-audit.md)。此前“静态核验/构建通过”不代表行为迁移完整；媒体布局与旧控制中心父项已改标部分迁移。

2026-10-09 后续发布：完成防砖缺口修正、130 项测试与 `0.1.2` Release 构建，已在连接设备覆盖安装。
未重启宿主、未验收新迁移功能；安装与保护边界详见 `release-0.1.2-safety.md`。下方 109 项测试及 APK 哈希为迁移批次历史记录。

## 2026-10-09 分应用音量迁移补齐

此节覆盖后文“保留悬浮球/原生全屏面板”的旧实现边界，详见 `app-volume-migration.md`。

- 用户截图确认此前只迁入口不符合预期；新增 MiSound 控制器范围的旧球挂窗拦截、右侧局部毛玻璃卡片、滑块比例与滑入滑出动画。
- 修复入口高级材质因 helper 状态未变化而跳过背景初始化；按参考实现切换复制 helper 的局部状态后恢复未激活样式。
- 保留原生音源、音量写入、返回/超时路径和权限保护广播；增加分页复用清理、小窗口边界与关闭回调代次保护。
- MiSound 静态证据扩展为 20 项代码/布局目标与 4 组文件映射，插件为 42 项目标；均经 MT 实时重读。
- 本地 136 项单元测试通过；设备仅核对版本，未安装本轮包、未重启宿主，视觉与交互仍待真机验收。

## 最新检查点：2026-10-09 插件核验与续迁移

本节覆盖旧检查点的工件状态；后面的 15/16 工件、插件缺失和 92/103 测试记录为历史记录。
当前登记 **28 个 HyperCeiler 功能入口 + 1 个 HyperVolumeANC 实验入口**，并非设备验收完成数。

### 新工件与证据

- MT 可用工件增至 19 项。控制中心/音量插件 `miui.systemui.plugin` `18.3.2.22.0` / `183022200`，
  workspace `gqbnd0ih`，2 dex / 14371 classes；实际读取了代码与混淆文件名的布局。
- AOD `com.miui.aod` `DEV-2446.3.0.1-09042206` / `22446301`，workspace `7rdlzzo0`，3 dex / 22197 classes。
- WMShell `com.android.wm.shell` `17` / `37`，workspace `36se70sn`，**0 dex / 0 classes**。
  已收到的是资源 APK，不是独立实现代码；仍需包含实现的 JAR/其他容器，不再写成“完全没有 WMShell 工件”。
- 工件摘要保存到 `systemui-plugin-artifacts.json`。没有读取被 MT 策略禁止的系统分区，没有修改 MT 工件。

### 实现、设置与边界

| 项目 | 设置键 | 本次变更与验收状态 |
| --- | --- | --- |
| 隐藏控制中心编辑按钮 | `control_center_hide_edit` | 新增默认关闭开关；核对 `EditButtonController.available(Z)Z` 与 `MainPanelContentDistributor.distributePanels(Z)` 列表筛选消费链；请求去优化调用者，只令该入口不可用，不禁止编辑能力。需要编辑时关闭并重启。待真机验收 |
| 隐藏折叠音量底部按钮 | `volume_hide_collapsed_footer` | 新增默认关闭开关；折叠时隐藏 ringer 区域、保持宿主展开按钮，展开时遵循最近宿主 footer 显示请求；不照搬上游强制显示造成的屏蔽条件丢失。待真机验收 |
| 分应用音量入口修正 | `volume_app_entry` | 完成插件代码/布局静态核验；修正 native 状态边距、控制器收起、重复控件 ID、无障碍标签、资源刷新与异步请求生命周期。仍为实验性，待真机验收 |

插件加载由 `SystemUiPluginHooks` 统一监听工厂及缓存；不重复注册 API 102 同方法 Hook，不 Hook 全局 loadClass。
分应用音量入口不再在 View.showH 的 ThreadLocal 内改资源 margin，而在 native `ViewStateGroup.apply` 前后恢复/重算，
覆盖异步 pre-draw show 与展开/收起路径；翻折外屏和非顶部锚定布局跳过。
点击成功走 `VolumePanelViewController.dismissH(8)`，保留宿主 mShowing、消息队列和非空回调清理；
不再使用会触发空回调异常的 `View.dismissH(true,null)`。请求有两秒总等待，收起/分离/下一次显示使旧结果失效。
原生面板、悬浮球失败回退及发送方权限限制不变，不迁移 ANC 或上游面板改版。

### 本次验证

- SystemUI 主包 **54** 个代码/布局目标 + **11** 组资源映射、MiSound **9** 个目标重新实时核验通过。
- 音量/控制中心插件新增 **40** 个代码/布局目标 + **10** 组资源映射 + **2** 组资源文件映射，实时重读通过；
  可重复清单 `systemui-plugin-host-evidence.json`，脚本新增只读 `mt_apk_resource_read` 校验混淆布局路径。
- AOD 另核对 **3** 个候选方法，仅为定位证据，不能作为已迁移功能计数。
- `:app:testDebugUnitTest :app:assembleRelease` 通过；**109 项测试，0 失败/错误/跳过**。
  新增覆盖 native margin 重写、重复补偿、旋转/密度变化、收起/重显与过期广播结果、请求超时、footer 宿主抑制及设置边界。
- Release APK：`app/build/outputs/apk/release/app-release.apk`，2,490,966 bytes；
  SHA-256：`4a4326aaeda7e317a06a253226a9878f89c31eaebca45d244e6f2414eb4edae0`。
  已检查 APK 中 MiSound 作用域、HyperVolumeANC Apache 许可全文和 NOTICE。
- `git diff --check` 通过（已有 CRLF 规范化提示）；`adb devices -l` 为空，未安装模块或重启设备。

```powershell
python tools/mt_systemui_probe.py --workspace gqbnd0ih --manifest docs/systemui-plugin-host-evidence.json --output .workbuddy/tmp/mt-systemui/plugin-evidence-refresh.json
python tools/mt_systemui_probe.py --workspace 7rdlzzo0 --manifest docs/systemui-aod-candidate-evidence.json --output .workbuddy/tmp/mt-systemui/aod-candidate-refresh.json
```

### 继续迁移的定位结果与待办

- AOD 已读完整 `ShortcutViewLayoutController.onShortcutDataChanged/updateShortcutView/release`：
  **当前 updateShortcutView 是 `(ShortcutEntity, ImageView, View)` 三参数方法，每次只更新一侧**。
  上游 `AodBlurButton` 却根据一个 entity 同时修改左右按钮，不能直接移植，否则一侧空配置会错误影响另一侧。
  证据已保存到 `systemui-aod-candidate-evidence.json`；下一步核对插件加载路径、背景/模糊与 release 生命周期，再接设置。
- 灵动舞台仍需读通 `DeviceNotificationListenerImpl.structModelForCharge` 的模型消费链；旧 MIUIStrongToast 名称缺失不等于不适用。
- WMShell 仍缺实现 dex；只登记资源 APK 到位，不登记代码迁移完成。
- 新开关需分别及共同测试：编辑按钮关闭后恢复、折叠/展开/宿主屏蔽、音量连续按键、主题/材质切换、横竖屏、锁屏、TalkBack，
  以及 MiSound 冷启动/失败回退/多应用音量。详见 `app-volume-migration.md`；未做真机验收前不登记完整完成。

## 参考与验证依据

- 上游本地版本：HyperCeiler `5e46860`，OS4 入口以 `SystemUIB.java`（SDK >= 36）为准，不使用 `os2` 分支。
- MT MCP：`http://192.168.10.85:8787/mcp`，只读工作区 `wot5ikcq`。
- MT 返回宿主：`com.android.systemui`，`17.03.260226.r`，versionCode `202602260`，minSdk/targetSdk 均为 `37`。
- 已实际通过 MT `mt_apk_list/search/dex_outline_class/read_text` 读取目标 Smali，不只检查名字。
- 本地原始 MT 查询结果：`.workbuddy/tmp/mt-systemui/`（忽略的大体积诊断目录）；关键证据见下。
- 每次迁移后执行单元测试、Release 构建；尚未安装或做设备实测。

## 前批完成

| 西米露子项 | 模块键 | MT 确认的 OS4 路径 |
| --- | --- | --- |
| 解除通知限制 | `lockscreen_show_notifications` | `ExpandedNotification.canShowOnKeyguard()Z` 返回控制锁屏显示能力的布尔值；不改隐私过滤器 |
| 解锁后保留通知 | `lockscreen_keep_notifications` | `NotificationFilterController.forceHideOnKeyguard(NotificationEntry)Z` 读取 `mSbn.mHasShownAfterUnlock`，然后仍判断 `canShowOnKeyguard` |
| 锁屏时隐藏状态栏 | `lockscreen_hide_status_bar` | `statusbar.ui.viewmodel.KeyguardStatusBarViewModel` 构造器初始化 `isVisible:ReadonlyStateFlow<Boolean>`，无须走旧 CentralSurfaces 回调链 |
| 禁止显示蓝牙解锁成功 Toast | `lockscreen_hide_ble_toast` | `MiuiBleUnlockHelper.tryUnlockByBle()` 调用 `Toast.makeText(Context,int,int)`；资源 `0x7f1409ae` 被 MT 解析为 `miui_keyguard_ble_unlock_succeed_msg` |
| 亮屏时静音 | `notification_mute_when_interactive` | `MiuiAlertManager.buzzBeepBlink(NotificationEntry)V` 统一处理声音、振动和灯光，`mContext` 提供 PowerManager |
| 禁用透明模式 | `notification_disable_transparent` | `NotificationRowContentBinderInjectorImpl.isTransparent(Context)Z` 判断横屏透明模式 |
| 更多应用通知栏下拉打开小窗 | `notification_freeform` | `ExpandableNotificationRowInjector.updateMiniWindowBar()` 内部调用 `AppMiniWindowManagerImpl.canNotificationSlide(String,PendingIntent)Z`，再计算横条与背景 |

所有新增项默认关闭，分别归入锁屏、通知与控制中心、其他子页，不增加新的 LSPosed 作用域。

## 2026-10-08 续迁移：新增 14 项

源码直接使用用户指定的开源仓库 `https://github.com/ReChronoRain/HyperCeiler`。本轮执行 `git fetch origin` 后，
本地 HEAD 与远端 main 均为 `5e46860`；没有反编译西米露 APK。MT 仅用于读取目标 SystemUI 的 Smali/布局。

| 子项 | 模块键 | MT 验证入口与适配 |
| --- | --- | --- |
| PIN 密码键盘乱序 | `lockscreen_scramble_pin` | `KeyguardPINView.onFinishInflate`；数字 View 连同监听器重排，同时更新 `mViews` 与 `mNumViews`，保留紧急呼叫/删除/确认 |
| 双击锁屏空白处息屏 | `lockscreen_double_tap` | `NotificationsQuickSettingsContainer` 的继承触摸入口；只接受传到容器的空白触摸，使用完成的点击、单调时间、多指/拖动取消及 TalkBack 保护 |
| 隐藏锁屏勿扰提示 | `lockscreen_hide_zen` | `NotificationNumStateViewModel.isZenModeEnabled` 展示 Flow；不改全局勿扰控制器或通知计数流 |
| 隐藏底部解锁提示 | `lockscreen_hide_hint` | `KeyguardIndicationRotateTextViewController.updateIndication`；核对宿主类型名称后仅移除 13/17/18/19 操作提示，保留充电/设备策略/认证错误 |
| 第三方锁屏生物识别 | `lockscreen_third_party_biometrics` | `KeyguardUpdateMonitor.isUnlockWithFacePossible` 与新 `MiuiKeyguardUpdateMonitorStub.isUnlockWithFingerprintPossible(int)`；沿用上游能力查询，不改认证结果/强认证策略 |
| 勿扰通知修复 | `notification_zen_fix` | `MiuiAlertManager.buzzBeepBlink`；与亮屏静音合为一个 Hook，明确过滤器 2/3/4，未知状态不静音 |
| 原生剪贴板编辑浮窗 | `clipboard_native_overlay` | `ClipboardListener.onPrimaryClipChanged`；仅本次调用临时放宽来源列表，finally 恢复，不跳过锁定/敏感/初始化/主动抑制检查 |
| 点击磁贴后收起 | `control_center_auto_collapse` | `QSTileImpl.click(Expandable)` 与 `MiuiQSHostAdapter.collapsePanels`；排除编辑、不可用、管理员禁用磁贴 |
| 解除强制新版控制中心 | `control_center_unlock_old` | `ControlCenterSettingsRepositoryImpl.forceUseControlCenter`；构造后与 Flow 消费点同步放宽，避免 Eagerly 流早于构造后 Hook 的竞态 |
| 通知设置直达渠道 | `notification_channel_settings` | `NotificationSettingsHelper.startAppNotificationSettings` 参数直接包含渠道与 UID；保留菜单原有退出逻辑、Mi Push 特殊入口，失败回退 |
| 自定义莫奈主题色 | `systemui_monet_custom` | `ThemeOverlayController.createOverlays(int)` 的种子色；保留深浅色/对比度/风格，六位 RGB 输入，不注册重复监听器 |
| 指定应用焦点通知 | `notification_unlock_focus` | `NotificationSettingsManager.canShowFocusState/canShowFocusStateApp(Context,String):int`；只匹配配置的完整包名，空名单不放宽 |
| 禁止自动折叠通知 | `notification_disable_auto_fold` | `FoldCoordinator` 的新增、更新、收起、闹钟四条路径限定调用域；不全局覆盖其他用途的 `shouldIgnoreEntry`，不移动已折叠通知 |
| 媒体卡片自定义操作 | `media_unlock_custom_actions` | `MediaActionsKt$$ExternalSyntheticLambda0.invoke` 生成图标；另处理 `MediaActionsInjector$getCustomAction$2.run` 点击时的再次校验，保留宿主的 transport/extra |

以上 14 项与前批 7 项合计登记 21 项。颜色与包名列表使用现有文本配置控件；所有新增主开关默认关闭。
子菜单顺序固定为“状态栏、锁屏、通知与控制中心、其他”，不再被功能登记顺序改变。
每个新安装动作独立捕获异常，某个私有 API 不可用不阻断后续功能。

### 锁屏子项边界

上游 `SystemUIB.java` 的锁屏区实际装载 9 个 Hook，本轮完成此前剩余 5 个；这 9 项均已有实现和静态核验。
**不等于 `system_ui_lock_screen.xml` 全页、系统界面全量迁移或真机验收完成。**
该 XML 还保留按钮/模糊/充电/联动动画等 14 条设置和依赖，分别涉及 AOD 插件、旧分支、OS4 尚需重新定位的实现，继续保留 pending。

特别注意：上游隐藏提示代码按“静态、单参数”匹配，在本机命中的是 `-$$Nest$mhandleFingerprintStateChanged`，
其内容实际设置指纹失败提示，不能直接复制。第三方生物识别则保留上游强制能力语义，界面已提示未录入时可能出现不可用入口。

### 本轮验证

- `tools/mt_systemui_probe.py` 通过同一 MT MCP 实时重读 23 个代码/布局目标并断言关键消费逻辑；
  持久证据清单为 `systemui-host-evidence.json`，运行结果（含内容 SHA-256）在 `.workbuddy/tmp/mt-systemui/evidence-refresh.json`。
- 单元测试覆盖 PIN 唯一性与两套矩阵一致性、双击/拖动/取消/超时、勿扰与亮屏组合、提示类型保护、颜色解析、包名精确匹配、目录分组和方法签名。
- `:app:testDebugUnitTest :app:assembleRelease` 已通过（80 项测试，0 失败）；新增私有剪贴板 API 仅在特权宿主使用，局部 Lint 注释说明原因，没有全局关闭 Lint。
- 当前 `adb devices -l` 为空；没有安装模块、重启系统界面或声称真机生效。

可重复核验：

```powershell
python tools/mt_systemui_probe.py
python tools/systemui_inventory.py .workbuddy/refsrc/HyperCeiler
.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease --console=plain
```

### 与直接复制上游不同的处理

- 保留通知：在实际消费“已解锁展示”标志的位置清除标志，仍走宿主的用户隐私、工作资料和敏感通知限制。
- 蓝牙 Toast：只标记匹配资源生成的 Toast 对象；不使用上游嵌套注册 `Toast.show` 的做法，避免隐藏之后的所有 Toast。
- 小窗：仅在原横条更新方法调用期间放宽判断，并要求非空包名与 Activity PendingIntent，防止无跳转目标时显示不可用手势。保留原方法中的锁屏状态与横条显隐条件。
- 锁屏状态栏：使用已完成初始化的 ViewModel 构造后入口与宿主只读流，不依赖失效的旧对象查找链。

## 2026-10-09 续迁移：媒体卡片布局与字号

再次 `git fetch origin`，本地与远端 main 仍为 `5e46860`。继续只使用上游 GitHub 源码；
MT 只读核对 SystemUI `17.03.260226.r`，没有读取西米露 APK 实现。

新增 **2 个功能入口、9 个子配置**，不是 11 个独立功能。`SYSTEM_UI_FEATURES` 累计登记 23 个入口。

| 上游设置 | 模块键 | OS4 适配 |
| --- | --- | --- |
| 媒体布局总开关 | `media_card_layout` | `MiuiMediaNotificationControllerImpl.loadLayout()` 后更新普通布局与专辑内层约束；不改 `tinyLayout/tinyAlbumLayout` |
| 封面模式 | `.album` | 默认、隐藏应用角标、隐藏封面与角标；隐藏封面同时补标题/操作区的 gone margin |
| 按钮顺序 | `.order` | 两种上游排列，保留所有按钮对象、监听器、可见性及无障碍标签；将链样式移至新首按钮 |
| 按钮靠左 | `.left_aligned` | 仅清除末尾 action4 的右锚点，不改竖直约束 |
| 隐藏输出切换入口 | `.hide_seamless` | 普通布局约束 + `setSeamless(MediaData)` 后处理；保留宿主投放/设备管理逻辑，不跳过整个方法 |
| 标题顶部/艺术家间距 | `.title_margin` / `.artist_margin` | 十分之一 dp，按宿主 density 换算；默认 21/4 表示不覆盖系统资源 |
| 字号总开关及三项字号 | `media_card_text_size`、`.title` / `.artist` / `.time` | `attach(MiuiMediaViewHolder)` 与 `updateLayout$1()` 后设置标题、艺术家、已播放/总时长的 sp 字号 |

### 本批关键证据与边界

- `MiuiMediaViewHolder` **仍是普通 final 类，不是接口**，但 dex 没有声明 `<init>`。
  `NotificationSectionsManager.reinflateViews()` 内直接分配对象、初始化字段，再依次调用 `attach`、`loadLayout`、`updateLayout$1`。
  因此不沿用上游 holder 构造 Hook，也不挂全局 `Object` 构造器。
- `updateLayout$1` 会调用 `TextView.setTextAppearance` 重置标题和艺术家样式；字号必须在这一步后重新应用，
  不能只改首次创建。翻折小屏用宿主 `MiuiConfigs.isFlipTinyScreen(Context)` 判断，保留小屏字体；
  同一 holder 切到小屏时还原宿主不主动重置的时间字号，弱引用缓存不保留视图。
- 上游仅选“隐藏应用角标”时可能被 `needUpdate` 条件漏掉，本实现把此模式视为有效修改。
  上游按钮顺序改变后仍把 `spread_inside` 留在 action0，本实现将其移到真实链首。
- 使用宿主 classloader 反射调用 ConstraintSet，不依赖模块侧 AndroidX 对象。
  先克隆两个普通约束集并完成全部编辑，再发布字段；资源缺失或编辑失败不发布半套约束。
- 布局与字号共用宿主刷新方法时避免重复注册相同方法；只隐藏输出入口时单独挂 `setSeamless` 后处理。
  对已确认的创建/边界变化调用者请求去优化；仍需要设备验收确认框架实际挂载效果。
- **本批不含**按钮图标缩放、进度条、背景/氛围光、常暗模式或灵动岛布局。
  上游图标缩放依赖英文 contentDescription 且单位与 XML 标注不一致，不能直接照搬；这些键继续 pending。
  `MediaSeekBar` 的旧 observer 在当前目标已换成 `MiuiSeekBarRenderStateApplier`，也未算完成。

### 本批验证

- MT 持久证据扩充到 **39 个代码/布局目标 + 11 组资源名/ID 映射**，实时重读全部通过；
  资源名映射也纳入 `tools/mt_systemui_probe.py`，不是只靠硬编码 ID。
- 新增 12 项单元测试：默认不改布局、单独隐藏角标、隐藏封面的锚点、两种排列唯一性与双向连接、
  链首样式、靠左边界、dp 换算、不累积偏移、无效资源/密度、配置注册与范围、OS4 方法签名。
- `:app:testDebugUnitTest :app:assembleRelease` 通过，**92 项测试，0 失败/错误/跳过**。
- `adb devices -l` 仍为空；未安装 APK、未重启系统界面、未做真机验收。

## 待迁移与重新定位

### 2026-10-09 续迁移：通知展开/收起、运营商与分应用音量入口

**全量迁移仍未完成。** 本轮增加 3 个 HyperCeiler 设置入口（其中运营商仅一个模式），
并接入 1 个 HyperVolumeANC 实验性入口。不得将 4 个新开关称为 4 项设备验收完成。

| 项目 | 设置键 | 实现、核验与边界 |
| --- | --- | --- |
| 指定应用自动展开通知 | `notification_auto_expand` / `.packages` | 普通通知使用 `onNotificationUpdated/setSystemExpanded`，固定悬浮通知用 `setHeadsUp` 后原生展开监听器；重查锁屏/公开内容/通知身份/固定状态，保留用户手动选择；静态已核验，设备待验收 |
| 展开悬浮通知自动收起 | `notification_expanded_timeout` / `.delay` | `HeadsUpEntry.updateEntry(String,boolean)` 和三参数 `StatusBarNotificationPresenter.onExpandClicked`；单个可撤销任务、到期重查身份/固定/回复/菜单/全屏提醒，复用宿主 remove runnable，不删除通知；设备待验收 |
| 隐藏运营商名称 | `control_center_hide_carrier` | 两参数 `ControlCenterHeaderController.updateCarrierAndPrivacyVisible` 后仅把可见 carrierLayout 改为 INVISIBLE；不动隐私容器、HD 图标；仅此模式，原 XML 键标记 partial |
| 音量条上方分应用音量入口 | `volume_app_entry` | 接入设置、SystemUI 插件发现和 MiSound 原生面板点击；**插件内布局仍未静态核验**，仅登记 experimental，详见 `app-volume-migration.md` |

OS4 适配细节：

- 旧 `setFeedbackIcon` 不在当前通知行中，改用已读取方法体的绑定更新入口。
  `getEntry()` 实际经 Injector 的 `getLegacyEntry()` 获取，不假设通知行仍有旧 `mEntry` 字段。
- `mOnKeyguard` 在父类 `ActivatableNotificationView` 中；除它之外同时查询 KeyguardManager 和 `shouldShowPublic()`。
- `onExpandClicked` 参数是 row、EntryAdapter、boolean，不是上游假设的 entry、boolean。
  手动展开会直接取消宿主超时，而非调用 updateEntry，因此两个入口都处理；请求去优化已知调用者。
- 延迟展开/收起任务只持有弱引用，并检查当前 head/entry 身份；快捷回复和菜单状态变化能取消或阻止自动收起。
  仍须真机验证 avalanche/动画与用户触摸交错时序，单元测试不覆盖系统运行调度。

工件与后续定位：

- MT 清单变为 **16 项**，新增 `com.miui.misound` `16-4.0-4-20260903` / `260903`，已实际读取，workspace `yglu33cw`。
- `miui.systemui.plugin`、AOD 和独立 WMShell 工件仍没有；没有再次尝试被拒绝的系统分区目录。
- HyperVolumeANC 固定引用 `636ce289457ed10f149c033e3b723cdfc62838aa`，仅入口相关代码，保留 Apache-2.0 通知与许可全文并打入 APK。
- 灵动舞台已精确追查上游 `LazyClass.NewStrongToast`，实际名字为 `com.miui.toast.MIUIStrongToast`，主包精确类查询无结果。
  继续按资源查到 11 个 `strong_toast` 资源；高度资源无静态引用，充电文案指向
  `DeviceNotificationListenerImpl.structModelForCharge(...)`。这只是新路径线索，未读取完整消费链，不装猜测 Hook、不认定不适用。
- HyperCeiler 再次 fetch 后本地和远端 main 仍是 `5e46860`。

本轮可重复验证：SystemUI **54 个代码/布局/manifest 目标 + 11 组资源映射**、MiSound **9 个目标**实时重读通过。
证据分别在 `systemui-host-evidence.json` 和 `app-volume-host-evidence.json`；Hash 运行记录仍在忽略的诊断目录。
最终 `:app:testDebugUnitTest :app:assembleRelease` 通过，**103 项测试，0 失败/错误/跳过**；
Release APK 为 `app/build/outputs/apk/release/app-release.apk`（2,474,582 bytes）。已检查 APK 内包含新增作用域及 Apache 许可/NOTICE。
SHA-256：`f3256f01630ce7b87a297ecbe7c588586421ec1f39dfd1f9b8b2e8f8cdbdd39c`。
`git diff --check` 通过（仅已有 CRLF 规范化提示）；`adb devices -l` 为空，没有安装模块或重启设备。

新增待验收：指定包名与空名单、手动收起后更新、60ms 内更换通知/进入锁屏、悬浮结束后回列表；
超时中途回复/开菜单/收起再展开/应用撤回/更新同 key；运营商隐藏时相机/麦克风/定位提示仍正常。
分应用入口另按专用文档六组场景验收。

### 2026-10-09 全量续迁移检查点（未新增完成项）

- 重读 MT 可用 APK 清单，共 15 个条目；仍未提供控制中心/音量插件、AOD 插件或独立 WMShell 工件。
- 只读请求 `/system_ext/priv-app` 与 `/product/priv-app` 均返回 `FILE_ACCESS_DENIED`，原因为 MCP 文件策略。
  不再尝试绕过；后续需将相关目标系统工件放入 MT 允许目录，或由用户调整允许范围。
- 主包名称搜索已定位 `ExpandableNotificationRow`、`HeadsUpManagerImpl$HeadsUpEntry`、
  `StatusBarNotificationPresenter`、`ControlCenterCarrierText`、`ControlCenterHeaderController`、`MiuiCarrierTextLayout`。
  这些仅为定位结果，下一步须读取方法体/消费链，不能据此登记功能完成。
- `StrongToast` 名称搜索无结果；需继续按上游 `LazyClass.NewStrongToast` 的实际匹配与行为定位，不能判为不适用。
- 本次只做工件与候选定位，未新增 Hook、设置或构建；最后完成验证仍为上一批的 92 项测试与 Release 构建。
  原始结果在 `.workbuddy/tmp/mt-systemui/continuation-*.json`、`next-search-*.json`。

`systemui-upstream-inventory.json` 保存每个上游 XML 键、中文标题与迁移状态。它包含设置页入口和依赖分组，不能将行数直接称为功能数。

生成命令：

```powershell
python tools/systemui_inventory.py .workbuddy/refsrc/HyperCeiler
```

- 锁屏：`SystemUIB` 的 9 项已实现；XML 中按钮/模糊/充电/联动动画等剩余行仍待逐项核验，不能与已完成的 9 项混算。
- 状态栏：现有迁移仍待逐项审计；移动网络类型、双排图标、时钟剩余设置、电池信息、灵动岛未完成。
- 导航、磁贴、天气、媒体卡片剩余子项、控制中心布局及其他页仍待迁移；媒体布局/字号仅完成普通通知卡片的上述范围。
  需要插件 APK 的条目先定位插件，不用 SystemUI 主 dex 的零结果代替验证。
- 最新工件状态见开头检查点：控制中心/音量插件与 AOD 已到位，WMShell 只有无 dex 的资源 APK；`MiuiDecoration*` 的实现仍需继续定位，不能判为不适用。
- `control_center_unlock_old` 上游设置位于 `system_settings.xml`，清单单独纳入该键；本轮只迁移 SystemUI 侧，不宣称系统设置侧的样式入口也已解锁。

## 待用户设备验收

1. 新增每项单独开启并重启系统界面，确认关闭后恢复系统行为。
2. 通知：解锁再锁屏后仍可见，但机密通知、工作资料与隐藏敏感内容仍遵循系统设置。
3. 蓝牙解锁：只有成功提示消失，其他 Toast 和解锁条件不受影响。
4. 亮屏静音：亮屏无通知声音/振动，息屏提醒及通知本身仍正常。
5. 横屏通知：透明背景被禁用，竖屏和锁屏通知布局正常。
6. 小窗：测试有 Activity 跳转的普通通知、Mi Push、无跳转目标通知及锁屏场景；无可用目标不显示强制手势。
7. 锁屏状态栏：锁屏隐藏，解锁后恢复；控制中心、横竖屏和 AOD 不出现图标卡死。
8. PIN：所有数字各出现一次，输入内容与数字一致，删除/确认/紧急呼叫正常；横竖屏、单手模式及进入/退出动画无错位。
9. 锁屏双击：空白处双击息屏，拖动/多指/通知操作/解锁后不误触；TalkBack 双击不息屏。
10. 提示：隐藏操作提示/勿扰提示时，充电、错误提示、必须输入密码提示与通知计数仍可见，实际勿扰规则不变。
11. 剪贴板：普通复制出现浮窗，敏感内容不明文暴露；锁定设备、用户切换与系统主动抑制仍正常。
12. 控制中心：普通磁贴点击收起，编辑/不可用/管理员限制/长按不受影响；旧样式遵循系统设置，重启后不闪回新样式。
13. 通知渠道：普通、工作资料及双开应用进入正确渠道；空渠道/无可用设置 Activity 回退正常，菜单正确退出。
14. 自定义色/焦点名单：空值与非法值不生效，包名仅精确匹配；关闭后重启恢复系统行为。
15. 媒体操作：应用声明的自定义操作既有图标也可点击，应用未提供的操作不凭空出现；切歌、切换应用与云规则刷新后复测。
16. 媒体布局：只改角标也能隐藏；切换两种按钮顺序、靠左与隐藏封面后，播放/上一首/下一首/自定义操作仍对应原按钮；只有 1/3/5 个操作时无重叠。
17. 媒体字号与输出入口：布局、字号分别及同时开启，横竖屏、切歌、主题变化、锁屏/AOD 后保持配置；隐藏输出入口不停止已有投放。大字体下检查截断与触摸目标。
18. 翻折外屏、灵动岛保持宿主样式；主屏/外屏切换后时间字号不残留。关闭两个主开关并重启后恢复原布局与字号。
