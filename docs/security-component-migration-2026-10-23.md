# 安全组件迁移记录（2026-10-23）

## 范围与结论

本轮继续迁移西米露 HyperCeiler 的安装器、安全中心、Guard、Joyose、信任服务、
云服务、统计服务、HTML 查看器与应用商店功能。系统框架对应功能已由 `0de0442`
批次完成；安全组件功能已补到 77 项，并修复 XSOF Root 环境任务、应用商店广告、
安全分数和恶意应用通知 Hook 的宿主语义问题。

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
| 信任服务 | 1 | `SecurityCenter.java` 中的 MRM 初始化规则 |
| 云服务 | 1 | `SecurityCenter.java` 中的同步类别规则 |
| 统计服务 | 1 | `SecurityCenter.java` 中的使用量上报规则 |
| HTML 查看器 | 1 | `SecurityCenter.java` 中的云控数据更新规则 |
| 安全中心 | 54 | `SecurityCenter.java` 中已确认靶点的应用、隐私、维护、电池与侧边栏规则 |
| 应用商店 | 5 | `GetApps.java` |
| 合计 | 77 | `SECURITY_COMPONENT_FEATURES` |

新增的安装器功能：

- `package_installer_disable_safe_mode_tip`
- `package_installer_all_system_app`
- `package_installer_install_source`
- `package_installer_display_more_info`

新增的应用商店功能：

- `market_disable_ads`
- `market_device_modify`

相对上一版 24 项，本轮新增 17 项，合计 41 项：

- 跨作用域：`trustservice_disable_mrm`、`micloud_service_list`、
  `analytics_fuck_guard`、`html_viewer_disable_cloud_control`。
- 安全中心：`security_center_disable_root_check_item`、
  `security_center_simlock_auth_bypass`、`security_center_skip_countdown`、
  `security_center_hide_xopt_tip`、`security_center_lock_thumbnail_blur`、
  `security_center_simplify_home`、`security_center_disable_miaoxiang_power_saver`、
  `security_center_disable_offline_info`、`security_center_hide_simlock_success_dialog`、
  `security_center_hide_low_battery_warning`、
  `security_center_unlock_low_temp_endurance`、
  `security_center_unlock_smart_charge`、
  `security_center_unlock_super_wireless_charge`。

在 41 项基线之上，本轮再新增 36 项安全中心功能，合计 77 项：

- 安全与安装：`security_center_lock_score`、
  `security_center_disable_malicious_app_notification`、
  `security_center_applock_pin_scramble`、
  `security_center_remove_open_app_confirmation_popup`、
  `security_center_unlock_car_sickness`、
  `security_center_disable_auto_turn_accessibility_off`。
- 应用管理：`security_center_app_default_sort`、`security_center_app_restrict`、
  `security_center_app_disable`、`security_center_app_details`、
  `security_center_app_default_setting`、`security_center_aosp_app_info`、
  `security_center_aosp_app_manager`。
- 电池与充电：`security_center_show_battery_temperature`、
  `security_center_show_battery_health`、`security_center_unlock_screen_time`、
  `security_center_power_consumption_ranking`、
  `security_center_show_more_battery_info`。
- 视频与游戏工具箱：`security_center_disable_remove_screen_hold_on`、
  `security_center_remove_vb_temp_limit`、`security_center_dolby_open`、
  `security_center_game_performance_wild_mode`、`security_center_remove_game_toast`、
  `security_center_unlock_gun_service`、`security_center_remove_macro_black_list`、
  `security_center_unlock_memc`、`security_center_unlock_s_resolution`、
  `security_center_unlock_enhance_contours`、`security_center_unlock_new_vb`、
  `security_center_sidebar_line_color`。
- 气泡、前置摄像与隐私：`security_center_remove_conversation_bubble_settings_restriction`、
  `security_center_unlock_side_hide_freeform`、`security_center_beauty_light_auto`、
  `security_center_beauty_face`、`security_center_beauty_privacy`、
  `security_center_beauty_pc`。

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

安全分数与恶意应用通知：

- `com.miui.securityscan.scanner.ScoreManager` 的 `q(): int`、`s(): int`、
  `j(): int`、`C(): boolean` 与 `c(y4.g): void` 共同组成风险汇总链。
  Hook 分别固定扣分、总分、风险状态和风险项写入结果。
- `com.miui.securityscan.ui.main.MainContentFrame#onClick(View): void` 会在满分状态
  继续打开扣分项详情，因此只在当前安全分数功能开启时跳过该入口。
- `w4.h#i(org.json.JSONObject): void` 是危险应用常驻通知的独立副作用入口；
  跳过原方法可阻止通知，不关闭扫描服务。

本轮补充的安全、隐私、维护与电池靶点：

- 扫描项 Root 开关：`w4.l#o(): boolean`。方法体读取
  `key_check_item_root`，Hook 固定返回 `false`；它与 `w4.l#M(): void` 所属的
  XSOF 环境任务分开控制。
- SIM 锁账号校验：`com.miui.simlock.SimLockUtils#m(Context): boolean`。
  方法体调用 `ExtraAccountManager.getXiaomiAccount`，Hook 固定返回 `true`。
- SIM 锁验证成功页：
  `com.miui.simlock.activity.SuccessDialogActivity#onCreate(Bundle): void`。
  在执行前结束 Activity，不进入宿主后续提示流程。
- 危险选项倒计时：
  `com.miui.permcenter.privacymanager.model.InterceptBaseActivity#onCreate(Bundle): void`。
  按上游写入 `KET_STEP_COUNT=0` 与 `KEY_ALLOW_ENABLE=true`，保留宿主页面本身。
- XOpt 提示：`com.miui.permcenter.v#j(Context, String): boolean`。该方法是当前
  安全中心里唯一使用 `get third desktop provider exception!` 的布尔方法，固定返回
  `true`。
- 模糊预览图：`ThumbnailBlurUtils#d/e(Context, String, boolean): void`。两个方法
  都使用 `miui_recents_privacy_thumbnail_blur`；只在调用栈来自
  `PrivacyThumbnailBlurSettings` 时放行，避免阻止用户主动修改名单。
- 首页精简：`com.miui.common.card.CardViewRvAdapter#addAll(List): void`。过滤
  `FuncListBannerCardModel` 与 `PopularActionCardModel`。
- 妙享省电：`sh.c#b(String, Intent, Context): void`。该方法是当前宿主里唯一使用
  `hang_up_enable` 的方法，跳过它即可阻断挂断广播触发的省电切换。
- 网络助手离线状态：
  `com.miui.networkassistant.ui.bean.OffLineData$BaseData`。构造后写入
  `isOffline=false`，同时将同名 getter 固定为 `false`。
- 低电量提示：设置进程内的
  `android.provider.Settings$System#getInt/getString`。仅把
  `low_battery_dialog_disabled` 改为 `1`，把 `low_battery_sound` 改为 `null`。
- 低温极限续航：`oh.g#t(Context): boolean`。该方法是全包唯一使用
  `low_temperature_extreme_endurance_support` 的方法。
- 智能充电：`yh.c0#H()`、`yh.p#b()`、`yh.u#c(int)`、`yh.u#d(int)`、
  `com.miui.powercenter.charge.protect.e#h()`、
  `com.miui.powercenter.charge.protect.e#k()`、
  `com.miui.gamebooster.utils.o0#X()`。七项与宿主中使用
  `persist.vendor.smartchg` 的布尔方法逐一对应。
- 超级无线充电：`wh.a#c(): boolean` 使用
  `persist.vendor.tx.speed.control`；`com.miui.powercenter.l#T1(boolean): void`
  使用 `key_is_connected_super_wls_tx`。前者解除能力判断，后者把连接提示状态
  固定为真。

本批次其余已核对靶点：

- 应用管理：默认排序由应用列表 Activity 的排序参数入口改写；系统应用限制、
  停用菜单、详情页、默认打开、AOSP 应用信息与应用管理入口均按当前宿主的
  `AppDetails`、`ApplicationInfo` 和菜单回调形状单独安装。
- 电池与充电：温度读取宿主电池广播，健康度读取宿主已有统计；
  屏幕使用时间、功耗排行和更多电池信息分别对应省电页的独立能力或偏好分类。
- 视频与游戏工具箱：`VideoToolboxTempMonitor`、`DockWindowManagerService`、
  `mb.x`、`mb.q`、`GameToastWindowManager`、`GunService`、
  `gamebooster.utils.s1` 与 `ea.b` 的当前方法签名均先静态核对后安装。
- 气泡与前置摄像：气泡候选、活动气泡查询和序列化入口分别处理；
  前置补光、隐私摄像头与人像居中按能力 getter/写入方法分别放行。
- 侧边栏线条颜色：使用当前安全中心
  `0x7f060e99`、`0x7f060e9a`、`0x7f060e9b` 资源，通过
  `Resources#getColor(int)` 与 `getColor(int, Theme)` 覆盖。
- 未迁移的三项侧边栏整项及其宿主取证边界见
  `security-sidebar-gap-audit-2026-10-24.md`。它们不登记为已覆盖功能。

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
- `:app:testDebugUnitTest`：921 项通过。
- 新增 `PackageInstallerDisplayFormatterTest`，覆盖文件大小单位、版本迁移、
  SDK 与缺失字段回退。
- `SecurityComponentFeaturesTest` 校验 77 项唯一 ID、作用域、分组与可配置项。
- 最终发布构建、脱敏检查和差异检查结果在提交前另行复核。
