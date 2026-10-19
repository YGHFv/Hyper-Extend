/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object NavigationHandlePolicy {
    /**
     * 外观方式只认精确版本与主线程。
     *
     * 这里**不再有一个 `hidden` 参数**：隐藏方式已并入同一张卡片的「处理方式」选择项，
     * 由安装阶段决定装外观还是装隐藏（见 [NavigationHandleHooks.install]），
     * 不需要在每个回调里再做一次优先级判断。
     */
    fun supported(mainThread: Boolean): Boolean = mainThread

    /** 半径 0 表示「不覆盖」：0 以前被用来隐藏绘制，那条路径已并入隐藏方式，不再重复实现。 */
    fun radiusPixels(dp: Float, density: Float): Float? =
        if (dp.isFinite() && dp in 0f..5f && density.isFinite() && density > 0f)
            (dp * density).takeIf { it.isFinite() } else null

    data class Dimensions(val width: Float?, val bottom: Float?)

    fun dimensions(widthDp: Float?, bottomDp: Float?, density: Float, viewWidth: Int, viewHeight: Int,
        radius: Float, pulse: Float, extraWidth: Float, extraHeight: Float,
        widthScale: Float, heightScale: Float): Dimensions? {
        if (widthDp == null && bottomDp == null) return Dimensions(null, null)
        if (viewWidth <= 0 || viewHeight <= 0 ||
            listOf(density, radius, pulse, extraWidth, extraHeight, widthScale, heightScale).any { !it.isFinite() } ||
            density <= 0 || radius <= 0 || pulse !in 0f..1f || extraWidth < 0 || extraHeight < 0 ||
            widthScale <= 0 || heightScale <= 0) return null
        if (widthDp != null && (!widthDp.isFinite() || widthDp !in 1f..400f) ||
            bottomDp != null && (!bottomDp.isFinite() || bottomDp !in 0f..40f)) return null
        val maxWidth = viewWidth / widthScale - extraWidth * pulse
        val halfHeight = (2 * radius + extraHeight * pulse) * heightScale / 2
        // The host keeps the pulse/press center at height - bottom - radius.
        val minBottom = maxOf(0f, halfHeight - radius)
        val maxBottom = viewHeight - radius - halfHeight
        if (!maxWidth.isFinite() || maxWidth <= 0 || !halfHeight.isFinite() ||
            maxBottom < minBottom) return null
        val width = widthDp?.let { (it * density).takeIf(Float::isFinite)?.coerceAtMost(maxWidth) ?: return null }
        val bottom = bottomDp?.let { (it * density).takeIf(Float::isFinite)?.coerceIn(minBottom, maxBottom) ?: return null }
        return Dimensions(width, bottom)
    }
}

/** Restore even when preparation or the original method fails; never replay the host call. */
internal inline fun <T> withNavigationHandleOverride(prepare: () -> Unit, restore: () -> Unit, block: () -> T): T =
    try { prepare(); block() } finally { restore() }
