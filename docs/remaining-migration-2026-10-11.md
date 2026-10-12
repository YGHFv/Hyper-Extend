# 剩余迁移推进与全表源码索引（2026-10-11）

## 最新：剩余 14 条源码续迁，统一验证仍推迟

WMShell 四项和传送门磁贴已接入源码、默认关闭的目录及安装链；新增完整宿主证据
27 条。337 条清单现有 9 pending、27 待审计；本批 5 条仅提升为源码部分实现未构建。
未编译、运行测试、安装、重启、修改作用域或执行设备功能。尚未完成全部 14 条。
桌面代码已定位到 Rust `libapp_launcher.so`，传送门已定位当前原生 Provider；
WMShell 不再缺代码工件。以 `docs/pending14-migration-2026-10-11.md` 为最新边界，
下方旧工件缺失判断、计数和历史测试结论不适用于本批新源码。

## 最新复核：MT 恢复与内嵌百分比生命周期

MT `.85:8787/mcp` 已正常取证；脚本已适配新版工作区发现与重开，不再盲用旧 ID。
13 份清单的 268 条宿主证据哈希匹配。修复内嵌标签安全模式残留和原生富文本误恢复，
完整 772 项单测、10 项工具测试通过；lint 与源码哈希见
`docs/mt-reconnect-validation-2026-10-11.json`，范围见
`docs/mt-reconnect-review-2026-10-11.md`。未安装或操作设备功能。
SecurityCenter 不在当前允许目录中，WMShell 导出仍无 DEX；不能据此断言设备功能不存在。
337 条状态未提升，14 待迁移、27 待审计及其他部分实现缺口继续保留。

## 上一批审计：GMS 事务修复与长按设置续迁

本轮先复核网速、天气、卡片列表及 GMS 的限定源码范围，复现并修复 GMS 事务终态
误报，通过定向回归后再接入长按设置。目标不存在、权限不足或原生启动实现为空时
不开放长按；该路径不写包状态。未进行设备操作，目标页面存在性仍待取证。
详见 `docs/migrated-feature-review-2026-10-11.md` 和
`docs/migrated-feature-review-validation-2026-10-11.json`。
337 条状态未提升，仍有 14 待迁移、27 待审计及其他部分实现边界。

## 上一批修正：网速回环扣除与天气刷新生命周期

已开始修复部分实装项：网速按方向扣除 lo 回环差值，异常计数保留原生；
天气在同一头部实例隐藏/分离后保留刷新间隔，重开不重复查询，用户切换仍清空。
本批验证和源码哈希见 `docs/partial-feature-repair-validation-2026-10-11.json`，
实现与剩余边界见 `docs/migration-continuous-progress-2026-10-11.md` 顶部。
本批没有重新打包、安装或重启；16:26:54 已安装包不含本批修复。
337 条状态未批量提升，仍有 14 待迁移、27 待审计及所有其他部分实现缺口。
下方“未构建/测试未执行”等均为旧检查点，不代表本批验证结果。

## 历史持续检查点：当时未完成、未构建

337条：14 pending、27待审计、12结构导航、93有界静态、10修复待设备、
4部分静态、132源码部分未编译、43源码修复未编译、2其他。
SYSTEM_UI_FEATURES现63（新增实际GMS磁贴），STATUS_BAR_FEATURES现17。
当前灵动岛背景/配色/氛围光六项已接入独立双Holder适配；GMS磁贴有独立工厂、
经典/插件编辑器和明确点击后的有界包状态事务。没有执行任何包状态写入。
普通/灵动岛原生进度修复level0/1真实getMaxHeight几何链，拖动保持原生。
本段17个新增测试仅定义未执行；历史606不覆盖现源码。
WMShell实际依赖com.miui.wm.shell；Launcher导出无DEX，依赖hyperos.rustruntime.v5。
这两者缺少实际实现工件，不代表功能不存在。14实际待迁移项、27项待审计、
所有部分实现遗留及统一构建/全337复核仍未完成。未安装、重启、热重载或改设备。
准确源码清单、完整成员证据和恢复队列见 `docs/migration-continuous-progress-2026-10-11.md`。
本节覆盖下方历史计数与旧未实现判断。

## 持续 worker 最新状态

当前目录 60，337 条：17 pending、126 待审计、12 结构导航、93 有界静态、
19 修复待设备、4 部分静态、60 源码部分未编译、4 修复未编译、2 其他。
新增实际实现和遗留边界见 `docs/migration-continuous-progress-2026-10-11.md`。
新源码没有统一编译/单测/lint/R8，也未安装或设备验收；历史606测试不覆盖它。
仍未完成所有337记录的源码与证据覆盖，不能进入“全部完成”结论。
以下旧批次计数和未实现列表保留为历史记录。

## 后续进展（覆盖下方旧计数）

已继续接入默认关闭的原生音量面板、原生关机菜单、普通卡片磁贴颜色和头部渐变模糊，
新增 66 条完整成员证据；没有编译或部署。12 个 category/fragment 导航条目已单独
定性，不能按已迁移功能计数，子项保持独立状态。

当前 337 条记录：44 pending、126 待审计、12 结构导航、93 有界静态、
19 修复待真机、7 部分静态、30 逻辑部分未编译、4 修复未编译、2 其他；
功能目录 53。**仍未全部迁完，未进入统一编译和编译后全量复核。**
完整实现、卡片 detail 重复创建风险和未完成队列见
[原生面板/卡片/头部渐变续迁](native-card-continuation-2026-10-11.md)。
以下保留前次记录，不用旧计数或旧证据哈希描述当前源码。

## 状态与规则

本轮用户要求全部剩余功能迁完后统一编译，再从头核查所有功能逻辑；中途问题先记录。
**当前未达成全量完成条件，不能把本批成果或源码索引说成“全部迁完”。**
尚未进入统一编译和编译后的全功能复核阶段。

- 保留本轮开始时全部未提交成果，包括 5G 上下文修复和返回位置修复。
- 未运行 Gradle、Kotlin/Java 编译、单元测试、lint、R8 或打包。
- 未安装、重启、热重载、修改模块/作用域/安全模式/磁贴/SIM/网络设置，未提交推送。
- 未委派子代理。最后 Git 提交仍为 `de34123`。
- MT 只读服务可用，SystemUI `202602260`、插件 `183022200`。
- ADB `devices` 本轮返回空列表；不能重新确认当前已安装 APK 或获取设备缺失工件。
  这仅影响设备读取，不意味着可把所有未实现项归因于 ADB。
- 历史 606 项测试、Release 和 lint 结果不覆盖本批源码。

## 本批源码实现

### 经典控制中心展开列数

保留 `classic_qs_layout`，补横竖屏 `.columns_portrait` / `.columns_landscape`。

- 上游在 `layoutTileRecords` 结束后写 `mColumns`；当前宿主先按列数测量 cell、计算
  分页容量，直接照搬会让测量、分页、布局不一致。
- 改为 pager 测量前为所有现有页调用原生 `updateColumns`，新建页在分配磁贴之前
  同步处理。仅临时更改 resource-column 输入，finally 恢复，不永久污染原始资源列数。
- 原生有效列数保留给后续测量、布局及可访问性使用；变化时才标记重新分页。
- 保留系统最大列数，按实际宽度、padding、margin 和 48dp 最小触摸宽度限制请求。
  无有效几何时回到原生列数，不遗留上一次自定义值。
- 原行数、折叠前缀选择、顺序、状态和原生经典样式选择不变；四个 Hook 全就绪才生效。
- 关闭后下次原生测量恢复；安全模式直接绕过 Hook，不保证当前布局即时恢复。

源码：`ClassicQsSettings.kt`、`ClassicQsHooks.kt`、`ClassicQsPolicy.kt`、
`ClassicQsTargets.kt`。完整方法/字段证据 30 条：
`docs/classic-columns-evidence.json`；精确签名 fixture 已同步。

### 媒体进度条样式

保留 `media_card_progress` 与原 `.height`，新增 `.mode`、`.thumb`、`.corner`、
`.comet`、`.round`；不是新增五个独立功能。

- 新增原生、波浪、直线模式；自定义直线支持彗星、圆角，按钮支持圆形、竖条、
  隐藏或圆角矩形。保留本地十分之一 dp 存储，不误当上游原始配置值直接导入。
- **实际版本差异**：当前没有上游旧的 controller seekBarObserver，当前链为
  `MiuiMediaSeekBarProgressOwner$progressObserver$1` →
  `MiuiSeekBarRenderState` → controller → `MiuiSeekBarRenderStateApplier`。
- 保留原生 SeekBar、父子层级、ID、listener、拖动、反误触、进度/时间更新和无障碍；
  只替换已绑定进度条的绘制，不照搬上游替换整个控件并吞掉 observer 的实现。
- 动画只读实际 enabled/listening/playing/scrubbing，系统关闭动画时不启动；
  非拖动且可见时最多每 33ms 请求一帧，不创建后台无限任务。
- 着色器设备 level 2 的 primary-color setter 为 no-op；不能从这些旧字段读颜色。
  自定义样式改取当前主题下时间文字颜色，轨道取低透明度；level 0/1 使用原生字段。
- 原生粗细改为 onDraw 期间临时写 `mProgressHeight` 并 finally 恢复，不再永久
  改最小高度；按压/拖动时保持原生，避免动画与关闭恢复相互覆盖。
- 精确版本、主线程/主屏、弱 controller/holder 引用和身份检查、四 Hook 全就绪。
  联合检查原生 material 的锁屏/锁屏通知/enterFullAod flow 及 mWillDoAodAnim，
  不仅依赖 KeyguardManager，避免解锁过渡期间误画。锁屏/AOD/外屏/灵动岛保持原生；
  服务读取失败回退，关闭后下次绘制恢复。两个抽象查询只解析调用，不要求去优化。

源码：`MediaProgressSettings.kt`、`MediaProgressHooks.kt`、`MediaProgressTargets.kt`、
`MediaProgressDrawing.kt`；旧 `MediaCardHooks.progressHeight` 已移除，避免双 owner。
完整方法/字段及抽象查询声明证据 39 条：`docs/media-progress-evidence.json`。

这是普通通知中心的有界实现，不宣称和上游 shader/材质及所有动画像素一致。
实际进度头/触摸对应、RTL、可访问性、播放暂停、卡片替换、深浅主题仍需统一运行验收。

### 隐藏网络监控提示行

新增默认关闭 `control_center_hide_security_footer`，配置接入功能目录、面板和两条安装链。

- 只隐藏新旧控制中心提示行，不伪造 `DevicePolicyManager` / `SecurityController`。
  不改企业策略、工作资料、证书、VPN 状态和状态栏图标。
- 经典控制中心：原显示更新 Runnable 先执行，确认 R8 classId 为 1 后隐藏 root；
  不吞图标更新、不改 `mIsVisible`，关闭后下次真实显示更新自然恢复。
- 新控制中心：原生 available 判断先执行，再对已启用且精确版本/主屏的实例返回 false；
  保留安全状态监听与刷新，并去优化内容分配调用方和插件发现链。
- 不主动强制面板重排；关闭恢复等待原生刷新/分配，安全模式完整恢复可能需要重建。
- 提示行包含管理或监控信息，文案明确提醒用户。和上游全进程伪造“未被管理”不同。

源码：`SecurityFooterHooks.kt`；主包 5 条、插件 5 条完整证据：
`docs/security-footer-systemui-evidence.json`、`docs/security-footer-plugin-evidence.json`。

## 全表源码索引与未处理问题

生成器 `tools/systemui_migration_audit.py` 对 337 条清单逐条收集上游 Java/Kotlin
中的精确字符串引用、源码行、文件哈希、XML enabled/visible 属性、原状态和本地 target，
输出 `docs/systemui-source-audit-2026-10-11.json`。每条保留待复核边界。

**索引不是完整控制流审计，也不自动提升状态。**
注释中的注册、UI 读写方、动态拼接 key、另一个 key 的自定义编辑器都需要继续跟踪。
例如 `OtherSettings.generateListener` 的原生音量条/关机菜单是直接修改插件组件状态，
并非 libhook 中的普通开关；不能因找不到 hook key 就判断它们没实现。

集中处理队列仍包括：

| 组 | 尚未完成的工作与已知证据问题 |
| --- | --- |
| 媒体 | 完整莫奈/反色/配色过渡/环境光；灵动岛背景、布局、文字和进度条。当前普通卡片实现不能代替灵动岛适配 |
| 磁贴 | 卡片列表和颜色、材质、GMS/传送门/雪豹/阳光/手电筒亮度。卡片 getter 有三处实际调用；还需核对创建、去重、detail 和原生 VoWiFi 生命周期 |
| 天气/通知 | 天气显示与地区、焦点通知混色及增强、头部渐变/强制模糊；系统允许管理所有通知还涉及 Settings/system_server |
| 状态栏 | 132 个原待审计记录未被索引自动清零；其中电池详情和灵动岛也有真正的未实现项，不能都当已有功能 |
| 导航/锁屏 | 导航栏/框架高度、隐藏栏、快捷按钮模糊/高级材质。手势线三个尺寸项上游 XML 明确 disabled，但是否不迁移仍需单独定性，不放空控件 |
| 音量/其他 | 完整百分比浮层/边距/材质、原生音量和关机菜单、Toast 材质、日志迁移、快捷标签。原生组件禁用需核对当前宿主回退与恢复链，不能直接改 PM 状态 |
| WMShell | 四个窗口功能缺当前实现 dex；先前资源 APK 的 dexEntries 为 0，当前 SystemUI/插件类搜索也没有目标。ADB 未连接，尚未获取实际共享库；不能使用旧类名填空 |
| 已部分迁移 | 5G 长按/详情/图标、音量长按完整缩放/按钮行为、磁贴材质/卡片、媒体与锁屏部分动画等原边界仍有效 |

所有未完成记录可通过 JSON 的 `reviewRequired` 和原始 `inventoryStatus` 追踪；
16 个结构候选也只标候选，没有当作已经迁移的功能清除。

## 当前计数与验证

- 总计 337 条配置/导航/依赖记录；56 pending（本批减少 8）、132 待审计。
- 93 有界静态、19 修复待真机、7 部分静态、24 逻辑部分未编译、4 逻辑修复未编译、
  2 其他状态。功能目录由 48 增为 49；参数不按功能计数。
- 原累计构建输入哈希已失配，旧 18 条提升状态按生成器规则回到未编译；
  没有沿用 606 测试结论给新增代码背书。
- 完成的是 MT 完整成员读取/签名和正文关键片段核对、JSON 元数据一致性、
  源码引用枚举以及 `git diff --check`；新增回归测试定义尚未执行。
- 本批代码级检查记录：`docs/remaining-code-review-2026-10-11.json`，含所改核心源码哈希；
  `compiled`、`testsExecuted`、`allMigrationComplete` 和编译后全审计标记均为 false。
- **尚未完成剩余所有功能，尚未统一编译，尚未做编译后的全功能逻辑复核。**
