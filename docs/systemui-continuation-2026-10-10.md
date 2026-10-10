# SystemUI 续迁移与配置交互整理（2026-10-10）

本轮完成缺口筛选、源码适配、菜单整理、配置控件替换、回归与构建流程。
**不是西米露全量迁移完成，也不是设备验收通过。** 没有安装 APK、重启宿主或更改设备开关。

## 来源与宿主证据

- 上游直接使用 GitHub `https://github.com/ReChronoRain/HyperCeiler`，执行 `git fetch origin` 后仍为 `5e46860`。
  没有反编译西米露 APK，没有覆盖参考仓库已有的两个未跟踪辅助脚本。
- 对照 `SystemUIB.java`、`MoreNotificationSettings.java`、`NotificationImportanceHyperOSFix.kt`、
  `DefaultPluginTheme.kt`、`MediaSeekBar.kt` 和 `ControlCenterSettings.java`。
  后者的通知展开/焦点名单使用 `SubPickerActivity + ALL_APPS_MODE`，本轮移植的是这种选应用交互，不是输入包名。
- 宿主全部通过 MT MCP `http://192.168.10.85:8787/mcp` 的只读查询分析。
  SystemUI `wot5ikcq / 202602260`，插件 `gqbnd0ih / 183022200`。
- 新证据：`systemui-continuation-host-evidence.json` 11 项，`systemui-continuation-plugin-evidence.json` 5 项。
  另重新读取现有主包证据 54 项和 11 组资源、插件证据 42 项和 10 组资源、2 个布局文件映射。
  所有清单断言通过；这只证明宿主静态契约，没有执行 Hook。

## 功能与状态

| 功能 | 实现 | 边界 |
| --- | --- | --- |
| 通知重要程度 `notification_importance` | 新增系统界面/系统设置双作用域入口。复用渠道跳转链；Settings 端恢复 importance、badge、allow_keyguard 并绑定渠道等级写入 | **部分迁移**：SystemUI 静态核验通过，当前 Settings APK 不在 MT 可用工件中，其适配代码待核验，界面明确提示。目标完整契约不符则不暴露选项 |
| 音量面板使用默认主题 `volume_default_theme` | 只在音量滑块背景、ringer 模糊、展开背景三个调用域中放宽主题判断 | 不全局覆盖 ThemeUtils，不影响共享插件中的控制中心/设备控制主题；保留低端设备与材质判断，待设备验收 |
| 媒体卡片进度条粗细 `media_card_progress` | 复用原生着色器进度条与拖动监听；适配 attach、updateLayout 和外屏恢复 | **部分迁移**：只含 deviceLevel 1/2 的原生粗细。未迁移替换进度条、彗星、低性能绘制或灵动岛样式；待设备验收 |

三个主开关默认关闭。`SYSTEM_UI_FEATURES` 从 28 增至 31 个入口；**不能将 31 解释为完整迁移功能数**。

### 通知重要程度的 OS4 差异

上游在 `StackCoordinator$attach$1.onAfterRenderList` 根据代表通知的重要程度过滤整个列表。
当前 OS4 同时用该列表更新 `RenderNotificationListInteractor`，并且新增 `BundleEntry`。
本轮只拦截 `calculateNotifStats`：先调用宿主 `getFlatNotifEntryList` 展开组和 Bundle，
再独立过滤重要程度 0/1 的通知统计。未知/缺失 ranking 保留，原列表不改，不删除通知，
不让最低等级摘要连带排除高等级子通知；隐私、是否可清除和 section 判定仍由宿主计算。

Settings 端没有在模块里另建“包名 + 渠道 ID + 数字等级”输入框。
用户通过长按通知或“打开系统通知管理”进入系统原生渠道编辑器，选项保存到真实渠道。
监听器限制为 1..4 且须在原生候选中，遵循原偏好 enabled 状态，不解锁 block/绕过勿扰设置。
反射或持久化调用抛错时返回 false，并恢复内存中的原等级/importance 锁定位；
宿主 backend 若自行吞掉 Binder 错误仍需要设备复读验证，不能据本地返回值宣称成功。
关闭本功能不会撤销用户已经保存到系统的通知渠道设置。

### 进度条适配

上游默认 thickness 为 80 且直接按 dp 转 px；本轮不沿用该数值单位，使用明确的 0.1 dp 存储，
范围 1..16 dp，初始 8 dp。实际像素高度取偶数、不得超过宿主原生按压最大值。
不更改 View 布局高度、触摸范围、seek listener 或媒体会话；小屏复用时恢复原始高度。

## 菜单和交互

- 系统界面主页只保留“状态栏、锁屏、通知与控制中心、其他”四组。
  `gesture_line`、`wallpaper_monet`、`rotation_suggestion` 移至“其他”，键、默认值、作用域不变。
  依用户要求不再迁移西米露的旋转建议替代功能。
- 自动展开通知、焦点通知改为可搜索的应用多选：应用图标/名称、系统应用过滤、只看已选、清空、保存、取消。
  查询与图标读取在 IO 线程，列表懒加载；中文名称按本地排序规则显示。
- 原有逗号/分号/空白分隔包名继续读取，写回稳定的换行格式；未知/已卸载应用仍保留，允许取消选择。
  支持系统包 `android`，不放宽通配符。空名单显示不生效提示。
- 为枚举没有桌面入口的系统/通知应用增加 `QUERY_ALL_PACKAGES`；仅在打开选择器时本地读取，不上传列表。
- 莫奈主题色改为预设色板 + 色相/饱和度/明亮度滑块 + 种子色预览；支持恢复跟随系统。
  仍按原六位 RGB 设置键写回，旧配色不丢失；关闭对话框/取消不保存，只有确认时同步设置。
- 通知重要程度最初提供系统通知管理二级页；后续按用户要求改为「解锁通知重要程度」单开关，
  移除冗余跳转页，保留原设置键与双作用域，开关摘要注明 Settings 端待验证。
- 通用数值控件将导入/旧版本的越界值夹回滑块范围，避免 UI 显示与 Hook 安全取值不一致。

## 已迁移逻辑复查与修正

- 媒体自定义操作原先在每次图标生成/点击时永久清空共享云端及本地黑名单。
  改为同步的临时覆盖，finally 恢复原对象；嵌套调用、异常和部分设置失败均恢复，
  云端若替换了列表则保留新列表，不写回过时配置。仍走原生 transport controls/custom-action extras。
- 渠道跳转开关与“通知重要程度”共享一次 Hook 安装，不重复接管通知菜单。
  原来的通知 UID/会话 ID、Mi Push 路径和失败回退不变。
- 旧的手势、壁纸、图标、电池、移动网络、网速、时钟安装步骤统一接入逐项异常隔离，
  单个兼容性错误不阻断后续项目；这是安装阶段防护，不保证隔离任意异步/原生层崩溃。
- 复读现有主包/插件清单并运行全量单元测试；本轮没有重新逐条动态验收时钟、电池、AOD、小窗或音量面板。

## 暂缓项（没有静默当作已完成）

1. **Settings 宿主核验**：MT 当前 APK 列表没有 `com.android.settings`；ADB 无连接。
   未读取受 MT Home 策略保护的系统分区，也未尝试绕过策略。需要当前系统设置 APK 后检查类、偏好类型、backend 写入与重开持久化。
2. **强制音量模糊**：MT 实读 `Util.isSupportBlurS()Z` 已恒为 true，此版本无需重复 Hook，不添加无效开关。
3. **媒体始终深色**：已读当前前景颜色和 Full AOD 监听；上游移除整个 listener 也会丢失动画/透明度恢复，暂不移植。
4. **控制中心运营商完整模式、磁贴列表、通知元素配色**：本轮筛选后仍待独立消费链/资源适配；现有隐藏运营商不登记完整模式。
5. **WMShell/AOD/灵动舞台**：沿用前次 pending；资源 APK 不等于拿到 WMShell 实现 dex。

## 验证

- `:app:testDebugUnitTest :app:assembleRelease`：通过，226 项测试，0 失败/错误/跳过。
  新增 12 项，覆盖旧配置往返、分组/列表统计策略、等级输入边界、进度条单位/边界、临时名单恢复。
- `git diff --check`：通过。
- `:app:lintDebug`：未通过；22 个已有错误集中于旧 Hook 的 API/权限标注、SettingsActivity super 调用与本机 local.properties 转义。
  新增文件的私有 API 使用已局部注明，系统通知管理入口添加了版本门禁；没有新增全局 Lint 抑制或 baseline。
- Release 仍为 `0.1.3 / 4` 的本地后续开发产物，未安装。产物/测试摘要见 `systemui-continuation-build.json`。
- 没有连接设备，未进行截图、TalkBack、横竖屏或真实通知渠道写入验收。

```powershell
python tools/systemui_inventory.py .workbuddy/refsrc/HyperCeiler
python tools/mt_systemui_probe.py --manifest docs/systemui-continuation-host-evidence.json
python tools/mt_systemui_probe.py --workspace gqbnd0ih --manifest docs/systemui-continuation-plugin-evidence.json
.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease --console=plain
```
