/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.compatibleVersionCode

import android.os.Looper
import android.view.View
import io.github.YGHFv.HyperExtend.core.ClassicQsSettings
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal object ClassicQsHooks {
    fun install(loader: ClassLoader, settings: HookSettings): Int {
        val targets = ClassicQsTargets
        val feature = ClassicQsSettings.FEATURE
        val methods = targets.methods.associateWith { NotificationHooks.resolve(loader, it) ?: return 0 }
        val fields = targets.fields.associate { (owner, name, expected) ->
            name to Class.forName(owner, false, loader).getDeclaredField(name).apply {
                require(type.name == expected); isAccessible = true
            }
        }
        val pageType = Class.forName(targets.PAGE, false, loader)
        val pagerType = Class.forName(targets.PAGER, false, loader)
        val quickType = Class.forName(targets.QUICK, false, loader)
        val constructor = Class.forName(targets.PANEL, false, loader)
            .getDeclaredConstructor(android.content.Context::class.java, android.util.AttributeSet::class.java)
        if (!(methods.values + constructor).map { HookRuntime.deoptimize(it, "$feature/caller") }.all { it }) return 0
        val ready = AtomicBoolean(false)
        val previousLimits = WeakHashMap<View, Int>()
        val active = ThreadLocal<View?>()
        var supportedHost: Boolean? = null

        fun supported(view: View): Boolean {
            if (!ready.get() || Looper.myLooper() != Looper.getMainLooper() || view.display?.displayId != 0) return false
            return supportedHost ?: runCatching {
                (view.context.packageManager.getPackageInfo("com.android.systemui", 0).compatibleVersionCode == 202602260L)
                    .also { supportedHost = it }
            }.getOrDefault(false)
        }
        fun requested(view: View, quick: Boolean): Int? {
            if (!settings.isOn(feature)) return null
            val row = ClassicQsSettings.row(view.resources.configuration.orientation, quick) ?: return null
            return ClassicQsSettings.value(settings.string(row.key), row)
        }

        val rows = HookRuntime.hook(methods.getValue(targets.measure), "$feature/rows") { chain ->
            val pager = chain.thisObject as? View ?: return@hook chain.proceed()
            if (pager.javaClass != pagerType || !supported(pager) || active.get() === pager) return@hook chain.proceed()
            val pages = fields.getValue("mPages").get(pager) as? List<*> ?: return@hook chain.proceed()
            val page = pages.firstOrNull()?.takeIf { it.javaClass == pageType } ?: return@hook chain.proceed()
            val maxField = fields.getValue("mMaxAllowedRows")
            val nativeMax = maxField.getInt(page)
            val nativeMin = fields.getValue("mMinRows").getInt(page)
            val effective = ClassicQsPolicy.rowLimit(requested(pager, false), nativeMax, nativeMin) ?: return@hook chain.proceed()
            val previous = active.get()
            active.set(pager)
            try {
                if (ClassicQsPolicy.redistribute(previousLimits[pager], effective, nativeMax))
                    fields.getValue("mDistributeTiles").setBoolean(pager, true)
                // The pager reads the first page's cap before distributing all records. Do not
                // change columns after layout or replace its height/minimum/page-count algorithm.
                maxField.setInt(page, effective)
                val result = chain.proceed()
                previousLimits[pager] = effective
                result
            } finally {
                try { if (maxField.getInt(page) == effective) maxField.setInt(page, nativeMax) }
                finally { if (previous == null) active.remove() else active.set(previous) }
            }
        }
        val quick = HookRuntime.hook(methods.getValue(targets.maxTiles), "$feature/quickCount") { chain ->
            val panel = chain.thisObject as? View ?: return@hook chain.proceed()
            if (panel.javaClass != quickType || !supported(panel)) return@hook chain.proceed()
            if ((chain.args[0] as Int) <= 0) return@hook chain.proceed()
            val count = requested(panel, true) ?: return@hook chain.proceed()
            // Keep the native animator reset and current-tile-prefix selection in setMaxTiles.
            chain.proceed(arrayOf<Any?>(count))
        }
        val attach = HookRuntime.hookAfter(methods.getValue(targets.attached), "$feature/quickAttach") { chain, result ->
            val panel = chain.thisObject as? View
            // Construction has no display yet. Replay the native resource update on attach,
            // without writing mMaxTiles or replaying constructor/tuner registration.
            if (panel != null && panel.javaClass == quickType && supported(panel) && settings.isOn(feature))
                methods.getValue(targets.quickResources).invoke(panel)
            result
        }
        ready.set(rows && quick && attach)
        return if (ready.get()) 3 else 0
    }
}
