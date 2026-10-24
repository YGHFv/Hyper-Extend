/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.screenshot

internal object ScreenshotEditorPolicy {
    fun remapShellQuery(query: String, actualDevice: String, customDevice: String?): String {
        val custom = customDevice?.trim().orEmpty()
        if (custom.isEmpty() || actualDevice.isEmpty() || custom == actualDevice) return query
        if (query == actualDevice) return custom
        val prefix = "${actualDevice}_"
        return if (query.startsWith(prefix)) custom + query.removePrefix(actualDevice) else query
    }

    /**
     * 把裁剪框的最小边长压到 [floorPx]，但**绝不归零、也绝不抬高**。
     *
     * 为什么不照抄上游的 `returnConstant(0)`：宿主的保存路径
     * `ScreenCropEntry#apply` 里是
     * `Bitmap.createBitmap((int)(mCropArea.width() + 0.5f), (int)(mCropArea.height() + 0.5f), ...)`，
     * **没有任何宽高非空校验**；而 `mCropArea` 正是被这个最小值钳位的矩形
     * （`onFreeResize` 里 4 处 `constraint()` 都拿它当硬下限）。归零之后，把裁剪框拖到
     * 亚像素宽度、导出时 `(int)(w + 0.5f)` 取整为 0，就会走到
     * `IllegalArgumentException: width must be > 0` —— 直接崩在截图编辑器的保存动作上。
     *
     * 用 `minOf` 而不是常量替换还有第二层意思：**只会变松，不会变紧**。
     * 换宿主版本之后如果这个数本来就比下限小（甚至为 0，即宿主自己已不设限），
     * 原样回传，不替宿主编一个更大的限制。
     */
    fun relaxMinCropSize(original: Int, floorPx: Int): Int {
        if (floorPx <= 0 || original <= 0) return original
        return minOf(original, floorPx)
    }
}
