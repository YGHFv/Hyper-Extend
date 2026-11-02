/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal object DeviceCenterSingleRowTargets {
    private const val ROOT = "miui.systemui.controlcenter.panel.main.devicecenter"
    const val CONTROLLER = "$ROOT.devices.DeviceCenterCardController"
    const val DIFF = "$CONTROLLER\$handleUpdateList\$2"
    const val ITEM = "$ROOT.devices.DeviceItem"
    const val DEVICE = "$ITEM\$DeviceInfoItem"
    const val DETAIL = "$ITEM\$DetailItem"
    const val MODE = "$ROOT.entry.DeviceCenterEntryViewHolder\$Mode"

    val update = SystemUiMethod(CONTROLLER, "handleUpdateList", "void", listOf("boolean"))
    val mode = SystemUiMethod(CONTROLLER, "getMode", MODE)
    val callers = listOf(
        update,
        SystemUiMethod(CONTROLLER, "handleUpdateList\$default", "void",
            listOf(CONTROLLER, "boolean", "int", "java.lang.Object"), isStatic = true),
        SystemUiMethod(CONTROLLER, "updateListForDCCardChanged", "void"),
        SystemUiMethod(CONTROLLER, "updateTask\$lambda\$3", "void", listOf(CONTROLLER), isStatic = true),
    )
    val fields = listOf(
        Triple(CONTROLLER, "deviceItems", "java.util.ArrayList"),
        Triple(CONTROLLER, "pendingUpdateList", "java.util.List"),
        Triple(CONTROLLER, "detailItem", DETAIL),
        Triple(DIFF, "\$newItems", "java.util.ArrayList"),
        Triple(DIFF, "\$oldItems", "java.util.ArrayList"),
    )

    data class Resolved(
        val constructor: Constructor<*>,
        val callers: List<Method>,
        val device: Class<*>,
        val detail: Class<*>,
    )

    fun resolve(loader: ClassLoader): Resolved {
        fun load(name: String) = Class.forName(name, false, loader)
        fields.forEach { (owner, name, type) ->
            val field = load(owner).getDeclaredField(name)
            require(field.type.name == type && !Modifier.isStatic(field.modifiers))
        }
        val controller = load(CONTROLLER)
        val resolvedCallers = callers.map { spec -> controller.declaredMethods.single(spec::matches) }
        controller.declaredMethods.single(mode::matches)
        val modes = load(MODE)
        require(modes.isEnum && listOf("MODE_COLLAPSED", "MODE_1_ROW", "MODE_2_ROWS").all { name ->
            modes.getDeclaredField(name).let { it.type == modes && Modifier.isStatic(it.modifiers) }
        })
        val diff = load(DIFF)
        require(diff.superclass?.name == "androidx.recyclerview.widget.DiffUtil\$Callback")
        val constructor = diff.getDeclaredConstructor(ArrayList::class.java, ArrayList::class.java)
        val item = load(ITEM)
        val device = load(DEVICE)
        val detail = load(DETAIL)
        require(item.isAssignableFrom(device) && item.isAssignableFrom(detail))
        return Resolved(constructor, resolvedCallers, device, detail)
    }
}
