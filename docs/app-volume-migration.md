# HyperVolumeANC 分应用音量入口迁移

## 0.1.3 后续部署

2026-10-09 13:25:22 按用户请求覆盖安装成功，修复提交 `42d49da` 已推送。
安装包哈希核对一致，MiSound/SystemUI 等宿主 PID 未变化；未重启或热重载，模糊视觉验收仍待进行。
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

## 状态：已接入实验性实现，未完成验收

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
   卡片关闭动画沿用已核验 `g()` 的关闭状态 301，200ms 后调用原生 `p()` 清理；代次检查避免旧回调关闭新面板。
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
- **右侧卡片**：距右侧 16dp、垂直居中、28dp 圆角、16dp 内边距；背景局部模糊，取消窗口全屏模糊和压暗。模糊接口不可用时退回圆角半透明背景。
- **滑块与分页**：保留原生音源绑定及音量写入，只调整滑块为短边 15.8% 宽、长边 22.1% 高、短边 3.8% 列间距；小窗口等比缩小。保留平板原生五列分页，避免直接裁成三列；清理复用 holder 的旧页面，卡片空白不再触发原生关闭监听。
- **动画**：整张卡片从右侧 220ms 滑入、200ms 滑出；清除原分页器缩放动画。保留原生返回、外部点击和超时入口，由原生清理函数卸窗。
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
