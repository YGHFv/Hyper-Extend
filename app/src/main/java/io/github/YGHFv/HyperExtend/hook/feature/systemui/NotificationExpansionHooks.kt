/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.app.KeyguardManager
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.view.View
import io.github.YGHFv.HyperExtend.core.NotificationExpansionSettings as Settings
import io.github.YGHFv.HyperExtend.core.SystemUiCustomSettings
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect
import java.lang.ref.WeakReference
import java.util.WeakHashMap

internal object NotificationExpansionHooks {
    private const val ROW = "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow"
    private const val HEAD = "com.android.systemui.statusbar.notification.headsup.HeadsUpManagerImpl\$HeadsUpEntry"
    private const val PRESENTER = "com.android.systemui.statusbar.phone.StatusBarNotificationPresenter"
    internal val presenterTarget = SystemUiMethod(PRESENTER, "onExpandClicked", "void",
        listOf(ROW, "com.android.systemui.statusbar.notification.collection.EntryAdapter", "boolean"))
    internal val carrierTarget = SystemUiMethod("com.android.systemui.controlcenter.shade.ControlCenterHeaderController",
        "updateCarrierAndPrivacyVisible", "void", listOf(
            "com.android.systemui.plugins.miui.statusbar.MiuiPrivacyController\$MiuiPromptInfo",
            "com.android.systemui.plugins.miui.statusbar.MiuiPrivacyController\$AndroidPromptInfo"))

    fun install(loader: ClassLoader, settings: HookSettings): List<String> = buildList {
        installSystemUiFeature(settings, Settings.EXPAND) { expand(loader, SystemUiCustomSettings.packages(settings.string(Settings.PACKAGES))) }
        installSystemUiFeature(settings, Settings.COLLAPSE) {
            timeout(loader, settings.number(Settings.timeout.key, Settings.timeout.default, Settings.timeout.min, Settings.timeout.max) * 100L)
        }
        installSystemUiFeature(settings, "control_center_hide_carrier") { hideCarrier(loader) }
    }

    private fun unlocked(row: View): Boolean = Reflect.attempt {
        row.context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false &&
            Reflect.readField(row, "mOnKeyguard") == false && Reflect.callWith(row, "shouldShowPublic") == false
    } == true

    private fun expand(loader: ClassLoader, packages: Set<String>): Int {
        if (packages.isEmpty()) return 0
        val type = Reflect.loadClass(loader, ROW) ?: return 0
        val system = type.getDeclaredMethod("setSystemExpanded", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        val getEntry = type.getDeclaredMethod("getEntry").apply { isAccessible = true }
        val click = Reflect.findField(type, "mExpandClickListener") ?: return 0
        val handler = Handler(Looper.getMainLooper())
        val pending = WeakHashMap<View, Runnable>()
        val touched = WeakHashMap<View, WeakReference<Any>>()
        val automatic = ThreadLocal<Boolean>()
        fun selected(row: View): Boolean {
            if (!unlocked(row) || Reflect.readField(row, "mHasUserChangedExpansion") != false) return false
            val entry = getEntry.invoke(row) ?: return false
            if (touched[row]?.get() === entry) return false
            val sbn = Reflect.readField(entry, "mSbn") as? StatusBarNotification ?: return false
            return sbn.packageName in packages
        }
        var count = 0
        if (HookRuntime.hook(type.getDeclaredMethod("toggleExpansionState", View::class.java, Boolean::class.javaPrimitiveType),
                "notification_expand/userChoice") { chain ->
                val row = chain.thisObject as? View
                Reflect.attempt {
                    if (row != null && automatic.get() != true) getEntry.invoke(row)?.let { touched[row] = WeakReference(it) }
                }
                chain.proceed()
            }) count++ else return 0
        if (HookRuntime.hook(system, "notification_expand/systemExpanded") { chain ->
                val row = chain.thisObject as? View
                if (row != null && chain.args[0] == false && selected(row)) chain.proceed(arrayOf<Any?>(true))
                else chain.proceed()
            }) count++
        if (HookRuntime.hookAfter(type.getDeclaredMethod("onNotificationUpdated"), "notification_expand/updated") { chain, original ->
                val row = chain.thisObject as? View
                if (row != null && selected(row) && Reflect.readField(row, "mIsHeadsUp") == false) system.invoke(row, true)
                original
            }) count++
        if (HookRuntime.hookAfter(type.getDeclaredMethod("setHeadsUp", Boolean::class.javaPrimitiveType), "notification_expand/headsUp") { chain, original ->
                val row = chain.thisObject as? View
                if (row != null) {
                    pending.remove(row)?.let(handler::removeCallbacks)
                    if (chain.args[0] == true && selected(row)) {
                        val weak = WeakReference(row)
                        val identity = WeakReference(getEntry.invoke(row))
                        val task = Runnable {
                            val current = weak.get() ?: return@Runnable
                            pending.remove(current)
                            Reflect.attempt {
                                val pinned = Reflect.callWith(Reflect.callWith(current, "getPinnedStatus"), "isPinned") == true
                                if (current.isAttachedToWindow && identity.get() != null && getEntry.invoke(current) === identity.get() && selected(current) && pinned &&
                                    Reflect.readField(current, "mIsHeadsUp") == true &&
                                    Reflect.readField(current, "mExpandedWhenPinned") == false) {
                                    automatic.set(true)
                                    try { (click.get(current) as? View.OnClickListener)?.onClick(current) }
                                    finally { automatic.remove() }
                                }
                            }
                        }
                        pending[row] = task
                        handler.postDelayed(task, 60)
                    }
                }
                original
            }) count++
        return count
    }

    private fun timeout(loader: ClassLoader, delay: Long): Int {
        val type = Reflect.loadClass(loader, HEAD) ?: return 0
        val presenter = Reflect.loadClass(loader, PRESENTER) ?: return 0
        val update = type.getDeclaredMethod("updateEntry", String::class.java, Boolean::class.javaPrimitiveType)
        val cancel = type.getDeclaredMethod("cancelAutoRemovalCallbacks", String::class.java)
        val clicked = presenter.declaredMethods.single(presenterTarget::matches)
        Reflect.loadClass(loader, ROW)?.getDeclaredMethod("toggleExpansionState", View::class.java, Boolean::class.javaPrimitiveType)
            ?.let { HookRuntime.deoptimize(it, "notification_timeout/presenter caller") }
        val handler = Handler(Looper.getMainLooper())
        val pending = WeakHashMap<Any, Runnable>()
        fun eligible(head: Any): Boolean {
            val entry = Reflect.readField(head, "mEntry") ?: return false
            val row = Reflect.readField(entry, "row") as? View ?: return false
            val sbn = Reflect.readField(entry, "mSbn") as? StatusBarNotification ?: return false
            return unlocked(row) && ExpandedTimeoutPolicy.eligible(
                Reflect.readField(head, "mExpanded") as? Boolean,
                Reflect.callWith(entry, "isRowPinned") as? Boolean,
                Reflect.readField(head, "mRemoteInputActive") as? Boolean,
                Reflect.readField(head, "mGutsShownPinned") as? Boolean,
                sbn.notification.fullScreenIntent != null,
            )
        }
        fun schedule(head: Any) {
            pending.remove(head)?.let(handler::removeCallbacks)
            if (!eligible(head)) return
            val weak = WeakReference(head)
            val identity = WeakReference(Reflect.readField(head, "mEntry") ?: return)
            val task = Runnable {
                val current = weak.get() ?: return@Runnable
                pending.remove(current)
                Reflect.attempt {
                    val manager = Reflect.readField(current, "this\$0") ?: return@attempt
                    val entry = identity.get() ?: return@attempt
                    val key = (Reflect.readField(entry, "mSbn") as? StatusBarNotification)?.key ?: return@attempt
                    if (Reflect.readField(current, "mEntry") === entry &&
                        Reflect.callWith(manager, "getHeadsUpEntry", key) === current && eligible(current)) {
                        (Reflect.readField(current, "mRemoveRunnable") as? Runnable)?.run()
                    }
                }
            }
            pending[head] = task
            handler.postDelayed(task, if (Reflect.readField(head, "extended") == true) 10000L else delay)
        }
        var count = 0
        if (!HookRuntime.hookAfter(cancel, "notification_timeout/cancel") { chain, original ->
                pending.remove(chain.thisObject)?.let(handler::removeCallbacks)
                original
            }) return 0
        count++
        if (HookRuntime.hookAfter(update, "notification_timeout/update") { chain, original ->
                chain.thisObject?.let(::schedule)
                original
            }) count++
        if (HookRuntime.hookAfter(clicked, "notification_timeout/expandClicked") { chain, original ->
                val manager = chain.thisObject?.let { Reflect.readField(it, "mHeadsUpManager") }
                val key = Reflect.callWith(chain.args[1], "getKey")
                Reflect.callWith(manager, "getHeadsUpEntry", key)?.let(::schedule)
                original
            }) count++
        return count
    }

    private fun hideCarrier(loader: ClassLoader): Int {
        val type = Reflect.loadClass(loader, "com.android.systemui.controlcenter.shade.ControlCenterHeaderController") ?: return 0
        val target = type.declaredMethods.single(carrierTarget::matches)
        val field = Reflect.findField(type, "carrierLayout") ?: return 0
        return if (HookRuntime.hookAfter(target, "control_center_hide_carrier/update") { chain, original ->
                // Keep all privacy prompts and their visibility/spacing calculations intact.
                (field.get(chain.thisObject) as? View)?.let { if (it.visibility == View.VISIBLE) it.visibility = View.INVISIBLE }
                original
            }) 1 else 0
    }
}

internal object ExpandedTimeoutPolicy {
    fun eligible(expanded: Boolean?, pinned: Boolean?, remoteInput: Boolean?, guts: Boolean?, fullScreen: Boolean): Boolean =
        expanded == true && pinned == true && remoteInput == false && guts == false && !fullScreen
}
