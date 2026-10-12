# 原生面板、卡片颜色与头部渐变续迁（2026-10-11）

## 最新持续检查点：未完成、未构建

337条：14 pending、27待审计、12结构导航、93有界静态、10修复待设备、
4部分静态、132源码部分未编译、43源码修复未编译、2其他。
SYSTEM_UI_FEATURES现63（新增实际GMS磁贴），STATUS_BAR_FEATURES现17。
当前灵动岛背景/配色/氛围光六项已接入独立双Holder适配；GMS磁贴有独立工厂、
经典/插件编辑器和明确点击后的有界包状态事务。没有执行任何包状态写入。
普通/灵动岛原生进度修复level0/1真实getMaxHeight几何链，拖动保持原生。
本段17个新增测试仅定义未执行；历史606不覆盖现源码。
WMShell实际依赖com.miui.wm.shell；Launcher导出无DEX，依赖hyperos.rustruntime.v5。
这两者缺少实际实现工件，不代表功能不存在。14实际待迁移项、27项待审计、
所有部分实现遗留及统一构建/全337复核仍未完成。未安装、重启、热重载或改设备。
准确源码清单、完整成员证据和恢复队列见 `docs/migration-continuous-progress-2026-10-11.md`。
本节覆盖下方历史计数与旧未实现判断。

## 持续 worker 最新检查点（覆盖下方历史队列）

当前目录 60；337 条记录中 17 pending、126 待审计、12 结构导航、
93 有界静态、19 修复待设备、4 部分静态、60 源码部分未编译、4 修复未编译、
2 其他。卡片/detail/VoWiFi 保留链、天气与当前用户、焦点混色、导航几何、
三项手势线尺寸、普通媒体配色/反色/环境光、百分比浮层和锁屏按钮材质均已有
新的有界实现，不代表全量迁移完成。媒体 level 1/2 原生进度 shader 也已通过
同步 Paint 调色接入，保持 shader/纹理/alpha/几何，未运行设备渲染验收。
最新再补经典亮度/硬件音量面板百分比、已解析应用图标回退、普通文字 Toast
独立背景及 5G 长按设置路由，仍是源码部分迁移，不是全量完成。

未构建、未运行测试/lint/R8、未部署或修改设备。准确实现边界、17 个 pending
键及后续队列以 `docs/migration-continuous-progress-2026-10-11.md` 为准。
下方“卡片/天气/导航/锁屏仍未实现”等是该次历史检查点，不覆盖最新源码。

## 当前状态

保留开始时全部未提交源码和历史构建/安装证据。本次继续实现四个默认关闭的功能，
对应六条上游配置记录；不是全量迁移完成。没有运行 Gradle、Kotlin/Java 编译、
单元测试、lint、R8 或打包，没有安装、重启、热重载、修改功能/作用域/网络开关。
未提交、推送、启动后台迁移任务或委派代理。

用户要求的顺序仍是：剩余项逐项迁移、问题集中处理，然后统一编译，最后从头复核
全部功能。当前仍有未完成项，**尚未进入统一编译或编译后的全功能复核**。

## 源码实现

### 原生音量面板与原生关机菜单

- `volume_native_dialog` 与 `power_native_menu` 独立默认关闭，分别归入音量与电源面板。
- 上游 `OtherSettings.generateListener` 是 PackageManager 组件禁用/恢复操作，并非
  普通 hook。这里不复制该机制，不改变插件 APK 的组件状态。
- 当前宿主 `VolumeDialogComponent.<init>`、`GlobalActionsComponent.start` 都通过
  `ExtensionBuilder.withPlugin` / `withDefault` / `build` 选择实现。只在对应同步调用
  范围内省略该接口的插件注册，由原调用方继续添加默认 Supplier、构建并执行回调。
- 音量默认 Supplier 返回宿主 `com.android.systemui.volume.dialog.VolumeDialogPlugin`，
  不是插件 APK 的同名类。原回调仍执行旧实例 destroy、新实例 init、安全音量逻辑。
- 关机默认 Provider 的注入片段来自 Dagger `get18` 中的 `globalActionsImplProvider`；
  该大方法的片段仅作补充定位，不冒称整方法读取完整。原菜单显示、锁屏、管理员限制、
  关机/重启操作全部仍由宿主处理，模块不主动调用这些动作。
- 三个 hook 全就绪、精确签名、当前版本/主屏门控；同步 ThreadLocal 支持嵌套、
  异常恢复与线程隔离，不在安装阶段调用 Provider 或创建默认实现。
- 不是热切换：开关变化和安全模式完整恢复需用户重启 SystemUI。插件专属音量功能
  不作用于原生面板，控制中心插件仍正常保留。

源码：`NativeSystemUiSettings.kt`、`NativeSystemUiHooks.kt`、
`NativeSystemUiTargets.kt`、`NativeSystemUiPolicy.kt`。
完整方法/字段证据 19 条：`docs/native-systemui-evidence.json`。

### 已启用卡片磁贴颜色

- 新 `control_center_card_tile_color`，独立背景与图标/文字颜色；不复用小磁贴开关。
- 当前 `QSCardItemView.updateBackground(ZZ)` 已拆分普通 Drawable 与材质分支，
  不照搬上游旧的无参数方法或整背景替换。
- 普通启用背景仅复制独立 `GradientDrawable`；原生继续构建 LayerDrawable、
  设置形状与圆角、驱动启用层 alpha 动画。不改共享资源或强关材质。
- 图标在原生 `setIcon` 期间临时覆盖两项每实例颜色输入，finally 仅恢复仍归模块
  所有的值；不替换 drawable、动画或回调。文字保存原 ColorStateList，恢复时
  验证所有权；不覆盖流量警告、策略限制、临时状态或不可用状态。
- 处理同状态早返回：恢复后重新施加仍归模块所有的背景/文字，不要求系统一定
  重新创建背景。弱表值只有颜色，没有 View/Drawable 回调反向保活。
- 7 个 hook 全就绪；补查完整 dispatch xref，额外去优化 QSRecord 的 Handler、
  绑定、详情刷新与两类 ViewHolder 的材质/回收调用，不能只去优化内部方法。
- 精确版本、主线程、主屏、默认主题及原生普通背景门控。材质/玻璃、自定义主题、
  有状态或显式 filter 背景保持原生。图标变色/关闭仍可能等到真正原生着色；
  安全模式不承诺立即恢复已有视图。

源码：`CardTileColorSettings.kt`、`CardTileColorHooks.kt`、`CardTileColorTargets.kt`。
插件完整证据 32 条、共享 State 字段主包证据 4 条：
`docs/card-tile-color-evidence.json`、`docs/card-tile-color-systemui-evidence.json`。
不能把这些普通背景分支称为完整上游材质/颜色视觉一致。

### 面板头部渐变模糊

- 新 `shade_header_gradient_blur`，默认关闭。当前 selector 原本同时检查
  `ShadeBlurController.miBlurOpen` 和 `MiBlurCompat.gradientBlurSupported`。
- 保留原生结果和宿主 gradientBlurSupported 能力门控，只允许支持的主屏在
  整体材质选择之外使用原生头部渐变；不无条件返回 true，不修改系统属性/开关。
- 9 处完整 dispatch 引用来自 7 个不同方法：裁剪比例、渐变参数、前景 alpha、
  flow 消费者、绘制、失效和 Paint alpha。全部解析及去优化成功才安装 selector。
- 原生 clipRatio/渐变高度与半径、绘制和动画仍由同一控制器管理，无额外覆盖层。
  功能可能增加耗电；设置变化后的 Flow/缓存一致性需要重建，明示重启生效。
- 安装时只解析静态字段，不读取并初始化 MiBlurCompat。运行时先验证版本/主屏。
  已核对其 clinit 的能力来源；不在未知宿主上先读取静态能力再拒绝版本。

源码：`ShadeHeaderBlurHooks.kt`、`ShadeHeaderBlurTargets.kt`。
11 条完整方法/字段证据和 dispatch xref：`docs/shade-header-gradient-evidence.json`。
此项不替代另一项“强制背景模糊”。

## 已核对但尚未完成的问题

| 项目 | 当前结论与后续工作 |
| --- | --- |
| 卡片列表 | getter 有创建、排除列表和 detail 三处调用。QSController 构造期间已生成排除列表，不能创建后才改 getter。detail 另行创建 bt/coolingfan，直接添加同名卡片会覆盖 Map 中实例，需一并核查清理/回调，不能只套上游列表替换 |
| VoWiFi | 当前有独立 create/clear/distribute 与缩小卡片链；不能照搬上游吞掉 createVoWifiTiles，避免破坏原生通话入口与生命周期 |
| 手势线三尺寸 | 上游 XML 三项明确 disabled，HandleLineCustom 只读半径与两种颜色；保持 pending 并记录，不新增无实现控件，也没有借此宣称用户目标已取消 |
| 快捷开关标签 | 它是 SwitchPreference，不是分类；所在父 PreferenceCategory 为隐藏状态。没有找到精确上游键引用，仍待定性，不能把零引用自动当已实现 |
| 手势日志迁移 | 当前 SystemUI 的 statusbar_gestures 字符串成员及 GestureRecorder 名称查询均无匹配；这不是完整文件写入路径证明，未增加旧类名 hook，也未移动任何文件 |
| 媒体/灵动岛 | 完整莫奈/反色/颜色过渡/环境光、灵动岛布局/文字/进度仍未完成，普通媒体实现不代替它们 |
| 其他磁贴 | GMS/传送门/雪豹/阳光/手电筒亮度及完整材质仍待实际工厂、权限、设备能力与销毁链适配 |
| 天气/焦点/导航/锁屏 | 天气 provider 与生命周期、焦点混色、导航/框架几何、锁屏高级材质等仍需实现，不归因于 WMShell 工件缺失 |
| WMShell | 当前 MT 列表没有实现库工作区，本次未获取新工件；四项仍未实现，没有使用资源 APK 或旧名称凑完成数 |
| 已迁移部分 | 5G 详情/长按/图标、完整音量长按缩放/按钮行为、百分比浮层及其材质等历史边界继续有效 |

## 清单与核查边界

- 对 **12 条**已检查 XML 的 category/fragment-only 导航记录标记
  `structural_navigation_only`，不是新增功能、更不是下级功能迁完。包含尚未实现的
  卡片列表和灵动岛入口；子项继续独立计数。生成器对 tag/fragment 做断言，
  未泛化所有 `Preference`：应用选择器和灵动岛测试动作没有被错误清零。
- 引用索引新增父节点 enabled/visible 信息，修正隐藏父分类下开关被当作“无可见性
  限制”的信息缺失；例如快捷开关标签。索引依旧不是完整逻辑验收。
- 总计 337：44 pending、126 待审计、12 结构导航、93 有界静态、19 修复待真机、
  7 部分静态、30 逻辑部分未编译、4 修复未编译、2 其他。
- pending 比上次少 12，其中仅 6 是本次实际实现的配置记录，另 6 是导航分类；
  待审计少 6 也全是导航分类。不能说新增了 18 个功能。
- SystemUI 功能目录 49 → 53；新增 10 个回归测试定义，**未执行**。
  66 个完整宿主成员已读取并保存哈希，签名 fixtures 已更新。
- 旧累计构建输入仍不匹配；历史 606 项测试不覆盖这些源码。
  新核查快照见 `docs/native-card-source-review-2026-10-11.json`。
