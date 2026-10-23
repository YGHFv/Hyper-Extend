/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.github.YGHFv.HyperExtend.hook.feature.settings

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.NotificationChannel
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.core.SETTINGS_USB_MODE_CHOICE_KEY
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect
import io.github.YGHFv.HyperExtend.hook.UnsafeAccess
import java.lang.reflect.Field
import java.lang.reflect.Proxy
import java.util.HashSet

/**
 * 系统设置作用域功能。
 *
 * 所有靶子都用当前宿主 DEX 的类名与方法形状固定；某个方法在新版本消失时只记录警告，
 * 不阻止同一进程里的其他功能安装。
 */
internal object SettingsFeatureHooks {
    private const val MODULE_PACKAGE = "io.github.YGHFv.HyperExtend"

    private val VPN_TYPE_LABELS = arrayOf(
        "PPTP",
        "L2TP/IPSec PSK",
        "L2TP/IPSec RSA",
        "IPSec Xauth PSK",
        "IPSec Xauth RSA",
        "IPSec Hybrid RSA",
        "IKEv2/IPSec MSCHAPv2",
        "IKEv2/IPSec PSK",
        "IKEv2/IPSec RSA",
    )

    @Volatile
    private var wifiSharedKey: String? = null

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        var count = 0
        if (settings.isOn("system_settings_allow_manage_all_notifications")) {
            count += hookAllowManageAllNotifications(loader)
        }
        if (settings.isOn("system_settings_unlock_google_header")) {
            count += hookGoogleHeader(loader)
        }
        if (settings.isOn("system_settings_international_build")) {
            count += applyInternationalBuild(loader)
        }
        if (settings.isOn("system_settings_unlock_hyperai_entrance")) {
            count += hookConstant(
                loader,
                "com.android.settings.InternalDeviceUtils",
                "isAiSupported",
                1,
                "settings_ai/isAiSupported",
                true,
            )
        }
        if (settings.isOn("system_settings_unlock_taplus")) {
            count += hookConstant(
                loader,
                "com.android.settings.utils.SettingsFeatures",
                "isNeedRemoveContentExtension",
                1,
                "settings_taplus/isNeedRemoveContentExtension",
                false,
            )
        }
        if (settings.isOn("system_settings_link_turbo_all_apps")) {
            count += hookLinkTurbo(loader)
        }
        if (settings.isOn("system_settings_view_wifi_password")) {
            count += hookViewWifiPassword(loader)
        }
        if (settings.isOn("system_settings_more_vpn_types")) {
            count += hookMoreVpnTypes(loader)
        }
        if (settings.isOn("system_settings_anti_ques")) {
            count += hookAntiQues(loader)
        }
        if (settings.isOn("system_settings_new_nfc_page")) {
            count += hookConstant(
                loader,
                "com.android.settings.utils.SettingsFeatures",
                "isNeedShowMiuiNFC",
                0,
                "settings_nfc/isNeedShowMiuiNFC",
                true,
            )
        }
        if (settings.isOn("system_settings_novelty_haptic")) {
            count += hookConstant(
                loader,
                "com.android.settings.utils.SettingsFeatures",
                "isNoveltyHaptic",
                0,
                "settings_haptic/isNoveltyHaptic",
                true,
            )
        }
        if (settings.isOn("system_settings_unlock_ui_mode")) {
            count += hookAutoUiMode(loader)
        }
        if (settings.isOn("system_settings_unlock_super_resolution")) {
            count += hookScreenEnhancement(loader, "super_resolution")
        }
        if (settings.isOn("system_settings_unlock_ai_image")) {
            count += hookScreenEnhancement(loader, "ai_image")
        }
        if (settings.isOn("system_settings_unlock_memc")) {
            count += hookScreenEnhancement(loader, "memc")
        }
        if (settings.isOn("system_settings_allow_never_lock_screen")) {
            count += hookNeverLockScreen(loader)
        }
        if (settings.isOn("system_settings_more_notifications")) {
            count += hookMoreNotifications(loader)
        }
        if (settings.isOn("system_settings_overlay_permission_direct")) {
            count += hookOverlayPermission(loader)
        }
        if (settings.isOn("system_settings_accessibility_permission_direct")) {
            count += hookAccessibilityPermission(loader)
        }
        if (settings.isOn("system_settings_apps_freezer")) {
            count += hookConstant(
                loader,
                "com.android.settings.development.CachedAppsFreezerPreferenceController",
                "isAvailable",
                0,
                "settings_freezer/isAvailable",
                true,
            )
        }
        if (settings.isOn("system_settings_usb_mode")) {
            count += hookUsbMode(loader, settings)
        }
        if (settings.isOn("system_settings_unlock_max_fps")) {
            count += hookConstant(
                loader,
                "com.android.settings.development.ForcePeakRefreshRatePreferenceController",
                "isAvailable",
                0,
                "settings_fps/isAvailable",
                true,
            )
        }
        if (settings.isOn("system_settings_unlock_speed_mode")) {
            count += hookConstant(
                loader,
                "com.android.settings.development.SpeedModeToolsPreferenceController",
                "getAvailabilityStatus",
                0,
                "settings_speed/getAvailabilityStatus",
                0,
            )
        }
        if (settings.isOn("system_settings_restore_running_services")) {
            count += hookRunningServices(loader)
        }
        return count
    }

    /**
     * 当前设置宿主已不再包含旧版 `UsbModeChooserActivity`。OTG 接入模式框改由
     * `PhoneAttachedService#initDialog()` 创建，候选表固定为“传输文件 / 仅充电”。
     *
     * 这里保留上游“开启后不弹窗”的语义；配置为非零时先调用宿主自己的 `setMode(int)`
     * 应用选择，再关闭弹窗和临时服务。`setMode` 的索引以宿主 `getTitle(int)` 为准：
     * 0 是传输文件，1 是仅充电。调用失败时仍关闭弹窗，但不能伪造已经应用模式。
     */
    private fun hookUsbMode(loader: ClassLoader, settings: HookSettings): Int {
        val clazz = Reflect.loadClass(
            loader,
            "com.android.settings.connecteddevice.usb.PhoneAttachedService",
        ) ?: return missing("settings_usb/PhoneAttachedService")
        val initDialog = Reflect.findMethods(clazz, "initDialog", 0).singleOrNull {
            it.returnType == Void.TYPE
        } ?: return missing("settings_usb/PhoneAttachedService#initDialog")
        val choice = settings.string(SETTINGS_USB_MODE_CHOICE_KEY).toIntOrNull() ?: 0
        val modeIndex = when (choice) {
            1 -> 1
            2 -> 0
            else -> null
        }
        return if (HookRuntime.hookAfter(
            initDialog,
            "settings_usb/PhoneAttachedService#initDialog",
        ) { chain, _ ->
                if (modeIndex != null) {
                    val applied = Reflect.attempt {
                        Reflect.callWith(chain.thisObject, "setMode", modeIndex)
                        true
                    } == true
                    if (!applied) {
                        ModuleLog.warn("settings_usb: setMode($modeIndex) failed")
                    }
                }
                Reflect.readField(chain.thisObject, "mDialog")?.let { dialog ->
                    Reflect.attempt { Reflect.callWith(dialog, "dismiss") }
                }
                Reflect.attempt { Reflect.callWith(chain.thisObject, "stopSelf") }
            }
        ) 1 else 0
    }

    /**
     * 通知屏蔽开关在系统应用与不可屏蔽频道上会被禁用。
     *
     * 当前宿主有四个 `setupBlock()` 实现（新旧两个包名各两份），逐个在原生执行后
     * 把 `block` 项重新启用；框架层的 `NotificationChannel` 与
     * `NotificationFilterHelper` 同时解除判定。
     */
    private fun hookAllowManageAllNotifications(loader: ClassLoader): Int {
        val classes = listOf(
            "com.android.settings.notification.AppNotificationSettings",
            "com.android.settings.notification.ChannelNotificationSettings",
            "com.android.settings.notification.app.AppNotificationSettings",
            "com.android.settings.notification.app.ChannelNotificationSettings",
        )
        var count = 0
        for (name in classes) {
            val clazz = Reflect.loadClass(loader, name) ?: continue
            count += HookRuntime.hookAll(clazz, "setupBlock", "settings_notify/$name#setupBlock") { chain ->
                val original = chain.proceed()
                val preference = Reflect.callWith(chain.thisObject, "findPreference", "block")
                if (preference != null) {
                    Reflect.callWith(preference, "setEnabled", true)
                }
                original
            }
        }

        val channel = Reflect.loadClass(loader, "android.app.NotificationChannel")
        if (channel != null) {
            Reflect.findMethods(channel, "isBlockable", 0).firstOrNull {
                it.returnType == Boolean::class.javaPrimitiveType
            }?.let { method ->
                if (HookRuntime.hookReturning(method, "settings_notify/NotificationChannel#isBlockable", true)) {
                    count++
                }
            }
            Reflect.findMethods(channel, "setBlockable", 1).firstOrNull {
                it.parameterTypes[0] == Boolean::class.javaPrimitiveType
            }?.let { method ->
                if (HookRuntime.hook(method, "settings_notify/NotificationChannel#setBlockable") { chain ->
                        chain.proceed(arrayOf(true))
                    }
                ) {
                    count++
                }
            }
        }

        val helper = Reflect.loadClass(loader, "miui.util.NotificationFilterHelper")
        if (helper == null) {
            ModuleLog.warn("settings: NotificationFilterHelper missing; partial notification unlock")
            return count
        }
        count += hookAllConstant(helper, "isNotificationForcedEnabled", false, "settings_notify/forcedEnabled")
        count += hookAllConstant(helper, "isNotificationForcedFor", false, "settings_notify/forcedFor")
        count += hookAllConstant(helper, "canSystemNotificationBeBlocked", true, "settings_notify/canBlock")
        count += hookAllConstant(helper, "containNonBlockableChannel", false, "settings_notify/nonBlockable")
        count += HookRuntime.hookAll(
            helper,
            "getNotificationForcedEnabledList",
            "settings_notify/forcedList",
        ) { HashSet<Any?>() }
        return count
    }

    /**
     * 只在首页列表确实缺少 Google 入口时补入，避免国际版重复添加。
     */
    private fun hookGoogleHeader(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "com.android.settings.MiuiSettings")
            ?: return missing("settings_google/MiuiSettings")
        val method = Reflect.findMethods(clazz, "updateHeaderList", 1).firstOrNull {
            it.returnType == Void.TYPE && List::class.java.isAssignableFrom(it.parameterTypes[0])
        } ?: return missing("settings_google/updateHeaderList")
        return if (HookRuntime.hookAfter(method, "settings_google/updateHeaderList") { chain, original ->
                val list = chain.args.firstOrNull() as? List<*>
                if (list != null && !hasGoogleHeader(list, loader)) {
                    Reflect.attempt {
                        Reflect.callWith(chain.thisObject, "AddGoogleSettingsHeaders", list)
                    }
                }
                original
            }
        ) 1 else 0
    }

    private fun hasGoogleHeader(list: List<*>, loader: ClassLoader): Boolean {
        val idClass = Reflect.loadClass(loader, "com.android.settings.R\$id") ?: return false
        val target = Reflect.staticFieldOrNull(idClass, "header_google_settings")
            ?.let { field -> Reflect.attempt { field.getInt(null) } }
            ?: return false
        return list.any { item ->
            val id = (Reflect.readField(item ?: return@any false, "id") as? Number)?.toInt()
            id == target
        }
    }

    /**
     * 只改设置进程内的静态构建标记；已内联到宿主代码里的常量不会随之变化。
     */
    private fun applyInternationalBuild(loader: ClassLoader): Int {
        // 静态字段写入前必须先完成类初始化，否则 <clinit> 会把刚写入的值覆盖回机型默认值。
        val clazz = Reflect.attempt { Class.forName("miui.os.Build", true, loader) }
            ?: return missing("settings_intl/miui.os.Build")
        val field = Reflect.staticFieldOrNull(clazz, "IS_INTERNATIONAL_BUILD")
            ?: return missing("settings_intl/IS_INTERNATIONAL_BUILD")
        if (field.type != Boolean::class.javaPrimitiveType) {
            return missing("settings_intl/IS_INTERNATIONAL_BUILD type")
        }
        val written = UnsafeAccess.putStaticBoolean(field, true) &&
            Reflect.attempt { field.getBoolean(null) } == true
        return if (written) 1 else missing("settings_intl/write")
    }

    /**
     * 网络加速的默认列表来自系统服务。设置页只读取它，因此直接把默认列表替换成
     * 当前设备上的全部第三方应用即可，不修改系统服务本身。
     */
    private fun hookLinkTurbo(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(
            loader,
            "com.android.settings.wifi.linkturbo.LinkTurboClient",
        ) ?: return missing("settings_linkturbo/LinkTurboClient")
        val method = Reflect.findMethods(clazz, "getLinkTurboDefaultPn", 0).firstOrNull {
            List::class.java.isAssignableFrom(it.returnType)
        } ?: return missing("settings_linkturbo/getLinkTurboDefaultPn")
        val packages = ArrayList<String>()
        var loaded = false
        return if (HookRuntime.hook(method, "settings_linkturbo/getLinkTurboDefaultPn") { _ ->
                if (!loaded) {
                    packages += thirdPartyPackages(loader)
                    loaded = true
                }
                packages
            }
        ) 1 else 0
    }

    /**
     * 在已保存网络的删除确认框里补上 WLAN 密码。
     *
     * 当前宿主的入口是 `MiuiSavedAccessPointsWifiSettings#showDeleteDialog(WifiEntry)`，
     * 密码由 `WifiDppUtils#getPresharedKey` 从 privileged network 列表读取。只在
     * `WifiEntry.canShare()` 为真时取密码，避免把不可分享网络的内容写进对话框。
     */
    private fun hookViewWifiPassword(loader: ClassLoader): Int {
        var count = 0
        val preferenceClass = Reflect.loadClass(
            loader,
            "com.android.settings.wifi.SavedAccessPointPreference",
        )
        val bindMethod = preferenceClass?.let { clazz ->
            Reflect.findMethods(clazz, "onBindViewHolder", 1).firstOrNull {
                it.returnType == Void.TYPE
            }
        }
        if (bindMethod != null) {
            val installed = HookRuntime.hookAfter(
                bindMethod,
                "settings_wifi_password/SavedAccessPointPreference#onBindViewHolder",
            ) { chain, _ ->
                val view = Reflect.readField(chain.thisObject, "mView") as? View
                if (view != null) {
                    val buttonId = view.resources.getIdentifier(
                        "btn_delete",
                        "id",
                        "com.android.settings",
                    )
                    val button = if (buttonId == 0) null else view.findViewById<TextView>(buttonId)
                    val context = Reflect.callWith(chain.thisObject, "getContext") as? Context
                    if (button != null && context != null) {
                        button.text = moduleString(
                            context,
                            "system_settings_wifipassword_btn_title",
                            "查看",
                        )
                    }
                }
            }
            if (installed) count++
        }

        val settingsClass = Reflect.loadClass(
            loader,
            "com.android.settings.wifi.MiuiSavedAccessPointsWifiSettings",
        )
        val showDeleteDialog = settingsClass?.let { clazz ->
            Reflect.findMethods(clazz, "showDeleteDialog", 1).firstOrNull {
                it.returnType == Void.TYPE
            }
        }
        if (showDeleteDialog != null) {
            val installed = HookRuntime.hook(showDeleteDialog, "settings_wifi_password/showDeleteDialog") { chain ->
                val entry = chain.args.firstOrNull()
                wifiSharedKey = if (entry != null &&
                    Reflect.callWith(entry, "canShare") == true
                ) {
                    readWifiSharedKey(chain.thisObject, entry, loader)
                } else {
                    null
                }
                try {
                    chain.proceed()
                } finally {
                    wifiSharedKey = null
                }
            }
            if (installed) count++
        }

        val builderClass = Reflect.loadClass(loader, "miuix.appcompat.app.AlertDialog\$Builder")
        if (builderClass != null) {
            val setTitleCharSequence = Reflect.findMethod(
                builderClass,
                "setTitle",
                CharSequence::class.java,
            )
            if (setTitleCharSequence != null) {
                val installed = HookRuntime.hook(
                    setTitleCharSequence,
                    "settings_wifi_password/Builder#setTitle",
                ) { chain ->
                    if (wifiSharedKey == null) {
                        chain.proceed()
                    } else {
                        val context = Reflect.readField(chain.thisObject, "mContext") as? Context
                        chain.proceed(
                            arrayOf(
                                if (context == null) {
                                    "WiFi 详情"
                                } else {
                                    moduleString(
                                        context,
                                        "system_settings_wifi_password_dlgtitle",
                                        "WiFi 详情",
                                    )
                                },
                            ),
                        )
                    }
                }
                if (installed) count++
            }

            val setMessage = Reflect.findMethod(
                builderClass,
                "setMessage",
                CharSequence::class.java,
            )
            if (setMessage != null) {
                val installed = HookRuntime.hook(
                    setMessage,
                    "settings_wifi_password/Builder#setMessage",
                ) { chain ->
                    val password = wifiSharedKey
                    if (password == null) {
                        chain.proceed()
                    } else {
                        val context = Reflect.readField(chain.thisObject, "mContext") as? Context
                        val label = if (context == null) {
                            "密码"
                        } else {
                            moduleString(
                                context,
                                "system_settings_wifi_password_label",
                                "密码",
                            )
                        }
                        val original = chain.args.firstOrNull()?.toString().orEmpty()
                        chain.proceed(arrayOf("$original\n$label: $password"))
                    }
                }
                if (installed) count++
            }

            val dialogClass = Reflect.loadClass(loader, "miuix.appcompat.app.AlertDialog")
            val dialogOnCreate = dialogClass?.let { clazz ->
                Reflect.findMethods(clazz, "onCreate", 1).firstOrNull {
                    it.returnType == Void.TYPE
                }
            }
            if (dialogOnCreate != null) {
                val installed = HookRuntime.hookAfter(
                    dialogOnCreate,
                    "settings_wifi_password/AlertDialog#onCreate",
                ) { chain, _ ->
                    if (wifiSharedKey != null) {
                        val message = Reflect.callWith(chain.thisObject, "getMessageView")
                        if (message is TextView) message.setTextIsSelectable(true)
                    }
                }
                if (installed) count++
            }
        }

        if (count == 0) missing("settings_wifi_password/all")
        return count
    }

    private fun readWifiSharedKey(host: Any, entry: Any, loader: ClassLoader): String? {
        val wifiManager = Reflect.readField(host, "mWifiManager") ?: return null
        val config = Reflect.callWith(entry, "getWifiConfiguration") ?: return null
        val dppUtils = Reflect.loadClass(loader, "com.android.settings.wifi.dpp.WifiDppUtils")
            ?: return null
        val getPresharedKey = Reflect.findMethods(dppUtils, "getPresharedKey", 2)
            .firstOrNull { it.returnType == String::class.java }
            ?: return null
        val removeQuotes = Reflect.findMethods(dppUtils, "removeFirstAndLastDoubleQuotes", 1)
            .firstOrNull { it.returnType == String::class.java }
            ?: return null
        val raw = Reflect.attempt {
            getPresharedKey.isAccessible = true
            getPresharedKey.invoke(null, wifiManager, config) as? String
        } ?: return null
        return Reflect.attempt {
            removeQuotes.isAccessible = true
            removeQuotes.invoke(null, raw) as? String
        }?.takeIf { it.isNotBlank() }
    }

    /**
     * 当前宿主只暴露 PPTP/IKEv2 三种类型。这里同时放宽框架的 legacy 判定，
     * 改写两个页面的静态类型表，并在宿主设置完三行适配器后替换成完整九行。
     */
    private fun hookMoreVpnTypes(loader: ClassLoader): Int {
        var count = 0
        val vpnProfile = Reflect.attempt {
            Class.forName("com.android.internal.net.VpnProfile", true, loader)
        }
        val legacy = vpnProfile?.let { clazz ->
            Reflect.findMethods(clazz, "isLegacyType", 1).firstOrNull {
                it.returnType == Boolean::class.javaPrimitiveType &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            }
        }
        if (legacy != null) {
            val installed = HookRuntime.hook(legacy, "settings_vpn/VpnProfile#isLegacyType") { chain ->
                val type = chain.args.firstOrNull() as? Int
                if (type != null && type in 0..5) true else chain.proceed()
            }
            if (installed) count++
        }

        for (className in listOf(
            "com.android.settings.vpn2.MiuiVpnEditFragment",
            "com.android.settings.vpn2.ConfigDialog",
        )) {
            val clazz = Reflect.attempt {
                Class.forName(className, true, loader)
            } ?: continue
            val field = Reflect.staticFieldOrNull(clazz, "VPN_TYPES")
            if (field != null && List::class.java.isAssignableFrom(field.type)) {
                val written = UnsafeAccess.putStaticObject(field, (0..8).toList())
                if (written) {
                    count++
                } else {
                    ModuleLog.warn("settings_vpn: unable to replace $className#VPN_TYPES")
                }
            }

            val spinner = Reflect.loadClass(loader, "miuix.appcompat.widget.Spinner")
            val spinnerTypes = listOfNotNull(
                spinner,
                Spinner::class.java,
            )
            for (spinnerType in spinnerTypes) {
                val method = Reflect.findMethods(clazz, "setTypesByFeature", 1)
                    .singleOrNull {
                        it.returnType == Void.TYPE &&
                            it.parameterTypes[0] == spinnerType
                    } ?: continue
                val installed = HookRuntime.hookAfter(
                    method,
                    "settings_vpn/$className#setTypesByFeature",
                ) { chain, original ->
                    val target = chain.args.firstOrNull() as? Spinner
                        ?: return@hookAfter original
                    val context = Reflect.callWith(chain.thisObject, "getContext") as? Context
                        ?: return@hookAfter original
                    val adapter = ArrayAdapter(
                        context,
                        android.R.layout.simple_spinner_item,
                        VPN_TYPE_LABELS,
                    )
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    target.adapter = adapter
                    original
                }
                if (installed) count++
            }
        }

        if (count == 0) missing("settings_vpn/all")
        return count
    }

    /**
     * 上游在设备名称、热点名称和蓝牙名称校验期间临时把国际版标记设为 true。
     *
     * 当前宿主靶点已逐一核对：
     * - `MiuiDeviceNameEditFragment#onSave()` 与 `EditTetherFragment#onSave()`；
     * - `DeviceNameCheckManager#getDeviceNameCheckResult(Context, String, int, callback)`；
     * - `MiuiBTUtils#isSupportNameComplianceCheck(Context)` 与 `isInternationalBuild()`。
     *
     * 静态标记只在目标方法执行期间切换，并在异常路径恢复，避免影响设置进程其他逻辑。
     */
    private fun hookAntiQues(loader: ClassLoader): Int {
        val buildClass = Reflect.attempt { Class.forName("miui.os.Build", true, loader) }
            ?: return missing("settings_anti_ques/miui.os.Build")
        val field = Reflect.staticFieldOrNull(buildClass, "IS_INTERNATIONAL_BUILD")
            ?.takeIf { it.type == Boolean::class.javaPrimitiveType }
            ?: return missing("settings_anti_ques/IS_INTERNATIONAL_BUILD")
        val lock = Any()
        fun proceedAsInternational(chain: io.github.libxposed.api.XposedInterface.Chain): Any? =
            synchronized(lock) {
                val original = readStaticBoolean(field)
                    ?: return@synchronized chain.proceed()
                if (!UnsafeAccess.putStaticBoolean(field, true)) {
                    return@synchronized chain.proceed()
                }
                try {
                    chain.proceed()
                } finally {
                    UnsafeAccess.putStaticBoolean(field, original)
                }
            }

        var count = 0
        for (className in listOf(
            "com.android.settings.MiuiDeviceNameEditFragment",
            "com.android.settings.wifi.EditTetherFragment",
        )) {
            val clazz = Reflect.loadClass(loader, className) ?: continue
            val method = Reflect.findMethods(clazz, "onSave", 0).singleOrNull {
                it.returnType == Void.TYPE
            } ?: continue
            if (HookRuntime.hook(method, "settings_anti_ques/$className#onSave") {
                    chain -> proceedAsInternational(chain)
                }
            ) {
                count++
            }
        }

        val manager = Reflect.loadClass(loader, "com.android.settings.DeviceNameCheckManager")
        val callback = Reflect.loadClass(
            loader,
            "com.android.settings.DeviceNameCheckManager\$GetResultSuccessCallback",
        )
        val check = if (manager != null && callback != null) {
            Reflect.findMethods(manager, "getDeviceNameCheckResult", 4).singleOrNull {
                it.returnType == Void.TYPE &&
                    it.parameterTypes[0] == Context::class.java &&
                    it.parameterTypes[1] == String::class.java &&
                    it.parameterTypes[2] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[3] == callback
            }
        } else {
            null
        }
        if (check != null &&
            HookRuntime.hook(check, "settings_anti_ques/DeviceNameCheckManager") { chain ->
                proceedAsInternational(chain)
            }
        ) {
            count++
        }

        val bluetooth = Reflect.loadClass(loader, "com.android.settings.bluetooth.MiuiBTUtils")
        val supported = bluetooth?.let { clazz ->
            Reflect.findMethods(clazz, "isSupportNameComplianceCheck", 1).singleOrNull {
                it.returnType == Boolean::class.javaPrimitiveType &&
                    it.parameterTypes[0] == Context::class.java
            }
        }
        if (supported != null &&
            HookRuntime.hookReturning(
                supported,
                "settings_anti_ques/MiuiBTUtils#supported",
                false,
            )
        ) {
            count++
        }
        val international = bluetooth?.let { clazz ->
            Reflect.findMethods(clazz, "isInternationalBuild", 0).singleOrNull {
                it.returnType == Boolean::class.javaPrimitiveType
            }
        }
        if (international != null &&
            HookRuntime.hookReturning(
                international,
                "settings_anti_ques/MiuiBTUtils#international",
                true,
            )
        ) {
            count++
        }

        if (count == 0) missing("settings_anti_ques/all")
        return count
    }

    private fun readStaticBoolean(field: Field): Boolean? = Reflect.attempt {
        field.isAccessible = true
        field.getBoolean(null)
    }

    /**
     * 屏幕增强能力页把硬件能力拆成 image/video/S2H 三组判断；三项开关分别只解除
     * 自己对应的组，避免“开 AI”顺手把 MEMC 或超分状态也伪装成已启用。
     */
    private fun hookScreenEnhancement(loader: ClassLoader, kind: String): Int {
        val clazz = Reflect.loadClass(
            loader,
            "com.android.settings.display.ScreenEnhanceEngineStatusCheck",
        ) ?: return missing("settings_screen/$kind")
        val methods = when (kind) {
            "super_resolution" -> listOf(
                "getSrForVideoStatus",
                "getSrForImageStatus",
                "getS2hStatus",
                "isSrForVideoSupport",
                "isSrForImageSupport",
                "isS2hSupport",
            )
            "ai_image" -> listOf("isAiSupport")
            "memc" -> listOf("isMemcSupport")
            else -> return 0
        }
        var count = 0
        for (name in methods) {
            val targets = Reflect.findMethods(clazz, name).filter {
                it.returnType == Boolean::class.javaPrimitiveType
            }
            count += targets.count {
                HookRuntime.hookReturning(it, "settings_screen/$kind#$name", true)
            }
        }
        if (count == 0) missing("settings_screen/$kind")
        return count
    }

    /**
     * 当前宿主在 `removeDefaultPrefs()` 里无条件隐藏重要性、角标和锁屏开关。
     * 这里在可见性入口统一放行，并把渠道页的 `mImportance` 重新接到可写的偏好项上。
     */
    private fun hookMoreNotifications(loader: ClassLoader): Int {
        var count = 0
        val base = Reflect.loadClass(
            loader,
            "com.android.settings.notification.BaseNotificationSettings",
        )
        if (base != null) {
            val visibleMethods = Reflect.findMethods(base, "setPrefVisible", 2).filter {
                it.returnType == Void.TYPE
            }
            for (method in visibleMethods) {
                val installed = HookRuntime.hook(method, "settings_notifications/setPrefVisible") { chain ->
                    val preference = chain.args.firstOrNull()
                    val key = Reflect.callWith(preference, "getKey") as? String
                    if (key in setOf("importance", "badge", "setting_badge", "allow_keyguard")) {
                        chain.proceed(arrayOf(preference, true))
                    } else {
                        chain.proceed()
                    }
                }
                if (installed) count++
            }
        }

        for (className in listOf(
            "com.android.settings.notification.ChannelNotificationSettings",
            "com.android.settings.notification.app.ChannelNotificationSettings",
        )) {
            val clazz = Reflect.loadClass(loader, className) ?: continue
            val setup = Reflect.findMethods(clazz, "setupChannelDefaultPrefs", 0)
                .firstOrNull { it.returnType == Void.TYPE }
                ?: continue
            val installed = HookRuntime.hookAfter(
                setup,
                "settings_notifications/$className#setupChannelDefaultPrefs",
            ) { chain, original ->
                val settings = chain.thisObject
                val preference = Reflect.callWith(settings, "findPreference", "importance")
                    ?: return@hookAfter original
                Reflect.writeField(settings, "mImportance", preference)
                val backup = (Reflect.readField(settings, "mBackupImportance") as? Number)
                    ?.toInt()
                    ?: return@hookAfter original
                if (backup <= 0) return@hookAfter original
                val index = (Reflect.callWith(
                    preference,
                    "findSpinnerIndexOfValue",
                    backup.toString(),
                ) as? Number)?.toInt() ?: -1
                if (index >= 0) Reflect.callWith(preference, "setValueIndex", index)
                val listener = createImportanceListener(loader, settings)
                    ?: return@hookAfter original
                Reflect.callWith(preference, "setOnPreferenceChangeListener", listener)
                original
            }
            if (installed) count++
        }

        if (count == 0) missing("settings_notifications/all")
        return count
    }

    private fun createImportanceListener(loader: ClassLoader, settings: Any): Any? {
        val listenerClass = Reflect.loadClass(
            loader,
            "androidx.preference.Preference\$OnPreferenceChangeListener",
        ) ?: return null
        return Reflect.attempt {
            Proxy.newProxyInstance(loader, arrayOf(listenerClass)) { proxy, method, args ->
                when {
                    method.declaringClass == Any::class.java && method.name == "equals" ->
                        proxy === args?.firstOrNull()
                    method.declaringClass == Any::class.java && method.name == "hashCode" ->
                        System.identityHashCode(proxy)
                    method.declaringClass == Any::class.java && method.name == "toString" ->
                        "${listenerClass.name}@${Integer.toHexString(System.identityHashCode(proxy))}"
                    method.name != "onPreferenceChange" -> true
                    else -> {
                        val importance = (args?.getOrNull(1) as? String)?.toIntOrNull()
                        if (importance != null) {
                            Reflect.writeField(settings, "mBackupImportance", importance)
                            val channel = Reflect.readField(settings, "mChannel")
                            val backend = Reflect.readField(settings, "mBackend")
                            val pkg = Reflect.readField(settings, "mPkg")
                            val uid = (Reflect.readField(settings, "mUid") as? Number)?.toInt()
                            if (channel != null) {
                                Reflect.callWith(channel, "setImportance", importance)
                                Reflect.callWith(channel, "lockFields", 4)
                                if (backend != null && pkg != null && uid != null) {
                                    Reflect.callWith(backend, "updateChannel", pkg, uid, channel)
                                }
                            }
                            Reflect.callWith(settings, "updateDependents", false)
                        }
                        true
                    }
                }
            }
        }
    }

    /**
     * 无障碍服务列表本身没有“直达详情”的入口。请求方打开
     * `android.settings.ACCESSIBILITY_SETTINGS` 时，从 Intent 的调用包名找到其已安装服务，
     * 直接启动原生的音量键快捷方式详情页。
     */
    private fun hookAccessibilityPermission(loader: ClassLoader): Int {
        val settingsActivity = Reflect.loadClass(loader, "com.android.settings.SettingsActivity")
            ?: return missing("settings_accessibility/SettingsActivity")
        val onCreate = Reflect.findMethods(settingsActivity, "onCreate", 1).firstOrNull {
            it.returnType == Void.TYPE && it.parameterTypes[0] == Bundle::class.java
        } ?: return missing("settings_accessibility/onCreate")
        return if (HookRuntime.hookAfter(
            onCreate,
            "settings_accessibility/SettingsActivity#onCreate",
        ) { chain, original ->
            val activity = chain.thisObject as? Activity ?: return@hookAfter original
            val intent = activity.intent ?: return@hookAfter original
            if (intent.action != "android.settings.ACCESSIBILITY_SETTINGS") {
                return@hookAfter original
            }
            val packageName = (
                Reflect.readField(intent, "mSenderPackageName") as? String
                )?.takeIf { it.isNotBlank() }
                ?: activity.callingPackage?.takeIf { it.isNotBlank() }
                ?: return@hookAfter original
            val packageManager = activity.packageManager
            val accessibilityManager = activity.getSystemService(
                Context.ACCESSIBILITY_SERVICE,
            ) as? AccessibilityManager ?: return@hookAfter original
            val service = accessibilityManager.installedAccessibilityServiceList
                .firstOrNull { info ->
                    info.resolveInfo?.serviceInfo?.packageName == packageName
                } ?: return@hookAfter original
            val serviceInfo = service.resolveInfo.serviceInfo
            val appName = Reflect.attempt {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(packageName, 0),
                ).toString()
            } ?: packageName
            val subSettings = Reflect.loadClass(loader, "com.android.settings.SubSettings")
                ?: return@hookAfter original
            val open = Intent(activity, subSettings).apply {
                action = Intent.ACTION_MAIN
                putExtra(":settings:show_fragment_title", appName)
                putExtra(":settings:source_metrics", 0)
                putExtra(":settings:show_fragment_title_resid", -1)
                putExtra(
                    ":settings:show_fragment",
                    "com.android.settings.accessibility.VolumeShortcutToggleAccessibilityServicePreferenceFragment",
                )
                putExtra(
                    ":settings:show_fragment_args",
                    Bundle().apply {
                        putParcelable(
                            "component_name",
                            ComponentName(packageName, serviceInfo.name),
                        )
                        putString("package", packageName)
                        putString("preference_key", "$packageName/${serviceInfo.name}")
                        putString(
                            "summary",
                            Reflect.attempt { service.loadDescription(packageManager) },
                        )
                    },
                )
            }
            activity.startActivity(open)
            original
        }
        ) 1 else 0
    }

    private fun moduleString(context: Context, name: String, fallback: String): String =
        Reflect.attempt {
            val module = context.createPackageContext(
                MODULE_PACKAGE,
                Context.CONTEXT_IGNORE_SECURITY,
            )
            val id = module.resources.getIdentifier(name, "string", MODULE_PACKAGE)
            if (id == 0) fallback else module.getString(id)
        } ?: fallback

    private fun thirdPartyPackages(loader: ClassLoader): List<String> {
        val application = Reflect.attempt {
            val activityThread = Class.forName("android.app.ActivityThread", false, loader)
            activityThread.getMethod("currentApplication").invoke(null) as? Context
        } ?: return emptyList()
        val packageManager = application.packageManager
        return Reflect.attempt {
            packageManager.getInstalledPackages(0)
                .filter {
                    ((it.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM) == 0
                }
                .map { it.packageName }
        } ?: emptyList()
    }

    private fun hookAutoUiMode(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "com.android.settings.utils.SettingsFeatures")
            ?: return missing("settings_uimode/SettingsFeatures")
        val method = Reflect.findMethods(clazz, "shouldShowAutoUIModeSetting", 0).firstOrNull {
            it.returnType == Boolean::class.javaPrimitiveType
        } ?: return missing("settings_uimode/shouldShowAutoUIModeSetting")
        return if (HookRuntime.hook(method, "settings_uimode/shouldShowAutoUIModeSetting") { chain ->
                val enabled = SystemProperties.boolean(loader, "persist.miui.auto_ui_enable", false)
                if (enabled) true else chain.proceed()
            }
        ) 1 else 0
    }

    /**
     * OLED 宿主会把「永不」超时从下拉里过滤掉。设备策略没有设置上限时，
     * 保留宿主原始条目，只执行按钮禁用状态刷新。
     */
    private fun hookNeverLockScreen(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(
            loader,
            "com.android.settings.KeyguardTimeoutDropDownPreference",
        ) ?: return missing("settings_neverlock/KeyguardTimeoutDropDownPreference")
        val method = Reflect.findMethods(clazz, "disableUnusableTimeouts", 0).firstOrNull {
            it.returnType == Void.TYPE
        } ?: return missing("settings_neverlock/disableUnusableTimeouts")
        return if (HookRuntime.hook(method, "settings_neverlock/disableUnusableTimeouts") { chain ->
                val preference = chain.thisObject
                val context = Reflect.readField(preference, "mContext") as? Context
                val policy = context?.getSystemService(Context.DEVICE_POLICY_SERVICE)
                val maximum = if (policy is DevicePolicyManager) {
                    policy.getMaximumTimeToLock(null)
                } else {
                    0L
                }
                if (maximum != 0L) return@hook chain.proceed()
                if (!hasNeverTimeout(preference)) return@hook chain.proceed()
                Reflect.callWith(preference, "updateDisableState", true)
                null
            }
        ) 1 else 0
    }

    private fun hasNeverTimeout(preference: Any): Boolean {
        val entries = Reflect.callWith(preference, "getEntries") as? Array<*> ?: return false
        val values = Reflect.callWith(preference, "getEntryValues") as? Array<*> ?: return false
        if (entries.size != values.size) return false
        var neverCount = 0
        for (index in values.indices) {
            val entry = entries[index] ?: return false
            val value = values[index]?.toString()?.toLongOrNull() ?: return false
            if (entry.toString().isBlank() || value <= 0L || value > Int.MAX_VALUE) return false
            if (value == Int.MAX_VALUE.toLong()) neverCount++
        }
        return neverCount == 1
    }

    private fun hookOverlayPermission(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "com.android.settings.SettingsActivity")
            ?: return missing("settings_overlay/SettingsActivity")
        val method = Reflect.findMethods(clazz, "redirectTabletActivity", 1).firstOrNull {
            it.returnType == Void.TYPE
        } ?: return missing("settings_overlay/redirectTabletActivity")
        return if (HookRuntime.hook(method, "settings_overlay/redirectTabletActivity") { chain ->
                val activity = chain.thisObject as? Activity
                val intent = activity?.intent
                val action = intent?.action
                val scheme = intent?.data?.scheme
                if (action == "android.settings.action.MANAGE_OVERLAY_PERMISSION" && scheme == "package") {
                    Reflect.writeField(
                        activity,
                        "initialFragmentName",
                        "com.android.settings.applications.appinfo.DrawOverlayDetails",
                    )
                }
                chain.proceed()
            }
        ) 1 else 0
    }

    private fun hookRunningServices(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "com.android.settings.SettingsActivity")
            ?: return missing("settings_running/SettingsActivity")
        val method = Reflect.findMethods(clazz, "getStartingFragmentClass", 1).firstOrNull {
            it.returnType == String::class.java
        } ?: return missing("settings_running/getStartingFragmentClass")
        return if (HookRuntime.hook(method, "settings_running/getStartingFragmentClass") { chain ->
                val intent = chain.args.firstOrNull() as? Intent
                if (intent?.component?.className == "com.android.settings.RunningServices") {
                    "com.android.settings.applications.RunningServices"
                } else {
                    chain.proceed()
                }
            }
        ) 1 else 0
    }

    private fun hookConstant(
        loader: ClassLoader,
        className: String,
        methodName: String,
        paramCount: Int,
        label: String,
        value: Any?,
    ): Int {
        val clazz = Reflect.loadClass(loader, className) ?: return missing(label)
        val method = Reflect.findMethods(clazz, methodName, paramCount).firstOrNull()
            ?: return missing(label)
        return if (HookRuntime.hookReturning(method, label, value)) 1 else 0
    }

    private fun hookAllConstant(
        clazz: Class<*>,
        methodName: String,
        value: Any?,
        label: String,
    ): Int = Reflect.findMethods(clazz, methodName).count {
        HookRuntime.hookReturning(it, "$label#$methodName", value)
    }

    private fun missing(label: String): Int {
        ModuleLog.warn("settings: target missing $label")
        return 0
    }
}

/**
 * 设置进程里读系统属性只需要一个布尔入口；独立成小对象方便测试替身与日志定位。
 */
private object SystemProperties {
    fun boolean(loader: ClassLoader, key: String, fallback: Boolean): Boolean =
        Reflect.attempt {
            val clazz = Class.forName("android.os.SystemProperties", false, loader)
            clazz.getMethod(
                "getBoolean",
                String::class.java,
                Boolean::class.javaPrimitiveType,
            ).invoke(null, key, fallback) as? Boolean
        } ?: fallback
}
