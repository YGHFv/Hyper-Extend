/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

/**
 * 合并卡片的一次性键搬迁规则。
 *
 * 拆成纯函数而不是直接写进 `HyperSettings`，理由和目录里其它策略一致：搬迁是**有分支的判断**
 * （老键存不存在、显式样式是什么、两个子项各自要不要跟着开），直接塞进读写代码里就只能靠
 * 真机升级来验证。这里输入输出都是普通值，单元测试可以覆盖每条分支。
 *
 * 规则只做「老键 → 新键」的**最小搬迁**：不猜用户意图、不覆盖用户显式选过的配置。
 */
internal object MergedCardMigration {

    /**
     * 一次迁移要写的东西。[switches] 是布尔开关，[configs] 是多选一/数值这类字符串配置键。
     *
     * 两个集合都只包含「确实需要写」的键：老的值是默认/未设置时不要顺手写一遍，
     * 否则会把用户没碰过的东西标成「显式选过」，之后再也分不清默认与用户选择。
     */
    data class Plan(val switches: Map<String, Boolean>, val configs: Map<String, String>)

    /**
     * v1 的两张卡片 `control_center_brightness_value` / `control_center_volume_value`
     * → v2 的 `slider_percentage`（原位数值方式）。
     *
     * 两张老卡片的效果就是「控制中心原位数值」，等价于合并后的 `style=1` + 对应子项；
     * 所以任一老卡片被写过且为真时：
     * - 打开对应子项（亮度 / 音量各自独立）；
     * - 打开总开关 `slider_percentage`（否则子项会被 `HookSettings.isOn` 的主开关闸门挡掉）；
     * - 仅当用户**没有显式选过**显示方式时写入 `style=1`；已经选过就尊重用户的选择，
     *   因为原位与浮层是同一功能的两套实现，合并后只能保留一种，用户显式选的那个优先。
     *
     * **老键为假时一个开关都不写**：合并前 `slider_percentage.brightness` 本来就是独立子项，
     * 用户可能在那边开着、在这张独立卡片关着。把老键的 `false` 搬过去会静默关掉用户正用着的功能，
     * 所以搬迁只做「加」不做「减」—— 老键为真就打开，老键为假就只删键。
     *
     * @param brightness 老亮度键的值；键不存在传 null
     * @param volume 老音量键的值；键不存在传 null
     * @param style 当前已存的显示方式；没存过传 null
     * @return 需要写入的键；两个老键都没出现过时返回 null（无事可做）
     */
    fun sliderValuePlan(brightness: Boolean?, volume: Boolean?, style: String?): Plan? {
        if (brightness == null && volume == null) return null
        val enabled = brightness == true || volume == true
        if (!enabled) return Plan(emptyMap(), emptyMap())
        val switches = LinkedHashMap<String, Boolean>(3)
        if (brightness == true) switches[PercentageSettings.BRIGHTNESS] = true
        if (volume == true) switches[PercentageSettings.VOLUME] = true
        switches[PercentageSettings.FEATURE] = true
        return Plan(switches, if (style == null) mapOf(PercentageSettings.STYLE to "1") else emptyMap())
    }

    /**
     * 合并前 `gesture_line` / `navigation_geometry` 两张卡片 → `navigation_handle_custom` 的处理方式。
     *
     * 两张老卡片的子项键（`gesture_line.skip_draw` 等、`navigation_geometry.height` 等）**都没变**，
     * 现在仍是同一张卡片的子项/配置行，值不需要搬。要搬的只有主开关与处理方式：
     * - 老卡片开着 → 打开新卡片，并把处理方式设成对应的那一种（隐藏 / 几何）。
     * - 老卡片关着或没出现过 → 什么都不写：用户可能正在外观方式里用着新卡片，
     *   写 false 会把他的外观设置一起关掉。
     *
     * 两个老卡片都为真（合并前本来就会互相覆盖）时，**按几何优先**：
     * 几何是测量链的改动，用户多半是专门为它开的；隐藏可以随时在选择项里手动换。
     *
     * @param legacyHide `gesture_line` 主开关；键不存在传 null
     * @param legacyGeometry `navigation_geometry` 主开关；键不存在传 null
     * @param mode 当前已存的「处理方式」；没存过传 null
     * @return 需要写入的键；无事可做时返回 null
     */
    fun navigationMergePlan(legacyHide: Boolean?, legacyGeometry: Boolean?, mode: String?): Plan? {
        if (legacyHide == null && legacyGeometry == null) return null
        val enabled = legacyHide == true || legacyGeometry == true
        if (!enabled) return Plan(emptyMap(), emptyMap())
        val configs = if (mode == null) {
            val selected = if (legacyGeometry == true) NavigationHandleSettings.MODE_GEOMETRY
            else NavigationHandleSettings.MODE_HIDE
            mapOf(NavigationHandleSettings.MODE to selected)
        } else emptyMap()
        return Plan(mapOf(NavigationHandleSettings.FEATURE to true), configs)
    }

    /**
     * v4→v5：「隐藏底部手势横条」独立卡片 → `navigation_handle_custom` 的「改系统原生隐藏偏好」档。
     *
     * 两条规则：
     * - 老卡片为真 → 打开新卡片；仅在用户没显式选过处理方式时设为「原生隐藏偏好」。
     *   若用户已经选过别的处理方式（例如他自己在合并后选过「自定义外观」），
     *   尊重用户当前的选择，不把他的方式改掉 —— 效果上就是「关掉老卡片的那次开启」。
     * - 老卡片为假或没出现过 → 什么都不写：用户可能正在新卡片里用别的处理方式，
     *   写 false 会把它一并关掉。
     *
     * 老卡片的 `restore_on_disable` 子项**不搬迁**：它当年就是死代码（见
     * [NavigationHandleSettings.LEGACY_NATIVE_RESTORE]），合并后用「按快照恢复」取代，
     * 没有对应的新键可写。它的存在只用于判断「老卡片是否出现过」——出现过的键都要删掉。
     *
     * @param legacyNative 老卡片主开关；键不存在传 null
     * @param restore 老卡片的「关闭时复位」子项；只参与「是否出现过」的判断，键不存在传 null
     * @param mode 当前已存的处理方式；没存过传 null
     */
    fun nativeHideMergePlan(legacyNative: Boolean?, restore: Boolean?, mode: String?): Plan? {
        if (legacyNative == null && restore == null) return null
        val switches = LinkedHashMap<String, Boolean>(2)
        val configs = LinkedHashMap<String, String>(1)
        if (legacyNative == true) {
            switches[NavigationHandleSettings.FEATURE] = true
            if (mode == null) configs[NavigationHandleSettings.MODE] = NavigationHandleSettings.MODE_NATIVE
        }
        return Plan(switches, configs)
    }

    /**
     * v3 的独立卡片 `notification_channel_settings` → v4 的 `notification_importance`。
     *
     * 合并前两张卡片共用同一个菜单拦截器，且只装其中一个（见 `ControlCenterHooks`）：
     * 只要重要程度开着，菜单跳转就会被改。合并后菜单目标默认回到原生，
     * 所以老用户如果专门开过渠道跳转卡片，需要把它显式搬成 `menu_target=2`。
     *
     * 重要程度卡片本身不受影响（它不是新加的东西）。
     *
     * @param legacyChannel 老卡片主开关；键不存在传 null
     * @param existingTarget 已存的菜单目标；没存过传 null
     * @return 需要写入的键；无事可做时返回 null
     */
    fun notificationMenuPlan(legacyChannel: Boolean?, existingTarget: String?): Plan? {
        if (legacyChannel == null) return null
        if (legacyChannel != true) return Plan(emptyMap(), emptyMap())
        val configs = if (existingTarget == null) {
            mapOf(NotificationImportanceSettings.MENU_TARGET to NotificationImportanceSettings.TARGET_CHANNEL)
        } else emptyMap()
        return Plan(emptyMap(), configs)
    }

    /**
     * v5→v6：独立卡片「锁屏左侧入口替换为手电筒」→ 合并后的 [LockscreenShortcutSettings] 处理方式。
     *
     * 合并后左侧按钮是一张三选一的卡片，主键仍是老「隐藏左侧入口」卡片的键
     * （`lockscreen_hide_left_shortcut`，见 [LockscreenShortcutSettings.FEATURE]），
     * 所以**只有手电筒那张卡的顶层键需要处理**：把它读成 `mode=2`。
     *
     * 规则按「老用户当时实际看到的画面」还原，而不是按两个键的各自取值：
     * - 合并前隐藏优先（两张卡都对同一个视图动手，代码里只能二选一，隐藏赢），
     *   所以**两张卡同时为真时搬成「隐藏」**，否则老用户会从「按钮不见了」突然变成手电筒。
     * - 只开手电筒（隐藏关着）→ 搬成「手电筒」，这正是他当时看到的。
     * - 只开隐藏 → 老卡片键已经是新卡片主键，且 `mode` 为空即默认「保持原生」——
     *   但那不是用户的选择：他开的是隐藏。所以显式写 `mode=1`。
     * - 手电筒卡为假或没出现过 → 不写任何主开关；若隐藏卡也没开过，什么都不做。
     *
     * 已有的显式 `mode` 一律不覆盖：用户合并后自己选过的值优先于任何搬迁推断。
     *
     * @param legacyHide 老「隐藏左侧入口」卡片主开关；键不存在传 null
     * @param legacyFlashlight 老「手电筒」卡片主开关；键不存在传 null
     * @param mode 当前已存的合并卡片处理方式；没存过传 null
     * @return 需要写入的键；无事可做时返回 null
     */
    fun leftButtonMergePlan(legacyHide: Boolean?, legacyFlashlight: Boolean?, mode: String?): Plan? {
        if (legacyHide == null && legacyFlashlight == null) return null
        // 用户合并后已经显式选过 → 只决定要不要把卡片打开，不动他的选择。
        val enabled = legacyHide == true || legacyFlashlight == true
        if (!enabled) return Plan(emptyMap(), emptyMap())
        val switches = mapOf(LockscreenShortcutSettings.FEATURE to true)
        if (mode != null) return Plan(switches, emptyMap())
        // 合并前隐藏优先：两张都开着时用户当时看到的是隐藏。
        val selected = if (legacyHide == true) LockscreenShortcutSettings.MODE_HIDE
        else LockscreenShortcutSettings.MODE_FLASHLIGHT
        return Plan(switches, mapOf(LockscreenShortcutSettings.MODE to selected))
    }

    /**
     * v7→v8：纯开关「隐藏控制中心运营商名称」→ [CarrierTextSettings] 的四选一。
     *
     * 卡片主键**没变**（仍是 `control_center_hide_carrier`），所以要搬的只有 `mode`：
     * 老用户当时唯一能做的事就是隐藏名称，那正是合并后的 `MODE_HIDE`。
     * 不写这一笔的话，升级后 `mode` 读出来是缺省的「保持原生」——
     * 他正在用的隐藏会静默失效，而开关明明还开着（最难查的那种 bug）。
     *
     * - 开关为真且没显式选过处理方式 → 写 `mode=2`。
     * - 开关为真但已经选过（升级后又进过界面）→ 只保留卡片，不动他的选择。
     * - 开关为假或从没出现过 → 一个字节都不写。「只加不减」的老规矩：
     *   写 `mode=0` 对一个关着的功能毫无意义，却会把它标成「用户显式选过保持原生」。
     *
     * @param hide 老布尔开关的值；键不存在传 null
     * @param mode 当前已存的处理方式；没存过传 null
     * @return 需要写入的键；无事可做时返回 null
     */
    fun carrierTextModePlan(hide: Boolean?, mode: String?): Plan? {
        if (hide != true) return null
        if (mode != null) return Plan(emptyMap(), emptyMap())
        return Plan(emptyMap(), mapOf(CarrierTextSettings.MODE to CarrierTextSettings.MODE_HIDE))
    }
}
