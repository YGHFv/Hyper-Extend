/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object DeviceCenterSingleRowPolicy {
    fun eligible(enabled: Boolean, safeMode: Boolean, mainThread: Boolean) =
        enabled && !safeMode && mainThread

    fun <T> select(
        items: List<T>,
        isDevice: (T) -> Boolean,
        isDetail: (T) -> Boolean,
    ): List<T> {
        if (items.size <= 4 || !isDetail(items.last())) return items
        val devices = items.dropLast(1)
        if (!devices.all(isDevice)) return items
        // 四个设备不挤占展开格；更多设备才使用原生末尾的“更多设备”对象。
        return if (devices.size == 4) devices else devices.take(3) + items.last()
    }
}
