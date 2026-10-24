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
     * 按比例放宽裁剪框的最小边长，并保证两件事：**只会变松**、**绝不归零**。
     *
     * 为什么不照抄上游的 `returnConstant(0)`：保存路径 `ScreenCropEntry#apply` 里是
     * `Bitmap.createBitmap((int)(mCropArea.width() + 0.5f), (int)(mCropArea.height() + 0.5f), …)`，
     * **没有任何宽高非空校验**；而 `mCropArea` 能拖到多小正是由这个最小值钳位的
     * （`onFreeResize` 里 4 处 `constraint()` 都拿它当硬下限）。下限归零 = 钳位消失 =
     * 能拖出亚像素裁剪框 = 点保存时 `IllegalArgumentException: width must be > 0`。
     *
     * 非零性分两层，说清楚各层管到哪里：
     *
     * - **当前宿主**由比例保证：宿主值 ≥ 16·scale，故 `值/8` 折算回图像侧仍 ≥ 2 个
     *   图像像素，取整必然非零。这一层是**可证明**的。
     * - `coerceAtLeast(1)` 只保证「下限不被我们彻底抹掉」，它管的是另一类缺陷：
     *   宿主公式若换成返回一个很小的值（返回 4、分母 8 → `4/8 == 0`），整除本身就
     *   把下限变成了 0，等于我们亲手复现上游那个崩溃。钳到 1 之后
     *   `original >= 1` 时结果必然落在 `1..original` —— 只放宽、不收紧、不清零。
     *   ⚠️ 但**不要把它当成非零保存的保证**：1 个显示像素在放大状态下折算回图像侧
     *   依然可能取整为 0。那一路只能靠比例成立，不能靠这个 1。
     *
     * 非正的宿主值（0 或负，语义是宿主自己已不设限）原样回传：不替它编一个限制，
     * 也不让它变成我们引入的行为。
     */
    fun relaxMinCropSize(original: Int, divisor: Int): Int {
        if (divisor <= 1 || original <= 0) return original
        return (original / divisor).coerceAtLeast(1)
    }
}
