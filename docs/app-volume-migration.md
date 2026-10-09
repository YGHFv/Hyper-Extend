# HyperVolumeANC 分应用音量入口迁移

## 2026-10-09：当前方案确认并保存

- 18:18:09 覆盖安装紧凑布局修复包成功，设备端 APK SHA-256 与构建包一致：
  `fd53d8f2d7c33e328bc39acee54d7cc70bcc29b8d8fef37998c589773eae4897`（0.1.3 / 4）。
  安装过程未主动重启设备或宿主，未调整设备音量。
- 用户安装后反馈“目前没有问题了”，确认保留 SystemUI 承载、直接使用官方展开/关闭动画的方案：
  紧凑背景、原生媒体总音量列、独立应用列、隐藏分应用面板底部快捷按钮，以及 MiSound 数据/写入桥。
- 此记录仅代表当前适配设备的用户使用确认，不等同于所有横竖屏、多页、重建及异常场景已完整验收。
  下方保留开发阶段记录；其中“未安装”“未验收”“已暂存”描述当时状态，不代表本节的最新状态。
- 按用户要求将此前暂存的参数复用尝试与最终工作区方案一起纳入版本历史。旧卡片/弹簧实现保留为历史源码，
  不进入当前安装链路；本次保存不再改动已确认的运行逻辑，仅更新验收文案与相应测试。
- 保存前重新验证：191 项 JVM 测试通过，Release 构建通过，暂存区差异检查通过。
  文案更新后的构建包未再次安装；设备仍运行上述已确认的修复包。

## 2026-10-09：紧凑布局、恢复总音量与隐藏快捷按钮（开发记录）

- 用户确认 16:34:13 安装的修复包展开动画已正常，截图显示仅应用列、左侧空白和底部快捷按钮。
  原因是展示列表替换掉所有原生列，但仍使用完整官方面板的固定背景宽高和 ringer 区域。
- 保留实际 `STREAM_MUSIC` 原生列及其原有监听器、静音图标、系统音量状态更新；其他系统列仍临时停放。
  仅主动操作总音量列走系统流写入；独立应用列仍通过 MiSound 调整比例，不混用监听器。
- 每页为一列总音量加若干应用列，总列数不超过官方可见列容量和五列。按原生列测量宽高及
  `VolumeColumnRes.getMarginRight` 计算内容区域，背景使用原生内边距，右侧/横屏居中/顶部仍调用官方定位方法。
  在官方 pre-draw 采样目标之前更新背景和原生阴影，不替换 `VolumeExpandCollapsedAnimator` 或关闭目标。
- 底部静音、勿扰、定时区保持已测量但不可见、不可点击（`INVISIBLE`），紧凑面板显式排除它的宽高。
  不能简单使用 `GONE`：官方逐帧代码会除以定时子视图宽高，冷启动未测量时会产生无效缩放。
  同时协调“隐藏折叠底部按钮”功能，避免另一组钩子将快捷区重新显示。
- 末页不足一整页时从前页回填应用，保持动画列对象/数组长度稳定且没有空槽；总音量列不参与分页。
  关闭/异常/重建恢复原始列顺序、布局参数和原生请求的快捷区可见性，不把紧凑尺寸带入下一次官方面板。
- 本轮仅修改工作区，原 11 文件暂存检查点不变。未安装本轮紧凑布局包，未重启或调整设备音量。
- 验证：191 项 JVM 测试通过（失败/错误/跳过均为 0），Release 构建通过，33 处官方代码目标只读复核通过。
  APK SHA-256：`fd53d8f2d7c33e328bc39acee54d7cc70bcc29b8d8fef37998c589773eae4897`。
  新布局仍需真机验证单/多应用、媒体总音量、底部无留白、横竖屏及关闭后再次打开官方面板；不把静态证据当成 UI 验收。

## 2026-10-09：安装后回归修复（源码阶段）

- 15:54:29 安装 SystemUI 承载方案后，用户反馈旧悬浮球出现、顶部入口无反应。
- 只读检查确认 SystemUI PID 31197 已安装 11 个入口/面板钩子，MiSound PID 4103 已安装 4 个数据桥钩子；
  系统广播历史记录到连续 QUERY，私有日志记录请求被拒绝，没有进入列安装阶段。
  `dumpsys audio` 同时确认 `STREAM_MUSIC` 为 `Muted: true`、`streamVolume:0`。
- 修复数据桥错误的全局静音门禁：总音量为 0 不影响查询或调整应用比例，不调用系统流写入/解除静音。
  原生服务在静音检查之前已经初始化控制器，故不需要伪造服务 flags 或 Hook `AudioManager.isStreamMute`。
- 恢复仅针对 MiSound `C(View,LayoutParams)` 中原生球字段 `m` 的挂窗拦截；适配版本、钩子与调用方去优化完整时，
  首次打开模块面板之前就隐藏旧球。保留未知版本和挂钩失败回退，不拦截其他窗口或系统音量对话框。
- QUERY 拒绝携带原因；明确失败不再盲目重试六次。入口超时/服务失败/无应用/禁用/官方展开拒绝显示提示，
  日志区分查询成功、列安装和动画完成，避免所有失败都表现为“点了没反应”。
- 原来 11 文件的暂存检查点不变，本轮继续修改工作区。未改变设备音量、开关或宿主进程。
- 修复版验证：184 项 JVM 测试通过（失败/错误/跳过均为 0），Release 构建通过，`git diff --check` 通过。
  APK SHA-256：`3d31b8a32198de3aad20132c549c7cdc07850af1630b516e0b172afea41fe728`。
  **此修复版尚未安装**；测试不执行宿主反射与官方动画，旧球隐藏、静音时打开及展开/关闭观感仍需真机验收。

## 2026-10-09：改为 SystemUI 承载，直接调用官方动画（实现记录）

此前参数复用方案的 11 个文件已 `git add` 暂存，没有提交；本次实现保留为未暂存修改。
以下旧方案与部署记录是历史记录，不代表当前执行路径。

- 顶部入口查询 MiSound 后，不再关闭 SystemUI 并打开第二个窗口。先移除入口占用的额外行并完成布局，
  再调用现有 `calculateFromViewValues(true)` 与 `VolumePanelViewController.onExpandClicked()`。
- 在 `updateExpandedH(ZZZ)` 完成官方重新挂载之后、官方 pre-draw 采样目标之前，换入独立的原生 `VolumeColumn`。
  原来的背景、阴影、ringer、锚点、尺寸及 `VolumeExpandCollapsedAnimator` 实例不替换；
  `calculateToViewValues/expand/collapse` 与逐列错峰仍由宿主执行，不创建模块自己的展开/收起 Animator。
- 系统音量列保留在控制器的原始 `mColumns`，其他列临时停放在独立容器；顶部紧凑布局修复保留媒体列显示。
  该容器也接收原生动态流的增删。
  应用列只交给展示层的 `setVolumeColumns`，使用私有负数标识和独立监听器，绝不注册成音频流或调用系统流监听器。
- 保留官方 show 前采样的离屏关闭目标，不用当前屏内锚点覆盖它。返回、外部点击、超时仍经官方控制器关闭。
  完成/脱窗/重建时恢复原始列与父容器，移除可能残留的一帧 Choreographer 回调，释放应用列和私有 ratio flow。
- MiSound 桥接分为 QUERY/WRITE/END，动态接收器仍要求发送方持有 `STATUS_BAR_SERVICE`。
  数据服务启动跳过 `B/t` 展示链路；成功接管后移除已有原生球，在会话期间抑制再次弹球。
  已由顶部回归修复改为首次接管前隐藏旧球；不再安装 `MiSoundCardHooks`，不修改其窗口定位或播放整卡动画。
- 写入沿用 MiSound 260903 的 per-player setter (`a.i`) 和 `a.w(package, ratio)` 持久化，不调用 `setStreamVolume`。
  每次写入重查当前音源/UID/包身份、锁屏/息屏/禁用，校验会话租约与单调序号。
  前端最多一笔在途写入、按 UID 合并待写值，关闭后有界排空最后一笔再 END；迟到结果不能重新打开面板。
- 总列数不超过官方可见列槽和五列；多页点击应用图标或顶部页码切换，固定列对象/几何，避免改变官方缓存的动画数组长度。
  应用变化不在拖动中重绑：关闭当前面板，再打开取新列表。仅面板展示时每两秒刷新，无后台常驻轮询。
- 当前限制：最多 32 个活跃应用；共享 UID、隔离 UID、其他 Android 用户/双开资料不提供写入，避免包名级持久化串用户。
  顶部紧凑布局修复恢复媒体总音量列并隐藏静音/勿扰快捷区。应用图标不套用系统流图标着色。
- 只启用已审计的 MiSound 260903 / 插件 183022200；关键钩子或去优化失败不启用入口。
  旧卡片/弹簧源码暂保留用于与暂存区对比，但不再从当前安装链路进入。
- 本轮不安装、不重启设备。**直接复用动画链路不等于已经确认真机观感一致**，需受控加载后录屏验证。

### 新方案验收重点

1. 对比官方展开与分应用展开的背景上下沿、阴影、逐列错峰，以及返回/超时/点外部的关闭目标。
2. 连续开关、展开中关闭、旋转/字体/材质变化、重新挂载，确认下一次官方面板没有丢列或残留位移。
3. 同时播放两个应用，调节和静音各应用、跨页返回检查读数；确认系统媒体总音量及其他应用不变。
4. 暂停/启动其他播放应用、锁屏、服务退出、写入超时、不同用户/共享 UID：不写错对象，不留下可用的过期滑块。
5. MiSound 原生面板已展开、模块只勾选单作用域、关键钩子失败时，官方面板继续可用。
6. 与隐藏折叠底部按钮同时开启，检查入口消失后没有一帧闪位；TalkBack 读出应用名和分页操作。

新增只读证据：`app-volume-systemui-host-evidence.json` 和 `app-volume-backend-host-evidence.json`。
本轮验证：179 项 JVM 测试通过，Release 构建通过；新增 23 项 SystemUI / 9 项 MiSound 代码证据通过只读复核。
没有安装、重启或运行设备 UI 验收。JVM 测试覆盖策略/租约，不执行宿主反射与实际动画。

## 历史：2026-10-09 参数复用方案（已暂存）

- 新增 `app-volume-panel-host-evidence.json`，通过 MT 只读核对插件 183022200 的 22 个代码目标与 13 个资源映射。
- 官方默认竖屏展开背景 top 为 `158dp`、右边距 `20dp`、内边距 `16dp`、圆角 `36dp`；滑块实际走
  `VolumeColumnRes` 的 `o3_miui_volume_expend_height=172dp` 和宽度 `64dp` / 独立通知流 `58dp`，
  **不是**旧 `miui_volume_column_height_expanded=170dp`。运行时调用官方方法，不把这些默认值当所有设备的常量。
- SystemUI 在每次受权限保护的打开请求中携带 `getMarginTop/getMarginRight/getInsetRight` 的结果、原生滑块尺寸、
  圆角、实际折叠内容坐标及六组 layered spring 参数。只传基本类型，不在 MiSound 加载插件代码或复制控制器。
- MiSound 改为 `TOP | RIGHT`，将屏幕坐标换算到本窗口原点，避免状态栏/右侧安全区重复偏移；小窗口等比收缩并约束边界。
  横屏按官方完整背景高度计算的锚点定位，不按分应用卡片高度重新居中。分页数量仍由原生适配器决定。
- 移除原有 220/200ms 自定义侧滑及 MiSound 窗口动画；布局完成后的 pre-draw 才开始从折叠位置/尺寸展开，
  使用官方 SIZE/POSITION/COLOR 弹簧参数实现独立属性轨道。关闭由动画完成回调触发原生 `p()`，不再固定延迟 200ms。
  取消、detach、关闭系统动画及旧代回调均有清理路径。
- 这是跨宿主的**参数与几何复用**，不是直接运行 `VolumeExpandCollapsedAnimator`：该类依赖 SystemUI 音量列、
  ringer、shadow、blur controller。MiSound 仍按整卡属性过渡，不宣称逐列错峰/材质动画与官方逐帧一致。
- 缺少快照或旋转/密度/显示器不匹配时，尝试只读插件资源与基础弹簧参数；这条降级不含官方设备形态判断和 layered 因子。
  资源也不可用时保留有界顶部布局、不播放变形动画，并记录降级。局部模糊逻辑与音量写入保持不变。
- 本轮不自动安装、不重启宿主、不做设备 UI 操作。仍需同屏截图和录屏验收定位、过渡、横竖屏及快速开关。
- 本地验证：164 项 JVM 测试通过（新增 11 项定位/尺寸/弹簧测试），Release 构建通过；不等同于真机动画或触摸验收。

## 0.1.3 后续部署

2026-10-09 13:25:22 按用户请求覆盖安装成功，修复提交 `42d49da` 已推送。
安装包哈希核对一致。立即核验时 PID 未变化，稍后 MiSound/SystemUI 出现 SIGNALED/15 退出并更换 PID；原因未确定，代理未发出重启/热重载命令。模糊视觉验收仍待进行。
以下“本轮未安装”特指此前源码修复阶段。

## 0.1.3 修复：面板局部模糊

用户确认卡片布局已正确，但缺少音量条式模糊。复查发现旧代码仅接受
`setBlurRadius(int)` / `setCornerRadius(float)`，参考代码同时兼容 int/float
及四角圆角重载；旧代码任何一步失败就换成半透明纯色卡片。
本次补齐重载兼容、`setWillNotDraw(false)`、每个 ViewRoot 的 Drawable 缓存、
跨窗口模糊开关监听及 detach 清理；配置/布局改变时更新圆角和底色，不模糊整屏。
MiSound 的面板窗口原生 flags `0x1048106` 已包含硬件加速，不盲目修改窗口模式。
系统主动关闭模糊或接口确实不可用时仍保留明确记录的半透明降级，**不算模糊验收成功**。
本轮未安装或重启，视觉效果需在后续受控加载后验收，详见 `migration-repair-0.1.3.md`。

## 历史状态：已接入实验性实现，未完成验收

- 上游：<https://github.com/zhhhyyyyyy/HyperVolumeANC>，`636ce289457ed10f149c033e3b723cdfc62838aa`，Apache-2.0。
- 迁移顶部入口、隐藏旧悬浮球、右侧分应用音量卡片及其模糊与动画，不迁移 ANC、蓝牙、耳机广播和上游设置界面。
- 设置键 `volume_app_entry`，默认关闭，入口在“系统界面 → 通知与控制中心”。
- LSPosed 需同时勾选 `com.android.systemui` 和新增的 `com.miui.misound`；配置后重启两个宿主。
- **不能登记为完整迁移完成**：音量插件 `miui.systemui.plugin` 183022200 已完成静态代码/布局核验，但尚无真机运行验收。

## 已核对的宿主证据

2026-10-09 最新 MT 清单为 19 项，已新增音量/控制中心插件、AOD 和 WMShell 资源 APK；未读取被策略禁止的系统分区。

1. SystemUI `17.03.260226.r`：`PluginInstance$PluginFactory.createClassLoader()` 从 `pluginAppInfo.packageName`
   选择缓存并写入 `PluginInstanceInjector.sClassLoaders`。仅监视该入口和已有缓存，不 Hook 全局 `ClassLoader.loadClass`，不持续轮询。
2. SystemUI manifest 声明 `android.permission.STATUS_BAR_SERVICE`。MiSound 动态接收器要求发送方持有该权限，
   不使用上游无发送权限限制的导出接收器。请求限定 MiSound 包，结果确认面板已挂窗才返回成功。
3. MiSound `16-4.0-4-20260903` / `260903` / minSdk 33 / targetSdk 36，MT workspace `yglu33cw`：
   `VolumeUIService.onStartCommand(Intent,int,int)` 使用 `streamType=3` 与 `flags=0`，仍执行屏幕、设置、静音等宿主检查。
4. 服务进入控制器 `a.B()`，由 `u()` 刷新活跃音源、`z()` 展示原生球、`m()` 初始化分页；
   `a.n()` 给字段 `n:FloatingActionButton` 设置 `a$c` 点击监听器。
5. 原生点击仅接受状态 `2000`，移除球窗口、复位状态并调用 `y()`；后者设置 `5000` 并添加 `MediaVolumePageView`。
   打开流程不强写状态、不伪造音源列表。成功检查同时要求状态 5000 和面板实际 attached。
   卡片关闭动画沿用已核验 `g()` 的关闭状态 301，动画结束后调用原生 `p()` 清理；代次检查避免旧回调关闭新面板。
6. 播放筛选参考宿主 `f.a(Context)`，要求启动状态、媒体 usage/music stream、应用 UID，排除动态壁纸。
   模块额外要求包名可解析，并按 user/appId 拆分 UID，避免工作资料系统 UID 被误认成应用。
7. 插件 `18.3.2.22.0` / `183022200` / workspace `gqbnd0ih`：模板实际文件为 `res/6Sy.xml`，
   主面板为 `res/um1.xml`，不是上游可读文件名。新证据清单覆盖 40 个代码/布局目标、10 组资源映射和 2 组文件映射。
8. `MiuiVolumeDialogMotion.updateStates/updateStateToExpand` 都经 `ViewStateGroup.apply` 写入 top margin；
   show 的 pre-draw 可能异步执行，不能只在 View.showH 的 ThreadLocal 内修改资源结果。
9. View 的 `lambda$dismissH$0` 无条件调用 Runnable.run；必须由 `VolumePanelViewController.dismissH(8)`
   清理超时和 mShowing 并提供回调，不再直接调用 View.dismissH(true,null)。
10. 本轮补读 MiSound `C/g/p/m`、适配器 `a$i`、holder `a$i$a`、分页回调 `a$e` 及四个布局。
    实际布局路径为 `res/P8.xml`、`res/nR.xml`、`res/qp.xml`、`res/bi.xml`；不是可读资源名路径。
11. 插件 `updateStateWithAdvancedMaterial()` 在 `mLastState/mLastExpanded` 没变化时直接返回。
    原实现只调用 `updateState()`，未初始化复制按钮的背景；现按上游切换 helper 局部状态 true/false 后刷新。

可重复核验：

```powershell
python tools/mt_systemui_probe.py
python tools/mt_systemui_probe.py --workspace yglu33cw --manifest docs/app-volume-host-evidence.json --output .workbuddy/tmp/mt-systemui/app-volume-evidence-refresh.json
python tools/mt_systemui_probe.py --workspace gqbnd0ih --manifest docs/systemui-plugin-host-evidence.json --output .workbuddy/tmp/mt-systemui/plugin-evidence-refresh.json
.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease --console=plain
```

## 实现范围与上游差异

- 原生 ringer 模板和 `RingerButtonHelper` 生成入口；仅普通音量对话框，展开二级菜单、锁屏、无媒体时隐藏。
- 入口占据顶部新增一行，同时补偿 top margin；顶部空间不足时不显示，不把音量滑块向下推。
- 每个 dialog 独立状态，音频 callback / pre-draw 随 attach 注册、detach 注销；播放启停会刷新。
- 只在已绑定的 dialog 上补偿边距，native state/layout 写入前恢复旧补偿、写入后重算；不 Hook 全局资源 margin。
- 顶部锚定布局才显示，翻折外屏和空间不足时跳过；入口高度来自 helper 实际按钮尺寸加宿主间距。
- 所有复制控件重新分配 ID，避免宿主查找静音/DND 控件时命中新入口；保留 helper 持有的直接引用。
- 移除复制按钮的静音开关无障碍代理，暴露单个“分应用音量”按钮；随资源/材质更新重新配置图标与尺寸。
- 收起期间冻结可见性并禁用点击；结束或 detach 恢复边距、取消请求；旧广播结果不能收起下一次显示的面板。
- 点击先启动原生前台服务，最多六次请求、总等待 2 秒；只有原生面板成功打开才经控制器收起音量条。
- 请求调度与有序广播结果使用独立 Handler；取消本地任务不移除必须执行的广播结果回调。
- 检查 AppVolumeBarHook 和 HyperVolumeANC 的既有入口 tag。不能据此保证与 Soundman 或其他模块无冲突，设置页要求二选一。
- **隐藏旧悬浮球**：仅拦截 MiSound 控制器 `C(View,LayoutParams)` 对字段 `m` 的挂窗，不 Hook 全局 WindowManager；保留原生点击与状态机。未知版本、挂钩不完整或面板结构不匹配时保留原生入口。
- **右侧卡片**：优先使用官方展开 top、右边距、圆角和内边距；不再垂直居中。背景局部模糊，取消窗口全屏模糊和压暗。模糊接口不可用时退回圆角半透明背景。
- **滑块与分页**：保留原生音源绑定及音量写入，尺寸与间距优先来自官方方法/资源，小窗口等比缩小。保留平板原生五列分页，避免直接裁成三列；清理复用 holder 的旧页面，卡片空白不再触发原生关闭监听。
- **动画**：从折叠面板位置和尺寸过渡，复用官方分层弹簧参数；清除原分页器缩放及窗口动画。保留原生返回、外部点击和超时入口，由原生清理函数卸窗。跨宿主边界与降级见本文件顶部说明。
- SystemUI 插件加载监视器由三个插件功能共用，避免 API 102 重复挂同一工厂方法；缓存和新加载均处理。
- 插件内方法、布局、helper 和资源映射已由 MT 重读；运行时样式、动画与触摸效果仍待设备验收。

## 待真机验收

1. 单开功能、双作用域重启；播放/暂停媒体，确认按钮出现/消失且其他控件绝对位置不变。
2. 横竖屏、大字体、控制中心、二级菜单、锁屏、息屏唤醒，确认无残留入口或边距累计。
3. MiSound 冷启动、已展开、宿主设置禁用、静音、没有活跃音源、没有 MiSound Hook 时测试点击；失败保留原音量条。
4. 同时播放两个应用，调节各自音量，检查没有修改系统总音量或错误应用；单独验证工作资料和双开。
5. 从普通应用发送同 action 的广播应被权限拒绝；通过入口打开后，返回/超时关闭正常。
6. 关闭功能并重启两个宿主，确认恢复原生入口、面板和音量行为。
7. 连续音量键、超时收起中播放启停、点击后立即锁屏/旋转/再显示，检查旧请求不影响新面板、滑块不跳位。
8. TalkBack 只读出“分应用音量”按钮，不读成静音开关；主题/高级材质切换后图标不回退为静音。
9. 与“隐藏折叠音量面板底部按钮”同时开启，确认入口、展开按钮和触摸区域均正常。
10. 音量键弹出时旧悬浮球不再出现；新按钮有与静音/勿扰一致的胶囊背景，不再只剩白色图标。
11. 展开后应用背景保持清晰，右侧卡片内局部模糊；卡片空白不误关闭，外部点击、返回和超时能关闭。
12. 三个以上应用跨页来回滑动、平板五列、旋转和快速重复开关，确认没有旧页重叠、滑块裁切或旧动画回调卸载新窗口。

验证记录与全量迁移进度见 `systemui-migration.md`。本文件不将本地测试视为设备验收。

## 本轮安装记录

2026-10-09 06:04（Asia/Shanghai）按用户要求覆盖安装本轮 Release，`adb install -r` 返回 Success。
包管理器确认版本 `0.1.2` / `3`，安装包 2,491,082 bytes，
SHA-256 `f5027f670b31dbe22489dd9a45ff4ce55e39a6df2b9677149d527d536a8b690e`。
本轮 136 项单元测试和 Release 构建通过；未重启宿主、未更改开关或 LSPosed 作用域，安装不等于功能验收。
