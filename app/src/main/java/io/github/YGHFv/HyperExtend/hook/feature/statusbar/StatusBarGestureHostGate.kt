/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import android.graphics.Region
import android.view.View
import io.github.YGHFv.HyperExtend.hook.feature.systemui.NotificationHooks
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/** 读取原生 invokeInputManager 的禁用位与灵动岛门禁，不改写 Flow 或点击计时字段。 */
internal class StatusBarGestureHostGate(loader: ClassLoader) {
    private val t = StatusBarGestureTargets
    val handler = field(loader, t.OWNER, "mTouchEventHandler", t.HANDLER)
    val detector = field(loader, t.OWNER, "mStatusBarLongPressGestureDetector", t.DETECTOR)
    val listenerDetector = field(loader, t.LISTENER, "this\$0", t.DETECTOR)
    private val region = field(loader, t.OWNER, "mTouchableRegion", "android.graphics.Region")
    private val clickTool = field(loader, t.DETECTOR, "clickTool", t.CLICK_TOOL)
    private val commandQueue = field(loader, t.CLICK_TOOL, "commandQueue", t.COMMAND_QUEUE)
    private val disableState = field(loader, t.COMMAND_QUEUE, "disableState", t.FLOW)
    private val island = field(loader, t.CLICK_TOOL, "statusBarIsland", "dagger.Lazy")
    private val registered = field(loader, t.ISLAND, "registerFlow", t.FLOW)
    private val delegate = field(loader, t.FLOW, "\$\$delegate_0", "kotlinx.coroutines.flow.StateFlow")
    private val value = checkNotNull(NotificationHooks.resolve(loader, t.stateValue))
    private val first = checkNotNull(NotificationHooks.resolve(loader, t.pairFirst))
    private val lazyGet = checkNotNull(NotificationHooks.resolve(loader, t.lazyGet))
    private val click = checkNotNull(NotificationHooks.resolve(loader, t.nativeClick))

    fun inRegion(view: View, x: Float, y: Float): Boolean =
        (region.get(view) as? Region)?.contains(x.toInt(), y.toInt()) == true

    fun permits(view: View): Boolean = runCatching {
        val tool = tool(view)
        val flow = disableState.get(commandQueue.get(tool))
        val flags = (first.invoke(value.invoke(delegate.get(flow))) as Number).toInt()
        val showing = value.invoke(delegate.get(registered.get(lazyGet.invoke(island.get(tool))))) as Boolean
        permits(flags, showing)
    }.getOrDefault(false)

    fun native(view: View, double: Boolean) { click.invoke(tool(view), double) }

    private fun tool(view: View): Any = checkNotNull(clickTool.get(detector.get(view)))

    companion object {
        fun permits(flags: Int, islandShowing: Boolean): Boolean =
            !islandShowing && !(flags and 0x800000 != 0 && flags and 0x100000 != 0 && flags and 0x20000 != 0)

        private fun field(loader: ClassLoader, owner: String, name: String, type: String): Field =
            Class.forName(owner, false, loader).getDeclaredField(name).apply {
                require(this.type.name == type && !Modifier.isStatic(modifiers))
                isAccessible = true
            }
    }
}
