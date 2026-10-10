# 新对话交接：SystemUI 迁移（2026-10-10 安装后）

## 当前权威状态：19:29 低等级通知图标修复安装

### 19:34 运行时与 Git 补录

- 累计源码/测试/资源/文档已暂存、提交并正常推送：`77e779c`
  `feat: migrate system UI features and repair notification controls`；共 326 个文件。
  推送后 `origin/main` 与本地该提交一致。原始宿主 APK/日志/本机密钥/构建产物未提交。
  本补录为随后独立文档提交，不 amend、不 force push；最终提交号用 `git log -2 --oneline` 查看。
- 安装后观察到 SystemUI 自行进入 PID `30431`，19:29:58 日志已安装
  `notification_importance/statusBarEffect`、`notification_importance/iconModel`，dispatch importance=3。
- 19:30:01 日志确认 `low-ranking status-bar icon suppression reached`。
  **新图标补丁已加载且实际命中**；此前“尚未确认加载”是安装即时状态，已由本条更新。
- 没有发送重启/热重载命令；PID 变化原因未调查。实际图标消失/升回恢复、分组等视觉结果
  仍无验收证据，不把 Hook 命中当全部验收通过。下一轮不必重复排查是否漏装图标 Hook。

用户确认双页面修复后“可以调整”，但中文“低”仍显示状态栏图标，继续补修。
**以本节为最新部署与进度，下面保留历史批次，勿误读为当前状态。**

- Settings 新会话 PID 10953 于 19:10 安装 importance=3，新 app 渠道页多次
  listener bound level=1；用户确认可调，但日志暂未捕获 selection/save，不冒称全场景验收。
- SystemUI 19:10 旧代码 importance=1 仅改统计，漏了给状态栏图标供数的 activeNotifications。
  西米露过滤 onAfterRenderList 会同时影响这条链；此前的 stats-only 迁移不等价。
- 本批以 toModel(NotificationEntry) 作用域 + shouldSuppressVisualEffect(32) 精确判断补齐：
  等级 0/1 进入原生状态栏 suppression，其他级别/效果/调用域保留原生，不删除通知、
  不裁剪共享渲染列表、不改 Ranking/系统保存设置。去优化调用方，整体 ready 门控。
  中文“低”=1，中文“中”=2，不要混同 Android 常量名 IMPORTANCE_LOW=2。
- 455 项测试全通过、R8 Release 成功、10 个本批 SystemUI 方法实时核验；42 个前批
  Settings DEX 成员证据保留。全量 lint 未重跑，历史 22 个错误仍未解决。
- 最新安装：2026-10-10 19:29，`0.1.3 / 4`，2696133 bytes，本地和设备 SHA-256 一致：
  `41001fbbc4db487523af392526446e73ceafc286494db923b483f2a21565ab63`。
- 安装前后 system_server `3641`、SystemUI `10580`、Settings `10953`、MiSound `905`
  未变。未主动重启/热重载、改开关/作用域/通知等级/SIM/网络。新图标 Hook 尚未确认加载，
  图标消失/恢复没有真机成功证据，勿直接宣称全部修好。
- 详情：`docs/notification-importance-icon-2026-10-10.md`；构建/安装分别为
  `docs/notification-importance-icon-build.json`、`docs/notification-importance-icon-install.json`。

下一轮优先：用户安排真机验证时确认 SystemUI importance=3、首次低等级 suppression 日志，
再看等级 1 隐藏/2-4 恢复且通知卡片不消失；验证分组/聚合/通知更新/DND/其他图标功能。
Settings 保存链继续看新页面 listener bound 与 selection/save 回读日志。不得主动重启。

迁移统计仍 337 条配置/导航/依赖记录：**87 待迁移、132 待审计**，89 有界静态、
19 修复待真机、8 部分静态、2 其他；不是功能数。其他迁移待办及约束见后文。

## 19:09 双渠道页面修复安装记录

用户在 18:48 包后再次报告通知重要程度不保存，已暂停原定推送继续排查并补修。
**最新安装包与状态以本节为准。**

- 设备 Settings 镜像日志显示 18:49 PID 706 已安装 importance=2，但从无 listener bound。
  设备同时启用西米露的更多通知设置，选项可见不代表本模块保存链已绑定。
- 实际活动记录出现新 `notification.app.ChannelPanelActivity`，其 APK 正文明确跳到新
  `notification.app.ChannelNotificationSettings`；此前只挂旧 `notification.ChannelNotificationSettings`。
  两套页是同一个 Base 的兄弟类，旧 onResume Hook 不会覆盖新页，这是漏适配。
- 已修复两个页面各自 onResume/removeDefaultPrefs 去优化与绑定，共用单一 visibility，
  保存后按当前页面调用对应 updateDependents。保留复制渠道、user-lock bit 4、写入回读确认
  及原生限制。增加无敏感包/渠道信息的 bind skipped、listener bound、selection/save 日志。
- 449 项测试全部通过，R8 Release 成功，42 个 Settings DEX 成员核验，diff check 通过。
- 19:09 再次覆盖安装 `0.1.3 / 4` 成功，本地和设备 SHA-256 一致：
  `afd41c10cdcbe9ed7ecf68cdd6bd2463a52e8a6f5045b77aa4d2b742a84fb5e3`，2696133 bytes。
- 安装前后 system_server `3641`、SystemUI `26550`、Settings `706`、MiSound `905` 不变。
  未主动重启/热重载、改开关/作用域或通知渠道；**Settings 仍旧会话，双页面修复未确认加载，
  用户的保存返回重进问题没有运行时成功证据，不能宣称已经验收修好。**
- 完整调查：`docs/notification-importance-panel-2026-10-10.md`；构建/安装分别为
  `docs/notification-importance-panel-build.json`、`docs/notification-importance-panel-install.json`。

后续用户安排验收时，先看 Settings 新会话 importance=3 和新页面 listener bound，再看
selection/save confirmed=true/actual 与用户选择一致，最后返回重进确认。未绑定则按新增的
跳过原因排查，不先猜 Binder。尚未授权主动重启，勿替用户改渠道、西米露或本模块开关。
统一迁移清单仍为 337 行，87 待迁移、132 待审计、89 有界静态、19 修复待真机、8 部分、2 其他。

## 18:48 安装与提交批次记录

**先读本节；下方“最新”标题为各历史批次原记录，不代表当前未安装。**

最新用户要求：禁止自动折叠时同步移除通知长按菜单“收纳到更多通知”，与西米露对应行为
对齐；完成后安装、暂存提交推送，并交接继续迁移。不委派子代理，不主动重启/热重载宿主。

- 已完成同一开关联动。当前 ROM 的 `canCustomFold` 不调用 `shouldIgnoreEntry`，新增
  菜单生成域的精确对象身份拦截，避免空位；已折叠项仍可移出，其他菜单操作保持原生。
  自动折叠四条原路径保留；去优化调用方，7 个 Hook 未全装成则整体保留原生。
- 上轮功能布局已按功能块合并面板，不按 hasDetailPage 分卡；详情同名 group 也合并。
  实际开关/二级配置入口含义保持不变，未删除有效二级页。
- 上轮通知重要程度保存修复已包含：Settings `17 / 37` 完整页面链绑定，public
  findIndexOfValue，复制最新渠道写入并回读确认，拒绝静默失败并提示；只改重要程度与其
  user-lock 位，不突破管理员/ECM 等原生限制。**用户报告的返回重进问题仍待真机确认**。
- 本次 448 项单元测试全部通过，0 失败/错误/跳过；R8 Release 成功；14 个本批宿主
  方法/字段实时核验。全量 lint 未重跑，历史 22 个错误仍未解决。
- 2026-10-10 18:48 已向设备 `4e2d9aa2` 覆盖安装 `0.1.3 / 4`，本地与设备 APK 哈希一致：
  `ef684531b1126d8c178316645e15b6e9e6e4ad62bf6e5a060eacd59b3e815696`，2696133 bytes。
- 安装前后 system_server `3641`、SystemUI `16055`、Settings `23704`、MiSound `905`
  均不变，独立插件进程未运行。没有主动重启/热重载、改开关/作用域/渠道/SIM/网络。
  **安装不等于 Hook 已加载，更不等于运行时验收完成。**
- 当前分支 `main`，远端 `origin https://github.com/YGHFv/Hyper-Extend.git`。
  已核验提交前本地 HEAD 与 origin/main 同为 `2afbaf1`，没有分叉；本批将累计修改一并提交。
  提交/推送结果在本节补录，后续继续前先 `git status --short` / `git log -3 --oneline`。

关键记录：

- `docs/notification-fold-menu-2026-10-10.md`（本批行为、证据与验收边界）。
- `docs/notification-fold-menu-build.json` / `docs/notification-fold-menu-install.json`。
- `docs/panels-notification-importance-2026-10-10.md`（上轮 UI 与重要程度修复）。
- `docs/systemui-upstream-inventory.json`，生成器 `tools/systemui_inventory.py`。

### 当前还有多少与下一轮方向

337 条配置/导航/依赖记录：**87 pending、132 needs_audit_existing_partial**、
89 implemented_static_verified、19 repaired_static_pending_device、
8 partially_implemented_static_verified、2 个其他特定边界状态。不是功能数，不能宣称全迁完。
本批补齐已有行行为，pending/待审计数不变。

继续以清单为准迁移未做项，同时审计已有实现；完整媒体配色/动画、控制中心磁贴与旧布局、
锁屏剩余项、灵动岛、AOD、WMShell 仍未全迁。左侧手电筒是有界部分实现，缺上游动画与
主题图标，不能标为完整对齐。不要重复迁移已写入的充电/左右隐藏/手电筒基础替换。

统一真机验收优先包含：功能面板主列表/搜索/详情；通知重要程度 1-4 保存返回重进与失败
提示；收纳菜单消失/无空位/TalkBack/更新旋转/已折叠项移出；锁屏手势与充电退出恢复；
媒体卡片生命周期、六位置音量动画、安全模式。当前仅授权这一次安装，不据此自动重启或
持续部署每一批；后续仍先完成迁移，验收留到用户安排的统一阶段。

---

## 历史批次记录（部署状态已被顶部覆盖）

## 最新用户修正：功能分块面板与通知重要程度保存

- 用户要求取消以“有没有二级页面”分卡。主列表与搜索改用功能块；详情页同名 group 的
  开关和数值/下拉合并同一面板。保留真正有配置的二级入口、现有键与默认值。
- 用户报告重要程度显示但保存无效。本批只读提取当前 Settings `17 / 37` APK，确认原先
  getMethod 查找的是 private findSpinnerIndexOfValue，已改 public findIndexOfValue。
  改为完整 onResume 后绑定、去优化调用方、复制最新渠道写入并回读确认；失败拒绝选择，
  保留管理员/ECM/不可修改渠道限制，不强开其他未绑定行。
- 35 个 Settings DEX 成员 + 5 份正文证据；非 MT 工作区证据，非功能验收。
  详情：`docs/panels-notification-importance-2026-10-10.md`；
  构建结果：`docs/panels-importance-build.json`。
- 最终 439 项单元测试通过，R8 Release 成功，`git diff --check` 通过；版本 `0.1.3 / 4`。
  APK SHA-256 `a99bca1796d3a07fac735fa1ac2a41fb1574d1b4c993e41e6cf04769711d1f8c`。
- 清单 pending 87 / 待审计 132 不变；通知重要程度由等待 Settings 工件转为静态修复待真机，
  repaired 18 → 19，其余两类特定状态共 2 条，总计仍 337 行，不是功能数。
- 未安装、重启/热重载、改开关/作用域/渠道等级；用户报告的返回重进问题待修复包统一验收。

## 最新续迁：锁屏左侧手电筒替换（有界部分迁移）

继续上轮隐藏入口之后的手电筒子项，新增默认关闭开关 `lockscreen_left_flashlight`。
按住至少 400ms 后松开切换，支持 TalkBack，左侧隐藏优先；使用宿主手电筒控制器，
不绕过不可用/低电量/强制关闭策略，不修改已保存快捷应用。
仅主屏普通锁屏且原生左入口可见时替换，未含上游按压缩放动画与主题图标。

- 详细边界与恢复审计：`docs/lockscreen-flashlight-2026-10-10.md`。
- 54 个宿主代码/布局/字段目标实时核验；新增手势处理保留外层原生结束与清理，
  UP/CANCEL 返回 false，避免 `mIsShortcutMoving` 残留；迟到回调与旧按压代次拒绝。
- 420 项单元测试通过，R8 Release 构建成功；版本仍为 `0.1.3 / 4`，未部署。
  APK SHA-256 `9532a528d8a6a91d53d11cf2971ff4509842117fa789beee8173ba50d7588489`；
  结果见 `docs/lockscreen-flashlight-build.json`，`git diff --check` 通过。
- 清单计数不变：87 pending、132 待审计、89 有界静态、18 修复待真机、8 部分静态、
  其余 3 个特定边界状态，总 337 行。此次推进原有部分迁移行，不虚减 pending。
- 不安装、不重启/热重载、不改开关/作用域；设备仍是 15:43 包，统一真机验收留后。
  没有提交或推送。此前章节的“手电筒仍待迁移”是该批历史，本批仍不宣称完整上游一致。

## 最新续迁：锁屏左右快捷入口隐藏

在充电信息后新增两个默认关闭开关：`lockscreen_hide_left_shortcut`、
`lockscreen_hide_right_shortcut`。当前 ROM 使用 AOD 包内快捷插件，复用 SystemUI
单一插件加载 Hook，不新增 AOD 作用域。隐藏整侧图标与入口并拦截对应命中/点击/启动，
保留原生手势结束、取消和持久化快捷配置。左侧手电筒替换仍待迁移；右侧隐藏包括更换后的应用。

- SystemUI 5 + AOD 插件 24 个目标实时核验，精确版本门控；详情：
  `docs/lockscreen-shortcut-2026-10-10.md`、`docs/lockscreen-shortcut-build.json`。
- 399 项单元测试通过，R8 Release 构建成功；APK 版本仍为 `0.1.3 / 4`，
  SHA-256 `2b7d783e049e7bf3ef5760336202afdea7806d887d6bb9f66893c334d0ef263c`，未安装。
- 清单为 87 pending、132 待审计、89 有界静态、18 修复待真机、8 部分静态、
  其余 3 个特定边界状态；总计 337 行，不是功能数。左侧模式明确只部分迁移。
- 本批未安装/重启/热重载/改开关作用域/SIM/网络，未提交；设备仍为 15:43 安装包。
  迁移完成后统一真机验收的规则不变。

## 最新续迁：锁屏充电信息

在媒体续迁后继续新增 `lockscreen_charging_info`，包含电流、电压、电池侧估算功率、
可选 mA/温度及 1-5 秒半秒步进刷新。仅原生普通锁屏充电提示实际显示时追加独立信息行；
保留原提示、点击和轮播，不强改原生字体。单工作线程读取 Android 电池接口，
隐藏/分离/换区后取消任务并拒绝旧结果；AOD/外屏/反向充电/保护提示回退原生。

- 21 个宿主靶点实时核验；详见 `docs/lockscreen-charging-2026-10-10.md`、
  `docs/lockscreen-charging-build.json`。
- 待迁移从 94 降至 89 行，有界静态实现从 83 增至 88 行；其余状态不变，总计 337 行。
  这是同一充电功能的 5 个配置行，不是新增 5 个功能；仍未建立去重后的功能总数。
- 底部快捷按钮已转交 shortcut 插件，继续待迁移，不能照搬旧按钮字段。
- 本次依然未安装、重启、热重载、改开关/作用域或提交。设备部署仍是 15:43 的包，
  本地 Release 已包含之后的媒体与充电源码；统一真机验收尚未开始。

## 15:43 安装后的源码续迁更新

以下正文安装记录仍是设备的最后部署状态，不代表后续本地 APK 已安装。
新增记录：`docs/media-continuation-2026-10-10.md`、
`docs/media-continuation-build.json`。本次未安装、重启、热重载或启用新功能。

- 新增默认关闭的 `media_card_background.transition`，仅 333ms 封面背景过渡；
  不冒充莫奈前景颜色动画，仍属部分迁移。
- 修复换图期间恢复原材质闪回、排除状态仍处理封面、空 bind 后旧封面复活、
  holder 身份检查、AOD 延后恢复覆盖新背景、材质重建后的归属丢失；队列改为硬上限四项。
- MT 已恢复，媒体证据现为 34 个实时核验目标，含 holder setter 与调用方证据。
- 清单现为 94 pending、132 待审计、83 有界静态、18 修复待真机、7 部分静态、
  其余 3 个边界状态；总计仍 337 行，不是功能数。未全量迁完，统一真机验收仍待进行。

下文 350 测试、95 pending、6 部分静态及 33 媒体目标等数字保留为 15:43 安装阶段历史。

## 用户目标与工作规则

项目：`E:\MTool\Work\Hyper-Extend`。

继续对照西米露 / HyperCeiler 迁移尚未迁移的系统界面功能，同时复查已迁移逻辑。
用户要求迁移后统一真机验收，不要每迁一批就要求测试。
最新指令是“打包安装然后总结进度方便我开新对话继续”，本次已执行安装。

- 安装成功、入口存在、构建通过、MT 靶点匹配都不是功能验收完成。
- 不主动重启 SystemUI/设备、热重载、改功能开关或作用域、切换 SIM/网络。
- 日常只安装 R8 优化 Release，不安装 Debug 注入宿主。
- 不启动子代理或并行委派；本轮未委派。
- 大量未提交修改，本次安装前 `git status --short` 为 255 项，文档更新后略增。
  保留所有已有修改，不 reset/checkout/revert；没有提交或推送本批代码。
- 全量 lint 以前有 22 个已知问题，本次未重跑全量 lint，不能报告全部干净。

## 最新构建与安装状态

- 2026-10-10 15:43 左右（Asia/Shanghai）覆盖安装到 `4e2d9aa2` 成功。
- 设备：`25098PN5AC / pandora`；`ro.build.version.release` 本次返回 `17`。
- 包：`io.github.YGHFv.HyperExtend`，版本 `0.1.3 / 4`，未增加版本号。
- 命令：`adb -s 4e2d9aa2 install -r app/build/outputs/apk/release/app-release.apk`。
- 本次重新执行 `:app:testDebugUnitTest :app:assembleRelease`，R8 Release 成功。
- **350 项测试，0 失败/错误/跳过**；所有最后源码修改均已覆盖构建。
- 日志：`build/migration-install-build.log`。
- APK：`app/build/outputs/apk/release/app-release.apk`，2,663,365 bytes。
- 本地与设备端 SHA-256 一致：
  `0cd44b28f94f520e6a4b4e776f84ebf652a503f010e1edfa21a522156c230a0f`。
- 正式安装记录：`docs/migration-install-2026-10-10.json`。
- 安装前后立即采样：system_server `3641`、SystemUI `14609`、MiSound `25455` 均未变化。
  Settings/独立插件进程不在该次采样中。只代表采样时状态，不能推断永远不会重启。
- **未主动重启/热重载宿主，未开启新功能；不确认新增 Hook 已加载，未完成运行时验收。**

下列历史文档中的“未部署”、旧测试数与旧 APK 哈希是当批源码阶段记录；
最新安装状态以此交接和安装 JSON 为准。当前安装包包含所有以下累计源码改动。

## 已有前序需求（保留，不覆盖）

- 备份、恢复、重置改为整行入口。
- “熔断”迁移为设置中的“安全模式”，按宿主停用，不清空配置，恢复需确认。
- 真正触发保护才报告异常；事件后台保留，下次打开模块提示。
- “统一多应用音量调节样式”六个 Miuix 位置：音量条上方、静音上方、勿扰上方、
  勿扰下方、替换静音、替换勿扰。
- “解锁通知重要程度”是直接开关；关于页移除重复日志、紧急停用说明。
- 其他前序迁移包括通知展开/定时收起、原生媒体进度条粗细、按钮/文字/布局等。
  这些不等于全量迁移或完整审计；Settings 端通知重要程度仍缺完整宿主核验。

## 本次累计迁移与修复

### 音量入口动画与六位置

修复替换模式因 `onPreDraw` 执行 `resetMotion` 而不跟随系统动画的问题。
六位置统一 `syncMotion`，减去实际父容器已有变换，避免重复位移/缩放/透明度。
底部新增行同步扩展固定高度父容器，替换模式不扩展；已布局/待布局增量分开，
原生布局钳制或重写时不重复扣除；隐藏/展开/结束恢复自己拥有的修改。

文件：`AppVolumeEntryHooks.kt`、`AppVolumeEntryMotion.kt`、`AppVolumePlacementPolicy.kt`
（`hook/feature/volume`）。文档：`docs/app-volume-button-position-2026-10-10.md`、
`docs/systemui-next-audit-2026-10-10.md`。

仍需六位置动画、完整点击区、连续音源变化、横竖屏、小空间、原按钮恢复真机测试。

### 双排移动网络图标

键 `status_bar_dual_row_signal`，系统界面 → 状态栏。
默认/经典/粗体/主题四样式；70%-140% 缩放，左右与上下有符号偏移。
与非默认信号显示逻辑/隐藏任意 SIM 冲突，UI 提示且 Hook 不启用。
限 SystemUI `202602260`；保留原 View/ID/约束，默认上网卡为上排和承载视图。
普通蜂窝资源严格识别；单卡、飞行、卫星、未知/无服务等回退，不保留旧强度。

逐绑定 VM 接口代理，与网络类型显示共享 `MobileViewModelFacade.kt`；
支持代理叠加、身份 equals/hashCode、解包原 VM，不修改多处共享 VM。
Flow 在 CREATED 生命周期收集，隐藏下排本身不会使本地订阅停止；
分离/重新绑定清理与回调代次保护已补。偏移/密度缓存和无障碍空值恢复已补。
132 个上游资源有来源清单。尚未双卡真机验收，生命周期不是完整热重载保证。

文件：`DualRowSignalSettings.kt`、`DualRowSignalHooks.kt`、`DualRowSignalPolicy.kt`。
证据：`docs/dual-row-systemui-evidence.json`；资源来源：`docs/dual-row-signal-assets.json`。

### 通知数量与网络类型

- `notification_remove_count_limit`：只拦截当前 OS4 超限清除回调，保留 coordinator attach、
  手动清除、应用撤回和 system-server 发布限额。默认关闭。
- `status_bar_mobile_type_text`：定制非空原生网络类型名，最多 8 个 Unicode 码点；
  拒绝控制符、换行/段分隔、方向控制与非法代理项；保留原生可见性和测量。
  正式证据现已补齐：`docs/mobile-type-text-systemui-evidence.json`。
- `status_bar_mobile_type_display`：五档显示策略、独立原生文字、左右位置、加粗、
  字号和偏移。使用逐绑定 relay，不移除原图标或禁用测量；无服务/未知状态保守回退。
  可以组合双排与自定义文本。文档：`docs/mobile-type-display-2026-10-10.md`。
- 上游隐藏的“大网络类型图标”XML 尚未找到有效当前接线，不能为了清 pending 加空壳。

### 时钟剩余形态

原键 `status_bar_clock`：双行时间/日期顺序、对齐、行距倍率、固定宽度；
状态、大时钟、迷你/横屏、平板日期各自样式；平板日期隐藏；大时钟显式格式同步。
保留旧 `.editor_s/b/n/p` 和状态栏偏移编码（12 为居中），默认大时钟仍独立格式。
未知时钟 ID 不处理，横屏时钟只改样式不改原生格式。
同步主线程格式化，临时写宿主日历后 finally 恢复；不照搬上游后台线程共享日历。
保留原生 updateTime/无障碍/demo 行为、主题/字号刷新及自有样式恢复。

文件：`ClockSettings.kt`、`StatusBarClock.kt`、`ClockFormatPolicy.kt`、`ClockSecondsTicker.kt`。
记录：`docs/clock-migration-2026-10-10.md`；9 个代码/布局目标、5 个 ID 正式证据。
24 个额外配置行有界映射，旧修复行不改为已验收。

### 媒体始终深色

键 `media_card_always_dark`，控制中心组，直接开关，默认关闭。
当前七种原生背景效果使用局部深色资源 Context；前景色更新结束恢复控制器 Context。
不移除 AOD 监听，不永久替换 Context，不更改进度显隐/播放控制。
真实监听签名为 `onFullAodChange(boolean, boolean)`，不是控制器的单参方法。
增加所有 Hook/去优化调用者的精确 MT 方法签名 fixture 回归。

记录：`docs/media-dark-migration-2026-10-10.md`；21 个代码/布局/字段目标、
6 个资源名、1 个文件、8 个日夜值证据。后来与自定义背景共用唯一 Hook owner。

### 媒体背景首批（明确部分迁移）

键 `media_card_background`，默认关闭，mode 0 保持原生；mode 1-4 是封面拼色、
模糊封面、径向/线性渐变，blur 0-20%，0 真正无模糊。
**仅普通未锁屏通知中心卡片；锁屏、AOD、翻折外屏、灵动岛保持原生。**

- 当前链：`MediaViewBinder` → `mediaViewEffectsMap` → 七种 `MediaView*Effect`。
  旧 `updateMediaBackground` 不存在，未用旧靶点。
- 复用宿主 `artWorkDrawable`，通过 `MediaData.artwork` 区分封面与应用图标回退。
  不再次读取应用资源/URI，不修改或回收宿主位图。
- 192x192 缩略图；硬件图复制限制 4MP，无法处理则原生回退。
  主线程复制 + 单工作线程像素处理，最多四个排队任务，失效任务代次拒绝发布。
- 唯一 `MediaDarkHooks` 同时处理两项设置，避免重复注册互相覆盖。
  原生 clear + 材质 type 0 后绘制，退出时重新应用原生效果/前景。
- 重新绑定、holder 更换、分离、空封面、配置变化和安全模式恢复已作有界处理。
  锁屏材质事件后同步恢复前景，防止自定义背景去掉但浅色文字遗留。
- 当前固定深色对比度，RGB 上限 46/255，按已核验半白色次要文本计算超过 4.5:1。
  **不等同上游莫奈配色、随机拼色、颜色过渡、反色、环境光。**
  主线程硬件图复制耗时、异步/RenderThread、动画和恢复仍需真机检查。

文件：`MediaBackgroundSettings.kt`、`MediaBackgroundPolicy.kt`、`MediaArtworkJob.kt`、
`MediaArtworkDrawable.kt`、`MediaDarkHooks.kt`、`MediaDarkTargets.kt`。
记录：`docs/media-background-migration-2026-10-10.md`。
证据：`docs/media-background-systemui-evidence.json`，33 个代码/布局/字段目标、
7 个资源名、1 个文件、8 个日夜值。两个配置行均标部分实现，不是已迁完。

## 遗漏清单最新状态

清单：`docs/systemui-upstream-inventory.json`；生成器：`tools/systemui_inventory.py`。
参考：`.workbuddy/refsrc/HyperCeiler`，revision `5e4686069dd7ab1f3697e256d5fc7d68fb73e317`。
337 行包含导航/依赖，不能说成 337 个功能：

- 95 `pending`。
- 132 `needs_audit_existing_partial`。
- 83 `implemented_static_verified`（仅命名范围，不代表完整上游一致）。
- 18 `repaired_static_pending_device`。
- 6 `partially_implemented_static_verified`。
- 1 `partially_implemented_pending_settings_host`。
- 1 `covered_by_existing_feature_per_user`，1 `already_supported_by_current_host`。

仍未全量迁移，也未完整审计所有已迁移代码。主要遗漏：媒体完整配色/动画/环境光、
完整进度条与彗星、控制中心磁贴/旧布局、锁屏充电与底部入口、灵动岛、AOD、WMShell 等。
Settings 通知重要程度适配器仍待完整宿主证据。上游缺旧类名不等于功能不适用。

## MT 与工具

- MT MCP：`http://192.168.10.85:8787/mcp`。
- SystemUI：`wot5ikcq`，`17.03.260226.r / 202602260`。
- 音量/控制中心插件：`gqbnd0ih`，`183022200`。
- MiSound：`yglu33cw`，`260903`。
- AOD：`7rdlzzo0`；WMShell `36se70sn` 当前只有资源 APK，没有实现 dex。
- helper：`tools/mt_systemui_probe.py` / Python `MtSession`，只读。
- 文本 maxChars 最大 131072、limit 最大 2000；搜索 limit 最大 **200**。
  检查 pagination/hasMore/truncated/skippedScopes，AXML 使用 `axml:`。
- 最新媒体背景 33 目标实时读取已成功；最后追加 clear 签名断言时再次连接收到
  WinError 10061（拒绝连接），故使用与当批刷新哈希一致的缓存文本检查新增断言。
  新对话若需要新靶点，先确认 MT 服务恢复，不谎称追加了新实时证据。
- 原始证据：`.workbuddy/tmp/mt-systemui/`，重点 `media-bg-*`、`media-dark-*`、
  `media-next-*`、`clock-next-*`、`type-*`、`dual-*`、`position-*`。

## 建议新对话先做

1. 读本交接、最新安装 JSON 与媒体背景记录，确认工作区状态；不要重复安装或擅自重启。
2. 按清单继续迁移精确子项。若继续媒体，先复查局部原型与上游视觉差异，完成配色、
   过渡等剩余项，避免把首批深色缩略图实现冒充完整背景迁移。
3. 重点复查当前媒体异步生命周期、主线程复制开销、材质 clear/reapply、AOD/锁屏
   切换、holder 更换以及两个设置组合；静态回归不等于 Android 运行时正确性。
4. 继续更新正式证据、边界、清单和阶段记录；既有音量/双排/时钟也仍待统一设备验收。
5. 迁移结束后按用户方向进行统一验收；本次安装并未授权重启或启用任何新功能。

常用命令：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease --console=plain
python tools/systemui_inventory.py .workbuddy/refsrc/HyperCeiler
python tools/mt_systemui_probe.py --manifest docs/media-background-systemui-evidence.json
python tools/mt_systemui_probe.py --workspace gqbnd0ih --manifest docs/app-volume-position-evidence.json
git diff --check
```

PowerShell 下 `rg` 使用目录配 `-g` 过滤，不要把 glob 作为路径参数。
