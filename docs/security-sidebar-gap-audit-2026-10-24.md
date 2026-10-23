# 安全中心侧边栏缺口审计（2026-10-24）

## 结论

上游 `SecurityCenter.java` 共有 56 个 `initHook` 调用；扣除重复安装的
`SidebarLineCustom`、共用同一开关的 `IsSbnBelongToActiveBubbleApp` /
`GetBubbleAppString`，再按当前实现拆分前置补光与视频增强子项后，安全中心共有
57 个可独立表达的功能位。本项目当前登记 54 个，未登记的是：

- `security_center_newbox_custom_enable`（上游 `NewBoxBlur`）
- `se_enable`（上游 `BlurSecurity`）
- `security_center_hide_sidebar`（上游 `AddSideBarExpandReceiver`）

这三项不是静默遗漏。当前宿主的类、方法或资源链不足以安全复用上游实现，
因此没有创建工作为空转的开关。

## NewBoxBlur

上游依赖：

- `com.miui.gamebooster.windowmanager.newbox.j`
- `com.miui.gamebooster.windowmanager.newbox.i`
- `com.miui.gamebooster.windowmanager.j`
- `com.miui.gamebooster.videobox.adapter.i`
- `TurboLayout#getDockLayout()`
- `NewToolBoxTopView#onAttachedToWindow()`

只读核对当前安全中心后确认：

- `windowmanager.newbox.j` 仍是可加载类，但已不是上游预期的 Dock 布局基类，
  实际形态包含 `Runnable` 语义，不能按 `ViewGroup` 构造函数安装。
- `windowmanager.j` 与 `videobox.adapter.i` 已不存在。
- `TurboLayout#getDockLayout(): p0`、`getTargetBox()` 与
  `NewToolBoxTopView#onAttachedToWindow()` 仍存在，但只保留部分 UI 入口，
  上游的模糊背景、圆角、着色和容器替换链没有完整落点。

结论：无法在不猜测类角色的前提下安全迁移，暂不登记功能。

## BlurSecurity

上游 `BlurSecurity` 依赖 DexKit 字符串 `game_turbo_box_mode_change` 定位动画方法，
并依赖 `addView error` 定位游戏工具箱管理方法。当前安全中心 DEX 中
`game_turbo_box_mode_change` 为零命中，无法形成唯一、稳定的方法落点。

该规则还依赖上游整套 `BlurUtils` / `MiBlurUtils`：

- 创建和识别系统模糊 Drawable；
- 设置模糊半径、混合色与圆角；
- 对工具箱内容树反色；
- 按资源 ID 隐藏顶部静态图。

当前项目没有等价工具链。若只 Hook 仍存在的
`TurboLayout#getTargetBox()` 或 `NewToolBoxTopView#onAttachedToWindow()`，
只能做到部分视图操作，达不到上游的整项效果，且容易破坏游戏工具箱透明区域、
文字动画和触摸层级。结论：暂不按半套逻辑迁移。

## AddSideBarExpandReceiver

当前安全中心仍存在
`com.android.systemui.navigationbar.gestural.RegionSamplingHelper`，但只读核对发现：

- 当前启动入口是 `j(Rect): void`，不是上游按 `start(Rect)` 形状匹配的方法；
- 隐藏侧边栏依赖广播注册、`sp_video_box` 持久化写入和合成
  `MotionEvent` 模拟拖动；
- 上游还需按版本改写
  `sidebar_height_default` / `sidebar_height_vertical` 的 density；
- 当前资源中 `sidebar_height_default=0x7f071c0a`、
  `sidebar_height_vertical=0x7f071c0b` 与
  `game_turbo_top_line_bg=0x7f0b0529` 存在，
  `video_box_top_line_bg` 不存在。

模拟触摸与持久化写入会直接改变宿主交互状态，不能仅凭静态命中就移植。
在找到当前宿主的真实展开回调、生命周期注销点和安全触发方式之前，
本项保持未迁移。

## 验证边界

本审计只使用 MT MCP 只读分析安装包，没有对设备执行安装、输入、广播、
设置写入、重启或触摸模拟。上述三项只有完成当前宿主适配和独立测试后才应加入
`SECURITY_COMPONENT_FEATURES`。
