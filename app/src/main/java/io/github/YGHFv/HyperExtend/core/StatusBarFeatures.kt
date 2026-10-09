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
 * 系统界面作用域里的「状态栏」功能。
 *
 * ## 它们是哪来的
 *
 * 逐条对应参考项目（西米露 / HyperCeiler）「系统界面 → 状态栏」整页里的条目，
 * 选项与数值的默认值也照抄原页面。**界面文案照抄参考项目**：用户是按自己熟悉的名字
 * 找开关的，改写得更通顺也不如原名好找。
 *
 * ## 只做 OS4
 *
 * 参考项目把功能按系统版本拆成了两条线（新线 / `os2` 支线），这里只按**澎湃 OS 4
 * （Android 17）的系统界面**写 hook：靶子类名、图标槽位名、字段名都以本机
 * `com.android.systemui`（17.03.x）的 dex 逐个校对过（见 `.workbuddy/tools/dexconst.py`、
 * `dexdump.py`）。找不到的靶子一律安静跳过并记日志，不猜、也不给旧版本留分支。
 *
 * 单独一个文件而不是塞进 `FeatureCatalog`：这一页光条目就有上百行，混在一起之后
 * 目录本身（也就是「功能有哪些、怎么归类」的那份）就没法一眼看完了。
 * 它们仍然是 [FEATURES] 的一部分 —— 界面、设置、搜索都只认那一份。
 *
 * ## 参考项目那一页里有、这里没有的条目
 *
 * 迁移是逐条对靶子做的，靶子在 OS4 上不存在的条目**不留空壳开关**（拨了没反应的开关
 * 比没有这个开关更糟 —— 用户会以为是自己没配对）。当前明确没做的是这几块：
 *
 * - **焦点通知 / 灵动岛**（媒体卡片、焦点歌词、隐藏灵动舞台强提示）：参考项目依赖的
 *   `FocusedNotifPromptView`、`CollapsedStatusBarFragment`、`FakeStatusBarClockController`
 *   等类在 OS4 的系统界面 dex 里一个都没有，整块能力已经换了实现。要做得先重新定位靶子。
 * - **电池信息卡片**（状态栏电量条里那行温度 / 电流 / 功率）：参考项目的入口是
 *   `com.miui.charge.ChargeUtils`，OS4 没有这个类。同样是先定位再迁移。
 * - **移动网络类型与双排图标**：「移动网络类型图标单独显示」「双排移动网络图标」
 *   「大网络类型图标」「自定义移动网络类型文本」尚未迁移。
 *   移动信号显示逻辑四档、隐藏 SIM 卡 1 / 2 已接入 OS4 可变状态流。
 * - **时钟的其余形态**：参考项目「时钟指示器」页还有「时钟样式」（状态栏时钟显示成两行
 *   日期）、「隐藏 Pad 日期时钟」、「禁用时钟同步」等；本模块只做了最常改的时钟格式，以及
 *   状态栏时钟本身的字号 / 加粗 / 位置。
 *
 * 另外两项**换成了做得成的形态**而不是原样照搬：
 * 图标页的「交换 WIFI 与移动网络」在 OS4 没有落脚点（`StatusBarIconList` 已经没有
 * 「用一串槽位名构造」的那个构造器，槽位顺序改由第一次 `findOrInsertSlot` 决定）；
 * 闹钟的「仅在响铃前显示」依赖的 `PhoneStatusBarPolicy#onAlarmChanged` 也不存在了。
 */

/** 图标槽位的五态。取值与语义同参考项目：`RIGHT_BLOCK_LIST` / `CONTROL_CENTER_BLOCK_LIST` 的两张黑名单。 */
private val ICON_MODES = listOf(
    ChoiceEntry("0", "默认"),
    ChoiceEntry("1", "始终显示"),
    ChoiceEntry("11", "仅在控制中心显示"),
    ChoiceEntry("12", "仅在状态栏显示"),
    ChoiceEntry("2", "始终隐藏"),
)

/**
 * 「只做得到隐藏」的那一类图标开关。
 *
 * WIFI 标准（图标旁边那个 5 / 6 的小角标）不是「进哪张名单」的问题，而是系统算出来的一个值。
 * 参考项目给它三档，其中「始终显示」要拦下宿主里那个把值清零的函数 —— 那个函数在 OS4 上
 * 换成了协程状态机里的一段，靠类名与常量特征定位（参考项目用的是 dexkit 特征搜索）。
 * 本模块不做特征搜索，所以**只保留做得成的两档**：用五态或三态去表达它，
 * 用户会得到拨了没有任何反应的选项，那比少一个选项更糟。
 */
private val ICON_MODES_HIDE_ONLY = listOf(
    ChoiceEntry("0", "默认"),
    ChoiceEntry("2", "始终隐藏"),
)

/**
 * WIFI 标准的设置键。
 *
 * 它不是「某个槽位」而是宿主算出来的一个值（见 [ICON_MODES_HIDE_ONLY]），所以不进
 * [ICON_SLOT_NAMES]，单独一个键、单独一处 hook。公开出去是为了让注入侧直接引用它 ——
 * 界面写一个键、hook 读另一个键是这类模块最典型的失效方式。
 */
const val WIFI_STANDARD_KEY = "status_bar_icons.wifi_standard"

/** 一个图标槽位：设置键后缀 + 标题 + 它在 ROM 里的候选名。 */
private class IconSlot(
    val suffix: String,
    val title: String,
    val names: List<String>,
) {
    val key: String get() = iconSlotKey(suffix)
}

/**
 * 可管理的图标槽位。
 *
 * ## 为什么一个槽位配多个名字
 *
 * OS4 的系统界面里同一类图标换过写法（无 SIM 卡一处写 `nosim`、别处写 `no_sim`），
 * 而模块只能按字符串比对命中它们。多给一个名字的代价是列表里多一条谁也不匹配的字符串
 * （行为完全不变）；少给一个的代价是「这个开关点了没反应」。两边的代价不对称，宁可多写。
 */
private class IconSlotGroup(val title: String, val slots: List<IconSlot>)

/**
 * 槽位的分组与顺序，**照抄参考项目页面的分组与顺序**。
 *
 * 做成「分组 + 组内顺序」而不是一个平表：界面要按这个顺序画，配置区也要按这个顺序建，
 * 而两者各存一份顺序的结果是「界面上相邻的两个开关，在 hook 里隔了十条」，
 * 出问题时光看界面完全对不上。
 */
private val ICON_SLOT_GROUPS: List<IconSlotGroup> = listOf(
    IconSlotGroup(
        "网络连接",
        listOf(
            // 演示模式的 WIFI 是另一条槽位名，和真 WIFI 走同一档设置。
            IconSlot("wifi", "WIFI 图标", listOf("wifi", "demo_wifi")),
            IconSlot("hotspot", "WIFI 热点", listOf("hotspot")),
            IconSlot("airplane", "飞行模式", listOf("airplane")),
            IconSlot("no_sim", "无 SIM 卡图标", listOf("nosim", "no_sim")),
            IconSlot("cast", "投屏", listOf("cast")),
            IconSlot("ethernet", "以太网", listOf("ethernet")),
        ),
    ),
    IconSlotGroup(
        "蓝牙",
        listOf(
            IconSlot("bluetooth", "蓝牙", listOf("bluetooth")),
            IconSlot("bluetooth_battery", "蓝牙电量", listOf("bluetooth_handsfree_battery")),
        ),
    ),
    IconSlotGroup(
        "敏感信息",
        listOf(
            IconSlot("location", "位置信息", listOf("location")),
            IconSlot("mic", "麦克风", listOf("micphone", "mikey")),
            IconSlot("camera", "相机", listOf("camera")),
            IconSlot("privacy_mode", "隐私模式", listOf("privacy_mode")),
        ),
    ),
    IconSlotGroup(
        "状态信息",
        listOf(
            IconSlot("alarm_clock", "闹钟", listOf("alarm_clock")),
            IconSlot("nfc", "NFC", listOf("nfc")),
            IconSlot("vpn", "VPN", listOf("vpn")),
            IconSlot("data_saver", "流量节省", listOf("data_saver")),
            IconSlot("volume", "静音", listOf("volume")),
            IconSlot("zen", "勿扰", listOf("zen")),
            IconSlot("hd", "新 HD 图标", listOf("hd")),
            IconSlot("screen_record", "录屏", listOf("screen_record")),
            IconSlot("rotate", "旋转锁定", listOf("rotate")),
            IconSlot("ime", "输入法", listOf("ime")),
            IconSlot("missed_call", "未接来电", listOf("missed_call")),
            IconSlot("managed_profile", "工作资料", listOf("managed_profile")),
            IconSlot("tty", "TTY", listOf("tty")),
        ),
    ),
    IconSlotGroup(
        "互联互通",
        listOf(
            IconSlot("glasses", "智能眼镜", listOf("glasses")),
            IconSlot("dist_compute", "分布式计算", listOf("dist_compute")),
            IconSlot("car", "汽车", listOf("car")),
            IconSlot("pad", "PAD", listOf("pad")),
            IconSlot("pc", "电脑", listOf("pc")),
            IconSlot("phone", "手机", listOf("phone")),
            IconSlot("soundbox", "小爱音箱", listOf("sound_box")),
            IconSlot("soundbox_screen", "小爱触屏音箱", listOf("sound_box_screen")),
            IconSlot("soundbox_group", "小爱音箱组合", listOf("sound_box_group")),
            IconSlot("soundbox_group_stereo", "小爱音箱组合立体声", listOf("stereo")),
            IconSlot("tv", "电视", listOf("tv")),
            IconSlot("headset", "有线耳机", listOf("headset")),
            IconSlot("wireless_headset", "无线耳机", listOf("wireless_headset")),
        ),
    ),
)

private val ICON_SLOTS: List<IconSlot> = ICON_SLOT_GROUPS.flatMap { it.slots }

/** 图标槽位的设置键（`status_bar_icons.wifi` 这种）。注入侧按它取「这个名字该显示在哪」。 */
fun iconSlotKey(suffix: String): String = "status_bar_icons.$suffix"

/**
 * 槽位键 → 该槽位在 ROM 里的候选名。
 *
 * 从**目录**派生给注入侧用，而不是在 hook 里再写一份：两处各写一份的结果是
 * 「界面列出来的图标」与「hook 真正动手的图标」不是同一批，而这种错在界面上完全看不出来。
 */
val ICON_SLOT_NAMES: Map<String, List<String>> =
    ICON_SLOTS.associate { iconSlotKey(it.suffix) to it.names }

val STATUS_BAR_FEATURES: List<HyperFeature> = listOf(
    HyperFeature(
        id = "status_bar_icons",
        title = "状态栏图标",
        summary = "设置系统图标的显隐与显示位置",
        scopes = listOf("systemui"),
        origin = "西米露 / HyperCeiler · StatusBarIcon、NotificationIconColumns、HideBatteryIcon",
        license = "AGPL-3.0",
        defaultEnabled = false,
        config = buildList {
            // 顺序照抄参考项目页面（分组与组内顺序都一致）。配置区是**按声明顺序分组渲染**的，
            // 所以这里的顺序就是用户看到的顺序，不是随便排的。
            ICON_SLOT_GROUPS.forEach { group ->
                group.slots.forEach { slot ->
                    add(
                        HyperChoice(
                            key = slot.key,
                            title = slot.title,
                            group = group.title,
                            entries = ICON_MODES,
                        ),
                    )
                    // 「WIFI 标准」紧跟在 WIFI 图标后面：它是同一个图标的另一个方面
                    // （图标旁边那个 5 / 6 的小角标）。
                    if (slot.suffix == "wifi") {
                        add(
                            HyperChoice(
                                key = WIFI_STANDARD_KEY,
                                title = "WIFI 标准",
                                group = group.title,
                                entries = ICON_MODES_HIDE_ONLY,
                            ),
                        )
                    }
                }
            }
            add(
                HyperSlider(
                    key = "status_bar_icons.notification_icon_max_value",
                    title = "最大数量",
                    summary = "配合上面的「通知图标最大数量」使用",
                    group = "通知图标",
                    min = 1,
                    max = 15,
                    default = 3,
                    unit = " 个",
                ),
            )
        },
        options = listOf(
            HyperOption(
                id = "status_bar_icons.wifi_activity",
                title = "隐藏 WIFI 网络活动指示器",
                summary = "WIFI 图标上的上下行小箭头",
                defaultEnabled = false,
                group = "网络连接",
            ),
            HyperOption(
                id = "status_bar_icons.notification_icon_max",
                title = "通知图标最大数量",
                summary = "限制状态栏通知图标数量",
                defaultEnabled = false,
                group = "通知图标",
            ),
            HyperOption(
                id = "status_bar_icons.battery_percent",
                title = "隐藏电量百分比",
                defaultEnabled = false,
                group = "电池",
            ),
            HyperOption(
                id = "status_bar_icons.battery_percent_mark",
                title = "隐藏电量百分比符号",
                summary = "仅隐藏 % 符号",
                defaultEnabled = false,
                group = "电池",
            ),
            HyperOption(
                id = "status_bar_icons.battery_charging",
                title = "隐藏充电指示器",
                defaultEnabled = false,
                group = "电池",
            ),
            HyperOption(
                id = "status_bar_icons.battery_icon",
                title = "隐藏电池图标",
                defaultEnabled = false,
                group = "电池",
            ),
            HyperOption(
                id = "status_bar_icons.hide_mute",
                title = "隐藏麦克风静音图标",
                defaultEnabled = false,
                group = "通话",
            ),
            HyperOption(
                id = "status_bar_icons.hide_speakerphone",
                title = "隐藏免提图标",
                defaultEnabled = false,
                group = "通话",
            ),
            HyperOption(
                id = "status_bar_icons.hide_call_record",
                title = "隐藏录音图标",
                defaultEnabled = false,
                group = "通话",
            ),
            HyperOption(
                id = "status_bar_icons.stealth",
                title = "隐藏隐身模式图标",
                defaultEnabled = false,
                group = "状态信息",
            ),
        ),
    ),

    HyperFeature(
        id = "status_bar_battery_style",
        title = "电池自定义",
        summary = "调整电量百分比样式与位置",
        scopes = listOf("systemui"),
        origin = "西米露 / HyperCeiler · BatteryStyle",
        license = "AGPL-3.0",
        defaultEnabled = false,
        options = listOf(
            HyperOption(
                id = "status_bar_battery_style.change_location",
                title = "交换电池图标与百分比位置",
                summary = "将百分比移到电池另一侧",
                defaultEnabled = false,
                group = "常规",
            ),
            HyperOption(
                id = "status_bar_battery_style.custom",
                title = "启用修改",
                summary = "启用自定义字号与间距",
                defaultEnabled = false,
                group = "扩展",
            ),
            HyperOption(
                id = "status_bar_battery_style.bold",
                title = "加粗",
                defaultEnabled = false,
                group = "扩展",
            ),
        ),
        config = listOf(
            HyperSlider(
                key = "status_bar_battery_style.font_size",
                title = "电量百分比字体大小",
                group = "扩展",
                min = 15,
                max = 40,
                default = 15,
                divisor = 2,
                unit = " dp",
            ),
            HyperSlider(
                key = "status_bar_battery_style.font_mark_size",
                title = "电量百分比符号字体大小",
                group = "扩展",
                min = 15,
                max = 40,
                default = 15,
                divisor = 2,
                unit = " dp",
            ),
            HyperSlider(
                key = "status_bar_battery_style.left_margin",
                title = "左边距",
                group = "扩展",
                min = 0,
                max = 10,
                default = 0,
                divisor = 2,
                unit = " dp",
            ),
            HyperSlider(
                key = "status_bar_battery_style.right_margin",
                title = "右边距",
                group = "扩展",
                min = 0,
                max = 10,
                default = 0,
                divisor = 2,
                unit = " dp",
            ),
            HyperSlider(
                key = "status_bar_battery_style.vertical_offset",
                title = "上下偏移量",
                group = "扩展",
                min = 0,
                max = 24,
                default = 12,
                divisor = 2,
                unit = " dp",
            ),
            HyperSlider(
                key = "status_bar_battery_style.vertical_offset_mark",
                title = "电量百分比符号上下偏移量",
                group = "扩展",
                min = 0,
                max = 27,
                default = 27,
                divisor = 2,
                unit = " dp",
            ),
        ),
    ),

    HyperFeature(
        id = "status_bar_mobile",
        title = "移动网络",
        summary = "调整信号显示逻辑与各类网络图标",
        scopes = listOf("systemui"),
        origin = "西米露 / HyperCeiler · MobilePublicHookV（HideVoWiFiIcon 并入）",
        license = "AGPL-3.0",
        defaultEnabled = false,
        requirement = "仅隐藏信号图标，不停用 SIM 卡。仅在连接时显示、仅显示上网卡模式下，隐藏 SIM 卡选项不生效。修改后重启系统界面生效。",
        config = listOf(
            HyperChoice(
                key = MobileSignalSettings.MODE,
                title = "移动信号显示逻辑",
                entries = listOf(
                    ChoiceEntry("0", "默认"),
                    ChoiceEntry("1", "非 WiFi 下始终显示"),
                    ChoiceEntry("2", "仅在连接时显示"),
                    ChoiceEntry("3", "仅显示上网卡"),
                ),
            ),
        ),
        options = listOf(
            HyperOption(
                id = MobileSignalSettings.HIDE_SIM_1,
                title = "隐藏 SIM 卡 1 信号图标",
                defaultEnabled = false,
            ),
            HyperOption(
                id = MobileSignalSettings.HIDE_SIM_2,
                title = "隐藏 SIM 卡 2 信号图标",
                defaultEnabled = false,
            ),
            HyperOption(
                id = "status_bar_mobile.hide_roaming",
                title = "隐藏漫游图标",
                summary = "隐藏两种漫游标记",
                defaultEnabled = false,
            ),
            HyperOption(
                id = "status_bar_mobile.hide_vowifi",
                title = "隐藏 VoWiFi 图标",
                defaultEnabled = false,
            ),
            HyperOption(
                id = "status_bar_mobile.hide_volte",
                title = "隐藏 VoLTE 图标",
                defaultEnabled = false,
            ),
            HyperOption(
                id = "status_bar_mobile.hide_indicator",
                title = "隐藏移动网络活动指示器",
                summary = "信号图标上的上下行小箭头",
                defaultEnabled = false,
            ),
        ),
    ),

    HyperFeature(
        id = "status_bar_network_speed",
        title = "网速指示器",
        summary = "调整网速样式、单位与刷新间隔",
        scopes = listOf("systemui"),
        origin = "西米露 / HyperCeiler · NewNetworkSpeed、NewNetworkSpeedStyle、" +
            "NetworkSpeedSpacing、NetworkSpeedSec",
        license = "AGPL-3.0",
        defaultEnabled = false,
        requirement = "网速按流量差值计算，可能与系统数值不同。",
        options = listOf(
            HyperOption(
                id = "status_bar_network_speed.font_size_enable",
                title = "修改网速字体大小",
                summary = "启用自定义字号",
                defaultEnabled = false,
                group = "常规",
            ),
            HyperOption(
                id = "status_bar_network_speed.hide",
                title = "隐藏慢速",
                summary = "低网速时隐藏",
                defaultEnabled = false,
                group = "常规",
            ),
            HyperOption(
                id = "status_bar_network_speed.hide_all",
                title = "当上下行均为慢速时隐藏",
                defaultEnabled = false,
                group = "常规",
            ),
            HyperOption(
                id = "status_bar_network_speed.sec_unit",
                title = "网速隐藏 *b/s 单位",
                defaultEnabled = false,
                group = "常规",
            ),
            HyperOption(
                id = "status_bar_network_speed.swap_places",
                title = "交换上下行网速与图标位置",
                defaultEnabled = false,
                group = "扩展",
            ),
        ),
        config = listOf(
            HyperSlider(
                key = "status_bar_network_speed.font_size",
                title = "网速字体大小",
                group = "常规",
                min = 8,
                max = 40,
                default = 13,
                divisor = 2,
                unit = " dp",
            ),
            HyperChoice(
                key = "status_bar_network_speed.font_style",
                title = "网速字体样式",
                group = "常规",
                entries = listOf(
                    ChoiceEntry("0", "官方预设字体"),
                    ChoiceEntry("1", "默认字体"),
                    ChoiceEntry("2", "默认字体-粗"),
                ),
            ),
            HyperSlider(
                key = "status_bar_network_speed.hide_slow",
                title = "慢速水平",
                summary = "低于此值时使用慢速样式",
                group = "常规",
                min = 1,
                max = 2048,
                default = 64,
                unit = " KB/s",
            ),
            HyperSlider(
                key = "status_bar_network_speed.update_spacing",
                title = "网速更新间隔",
                group = "常规",
                min = 10,
                max = 100,
                default = 40,
                divisor = 10,
                unit = " s",
            ),
            HyperChoice(
                key = "status_bar_network_speed.style",
                title = "网速指示器样式",
                group = "扩展",
                entries = listOf(
                    ChoiceEntry("0", "默认"),
                    ChoiceEntry("1", "值和单位单行显示"),
                    ChoiceEntry("2", "值和单位双排显示"),
                    ChoiceEntry("3", "上下行网速单行显示"),
                    ChoiceEntry("4", "上下行网速双排显示"),
                ),
            ),
            HyperChoice(
                key = "status_bar_network_speed.icon",
                // 候选顺序照抄参考项目，但把默认那把箭头放到第一位：HyperChoice 的出厂值
                // 就是第一个候选，而参考项目这一项的默认值正是「箭头」（值 2）。
                title = "指示器图标",
                group = "扩展",
                entries = listOf(
                    ChoiceEntry("2", "上下行箭头"),
                    ChoiceEntry("1", "无图标"),
                    ChoiceEntry("3", "细箭头"),
                    ChoiceEntry("4", "圆形箭头"),
                    ChoiceEntry("5", "细长箭头"),
                    ChoiceEntry("6", "粗长箭头"),
                ),
            ),
            HyperChoice(
                key = "status_bar_network_speed.align",
                title = "水平对齐",
                group = "扩展",
                entries = listOf(
                    ChoiceEntry("1", "默认"),
                    ChoiceEntry("2", "左侧"),
                    ChoiceEntry("3", "居中"),
                    ChoiceEntry("4", "右侧"),
                ),
            ),
            HyperSlider(
                key = "status_bar_network_speed.fixedcontent_width",
                title = "固定宽度",
                summary = "防止相邻元素左右抖动",
                group = "扩展",
                min = 10,
                max = 150,
                default = 10,
                unit = " dp",
            ),
            HyperSlider(
                key = "status_bar_network_speed.spacing_margin",
                title = "行间距",
                group = "扩展",
                min = 14,
                max = 22,
                default = 16,
                divisor = 20,
                unit = " dp",
            ),
            HyperSlider(
                key = "status_bar_network_speed.left_margin",
                title = "左边距",
                group = "扩展",
                min = 0,
                max = 16,
                default = 0,
                divisor = 2,
                unit = " dp",
            ),
            HyperSlider(
                key = "status_bar_network_speed.right_margin",
                title = "右边距",
                group = "扩展",
                min = 0,
                max = 16,
                default = 0,
                divisor = 2,
                unit = " dp",
            ),
            HyperSlider(
                key = "status_bar_network_speed.vertical_offset",
                title = "上下偏移量",
                group = "扩展",
                min = 0,
                max = 80,
                default = 40,
                divisor = 10,
                unit = " dp",
            ),
        ),
    ),

    HyperFeature(
        id = "status_bar_clock",
        title = "时钟指示器",
        summary = "调整时钟格式、字号与位置",
        scopes = listOf("systemui"),
        origin = "西米露 / HyperCeiler · StatusBarClockNew",
        license = "AGPL-3.0",
        defaultEnabled = false,
        requirement = "使用 MIUI 时间格式，如 HH:mm、M/d E。",
        options = listOf(
            HyperOption(
                id = "status_bar_clock.bold",
                title = "加粗",
                defaultEnabled = false,
            ),
        ),
        config = listOf(
            HyperSlider(
                key = "status_bar_clock.size",
                title = "时钟大小",
                group = "状态栏时钟",
                min = 7,
                max = 20,
                default = 12,
                unit = " dp",
            ),
            HyperSlider(
                key = "status_bar_clock.left_margin",
                title = "左边距",
                group = "状态栏时钟",
                min = 0,
                max = 40,
                default = 0,
                unit = " dp",
            ),
            HyperSlider(
                key = "status_bar_clock.right_margin",
                title = "右边距",
                group = "状态栏时钟",
                min = 0,
                max = 40,
                default = 0,
                unit = " dp",
            ),
            HyperSlider(
                key = "status_bar_clock.vertical_offset",
                title = "上下偏移量",
                group = "状态栏时钟",
                min = 0,
                max = 24,
                default = 12,
                divisor = 2,
                unit = " dp",
            ),
            HyperText(
                key = "status_bar_clock.editor_s",
                title = "状态栏时钟格式",
                summary = "留空使用系统格式",
                group = "时钟格式",
                placeholder = "HH:mm",
            ),
            HyperText(
                key = "status_bar_clock.editor_b",
                title = "通知中心大时钟格式",
                summary = "留空使用系统格式",
                group = "时钟格式",
                placeholder = "HH:mm",
            ),
            HyperText(
                key = "status_bar_clock.editor_n",
                title = "通知中心迷你时钟格式",
                summary = "留空使用系统格式",
                group = "时钟格式",
                placeholder = "M/d E",
            ),
            HyperText(
                key = "status_bar_clock.editor_p",
                title = "状态栏日期时钟格式",
                summary = "仅限平板；留空使用系统格式",
                group = "时钟格式",
                placeholder = "M/d E",
            ),
        ),
    ),

    HyperFeature(
        id = "status_bar_double_tap",
        title = "双击状态栏锁屏",
        summary = "双击状态栏空白处息屏",
        scopes = listOf("systemui"),
        origin = "西米露 / HyperCeiler · DoubleTapToSleep",
        license = "AGPL-3.0",
        defaultEnabled = false,
    ),

    HyperFeature(
        id = "status_bar_screenshot_hide",
        title = "截屏时隐藏状态栏",
        summary = "截图不显示时间、电量和通知图标",
        // Keep the entry on the SystemUI page; capture itself only needs the screenshot host.
        scopes = listOf("systemui", "screenshot"),
        origin = "西米露 / HyperCeiler · HideStatusBarBeforeScreenshot",
        license = "AGPL-3.0",
        defaultEnabled = false,
        requirement = "需启用截屏作用域。OS4 原生取图时排除状态栏图层，不改变屏幕上状态栏的可见性；背屏和不支持图层排除的旧宿主保持原样。待真机验收。",
    ),
).map { it.copy(group = ScopeFeatureGroup.STATUS_BAR) }
