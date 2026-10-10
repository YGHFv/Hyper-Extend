# 手势提示线粗细与颜色迁移（2026-10-10）

## 本批范围

- 从干净工作区开始，HEAD `ef84f64`，上一源码提交 `77e779c`；本批未提交或推送。
- 对照 HyperCeiler `5e4686069dd7ab1f3697e256d5fc7d68fb73e317` 的
  `navigation/HandleLineCustom.java` 与 `system_ui_navigation.xml`。
- 新增默认关闭 `navigation_handle_custom`，位于系统界面 → 其他 → 导航与方向。
  半径 `0-5 dp`、步进 `0.05 dp`、默认 `1.85 dp`，厚度是半径的两倍；
  浅色背景与深色背景各自支持颜色及不透明度，留空跟随系统。
- 上游直接替换半径资源与颜色资源；此处只处理精确宿主 `NavigationHandle`，
  不全局替换资源、不改变系统手势设置、不以隐藏导航栏为启用条件。
  上游颜色缺省为 `#CC000000` / `#FFFFFFFF`，本地缺省保留当前系统颜色，明确不是原值导入。
- 上游高度、竖屏宽度、横屏宽度三项 XML `enabled=false`，当前实现也没有消费其键。
  继续留 pending，不添加空壳入口。当前宿主宽度为屏幕宽度乘 `0.32` / `0.21`，
  不使用旧宽度资源；这三项不属于本批完成范围。

## 宿主证据与实现

SystemUI `17.03.260226.r / 202602260`，MT 工作区 `wot5ikcq`。
正式证据 `docs/navigation-handle-systemui-evidence.json`，16 个方法 + 8 个字段实时核验。
完整刷新输出在 `.workbuddy/tmp/mt-systemui/navigation-evidence-refresh.json`。
逐靶点正文 `navigation-target-00.json` 至 `navigation-target-23.json` 留在忽略目录，不提交宿主代码。

1. 当前 `onDraw(Canvas)` 调用 `computePillGeometry(true)` 后直接绘制圆角矩形。
   计算会读取 `mRadius`，叠加 pulse 增量和原生按压的宽度、高度、半径缩放。
2. `HomeHandleVisibilityController.getHomeHandleInfo()` 读取 `getPillRect()`、
   `getPillRadius()`、`getHandleColor()`，并合成祖先透明度；只改绘制会使过渡元数据不一致。
   因此绘制和三个 getter 共四个 Hook，全部安装成功才启用。
3. 原生 `mDarkIntensity=0` 使用 `mLightColor`（深色背景下的亮色），`=1` 使用
   `mDarkColor`（浅色背景下的暗色）；继续使用 Android `ArgbEvaluator`，保留系统背景判定
   与中间色过渡，不按夜间模式简单二选一。未设置的一端仍读宿主原值。
4. 每次调用临时覆盖半径及 Paint 颜色，强制重算几何；finally 恢复半径、矩形、
   缓存半径、dirty 位和本模块拥有的 Paint 颜色。getter 原生返回矩形副本，恢复缓存不改返回值。
   不永久写 final 颜色字段、不缓存 View、不增加线程/监听器/定时器，也无分离后的异步任务。
5. 四个入口、几何计算和过渡信息调用方均先去优化；反射签名/字段类型、版本、主线程门控。
   缺目标/去优化失败/部分安装时不改视图；安全模式由 HookRuntime 保留原生调用。
   半径/颜色临时写入在准备失败和原方法异常时也恢复，不重放原方法。
6. `gesture_line.skip_draw` 优先，本功能关闭后不再覆盖；原有隐藏横条逻辑不修改。
   半径 0 只跳过绘制，过渡元数据保留原生，避免原生零半径/空矩形 fallback 得到伪造尺寸。
   **0 不等同于修改系统“显示手势提示线”设置，不保证系统接管的独立过渡表面也消失。**

源码：`NavigationHandleSettings.kt`、`NavigationHandleHooks.kt`、
`NavigationHandleTargets.kt`、`NavigationHandlePolicy.kt`。

## 共享 UI 有界改动

- `HyperSlider` 新增可选显示精度，本条使用两位小数，避免 `1.85` 显示为 `1.9`；
  所有旧滑块保留默认一位小数行为和存储值。
- `HyperColor` 新增默认 false 的 ARGB 支持；仅本条两个端点启用不透明度。
  现有莫奈取色仍只接受 `#RRGGBB`，颜色解析/序列化兼容性有测试。
- 沿用现有功能块和 Miuix 色彩选择器，不另建重复设置源或主题系统。
  颜色恢复跟随系统、保存、取消保留，实际布局/交互尚未设备验收。

## 验证与部署

- `:app:testDebugUnitTest :app:assembleRelease --console=plain` 成功。
- 470 项测试，0 失败/错误/跳过；新增 15 项覆盖配置、ARGB 兼容、精度、密度、门控、
  临时状态正常/异常/嵌套恢复、全部 Hook/调用方/字段的实时签名 fixture。
- Release R8 与 lintVital 成功。未重跑全量 lint，原 22 个已知错误仍未处理。
- `git diff --check` 通过；生成器输出仍为 337 条配置/导航/依赖记录。
- APK `0.1.3 / 4`，2696133 bytes，SHA-256
  `fcc108d93c4b2f2b8ba0dad05c2ffa8b389f5d101acb6b2f20560f57007ed1b8`。
  构建记录 `docs/navigation-handle-build.json`，日志 `build/navigation-handle-build.log`。
- 本批**未安装、未重启/热重载、未改开关/作用域/SIM/网络**，没有主动调用 adb 操作。
  设备最后授权部署仍是 19:29 的通知图标包，不是本次本地 APK。

清单 pending **87 → 83**，有界静态 **89 → 93**；132 待审计、19 修复待真机、8 部分静态、
2 个其他状态不变。减少的是同一功能的四条配置记录，不是完成四个独立功能。
只声明本批静态有界迁移，不宣称所有导航或 SystemUI 迁移完成。

## 统一真机验收留后

待用户安排：主列表/搜索/详情、`1.85 / 1.90` 精度、两端颜色/不透明度/取消/恢复；
深浅背景连续切换、按压小爱/识屏、返回桌面过渡、横竖屏/折叠/密度变化、系统隐藏/显示、
三键导航、半径 0、与隐藏横条组合、关闭功能/安全模式恢复。
反射修改 final 实例半径、ART 去优化是否覆盖真实执行链、RenderThread 录制与视觉同步仍是
Android 运行时风险；宿主匹配和 JVM 测试不能代替这些验收。
