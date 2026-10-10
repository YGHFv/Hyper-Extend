# 锁屏底部快捷入口续迁（2026-10-10）

## 本批范围

- 默认关闭的 `lockscreen_hide_left_shortcut`：隐藏整个左侧入口。
  仅迁移上游 CustomizeBottomButton 的原生/隐藏部分，手电筒替换仍待迁移。
- 默认关闭的 `lockscreen_hide_right_shortcut`：隐藏整个右侧入口（默认相机）。
  用户换成其他快捷应用后仍隐藏该侧；不禁止相机应用、按键或其他相机启动路径。
- 两个独立直接开关位于“系统界面 → 锁屏”。不新增 AOD 作用域，不修改持久化快捷应用配置。

本批不含按钮模糊/颜色/圆角、手电筒替换，也不代表 AOD 息屏功能已迁移。

## 当前宿主与证据

MT 只读连接正常，本批精确读取并核验：

- SystemUI `wot5ikcq`，`com.android.systemui / 17.03.260226.r / 202602260`，5 个目标。
- AOD `7rdlzzo0`，`com.miui.aod / DEV-2446.3.0.1-09042206 / 22446301`，24 个目标（含 2 个布局）。
- 清单：`docs/lockscreen-shortcut-systemui-evidence.json`、
  `docs/lockscreen-shortcut-aod-evidence.json`。原始读取与刷新结果位于
  `.workbuddy/tmp/mt-systemui/shortcut-*.json`。
- 上游参考 revision `5e4686069dd7ab1f3697e256d5fc7d68fb73e317`。

当前 SystemUI 的 `KeyguardBottomAreaInjector.updateIcons` 已转交
`MiuiShortcutController`，实际视图来自 AOD 包内 `ShortcutPluginImpl`，运行于 SystemUI。
没有照搬上游旧版 `mLeftButton`、`mRightAffordanceViewLayout` 或 MoveRight/reset 拦截。
运行时只对上述精确版本组合生效；靶点、调用方去优化或任一 Hook 安装失败时，
新快捷入口适配器整体不启用，不以部分安装状态隐藏按钮。

## 实现与复查

- 复用 `SystemUiPluginHooks` 单一加载器发现入口，按包名分别安装原控制中心/音量适配器
  和快捷入口适配器；新增调用方检查失败不阻断原有音量/控制中心分支。
- 每次 `updateShortcutView` 先恢复旧绑定，再让原生完成新数据绑定；只处理参数与
  controller 对应侧字段及父子关系一致的 ImageView/FrameLayout，不一并改另一侧。
- 隐藏图标与其布局；逐绑定监听原生视图重新显示，避免动画恢复入口。
  保留原图标 Drawable、文字描述、监听器、主题、动画对象与布局参数。
- 用图标身份限制触摸命中；独立拦截指定侧点击（包含 TalkBack 路径）与 manager
  已选择的指定侧启动。未知/冲突选择保留原生；不拦截整个触摸事件或 `onTouchUp/reset`，
  保留原生取消、滑动标记和 trace 清理。
- 点击链包含 R8 合成监听器、转发方法及 lambda，均纳入精确证据与调用方去优化；
  异步启动协程只作为调用方去优化，不替换协程或原生启动后清理。
- 可见性状态按绑定隔离，分离时恢复、重新附着时重施，换绑定/释放时清理监听器。
  只恢复当前仍为本模块 GONE 值的属性；观察到原生其他可见性写入时更新基线。
  相同 GONE 写入本身不可区分，重新数据绑定由原生重新计算，不能声称任意厂商动画均已验收。
- 安全模式后 Hook 回退原生，已绑定视图在下一帧/分离时恢复；完整清理仍以正常重启为准。
- 复查前批充电信息的隐藏、分离、旧结果代际拒绝及安全模式退出路径，本批未确认新的
  充电缺陷，未改其代码；这不是运行时充电/锁屏验收。

主要代码：`LockscreenShortcutHooks.kt`、`LockscreenShortcutPolicy.kt`、
`LockscreenShortcutTargets.kt`、`SystemUiPluginHooks.kt`，目录为
`app/src/main/java/io/github/YGHFv/HyperExtend/hook/feature/systemui/`。

## 进度与验证

337 行配置/导航/依赖清单更新为：87 待迁移、132 已有部分实现待审计、
89 有界静态实现、18 修复待真机、8 部分静态实现、其余 3 条特定边界状态。
左侧模式行归为部分迁移，不把手电筒算完成；右侧隐藏行归为有界静态实现。
清单不是功能数量，尚未建立去重后的功能总数，不能报告完整迁移百分比。

JVM 测试覆盖精确版本、左右组合、未知选择回退、可见性所有权与重新绑定、
默认关闭与入口文案、Hook/查询/调用方签名，以及禁止接管原生手势清理的靶点约束。
399 项测试通过，0 失败/错误/跳过，R8 Release 构建成功；`git diff --check` 通过。
APK 内另核查左右开关键与新适配器字符串存在，不能据此推断 Hook 已加载。
测试、哈希和构建记录见 `docs/lockscreen-shortcut-build.json`。

本批未安装、重启、热重载、启用功能、改变作用域/SIM/网络，未提交或推送。
设备最后部署仍是 `docs/migration-install-2026-10-10.json` 的 15:43 包。
未运行全量 lint，不声称修复已有 22 个问题。

## 统一真机验收项（迁移结束后）

- 左/右/双侧隐藏，原生快捷应用替换、另一侧不受影响。
- 点击、长滑、取消、多指、TalkBack 点击；屏幕解锁和相机其他启动路径不被阻断。
- 锁屏反复开关、AOD 切换、主题/密度/旋转/折叠、分离重新附着、插件重建。
- 安全模式恢复、与锁屏充电/解锁提示隐藏组合、长时运行和宿主异常记录。
- 非目标版本及缺失靶点保持原生。未安装/未加载前不能把本批作为真机通过项。
