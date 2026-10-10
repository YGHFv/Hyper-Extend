# 锁屏左侧手电筒续迁（2026-10-10）

## 范围与边界

新增默认关闭的直接开关 `lockscreen_left_flashlight`，位于系统界面 → 锁屏。
沿用左右独立隐藏开关，左侧隐藏优先；不改已有键值，不修改系统保存的快捷应用。
适配 SystemUI `202602260` 与 AOD 快捷插件 `22446301` 的精确组合，仍由 SystemUI
的单一插件加载入口安装，不新增 AOD 作用域，不修改息屏功能。

- 仅主显示屏、已显示且未被应用遮挡/解锁离场的普通锁屏、原生左侧入口可见时替换。
  不强行显示被宿主隐藏或无 Drawable 的按钮，外屏/AOD/未知版本保持原生。
- 在原生圆形命中区按住至少 400ms 后松开切换。短按不切换；移动超出 touch slop、
  历史批次中的越界移动、多指、指针替换及取消均作废；取消后仍持有本次手势直到结束。
- TalkBack 原生点击链可直接切换；普通点击回调被吞掉，避免长按松开后再次点击而双切换。
- 手电筒控制复用 `MiuiStub.mSysUIProvider.mFlashlightController` 的宿主实例，调用
  `isAvailable/isEnabled/setFlashlight`；不使用 CameraManager 绕过原生低电量、强制关闭
  或相机占用策略，不自动解锁，不启动原快捷应用。切换前再次检查电源交互与锁屏状态。
- 独立高对比 Drawable 区分开关状态和不可用状态，尺寸不超过当前 ImageView。
  不使用模块私有资源 ID，不改按钮布局、点击监听器、缩放或宿主手势动画对象。
  **未迁移上游 1.21 倍按压动画及原版主题图标，不宣称完整视觉一致。**

## 调用链与恢复审计

沿用 `LockscreenShortcutHooks`，手电筒只增挂 `ShortcutMoveController.onTouchEvent`，
并去优化其 `ShortcutPluginImpl.onTouchEvent` 与 SystemUI 外层面板调用方。左右隐藏所需的点击 R8 合成转发、
方法 lambda、数据绑定及异步启动调用方继续保留精确签名校验与去优化。
新增靶点不完整时不启用手电筒替换；隐藏适配器仍可独立安装。基础适配器未完整安装时
所有视图修改均不启用，保留原生。

### 手势收尾

只在命中当前左图标且原生没有已进行中的触摸/动画拦截时捕获 DOWN。捕获后这条流不进入
原生插件 onTouchDown，因而不会置插件滑动/遮挡启动状态；其他流仍执行原方法。
不拦截 `onTouchUp/reset/finishAction/endMotion`，不临时改 manager 选择侧或快捷实体。

额外核验外层 `KeyguardPanelViewInjector.onTouchEvent(MotionEvent,int,float,float,boolean,boolean,boolean)`：
它将插件返回值同时写入 `mIsTouchShortcutIcon/mIsShortcutMoving`，返回 true 会跳过 finish。
因此模块在 UP/CANCEL 返回 false，让外层清除这两个标记并执行原有
`finishAction/endMotion` 路径；普通 MOVE 和已取消但尚未结束的流继续返回 true。
原生 `endMotion` 的滑动/位移前置条件与 velocity tracker 回收也已读取核验。
这仍是静态推导，外层面板、长按编辑和解锁手势交错必须统一真机检查。

### 视图与回调生命周期

- 宿主更新绑定前先撤销旧绑定，再执行原生更新，之后基于新数据替换。
- Drawable、ImageTintList、布局的 contentDescription 按对象身份记录所有权；观察到宿主
  新写入时更新基线，退出时不覆盖后续宿主写入。主题、密度、尺寸变化重新生成本地图标。
- 通过原生 addCallback/removeCallback 注册逐绑定代理；保持代理身份 equals/hashCode。
  回调只合并投递主线程刷新，不采信迟到事件参数，而是重新查询当前控制器状态。
- 图标不乐观切换；原生异步拒绝开启时仍显示查询结果，不伪造成功状态。
- 隐藏、AOD、解锁离场、分离时撤监听并恢复；一秒兜底检查防止息屏停止 pre-draw 后
  监听长期残留。重新附着重施，释放或重新绑定完整移除旧监听；旧按压代次不得复活。
- 安全模式下 Hook 回退原生，存量视图在下一帧/兜底检查/分离时恢复；不承诺完整热重载清理。
  没有为了恢复视图而主动关闭用户已经打开的手电筒。

本批同时重读前批 `LockscreenChargingHooks` 的 stop/dispose、分离重附着、请求代际拒绝与
隐藏后恢复路径，未确认新的充电缺陷，未改其代码；这不是充电功能真机验收。

## 证据与进度

- `docs/lockscreen-flashlight-aod-evidence.json`：36 个目标，含继承的隐藏链与两个布局。
- `docs/lockscreen-flashlight-systemui-evidence.json`：18 个目标，含插件发现、宿主控制器、
  Listener/Lazy 契约及外层收尾。合计 54 个实时只读核验目标，另有两个布局文件映射。
- 原始结果和哈希刷新：`.workbuddy/tmp/mt-systemui/flashlight-*.json`。
- JVM fixture：`app/src/test/resources/lockscreen-flashlight-host-methods.txt`；
  新增测试覆盖长按边界、取消、多指、越界、终止返回值、属性所有权、隐藏优先级与宿主签名。
- 最终 420 项单元测试通过（0 失败/错误/跳过），R8 Release 构建成功，
  `git diff --check` 通过；实测值：`docs/lockscreen-flashlight-build.json`。
  APK 内新旧快捷入口键与手电筒 Hook 标签存在，但不据此推断 Hook 已加载。

清单仍为 337 条配置/导航/依赖记录：87 pending、132 needs_audit_existing_partial、
89 implemented_static_verified、18 repaired_static_pending_device、
8 partially_implemented_static_verified、其余 3 条特定边界状态。
本批推进的是已经计入部分迁移的“左侧按钮模式”行，关联手电筒新开关；
**待迁移数不因新增入口而减少，不将设置行当作功能数。**
上游动画/视觉、无原生入口与外屏替换不在本批范围，左侧模式继续标为部分迁移。

## 部署与统一验收

本批未安装、重启、热重载、改开关/作用域/SIM/网络，未提交或推送。
设备最后部署仍是 `docs/migration-install-2026-10-10.json` 中的 15:43 包。
未运行全量 lint，既有 22 个问题不宣称解决。

迁移结束后统一验收：左右隐藏组合、400ms 边界、短按与 TalkBack、多指/移出移回/取消、
快速重复操作、相机占用/低电量/热限制、外部切换手电筒、锁屏/AOD/遮挡/解锁/重新附着、
壁纸明暗/主题/密度/折叠、原快捷应用更换、安全模式退出、原生编辑与解锁手势无残留。
特别核对长按松开后没有重复切换，外层面板没有残留“滑动中”，右侧与其他相机路径不受影响。
