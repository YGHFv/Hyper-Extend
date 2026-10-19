/* Copyright (C) 2023-2026 HyperCeiler Contributions; Copyright (C) 2026 YGHFv;
 * SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.os.Looper
import android.view.View
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings

/** Suppress only the disclosure row, never falsify DevicePolicyManager or VPN state. */
internal object SecurityFooterHooks {
    const val FEATURE = "control_center_hide_security_footer"
    const val CLASSIC = "com.android.systemui.qs.MiuiQSSecurityFooter"
    val classicRun = SystemUiMethod("$CLASSIC\$1", "run", "void")
    val available = SystemUiMethod("miui.systemui.controlcenter.panel.main.security.SecurityFooterController",
        "available", "boolean", listOf("boolean"))
    val view = SystemUiMethod("miui.systemui.util.ViewController", "getView", "android.view.View")

    fun suppress(native: Boolean, enabled: Boolean, main: Boolean, display: Int?,
                 isPlugin: Boolean = false): Boolean =
        native && enabled && main && display == 0 && (!isPlugin)

    private fun eligible(view: View, settings: HookSettings, plugin: Boolean): Boolean = runCatching {
        if (!settings.isOn(FEATURE) || Looper.myLooper() != Looper.getMainLooper() || view.display?.displayId != 0)
            return@runCatching false
        suppress(true, true, true, 0, plugin)
    }.getOrDefault(false)

    fun installClassic(loader: ClassLoader, settings: HookSettings): Int {
        val method = NotificationHooks.resolve(loader, classicRun) ?: return 0
        val owner = method.declaringClass.getDeclaredField("this\$0").apply {
            require(type.name == CLASSIC); isAccessible = true
        }
        val branch = method.declaringClass.getDeclaredField("\$r8\$classId").apply {
            require(type == Int::class.javaPrimitiveType); isAccessible = true
        }
        val root = Class.forName(CLASSIC, false, loader).getDeclaredField("mRootView").apply {
            require(type == View::class.java); isAccessible = true
        }
        return if (HookRuntime.hookAfter(method, "$FEATURE/classic") { chain, result ->
                // Branch zero only refreshes the icon and must not be used to restore visibility.
                if (branch.getInt(chain.thisObject) == 1) {
                    val footer = owner.get(chain.thisObject)?.let { root.get(it) as? View }
                    if (footer != null && eligible(footer, settings, false)) footer.visibility = View.GONE
                }
                result
            }) 1 else 0
    }

    fun installPlugin(loader: ClassLoader, settings: HookSettings): Int {
        val methods = listOf(available, view, EditButtonHooks.distribute)
            .associateWith { NotificationHooks.resolve(loader, it) ?: return 0 }
        if (!methods.values.map { HookRuntime.deoptimize(it, "$FEATURE/caller") }.all { it }) return 0
        return if (HookRuntime.hookAfter(methods.getValue(available), "$FEATURE/plugin") { chain, result ->
                val root = methods.getValue(view).invoke(chain.thisObject) as? View
                if (result == true && root != null && eligible(root, settings, true)) false else result
            }) 1 else 0
    }
}
