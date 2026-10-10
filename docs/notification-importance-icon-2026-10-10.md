# 重要程度已可调，但低等级状态栏图标未隐藏（2026-10-10）

## 用户反馈与日志

用户在双页面修复后确认“现在可以调整重要程度”，但调成“低”仍显示状态栏图标，
要求与西米露一致。本批读取设备日志确认：

- 19:10:23 Settings PID 10953 已安装两个页面 resume Hook，共 importance=3。
- 19:10:29、19:10:38、19:12:04 新 `notification.app.ChannelNotificationSettings`
  均有 `listener bound level=1`，证明上批漏适配页面已进入绑定，不再只有可见选项。
- 抓到的日志尚无 selection/save 事件，不能冒称亲眼验证了每次系统写入。
  用户确认可调与页面读到等级 1 是本批依据，不等同全场景持久化验收。
- SystemUI 19:10:22 已安装旧 importance=1（仅 stats）代码，所以不是简单没重载的问题。
- Settings 资源 `importance_min` 在 zh-rCN 是“低”，对应值 **1**；Android 常量
  IMPORTANCE_LOW=2 在这版中文界面显示“中”。本修复不误把等级 2 也全部隐藏。

## 根因与上游差异

HyperCeiler `NotificationImportanceHyperOSFix.kt` 在 StackCoordinator$attach$1 的
onAfterRenderList 前过滤代表项 ranking.importance <= 1。
本模块此前为了不误删共享渲染列表，只在 calculateNotifStats 过滤统计项。
当前 ROM 的 onAfterRenderList 除统计外，还把原列表交给
RenderNotificationListInteractor.setRenderedList，进而生成供图标使用的
ActiveNotificationListRepository.activeNotifications。**只改统计不能过滤图标。**

本批没有直接复制“把低等级整个渲染项删掉”的做法，以免静默分组代表项带走重要子通知，
或改变所有 activeNotifications 消费方。采用当前 ROM 原生图标抑制标志的有界适配。

## 修复实现

- 新 `NotificationImportanceIconHooks` 仍受 `notification_importance` 同一开关控制。
- ActiveNotificationsStoreBuilder.toModel(NotificationEntry) 读取
  shouldSuppressVisualEffect(32)，再生成/复用模型的 isSuppressedFromStatusBar。
- 仅在当前 toModel 的同一 NotificationEntry 对象调用域内，对 effect=32 且 ranking
  等级 0/1 返回 true；其他 effect、等级、线程和对象保持原生，不修改 Ranking 本身。
- 不全局改变 DND、不取消通知、不移除共享渲染列表，不更改通知面板/声音/震动字段。
  状态栏图标筛选原生读取 suppression，分组/聚合的原生规则保留。
- 在模型的缓存比较之前改变输入，避免修改已发布模型导致 StateFlow 相等性忽略更新；
  等级恢复后由原生重新计算，不保存“隐藏过”的持久名单。
- 去优化 toModel 及两个确证调用方（分组转换、render lambda），两个 Hook 未全部成功
  就不启用新拦截；ThreadLocal 嵌套/异常后恢复，不泄漏上下文。
- 日志首次命中输出 `low-ranking status-bar icon suppression reached`，不记录通知内容。
- 新实现仅对齐用户要求的低等级图标行为，不宣称上游整个列表裁剪的副作用完全一致。

已实时核验 10 个 SystemUI 方法：`docs/notification-importance-icon-evidence.json`。
状态栏消费者 showAmbient=false 会使用 suppression；共享模型的其他原生消费者仍按
各自参数处理。AOD/分组/聚合的最终表现尚未真机验收，不宣称全部完成。

## 验证与部署

- 新增 6 项回归：等级边界、effect 限制、对象身份、嵌套异常、线程隔离、单次调用和签名。
- **455 项测试全部通过，0 失败/错误/跳过**，R8 Release 成功。
- 19:29 覆盖安装 `0.1.3 / 4` 成功，2696133 bytes，本地和设备 SHA-256 一致：
  `41001fbbc4db487523af392526446e73ceafc286494db923b483f2a21565ab63`。
- 安装前后 system_server `3641`、SystemUI `10580`、Settings `10953`、MiSound `905`
  均不变；未主动重启/热重载、改开关/作用域/通知等级。
- **尚未确认新增图标 Hook 已加载，未真机确认图标消失/恢复。**
- 记录：`docs/notification-importance-icon-build.json`、`docs/notification-importance-icon-install.json`。
- 暂存完整累计变更时发现旧未跟踪 VisualConfigRows.kt 文件末尾多余空行，已只清理空行；
  之前未暂存的 diff check 不覆盖该文件，本批重新执行 cached diff check。
- 全量 lint 未重跑，历史 22 个错误没有在本批解决。

后续验证先确认 SystemUI importance=3（图标两 Hook + stats 一 Hook）和首次命中日志，
再检查中文“低”=1 图标隐藏、升回“中/默认/高”恢复、通知卡片仍在、更新/撤回正常、
分组代表项与混合子项、聚合图标、AOD、隐私/DND 和其他图标功能组合。
重要程度清单行继续标为 repaired_static_pending_device，不减少 pending 87 / 待审计 132。
