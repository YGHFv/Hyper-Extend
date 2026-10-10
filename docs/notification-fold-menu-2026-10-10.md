# 禁止自动折叠：移除长按收纳入口（2026-10-10）

## 行为与边界

沿用默认关闭的 `notification_disable_auto_fold`，不新增开关或作用域：

- 开启后阻止自动折叠，同时不生成普通通知长按菜单的“收纳到更多通知”。
- 在原生 `createMenuViews` 判断资格时返回 false，而不是事后隐藏 View；原生负责
  清空/复用条目、计算宽度、重新添加视图及无障碍节点，不留下收纳入口的空位。
- 已折叠项保留原生移出入口；不强制移动已有通知，不写入系统保存的手动归类。
- 不影响菜单里的通知设置、关闭通知等其他操作，不全局禁用 `canCustomFold`。
- 自动折叠仍只覆盖新增、更新、收起、闹钟四条调用路径；不改隐私过滤和渠道重要程度。
- 关闭开关并在宿主下一次正常加载配置后恢复原生行为；本批没有主动重启或热重载。

## 当前 ROM 与上游差异

只读 MT 工作区 `wot5ikcq`：SystemUI `17.03.260226.r / 202602260`。
上游参考 HyperCeiler `5e4686069dd7ab1f3697e256d5fc7d68fb73e317` 的
`UnimportantNotification.java` 使用全局 `shouldIgnoreEntry` + 清空待折叠列表。
当前 ROM 的 `canCustomFold` **并不调用** `shouldIgnoreEntry`，因此照搬上游函数
不能保证菜单入口消失。本批按用户要求补足菜单行为，不宣称上游所有副作用完全一致。

全 DEX smali 搜索无分页/跳过：

- `shouldIgnoreEntry`：4 处调用，均为上述自动折叠路径。
- `canCustomFold`：菜单生成和 FoldCoordinator 的筛选 lambda 两处；后者保持原生。
- `createMenuViews`：`createMenu(ViewGroup)` 和 `onNotificationUpdated()` 两处。

`NotificationFoldHooks` 在菜单生成域中按 ExpandedNotification **对象身份**限制资格
拦截，仅 `mIsFold=false` 时生效。ThreadLocal 在嵌套调用/异常后恢复，不污染其他线程。
未知/已折叠状态保持原生。去优化两个菜单调用方、菜单生成及四个自动折叠调用方，
避免 ART 内联绕过；完整解析字段和精确方法后安装，7 个 Hook 未全部成功则整体不启用。
字段核验发现 `mPendingNotifications` 实际声明为 `java.util.List`，不是 ArrayList；
已按真实类型检查。首次候选字段探测失败后修正并完整重验，未将猜测当作有效证据。

## 验证与安装

- 14 个宿主方法/字段实时复核：`docs/notification-fold-menu-evidence.json`。
- 新增 9 项回归：菜单作用域、折叠/未知状态、嵌套与异常、线程隔离、对象身份、
  单次原生调用、9 个精确宿主方法签名以及开关说明。
- 全量 **448 项测试通过，0 失败/错误/跳过**；R8 Release 成功。
- 构建记录：`docs/notification-fold-menu-build.json`；本机日志 `build/fold-menu-build.log`。
- 18:48 覆盖安装 `0.1.3 / 4` 成功，本地与设备 APK SHA-256 一致：
  `ef684531b1126d8c178316645e15b6e9e6e4ad62bf6e5a060eacd59b3e815696`。
- 安装前后采样 system_server `3641`、SystemUI `16055`、Settings `23704`、MiSound `905`
  均不变，独立插件进程未运行；采样不代表以后不会退出。
- 安装记录：`docs/notification-fold-menu-install.json`。未主动重启/热重载、改变功能开关、
  作用域、渠道等级或 SIM/网络，**不确认新 Hook 已加载，未做运行时验收**。
- 全量 lint 未重跑，历史 22 个错误没有在本批解决。

这是已有行的行为补齐，不虚减迁移待办。总 337 条配置/导航/依赖记录：pending 87、
待审计 132、有界静态 89、修复待真机 19、部分静态 8、其他特定边界 2；不是功能数。

统一真机验收时检查：开关关闭的原生菜单；开启后的普通/分组/自定义通知长按菜单和宽度；
通知更新、重新打开、旋转后不复现入口；TalkBack 不再聚焦收纳项；已折叠项仍可移出；
通知设置/关闭通知正常；自动新增/更新/收起/闹钟路径；原有持久归类和隐私限制未改动。
菜单移除不能代替上述运行时验收。
