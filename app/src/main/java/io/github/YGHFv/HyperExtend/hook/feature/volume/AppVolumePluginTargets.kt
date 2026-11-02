/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.volume

import android.content.Context
import java.lang.reflect.Method

/** OS3 的关闭回调在控制器中，较新宿主则先经过对话框的关闭桥。 */
internal class AppVolumePluginTargets private constructor(
    val os3: Boolean, val finish: Method, val finishCallers: List<Method>,
) {
    val resourceUpdates = if (os3) listOf("updateResources") else listOf("onMaterialModeChanged", "updateResources")

    fun wideFold(loader: ClassLoader): (Context) -> Boolean {
        if (!os3) {
            val query = Class.forName("miui.systemui.util.DeviceUtils", false, loader)
                .getDeclaredMethod("isWideFoldDevice")
            return { query.invoke(null) == true }
        }
        val common = Class.forName("miui.systemui.util.CommonUtils", false, loader)
        val instance = common.getDeclaredField("INSTANCE").get(null)
        val fold = common.getDeclaredMethod("getIS_FOLD")
        val large = common.getDeclaredMethod("isScreenLayoutLarge", Context::class.java)
        return { fold.invoke(instance) == true && large.invoke(null, it) == true }
    }

    companion object {
        fun resolve(loader: ClassLoader, dialog: Class<*>, controller: Class<*>): AppVolumePluginTargets {
            val current = runCatching { dialog.getDeclaredMethod("lambda\$dismissH\$0", Runnable::class.java) }.getOrNull()
            if (current != null) {
                return AppVolumePluginTargets(false, current, listOf(
                    Class.forName("com.android.systemui.miui.volume.s", false, loader).getDeclaredMethod("run"),
                    dialog.getDeclaredMethod("a", dialog, Runnable::class.java),
                ))
            }
            val finish = controller.getDeclaredMethod("lambda\$dismissVolumePanel\$9")
            require(finish.returnType == Void.TYPE)
            return AppVolumePluginTargets(true, finish, listOf(
                Class.forName("com.android.systemui.miui.volume.J", false, loader).getDeclaredMethod("run"),
                controller.getDeclaredMethod("a", controller),
            ))
        }
    }
}
