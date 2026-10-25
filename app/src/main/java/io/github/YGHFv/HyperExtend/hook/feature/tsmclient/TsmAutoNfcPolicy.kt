/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.tsmclient

import java.util.WeakHashMap

/** 隔离 NFC 系统调用，便于验证“原本关闭才恢复关闭”的状态机。 */
internal interface TsmAutoNfcControl {
    fun isEnabled(): Boolean
    fun enable(): Boolean
    fun disable(): Boolean
}

/**
 * 记录由刷卡页临时开启的 NFC。
 *
 * 只有“进入前确实关闭、开启后复核确实已开”的页面才会被记录；原本已开启的 NFC 在退出时
 * 绝不能关闭。键使用弱引用，页面实例被回收后不会因为状态表而泄漏。
 */
internal class TsmAutoNfcPolicy {
    private val openedByFeature = WeakHashMap<Any, TsmAutoNfcControl>()

    @Synchronized
    fun onOpened(owner: Any, control: TsmAutoNfcControl): Boolean {
        if (openedByFeature.containsKey(owner) || control.isEnabled()) return false
        control.enable()
        if (!control.isEnabled()) return false
        openedByFeature[owner] = control
        return true
    }

    @Synchronized
    fun onClosed(owner: Any): Boolean {
        val control = openedByFeature.remove(owner) ?: return false
        if (!control.isEnabled()) return false
        return control.disable()
    }
}
