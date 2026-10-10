# 圆角磁贴迁移与音量主题审计（2026-10-10，22:07）

遵守用户“代码逻辑验证后继续迁移其他功能，不实际编译”要求。先完成圆角磁贴的有界
迁移，再审计修复现有音量默认主题。未运行 Gradle、Kotlin 编译、单元测试、lint、打包；
未安装、重启/热重载、改设备开关/作用域/SIM/网络，未委派代理、提交或推送。保留此前
全部未提交成果。当前源码没有新 APK，历史545项测试通过不覆盖本批。

## 1. 圆角矩形磁贴

新增默认关闭 `control_center_tile_corners`，控制中心 → 磁贴。半径配置
`control_center_tile_corners.radius`：1-99，默认72，沿用上游原始像素单位，不乘density；
运行时限制不超过原生tileSize的一半，不改变磁贴或点击区域尺寸。

参考 HyperCeiler `5e4686069dd7ab1f3697e256d5fc7d68fb73e317` 的
`CCGridForHyperOSKt.kt` 与 `system_ui_control_center_tiles.xml`。上游直接修改传入背景
GradientDrawable，并全局替换该类getCornerRadius；本地不照搬共享对象修改和全局getter。

### 当前宿主与实现

- 精确 SystemUI 202602260 / 插件183022200、主线程、主屏、默认主题；card和isDetailTile
  为false，且背景材质关闭。缺少显示或版本上下文保留原生，不强关用户系统材质。
- setter收到精确GradientDrawable矩形、统一圆角时，用ConstantState创建新Drawable并
  mutate，再保留bounds/state/level/alpha/布局方向/可见性/colorFilter，修改独立副本。
  当前五种普通背景资源都是solid+corners，警告、不可用、受限制状态颜色仍由系统决定。
- 原生updateIconInternal先决定动画/普通/材质链，setter只交付副本；原生继续组合图层、
  设置图标及透明度动画。liteIconUpdate也走同一setter。不改state、点击、触摸、无障碍。
- 原生icon outline优先读enabledBg或disabledBg的GradientDrawable圆角；副本被原生
  setter保存并invalidateOutline即可，恢复/再次调整副本时补invalidateOutline。
- 六个Hook（两个setter、updateIconInternal/updateResources/onMaterialModeChanged/
  recycle）全部ready才生效。所有精确靶点、直接caller、构造及共享loader发现去优化失败
  均不启用。三个静态方法显式isStatic=true，不重复上一批静态桥接漏标问题。
- 弱键Drawable表只持Float记录，没有View/Context/callback反向强引用。原生更新前恢复
  自有圆角，结束后再补；处理同状态早返回和嵌套setter，避免把自有值记作系统基线。
  回收前恢复，回收后丢记录；原生调用抛错不重放，不在异常路径继续强写圆角。

### 明确保留的范围

这是**部分迁移、仅代码审查未编译**。混色/玻璃材质、卡片、二级亮度/详情小磁贴保持
原生。只读核对BrightnessPanelTilesDelegate构造明确传isDetailTile=true，不能只凭
类名把它当主面板。非标准Drawable/分角圆角不接管。

进一步检查发现QSItemViewHolder与DetailPanelAnimator会调用setCornerRadius做转场。
因此最终**不Hook setCornerRadius，不替换getCornerRadius**，不争抢每帧动画半径。
检测到已应用圆角被原生改写，丢弃拥有权而不是恢复旧值；后续新建背景时才可重新接管。
转场getter仍是原生半径，外观可能跳回系统形状直到新背景创建，不宣称完整形状转场迁移。

关闭功能/切材质随下一次原生图标或资源刷新恢复；不强刷整个控制中心。不保证已存在
但没有再创建背景的视图即时启用。未挂载视图只有明确主屏Context才接管。安全模式会
全局绕过Hook，完整旧视图清理需宿主重建/重启，不能宣称即时无缝回退。

## 2. 音量默认主题审计修复

已有 `volume_default_theme` 仍默认关闭，键和入口不变。旧PluginAppearanceHooks分支存在：
部分Hook成功就改变行为、忽略caller去优化失败、缺少精确版本/动态开关、未去优化外层
实际调用者。现拆分为VolumeThemeHooks，旧分支移除，避免双重Hook。

当前只在三个原生调用范围改变ThemeUtils查询结果：

1. RingerButtonHelper.isSuperBlurSupported，实际caller为updateStateWithDefaultMaterial。
2. 静态VolumeColumnRes.getSliderBackgroundResId(View,boolean,boolean)，caller为
   VolumeColumn.setSliderResource(boolean)。静态修饰符纳入证据与测试定义。
3. MiuiVolumeDialogMotion.updateExpandBgState，caller为updateStateToExpand(boolean)。

三个dispatch xref均完整、各一个调用点，精确正文核对完成。查询和三个范围Hook全部
ready、查询/范围/外层caller及共享发现去优化成功才启用；读取当前开关和精确双版本。
主线程且宿主Context明确主屏才设置范围，缺信息原生。ThreadLocal支持嵌套，排除的
内层范围覆盖外层true，并在finally恢复；无跨线程/异步传播。

ThemeUtils原生查询先执行，原生true不压回false；仅范围有效时将false改为true，不写
defaultPluginTheme静态字段。不替换资源、图标或整个皮肤，不影响范围外控制中心查询。
低端设备、模糊支持、展开/控制中心嵌入参数、强制高级材质等仍由原生分支决定。

界面说明改为“三个原生音量材质判断点”，不再暗示完整替换第三方音量皮肤。关闭后查询
保持原生，但已设置的背景/模糊效果等待原生刷新或重建，不承诺立即清掉旧效果。
此项状态改为 `code_reviewed_repair_unbuilt`，不沿用旧静态/构建覆盖。

## 证据与检查边界

- 圆角manifest：`docs/tile-corner-code-review-evidence.json`，35个完整方法/字段正文、
  5个AXML正文及资源映射；7组完整dispatch调用引用。正文仅存忽略目录，不复制到docs。
- 音量manifest：`docs/volume-theme-code-review-evidence.json`，9个完整方法/字段正文，
  3组完整dispatch引用。两个manifest保留成员static属性、关键片段与SHA-256。
- 复用 `docs/code-only-plugin-discovery-evidence.json` 所列精确共享发现链，不另起loader
  Hook。两个新入口都纳入discovery门控，日志明确本批源码未编译/测试。
- 添加13个测试定义：设置/目录2，圆角策略4，精确靶点2，音量查询/作用域/靶点/文案5。
  **均未执行**。目录数量断言更新47。手工走查配置键、调用签名、静态修饰符、恢复和门控；
  证据正文/片段/哈希一致性检查及git diff --check通过，不替代编译或设备验证。
- 清单仍337条配置/导航/依赖：68 pending、132 needs_audit_existing_partial、96
  implemented_static_verified、19 repaired_static_pending_device、9 partially_implemented_static_verified、
  9 code_reviewed_partial_unbuilt、2 code_reviewed_repair_unbuilt、2其他。
  pending减少2对应一个开关与半径参数，不是两个独立功能。

没有本批build.json/APK；设备仍19:29通知图标修复包，本地最近APK仍21:13长按音量批次。
旧全量lint22错误未解决。下一轮继续pending/已有项审计，仍不自动构建或部署。圆角颜色
与通知背景颜色等仍pending；本批没有全局资源替换或迁移这些颜色项。
