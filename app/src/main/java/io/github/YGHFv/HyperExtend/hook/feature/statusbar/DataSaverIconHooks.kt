/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import android.content.res.Resources
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime
import io.github.YGHFv.HyperExtend.hook.feature.systemui.NotificationHooks
import io.github.YGHFv.HyperExtend.hook.feature.systemui.SystemUiMethod
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 状态栏「流量节省」图标的显隐与 holder 补建（上游 `DataSaverIconOS4` 的等价实现）。
 *
 * ## 只在用户显式选了四档之一时安装
 *
 * `status_bar_icons.data_saver` 的 `默认(0)` 既不该改宿主布尔值，也不该替宿主建 holder，
 * 所以这一档直接不装 hook —— 「什么都不做」是最安全的默认。
 *
 * ## 为什么可见性要做在这里而不是块名单里
 *
 * 宿主 `PhoneStatusBarPolicy.onDataSaverChanged(Z)V` 的布尔值唯一去路就是
 * `StatusBarIconControllerImpl.setIconVisibility`。本模块的 [IconBlockListHooks] 管的是
 * 「槽位该出现在哪个区域」，这个方法管的是「这个槽位此刻是否显示」——两件事必须分开，
 * 否则「仅在控制中心显示」这类区域档位会被可见性一起改掉。
 *
 * ## 与上游的三点收敛
 *
 * 1. **只补建、不替换**：只有 `getIconHolder(tag, slot)` 返回 null 时才 `setIcon`，
 *    绝不去改已经存在的 holder（`bindable` / `mobile` / 外部槽位都可能复用同一个 tag）。
 * 2. **槽位名逐字校验**：`mSlotDataSaver` 必须等于 `data_saver` 才动手，否则原样放行。
 *    宿主哪天换了槽位，这里选静默退出，而不是改错一个图标。
 * 3. **资源缺失就放弃**：drawable 解析不到就不建，只记日志；绝不硬编码资源 id ——
 *    宿主升级后 id 会变，那是把「功能失效」换成「画错图标」。
 */
internal object DataSaverIconHooks {
    private const val FEATURE = "status_bar_icons"
    private const val MODE = "status_bar_icons.data_saver"

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        // 装入期只判断「这一档归不归本 hook 管」；具体取值在回调里实时读。
        if (!StatusIconPolicy.dataSaverManaged(settings.number(MODE, 0))) return 0

        val methods = (listOf(DataSaverIconTargets.callback, DataSaverIconTargets.setIcon) +
            DataSaverIconTargets.getHolder).associateWith {
            NotificationHooks.resolve(loader, it) ?: return 0
        }
        val fields = fields(loader) ?: return 0
        val controllerClass = Class.forName(DataSaverIconTargets.CONTROLLER, false, loader)

        val installed = HookRuntime.hook(
            methods.getValue(DataSaverIconTargets.callback), "$FEATURE/data-saver"
        ) { chain ->
            val owner = chain.thisObject ?: return@hook chain.proceed()
            val native = chain.args[0] as? Boolean ?: return@hook chain.proceed()
            // 槽位名读不到或不匹配时什么都不做：这比改错一个图标安全得多。
            val slot = fields.getValue(DataSaverIconTargets.POLICY to "mSlotDataSaver")
                .get(owner) as? String
            if (slot != DataSaverIconTargets.SLOT) return@hook chain.proceed()
            if (SafeModeRuntime.blocked || !HookRuntime.settings().isOn(FEATURE)) {
                return@hook chain.proceed()
            }
            val mode = HookRuntime.settings().number(MODE, 0)
            val visible = StatusIconPolicy.dataSaverVisible(mode, native) ?: return@hook chain.proceed()
            // 必须在原生 setIconVisibility 之前建好 holder，否则那一次调用还是空操作。
            if (visible) {
                runCatching { ensureHolder(owner, slot, fields, methods, controllerClass) }
                    .onFailure { ModuleLog.error("status_bar_icons/data-saver holder 补建失败", it) }
            }
            chain.proceed(arrayOf<Any?>(visible))
        }
        return if (installed) 1 else 0
    }

    private fun fields(loader: ClassLoader): Map<Pair<String, String>, Field>? = runCatching {
        DataSaverIconTargets.fields.associate { (owner, name, expected) ->
            (owner to name) to Class.forName(owner, false, loader).getDeclaredField(name).apply {
                require(type.name == expected && !Modifier.isStatic(modifiers))
                isAccessible = true
            }
        }
    }.onFailure { ModuleLog.warn("status_bar_icons/data-saver 字段形状不符：${it.message}") }.getOrNull()

    /** 只补建缺失的 holder，不动任何已存在的 holder。 */
    private fun ensureHolder(
        owner: Any,
        slot: String,
        fields: Map<Pair<String, String>, Field>,
        methods: Map<SystemUiMethod, Method>,
        controllerClass: Class<*>,
    ) {
        val controller = fields.getValue(DataSaverIconTargets.POLICY to "mIconController").get(owner)
            ?: return
        if (!controllerClass.isInstance(controller)) return
        val list = fields.getValue(DataSaverIconTargets.CONTROLLER to "mStatusBarIconList")
            .get(controller) ?: return
        if (methods.getValue(DataSaverIconTargets.getHolder)
                .invoke(list, DataSaverIconTargets.TAG, slot) != null) return

        val resources = fields.getValue(DataSaverIconTargets.POLICY to "mResources").get(owner)
            as? Resources ?: return
        val drawable = resources.getIdentifier(DataSaverIconTargets.DRAWABLE, "drawable",
            DataSaverIconTargets.PACKAGE)
        if (drawable == 0) {
            ModuleLog.warn("status_bar_icons/data-saver 找不到 ${DataSaverIconTargets.DRAWABLE}，保持原生")
            return
        }
        methods.getValue(DataSaverIconTargets.setIcon)
            .invoke(controller, description(resources, slot), slot, drawable)
    }

    /** 无障碍描述：优先 SystemUI 本地化串，其次框架槽位串，最后退回槽位名。 */
    private fun description(resources: Resources, fallback: String): String = sequenceOf(
        DataSaverIconTargets.PACKAGE to DataSaverIconTargets.DESCRIPTION,
        "android" to "status_bar_data_saver",
    ).map { (pkg, name) -> resources.getIdentifier(name, "string", pkg) }
        .firstOrNull { it != 0 }
        ?.let { runCatching { resources.getString(it) }.getOrNull() }
        ?: fallback
}
