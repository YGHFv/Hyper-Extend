# 通知重要程度仍不保存：双渠道页面修复（2026-10-10）

## 设备日志与真实入口

用户在 18:48 包安装后再次报告“选择不生效，返回再进入仍为旧等级”。
本批读取 Settings 的模块镜像日志；logcat 的 HyperExtend 输出为空，不能用空 logcat
判断 Hook 未安装。未修改渠道、开关或作用域，也没有主动重启 Settings/SystemUI。

Settings 日志的 18:49:10 会话（PID 706）确认新代码已加载：

```text
hook installed: notification_importance/settingsVisibility
hook installed: notification_importance/settingsResume
dispatch[com.android.settings] -> notification_importance=2, settings=3 | hooks=5, failed=0
```

但没有任何 `Settings importance listener bound` 或保存失败日志。该日志支持“安装了
Hook，但尚无保存监听绑定记录”，不支持直接认定 Binder 写入失败。

只读检查西米露配置发现 `prefs_key_system_settings_more_notification_settings=true`。
上游 MoreNotificationSettings 全局放行 BaseNotificationSettings 的 importance、badge、
allow_keyguard，并只在旧 `notification.ChannelNotificationSettings.setupChannelDefaultPrefs`
后设置保存监听。因此其他页面也可能显示重要程度，但没有对应保存代码。
本批没有替用户关闭西米露或断言它是唯一原因。

19:03 左右活动记录捕获：Settings SubSettings PID 706 为前台，同任务上一页为
`com.android.settings.notification.app.ChannelPanelActivity`。随后用户离开该页面，未获得
其 Fragment 运行时 dump。Settings 当前 APK 静态正文确认该入口：

- `ChannelPanelActivity.launchFullSettings()` 使用同包
  `notification.app.ChannelNotificationSettings.class.getName()` 创建 SubSettings。
- 嵌入面板也直接构造同包 `ChannelNotificationSettings`。
- APK 内存在两套渠道页：`notification.ChannelNotificationSettings` 与
  `notification.app.ChannelNotificationSettings`，都是 BaseNotificationSettings 的直接子类，
  各有独立 onResume/removeDefaultPrefs/updateDependents，不是继承转发关系。
- 上版只挂旧类 onResume，visibility 又用旧类 isInstance 限制，新入口永远不会绑定监听。
  结合真实入口与日志，这是已确认的漏适配链；不把它夸大成已证明所有设备保存失败原因。

## 对照西米露后的修复

西米露保存方法是：listener 读取下拉值 → mBackupImportance → mChannel.setImportance →
lockFields(4) → backend.updateChannel(mPkg,mUid,channel) → updateDependents(false)。
其反射 helper 可查 private findSpinnerIndexOfValue，与 Java getMethod 不同；直接照搬
旧页类名和全局显示会留下新页的空操作。并且上游失败后仍返回 true，没有服务器回读。

本次保留已实现的同类保存链和更严格确认，修复页面覆盖：

- 两套渠道页各挂 onResume，共用一个 Base visibility Hook，避免重复覆盖同一方法。
- 两套 removeDefaultPrefs/onResume 均去优化，完整 3 个 Hook 安装成功才启用。
- 字段统一从真实 Base 类解析；保存后的 updateDependents 按页面实例选所属类方法，
  不把旧页 Method 错误调用在新页实例上。
- 仍只改重要程度及 user-lock bit 4，复制最新服务端渠道并回读确认，不突破管理员/ECM/
  configurable/blockable/filter，不修改角标、锁屏、声音、震动等其他字段。
- 记录页面类、绑定等级、跳过原因、请求等级和回读等级，不记录目标包/渠道名或通知内容。
- 受限/无对应值的行若被其他模块显示则禁用，避免留下看似可编辑却没有保存监听的控件。
- 没有改为“吞错也返回 true”，没有自动重试、写回旧渠道或修改全局通知策略。

额外确认 XML 的 importance 原本已 `persistent=false`，entryValues 为 4/3/2/1；
之前的 setPersistent(false) 只是防御性保持，不是本次根因。

## 构建、部署与待验收

- Settings `17 / 37` APK 哈希不变，42 个 DEX 成员与 9 份正文/资源证据已记录在
  `docs/notification-importance-settings-evidence.json`；不是 MT Settings 工作区证据。
- **449 项测试通过，0 失败/错误/跳过**，R8 Release 成功，diff check 通过。
- 19:09 覆盖安装 `0.1.3 / 4`；本地和设备 SHA-256 一致：
  `afd41c10cdcbe9ed7ecf68cdd6bd2463a52e8a6f5045b77aa4d2b742a84fb5e3`。
- 安装前后采样 system_server `3641`、SystemUI `26550`、Settings `706`、MiSound `905`
  不变。18:48 之后 SystemUI 曾更换 PID，本批未发重启命令，原因未查明。
- Settings 仍为旧会话 PID 706，**尚未确认双页面修复加载，未确认保存返回重进成功**。
- 安装记录：`docs/notification-importance-panel-install.json`；
  构建记录：`docs/notification-importance-panel-build.json`。
- 原始日志/完整活动 dump 仅留在忽略的 `.workbuddy/tmp`，不提交无关应用信息。

下一次用户安排验证时，先确认最新 Settings 会话 dispatch 为 notification_importance=3，
再确认实际页面出现 listener bound，用户选择后有 selection/save confirmed=true 且 actual
等于请求等级，返回重进仍一致。若没有绑定，用明确的 bind skipped 原因继续定位。
两套入口、同名跨应用渠道、工作资料/conversation/Mi Push、被禁用/受管渠道都需覆盖。
目前迁移统计不变，仍属 repaired_static_pending_device，而不是运行时验收完成。
