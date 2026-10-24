/*
 * Copyright (C) 2026 YGHFv
 *
 * 本文件是「澎湃补全计划」（HyperExtend）的一部分。
 *
 * 本程序是自由软件：你可以依据 GNU Affero 通用公共许可证（由自由软件基金会发布）
 * 的条款重新发布和/或修改它，无论是许可证的第 3 版，还是（由你选择）任何更新的版本。
 *
 * 本程序基于「希望它有用」而分发，但不提供任何担保；甚至不包括对适销性或特定用途
 * 适用性的默示担保。详见 GNU Affero 通用公共许可证。
 */

package io.github.YGHFv.HyperExtend.core

/**
 * 作用域宿主 —— `META-INF/xposed/scope.list` 里每一项在界面上的化身。
 *
 * hook 不是「装在模块里」，而是**装进某个宿主进程里**。同一个开关在不同宿主里的生效方式不同：
 * 系统界面杀掉就自己回来，普通应用要等用户下次打开，system_server 只能随设备重启。
 * 把差异摊在界面的第一层，用户才知道「我拨了这个开关，接下来要做什么」。
 *
 * [id] 是内部标识，[process] 是真实进程名（重启命令直接用它），两者**不要合并**。
 */
data class HyperScope(
    val id: String,
    /** 界面上的名字。用系统里叫得响的那个（「系统框架」而不是「system_server」）。 */
    val title: String,
    /** 这个宿主负责什么。只出现在它自己的页面上，不要在别处重复。 */
    val summary: String,
    /** 真实进程名 / 包名。重启命令直接用它。 */
    val process: String,
    val restartKind: RestartKind,
    /** 取桌面图标用的包名。只有 system_server 例外（进程名 `system` 不是一个可查的包）。 */
    val iconPackage: String = process,
)

/**
 * 让改动生效需要动宿主的哪个进程。
 *
 * 三种情况的**危险程度**和**用户预期**完全不同，混成一个 `needsReboot` 会让
 * 「杀掉状态栏」和「重启整台设备」在代码里长得一样。
 */
enum class RestartKind {
    /** 杀掉进程，系统会立刻把它拉起来。只有 SystemUI 这类常驻进程适用。 */
    KILL,

    /** 结束进程，用户下次打开该应用时自然重启。普通应用适用。 */
    FORCE_STOP,

    /** 进程无法单独重启（它就是整机的宿主），只能随设备重启。 */
    REBOOT,
}

/**
 * 全部作用域宿主。**顺序即首页入口行的顺序。**
 *
 * 系统框架排在最前：它是整机所有应用的宿主，影响面最大，凡是「同时作用于系统框架和其他应用」
 * 的功能，入口也归到它下面（见 [entryScope]），所以它天然该是列表第一项。
 * 系统界面第二，其后依次为系统管理、常用系统工具、互联服务和独立应用。
 *
 * 这个顺序**只影响排序**，不影响功能归属 —— 归属由各功能自己的 `scopes` 顺序决定。
 */
val SCOPES: List<HyperScope> = listOf(
    HyperScope(
        id = "system_server",
        title = "系统框架",
        summary = "整机所有应用的宿主进程，凭据会话等系统级服务都在这里",
        process = "system",
        restartKind = RestartKind.REBOOT,
        iconPackage = "android",
    ),
    HyperScope(
        id = "systemui",
        title = "系统界面",
        summary = "状态栏、通知面板、锁屏，以及屏幕底部的手势横条",
        process = "com.android.systemui",
        restartKind = RestartKind.KILL,
    ),
    HyperScope(
        id = "launcher",
        title = "桌面",
        summary = "桌面快捷菜单与应用图标操作",
        process = "com.miui.home",
        restartKind = RestartKind.FORCE_STOP,
    ),
    HyperScope(
        id = "settings",
        title = "系统设置",
        summary = "设置首页入口、通知权限、连接与开发者选项",
        process = "com.android.settings",
        restartKind = RestartKind.FORCE_STOP,
    ),
    HyperScope(
        id = "securitycenter",
        title = "安全中心",
        summary = "权限、隐私、安装与支付安全的守门人",
        process = "com.miui.securitycenter",
        restartKind = RestartKind.FORCE_STOP,
    ),
    HyperScope(
        id = "packageinstaller",
        title = "应用包安装器",
        summary = "APK 安装流程中的云检查、风险提示与安装器广告",
        process = "com.miui.packageinstaller",
        restartKind = RestartKind.FORCE_STOP,
    ),
    HyperScope(
        id = "guardprovider",
        title = "系统安全检测",
        summary = "Root 环境检测与应用清单上传",
        process = "com.miui.guardprovider",
        restartKind = RestartKind.FORCE_STOP,
    ),
    HyperScope(
        id = "joyose",
        title = "Joyose",
        summary = "云端性能策略与 GPU Tuner 状态",
        process = "com.xiaomi.joyose",
        restartKind = RestartKind.FORCE_STOP,
    ),
    HyperScope(
        id = "trustservice",
        title = "小米信任服务",
        summary = "设备风险检测与信任服务状态",
        process = "com.xiaomi.trustservice",
        restartKind = RestartKind.FORCE_STOP,
    ),
    HyperScope(
        id = "cloudservice",
        title = "小米云服务",
        summary = "云同步数据类别与云端配置",
        process = "com.miui.cloudservice",
        restartKind = RestartKind.FORCE_STOP,
    ),
    HyperScope(
        id = "analytics",
        title = "统计服务",
        summary = "应用使用统计与遥测上报",
        process = "com.miui.analytics",
        restartKind = RestartKind.FORCE_STOP,
    ),
    HyperScope(
        id = "htmlviewer",
        title = "HTML 查看器",
        summary = "设置云控数据更新任务",
        process = "com.android.htmlviewer",
        restartKind = RestartKind.FORCE_STOP,
    ),
    HyperScope(
        id = "market",
        title = "应用商店",
        summary = "应用安全检测、安装监控与启动推送",
        process = "com.xiaomi.market",
        restartKind = RestartKind.FORCE_STOP,
    ),
    HyperScope(
        id = "screenshot",
        title = "截屏",
        summary = "截图保存、编辑器外观与隐私打码",
        process = "com.miui.screenshot",
        restartKind = RestartKind.KILL,
    ),
    HyperScope(
        id = "misound",
        title = "音质音效",
        summary = "系统分应用音量面板；入口设置归于系统界面",
        process = "com.miui.misound",
        restartKind = RestartKind.KILL,
    ),
    HyperScope(
        id = "milink",
        title = "设备互联",
        summary = "跨设备剪贴板、文件读取与共享存储目录治理",
        process = "com.milink.service",
        restartKind = RestartKind.KILL,
    ),
    HyperScope(
        id = "mishare",
        title = "小米互传",
        summary = "接收文件保存与媒体库索引冲突保护",
        process = "com.miui.mishare.connectivity",
        restartKind = RestartKind.KILL,
    ),
    HyperScope(
        id = "tsmclient",
        title = "小米智能卡",
        summary = "公交卡、门禁卡的刷卡界面与卡面",
        process = "com.miui.tsmclient",
        restartKind = RestartKind.FORCE_STOP,
    ),
    HyperScope(
        id = "scanner",
        title = "小米扫描器",
        summary = "扫码后拉起应用的内置浏览器",
        process = "com.xiaomi.scanner",
        restartKind = RestartKind.FORCE_STOP,
    ),
)

/** 按 id 取作用域；找不到返回 null（调用方记日志，不抛异常）。 */
fun scopeById(id: String): HyperScope? = SCOPES.firstOrNull { it.id == id }

/**
 * 一个功能归属的**主宿主**。
 *
 * 规则：[HyperFeature.scopes] 里第一个能在 [SCOPES] 里查到的宿主。
 *
 * 后果是「一个功能在界面上只出现一次」：通行密钥同时挂在设置、安全中心、扫描器和系统框架上，
 * 但它只在**系统框架**里显示 —— 用户不必在四个入口看到同一个开关、并困惑于它们是不是
 * 四个独立的设置项。跨宿主的完整清单在功能详情页的「生效范围」里如实列出。
 *
 * 想让某个功能换入口，改它自己的 `scopes` 顺序（主宿主写在前），**不要动 [SCOPES]**。
 */
val HyperFeature.entryScope: HyperScope?
    get() = scopes.firstNotNullOfOrNull { scopeById(it) }

/** 一个功能在哪些宿主里生效（保持声明顺序，用于功能详情页的「生效范围」）。 */
fun scopesOfFeature(feature: HyperFeature): List<HyperScope> =
    feature.scopes.mapNotNull { scopeById(it) }

/** 一个作用域入口下的功能（只认归属，不认「也在这里生效」）。 */
fun featuresOfScope(scopeId: String): List<HyperFeature> =
    FEATURES.filter { it.entryScope?.id == scopeId }

/** 二级菜单仍属于原宿主，没有独立的总开关或重启目标。 */
enum class ScopeFeatureGroup(val scopeId: String, val title: String, val summary: String) {
    SYSTEM_FRAMEWORK_CORE("system_server", "核心破解", "签名、系统应用更新与包管理限制"),
    SYSTEM_FRAMEWORK_FREEFORM("system_server", "小窗", "小窗数量、黑名单、气泡与前台固定"),
    SYSTEM_FRAMEWORK_VOLUME("system_server", "音量", "默认音量流、档位与安全限制"),
    SYSTEM_FRAMEWORK_DISPLAY("system_server", "显示", "深色模式与温控亮度"),
    SYSTEM_FRAMEWORK_DISPLAY_NOTIFY("system_server", "显示与通知", "屏幕方向、截屏、通知与系统提示"),
    SYSTEM_FRAMEWORK_UNDERLYING("system_server", "底层", "安装、包管理、剪贴板、日志与兼容性"),
    SYSTEM_FRAMEWORK_SHORTCUTS("system_server", "快捷菜单", "分享、打开方式与文本处理菜单"),
    SYSTEM_FRAMEWORK_LOCK_TASK("system_server", "引导式访问", "固定应用、电源键退出与状态栏屏蔽"),
    SYSTEM_FRAMEWORK_INPUT("system_server", "输入法", "输入法列表权限与全面屏导航栏兼容"),
    SYSTEM_FRAMEWORK_MIPAD("system_server", "平板输入", "触控笔、ESC 与多指手势"),
    SYSTEM_FRAMEWORK_AUDIO("system_server", "音效", "耳机连接时自动切换音效"),
    SECURITY_CENTER_SECURITY("securitycenter", "安全与安装", "Root、安装拦截、ADB 校验与支付扫描"),
    SECURITY_CENTER_APPS("securitycenter", "应用管理", "排序、停用、默认打开与应用详情"),
    SECURITY_CENTER_PRIVACY("securitycenter", "隐私保护", "权限提示与后台预览图"),
    SECURITY_CENTER_MAINTAIN("securitycenter", "系统维护", "首页精简、省电、离线配置与提示"),
    SECURITY_CENTER_BATTERY("securitycenter", "电池与充电", "低温续航、智能充电与无线充电"),
    SECURITY_CENTER_SIDEBAR_VIDEO("securitycenter", "视频工具箱", "温度墙与视频增强能力"),
    SECURITY_CENTER_SIDEBAR_GAME("securitycenter", "游戏工具箱", "宏名单、准星与提示"),
    SECURITY_CENTER_BUBBLES("securitycenter", "气泡与小窗", "气泡候选与侧边栏隐藏自由窗口"),
    SECURITY_CENTER_BEAUTY("securitycenter", "前置摄像助手", "补光、隐私摄像头与人像居中"),
    SECURITY_CENTER_SIDEBAR_APPEARANCE("securitycenter", "侧边栏外观", "线条颜色与视觉覆盖"),
    SETTINGS_HOME("settings", "首页与入口", "谷歌入口、国际版与澎湃 AI"),
    SETTINGS_CONNECTION("settings", "连接与共享", "网络加速与 NFC 页面"),
    SETTINGS_DISPLAY("settings", "显示与锁屏", "触感、布局优化与永不锁屏"),
    SETTINGS_NOTIFICATION("settings", "通知", "系统应用与不可屏蔽频道"),
    SETTINGS_PERMISSION("settings", "权限", "悬浮窗权限直达"),
    SETTINGS_DEVELOPER("settings", "开发者选项", "应用冻结、刷新率与极致模式"),
    PACKAGE_INSTALLER_SECURITY("packageinstaller", "安装安全", "云检查、风险提示、上传与安装器广告"),
    GUARD_PROVIDER_SECURITY("guardprovider", "安全检测", "Root 环境与应用清单上传"),
    JOYOSE_CLOUD("joyose", "云控与游戏", "云端性能策略与 GPU Tuner"),
    TRUST_SERVICE_SECURITY("trustservice", "风险检测", "MRM 风险事件与信任服务初始化"),
    CLOUD_SERVICE_SYNC("cloudservice", "云同步", "云同步数据类别列表"),
    ANALYTICS_TELEMETRY("analytics", "遥测上报", "使用统计与网络状态上报"),
    HTML_VIEWER_CLOUD("htmlviewer", "云控", "设置云控数据更新任务"),
    MARKET_SECURITY("market", "应用安全", "风险检测、安装监控与启动推送"),
    STATUS_BAR("systemui", "状态栏", "图标、电池、网速、时钟与手势"),
    LOCK_SCREEN("systemui", "锁屏", "锁屏通知、状态栏与解锁提示"),
    CONTROL_CENTER("systemui", "通知与控制中心", "通知提醒、背景与显示设置"),
    SYSTEM_UI_OTHER("systemui", "其他", "手势横条、旋转建议、主题取色与剪贴板"),
}

val HyperFeature.entryGroup: ScopeFeatureGroup?
    get() = group?.takeIf { it.scopeId == entryScope?.id }

fun featureGroupsOfScope(scopeId: String): List<ScopeFeatureGroup> =
    ScopeFeatureGroup.entries.filter { group ->
        group.scopeId == scopeId && featuresOfScope(scopeId).any { it.entryGroup == group }
    }

/** null 表示作用域主页的直属功能；首页总数仍使用未分组的 featuresOfScope。 */
fun featuresOfScopePage(scopeId: String, group: ScopeFeatureGroup? = null): List<HyperFeature> =
    featuresOfScope(scopeId).filter { it.entryGroup == group }

/**
 * 首页要显示的作用域入口。
 *
 * 过滤掉没有归属功能的作用域：设置、安全中心、扫描器在功能上都只是通行密钥的**次要宿主**，
 * 功能已归到系统框架下面，给它们各留一个空入口只会让首页变长。它们的安装状态仍然在
 * 「设置」页里如实列出。
 */
fun entryScopes(): List<HyperScope> = SCOPES.filter { featuresOfScope(it.id).isNotEmpty() }

/**
 * 一个功能当前算不算「生效中」。
 *
 * 有开关的看开关；配置型的（[HyperFeature.configKey] 非空）看配置项有没有填。
 * 两种情况的判据都必须走这里，不要在界面里各写一遍 —— 入口行上的计数与作用域页里
 * 那一行显示的状态必须是同一个结论，否则会出现「入口说启用 1 / 1，点进去那一行看着像没开」。
 */
fun isFeatureActive(
    feature: HyperFeature,
    switches: Map<String, Boolean>,
    strings: Map<String, String>,
): Boolean = feature.configKey?.let { key ->
    strings[key].orEmpty().isNotBlank()
} ?: (switches[feature.id] == true)

/**
 * 一个作用域下已生效的功能数。
 *
 * 只数**主开关**，不数子项：入口行上的「已启用 2 / 3」要和用户点进去能拨的那几行严格对应，
 * 把子项算进去会得到一个解释不通的分数。
 */
fun enabledCountInScope(
    scopeId: String,
    switches: Map<String, Boolean>,
    strings: Map<String, String>,
): Int = featuresOfScope(scopeId).count { isFeatureActive(it, switches, strings) }
