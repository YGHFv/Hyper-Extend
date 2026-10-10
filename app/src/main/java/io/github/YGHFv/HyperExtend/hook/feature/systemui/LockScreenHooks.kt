/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.content.Context
import android.widget.Toast
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect
import io.github.YGHFv.HyperExtend.hook.feature.statusbar.StateFlowFactory
import java.util.Collections
import java.util.WeakHashMap

internal object LockScreenHooks {
    fun install(loader: ClassLoader, settings: HookSettings): List<String> = buildList {
        installSystemUiFeature(settings, "lockscreen_wallpaper_transition") { LockscreenWallpaperHooks.install(loader, settings) }
        installSystemUiFeature(settings, "lockscreen_charging_info") { LockscreenChargingHooks.install(loader, settings) }
        installSystemUiFeature(settings, "lockscreen_hide_status_bar") { hideStatusBar(loader) }
        installSystemUiFeature(settings, "lockscreen_hide_ble_toast") { hideBleToast() }
        installSystemUiFeature(settings, "lockscreen_scramble_pin") { ScramblePin.install(loader) }
        installSystemUiFeature(settings, "lockscreen_double_tap") { LockScreenDoubleTap.install(loader) }
        installSystemUiFeature(settings, "lockscreen_hide_zen") { hideZen(loader) }
        installSystemUiFeature(settings, "lockscreen_hide_hint") { hideHint(loader) }
        installSystemUiFeature(settings, "lockscreen_third_party_biometrics") { thirdPartyBiometrics(loader) }
    }

    private fun thirdPartyBiometrics(loader: ClassLoader): Int {
        val methods = listOf(SystemUiTargets.facePossible, SystemUiTargets.fingerprintPossible)
            .map { NotificationHooks.resolve(loader, it) ?: return 0 }
        // Port the upstream capability queries, NOT authentication results or strong-auth
        // policy. Fingerprint uses the exported stub, not the monitor's global policy check.
        return methods.count { method ->
            HookRuntime.hookReturning(method, "lockscreen_third_party_biometrics/${method.toGenericString()}", true)
        }
    }

    private fun hideHint(loader: ClassLoader): Int {
        val update = NotificationHooks.resolve(loader, SystemUiTargets.indicationUpdate) ?: return 0
        val typeName = NotificationHooks.resolve(loader, SystemUiTargets.indicationType) ?: return 0
        val types = LockScreenHintPolicy.types.keys.filter { type ->
            LockScreenHintPolicy.hide(type, Reflect.attempt { typeName.invoke(null, type) as? String })
        }.toSet()
        if (types.isEmpty()) return 0
        return if (HookRuntime.hook(update, "lockscreen_hide_hint/updateIndication") { chain ->
                if (chain.args[0] in types) {
                    val args = chain.args.toTypedArray()
                    // Let the host remove the entry from its rotation queue. Do not hide the
                    // entire indication area (charging, device policy and auth errors share it).
                    args[1] = null
                    chain.proceed(args)
                } else chain.proceed()
            }) 1 else 0
    }

    private fun hideZen(loader: ClassLoader): Int {
        val vm = Reflect.loadClass(loader, SystemUiTargets.NOTIFICATION_NUM_VM) ?: return 0
        val field = Reflect.findField(vm, "isZenModeEnabled") ?: return 0
        val hidden = StateFlowFactory(loader).constant(false) ?: return 0
        if (!field.type.isInstance(hidden)) return 0
        // Only the presentation Flow is replaced. The global Zen controller and its
        // notification filtering remain untouched, including while the shade is open.
        return vm.declaredConstructors.count { constructor ->
            HookRuntime.hookAfter(constructor, "lockscreen_hide_zen/${constructor.toGenericString()}") { chain, original ->
                field.set(chain.thisObject, hidden)
                original
            }
        }
    }

    private fun hideStatusBar(loader: ClassLoader): Int {
        val vm = Reflect.loadClass(loader, SystemUiTargets.KEYGUARD_STATUS_VM) ?: return 0
        val field = Reflect.findField(vm, "isVisible") ?: return 0
        val hidden = StateFlowFactory(loader).constant(false) ?: return 0
        if (!field.type.isInstance(hidden)) {
            ModuleLog.warn("lockscreen_hide_status_bar: incompatible visibility Flow")
            return 0
        }
        return vm.declaredConstructors.count { constructor ->
            HookRuntime.hookAfter(constructor, "lockscreen_hide_status_bar/${constructor.toGenericString()}") { chain, original ->
                field.set(chain.thisObject, hidden)
                original
            }
        }
    }

    private fun hideBleToast(): Int {
        val hidden = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Toast, Boolean>()))
        val show = Toast::class.java.getDeclaredMethod("show")
        val make = Toast::class.java.getDeclaredMethod("makeText", Context::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        // Identify only this resource-backed Toast. Never suppress subsequent unrelated Toasts.
        val showHook = HookRuntime.hook(show, "lockscreen_hide_ble_toast/Toast#show") { chain ->
            if (hidden.contains(chain.thisObject)) null else chain.proceed()
        }
        if (!showHook) return 0
        val makeHook = HookRuntime.hookAfter(make, "lockscreen_hide_ble_toast/Toast#makeText") { chain, original ->
            val context = chain.args[0] as? Context
            val id = chain.args[1] as? Int
            if (original is Toast && context != null && id != null) {
                val name = Reflect.attempt { context.resources.getResourceName(id) }
                if (name == "com.android.systemui:string/miui_keyguard_ble_unlock_succeed_msg") hidden.add(original)
            }
            original
        }
        return if (makeHook) 2 else 0
    }
}
