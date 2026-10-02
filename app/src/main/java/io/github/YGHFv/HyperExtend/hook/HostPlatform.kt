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
 */

package io.github.YGHFv.HyperExtend.hook

import android.os.Build
import io.github.YGHFv.HyperExtend.core.ModuleLog

/**
 * 宿主平台常量与识别。
 *
 * ## 为什么要显式识别 HyperOS
 *
 * 本模块全部功能都建立在一个前提上：**宿主是小米的 ROM**。这个前提不成立时，
 * 按类名去摸 `com.android.systemui.theme.ThemeOverlayController` 之类的目标要么摸不到（无害），
 * 要么摸到了同名但语义不同的类（有害 —— 例如 AOSP 的 `NotificationUtil` 与 MIUI 的同名类
 * 根本不是一回事）。
 *
 * 所以每个 feature 在装 hook 之前都先问一句 [isXiaomiRom]，而不是直接开摸。
 */
internal object HostPlatform {

    const val SYSTEMUI = "com.android.systemui"
    const val SETTINGS = "com.android.settings"
    const val SECURITY_CENTER = "com.miui.securitycenter"
    const val XIAOMI_SCANNER = "com.xiaomi.scanner"
    const val TSM_CLIENT = "com.miui.tsmclient"

    /** 小米 ROM 的标记类。MIUI 与 HyperOS 都带它，AOSP / 类原生不带。 */
    private const val MIUI_MARKER_CLASS = "miui.os.Build"

    fun isXiaomiRom(loader: ClassLoader): Boolean =
        Reflect.attempt { Class.forName(MIUI_MARKER_CLASS, false, loader) } != null

    /**
     * 澎湃的「大版本」。
     *
     * 读 `ro.miui.ui.version.name`（形如 `V816`）并取其中的数字部分除以 100：
     * HyperOS 1.0 → 8，HyperOS 2 → 9 …… 这个换算不可靠，所以**只用它做日志**，
     * 任何判断分支都不要依赖它。真正需要区分版本的地方，一律用「类/方法在不在」来判断 ——
     * 那才是唯一不会随命名规则变化而失真的依据。
     */
    fun romVersionName(): String =
        Reflect.attempt {
            val clazz = Class.forName("android.os.SystemProperties")
            val get = clazz.getMethod("get", String::class.java)
            get.invoke(null, "ro.miui.ui.version.name") as? String
        }?.takeIf { it.isNotBlank() } ?: "unknown"

    fun describe(): String =
        "sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE} " +
            "rom=${romVersionName()} device=${Build.MANUFACTURER}/${Build.MODEL}"

    /**
     * 记一条「这个功能跳过了，原因是……」。
     *
     * 「跳过」和「装失败」在日志里必须长得不一样：前者是预期的（不是小米 ROM、宿主版本没有那个类），
     * 后者要人去查。统一走这里，就不会有人把正常跳过写成 error。
     */
    fun logSkip(feature: String, reason: String) {
        ModuleLog.warn("$feature skipped: $reason")
    }
}
