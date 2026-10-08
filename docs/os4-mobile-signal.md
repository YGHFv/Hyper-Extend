# OS4 移动信号显示逻辑

## 来源与静态校对

- 参考：HyperCeiler `main` 本地版本 `5e46860`，`MobilePublicHookV.kt` 的 OS4 分支、`MobileNetworkSettings.java` 与对应 XML/中文字符串。
- 校对 APK：`mcp/MiuiSystemUI.apk`，SHA-256 `7DA817A4C65299F7362FD530D72C09C0A4785C98CF2732395C721F2740E7662A`。
- `MiuiMobileIconBinder.bind(ViewGroup, LocationBasedMobileViewModel, MiuiMobileIconViewModel, MobileViewLogger)` 的第三个参数是 VM。
- `MiuiMobileIconVMImpl.isVisible` 声明为宿主 `StateFlow`；`iconInteractor.subId` 标识订阅，不能当作卡槽号。
- Binder 收集 `isVisible()` 并将值转为宿主 `kotlin.Pair<Boolean, Boolean>`；不使用模块自己的 Pair 或协程类型。
- 在原 bind 之前替换字段，以兼容视图已 attach 时同步启动收集。每个 VM 保留同一条可变流，刷新只更新流的值。
- `StateFlowKt.MutableStateFlow(Object)` 存在，但 `asStateFlow` 已被内联移除；通过 `ReadonlyStateFlow(MutableStateFlow)` 构造包装。
- `JavaAdapterKt.collectFlow(View, Flow, Consumer)` 提供跟随 attach/detach 的原始流收集，其返回句柄有 `dispose()`。默认模式或查询失败时恢复原始 Pair，不将系统两个标志强行合并。

MT MCP 在本次验证时拒绝连接，因此用本地 APK 的 dex 索引及 JADX 单类反编译校对；没有装机或修改设备状态。

## 行为与生命周期

| 显示逻辑 | 行为 | 隐藏指定卡槽 |
| --- | --- | --- |
| 默认 | 保留宿主 Pair 的原始两个值 | 支持 |
| 非 WiFi 下始终显示 | WiFi 连接时隐藏，否则显示；飞行模式下隐藏 | 支持 |
| 仅在连接时显示 | 默认网络为蜂窝连接时只显示上网卡 | 禁用 |
| 仅显示上网卡 | 只显示默认上网卡，与联网状态无关；飞行模式下隐藏 | 禁用 |

上游界面允许非 WiFi 档与隐藏卡槽组合，但其 Hook 的分支优先级会忽略隐藏卡槽；本实现修正为可叠加。
后两档不删除已保存的隐藏配置，返回前两档时恢复。导入冲突配置也按相同规则处理。

- 监听默认网络回调、订阅变化、上网卡/SIM/飞行模式/用户切换广播以及飞行模式设置变化，仅读取设备状态。
- 切卡时重新通过 `SubscriptionManager.getSlotIndex(subId)` 解析卡槽；没有将订阅 ID 固定映射成 SIM 1/2。
- 所有流更新在主线程执行；重复 bind 不重复替换流。状态栏与控制中心的不同 VM 都会刷新。
- 最后一个视图 detach 后注销网络、订阅、广播与设置监听；重新 attach 时先读取当前状态。没有定时轮询。
- 默认模式下未隐藏的卡保留系统行为；查询失败时保留原始值，目标签名不匹配则跳过并记录日志。

## 自动测试

`gradlew.bat testDebugUnitTest assembleRelease`

新增测试涵盖四档模式、隐藏卡槽组合、无效订阅、飞行模式、网络断连、切换上网卡、SIM 槽位变化、权限/状态不可用、设置注册和 UI 依赖规则。
另用独立 ClassLoader 模拟 OS4 精简后的协程 API，验证不依赖 `asStateFlow`，且可变流更新不会更换只读包装对象。

这些测试不等同于 Android/LSPosed 真机运行验证。

## 待用户真机验收

安装 release APK，在系统界面作用域的「状态栏 → 移动网络」启用功能。每次修改模块选项后重启系统界面；下面的系统状态切换由用户手动完成。

1. 默认模式：分别隐藏 SIM 1、SIM 2、两卡；检查状态栏和控制中心，确认通话与联网未被停用。
2. 非 WiFi 档：连接/断开 WiFi，检查信号图标隐藏/恢复；叠加隐藏 SIM 1/2，确认不会重新露出指定卡。
3. 仅在连接时显示：切换蜂窝数据开关、WiFi 和默认上网卡；只在蜂窝默认网络连接时显示对应上网卡。
4. 仅显示上网卡：在联网与不联网状态下切换默认上网卡；始终只显示所选卡，飞行模式开关后正常恢复。
5. 隐藏配置留存：后两档显示禁用的隐藏卡选项，切回默认/非 WiFi 档恢复保存值。
6. 单卡、拔插卡/eSIM 启停、横竖屏、锁屏解锁、反复开合控制中心后不出现卡号串位、图标卡死或重复监听。
7. 关闭模块的移动网络功能并重启系统界面，确认恢复系统原始显示。
