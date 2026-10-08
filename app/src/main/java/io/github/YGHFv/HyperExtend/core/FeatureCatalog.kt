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
 * 功能目录 —— **界面的唯一数据源**。
 *
 * 界面不写死任何一个开关：首页画哪些入口、入口里有哪些功能、搜索命中什么，全从 [FEATURES]
 * 与 [SCOPES] 派生。加一个功能只需要在这里加一条，界面、设置存取、搜索三处自动跟上。
 * 反过来，在界面里手写一行开关却没在这里登记，这个开关就既不会被搜索到、也不会被 hook 读到。
 *
 * ## id 就是设置键
 *
 * [HyperFeature.id] 与 [HyperOption.id] 直接当 SharedPreferences 的键用（见 config/HyperSettings）。
 * 于是「界面上有一个开关」与「hook 里读得到这个开关」在类型层面是同一件事，不存在两套命名
 * 需要人工对齐 —— 那正是这类模块最常见的 bug 来源（界面写 `a_b`、hook 读 `a.b`）。
 *
 * ## 功能按「作用域」归属
 *
 * 每个功能声明它活在哪些宿主进程里，首页按这个维度收纳成入口行：用户改完一个开关，
 * 要重启的正是那个宿主，两件事在界面上挨着，不用跨页面去猜。
 *
 * 归属**只取一个**（`HyperFeature.entryScope`）：通行密钥在四个宿主里各有一段逻辑，
 * 但它只显示在系统框架下面 —— 同一个开关在四个入口里各出现一遍，用户只会以为那是四件事。
 * 跨宿主的完整清单在功能详情页的「生效范围」里如实列出。
 *
 * ## 来源与许可
 *
 * 每条保留来源与许可元数据，界面按开源项目集中致谢。[license] 不是装饰：
 * 它是本模块整体采用 AGPL-3.0 的依据（见项目根 LICENSE），改动功能实现时不要把它删掉。
 */

/**
 * NFC 卡面的图片设置键。
 *
 * 定义在目录里而不是 `config/HyperSettings` 里：它是 [HyperFeature.configKey] 指向的键，
 * 也就是「这个功能配了什么」的存放位置，属于功能定义的一部分。设置层只是读写它的执行者，
 * 两处各写一份字面量的话，改一次键名就会变成「界面写这个键、hook 读那个键」——
 * 正是这个目录在文档里反复强调要避免的那类 bug。
 */
const val NFC_IMAGE_KEY = "nfc_card_face.image_mappings"

/**
 * 莫奈取色「风格」的设置键。
 *
 * 存的是 libmonet 的 Variant 名（`TONAL_SPOT` / `VIBRANT`……），**不是**宿主那个 style 整数。
 * 理由与 [HyperFeature.configKey] 同源：整数是宿主内部实现的细节，各版本未必一致；
 * 名字是 Google libmonet 的公开枚举名，宿主只是它的移植。注入侧拿到名字之后
 * **在运行时反查**本机宿主用哪个整数表示它（见 `hook/feature/WallpaperMonetFix`），
 * 这样换一个 HyperOS 版本也不会因为写死的整数而失效。
 *
 * 空串的含义是「不干预」——保持宿主自己的风格，这也是默认值。
 */
const val MONET_SCHEME_KEY = "wallpaper_monet.scheme"

/**
 * 通知图标库「同步来源」的设置键。
 *
 * 取值是 [NotifyIconSyncSource.variant]（`proxy1` / `proxy2` / `direct`），
 * 空串的含义与取值 `proxy1` 相同（官方仓库 + 国内代理）——这与其它字符串项
 * 「空 = 未配置」的语义一致，缺省就是最可用的那条路。
 */
const val NOTIFY_ICON_SOURCE_KEY = "native_notify_icon.sync_source"

/**
 * 通知图标库「自动同步时间」的设置键。
 *
 * 存 `HH:mm`（如 `03:00`）；空串按 `03:00` 处理。时间选项由界面提供，
 * 注入侧只做字符串比对（见 `hook/feature/NativeNotifyIcon` 的 TIME_TICK 逻辑），
 * 所以这里不需要时刻表数据 —— 换选项只改界面。
 */
const val NOTIFY_ICON_AUTO_TIME_KEY = "native_notify_icon.auto_time"
/** 自动防砖熔断的递增解除请求令牌；不属于功能开关，不展示在功能清单中。 */
const val FRAMEWORK_FUSE_RESET_KEY = "safety.framework_fuse_reset"

/**
 * 通知图标库的同步来源。GitHub 在国内的可达性不稳定，上游默认走代理。
 *
 * [urlPrefix] 为空表示直连。改这里的文案不影响注入侧 —— 注入侧只认 [variant]。
 */
enum class NotifyIconSyncSource(val variant: String, val label: String, val urlPrefix: String) {
    PROXY_1("proxy1", "官方仓库 · 镜像一", "https://cdn.gh-proxy.org"),
    PROXY_2("proxy2", "官方仓库 · 镜像二", "https://ghfast.top"),
    DIRECT("direct", "GitHub 直连", ""),
    ;

    companion object {
        /** 空串与未知值都回镜像一：与「缺省即可用」的键语义配套。 */
        fun fromVariant(value: String?): NotifyIconSyncSource =
            entries.firstOrNull { it.variant == value } ?: PROXY_1
    }
}

/** 通知图标库自动同步的可选时刻。顺序即界面顺序。 */
val NOTIFY_ICON_AUTO_TIMES: List<String> = listOf("03:00", "09:00", "15:00", "21:00")

/**
 * 功能详情页里的一行**配置**（相对于「开关」而言）：多选一、或一个数值。
 *
 * ## 为什么要增加这一类，而不是继续用开关
 *
 * 迁移过来的功能里有大量「模式」和「数值」：图标有三个状态（默认 / 始终显示 / 始终隐藏）、
 * 网速字体有 8~40 这么多档。用开关表达多选一，用户得自己保证只开一个；
 * 用开关表达数值，则等于没有这个数值。所以目录里必须有第三种、第四种行。
 *
 * ## 存的是什么
 *
 * 一律存**字符串**（见 config/HyperSettings 的 `STRING_KEYS`）：
 * - [HyperChoice] 存候选的 [ChoiceEntry.variant]；
 * - [HyperSlider] 存整数的十进制写法。
 *
 * 数值也走字符串是刻意的：设置层只需要「字符串」这一种额外类型，
 * 界面、投影到框架、导入导出三处就都不用为数值单开一条路。
 * 注入侧用 `HookSettings.number(key)` 解析（缺省/写坏时回落到目录里的默认值）。
 *
 * ## [group] 的语义
 *
 * 参考项目的页面里这些行是分组的（「网络连接」「蓝牙」「互联互通」……）。
 * 有值的行会另起一张卡片并带上这个标题；为 null 表示接在上一行所在的组里。
 * 界面按声明顺序分组渲染，**顺序即界面顺序**。
 */
sealed interface HyperConfigRow {
    val key: String
    val title: String
    val summary: String?
    val group: String?
}

/** 下拉里的一个候选：[variant] 是存进设置的值，[label] 是界面上的字。 */
data class ChoiceEntry(val variant: String, val label: String)

/** 多选一的一行。 */
data class HyperChoice(
    override val key: String,
    override val title: String,
    val entries: List<ChoiceEntry>,
    override val summary: String? = null,
    override val group: String? = null,
) : HyperConfigRow {
    /** 出厂取值：第一个候选。注入侧读不到配置时也按它处理（与界面显示的缺省一致）。 */
    val default: String get() = entries.first().variant

    /** 界面显示用的标签：取值不认识时回落到第一个候选，而不是显示一个空行。 */
    fun labelOf(variant: String): String =
        entries.firstOrNull { it.variant == variant }?.label ?: entries.first().label
}

/**
 * 一个数值行（滑块）。
 *
 * [divisor] 是「一个显示单位等于几个存储单位」：参考项目的滑块大多是 0.5 步进
 * （`displayDividerValue="2"`），所以存储用整数、显示时再除。这样存储层不必支持小数。
 */
data class HyperSlider(
    override val key: String,
    override val title: String,
    val min: Int,
    val max: Int,
    val default: Int,
    override val summary: String? = null,
    override val group: String? = null,
    val step: Int = 1,
    val divisor: Int = 1,
    val unit: String = "",
) : HyperConfigRow {
    /** 把存储值写成界面上显示的那串字（例如 `13.5 dp`）。 */
    fun display(stored: Int): String {
        val value = stored.toFloat() / divisor
        val number = if (divisor == 1) {
            value.toInt().toString()
        } else {
            String.format(java.util.Locale.US, "%.1f", value)
        }
        return number + unit
    }
}

/**
 * 一个自由文本行（时钟格式、自定义文字这类）。
 *
 * ## 为什么需要它
 *
 * 时钟格式是一串 `HH:mm:ss` 这样的模式，不是「从 N 个候选里挑一个」，也不是数值 ——
 * 用 [HyperChoice] 表达它就等于替用户决定他能用哪些格式，而时钟格式的语法
 * （`E`、`M/d`、`a`……）本来就该由用户自己写；用 [HyperSlider] 表达则根本不成立。
 *
 * ## 存的是什么
 *
 * 原样存字符串，**空串有明确含义**：这一项没被配置，宿主按自己原本的格式走。
 * 所以注入侧读到空串时不能拿 [default] 去覆盖宿主 —— [default] 只是界面上的占位提示
 * （`placeholder` 与它分开：前者是「什么都没有时显示什么」，后者是「这一项本来长什么样」）。
 */
data class HyperText(
    override val key: String,
    override val title: String,
    override val summary: String? = null,
    override val group: String? = null,
    val placeholder: String = "",
) : HyperConfigRow

/**
 * 功能详情页在「子项」之下追加的一个附加控件。
 *
 * 为什么要做成数据而不是在界面里 `if (feature.id == ...)`：那种写法在功能改名或再加
 * 第二个同类功能时必然漏掉一处，而漏掉的表现是「开关在那儿但点了没用」。
 * 这里登记一次，`ui/FeatureDetailPage` 按它渲染，加功能时只有目录要改。
 */
enum class FeatureExtra {
    /** 默认通行密钥应用：枚举设备上的凭据提供方并写入系统设置。 */
    PASSKEY_DEFAULT_APP,

    /** 莫奈取色风格：选择 libmonet 的 Variant。 */
    MONET_SCHEME,

    /** 通知图标库：同步来源、自动同步时间与手动同步。 */
    NOTIFY_ICON_LIBRARY,
}

/**
 * 一个可选的莫奈风格。[variant] 为空串表示「不干预宿主」。
 *
 * 顺序即下拉里的顺序：把最常用的（默认的色调点）放在最前面，后面按「离默认多远」排。
 */
data class MonetScheme(val variant: String, val label: String, val summary: String)

val MONET_SCHEMES: List<MonetScheme> = listOf(
    MonetScheme("", "不干预", "保持宿主自己的取色风格"),
    MonetScheme("TONAL_SPOT", "色调点", "Material You 默认风格：主色取壁纸主色调，点缀色同族"),
    MonetScheme("VIBRANT", "鲜艳", "整体提高饱和度，色块更跳"),
    MonetScheme("EXPRESSIVE", "表现力", "主色与点缀色刻意拉开色相，层次最多"),
    MonetScheme("FIDELITY", "保真", "尽量贴近壁纸原色，不额外偏移"),
    MonetScheme("CONTENT", "内容", "与保真接近，点缀色更依赖壁纸内容色"),
    MonetScheme("MONOCHROME", "单色", "全部色板取自同一色相的明度阶梯"),
    MonetScheme("NEUTRAL", "中性", "极低饱和，接近灰阶"),
    MonetScheme("RAINBOW", "彩虹", "点缀色取自壁纸的多组色相"),
    MonetScheme("FRUIT_SALAD", "水果沙拉", "点缀色大幅偏移，色彩最杂"),
)

data class HyperFeature(
    /** 设置键 & 稳定标识。改了它 = 老用户的这个开关回到默认值。 */
    val id: String,
    val title: String,
    val summary: String,
    /**
     * 这个功能在哪些宿主进程里生效，取值必须是 [SCOPES] 里存在的 id。
     *
     * **顺序有语义**：第一个能查到的就是它在界面上的入口，所以主宿主写在最前面。
     */
    val scopes: List<String>,
    /** 功能来自哪个模块（界面「来源」一栏显示这个）。 */
    val origin: String,
    /** 来源模块的许可。决定本模块能否直接使用其代码。 */
    val license: String,
    val defaultEnabled: Boolean,
    /** 生效条件说明。为空表示纯开关，没有额外前提。 */
    val requirement: String? = null,
    /**
     * 功能详情页里的配置行（多选一 / 数值）。顺序即界面顺序，
     * 见 [HyperConfigRow] 的注释。
     */
    val config: List<HyperConfigRow> = emptyList(),
    /**
     * 配置型功能：它**没有总开关**，作用域页里显示成入口行，点进去是它自己的配置页。
     *
     * 非 null 时这个值就是「它配置了什么」所对应的字符串设置键：值非空 = 已配置 = 生效中。
     * 于是「有没有开关」这件事仍然是数据，而不是散在界面里的 `if (feature.id == ...)`——
     * 那种写法在加第二个同类功能时必然被漏掉一处（开关画出来了，但 hook 侧读的是另一个键）。
     *
     * 为什么非要这样一个类别：开关只能表达「开/关」，而这类功能要表达的是「配了哪张图」。
     * 硬塞一个开关的结果是「开关开着但没配图 → 用户以为在生效」，或者反过来
     * 「配了图但开关关着 → 用户以为没生效」。把生效条件直接绑在配置值上，两种都消失。
     */
    val configKey: String? = null,
    val options: List<HyperOption> = emptyList(),
    /**
     * 「子项」之下再追加的一个附加控件（见 [FeatureExtra]）。
     *
     * 与 [options] 分开是因为它表达的不是「开/关」而是一份**选择**：
     * 开关能表达的状态只有两个，而「默认通行密钥用哪个应用」「取色用哪种风格」都是多选一。
     * 硬塞成一堆开关的结果是用户必须自己保证只开一个 —— 界面层根本没法拦住。
     */
    val extra: FeatureExtra? = null,
    /** 仅用于作用域内的二级菜单，不改变设置键、Hook 或生效范围。 */
    val group: ScopeFeatureGroup? = null,
)

/** Only features with additional controls need a separate settings page. */
val HyperFeature.hasDetailPage: Boolean
    get() = options.isNotEmpty() || config.isNotEmpty() || configKey != null || extra != null

/**
 * 功能下的一个子项。子项自己不判断可用性 —— 主开关关着时界面照常显示，
 * 只是 hook 侧根本不会装对应拦截（见各 feature 的 installXxx 实现）。
 *
 * [defaultEnabled] 默认 `true`：主开关一打开，子项就该全部生效，不必逐个去拨。
 * 代价是**任何直接读子项键的地方，都必须先确认所属功能的主开关为真**，
 * 否则「用户从没进过界面」会被读成「子项全开」。这条约束由 `hook/HookSettings.isOn`
 * 在内部强制，不依赖调用方自觉 —— 改那个函数之前先把它上面的注释读完。
 */
data class HyperOption(
    val id: String,
    val title: String,
    val summary: String? = null,
    val defaultEnabled: Boolean = true,
    /**
     * 子项在详情页里的分组标题。为 null 时归进「子项」。
     *
     * 迁移过来的功能动辄十来个开关，「子项」一张卡全塞进去之后，
     * 用户分不清「这几个是同一件事的几个方面」还是「一堆互不相干的开关」。
     * 参考项目本来就是分组的，这里保留同样的分组。
     */
    val group: String? = null,
)

/**
 * 全部功能。**顺序即界面顺序**，也是作用域入口里的排列顺序。
 *
 * 每个功能的 summary 都要回答「解决的问题是什么」，而不是「做了什么」——
 * 用户是按症状找开关的（「横条碍眼」「主题色一直是蓝的」），不是按实现。
 */
val FEATURES: List<HyperFeature> = listOf(
    HyperFeature(
        id = "screenshot_clipboard",
        title = "截图自动写入剪贴板",
        summary = "截图保存后自动复制到剪贴板",
        scopes = listOf("screenshot"),
        origin = "西米露 / HyperCeiler · UnlockCopyPicture；本模块补充保存时序保护",
        license = "AGPL-3.0",
        defaultEnabled = false,
        requirement = "不限制截图版本；目标方法不匹配时跳过。请关闭其他模块的同类功能。",
    ),
    HyperFeature(
        id = "milink_clipboard_guard",
        title = "剪贴板读取崩溃修复",
        summary = "避免设备互联读取无效图片时崩溃",
        scopes = listOf("milink"),
        origin = "本模块原创 · 根据设备互联崩溃堆栈修复",
        license = "AGPL-3.0",
        defaultEnabled = false,
        requirement = "启用设备互联作用域，重启其进程生效。",
    ),
    HyperFeature(
        id = "mishare_receive_guard",
        title = "接收文件索引冲突修复",
        summary = "绕过残留媒体索引，使用备用文件名接收",
        scopes = listOf("mishare"),
        origin = "本模块原创 · 根据双端日志与接收端文件系统证据修复",
        license = "AGPL-3.0",
        defaultEnabled = false,
        requirement = "在接收端启用并重启互传。需 Android 11+，不限制互传版本；目标方法不匹配时跳过。仅处理 Download/MiShare，不删除索引或主动覆盖文件。",
    ),
    HyperFeature(
        id = "gesture_line",
        title = "隐藏手势横条",
        summary = "隐藏底部横条，保留手势操作",
        scopes = listOf("systemui"),
        origin = "HideLine",
        license = "未声明开源协议 —— 按逆向结果自行实现",
        defaultEnabled = false,
        options = listOf(
            HyperOption(
                id = "gesture_line.skip_draw",
                title = "拦截横条绘制",
                summary = "阻止手势横条绘制",
            ),
            HyperOption(
                id = "gesture_line.report_hidden",
                title = "按原生「隐藏手势提示线」处理",
                summary = "同时隐藏横条及其高亮动画",
            ),
        ),
    ),
    HyperFeature(
        id = "wallpaper_monet",
        title = "壁纸取色修复",
        summary = "让动态主题色跟随壁纸更新",
        scopes = listOf("systemui"),
        origin = "HyperWallpaperMonet（PengDingkang）",
        license = "Apache-2.0 —— 允许参考与复用",
        defaultEnabled = false,
        options = listOf(
            HyperOption(
                id = "wallpaper_monet.startup",
                title = "启动后补偿",
                summary = "启动时同步壁纸颜色",
            ),
            HyperOption(
                id = "wallpaper_monet.retry",
                title = "延迟复检",
                summary = "启动后延迟复检两次",
            ),
            HyperOption(
                id = "wallpaper_monet.events",
                title = "颜色变化事件强制重算",
                summary = "壁纸变化后补全主题色",
            ),
        ),
        extra = FeatureExtra.MONET_SCHEME,
    ),
    HyperFeature(
        id = "native_notify_icon",
        group = ScopeFeatureGroup.STATUS_BAR,
        title = "原生通知图标",
        summary = "使用通知自带的单色图标",
        scopes = listOf("systemui"),
        origin = "MIUI 原生通知图标（fankes）",
        license = "AGPL-3.0 —— 允许参考与复用，衍生作品须同样开源",
        defaultEnabled = false,
        options = listOf(
            HyperOption(
                id = "native_notify_icon.no_substitute",
                title = "不替换为 App 图标",
                summary = "保留通知自带图标",
            ),
            HyperOption(
                id = "native_notify_icon.custom_app_icon",
                title = "还原通知自带的 smallIcon",
                summary = "优先使用通知原始图标",
            ),
            HyperOption(
                id = "native_notify_icon.icon_fix",
                // 上游同样默认关：库里没有的应用会保持原样，开了也不会更差，但替换本身
                // 改变的是「系统本来要显示什么」，是否接受由用户决定。
                title = "图标库修复",
                summary = "使用图标库适配不规范图标",
                defaultEnabled = false,
            ),
            HyperOption(
                id = "native_notify_icon.icon_fix_placeholder",
                title = "占位图标",
                summary = "未适配应用使用统一图标",
                defaultEnabled = false,
            ),
            HyperOption(
                id = "native_notify_icon.icon_fix_auto",
                title = "自动更新图标库",
                summary = "每天定时同步",
                defaultEnabled = false,
            ),
        ),
        extra = FeatureExtra.NOTIFY_ICON_LIBRARY,
    ),
    HyperFeature(
        id = "rotation_suggestion",
        title = "\u5173\u95ed\u65cb\u8f6c\u5efa\u8bae",
        summary = "隐藏导航栏附近的旋转建议按钮",
        scopes = listOf("systemui"),
        origin = "Android SystemUI RotationButtonController",
        license = "Apache-2.0 - behavior-level reimplementation",
        defaultEnabled = false,
    ),
    HyperFeature(
        id = "nfc_card_face",
        title = "NFC 卡面自定义",
        summary = "替换小米智能卡的卡面图片",
        scopes = listOf("tsmclient"),
        origin = "DIY NFC 卡面图片（zhizi42）",
        license = "GPL-3.0 —— 允许参考与复用，衍生作品须同样开源",
        // 没有开关可以拨，所以这个字段在这里不表示「默认开」；生效与否完全由 configKey 决定
        // （见 HyperFeature.configKey 的注释）。
        defaultEnabled = false,
        configKey = NFC_IMAGE_KEY,
        // 卡面替换有三个挂载点，其中「超级岛」那一处会同时改掉超级岛上显示的那张卡面。
        // 有人只想换钱包里的卡面、不想动超级岛，所以单独给一个开关。
        // 配置型功能没有主开关，所以这个子项的默认值就是它自己的默认值（默认开）。
        options = listOf(
            HyperOption(
                id = "nfc_card_face.super_island",
                title = "超级岛卡面",
                summary = "同步每张卡的自定义图片",
            ),
        ),
    ),
    HyperFeature(
        id = "passkey_fix",
        title = "通行密钥修复",
        summary = "修复凭据管理器无法打开或列表为空",
        scopes = listOf("system_server", "settings", "securitycenter", "scanner"),
        origin = "修复澎湃系统通行密钥（Howard20181）",
        license = "GPL-3.0 —— 允许参考与复用，衍生作品须同样开源",
        defaultEnabled = false,
        requirement = "需启用谷歌基础服务",
        options = listOf(
            HyperOption(
                id = "passkey_fix.settings",
                title = "系统设置里的默认凭据提供方",
                summary = "恢复凭据提供方列表",
            ),
            HyperOption(
                id = "passkey_fix.security_center",
                title = "阻止安全中心覆写配置",
                summary = "防止开机后恢复默认配置",
            ),
            HyperOption(
                id = "passkey_fix.scanner",
                title = "修复扫描器调用方",
                summary = "修正通行密钥调用信息",
            ),
            HyperOption(
                id = "passkey_fix.system_server",
                title = "系统服务侧远程提供方",
                summary = "接入 Google Play 服务",
            ),
        ),
        extra = FeatureExtra.PASSKEY_DEFAULT_APP,
    ),
    HyperFeature(
        id = "rotation_lock_fix",
        title = "旋转锁定保护",
        summary = "强行停止应用后仍保留方向锁定",
        scopes = listOf("system_server"),
        origin = "本模块原创",
        license = "AGPL-3.0",
        defaultEnabled = false,
        requirement = "需重启设备生效",
    ),
) + STATUS_BAR_FEATURES

/** 按 id 取功能；找不到返回 null——调用方负责记日志，不抛异常（hook 侧绝不能因设置异常崩）。 */
fun featureById(id: String): HyperFeature? = FEATURES.firstOrNull { it.id == id }

/**
 * 全部配置行的设置键（多选一 + 数值）。
 *
 * 与 [FEATURES] 同源派生，而不是在 `config/HyperSettings` 里再抄一份：抄的那份
 * 加一个配置行就会漏一处，而漏掉的表现是「界面写进去了、投影给框架时被过滤掉」——
 * 也就是「改了配置、重启宿主还是老样子」，且日志里看不出任何异常。
 */
val CONFIG_KEYS: List<String> = FEATURES.flatMap { feature -> feature.config.map { it.key } }

/**
 * 一个功能「生效范围」的完整文字描述（功能详情页用）。
 *
 * 从 [SCOPES] 派生而不是在每条功能里手写一遍：手写的那份加作用域时一定会忘改，
 * 而它恰恰是用户拿来判断「我到底该重启哪个应用」的依据。
 */
fun scopeDescriptionOf(feature: HyperFeature): String =
    scopesOfFeature(feature).joinToString("、") { "${it.title}（${it.process}）" }

/**
 * 任意开关 id（功能或子项）的出厂默认值。
 *
 * 全项目只有这一个「默认值从哪来」的出处：界面读它、hook 读它、设置文件里没写过的键也读它。
 * 写死第二份默认值（比如界面里 `?: true`、hook 里 `?: false`）会让同一个开关在两处
 * 表现相反，而且是那种「关了重启又自己开了」的诡异 bug。
 *
 * 未登记的 id 返回 false：宁可功能默认关着，也不要一个没人认识的键把功能打开。
 */
fun defaultEnabledOf(id: String): Boolean {
    featureById(id)?.let { return it.defaultEnabled }
    for (feature in FEATURES) {
        feature.options.firstOrNull { it.id == id }?.let { return it.defaultEnabled }
    }
    return false
}

/**
 * 搜索：标题、说明、来源、作用域名、子项标题都参与匹配。
 *
 * 作用域名在匹配范围内是刻意的：用户记不住「壁纸取色修复」叫什么，但记得「在系统界面里」，
 * 而「系统界面」正是作用域入口上的字。
 *
 * 子项命中时返回它所属的功能（而不是子项本身）——搜索结果是「去哪一页」，
 * 而开关只在功能页里出现。返回值里带上命中的子项标题，界面用它做一行「命中：xxx」。
 */
data class SearchHit(val feature: HyperFeature, val matchedOption: HyperOption? = null)

fun searchFeatures(query: String): List<SearchHit> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    val hits = mutableListOf<SearchHit>()
    for (feature in FEATURES) {
        if (feature.matches(q)) {
            hits += SearchHit(feature)
            continue
        }
        val option = feature.options.firstOrNull { it.matches(q) }
        if (option != null) {
            hits += SearchHit(feature, option)
            continue
        }
        // 配置行也参与匹配：用户找的是「网速字体大小」这种字，而不是功能名。
        if (feature.config.any { it.title.contains(q, true) || it.summary?.contains(q, true) == true }) {
            hits += SearchHit(feature)
        }
    }
    return hits
}

private fun HyperFeature.matches(q: String): Boolean =
    title.contains(q, true) ||
        summary.contains(q, true) ||
        origin.contains(q, true) ||
        entryGroup?.title?.contains(q, true) == true ||
        scopesOfFeature(this).any { it.title.contains(q, true) || it.process.contains(q, true) }

private fun HyperOption.matches(q: String): Boolean =
    title.contains(q, true) || (summary?.contains(q, true) == true)
