# 功能面板与通知重要程度修复（2026-10-10）

## 按功能块分面板

按用户提供的西米露截图调整布局，保留当前 Miuix 视觉和实际设置键：

- 作用域页、系统界面子菜单和搜索不再按照 hasDetailPage 拆成“功能设置 / 快捷开关”。
  通知、焦点通知、媒体卡片、新控制中心、经典控制中心、音量面板等各自一张面板。
- 状态栏的图标、网络、电池、时钟、手势，以及锁屏的快捷入口、充电、显示、安全等
  使用显式语义归属；每个现有功能恰好归属一次，不因有无二级页面改变面板。
- 行的控件仍保持其含义：直接开关继续直接开关，有额外配置的行继续进入详情。
  本批取消的是以控件类型为依据的分卡规则，不删除有效的二级配置入口。
- 详情页的同名 group 也合并：例如“扩展”的开关与字号/边距放入同一张面板，
  不再因 options/config 数组分离而出现两个“扩展”；未命名组统一“常规”。
- 搜索沿用相同面板归属，并把宿主/子菜单放入分组键，避免合并不同宿主的同名功能块。
  组内保留原声明顺序，组顺序取首次出现顺序，不新增空面板或未实现功能入口。

主要代码：`core/FeaturePanels.kt`、`ui/ScopeDetailPage.kt`、
`ui/HyperSubPages.kt`、`ui/FeatureDetailPage.kt`。
新增回归覆盖功能归属完整性、重复/漏项、开关与入口混排、搜索和详情组一致性。
没有安装新包进行屏幕视觉验收，不能宣称真机布局已验收。

## 通知重要程度：症状与已确认缺陷

用户报告：重要程度选项已出现，但修改后返回再进入仍是原值。
本批只读提取当前设备 `4e2d9aa2` 的 `/system_ext/priv-app/Settings/Settings.apk`：
系统设置版本 `17 / 37`，130431043 bytes，SHA-256
`4774deb4c6fb1a9c00b25d7bf35087b3c7bb7c7860a790b72a8742dad5abd342`。
MT 当前没有 Settings 工作区，本批证据来自 adb pull + 本地 DEX/JADX，不冒称 MT 核验。

确认以下代码问题：

1. 原适配器使用 `getMethod("findSpinnerIndexOfValue", String)` 查找公开方法，但该 ROM
   `miuix.preference.DropDownPreference.findSpinnerIndexOfValue` 是 private。
   当前有 public `findIndexOfValue` 转发，必须使用它。原写法可能在安装阶段直接失败。
2. 当前 `setupChannelDefaultPrefs` 不初始化 importance；真正页面链为 onResume 加载 XML、
   调用 setup、removeDefaultPrefs、updateDependents。只盯私有 setup 且不核验/去优化调用方，
   无法保证可见行与已绑定监听对应。
3. `MiuiNotificationBackend.updateChannel` 捕获并吞掉异常，返回 void；调用没抛异常不等于
   系统已保存。旧代码只改本地 channel 和 backup，没有回读确认。
4. 下拉控件在 callChangeListener 返回 true 后保存自身 value；若只有显示或默认控件持久化，
   并不会替代 NotificationManager 的渠道写入。

只读 Settings 宿主日志最后一次 attach 为 17:03，记录的只有通行密钥 Hook，没有本功能
安装记录。当前模块本地设置 `notification_importance=true`。因此不能据此断言用户看到的
行一定来自本模块当前适配器，也不能把源码缺陷当作已复现全部运行时原因。
没有读取通知内容或修改任何渠道来冒充验收。

## 修复后的保存链

- Settings `17 / 37` 精确版本及完整签名门控；使用 public findIndexOfValue。
- visibility 和 onResume 由唯一 Settings 适配器管理；去优化 onResume/removeDefaultPrefs，
  完成原生 onResume 后再从当前页面绑定 importance。任一 Hook 未完整安装均不启用。
- 仅显露 importance，不再强行显示没有当前绑定的 badge/allow_keyguard 行。
- 禁止下拉控件写入跨应用共享的本地 `importance` 偏好；初始值来自系统渠道。
- listener 持弱页面/行引用，核对当前行、包名、UID、渠道 ID 和 conversation，旧页面回调
  不得改新页面；不使用 mTargetPkg 替代实际渠道所有者，保留 Mi Push/多用户目标信息。
- 修改前读取最新同一渠道；检查原生 configurable/blockable、管理员、ECM、筛选范围和
  enabled 状态。拒绝关闭渠道、miscellaneous 与未知等级；允许选择 1-4。
- Parcel 复制最新渠道，保留其他字段与锁定位，只设置 importance 与 user-lock bit 4。
  通过原生 backend 更新，然后同一包/UID/渠道/conversation 回读。
- 回读等级一致才返回 true、更新 mChannel/mBackupImportance 与依赖控件。
  保存静默失败、回读缺失或异常时返回 false，恢复可确认的选择并提示未保存；不强制重试，
  不用另一次写入“回滚”可能已经保存但回读失败的系统值。
- 安全模式拒绝新修改；不自动撤销已经由用户保存的系统渠道设置。

主要代码：`NotificationImportanceSettingsHooks.kt`、`NotificationImportanceSettingsTargets.kt`、
`NotificationImportanceUpdate.kt`、`NotificationImportanceHooks.kt`。

## 验证与状态

证据：`docs/notification-importance-settings-evidence.json`，35 个 DEX 方法/字段的定义、
访问标志和所属 dex，包含私有/公开方法差异、下拉异步提交、后台写入/读取及页面链。
5 份反编译正文带哈希和断言；不提交整个系统 APK。

```powershell
python tools/settings_importance_probe.py .workbuddy/tmp/Settings-importance.apk
.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease --console=plain
git diff --check
```

最终 439 项单元测试通过（0 失败/错误/跳过），R8 Release 成功，`git diff --check` 通过。
Release APK 哈希见 `docs/panels-importance-build.json`。
测试覆盖静默保存失败、读回缺失、不变值不写入、错误渠道、受限渠道、写异常、复制保留锁位、
精确方法 fixture、私有方法查找回归，以及功能面板全量完整性。
初轮 fixture 生成脚本失败导致 1 个 fixture 测试失败，补齐真实 DEX fixture 后重跑，
不能将初轮失败隐藏为一路通过。

清单仍 337 行；87 pending、132 待审计不变。通知重要程度从“等待 Settings 工件”改为
“已静态修复待真机”，现 19 repaired_static_pending_device、89 有界静态、8 部分静态、
其余 2 个特定边界状态。已有实现和源码修复不等同真机验收完成。

本批未安装、重启/热重载宿主、改变任何开关/作用域/渠道等级/SIM/网络，未提交或推送。
设备部署仍以之前实际安装记录为准，不把本地 APK 当作已加载。
全量 lint 未重跑，历史 22 个问题不宣称解决。

统一验收：主列表/搜索/详情分卡和长标题；普通渠道等级 1-4 保存、返回重进、Settings 重建；
多应用相同渠道 ID、工作资料/双开、conversation、Mi Push、通知关闭/管理员限制；
确认保存失败时不伪显示成功，不误改声音/震动/锁屏/角标等其他渠道字段。
