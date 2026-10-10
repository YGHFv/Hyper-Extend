# 仅源码续迁：控制中心数值与编辑入口（2026-10-10）

后续修正：`docs/classic-qs-code-only-2026-10-10.md` 记录了本批4个静态桥接靶点漏标
isStatic 的修复。原签名存在不等于反射修饰符匹配；仍未编译/运行测试，不沿用本批逻辑
审查作为运行通过证明。目录两条生效说明同步补全“重启系统界面”。

本轮用户明确要求“不实际编译验证，仅代码逻辑验证，验证完成继续其他功能”。
按顺序完成亮度值、音量值两项部分迁移，再审计修复已有隐藏编辑入口。
未运行 Gradle、Kotlin 编译、JVM 测试、lint、打包或设备部署。新增测试文件只是后续用例，
不表示通过。历史 21:13 的 APK/545 项测试不覆盖本轮源码，原构建记录不修改。

## 1. 控制中心亮度值

- 新增默认关闭 `control_center_brightness_value`，控制中心 → 新控制中心。
- 上游 `VolumeOrQSBrightnessValue.kt` / `prefs_key_system_ui_control_center_qs_brightness_top_value_show`。
- 当前插件仍有 `BrightnessSliderController.updateIconProgress(boolean)`，通过精确的
  getSliderHolder → ToggleSliderViewHolder.getTopText/getSlider 取得当前视图，不使用
  上游“找不到 top_text 就创建未挂载 TextView”或强值弱键缓存方案。
- `VerticalSeekBar.getValue/getMin/getMax` 对应真实滑条范围；使用 Long 算术计算
  `(value - min) * 100 / (max - min)`，无效范围/值不显示。它不是物理亮度/尼特值，
  也不是自动亮度传感器结果；不写亮度、不改自动亮度策略。
- 原生 updateIconProgress、绑定、configuration 执行后刷新，绑定时未 attach 则
  用单个弱绑定 attach listener 等待。仅主屏主面板，编辑、镜像、禁用、触摸探索、
  非普通文字及原生已有可见提示保持原样。保留字号/宽度/颜色/材质/触摸区域。
- 此为部分迁移：二级亮度面板、独立亮度窗口/浮层、顶部边距和高级材质变体未迁移。

## 2. 控制中心音量值

- 亮度项逻辑审查后，继续新增独立默认关闭 `control_center_volume_value`，同一分组。
- 上游同一规则的 `prefs_key_system_ui_control_center_qs_volume_top_value_show`。
- 当前 `getTargetValue` 优先取 animatingValue，再取 slider value；经原生
  `valueToVolume(int)` → VolumeUtils.progressToLevel 并按 stream 范围限制，然后用
  `streamMaxVolume` 计算档位百分比，静音显示 0%。不抄 `sliderMaxValue / 1000`
  作为所有设备的最大档位，不把音量最大值无条件显示成 200%。
- `updateSuperVolume` 原生负责耳机/听筒/设备能力、阈值、特殊档位、显示文字和 Toast。
  本地不替换该方法、不改 showSuperVolumeText/superVolumeText，不屏蔽大音量提示。
  原生 topText 已可见时完全让位，因此超大音量区域仍由系统决定文案和无障碍标签。
- updateIconProgress/updateSuperVolume、绑定和 configuration 形成受保护更新范围。
  进入范围前恢复上一轮自有文字，原生执行完后才补普通范围数字；同 controller 嵌套
  不重复更新。这样原生“新旧文字相同就跳过写入”的 setter 不会把模块数字误当原生文本。
- 普通百分比只改 TextView.text/visibility，不调用 `setTopTextVisible`，因为该方法
  还会改 slider.accessibilityLabel。原生 SeekBar 的范围、操作与状态描述不替换，
  触摸探索直接排除；可见辅助文字是否造成重复朗读仍待设备验收。
- 此为部分迁移：侧边音量面板、展开面板、应用独立音量、百分比浮层及高级材质未迁移。

## 共用逻辑审查

源码：`SliderValueHooks.kt` / `SliderValueTargets.kt` / `SliderValuePolicy.kt`，复用
`SystemUiPluginHooks` 现有单一 loader 发现，不另挂全局 View/TextView/资源 Hook。

- 两个 adapter 各自独立 ready，亮度五 Hook、音量六 Hook；全部成功后才能写视图。
  精确 SystemUI `202602260` / 插件 `183022200`，版本结果按 adapter 缓存，异常不缓存。
  所有反射方法/字段严格返回及参数类型匹配；loader 发现调用方去优化失败不启用本项。
- 审查时修正了一个会让 ready 永远失败的问题：`E0.a.get()` 是抽象 Provider 查询，
  只需精确解析并调用，不应当成可去优化的 concrete caller。现在只去优化实际 Hook
  方法和原生具体调用者，查询 getter 不作错误的全量去优化。
- WeakHashMap 的 value 只弱引用 controller/TextView，纯 String 保存旧文本；不保留
  Spanned 的 span 对象（可能指回宿主）。非普通文字不覆盖，无线程/Handler/定时任务。
- 恢复仅当当前 text 与 visibility 仍等于模块最后写入的值；更晚的原生修改优先。
  unbind/destroy 在原生调用前清理，并设同 controller 作用域，防止清理时嵌套更新重新
  创建 label；detach 恢复，重新 attach 核对当前绑定，替换 holder 先清理旧 view。
- 原生异常不被当成模块失败重跑。模块查询/写入异常只记一次并跳过，ThreadLocal 在
  finally 恢复；没有绕过全局安全模式。关闭功能在下次原生更新/分离/重建清理，
  已熔断而不再触发 Hook 的可见视图可能保留到 detach/重启，不承诺立即回退。
- secondaryPanelRouter 主面板门控只在刷新/attach 时读取。转场开始后到下次刷新间
  已显示标签可能参与原生动画，本批不宣称转场全过程排除；不自定义动画或强制布局。
- 逻辑走查：零/中间/最大值、非零 min、零 max/反向/越界/整数溢出；静音与原生大音量
  优先；两开关互不依赖；重复更新、嵌套、关功能、换 holder、分离重挂、销毁、异常。
  这些是人工源码推演，不是实际执行的测试或 Android 渲染证据。

## 3. 审计修复隐藏编辑入口

继续检查 `control_center_hide_edit`，原先在 PluginAppearanceHooks 里无条件返回 false，
忽略 distributePanels 去优化失败，挂载后不再读开关，也没有精确宿主版本运行时门控。
新 `EditButtonHooks.kt` 代替旧分支，唯一执行路径通过共享插件发现接入。

- available(boolean) 原生先跑，只有本来 true、开关开且主包/插件精确匹配才改 false。
  原生 COMPACT/HORIZONTAL/WIDE_FOLD_COMPACT、NORMAL mode、省电判断全部保留，
  不把不可用入口变可用。查询版本失败保持原值。
- 当前 EditButtonController 同时实现 MainPanelContent；实际 distributor 通过
  MainPanelContent.available 调用它，完整正文已核对，不把接口调用误判为无调用。
- available/context/distributePanels 精确解析，caller 去优化必须成功；共享插件
  createPlugin/createPluginContext 发现去优化也纳入门控。一个 Hook，无异步或可见状态缓存。
- 关闭功能随下一次原生面板分配恢复，不主动改列表/保存布局/刷新页面。仅隐藏入口，
  不撤销编辑能力、不阻止其他入口；用户需要立即编辑仍可关开关后重启 SystemUI。
- 新稳定 Hook ID 替代旧带 loader 后缀的分支；本批未作热重载，中途多代 Hook 替换
  不作无缝承诺，后续验收用完整进程重启。

## 证据与未执行用例

- `docs/slider-value-plugin-evidence.json`：45 个精确正文成员（40 方法、5 字段）。
- `docs/edit-button-code-review-evidence.json`：3 个方法。
- `docs/code-only-plugin-discovery-evidence.json`：3 个共用主包发现方法，重新只读核对。
- 合计 51 个方法/字段，正文均完整、无分页/截断。大型 controller 先分段定位，最终
  manifest 逐个精确方法/字段完整读取。签名 fixture 仅用于未来测试，不运行宿主代码。
- 新增 10 个测试定义（5 百分比策略、2 靶点集合、1 目录、2 编辑入口策略），更新功能
  数量断言 45；**本轮没有运行它们，也没有把历史 545 项改成当前通过数**。
- 仅做源码/登记/证据一致性与 `git diff --check`；没有编译成功、测试成功或 APK 结论。

其他候选只调查、不冒充迁移：上游小窗装饰/无极缩放类及 GestureRecorder 在当前主包
workspace 的 dex_names 搜索为零命中（结果完整），不证明 ROM 整体不支持，可能属于
其他模块/拆分包，因此保持 pending；媒体进度样式依赖替换进度条与生命周期，不在本轮
硬塞未适配空壳。“网络监控提示”等安全相关可见信息未擅自隐藏。

## 清单与后续边界

337 条不变：75 pending、132 needs_audit_existing_partial、97 implemented_static_verified、
19 repaired_static_pending_device、9 partially_implemented_static_verified、2 其他；新增
2 code_reviewed_partial_unbuilt、1 code_reviewed_repair_unbuilt，显式区分本轮未编译源码。
已有隐藏编辑项从静态完成数移出到“逻辑修复未编译”，不能沿用旧构建证明新版通过。

本轮不生成 build.json 或新 APK。最后构建依旧是 21:13 长按音量条批次，不能部署该旧包
来验收本轮源码。全量 lint 历史 22 错误未处理，也未重新运行。未安装、重启/热重载、
改设备开关/作用域/SIM/网络，未启动子代理、提交或推送。

下一轮继续迁移/审计其他项，维持用户的“仅逻辑验证、不要编译”约束，除非用户另行更改。
未来授权后需验证：顶部文本布局/对比度、原生大音量提示与普通百分比来回切换、
耳机/静音/不同档位、自动亮度刷新、编辑/主二级转场/横竖屏/重绑、无障碍、关功能与
安全模式清理、编辑入口原生重新分配。当前不请求或自动执行这些设备操作。
