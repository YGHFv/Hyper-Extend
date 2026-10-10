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

import android.content.SharedPreferences
import io.github.YGHFv.HyperExtend.core.FEATURES
import io.github.YGHFv.HyperExtend.core.defaultEnabledOf
import io.github.YGHFv.HyperExtend.core.featureById

/**
 * 被注入进程里的开关读取。
 *
 * 数据来自框架托管的 RemotePreferences（`getRemotePreferences(HyperSettings.GROUP)`），
 * 由模块 App 写入。这里**不缓存**：RemotePreferences 是快照语义，缓存下来就再也看不到
 * 用户在界面上的改动，表现为「改了开关要重启才生效」——而这个模块里绝大多数功能
 * 本来就是重启才生效，两者叠在一起会让用户彻底分不清是哪一种。
 *
 * ## 读不到时一律当作「关」
 *
 * 框架没有 `PROP_CAP_REMOTE` 能力、或 RemotePreferences 还没被写过时，[prefs] 会是 null。
 * 此时**所有开关读出来都是 false**（而不是回落到目录里的默认值）：目录里那些默认值描述的是
 * 「用户第一次装这个模块时开关长什么样」，不是「读不到设置时该猜什么」。二者混淆的后果是
 * 一批用户根本没开过的功能在后台被装上了 hook。
 */
class HookSettings internal constructor(private val prefs: SharedPreferences?) {
    internal fun safeModeDisabled(scope: String): Boolean = runCatching {
        prefs?.getBoolean(io.github.YGHFv.HyperExtend.core.SafeModeKeys.disabled(scope), false) ?: true
    }.getOrDefault(true)

    internal fun safeModeReset(scope: String): Long = maxOf(
        long(io.github.YGHFv.HyperExtend.core.SafeModeKeys.reset(scope), 0),
        long(io.github.YGHFv.HyperExtend.core.FRAMEWORK_FUSE_RESET_KEY, 0),
    )

    /** 这份设置是否真的读到了。界面侧用来在「关于」页提示「设置未同步」。 */
    val isAvailable: Boolean get() = prefs != null

    /**
     * 读一个开关。
     *
     * ## 子项必须先过主开关 —— 这是防砖的第一道闸
     *
     * 目录里 `HyperOption.defaultEnabled` 的默认值是 `true`（主开关一打开，各子项就该全部生效，
     * 不必逐个去拨）。而 hook 侧有几处是**直接按子项 id 判断**的：`passkey_fix.system_server`、
     * `nfc_card_face.tsmclient` …… 如果这里只读那一个键，这些子项就会在「用户从没打开过主开关、
     * 甚至从没进过模块界面」的情况下读出 `true`。后果不是功能不生效，而是
     * **模块装上去什么都没开，system_server 里已经挂上了 hook** —— 这正是能走到开机循环的那类 bug。
     *
     * 所以：传进来的是子项 id 时，它所属功能的主开关也必须为真。
     * 判断放在 [isOn] 内部而不是各个调用点，是为了让「以后新增一个直接读子项的地方」
     * **不可能忘记**加这个前置条件。
     *
     * 其余语义不变：`defaultEnabledOf(id)` 作 fallback 只在 prefs 存在、但这个键没被写过
     * （例如从旧版本升级上来）时生效；目录里查不到的 id 一律 false —— 宁可关着，
     * 也不要一个没人认识的键把功能打开。
     */
    fun isOn(id: String): Boolean {
        if (featureById(id) != null) return raw(id)
        val owner = FEATURES.firstOrNull { feature -> feature.options.any { it.id == id } }
            ?: return false
        // 配置型功能（`configKey != null`，目前只有 NFC 卡面）**没有布尔主开关** ——
        // 它的「开」是「配了东西」。所以它的子项不依赖任何布尔键，直接读自己：
        // 若照其它功能那样先读 `raw(owner.id)`，那个键的默认值恰好是 false，
        // 结果是子项永远读成关（用户拨了开关却毫无效果，且根本查不出为什么）。
        if (owner.configKey != null) return raw(id)
        return raw(owner.id) && raw(id)
    }

    /** 不做子项归属检查的原始读取，只给 [isOn] 用。 */
    private fun raw(id: String): Boolean = runCatching {
        prefs?.getBoolean(id, defaultEnabledOf(id)) ?: false
    }.getOrDefault(false)

    /** 读一个字符串设置。缺省是空串 —— 所有的字符串项语义都是「空 = 未配置」，
     * 于是调用方不用区分「没这个键」和「值是空」，一条 `isBlank()` 就够。 */
    fun string(key: String): String = runCatching { prefs?.getString(key, "") ?: "" }.getOrDefault("")

    /**
     * 读一个数值型配置（滑块）。
     *
     * 目录里的数值配置按**字符串**存（见 `core/FeatureCatalog` 的 [HyperSlider]），
     * 所以这里解析一次十进制。解析不出来（用户从没进过界面、值被写坏）时按 [fallback] ——
     * 也就是目录里登记的那个默认值，与界面显示的默认值同一个出处。
     *
     * 越界一律夹回区间：滑块的区间是界面给的，而它可能随版本收窄，
     * 老设置里的旧取值落在区间外时，直接拿来用就是「宿主自己读了都不认识的一个数」。
     */
    fun number(key: String, fallback: Int, min: Int = Int.MIN_VALUE, max: Int = Int.MAX_VALUE): Int =
        string(key).toIntOrNull()?.coerceIn(min, max) ?: fallback
    /** 读内部控制用的长整型令牌；设置类型异常时按缺省值处理。 */
    fun long(key: String, default: Long = 0L): Long = runCatching {
        prefs?.getLong(key, default) ?: default
    }.getOrDefault(default)
}
