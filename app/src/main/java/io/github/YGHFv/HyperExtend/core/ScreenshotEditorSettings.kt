/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

object ScreenshotEditorSettings {
    const val DEVICE_FEATURE = "screenshot_device_customize"
    const val DEVICE = "$DEVICE_FEATURE.device"
    const val PRIVACY_FEATURE = "screenshot_unlock_privacy_marking"
    const val CROP_FEATURE = "screenshot_unlock_minimum_crop_limit"

    private val DEVICE_PATTERN = Regex("[A-Za-z0-9._-]{1,64}")

    /** 外壳资源键只允许宿主现有资源表能表示的标识符，空白表示保持原生。 */
    fun device(raw: String): String? = raw.trim().takeIf(DEVICE_PATTERN::matches)

    /**
     * 解锁后的裁剪框最小边长，单位是**显示坐标系**（宿主指令链
     * `getImageMatrix().mapRadius(calculateMinPixels())` 之后的那个坐标系），
     * 宿主的原生下限是同一坐标系里的常量 100。
     *
     * 刻意**不归零**。宿主的保存路径 `ScreenCropEntry#apply` 直接拿裁剪框宽高去
     * `Bitmap.createBitmap((int)(w + 0.5f), (int)(h + 0.5f), ...)`，中间没有任何非空校验。
     * 归零之后这个下限不再钳位（`constraint()` 会允许拖到任意小），亚像素宽度取整为 0
     * 就崩在保存动作上 —— 上游 HyperCeiler / Cemiuiler 的 `returnConstant(0)` 正是这条
     * 路径上的崩溃窗口，本模块不照抄。
     *
     * 换算链（决定「多大才算安全」）：`apply` 在取整之前先用
     * `setRectToRect(mBitmapArea, RectF(0, 0, bitmap.width, bitmap.height), FILL)`
     * 把 `mCropArea` 从显示坐标映回**图像像素**，所以真正进入 `createBitmap` 的是
     * 「显示尺寸 × 图像/显示之比」。图大于屏时这个比值 > 1（更安全），图小于屏被放大显示时
     * < 1，最窄处可以远小于显示值。因此下限只要 ≥ 1 个显示像素就不可能取整为 0，
     * 留到 16 是为了给放大显示的场景留出余量，同时肉眼仍然认为那是一个可拖的框。
     *
     * ⚠️ 数值选取的理由要说清楚：16 这个数**不是**宿主常量
     * `CROP_AREA_MIN_PIXELS = 16.0f` 的直接复用 —— 那个常量在**图像坐标系**
     * （`calculateMinPixels` 的返回值，还要经 `mapRadius` 映射），与本方法的返回坐标系
     * 不同，量级不可比，只是因为宿主自己用了这个数才显得不突兀。
     * 真正的硬约束只有「≥ 1 显示像素」加上「必须显著小于宿主的 100，否则不算解锁」。
     */
    const val CROP_MIN_SIZE_PX = 16

    val config = listOf(
        HyperText(
            DEVICE,
            "设备代号",
            summary = "填写截图外壳资源使用的设备标识；留空跟随系统。仅改变截图编辑器查询，不改 Build.DEVICE。",
            placeholder = "例如 shell_code",
        ),
    )
}
