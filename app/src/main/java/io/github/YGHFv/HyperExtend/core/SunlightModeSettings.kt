/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

/**
 * 「原生阳光模式磁贴」的**亮度增强方式** —— 上游 `sunshine_new_mode_high` 与
 * `sunshine_mode_brightness` 两行的合并落点。
 *
 * ## 为什么这两行合一
 *
 * 上游把它们做成阳光模式磁贴的**两个配置行**（一个三选一「高亮方式」，一个自定义亮度滑块），
 * 与「系统默认模式」一起决定点击磁贴时做什么 —— 它们本来就是同一件事的两种取值，
 * 分开成两张卡片只会得到「两个开关同时开着、谁生效说不清」。所以这里合成一个
 * 「亮度增强方式」选择项 + 一个「自定义亮度」滑块，与上游 XML 的结构一致。
 *
 * ## 为什么不照抄上游的 sysfs 写入
 *
 * 上游高亮方式的实现是 `chmod 777` 背光节点后 `echo Integer.MAX_VALUE > brightness`
 * （或写 `mi_display/.../brightness_clone`）。真机取证：
 *
 * - `/sys/class/backlight/panel0-backlight/brightness` 是 `0644 system:system`
 *   `u:object_r:vendor_sysfs_graphics:s0`，SystemUI 以 `u0_a204`（app id 10204）运行，
 *   既不是 system 也不是 root，DAC 与 SELinux 都写不进去；
 * - `max_brightness` 读数是 16383，上游却写 `Integer.MAX_VALUE`，**没有任何取值校验**；
 * - 上游把「上一个亮度」只存在进程字段里，进程一重启就丢，节点却停在写入后的值 ——
 *   这正是我们拒绝照抄的那类不可恢复状态。
 *
 * ## 当前固件里真正可用的载体
 *
 * 用系统自己的亮度设置面：`Settings.System.screen_brightness` 与
 * `screen_brightness_mode`，取值上下限由 `PowerManager.getMinimumScreenBrightnessSetting()`
 * / `getMaximumScreenBrightnessSetting()` 给出（与真机 `dumpsys display` 的
 * `brightnessMinimum/brightnessMaximum` 同源）。SystemUI 持有 `WRITE_SETTINGS`
 * （granted=true），因此不需要 root、不需要改权限位、不碰任何 sysfs。
 *
 * 代价是**只能到达原生上限**，不能像上游那样把面板推到超出系统允许的亮度：
 * 这是有界迁移，不是像素级复刻。上限由 `PowerManager` 给出，而不是写死的巨大整数，
 * 所以面板不会收到它自己不认识的取值。
 *
 * ## 快照与恢复
 *
 * 写入前把「原亮度 + 原亮度模式」存进宿主进程自己的 SharedPreferences
 * （见 `hook/feature/systemui/SunlightBrightnessHooks`），它跟着 SystemUI 一起活过
 * 进程重启与整机重启；关闭磁贴、或关掉本功能后再进一次 SystemUI，都会按快照恢复。
 * 上游那个「只存在进程字段」的缺陷在这里被结构性消除。
 */
object SunlightModeSettings {
    /** 与已发布的阳光模式卡片同键：这两行是它的配置，不是新卡片。 */
    const val FEATURE = "control_center_sunlight_tile"

    /** 亮度增强方式（上游 `sunshine_new_mode_high`）。 */
    const val MODE = "$FEATURE.mode"
    const val MODE_SYSTEM = "1"
    const val MODE_MAX = "2"
    const val MODE_CUSTOM = "3"

    /** 自定义亮度（上游 `sunshine_mode_brightness`）。存十进制整数。 */
    const val BRIGHTNESS = "$FEATURE.brightness"

    /**
     * 滑块区间。
     *
     * 界面上的区间是**保守的外壳**：真正的上下限在注入侧按设备实际值夹取
     * （见 [clampBrightness]），因为各机型的 `screen_brightness` 量程并不一致
     * （有的 0..255，有的 0..32767）。这里用 Android 当前的通用量程上限作壳，
     * 用户选的数落在壳内、又落在设备量程内时才会写。
     */
    const val BRIGHTNESS_MIN = 1
    const val BRIGHTNESS_MAX = 32767
    const val BRIGHTNESS_DEFAULT = 2048

    val brightness = HyperSlider(
        BRIGHTNESS, "自定义亮度", BRIGHTNESS_MIN, BRIGHTNESS_MAX, BRIGHTNESS_DEFAULT,
        summary = "仅在「亮度增强方式 = 自定义亮度」时使用；实际写入值按设备亮度量程夹取，不写节点",
    )

    val mode = HyperChoice(
        MODE, "亮度增强方式",
        listOf(
            ChoiceEntry(MODE_SYSTEM, "系统默认模式"),
            ChoiceEntry(MODE_MAX, "强制最高亮度"),
            ChoiceEntry(MODE_CUSTOM, "自定义亮度"),
        ),
        summary = "系统默认 = 切换系统阳光模式偏好；后两项改为写入系统亮度设置（关闭磁贴或本功能后按快照恢复）",
    )

    /** 功能详情页里的两行：先选方式，再给自定义档配数值。 */
    val config = listOf(mode, brightness)

    /** 归一化取值：空串与未知值一律按「系统默认模式」，也就是上游缺省的那一档。 */
    fun modeOf(raw: String): String = when (raw) {
        MODE_MAX -> MODE_MAX
        MODE_CUSTOM -> MODE_CUSTOM
        else -> MODE_SYSTEM
    }

    /** 这一档是否改系统亮度设置（而不是切换阳光模式偏好）。 */
    fun overridesBrightness(raw: String): Boolean = modeOf(raw) != MODE_SYSTEM

    /** 界面滑块的夹取：只按目录登记的区间，与设备无关。 */
    fun storedBrightness(raw: String): Int =
        raw.toIntOrNull()?.coerceIn(BRIGHTNESS_MIN, BRIGHTNESS_MAX) ?: BRIGHTNESS_DEFAULT

    /**
     * 真正要写进 `screen_brightness` 的值。
     *
     * 写出去的值**永远落在设备自己的量程内**：最大亮度档直接取 `PowerManager` 给的上限，
     * 自定义档按同一个区间夹取。设备量程本身非法（上限不大于下限）时返回 null ——
     * 调用方据此**不写**，保持原生行为。
     *
     * 这与上游「chmod 777 后写 `Integer.MAX_VALUE`，让面板自己去猜」正好相反：
     * 宁可这一档不生效，也不把面板没见过的取值塞进系统设置。
     *
     * @param mode 原始的「亮度增强方式」设置值
     * @param custom 用户配置的自定义亮度
     * @param deviceMin 设备下限（PowerManager 给的最小亮度设置）
     * @param deviceMax 设备上限（PowerManager 给的最大亮度设置）
     */
    fun targetBrightness(mode: String, custom: Int, deviceMin: Int, deviceMax: Int): Int? {
        if (deviceMax <= deviceMin) return null
        return when (modeOf(mode)) {
            MODE_MAX -> deviceMax
            MODE_CUSTOM -> clampBrightness(custom, deviceMin, deviceMax)
            else -> null
        }
    }

    /**
     * 按设备量程夹取。
     *
     * 「夹取」而不是「越界即拒绝」：界面上那个 1..32767 的区间只是保守外壳，各机型量程不同
     * （0..255 的机型很常见），用户在外壳里选的数落到设备量程外时，
     * 夹到设备认可的上/下限仍然是一个**设备接受**的取值；而拒绝写入会让这一档在那些机型上
     * 永远点不动。只有在设备量程非法时才返回 null（不写）。
     */
    fun clampBrightness(value: Int, deviceMin: Int, deviceMax: Int): Int? =
        if (deviceMax > deviceMin) value.coerceIn(deviceMin, deviceMax) else null
}
