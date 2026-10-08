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
 *
 * ---------------------------------------------------------------------------
 * 功能来源：西米露 / HyperCeiler 的「状态栏图标」整页（AGPL-3.0）。
 *
 * 四处挂载点，各自对应原页面里的一组设置：
 *   1. MiuiIconManagerUtils 的两张静态黑名单 —— 每个图标的「显示在哪」；
 *   2. MiuiBatteryMeterView —— 电池图标 / 百分比 / 充电指示器；
 *   3. NotificationIconContainer —— 通知图标最大数量；
 *   4. WifiRepositoryImpl / WifiViewModel —— WIFI 活动指示器与 WIFI 标准角标。
 *
 * 前两条是主力：参考项目最新版（OS3 线）也改成了直接增删那两张 `ArrayList`
 * （而不是像老版本那样去挂 setIconVisibility），因为**列表本身是可变的**，
 * 改内容不涉及 `static final` 写回，也不依赖任何方法的名称与签名。
 * 代价是槽位名必须写对 —— 本机 OS4 的 38 个槽位名是从
 * `MiuiIconManagerUtils.<clinit>` 的 `const-string` 指令里逐条读出来的
 * （`.workbuddy/tools/dexconst.py`），不是照抄旧版本。
 *
 * VoWiFi / VoLTE 归「移动网络」那一页（见 StatusBarMobile）：它们改的是运营商配置对象，
 * 与「哪些槽位进哪张黑名单」不是同一种做法，混在一个开关下会让用户以为它们是一件事。
 */

package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import android.view.View
import android.widget.TextView
import io.github.YGHFv.HyperExtend.core.ICON_SLOT_NAMES
import io.github.YGHFv.HyperExtend.core.WIFI_STANDARD_KEY
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect

/** 「状态栏图标」。作用域：com.android.systemui。 */
internal object StatusBarIcons {

    private const val FEATURE = "status_bar_icons"

    private const val OPT_NOTIFICATION_MAX = "status_bar_icons.notification_icon_max"
    private const val KEY_NOTIFICATION_MAX = "status_bar_icons.notification_icon_max_value"
    private const val OPT_BATTERY_PERCENT = "status_bar_icons.battery_percent"
    private const val OPT_BATTERY_MARK = "status_bar_icons.battery_percent_mark"
    private const val OPT_BATTERY_CHARGING = "status_bar_icons.battery_charging"
    private const val OPT_BATTERY_ICON = "status_bar_icons.battery_icon"
    private const val OPT_HIDE_MUTE = "status_bar_icons.hide_mute"
    private const val OPT_HIDE_SPEAKERPHONE = "status_bar_icons.hide_speakerphone"
    private const val OPT_HIDE_RECORD = "status_bar_icons.hide_call_record"
    private const val OPT_WIFI_ACTIVITY = "status_bar_icons.wifi_activity"
    private const val OPT_STEALTH = "status_bar_icons.stealth"

    private const val ICON_MANAGER = "com.android.systemui.statusbar.phone.MiuiIconManagerUtils"
    private const val BATTERY_VIEW = "com.android.systemui.statusbar.views.MiuiBatteryMeterView"
    private const val ICON_CONTAINER = "com.android.systemui.statusbar.phone.NotificationIconContainer"
    private const val WIFI_REPOSITORY =
        "com.android.systemui.statusbar.pipeline.wifi.data.repository.prod.WifiRepositoryImpl"
    private const val WIFI_VIEW_MODEL =
        "com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel.WifiViewModel"

    /** 黑名单列表语义：进状态栏那张表 = 不在状态栏显示，进控制中心那张表 = 不在控制中心显示。 */
    private const val MODE_DEFAULT = 0
    private const val MODE_SHOW = 1
    private const val MODE_CONTROL_ONLY = 11
    private const val MODE_STATUS_BAR_ONLY = 12
    private const val MODE_HIDE = 2

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        var installed = 0
        installed += installIconSlots(loader, settings)
        installed += installBattery(loader, settings)
        installed += installNotificationIconLimit(loader, settings)
        installed += installWifiActivity(loader, settings)
        installed += installWifiStandard(loader, settings)
        return installed
    }

    // ------------------------------------------------------------------ 图标黑名单

    /**
     * 把每个图标的「显示在哪」写进 ROM 自己那两张静态列表。
     *
     * 这里**不挂任何 hook**：列表在 ROM 的静态初始化里建好之后就一直是那一个实例，
     * 我们在宿主进程启动早期改掉它的内容，之后所有查询都会看到新内容。
     * 比起挂 `setIconVisibility` 再一个个拦截，「改数据」不受方法改名、参数变化、
     * 以及新图标走别的路径（本机 OS4 的图标已经改由 pipeline 分发）的影响。
     */
    private fun installIconSlots(loader: ClassLoader, settings: HookSettings): Int {
        val manager = Reflect.loadClass(loader, ICON_MANAGER) ?: run {
            ModuleLog.warn("$FEATURE: $ICON_MANAGER not found — icon slots skipped")
            return 0
        }
        val statusBar = iconList(manager, "RIGHT_BLOCK_LIST") ?: return 0
        val controlCenter = iconList(manager, "CONTROL_CENTER_BLOCK_LIST") ?: return 0

        var changed = 0
        ICON_SLOT_NAMES.forEach { (key, names) ->
            val mode = settings.number(key, MODE_DEFAULT)
            if (mode != MODE_DEFAULT) {
                apply(mode, names, statusBar, controlCenter)
                changed++
            }
        }

        // 三个「隐藏某个通话相关图标」与隐身模式：语义就是「两边都不显示」。
        val hides = buildList {
            if (settings.isOn(OPT_STEALTH)) add(listOf("stealth"))
            if (settings.isOn(OPT_HIDE_MUTE)) add(listOf("mute"))
            if (settings.isOn(OPT_HIDE_SPEAKERPHONE)) add(listOf("speakerphone"))
            if (settings.isOn(OPT_HIDE_RECORD)) add(listOf("record"))
        }
        hides.forEach { names ->
            apply(MODE_HIDE, names, statusBar, controlCenter)
            changed++
        }

        ModuleLog.info("$FEATURE: ${changed} icon slot(s) adjusted")
        // 改数据不算「装上了 hook」，但用户需要知道这一步做成了没有 —— 用 1 表示「动过」。
        return if (changed > 0) 1 else 0
    }

    @Suppress("UNCHECKED_CAST")
    private fun iconList(clazz: Class<*>, name: String): MutableList<Any?>? =
        Reflect.safe("$FEATURE static $name") {
            Reflect.staticFieldOrNull(clazz, name)?.get(null) as? MutableList<Any?>
        } ?: run {
            ModuleLog.warn("$FEATURE: $ICON_MANAGER#$name is gone — icon slots skipped")
            null
        }

    private fun apply(
        mode: Int,
        names: List<String>,
        statusBar: MutableList<Any?>,
        controlCenter: MutableList<Any?>,
    ) {
        for (name in names) {
            when (mode) {
                MODE_SHOW -> {
                    statusBar.remove(name)
                    controlCenter.remove(name)
                }
                // 只在控制中心：状态栏那张表里要有它，控制中心那张表里不能有。
                MODE_CONTROL_ONLY -> {
                    addIfAbsent(statusBar, name)
                    controlCenter.remove(name)
                }
                MODE_STATUS_BAR_ONLY -> {
                    statusBar.remove(name)
                    addIfAbsent(controlCenter, name)
                }
                MODE_HIDE -> {
                    addIfAbsent(statusBar, name)
                    addIfAbsent(controlCenter, name)
                }
            }
        }
    }

    private fun addIfAbsent(list: MutableList<Any?>, name: String) {
        if (!list.contains(name)) list.add(name)
    }

    // ------------------------------------------------------------------ 电池

    /**
     * 电池图标 / 百分比 / 充电指示器。
     *
     * 三个更新入口都挂：`onBatteryStyleChanged` 管样式切换、`updateAll` 管整体刷新、
     * `updateChargeAndText` 管充电与文字刷新。只挂其中一个的话，用户会看到
     * 「刚改设置时好了，插上充电器又回来了」这种时好时坏的现象。
     */
    private fun installBattery(loader: ClassLoader, settings: HookSettings): Int {
        val wantsAnything = settings.isOn(OPT_BATTERY_ICON) ||
            settings.isOn(OPT_BATTERY_PERCENT) ||
            settings.isOn(OPT_BATTERY_MARK) ||
            settings.isOn(OPT_BATTERY_CHARGING)
        if (!wantsAnything) return 0
        val view = Reflect.loadClass(loader, BATTERY_VIEW) ?: run {
            ModuleLog.warn("$FEATURE: $BATTERY_VIEW not found — battery icons skipped")
            return 0
        }
        var installed = 0
        for (name in arrayOf("onBatteryStyleChanged", "updateAll", "updateChargeAndText")) {
            val method = Reflect.findMethods(view, name).firstOrNull() ?: continue
            val ok = HookRuntime.hookAfter(method, "$FEATURE/MiuiBatteryMeterView#$name") { chain, original ->
                applyBatteryVisibility(chain.thisObject, settings)
                original
            }
            if (ok) installed++
        }
        return installed
    }

    private fun applyBatteryVisibility(target: Any?, settings: HookSettings) {
        if (target == null) return
        if (settings.isOn(OPT_BATTERY_ICON)) {
            (Reflect.readField(target, "mBatteryIconView") as? View)?.visibility = View.GONE
            // 数字样式的电池主体是另一个 View，不一起藏会剩下一个空壳。
            if (Reflect.readField(target, "mBatteryStyle") == 1) {
                (Reflect.readField(target, "mBatteryDigitalView") as? View)?.visibility = View.GONE
            }
        }
        if (settings.isOn(OPT_BATTERY_PERCENT) || settings.isOn(OPT_BATTERY_MARK)) {
            (Reflect.readField(target, "mBatteryPercentMarkView") as? TextView)?.textSize = 0f
        }
        if (settings.isOn(OPT_BATTERY_PERCENT)) {
            (Reflect.readField(target, "mBatteryPercentView") as? TextView)?.textSize = 0f
            // 本机 OS4 上这个字段已经没有了（百分比改由别处绘制），取不到就跳过。
            (Reflect.readField(target, "mBatteryTextDigitView") as? TextView)?.textSize = 0f
        }
        if (settings.isOn(OPT_BATTERY_CHARGING)) {
            (Reflect.readField(target, "mBatteryChargingView") as? View)?.visibility = View.GONE
        }
    }

    // ------------------------------------------------------------------ 通知图标数量

    /**
     * 通知图标最大数量。
     *
     * 覆盖的是 `setMaxIconsAmount` 算出来的结果（而不是去改布局参数）：
     * 那个方法正是 ROM 用来声明上限的地方，之后每次排布读的都是这个字段。
     */
    private fun installNotificationIconLimit(loader: ClassLoader, settings: HookSettings): Int {
        if (!settings.isOn(OPT_NOTIFICATION_MAX)) return 0
        val container = Reflect.loadClass(loader, ICON_CONTAINER) ?: run {
            ModuleLog.warn("$FEATURE: $ICON_CONTAINER not found — notification icon limit skipped")
            return 0
        }
        val max = settings.number(KEY_NOTIFICATION_MAX, DEFAULT_MAX_ICONS, MIN_MAX_ICONS, MAX_MAX_ICONS)
        val setter = Reflect.findMethod(container, "setMaxIconsAmount", Integer.TYPE) ?: run {
            ModuleLog.warn("$FEATURE: setMaxIconsAmount(int) is gone — notification icon limit skipped")
            return 0
        }
        val ok = HookRuntime.hookAfter(setter, "$FEATURE/NotificationIconContainer#setMaxIconsAmount") { chain, original ->
            Reflect.writeField(chain.thisObject, "mMaxIcons", max)
            original
        }
        return if (ok) 1 else 0
    }

    // ------------------------------------------------------------------ WIFI 活动指示器

    /**
     * WIFI 图标上的上下行小箭头。
     *
     * OS4 的这个状态直接由仓库里的一条 StateFlow 提供，所以做法是把那条流换成
     * 「永远是默认值」的常量流 —— 与参考项目一致。取值从同一个类的 `ACTIVITY_DEFAULT`
     * 静态字段拿，不自己构造（那个类型的构造签名各版本都不一样）。
     */
    private fun installWifiActivity(loader: ClassLoader, settings: HookSettings): Int {
        if (!settings.isOn(OPT_WIFI_ACTIVITY)) return 0
        val repository = Reflect.loadClass(loader, WIFI_REPOSITORY) ?: run {
            ModuleLog.warn("$FEATURE: $WIFI_REPOSITORY not found — wifi activity skipped")
            return 0
        }
        val constructor = repository.constructors.firstOrNull() ?: return 0
        val flows = StateFlowFactory(loader)
        val fallback = Reflect.attempt {
            Reflect.staticFieldOrNull(repository, "ACTIVITY_DEFAULT")?.get(null)
        } ?: run {
            ModuleLog.warn("$FEATURE: WifiRepositoryImpl.ACTIVITY_DEFAULT is gone — wifi activity skipped")
            return 0
        }
        val constant = flows.constant(fallback) ?: run {
            ModuleLog.warn("$FEATURE: kotlinx StateFlow unavailable — wifi activity skipped")
            return 0
        }
        val ok = HookRuntime.hookAfter(constructor, "$FEATURE/WifiRepositoryImpl#<init>") { chain, original ->
            if (!StatusBarFields.alreadyReplaced(chain.thisObject, "wifiActivity", constant)) {
                StatusBarFields.write(chain.thisObject, "wifiActivity", constant)
            }
            original
        }
        return if (ok) 1 else 0
    }

    // ------------------------------------------------------------------ WIFI 标准

    /**
     * WIFI 标准（图标旁边那个 5 / 6 的小角标）。
     *
     * OS4 把它做成了视图模型上的一条状态流（`wifiStandard`：0 表示不显示，其它值是标准号），
     * 所以「始终隐藏」就是把这条流换成恒为 0 的常量流 —— 与参考项目的做法一致，
     * 但不需要它那套按常量特征去搜宿主函数的手段（那一档我们本来也不做，
     * 理由见 `core/StatusBarFeatures` 的说明）。
     */
    private fun installWifiStandard(loader: ClassLoader, settings: HookSettings): Int {
        // 0 = 默认，2 = 始终隐藏；目录里这一项只有这两个候选。
        if (settings.number(WIFI_STANDARD_KEY, 0) != EFFECT_WIFI_STANDARD_HIDE) return 0
        val viewModel = Reflect.loadClass(loader, WIFI_VIEW_MODEL) ?: run {
            ModuleLog.warn("$FEATURE: $WIFI_VIEW_MODEL not found — wifi standard skipped")
            return 0
        }
        if (Reflect.findField(viewModel, "wifiStandard") == null) {
            ModuleLog.warn("$FEATURE: WifiViewModel#wifiStandard is gone — wifi standard skipped")
            return 0
        }
        val constant = StateFlowFactory(loader).constant(0) ?: run {
            ModuleLog.warn("$FEATURE: kotlinx StateFlow unavailable — wifi standard skipped")
            return 0
        }
        // 构造器要逐个挂、且标签里带参数个数：同名同 id 挂到两个不同的构造器上，
        // 框架会当成「同一个挂载点的两次声明」互相替换，结果是只有一个真的装上。
        val installed = viewModel.constructors.count { constructor ->
            HookRuntime.hook(
                constructor,
                "$FEATURE/WifiViewModel#<init>/${constructor.parameterCount}",
            ) { chain ->
                val result = chain.proceed()
                if (!StatusBarFields.alreadyReplaced(chain.thisObject, "wifiStandard", constant)) {
                    StatusBarFields.write(chain.thisObject, "wifiStandard", constant)
                }
                result
            }
        }
        return if (installed > 0) 1 else 0
    }

    private const val EFFECT_WIFI_STANDARD_HIDE = 2

    private const val DEFAULT_MAX_ICONS = 3
    private const val MIN_MAX_ICONS = 1
    private const val MAX_MAX_ICONS = 15
}
