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
 * 功能来源：HyperWallpaperMonet（PengDingkang，Apache-2.0）。
 *
 * 挂载点（三个类/方法）与「壁纸颜色监听器靠 dex 扫描 + 结构校验来定位」的做法，
 * 均取自该项目的 ModernEntry。修复算法本身在 [WallpaperColorRepair]。
 */

package io.github.YGHFv.HyperExtend.hook.feature

import android.app.WallpaperColors
import android.app.WallpaperManager
import io.github.YGHFv.HyperExtend.core.MONET_SCHEME_KEY
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.DexScan
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect

/** 「壁纸取色修复」。作用域：com.android.systemui。 */
internal object WallpaperMonetFix {

    private const val FEATURE = "wallpaper_monet"
    private const val OPT_STARTUP = "wallpaper_monet.startup"
    private const val OPT_RETRY = "wallpaper_monet.retry"
    private const val OPT_EVENTS = "wallpaper_monet.events"

    private const val CONTROLLER_CLASS = "com.android.systemui.theme.ThemeOverlayController"

    /** libmonet 里「取色风格」的枚举与承载它的方案对象。宿主是这套实现的移植，两个类都在。 */
    private const val VARIANT_CLASS = "com.google.ux.material.libmonet.dynamiccolor.Variant"
    private const val SCHEME_CLASS = "com.google.ux.material.libmonet.dynamiccolor.DynamicScheme"

    /** 监听器是控制器的内部类，名字形如 `ThemeOverlayController$1`、`$WallpaperColorsListener`…… */
    private const val CONTROLLER_NESTED_PREFIX = "ThemeOverlayController$"

    /**
     * @return 成功挂上的 hook 数
     */
    fun install(loader: ClassLoader, settings: HookSettings): Int {
        var installed = 0

        // 取色风格与下面那组「修复」互不依赖：它改的是「生成配色时用哪一套风格」。
        // 所以即使控制器类不存在（非澎湃设备），也照样尝试安装它。
        val scheme = settings.string(MONET_SCHEME_KEY).trim()
        if (scheme.isNotEmpty()) {
            installed += installSchemeVariantHook(loader, scheme)
        }

        val controller = Reflect.loadClass(loader, CONTROLLER_CLASS)
        if (controller == null) {
            // 不是每个 ROM 都有这个类；找不到就说明这台设备不适用，安静跳过「修复」那一组。
            ModuleLog.warn("$FEATURE: $CONTROLLER_CLASS not found — repair hooks skipped")
        } else {
            val repair = WallpaperColorRepair()
            val retry = settings.isOn(OPT_RETRY)

            if (settings.isOn(OPT_STARTUP)) {
                val start = Reflect.findMethods(controller, "start", paramCount = 0).firstOrNull()
                if (HookRuntime.hookAfter(start, "$FEATURE/ThemeOverlayController#start") { chain, original ->
                        repair.onControllerStarted(chain.thisObject, retry)
                        original
                    }
                ) installed++
            }

            if (settings.isOn(OPT_EVENTS)) {
                installed += installReevaluateHook(controller, repair)
                installed += installColorsChangedHook(loader, controller, repair)
            }
        }

        if (installed == 0) {
            ModuleLog.warn("$FEATURE: nothing installed (targets missing or all sub-switches off)")
        }
        return installed
    }

    /**
     * 把取色风格固定成用户选的那一个（libmonet 的 `Variant`）。
     *
     * ## 为什么挂在 `DynamicScheme` 的构造器上
     *
     * 宿主里「风格」这条链的末端就是它：各式 Scheme 子类（`SchemeTonalSpot`、`SchemeVibrant`……）
     * 最终都构造一个 `DynamicScheme`。本机传入的调色板已在构造前生成，不能只改 Variant；
     * 必须使用同一色源、明暗、对比度和 ColorSpec 重新生成五组调色板及错误色板。
     * 只处理经过完整签名校验的构造器，不支持的版本保持宿主行为。
     *
     * 传入的名字是 libmonet 的公开枚举名（`TONAL_SPOT` 等），这里在本机宿主的
     * `Variant` 类上按名字取常量 —— 于是枚举顺序变了也不会错位。
     */
    private fun installSchemeVariantHook(loader: ClassLoader, variantName: String): Int {
        val variantClass = Reflect.loadClass(loader, VARIANT_CLASS) ?: run {
            ModuleLog.warn("$FEATURE: $VARIANT_CLASS not found — scheme style not installable")
            return 0
        }
        val wanted = Reflect.attempt {
            variantClass.enumConstants?.firstOrNull { (it as Enum<*>).name == variantName }
        } ?: run {
            ModuleLog.warn("$FEATURE: unknown monet variant '$variantName'")
            return 0
        }
        val schemeClass = Reflect.loadClass(loader, SCHEME_CLASS) ?: run {
            ModuleLog.warn("$FEATURE: $SCHEME_CLASS not found")
            return 0
        }

        var count = 0
        for (ctor in schemeClass.declaredConstructors) {
            val compat = MonetPaletteCompat.resolve(loader, ctor, wanted) ?: continue
            if (HookRuntime.hook(ctor, "$FEATURE/DynamicScheme#${ctor.parameterCount}args") { chain ->
                    val replacement = Reflect.safe("monet: rebuild $variantName palettes") {
                        compat.rewrite(chain.args)
                    }
                    if (replacement == null) chain.proceed() else chain.proceed(replacement)
                }
            ) count++
        }
        if (count > 0) {
            ModuleLog.info("$FEATURE: monet variant forced to $variantName ($count constructor(s))")
        } else {
            ModuleLog.warn("$FEATURE: no safe DynamicScheme palette constructor matched — style not applied")
        }
        return count
    }

    /**
     * `reevaluateSystemTheme(boolean)`。
     *
     * 两种改法按需选择：
     * - 需要补齐颜色 → 先补，再把参数强制成 `true`（要求连覆盖层一起重建）；
     * - 不需要 → 原样放行。
     *
     * 注意这里是**替换参数**（`chain.proceed(newArgs)`）而不是改 `before` 里的 args —
     * libxposed 的 Chain 是不变接口，参数只能通过 proceed 的重载交回去。
     */
    private fun installReevaluateHook(controller: Class<*>, repair: WallpaperColorRepair): Int {
        val method = Reflect.findMethods(controller, "reevaluateSystemTheme")
            .firstOrNull { it.parameterCount == 1 && it.parameterTypes[0] == java.lang.Boolean.TYPE }
            ?: run {
                ModuleLog.warn("$FEATURE: reevaluateSystemTheme(boolean) not found")
                return 0
            }
        return if (HookRuntime.hook(method, "$FEATURE/ThemeOverlayController#reevaluateSystemTheme") { chain ->
                if (repair.beforeReevaluate(chain.thisObject)) {
                    chain.proceed(arrayOf<Any?>(java.lang.Boolean.TRUE))
                } else {
                    chain.proceed()
                }
            }
        ) 1 else 0
    }

    /** 壁纸颜色监听器的 `onColorsChanged(WallpaperColors, int, int)`。 */
    private fun installColorsChangedHook(
        loader: ClassLoader,
        controller: Class<*>,
        repair: WallpaperColorRepair,
    ): Int {
        val listener = findColorListenerClass(loader, controller) ?: return 0
        val method = Reflect.findMethods(listener, "onColorsChanged").firstOrNull {
            it.parameterCount == 3 &&
                it.parameterTypes[0] == WallpaperColors::class.java &&
                it.parameterTypes[1] == Integer.TYPE &&
                it.parameterTypes[2] == Integer.TYPE
        } ?: run {
            ModuleLog.warn("$FEATURE: listener#onColorsChanged(WallpaperColors,int,int) not found")
            return 0
        }
        // 监听器自己持有控制器引用，事件里不带控制器，所以得从 thisObject 反查。
        val controllerField = Reflect.findFieldByType(listener, controller)
        return if (HookRuntime.hookAfter(method, "$FEATURE/listener#onColorsChanged") { chain, original ->
                val colors = chain.args.getOrNull(0) as? WallpaperColors
                val which = chain.args.getOrNull(1) as? Int ?: 0
                val userId = chain.args.getOrNull(2) as? Int ?: Int.MIN_VALUE
                val owner = chain.thisObject
                val target = controllerField?.let { Reflect.safe("listener->controller") { it.get(owner) } }
                if (target != null) repair.onColorsChanged(target, colors, which, userId)
                original
            }
        ) 1 else 0
    }

    /**
     * 定位壁纸颜色监听器。
     *
     * 为什么不能写死：它是控制器的**匿名/内部类**，编译后的名字编号（`$1`、`$2`……）
     * 随版本变化，而且中间一旦插入别的内部类，编号全部平移。所以走「找候选 → 按结构校验」：
     *
     * 1. 必须是 `ThemeOverlayController$*`；
     * 2. 必须实现 `WallpaperManager.OnColorsChangedListener`；
     * 3. 必须声明了 `onColorsChanged(WallpaperColors, int, int)`；
     * 4. 必须有一个类型可赋值给控制器的字段（它得能回调回控制器）。
     *
     * 四条同时满足才能中选。这样即使编号变了、甚至被 R8 内联改名，也照样找得到。
     */
    private fun findColorListenerClass(loader: ClassLoader, controller: Class<*>): Class<*>? {
        val candidates = DexScan.namesWithSimpleNamePrefix(
            HookRuntime.codePaths,
            CONTROLLER_NESTED_PREFIX,
        )
        for (name in candidates) {
            val candidate = Reflect.attempt { Class.forName(name, false, loader) } ?: continue
            if (!WallpaperManager.OnColorsChangedListener::class.java.isAssignableFrom(candidate)) continue
            val hasMethod = Reflect.findMethods(candidate, "onColorsChanged").any {
                it.parameterCount == 3 && it.parameterTypes[0] == WallpaperColors::class.java
            }
            if (!hasMethod) continue
            if (Reflect.findFieldByType(candidate, controller) == null) continue
            ModuleLog.info("$FEATURE: resolved wallpaper color listener: $name")
            return candidate
        }
        // 扫描失败时退回编号枚举：上游也是这么兜的，能救回「dex 枚举被 ROM 限制」的情况。
        for (index in 1..32) {
            val candidate = Reflect.attempt {
                Class.forName("$CONTROLLER_CLASS\$$index", false, loader)
            } ?: continue
            if (!WallpaperManager.OnColorsChangedListener::class.java.isAssignableFrom(candidate)) continue
            if (Reflect.findFieldByType(candidate, controller) == null) continue
            ModuleLog.info("$FEATURE: resolved listener by index fallback: ${candidate.name}")
            return candidate
        }
        ModuleLog.warn("$FEATURE: wallpaper color listener not found (scanned ${candidates.size} candidates)")
        return null
    }
}
