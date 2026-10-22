/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.github.YGHFv.HyperExtend.hook.feature.system

import android.content.Context
import android.content.IntentFilter
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.core.SYSTEM_FRAMEWORK_AOSP_IME_PACKAGES
import io.github.YGHFv.HyperExtend.core.SYSTEM_FRAMEWORK_MIUI_IME_PACKAGES
import io.github.YGHFv.HyperExtend.core.SystemUiCustomSettings
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect
import java.util.concurrent.atomic.AtomicBoolean

/** 输入法列表权限与 AOSP 输入法导航栏兼容。 */
internal object SystemFrameworkInputHooks {
    private const val IME_NAV_BAR = 1

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        var count = 0
        if (settings.isOn("system_framework_miui_ime_unlock")) {
            val selected = selectedPackages(settings, SYSTEM_FRAMEWORK_MIUI_IME_PACKAGES)
            if (selected.isNotEmpty()) count += installMiuiImeUnlock(loader, selected)
        }
        if (settings.isOn("system_framework_aosp_ime")) {
            val selected = selectedPackages(settings, SYSTEM_FRAMEWORK_AOSP_IME_PACKAGES)
            if (selected.isNotEmpty()) count += installAospIme(loader, selected)
        }
        return count
    }

    private fun selectedPackages(settings: HookSettings, key: String): Set<String> =
        SystemUiCustomSettings.packages(settings.string(key))

    private fun installMiuiImeUnlock(loader: ClassLoader, selected: Set<String>): Int {
        val clazz = Reflect.loadClass(loader, "com.android.server.pm.AppsFilterUtils") ?: return 0
        val method = Reflect.findMethods(clazz, "canQueryViaComponents", paramCount = 3)
            .firstOrNull { it.returnType == Boolean::class.javaPrimitiveType }
            ?: return 0
        return if (HookRuntime.hook(method, "miui_ime_unlock/AppsFilterUtils#canQueryViaComponents") { chain ->
                val original = chain.proceed()
                if (original == true) return@hook original
                val querying = chain.args.getOrNull(0)
                val target = chain.args.getOrNull(1)
                val queryingName = packageName(querying)
                if (queryingName !in selected) return@hook original
                if (isIme(querying) && isIme(target)) true else original
            }
        ) 1 else 0
    }

    private fun installAospIme(loader: ClassLoader, selected: Set<String>): Int {
        var count = 0
        val service = Reflect.loadClass(loader, "com.android.server.inputmethod.InputMethodManagerService")
        val navMethod = service?.let {
            Reflect.findMethods(it, "getInputMethodNavButtonFlagsLocked", paramCount = 1)
                .firstOrNull { method -> method.returnType == Int::class.javaPrimitiveType }
        }
        if (navMethod != null) {
            if (HookRuntime.hook(navMethod, "aosp_ime/InputMethodManagerService#getInputMethodNavButtonFlagsLocked") {
                    chain ->
                    val original = chain.proceed() as? Int ?: return@hook chain.proceed()
                    val context = Reflect.readField(chain.thisObject, "mContext") as? Context
                    val fallback = context != null && shouldDrawNavBar(context, selected)
                    updateUserDataFlag(chain.args.getOrNull(0), fallback)
                    if (fallback) {
                        original or IME_NAV_BAR
                    } else {
                        original and IME_NAV_BAR.inv()
                    }
                }
            ) count++
        }

        val impl = Reflect.loadClass(loader, "com.android.server.inputmethod.InputMethodManagerServiceImpl")
        val between = impl?.let {
            Reflect.findMethods(it, "isCallingBetweenCustomIME", paramCount = 3)
                .firstOrNull { method -> method.returnType == Boolean::class.javaPrimitiveType }
        }
        if (between != null) {
            if (HookRuntime.hook(between, "aosp_ime/InputMethodManagerServiceImpl#isCallingBetweenCustomIME") {
                    chain ->
                    val original = chain.proceed()
                    if (original == true) return@hook original
                    val context = chain.args.getOrNull(0) as? Context ?: return@hook original
                    val uid = chain.args.getOrNull(1) as? Int ?: return@hook original
                    val current = currentImePackage(context) ?: return@hook original
                    if (current !in selected) return@hook original
                    val packages = context.packageManager.getPackagesForUid(uid)
                    if (packages?.contains(current) == true) true else original
                }
            ) count++
        }

        val customized = impl?.let {
            Reflect.findMethods(it, "isCustomizedInputMethod", paramCount = 1)
                .firstOrNull { method -> method.returnType == Boolean::class.javaPrimitiveType }
        }
        if (customized != null) {
            if (HookRuntime.hook(customized, "aosp_ime/InputMethodManagerServiceImpl#isCustomizedInputMethod") {
                    chain ->
                    val id = chain.args.getOrNull(0) as? String
                    if (normalizePackage(id) in selected) false else chain.proceed()
                }
            ) count++
        }
        return count
    }

    private fun shouldDrawNavBar(context: Context, selected: Set<String>): Boolean {
        val gestureMode = Settings.Secure.getInt(
            context.contentResolver,
            "navigation_mode",
            2,
        ) == 2
        return gestureMode && currentImePackage(context) in selected
    }

    private fun currentImePackage(context: Context): String? {
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        val info = Reflect.attempt {
            InputMethodManager::class.java
                .getMethod("getCurrentInputMethodInfo")
                .invoke(manager)
        }
        val packageName = Reflect.callWith(info, "getPackageName") as? String
        return normalizePackage(packageName)
    }

    private fun updateUserDataFlag(userData: Any?, enabled: Boolean) {
        if (userData == null) return
        val field = Reflect.findField(userData.javaClass, "mImeDrawsNavBar") ?: return
        val value = Reflect.attempt { field.get(userData) }
        if (value is AtomicBoolean) value.set(enabled)
    }

    private fun isIme(androidPackage: Any?): Boolean {
        if (androidPackage == null) return false
        val services = Reflect.callWith(androidPackage, "getServices") as? List<*> ?: return false
        for (service in services) {
            val exported = Reflect.callWith(service, "isExported") as? Boolean ?: continue
            if (!exported) continue
            val intents = Reflect.callWith(service, "getIntents") as? List<*> ?: continue
            for (intent in intents) {
                val filter = Reflect.callWith(intent, "getIntentFilter") as? IntentFilter ?: continue
                if (filter.matchAction("android.view.InputMethod")) return true
            }
        }
        return false
    }

    private fun packageName(androidPackage: Any?): String? =
        normalizePackage(Reflect.callWith(androidPackage, "getPackageName") as? String)

    private fun normalizePackage(value: String?): String? =
        value?.substringBefore('/')?.takeIf { it.isNotBlank() }
}
