# 长按展开音量条（2026-10-10，有界部分迁移，未部署）

新增默认关闭 `volume_long_press_expand`，系统界面 → 控制中心 → 音量面板。
接续手势提示线、锁屏壁纸、补全磁贴列表、降低亮度图标四批未提交成果，不覆盖旧修改。

## 上游与本地边界

参考 HyperCeiler `5e4686069dd7ab1f3697e256d5fc7d68fb73e317` 的
`library/libhook/src/main/java/com/sevtinge/hyperceiler/libhook/rules/systemui/plugin/systemui/StartCollpasedColumnPress.kt`，
对应 XML key `prefs_key_system_ui_volume_collpased_column_press`。

- 仅 SystemUI `202602260` / 插件 `183022200`，主屏普通折叠音量面板的活动滑条。
  单指静止按住 300ms 请求原生展开，允许系统 touch slop 内轻微抖动。
- 保留原生展开按钮、无障碍入口和滑条按压动画，不复制上游隐藏按钮、移除无障碍点击、
  共享 coroutine job、全局修改触摸时间戳或整面板 0.92 缩放。这些差异不是完整迁移。
- 正在显示/收起/展开动画、已展开、不可见/分离、外屏、控制中心嵌入音量、触摸探索、
  应用独立音量的自有展开面板、展开按钮起始触摸均不接管。
- 未触发前正常拖动、短按、取消仍走原生触摸链，不写音量、滑条 progress、系统开关或
  原生动画字段；不绕过固定屏幕、勿扰弹窗或原生展开限制。

## 宿主触摸与展开链

当前 `MiuiVolumeSeekBar.dispatchTouchEvent` 先运行拖动/按压动画，再交给 View dispatch。
`onTouchEvent` 内的 `RelativeSeekBarInjector.transformTouchEvent` 会 offsetLocation，
`VerticalSeekBar.onTouchEvent` 又会 setLocation；旧实现直接对 onTouchEvent 做前后比较
会混用已转换坐标。新观察点在 seek dispatch 入口，以 raw 坐标保存触点、pointer ID 与
历史样本，并在原方法改写事件前复制取消用快照。

`MiuiVolumeDialogMotion.processExpandTouch` 给当前活动条装 `h` listener，`h.a` 指向
motion，motion 的 `mVolumeView` 指回 dialog。新 adapter 校验双向绑定身份、活动滑条、
dialog callback 与 expandListener；只反射读取字段，不强写 `mIsExpandButton`。

原生手势按钮使用的 lambda 现为 `lambda$processExpandTouch$2`，不是上游旧 `$1`，且
要求 `mIsExpandButton=true` 并计算动画起点。本地不调用或改写它，使用现存的
`MiuiVolumeDialogView.expandListener.onClick(button)`：

1. `MiuiVolumeDialogView$1.onClick` → dialog `mCallback.onExpandClicked`。
2. `VolumePanelDialogController$1.onExpandClicked` → controller。
3. `VolumePanelViewController.onExpandClicked` 保留固定屏幕提示、勿扰弹窗与动画门控，
   原生 resetView/cancelKeyAnim 后进入 updateExpandedAnim。

计时器成功前重新检查版本、开关、安全模式、绑定、尺寸、configuration、progress 和
面板门控。调用前给滑条一次原生 ACTION_CANCEL，结束这条原生触摸流，再次检查绑定及
生命周期未作废，才请求展开。取消事件使用独立副本且 finally recycle，不发送合成 UP
或伪造短按。触发后消费该滑条剩余物理 MOVE/UP/CANCEL，避免已取消流重新调音。

复查了 `MiuiVolumeDialogMotion.isAnimating`：它仅查询 show/hide 与 expand/collapse
animator，不包含滑条自身的 press-up 动画。因此 CANCEL 的按压回弹不直接造成后续
动画门控恒为 true；真实动画竞争与视觉仍需设备验证。

## 取消、恢复与失败边界

- 每滑条独立 Session；超过 touch slop 的任意历史样本会永久取消本次长按，返回起点
  不重启计时。松手、CANCEL、非单指/非手指输入取消；dialog 外层额外观察多指，避免
  ViewGroup 拆分指针后滑条只能看到单指而漏判。
- dismiss/show/destroy、配置/展开变化取消待执行任务；detach 删除 session 和 attach
  listener。弱 key 的 Session 仅弱引用 seek/dialog/motion/callback/click，不强保留宿主。
- 每次原生触摸返回后检查 progress，计时到期再核对 progress/配置/绑定；不是全局监听
  每次音量变化，外部状态在两次观察之间改变又恢复不保证能检测，不作全覆盖承诺。
- 主线程 Handler，300ms 到期，超过 1000ms 的严重延迟任务拒绝执行。七个 Hook 全部
  ready 才建 session，精确方法/字段不匹配或 caller 去优化失败时保持原生。
- 插件发现复用现有 loader Hook，createPlugin/createPluginContext 去优化失败则不挂
  本项；不另起竞争 loader Hook。反射查询失败不展开，延时回调异常记录后取消且不冒泡。
- 关闭开关或安全模式在请求前阻止待执行展开；关闭开关不会撤销已交给系统的展开动画。
  功能正常运行时已 CANCEL 的流继续 drain。全局安全模式会绕过所有 Hook，因此若在
  CANCEL 后途中熔断，不能保证剩余物理流仍被消费；宿主重启才是完整清理边界。热重载
  也未作中途触摸验收，不承诺瞬间无缝回退，不为 drain 放宽全局安全模式。

## 验证与清单

- `docs/volume-long-press-plugin-evidence.json`：43 个精确靶点（31 方法、12 字段）。
- `docs/volume-long-press-systemui-evidence.json`：3 个共享插件发现方法。
- 全部只读实时核验，无分页/截断；签名 fixture 与证据集合一致，manifest 哈希与实时
  refresh 全部相符。调查正文只放在忽略的 `.workbuddy/tmp/mt-systemui`，不提交宿主代码。
- 新增 22 项本地测试，总计 545 项：版本/面板门控、300ms/超时、移动与历史样本、
  pointer/多指/非法坐标、取消后 drain 所有权、多滑条隔离、全部精确签名及默认关闭入口。
  这些 JVM 测试不运行 Android MotionEvent 分发、宿主动画或真实触摸链。
- 最终测试、APK 哈希/大小和构建时间见 `docs/volume-long-press-build.json`；日志
  `build/volume-long-press-build.log`。只构建 R8 Release，Debug 仅用于单元测试。
- 清单保持 337 条配置/导航/依赖：pending 78 → 77，partial 8 → 9，待审计 132、
  有界静态 98、修复待真机 19、其他 2 不变。不宣称整页音量迁完。
- 全量 lint 未重跑，历史 22 个错误仍未解决；lintVital 不是全量 lint 验收。

本批未安装、重启/热重载、改设备开关/作用域/SIM/网络，未启动子代理，未提交推送。
设备仍为最后授权的 19:29 通知图标修复包，没有本批 Hook 加载或视觉验收证据。

统一设备验收留后：短按与拖动原有音量行为、静止长按仅展开一次且无末尾跳音、移动后
返回/多指/快速重按、退出重入与配置切换、面板或活动音量变化、原生按钮与无障碍、
勿扰/固定屏幕、应用独立音量共存、原生按压回弹与展开动画、关闭/安全模式及资源释放。
须另获用户部署安排，不能沿用历史一次安装授权。
