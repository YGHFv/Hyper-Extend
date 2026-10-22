/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.github.YGHFv.HyperExtend.hook.feature.system

import android.content.Intent
import android.content.pm.ResolveInfo
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.core.SystemUiCustomSettings
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect

/** 分享、打开方式与文本处理菜单清理。 */
internal object SystemFrameworkShortcutHooks {
    private val shareActions = setOf(
        Intent.ACTION_SEND,
        Intent.ACTION_SENDTO,
        Intent.ACTION_SEND_MULTIPLE,
    )
    private val linkSchemes = setOf("http", "https", "vnd.youtube")

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        var count = 0
        if (settings.isOn("system_framework_clean_share_menu")) {
            count += installFilter(
                loader = loader,
                key = "system_framework_clean_share_menu.packages",
                label = "clean_share_menu",
                settings = settings,
                accepts = { intent ->
                    intent.action in shareActions &&
                        !intent.dataString.orEmpty().contains(":")
                },
            )
        }
        if (settings.isOn("system_framework_clean_open_menu")) {
            count += installFilter(
                loader = loader,
                key = "system_framework_clean_open_menu.packages",
                label = "clean_open_menu",
                settings = settings,
                accepts = { intent ->
                    intent.action == Intent.ACTION_VIEW &&
                        (intent.type != null || intent.scheme in linkSchemes)
                },
            )
        }
        if (settings.isOn("system_framework_clean_process_text_menu")) {
            count += installFilter(
                loader = loader,
                key = "system_framework_clean_process_text_menu.packages",
                label = "clean_process_text_menu",
                settings = settings,
                accepts = { it.action == Intent.ACTION_PROCESS_TEXT },
            )
        }
        return count
    }

    private fun installFilter(
        loader: ClassLoader,
        key: String,
        label: String,
        settings: HookSettings,
        accepts: (Intent) -> Boolean,
    ): Int {
        val selected = SystemUiCustomSettings.packages(settings.string(key))
        if (selected.isEmpty()) return 0
        val engine = Reflect.loadClass(loader, "com.android.server.pm.ComputerEngine")
        if (engine == null) {
            ModuleLog.warn("$label: ComputerEngine not found")
            return 0
        }
        val methods = Reflect.findMethods(engine, "queryIntentActivitiesInternal")
            .filter { it.parameterCount > 0 && it.parameterTypes[0] == Intent::class.java }
        if (methods.isEmpty()) {
            ModuleLog.warn("$label: queryIntentActivitiesInternal not found")
            return 0
        }
        return methods.count { method ->
            HookRuntime.hook(method, "$label/ComputerEngine#queryIntentActivitiesInternal") { chain ->
                val intent = chain.args.getOrNull(0) as? Intent
                val original = chain.proceed()
                if (intent == null ||
                    intent.getBooleanExtra("HyperCeiler", false) ||
                    !accepts(intent)
                ) {
                    return@hook original
                }
                @Suppress("UNCHECKED_CAST")
                val resolved = original as? MutableList<Any?>
                if (resolved != null) {
                    runCatching {
                        resolved.removeAll { item ->
                            val packageName = (item as? ResolveInfo)?.activityInfo?.packageName
                            packageName != null && (
                                packageName in selected ||
                                    "$packageName|0" in selected
                                )
                        }
                    }.onFailure {
                        ModuleLog.warn("$label: resolved list is not mutable")
                    }
                }
                original
            }
        }
    }
}
