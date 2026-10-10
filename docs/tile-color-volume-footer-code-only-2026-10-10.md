# 小磁贴背景颜色与音量底部按钮（2026-10-10，22:24）

按用户要求继续逐项迁移、仅做代码逻辑验证。本轮先迁移普通启用小磁贴背景颜色，再审计
修复现有折叠音量底部按钮。未执行编译、Gradle、单元测试、lint、打包、安装、重启/
热重载、设备开关/作用域/SIM/网络修改；未委派代理、提交或推送。保留所有旧未提交成果。
没有本批APK，历史545项通过不覆盖当前源码。

## 1. 普通启用小磁贴背景颜色

新增默认关闭 `control_center_tile_color`，控制中心 → 磁贴；颜色键
`control_center_tile_color.background`。复用现有HyperColor数据控件与CONFIG_KEYS，
只接受不透明RGB，留空/无效保持系统颜色，不沿用上游启用后默认全白。

参考 HyperCeiler `5e4686069dd7ab1f3697e256d5fc7d68fb73e317` 的 `QSColor.java` 和
`system_ui_control_center_tiles.xml`。上游直接改getActiveBackgroundDrawable返回对象，
并全局替换qs_icon_enabled_color。本轮不照搬共享资源替换，不修改图标色或卡片色。

### 当前调用链与边界

- 精确SystemUI 202602260 / 插件183022200、主线程、主屏、默认主题，非card/非detail、
  普通背景。材质/玻璃启用时跳过，不强关系统材质、不接管经典控制中心。
- 原生getActiveBackgroundDrawable先运行，其activeBgColor==1明确选择warning资源。
  模块只处理state==2且activeBgColor==0；disabledByPolicy、isTransient、原生
  isRestrictedState任一为true均保留系统色，未知状态或未知activeBgColor也跳过。
- QSTile.State在主包而非插件DEX。精确核对字段后由插件ClassLoader委托解析，不假定
  插件内存在同名副本。isRestrictedState还保留lastRestrictedState过渡判定。
- 只处理原生精确矩形GradientDrawable、统一圆角、非stateful纯色且无colorFilter；
  通过ConstantState.newDrawable后mutate创建副本，拷贝bounds/state/level/alpha/
  layoutDirection/visibility/filter，不改原对象、资源表或原生QSTile.State。
- 原生继续选择和组合enabled/disabled图层、透明度动画、图标、点击/长按/无障碍。
  图标仍原色，颜色控件说明明确要求用户自行保持对比度，本轮不宣称自动对比度保障。

### 与圆角共用拥有权

沿用上一批TileCornerHooks作为唯一背景适配器，新增active background返回Hook，现为
七个Hook整体ready。颜色/圆角任一开关启用时共享发现只安装一次；二者都开也不重复
Hook，不建立相互竞争的恢复表。安装失败不重试第二个入口形成半组状态。

active background副本记录原生ColorStateList与模块应用色；之后圆角setter若再次复制
该对象，将颜色拥有权一起传给副本。颜色和半径分别记录，原生转场改变半径只放弃半径
拥有权，不丢掉颜色恢复信息；检测到更新的原生颜色则放弃颜色拥有权，不反写旧颜色。

原生updateIconInternal/updateResources/onMaterialModeChanged前恢复自身修改，再跑
原生逻辑，结束后只对有对应拥有权的副本补当前配置；嵌套setter创建的新副本也先归一
基线。原生同状态早返回仍能恢复/补值，不将模块颜色误记成系统色。recycle前恢复，
结束后删除当前背景记录。弱Drawable键的value仅有数值和无View/callback的ColorStateList。

仍不Hook setCornerRadius/getCornerRadius，不抢原生转场半径；圆角原先的部分迁移
边界保持。更晚相同值的外部写入无法与自身写入区分，不宣称任意第三方Hook无缝兼容。

这是**部分迁移、未编译验证**：只映射上游启用开关和背景颜色两条记录，图标颜色、
卡片两色、经典小磁贴颜色保持pending。新启用可能等下一次原生背景创建；关闭/切材质
随下一次原生图标或资源回调恢复，不强制刷新。安全模式全局绕过Hook，完整清理需重建/
重启。原生颜色或限制变化若没有刷新回调，也不保证模块即时观察到。

## 2. 折叠音量底部按钮审计修复

保留默认关闭 `volume_hide_collapsed_footer` 及入口，拆分到VolumeFooterHooks，移除
PluginAppearanceHooks旧安装分支。旧文件只保留历史纯策略fixture，没有运行时注册。

旧实现的问题：

1. 按缓存requested布尔值在展开回调后写VISIBLE/GONE，会覆盖原生或其他面板后来的
   显隐决定；独立应用面板还被无条件写INVISIBLE。
2. 两Hook部分成功就生效，caller去优化失败被忽略，缺少双版本/动态开关门控。
3. 只考虑controller对updateFooterVisibility的三个caller，漏掉showH及
   ExpandCollapseStateHelper.updateExpanded对展开回调的调用。

新实现只保存真正由模块改过的footer View原可见性，不缓存“请求显示”。每次原生
updateFooterVisibility或onExpandStateUpdated之前，只有当前仍GONE且非独立应用面板
才恢复模块拥有的改动；原生完整执行后，只对普通主屏对话框、当前可见、折叠的区域
设GONE。原生已隐藏/INVISIBLE、展开、关闭开关、嵌入面板均不新改显隐。

mNeedShowDialog用于排除控制中心嵌入面板；双版本、主线程主屏、两个Hook全部ready，
8个精确方法（含5个实际caller）和共享发现去优化门控。方法声明owner明确：isExpanded
在ExpandCollapseLinearLayout，不从子类declaredMethods误取。字段核对类型与非static。

不调用updateFooterVisibility补刷新，因为它还会更新展开按钮；不动展开按钮、音量/
静音/勿扰状态或原生insets处理。原生activeStream 0/6与FlipTiny等隐藏判断仍执行。
只在回调前恢复自身可见性，不在原生结束后根据旧值“补可见”。独立应用音量面板
仍由SystemUiAppVolumePanel的hideFooter/restore管理，本项不接管其INVISIBLE布局。

恢复等待下一次回调，弱View-to-Int表无强引用环。安全模式、已脱离且没有回调的旧视图
可能保留GONE到重建/重启；相同GONE值的其他写入无法被身份判断区分。未宣称视觉/
无障碍焦点/触摸区域或所有Hook顺序已经运行验收。

## 证据与逻辑检查

- `docs/tile-color-plugin-evidence.json`：38个完整方法/字段正文，5个背景AXML及映射；
  active background两个实际调用点补充完整dispatch证据，复用前批7组调用引用。
- `docs/tile-color-systemui-evidence.json`：5个State字段/构造正文。主包与插件分开记录。
- `docs/volume-footer-plugin-evidence.json`：11个方法/字段正文；footer三个caller及
  expand两个caller的dispatch结果均无后续分页。宿主正文只存忽略目录，docs仅存签名/
  关键片段/静态属性/哈希，不复制完整smali。
- 新增10个测试定义但**未执行**：颜色设置2、颜色状态/拥有权/字段4、footer策略/恢复/
  签名/目录4。共享圆角靶点断言22并更新fixture；功能目录数量断言48。
- 手工核对状态、共享适配器只安装一次、圆角与颜色独立开关、恢复边界、继承owner、
  static参数及配置接线；完整证据片段/哈希/fixture一致性检查与git diff --check通过。
  这些不等于编译成功或运行时加载成功。

清单仍337条配置/导航/依赖：**66待迁移、132待审计、95有界静态、19修复待真机、
9部分静态、11逻辑部分未编译、3逻辑修复未编译、2其他**。待迁移减少2是一个功能的
开关和背景参数；footer从旧静态转逻辑修复未编译，不能拿旧构建覆盖。

设备仍19:29包；最新本地APK仍21:13长按音量批次，历史545项通过不覆盖后续四批。
全量lint历史22错误未解决。下一轮继续pending/已有项审计，默认不编译、不部署；
新旧面板视觉、颜色对比、转场、关闭恢复与Settings保存重进待用户安排统一验收。
