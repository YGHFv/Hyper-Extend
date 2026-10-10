# 经典控制中心行数与折叠数量（2026-10-10，仅逻辑审查）

继续遵守用户“不实际编译验证，逐项逻辑验证后继续其他功能”的要求。本轮按顺序迁移
展开面板最大行数、折叠面板数量，再修复上一批数值显示的静态靶点声明；未运行编译、
单元测试、lint、打包或设备操作。保留所有旧未提交修改，不使用历史545项通过证明当前源码。

## 迁移范围

新增默认关闭 `classic_qs_layout`，系统界面 → 控制中心 → 经典控制中心，四个配置：

- 展开最大行数：竖屏2–5（默认3），横屏1–3（默认2）。
- 折叠磁贴数量：竖屏3–7（默认5），横屏4–8（默认6）。

取值范围和默认值来自 HyperCeiler `5e4686069dd7ab1f3697e256d5fc7d68fb73e317` 的
`system_ui_control_center.xml`、`rules/systemui/controlcenter/QSGrid.kt` / `QQSGrid.kt`。
无效设置取默认，越界整数限制范围；未知 orientation 保留原生，不一律当横屏。

只处理 SystemUI `202602260` 主屏、精确 MiuiPagedTileLayout/MiuiQuickQSPanel 类型，
不挂新控制中心插件。不会自动打开“解锁经典控制中心”、更改样式或写系统设置；用户
自行选择经典样式。既有正常/禁用磁贴状态、保存顺序、点击/长按、安全策略不修改。

这是**部分迁移**，并非完整自定义行列数：展开列数两个配置仍 pending，未添加空壳。
折叠“数量”是系统接收的最大请求，不是强制保证屏幕能显示这么多列。

## 1. 展开面板最大行数

上游直接在 MiuiTileLayout.updateResources 后写 mMaxAllowedRows，且在
layoutTileRecords **结束后**改 mColumns。当前宿主：

1. MiuiPagedTileLayout.onMeasure 先按可用高度/cellHeight 计算首屏行数，受 mMinRows、
   mMaxAllowedRows 和磁贴数量限制。
2. 行数变化或 mDistributeTiles 标记触发原生页数/磁贴重新分配，首屏行数传播到其他页。
3. MiuiTileLayout.onMeasure 使用 mColumns 计算 cellWidth；layoutTileRecords 使用同一
   列数布局并保留 RTL。单在布局后改列数会使测量、分页和排布不同步，因此不照搬。

本地只在 pager.onMeasure 的原生调用范围内，临时设置第一张 TilePage 的最大行数，
请求值不小于原生 minimum。原生完整测量/分配/监听/页码/无障碍链继续执行；模块不写
mRows、mColumns、cellWidth，不手动增删页或搬动 TileRecord。finally 恢复最大行数，
若原生期间已写入不同值则保留更晚写入。

原生缓存只比较高度等条件，不会观察模块设置变化。因此每 pager 弱键缓存上一次有效
上限；只在有效上限变化或首次请求不同于原生时设 mDistributeTiles=true，保证横竖屏/
改配置/关闭功能的下次测量重算。相同设置不每帧强制分配，也不把原生已有 true 清掉。
缓存只有 Int，不强保留 View；同 pager 嵌套以 ThreadLocal 阻止重复临时修改并 finally
恢复。空页、未知子类型、损坏的原生 min/max、其他显示/版本不接管。

“行数”是上限：系统最小行数、可用高度和现有磁贴数量优先，可能少于设置值，也可能
因原生 minimum 大于请求而不能再减少。已在本轮测量完成的布局不会因关闭模块自动重排，
关闭功能在下一次测量恢复；安全模式绕过 Hook 时可能保留旧分页缓存，完整恢复需重建/重启。

## 2. 折叠面板磁贴数量

行数逻辑走查后继续实现 `QQSGrid` 对应数量项，精确拦截
MiuiQuickQSPanel.setMaxTiles(int) 的正值参数；不直接写 mMaxTiles。

当前原生 setMaxTiles 在数值改变时清理/重建 QSAnimator，再按当前用户已保存列表调用
setTiles(Collection)。后者原生检查 getUseControlCenter，非经典样式直接返回，并只取
列表前N项；模块不改列表、工厂或保存状态，不自动添加/点击磁贴。

HeaderTileLayout 使用固定原生 cellWidth，布局时依据可用宽度计算可见列数，空间不足
会将后续项目设为 GONE，更新可见项的无障碍顺序。模块保留这条链，不缩小磁贴或触摸区
强塞数量，所以高数量/大字号/窄屏可能显示更少。选择较少数量不删除其余已保存磁贴，
它们仍在展开面板；待真机验收动画与无障碍焦点。

构造期间 View 尚无 display，本项保守跳过，不能靠 constructor 的虚调用实现初次生效。
因此在原生 onAttachedToWindow 后按开关/版本/主屏门控调用一次已有 updateResources$1：
它只读系统数量并调用 setMaxTiles，让参数 Hook 接入。不重跑构造器、Tuner 注册或
onAttachedToWindow 本体；未启用不补调。之后方向/资源改变沿用原生刷新入口。

关闭后参数 Hook 保留原生，下次资源刷新恢复数量；不反向记忆或写入 Tuner。无强制
即时刷新，功能/参数修改的统一生效方式仍是用户安排重启 SystemUI，不在本轮执行。

## 安装与代码逻辑检查

- `ClassicQsSettings.kt` 的4个配置与目录、搜索、共享 CONFIG_KEYS 自动接线。
- `ClassicQsHooks.kt` 通过 ControlCenterHooks 主包入口安装，不复用或冲突插件 loader。
- measure/setMaxTiles/attach 三 Hook 整体 ready 门控，任一失败所有已挂 callback 保持
  原生。精确解析5个实例方法、4个字段与 MiuiQSPanel 构造器，具体调用者去优化须成功。
- 运行回调只在主线程写字段/弱表，版本按 adapter 缓存，查询失败保留原生。没有新增
  线程、队列、广播、资源全局替换或文件操作。宿主原方法异常不会触发重跑。
- 人工推演：native minimum 高于请求、可用高度不足、没有/少量/多页磁贴、无效方向/
  配置、首个/重复/改变上限、关闭后重新分配、原生自身要求重新分配、嵌套/异常 finally、
  构造无display到首次attach、反复attach、窄宽度显示减少、RTL与原生触摸区保持。
- 仅源码逻辑结论，不证明 Hook 实际加载、屏幕排布/动画正确、恢复时机或设备稳定性。

## 3. 上一批数值显示靶点修复

复查 SystemUiMethod.matches 时发现其严格比较 Modifier.isStatic；上一批 SliderValueTargets
将4个 Kotlin 静态桥接函数按默认实例方法声明，会使精确解析失败并直接返回0。
此前只有“签名字符串存在”的测试定义没有覆盖静态属性，也没有实际运行。

本轮补齐 `isStatic=true`：亮度/音量的 updateIconProgress$default、音量
access$updateIconProgress、updateSuperVolume$default。重新只读完整读取这4个方法，
正文SHA未变，manifest 增加静态属性说明；新增独立静态成员 fixture 与测试定义。
两项原有状态仍为 code_reviewed_partial_unbuilt，不升级为验证通过。

同时修正两条说明中的“重启”泛称，明确“重启系统界面”，与目录一致性断言及用户
生效方式相符。不改设置键、默认值、显示算法或既有绑定策略。

## 证据、未执行测试与清单

- `docs/classic-qs-code-review-evidence.json`：23个精确成员（18方法含构造、5字段），
  单独完整只读取得，无截断/分页，含当前原生分页、资源刷新、折叠选择与header布局。
- `docs/slider-value-plugin-evidence.json`：4个静态桥接正文重新核对；总成员数仍45，
  并非新增45个验证目标。未提交宿主正文，正文只在忽略的 .workbuddy/tmp/mt-systemui。
- 新增11个测试定义：3配置/目录、5行数策略、2精确成员、1静态桥接；功能总数断言改46。
  **没有编译或运行这些测试**。`git diff --check` 只检查文本差异，不当测试结果。
- 337条配置/导航/依赖：70 pending、132 needs_audit_existing_partial、97 implemented_static_verified、
  19 repaired_static_pending_device、9 partially_implemented_static_verified、7 code_reviewed_partial_unbuilt、
  1 code_reviewed_repair_unbuilt、2其他。减少5条pending是一个开关加4个参数，不是5个功能。
- 两个展开列数保持pending并记录原因；不把上游主开关有映射当整页行列数迁移完成。

没有本轮 APK/build.json，没有编译、测试、lint、安装、重启/热重载、改设备开关/作用域/
SIM/网络；未启动子代理、提交或推送。最后本地APK仍是21:13长按音量批次；设备仍是
19:29通知图标包。历史545项测试及lintVital成功不覆盖最近两批，历史全量lint22错误未解决。

下一轮继续其他待迁移/待审计项，仍默认只逻辑验证。真机布局/分页/点击/无障碍/动画/
旋转/开关恢复验收留后，不能因本批源码完成自动部署或沿用历史单次安装授权。
