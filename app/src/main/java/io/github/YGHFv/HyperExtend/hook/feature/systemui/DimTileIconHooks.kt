/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.compatibleVersionCode

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import java.util.concurrent.atomic.AtomicBoolean

internal object DimTileIconHooks {
    private data class ResourceKey(val configuration: Configuration, val densityDpi: Int)

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        val targets = DimTileIconTargets
        val methods = targets.methods.associateWith { NotificationHooks.resolve(loader, it) ?: return 0 }
        val fields = targets.fields.associate { (owner, name, type) ->
            name to Class.forName(owner, false, loader).getDeclaredField(name).apply {
                require(this.type.name == type); isAccessible = true
            }
        }
        val booleanState = Class.forName(targets.BOOLEAN_STATE, false, loader)
        val iconType = Class.forName(targets.ICON, false, loader)
        val drawableIcon = Class.forName(targets.DRAWABLE_ICON, false, loader)
        require(iconType.isAssignableFrom(drawableIcon))
        val constructor = drawableIcon.getDeclaredConstructor(Drawable::class.java)
        if (!methods.values.map { HookRuntime.deoptimize(it, "${DimTileIconPolicy.FEATURE}/caller") }.all { it }) return 0
        val icons = DimTileIconCache<ResourceKey, Any>()
        val logged = AtomicBoolean(false)
        val warned = AtomicBoolean(false)
        return if (HookRuntime.hookAfter(methods.getValue(targets.update), "${DimTileIconPolicy.FEATURE}/update") { chain, result ->
                if (!settings.isOn(DimTileIconPolicy.FEATURE)) return@hookAfter result
                val tile = chain.thisObject ?: return@hookAfter result
                val state = chain.args[0] ?: return@hookAfter result
                val context = fields.getValue("mContext").get(tile) as? Context ?: return@hookAfter result
                val version = runCatching { context.packageManager.getPackageInfo("com.android.systemui", 0).compatibleVersionCode }.getOrNull()
                if (!DimTileIconPolicy.eligible(true, version, fields.getValue("mTileSpec").get(tile) as? String,
                        booleanState.isInstance(state), fields.getValue("state").getInt(state),
                        fields.getValue("icon").get(state) != null, fields.getValue("iconSupplier").get(state) != null))
                    return@hookAfter result
                val key = ResourceKey(Configuration(context.resources.configuration), context.resources.displayMetrics.densityDpi)
                val replacement = icons.get(tile, key) {
                    runCatching {
                        // Resolve by name in the installed module, never use its R ID with host Resources.
                        val module = context.createPackageContext("io.github.YGHFv.HyperExtend", 0)
                            .createConfigurationContext(key.configuration)
                        val id = module.resources.getIdentifier(DimTileIconPolicy.ASSET, "drawable", module.packageName)
                        if (id == 0) return@runCatching null
                        val drawable = module.getDrawable(id)?.mutate() ?: return@runCatching null
                        if (drawable.constantState == null) return@runCatching null
                        // A plain DrawableIcon compares drawable identity. It cannot compare equal
                        // to the native resource icon on enable/disable; no fake host resource IDs.
                        constructor.newInstance(drawable)
                    }.onFailure {
                        if (warned.compareAndSet(false, true)) ModuleLog.error("${DimTileIconPolicy.FEATURE}: asset unavailable, retaining native icon", it)
                    }.getOrNull()
                } ?: return@hookAfter result
                // Native update has already written label/value/accessibility and the fallback icon.
                fields.getValue("icon").set(state, replacement)
                if (logged.compareAndSet(false, true)) ModuleLog.info("${DimTileIconPolicy.FEATURE}: icon update reached (device acceptance pending)")
                result
            }) 1 else 0
    }
}
