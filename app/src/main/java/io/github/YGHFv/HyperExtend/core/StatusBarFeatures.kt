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
 * SystemUI status-bar catalog. Current member evidence is version-bounded, not
 * proof of full upstream parity. Missing old class names do not establish that
 * island, battery-detail, alarm timing, or Wi-Fi/mobile reordering cannot migrate.
 * Those paths and every explicitly partial record remain in the recovery queue.
 */

/** 图标槽位的五态。取值与语义同参考项目：`RIGHT_BLOCK_LIST` / `CONTROL_CENTER_BLOCK_LIST` 的两张黑名单。 */
private val ICON_MODES = listOf(
    ChoiceEntry("0", "默认"),
    ChoiceEntry("1", "始终显示"),
    ChoiceEntry("11", "仅在控制中心显示"),
    ChoiceEntry("12", "仅在状态栏显示"),
    ChoiceEntry("2", "始终隐藏"),
)

/** Preserve native Wi-Fi availability; show also allows the native 4/5 standard values. */
private val WIFI_STANDARD_MODES = listOf(
    ChoiceEntry("0", "默认"),
    ChoiceEntry("1", "始终显示"),
    ChoiceEntry("2", "始终隐藏"),
)

/**
 * WIFI 标准的设置键。
 *
 * 它不是「某个槽位」而是宿主算出来的一个值（见 [WIFI_STANDARD_MODES]），所以不进
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

/** Catalog mappings retained while producer evidence is completed.
 * A candidate alias is NOT migration evidence. In particular no_sim, hd and
 * record are not validated by the current exact-string searches; the recovery
 * log tracks their real producer/resource routes separately.
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
        id = IslandMediaSettings.BACKGROUND,
        title = "灵动岛媒体背景",
        summary = "为原生主视图及过渡视图独立配置封面、配色和氛围光",
        scopes = listOf("systemui"),
        origin = "HyperCeiler / CustomBackground / AmbientLight",
        license = "AGPL-3.0",
        defaultEnabled = false,
        requirement = "仅系统界面 202602260 的主屏亮屏解锁状态。保留原生 MusicBgView、着色器子视图、同步和变换；只管理自有背景、配色及子视图透明度，关闭、隐藏、分离或安全模式恢复仍归模块所有的值。原生着色器并未停止运行，不承诺省电。使用有界封面采样和莫奈，原生进度着色器保持形状与透明度；并非完整 AGSL、转场和对比度验收。源码未编译；首次启用需要用户重启宿主。",
        config = IslandMediaSettings.background,
        options = IslandMediaSettings.backgroundOptions,
    ),
    HyperFeature(
        id = IslandMediaSettings.PROGRESS,
        title = "灵动岛媒体进度条",
        summary = "为独立媒体面板调整原生粗细或绘制波浪、直线",
        scopes = listOf("systemui"),
        origin = "HyperCeiler · MediaProgress（当前独立 Binder/Holder 适配）",
        license = "AGPL-3.0",
        defaultEnabled = false,
        requirement = "仅当前主屏解锁、亮屏的展开媒体及过渡副本，不作用普通通知媒体。保留原生 SeekBar、范围、监听器、会话、拖动、无障碍和 RenderState；按压/拖动、锁屏/息屏保持原生。原生粗细仅在绘制期间临时修改，结束恢复；自定义样式使用实际主题颜色，不假定高端 shader 的旧颜色字段有效。动画服从原生播放/监听/拖动状态及系统动画开关，不启动轮询任务。关闭后下一帧恢复，首次启用需用户重启；仍非原生 shader 全量视觉一致，源码未编译或真机验收。",
        config = IslandMediaSettings.progress,
        options = IslandMediaSettings.progressOptions,
    ),
    HyperFeature(
        id = "status_bar_hide_strong_toast",
        title = "隐藏强提醒",
        summary = "阻止外部强提醒命令新建临时灵动岛提示",
        scopes = listOf("systemui"),
        origin = "HyperCeiler · HideStrongToast（当前设备通知入口适配）",
        license = "AGPL-3.0",
        defaultEnabled = false,
        requirement = "仅系统界面 202602260 主屏已解析的 strong_toast_action。保留原生解析和日志，不取消整帧绘制，不隐藏全局灵动岛窗口，不干预内部充电动画、移除或普通通知。关闭后下一条命令恢复，已显示的提示由原生到期清理；首次启用需用户重启。旧格式测试动作与其他灵动岛消息不由本功能改写。源码未编译，未真机验收。",
    ),
    HyperFeature(
        id = IslandMediaSettings.LAYOUT,
        title = "灵动岛媒体布局",
        summary = "独立调整灵动岛展开媒体的布局与操作图标",
        scopes = listOf("systemui"),
        origin = "HyperCeiler · MediaViewLayout（当前独立 Binder/Holder 适配）",
        license = "AGPL-3.0",
        defaultEnabled = false,
        requirement = "仅当前主屏解锁媒体，真实与过渡副本使用同一布局。只复制当前原生 ConstraintSet，不替换视图、点击或播放会话。图标尺寸不缩小触摸区域；140 保留原生，语义中间三槽为主要操作，其他槽及普通通知动作使用自定义尺寸。布局更改/关闭需用户重启重建；自有字号、图标矩阵与可见性在分离、关闭或安全模式下恢复未被原生接管的值。完整背景、进度与 AOD 仍待适配。源码未编译或真机验收。",
        options = IslandMediaSettings.options,
        config = IslandMediaSettings.layout,
    ),
    HyperFeature(
        id = IslandMediaSettings.TEXT,
        title = "灵动岛媒体字号",
        summary = "独立调整标题、艺术家与播放时间字号",
        scopes = listOf("systemui"),
        origin = "HyperCeiler · MediaViewSize（当前独立 Binder/Holder 适配）",
        license = "AGPL-3.0",
        defaultEnabled = false,
        requirement = "主屏解锁展开媒体及其过渡副本；保留原生文字、字体、颜色和无障碍信息。字号为 sp，与上游实际 SP 设置一致而非 XML 的 dp 标签；关闭、分离或安全模式恢复自有改动，原生新值优先成为恢复基线。首次启用需用户重启，未编译或真机验收。",
        config = IslandMediaSettings.text,
    ),
    HyperFeature(
        id = "status_bar_signal_order",
        title = "交换 WIFI 与移动网络位置",
        summary = "在原生图标列表发布前调整信号组顺序",
        scopes = listOf("systemui"),
        origin = "HyperCeiler · SwapWiFiAndMobileNetwork（当前宿主适配）",
        license = "AGPL-3.0",
        defaultEnabled = false,
        requirement = "仅系统界面 202602260 的新建图标列表。保留每个原生槽位及其对象，WIFI 与移动信号组交换，不重复插入热点、飞行模式或无 SIM 槽位。不调整已绑定视图，不改变网络、SIM、显示/隐藏规则或双卡内部顺序。列表按宿主设计共享，各使用方会看到同一顺序，并非仅主屏。首次启用、关闭、修改与安全模式完整恢复需用户重启。当前源码未编译，演示、双卡、外屏和各面板显示待统一验收。",
    ),
    HyperFeature(
        id = BatteryDetailSettings.FEATURE,
        title = "状态栏电池信息",
        summary = "在左侧时钟旁显示温度、电流与电池侧估算功率",
        scopes = listOf("systemui"),
        origin = "HyperCeiler · BatteryDetailIndicator（当前宿主适配）",
        license = "AGPL-3.0",
        defaultEnabled = false,
        requirement = "仅当前主屏解锁状态栏，跟随原生时钟可见性、颜色及剩余宽度。不替换时钟或网速，不读写 sysfs。读数来自 BatteryManager 和电池广播，异常或过期读数不显示；功率不是充电器输入功率。不可见、锁屏、息屏、解绑或安全模式停止采样并移除自有文字。关闭后下次视图回调清理；首次启用和配置变更需用户重启。右侧独立图标槽位仍待适配，未提供空开关。此实现尚未编译或真机验收。",
        options = BatteryDetailSettings.options,
        config = BatteryDetailSettings.config,
    ),
    HyperFeature(
        id = "status_bar_icons",
        title = "状态栏图标",
        summary = "设置系统图标的显隐与显示位置",
        requirement = "仅系统界面 202602260。图标名单改为原生实例列表及真实父容器过滤，不在安装期改共享静态名单；保留系统精简模式、用户隐藏、可用性与灵动岛空间限制。WIFI 小箭头/标准隐藏接原生绑定与显示回调，不替换状态流；标准号强显仅恢复原生已计算但被过滤的 4/5，通过临时接口转发保留原生挂起/恢复。强显作用于原生共享视图模型，不承诺仅主屏；隐藏仍只作用主屏。通知数量仅主屏状态栏，在原生绑定前复制数量输入，保留通知筛选、失败计数及布局，AOD/通知架保持原生。电池隐藏与自定义样式使用同一组精确刷新入口。流量节省槽位改由模块补建宿主自己不再创建的原生 holder（宿主 onDataSaverChanged 只调用 setIconVisibility），只在用户显式选四档之一时安装，且只补建缺失 holder、不替换已有 holder。改动仍需重建宿主；已有列表在下一次原生更新恢复，WIFI 自有隐藏在分离/下次绘制关闭时恢复；电池和安全模式完整恢复需用户重启。无 SIM、HD、录音等尚无完整当前槽位生产证据，不因本次链路修复视为迁完。当前源码未编译或真机验收。",
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
                            entries = if (slot.suffix == "alarm_clock")
                                ICON_MODES + ChoiceEntry("3", "响铃前显示") else ICON_MODES,
                        ),
                    )
                    if (slot.suffix == "alarm_clock") {
                        add(HyperSlider("status_bar_icons.alarm_clock_hours", "提前显示小时数",
                            0, 48, 0, unit = " h", group = group.title,
                            summary = "配合响铃前显示；0 不干预。使用当前用户已有闹钟时间，最多 30 秒刷新，未知时间保持原生。不更改闹钟或锁屏闹钟文字；完整恢复及首次启用需用户重启"))
                    }
                    // 「WIFI 标准」紧跟在 WIFI 图标后面：它是同一个图标的另一个方面
                    // （图标旁边那个 5 / 6 的小角标）。
                    if (slot.suffix == "wifi") {
                        add(
                            HyperChoice(
                                key = WIFI_STANDARD_KEY,
                                title = "WIFI 标准",
                                group = group.title,
                            entries = WIFI_STANDARD_MODES,
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
        requirement = "仅系统界面 202602260 主屏。四个精确刷新入口共同就绪后生效，保留原生电量/充电/主题更新。加粗保留原生字体家族；隐藏百分号时右边距归可见数字，百分号默认偏移保留原生基线。尺寸限制与界面滑块一致，隐藏策略优先，不显示原本隐藏的文字。设置固定于宿主创建时，关闭、修改和安全模式完整恢复需要用户重启系统界面；不进行在线重排恢复。新的边距/精确签名修复尚未编译，非真机验收。",
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
        requirement = "仅隐藏图标，不停用 SIM 或改变通话/网络。仅在连接时显示、仅显示上网卡模式下，隐藏 SIM 卡选项不生效。漫游、活动指示器与 VoWiFi 只处理主屏原生显示回调，保留有效更新标记、动画容器及订阅取消，不向具体 Flow 字段写入不兼容类型。VoLTE 仅在当前宿主完整生成尚未发布的显示配置后置隐藏标记，保留其余运营商字段，不在构造或安装期初始化服务；此配置按原生设计跨显示共享。关闭后等待原生回调/配置重建，缓存、旧版本替换流和安全模式完整恢复需用户重启。新迁移未编译，非真机验收。",
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
        id = MobileTypeDisplaySettings.FEATURE,
        title = "移动网络类型显示",
        summary = "设置类型显示逻辑、独立文字及字号与位置",
        scopes = listOf("systemui"),
        origin = "西米露 / HyperCeiler · MobileTypeSingle2Hook（OS4 适配）",
        license = "AGPL-3.0",
        defaultEnabled = false,
        requirement = "沿用原生类型文字、测量与动画，不修改网络连接。无服务不保留旧类型；卫星与未知状态回退原生。独立显示关闭时字号与位置不生效；始终隐藏优先。与双排信号组合时由上网卡承载类型。待统一真机验收。",
        options = listOf(
            HyperOption(MobileTypeDisplaySettings.SEPARATE, "移动网络类型图标单独显示", defaultEnabled = false),
            HyperOption(MobileTypeDisplaySettings.LEFT, "显示在信号左侧", "仅独立显示生效；关闭后显示在右侧", defaultEnabled = false),
            HyperOption(MobileTypeDisplaySettings.BOLD, "加粗", "仅独立显示生效", defaultEnabled = false),
        ),
        config = listOf(
            HyperChoice(MobileTypeDisplaySettings.MODE, "显示逻辑", entries = listOf(
                ChoiceEntry("0", "默认"), ChoiceEntry("1", "始终显示"), ChoiceEntry("2", "非 WiFi 下显示"),
                ChoiceEntry("3", "始终隐藏"), ChoiceEntry("4", "仅在移动数据连接时显示"),
            ), summary = "始终显示仍要求有效蜂窝服务和非空类型；默认保留系统显隐条件"),
            HyperSlider(MobileTypeDisplaySettings.SIZE, "字体大小", min = 18, max = 40, default = 27,
                divisor = 2, unit = " dp", group = "独立显示"),
            HyperSlider(MobileTypeDisplaySettings.LEFT_MARGIN, "左边距", min = 0, max = 16, default = 0,
                divisor = 2, unit = " dp", group = "独立显示"),
            HyperSlider(MobileTypeDisplaySettings.RIGHT_MARGIN, "右边距", min = 0, max = 16, default = 0,
                divisor = 2, unit = " dp", group = "独立显示"),
            HyperSlider(MobileTypeDisplaySettings.VERTICAL, "上下偏移量", min = -40, max = 40, default = 0,
                divisor = 10, unit = " dp", group = "独立显示"),
        ),
    ),

    HyperFeature(
        id = DualRowSignalSettings.FEATURE,
        title = "双排移动网络图标",
        summary = "将双卡信号合并显示，可调整样式与位置",
        scopes = listOf("systemui"),
        origin = "西米露 / HyperCeiler · DualRowSignalHookV（OS4 适配）",
        license = "AGPL-3.0",
        defaultEnabled = false,
        requirement = "需要两张有效 SIM 卡。移动信号显示逻辑须为默认，且不能隐藏任意 SIM 卡。上排为默认上网卡；单卡、飞行模式、卫星或未知信号状态恢复系统显示。已完成静态适配，双卡实机效果待验证。修改后重启系统界面生效。",
        config = listOf(
            HyperChoice(
                key = DualRowSignalSettings.STYLE,
                title = "图标样式",
                entries = listOf(ChoiceEntry("", "默认"), ChoiceEntry("classic", "经典"),
                    ChoiceEntry("thick", "粗体"), ChoiceEntry("theme", "主题")),
            ),
            HyperSlider(DualRowSignalSettings.SCALE, "图标大小", 70, 140, 100, unit = "%"),
            HyperSlider(DualRowSignalSettings.LEFT, "左边距", -8, 8, 0, divisor = 2, unit = " dp"),
            HyperSlider(DualRowSignalSettings.RIGHT, "右边距", -8, 8, 0, divisor = 2, unit = " dp"),
            HyperSlider(DualRowSignalSettings.VERTICAL, "上下偏移量", -40, 40, 0, divisor = 10, unit = " dp"),
        ),
    ),

    HyperFeature(
        id = MobileTypeTextSettings.FEATURE,
        title = "自定义移动网络类型文本",
        summary = "替换信号旁的网络类型文字，不改变网络制式",
        scopes = listOf("systemui"),
        origin = "西米露 / HyperCeiler · MobileTypeTextCustom",
        license = "AGPL-3.0",
        defaultEnabled = false,
        requirement = "只替换系统原本显示的网络类型名称，不强制显示隐藏的图标、不伪造信号或网络连接。留空恢复系统名称；最多 8 个字符，不支持换行或方向控制符。与双排图标可同时使用，文字跟随默认上网卡。修改后重启系统界面生效，待真机验收。",
        config = listOf(HyperText(MobileTypeTextSettings.TEXT, "网络类型文本", placeholder = "例如 5G；留空跟随系统")),
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
        requirement = "仅系统界面 202602260 的原生网速控制器与视图；所有已选 hook 和精确调用方共同就绪后生效。上下行分别扣除 lo 回环流量；首次采样、任一计数不可用/重置、非一致读数或长时间休眠保留原生数值与非空单位，已确认差值才进入自定义格式，汇总防溢出。保留原生隐藏、图标阻止和消息停止策略；只调整已存在的后台采样消息，不新建采样线程或复活已停止循环。关闭后下次原生采样恢复间隔，原有字体/布局和安全模式完整恢复需重启系统界面。采样时点仍跟随原生文字回调，非后台同一快照；固定宽度、布局动画仍待验收。非真机验收。",
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
                title = "行间距倍数",
                summary = "双排文字行高的倍数，不是额外 dp 间距",
                group = "扩展",
                min = 14,
                max = 22,
                default = 16,
                divisor = 20,
                unit = " 倍",
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
        id = ClockSettings.FEATURE,
        title = "时钟指示器",
        summary = "调整各处时钟格式、双行排列、字号与位置",
        scopes = listOf("systemui"),
        origin = "西米露 / HyperCeiler · StatusBarClockNew",
        license = "AGPL-3.0",
        defaultEnabled = false,
        requirement = "使用 MIUI 时间格式。大时钟默认保留独立格式，可显式同步状态栏时间行；横屏时钟只调整样式，不替换原生格式。平板日期设置仅作用于 pad_clock。统一真机验收前不视为功能通过。",
        options = ClockSettings.options,
        config = ClockSettings.config,
    ),

    HyperFeature(
        id = "status_bar_double_tap",
        title = "双击状态栏锁屏",
        summary = "双击状态栏空白处息屏",
        requirement = "仅系统界面 202602260 主屏，观察原生 PhoneStatusBarView 已接受的背景触摸，不替换触摸监听器、不消费或重放事件。保留子控件、原生可触区域、状态栏交互和长按/下拉处理。触摸探索、遮挡、多指、鼠标/手写笔、越界、拖动、取消、分离或配置变化重置双击；包含历史移动采样。通过精确 PowerManager.goToSleep(long) 请求，失败保留原生，不提前初始化服务。关闭/安全模式停止处理，首次开启需要重建宿主；源代码修复尚未编译或实际息屏验收。",
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
        requirement = "需启用截屏作用域。仅截屏 10603038 实际选中的 ScreenCaptureInternal 策略和完整图层排除 API；旧策略、背屏和外屏保持原样。只在本次主屏取图的最终排除名单追加 StatusBar，保留原生一次性排除、工作资料名单、裁剪、安全/受保护内容检查及缓冲区释放，不改变屏幕或 Surface、不发送广播、不延时等待。关闭后下次取图恢复，首次启用需用户重启宿主。证据来自已缓存 APK/框架，不代表重新核实了当前安装；源码未编译或真机验收。",
    ),
).map { it.copy(group = ScopeFeatureGroup.STATUS_BAR) }
