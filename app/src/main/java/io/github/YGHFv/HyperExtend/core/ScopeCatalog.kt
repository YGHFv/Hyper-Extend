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
        id = "settings",
        title = "系统设置",
        summary = "设置里的凭据提供方、自动填充等条目",
        process = "com.android.settings",
        restartKind = RestartKind.FORCE_STOP,
    ),
    HyperScope(
        id = "securitycenter",
        title = "安全中心",
        summary = "权限、隐私与凭据相关配置的守门人",
        process = "com.miui.securitycenter",
        restartKind = RestartKind.FORCE_STOP,
    ),
    HyperScope(
        id = "screenshot",
        title = "截屏",
        summary = "截图保存与自动复制到剪贴板",
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
        summary = "跨设备剪贴板与文件读取保护",
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
    STATUS_BAR("systemui", "状态栏", "图标、电池、网速、时钟与手势"),
    LOCK_SCREEN("systemui", "锁屏", "锁屏通知、状态栏与解锁提示"),
    CONTROL_CENTER("systemui", "通知与控制中心", "通知提醒、背景与显示设置"),
    SYSTEM_UI_OTHER("systemui", "其他", "通知小窗及其他系统界面设置"),
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
