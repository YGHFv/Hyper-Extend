# 锁屏壁纸亮灭屏动画迁移（2026-10-10）

## 范围与入口

新增默认关闭 `lockscreen_wallpaper_transition`，系统界面 → 锁屏 → 锁屏显示。
亮屏 `wake`、息屏 `sleep` 独立设置 `100-1600 ms`，步进 1 ms，默认分别 300 / 200 ms。
只影响主屏、默认主题、普通锁屏的壁纸明暗过渡，不改变时钟、通知、认证、导航和解锁动画。

参考 HyperCeiler `5e4686069dd7ab1f3697e256d5fc7d68fb73e317`：
`lockscreen/LinkageAnimCustomer.kt` 及 `system_ui_lock_screen.xml`。
上游将 XML 值除以 100 显示为速率，但 Hook 实际传给 `setDuration(Long)`；本地直接显示 ms。
息屏缺省采用 XML 的 200 ms，不照搬上游 Hook 的 300 ms fallback。
亮屏将当前宿主的 spring 曲线替换为上游使用的 style 20 减速插值；不是只给 spring 修改 duration。

## 新 ROM 调用链

SystemUI `17.03.260226.r / 202602260`，MT 工作区 `wot5ikcq`。

- 旧 `ClockBaseAnimation.doAnimationToAod(ZZZ)` 和 `mWallpaperHideEase` 已不在当前接线中。
  现在五参 `doAnimationToAod(ZZZZ, AnimationTracker)` 管时钟，不能跳过它或手动补调二参版本。
- 当前 `KeyguardPanelViewController.linkageViewAnim$default` 调用
  `doWallpaperBlackAnim(ZFIZ, AnimationTracker)`，show=true 表示亮屏，第四个参数是 needAnim。
- 该方法根据全屏 AOD / 普通 AOD / 景深选取 ease。普通亮屏读 `localAodShowEaseStyle`，
  普通息屏读 `localAodHideEaseStyle`，景深息屏有单独 `localAodCutoutHideEaseStyle`。
- 动画配置通过 `KeyguardClockContainer$$ExternalSyntheticOutline0.m(EaseStyle)` 生成，
  宿主随后继续添加表面更新、状态跟踪、完成/取消的原生监听，最后调用 Folme `to(...)`。
- `fullAodBrightnessListener.onBrightnessChanged` 也调用同一方法，但 needAnim=false；
  此路径不应用本功能。没有把所有同线程 `AnimConfig` 一起改掉。

## 有界实现与恢复

1. 两个 Hook 整体 ready 门控：wallpaper 方法建立线程局部范围，配置工厂只匹配本次选中的
   原生 ease 对象身份且只消费一次。无范围、其他 ease、嵌套排除调用和其他线程均保持原生。
2. 新建属于本次动画的 `InterpolateEaseStyle(20, floatArrayOf(1f))`，设置时长后交给原生
   配置工厂。不修改宿主共享 ease、duration、final 字段、监听集合或动画全局缓存。
3. Folme 的 `updateInterpolatorAnim` 在后续帧中读取 ease.duration；因此不能暂时修改共享
   duration 后立即恢复。本实现每次都新建对象，旧动画仍保持自己的时长，不与新动画竞争。
4. 原生 show/black/darkenType/needAnim/tracker 参数原样传递；不创建或接管 SurfaceControl
   Transaction，不获取/释放额外唤醒锁，不替换 onBegin/onUpdate/onCancel/onComplete。
   宿主原有取消、结束、trace 和唤醒锁释放链保持。上限 1600 ms 低于方法中 2000 ms 的锁超时，
   但掉帧/调度/系统动画倍率下的实际时序仍须真机验证，不据此保证实时完成。
5. 只允许精确版本、主线程、功能已开、原生 needAnim=true、系统动画未关闭、已附着主屏
   root、默认主题及 keyguardShowing=true。排除全屏 AOD、景深、视频/景深视频、外屏、
   遮挡、从已解锁状态息屏、密码面板、正在解锁、超级省电。
   不强开 AOD、不强制本来没有的动画，不改变全屏 AOD 的亮度响应。
6. HookRuntime 安全模式绕过后续覆盖；线程范围 finally 恢复，不保留 View/Context/controller、
   无新线程/监听器/任务。已经交给宿主的有限时长动画仍由宿主完成或取消，不承诺安全模式
   触发时瞬间把进行中动画变回原时长，避免主动干扰唤醒锁和表面清理。
7. 配置工厂、wallpaper 入口和调用方去优化；所有方法、字段类型及构造签名安装前检查。
   Hook 不全、去优化失败时 ready=false，不做半套修改。

## 证据与回归

- `docs/lockscreen-wallpaper-systemui-evidence.json`：20 方法 + 16 字段，36 个实时核验目标。
  全量刷新成功，输出 `.workbuddy/tmp/mt-systemui/linkage-evidence-refresh.json`；所有哈希相符。
- 正式靶点全文使用方法/字段 locator，无 hasMore/truncated。
  早期 panel 大类窗口、wallpaper 泛搜有分页，仅用于定位，不作为完整成员枚举证据。
- 已查明配置工厂的原生 setEase 保存引用，style 20 对应 DecelerateInterpolator，
  onCancel/onComplete 均有原生唤醒锁 release 与 trace 结束；没有照搬旧匿名内部类构造。
- 新增 16 项测试：默认值/范围/ms 语义/目录，18 项门控输入，身份匹配/单次消费、
  嵌套范围与异常恢复、线程隔离/迟到调用、所有使用的方法/字段/构造 fixture。
- 完整单元测试 **486 项，0 失败/错误/跳过**；R8 Release 构建和 lintVital 成功。
  `git diff --check` 通过。未重跑全量 lint，历史 22 个错误仍未解决。

## 构建、清单与部署

`docs/lockscreen-wallpaper-build.json` 记录本次构建；日志 `build/lockscreen-wallpaper-build.log`。
本地 APK `0.1.3 / 4`，2696133 bytes，SHA-256：
`742c37efe222258efc8c25fa59200e9d70150c20251745f9cb6172249b70891b`。

清单仍 337 条配置/导航/依赖记录：pending **83 → 80**，有界静态 **93 → 96**，
132 待审计、19 修复待真机、8 部分静态、2 其他不变。
这是一个功能的三条记录，不是迁完三个功能；不是完整 AOD/锁屏动画体系迁移。

保留上轮手势提示线全部未提交修改，本批同样未提交或推送。
未调用 adb 安装、重启、热重载、改开关/作用域/SIM/网络，未启动子代理。
设备仍为最后授权的 19:29 通知图标包，本地构建不代表设备已运行本功能。

## 统一真机验收留后

待用户安排检查：设置保存/范围/恢复、亮灭屏各时长、快速反复亮灭屏、动画取消/切壁纸、
通知有无、密码面板/指纹直解、锁屏相机遮挡、全屏 AOD/景深/视频/外屏排除、系统关闭动画、
关闭功能/安全模式、长时长下无残余黑屏/唤醒锁/表面异常。MT 匹配与 JVM 测试不能证明这些
Android 运行时结果，当前也没有实际 Hook 加载或视觉成功证据。
