/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

/**
 * 运营商名称功能在当前宿主（SystemUI 17.03.260226.r / SDK 37）里的精确成员。
 * 逐字签名与字段留档在 `app/src/test/resources/carrier-text-current-host-members.txt`，
 * 由 `CarrierTextPolicyTest` 断言，证据取自 SystemUI 的 classes.dex / classes3.dex 全量 dexdump。
 */
internal object CarrierTextTargets {
    const val CARRIER_VIEW = "com.android.systemui.controlcenter.shade.ControlCenterCarrierText"
    const val HEADER = "com.android.systemui.controlcenter.shade.ControlCenterHeaderController"
    const val LAYOUT = "com.android.systemui.controlcenter.shade.MiuiCarrierTextLayout"

    /**
     * 控制中心运营商文本的**唯一监听者**：`ControlCenterCarrierText` 注册的那个匿名回调，
     * `PUBLIC FINAL onCarrierTextChanged(int carrierCount, String text, int slot) V`。
     *
     * 方法体开头两道 `return-void`：`innerCarrierSlotId < 0`、以及 `slot != innerCarrierSlotId`
     * ——也就是说每个实例只处理属于自己的那张卡，其余分发直接返回。
     * 之后 `TextUtils.isEmpty(text)` 决定 `carrierTextView` 是 GONE(8) 还是 VISIBLE(0)，
     * 再 `setText(text)` + `updateContentDesc(slot)`。
     *
     * ⚠️ 刻意**不拦**生产者 `MiuiCarrierTextController#fireCarrierTextChanged(I,String,I)`：
     * 那一处的分发对象还包含状态栏与锁屏的 `CarrierTextInjector`、`KeyguardClockContainer`，
     * 而本卡片写的是「控制中心运营商名称」。在生产者动手会把这张卡的作用域悄悄扩成整机。
     */
    val listener = SystemUiMethod(
        "$CARRIER_VIEW\$mCarrierTextCallback\$1", "onCarrierTextChanged", "void",
        listOf("int", "java.lang.String", "int"),
    )

    /**
     * 双卡分隔符所在容器自己的测量入口。宿主只在「左右两个运营商都该显示」时
     * 才把分隔符置为 VISIBLE，所以去掉 `" | "` 之后还必须让分隔符不可见，
     * 否则会剩下一条孤立的竖线。用 `hookAfter`：先让宿主把宽度算完，只改可见性。
     */
    val measure = SystemUiMethod(LAYOUT, "onMeasure", "void", listOf("int", "int"))

    /** 分隔符的两个载体：控制中心/通知中心用 ImageView，锁屏头部用 TextView。 */
    const val SEPARATOR_VIEW = "carrierSeparatorView"
    const val SEPARATOR_TEXT = "carrierSeparatorText"

    /**
     * 设备名读取用的系统属性键。见 `CarrierTextHooks.deviceName` 的注释。
     *
     * 取证：`mcp/miui-framework.jar` 的 `android.provider.SystemSettings$System` 里
     * `PERSIST_SYS_DEVICE_NAME` 常量名写的是 `SYS`，**值却是 `persist.private.device_name`**；
     * 而 `getDeviceName(Context)` 的实现是
     * `SystemProperties.get("persist.private.device_name", SystemProperties.get("ro.product.marketname", 资源默认名))`。
     * 上游 `CustomCarrierText` 用的正是前一个键，`HideDelimiter` 用的 `persist.sys.device_name`
     * 在本 ROM 上根本没人写。按宿主的键走，别跟旧的那个。
     */
    const val DEVICE_NAME_PROPERTY = "persist.private.device_name"
    const val MARKET_NAME_PROPERTY = "ro.product.marketname"

    val all = listOf(listener, measure)
}
