# 安全组件迁移记录（2026-10-23）

## 范围与结论

本轮继续迁移西米露 HyperCeiler 的安装器、安全中心、Guard、Joyose 与应用商店
功能。系统框架对应功能已由 `0de0442` 批次完成；本轮将安全组件功能从 18 项
补到 24 项，并修复 XSOF Root 环境任务与应用商店广告 Hook 的宿主语义问题。

上游核对基为 `5af3daeff`（`Revert "fix(thermalbrightness): 修正 Hook 目标对象，
修复字段查找失败"`）。截至本记录生成时，上游相关规则目录没有还需要同步的新提交。

本记录中的“完成”只表示代码、宿主靶点和静态验证完成。按照仓库协作约定，本轮没有
安装 APK、重启宿主、切换开关或对设备执行写操作，因此不把构建成功当成设备验收。

## 功能覆盖

| 作用域 | 数量 | 上游入口 |
| --- | ---: | --- |
| 应用包安装器 | 10 | `PackageInstaller.java` |
| Guard | 2 | `GuardProvider.java` |
| Joyose | 2 | `Joyose.java` |
| 安全中心 | 5 | `SecurityCenter.java` 中已确认靶点的安全规则 |
| 应用商店 | 5 | `GetApps.java` |
| 合计 | 24 | `SECURITY_COMPONENT_FEATURES` |

新增的安装器功能：

- `package_installer_disable_safe_mode_tip`
- `package_installer_all_system_app`
- `package_installer_install_source`
- `package_installer_display_more_info`

新增的应用商店功能：

- `market_disable_ads`
- `market_device_modify`

## 宿主取证

### 应用包安装器

通过 MT MCP 只读检查安装器 workspace，版本号为 `55600`。确认并使用以下契约：

- 云检查：`com.miui.packageInstaller.e#c`，成功对象由
  `CloudParams()` 与 `CloudResult$Success(CloudParams)` 构造。
- 风险、广告与完整安全模式：`B0.c#q/r/o`、`f2.n#w/p/r/s`。
- 上传：`com.miui.packageInstaller.util.a#f/g`，参数中包含 `ApkInfo`。
- 安全守护提示：`SafeModeTipViewObject#D(ViewHolder): void`；当前宿主的
  `J2.b` 没有上游旧版 `a` 方法，且存在多个布尔字段，不能照搬旧字段选择。
  当前实现只在该行渲染后隐藏 `ViewHolder.itemView`。
- 系统应用判定：`PackageUtil#u`、`com.xiaomi.onetrack.f.a#a`、`l2.l#d`，
  三者均接收 `ApplicationInfo` 并返回 `boolean`，在原方法执行前补
  `ApplicationInfo.FLAG_SYSTEM`。
- 安装来源：当前 `InstallStart` 没有自己声明 `getCallingPackage`，因此同时
  Hook `Activity#getLaunchedFromPackage` 与 `Activity#getCallingPackage`，
  并用与上游相同的 `InstallStart.onCreate` 调用栈门禁限制生效范围。
- 完整 APK 信息：`ApkInfo` 的 `getFileSize`、`getFileSizeString`、
  `getPackageName`、`getVersionName`、`getVersionCode`、`getPackageInfo`、
  `getInstalledVersionName` 均存在。普通页与风险授权页分别 Hook
  `AppInfoViewObject#D`、`RiskAppInstallAuthAppInfoViewObject#D`；优先写入
  `getTvDes()`，风险页没有该控件时回退到 `getTvAppVersion()`。
- 安装次数与完整安全版本：`RiskControlRules#getCurrentLevel`、`f2.n#s`。

### Guard 与 Joyose

通过 MT MCP 只读确认：

- Guard Root 状态为 `se2#a: boolean`，先完成 `se2` 类初始化，再通过 Unsafe
  写入最终静态结果。
- Guard 应用清单上传为 `ls1#d(..., JSONObject): String`。
- Joyose 云控为 `f.h$a#run(): void`。
- Joyose GPU Tuner 为 `f0.g0#k2(): boolean`。

### 安全中心

通过 MT MCP 只读检查安全中心 workspace，版本号为 `40001365`。

普通 Root 检测点：

- `w4.l#o(): boolean`
- `w4.l#M(): void`
- `w4.k#f(): boolean`
- `com.miui.antivirus.activity.r0#f(): boolean`
- `com.miui.firstaidkit.model.other.SystemRootModel#checkSystemRoot(): boolean`
- `com.miui.securityscan.model.manualitem.PermissionRootModel#checkSystemRoot(): boolean`

XSOF 环境任务链：

- `MiSafetyDetectService#n(Object): void`
- `co.a#a: int`，Root 环境请求的任务类型为 `0xb`
- `co.a#e: boolean`，取消标志
- `co.a#b(): boolean`，只读取 `e`，不会标记取消
- `eo.b#b(co.a): void`，异步进入 Guard 风险管理器

`b()` 是读取器而不是设置器，不能沿用“先调用 `b()` 再判断”的旧写法。当前实现只在
任务类型为 `0xb` 时通过 Unsafe 把 `co.a.e` 写为 `true`，再启动 `eo.b.b(request)`；
随后放行原 `n`，由原方法自己的取消检查直接返回。这样既不会漏掉正常请求，也不会让
原 `n` 再次执行 Root 扫描。若取消字段写入失败，则在风险请求已发出后跳过原 `n`；
若风险任务启动失败，则恢复取消字段并放行宿主原流程。

其他安全中心功能：

- 安装拦截：`ff.c#t(): boolean`
- ADB 安装联网校验：`AdbInstallVerifyActivity#z0(): void`、
  `AdbInstallVerifyActivity$b#a([Void]): String`
- 支付安全自动扫描：`w4.l#n(): boolean`
- 举报入口：`ApplicationsDetailsFragment#q2(Context, Uri): boolean`

当前系统框架 DEX 没有 `DisableVerifyCanBeDisabled` 所需的 `canBeDisabled` 靶点，
因此不登记对应功能，也不创建空 Hook；这一点不是遗漏，而是避免虚报覆盖。

### 应用商店

通过 MT MCP 只读检查应用商店 workspace，版本号为 `40007661`。

- 广告：`AppDetailV3`、`SplashManager`、`DetailSplashAdManager` 的目标布尔
  方法与 `tryToRequestSplashAd` 均存在。布尔方法严格按上游语义先执行原方法，
  再把返回值替换为固定值；`tryToRequestSplashAd` 则直接跳过原请求。
- 设备标识：`com.xiaomi.market.MarketApp` 当前只有无参构造器。`Build.DEVICE`、
  `Build.MODEL`、`Build.MANUFACTURER` 都是进程级 `static final String`，在
  `MarketApp` 构造前通过 Unsafe 写入并逐项回读。任一字段写入失败时只回滚仍等于
  本次替换值的字段，避免覆盖其他模块随后写入的值。
- 安全页、安装监控、启动推送：沿用已确认的 `AppSecurityCheckManager`、
  `MyPackageMonitor#init`、`NotificationRecallController` 靶点。

## 实现边界

- 所有宿主类都通过作用域 `ClassLoader` 反射加载；靶点缺失只记录警告，不阻止同
  作用域其他功能安装。
- 安装来源在配置为空时不安装 Hook。
- 设备标识只覆盖非空字段，留空字段保持宿主原值。
- 完整 APK 信息复用原生控件，不注入模块布局或资源。
- 广告 Hook 只覆盖应用商店自身的广告判断，不承诺拦截第三方广告 SDK。
- XSOF 修复只影响任务类型 `0xb`，不会改写其他 XSOF 请求。

## 验证

- `:app:compileDebugKotlin`：通过。
- `:app:testDebugUnitTest`：912 项通过。
- 新增 `PackageInstallerDisplayFormatterTest`，覆盖文件大小单位、版本迁移、
  SDK 与缺失字段回退。
- `SecurityComponentFeaturesTest` 校验 24 项唯一 ID、作用域、分组与可配置项。
- 最终发布构建、脱敏检查和差异检查结果在提交前另行复核。
