/*
 * Copyright (C) 2026 YGHFv
 *
 * 本文件是「澎湃补全计划」（HyperExtend）的一部分。
 *
 * 本程序是自由软件：你可以依据 GNU Affero 通用公共许可证（由自由软件基金会发布）
 * 的条款重新发布和/或修改它，无论是许可证的第 3 版，还是（由你选择）任何更新的版本。
 *
 * 本程序基于「希望它有用」而分发，但不提供任何担保；甚至不包括对适销性或特定用途
 * 适用性的默示担保。详见 GNU Affero 通用公共许可证。
 *
 * ---------------------------------------------------------------------------
 * 功能来源：西米露 / HyperCeiler · MobilePublicHookV、HideVoWiFiIcon（AGPL-3.0）。
 */

package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect

/**
 * 「移动网络」。作用域：com.android.systemui。
 *
 * ## 两个靶子，因为这两类标记本来就不在一处
 *
 * - **漫游与活动指示器**：属于「这条 SIM 当前该显示什么」，住在移动网络图标的视图模型上
 *   （`MiuiCellularIconVM`）。它在 OS4 上已经不是构造器带参注入的写法了，
 *   构造回调回来时字段还全是空的 —— 所以要挂在**绑定流程**（`MiuiMobileIconBinder#bind`）
 *   上，那里拿到的视图模型才是填好的。参考项目对 OS4 也是这么做的。
 * - **VoWiFi / VoLTE**：属于运营商定制，住在 `IOperatorCustomizedPolicy$OperatorConfig`，
 *   ROM 每次构造它都会按运营商填一遍，构造后盖掉那两个布尔量即可。
 *
 * ## 为什么用「常量流」而不是去改判断逻辑
 *
 * 这几个字段都是状态流，界面层只负责 `collect`。往里换一条恒定的流，等价于
 * 「这个值永远是假的」—— 不需要知道宿主是怎么算出它的，也不会因为 ROM 改了算法而失效。
 */
internal object StatusBarMobile {

    private const val FEATURE = "status_bar_mobile"

    private const val OPT_HIDE_ROAMING = "status_bar_mobile.hide_roaming"
    private const val OPT_HIDE_VOWIFI = "status_bar_mobile.hide_vowifi"
    private const val OPT_HIDE_VOLTE = "status_bar_mobile.hide_volte"
    private const val OPT_HIDE_INDICATOR = "status_bar_mobile.hide_indicator"

    private const val BINDER =
        "com.android.systemui.statusbar.pipeline.mobile.ui.binder.MiuiMobileIconBinder"
    private const val OPERATOR_CONFIG = "com.miui.interfaces.IOperatorCustomizedPolicy\$OperatorConfig"

    /** 一条 SIM 的视图模型上，这几个字段控制着「小标记显不显示」。 */
    private val ROAMING_FIELDS = arrayOf("mobileRoamVisible", "smallRoamVisible")

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        var installed = 0
        installed += MobileSignalVisibility.install(loader, settings)
        installed += installMobileMarkers(loader, settings)
        installed += installOperatorIcons(loader, settings)
        return installed
    }

    // ------------------------------------------------------------------ 漫游 / 活动指示器

    private fun installMobileMarkers(loader: ClassLoader, settings: HookSettings): Int {
        val hideRoaming = settings.isOn(OPT_HIDE_ROAMING)
        val hideIndicator = settings.isOn(OPT_HIDE_INDICATOR)
        if (!hideRoaming && !hideIndicator) return 0

        val binder = Reflect.loadClass(loader, BINDER) ?: run {
            ModuleLog.warn("$FEATURE: $BINDER not found — mobile markers skipped")
            return 0
        }
        val bind = Reflect.findMethods(binder, "bind", 4).firstOrNull() ?: run {
            ModuleLog.warn("$FEATURE: MiuiMobileIconBinder#bind(ViewGroup,*,*,*) is gone")
            return 0
        }

        val flows = StateFlowFactory(loader)
        val hidden = flows.constant(false) ?: run {
            ModuleLog.warn("$FEATURE: kotlinx StateFlow unavailable — mobile markers skipped")
            return 0
        }

        val ok = HookRuntime.hook(bind, "$FEATURE/MiuiMobileIconBinder#bind") { chain ->
            // 第三个参数就是这一条 SIM 的视图模型（见 MiuiMobileIconBinder#bind 的签名）。
            val viewModel = chain.args.getOrNull(2)
            if (viewModel != null) {
                if (hideIndicator) replaceOnce(viewModel, "inOutVisible", hidden)
                if (hideRoaming) {
                    for (field in ROAMING_FIELDS) replaceOnce(viewModel, field, hidden)
                }
            }
            // Attached views may collect synchronously inside bind; replace before that happens.
            chain.proceed()
        }
        return if (ok) 1 else 0
    }

    /**
     * 换掉一个字段里的状态流，且**只换一次**。
     *
     * 绑定流程对同一个视图模型会被走到很多次（切卡、旋转、进出控制中心……）。
     * 每次都重写一遍字段，会把界面层已经收集到的那条旧流换成一条新的 ——
     * 表现是「标记闪一下又回来了」。判据是恒等比较：字段里已经是我们造的那条流就跳过。
     */
    private fun replaceOnce(target: Any, field: String, replacement: Any) {
        if (StatusBarFields.alreadyReplaced(target, field, replacement)) return
        if (!StatusBarFields.write(target, field, replacement)) {
            ModuleLog.warn("$FEATURE: $field could not be replaced on ${target.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------------ VoWiFi / VoLTE

    /**
     * VoWiFi / VoLTE 图标。
     *
     * 这两个不是靠黑名单，而是运营商定制层的两个布尔字段：ROM 每次构造 `OperatorConfig`
     * 都会按运营商填一遍。所以在构造完成后盖掉要隐藏的那两个。
     * 这个类来自 MIUI 的框架层（不是 SystemUI 自己的 dex），拿不到就跳过 ——
     * 在别的 ROM 上它本来也不存在。
     */
    private fun installOperatorIcons(loader: ClassLoader, settings: HookSettings): Int {
        val hideVowifi = settings.isOn(OPT_HIDE_VOWIFI)
        val hideVolte = settings.isOn(OPT_HIDE_VOLTE)
        if (!hideVowifi && !hideVolte) return 0
        val config = Reflect.loadClass(loader, OPERATOR_CONFIG) ?: run {
            ModuleLog.warn("$FEATURE: $OPERATOR_CONFIG not found — vowifi/volte skipped")
            return 0
        }
        val constructor = config.constructors.firstOrNull() ?: return 0
        val ok = HookRuntime.hookAfter(constructor, "$FEATURE/OperatorConfig#<init>") { chain, original ->
            if (hideVowifi) Reflect.writeField(chain.thisObject, "hideVowifi", true)
            if (hideVolte) Reflect.writeField(chain.thisObject, "hideVolte", true)
            original
        }
        return if (ok) 1 else 0
    }
}
