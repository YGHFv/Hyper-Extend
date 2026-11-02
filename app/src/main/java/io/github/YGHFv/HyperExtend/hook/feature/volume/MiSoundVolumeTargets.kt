/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.volume

import java.lang.reflect.Field
import java.lang.reflect.Modifier

/** 两组经过宿主指令取证的名称映射；由服务字段类型选择，不按系统或应用版本放行。 */
internal object MiSoundVolumeTargets {
    data class Layout(
        val serviceController: String,
        val postureClass: String,
        val postureMethod: String,
        val adapterPages: String,
        val adapterController: String,
        val holderItem: String,
    )

    val current = Layout("e", "z.m", "m", "e", "g", "e")
    val os3 = Layout("d", "H.j", "j", "d", "f", "d")

    fun resolve(service: Class<*>, controller: Class<*>): Pair<Layout, Field> {
        val candidates = listOf(current, os3).mapNotNull { layout ->
            runCatching { service.getDeclaredField(layout.serviceController) }.getOrNull()
                ?.takeIf { it.type == controller && !Modifier.isStatic(it.modifiers) }
                ?.let { layout to it }
        }
        require(candidates.size == 1) { "音质音效服务控制器结构不唯一或不匹配" }
        return candidates.single().also { it.second.isAccessible = true }
    }
}
