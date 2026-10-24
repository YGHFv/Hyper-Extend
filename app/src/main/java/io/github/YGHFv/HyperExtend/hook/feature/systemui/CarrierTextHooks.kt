/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.view.View
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.core.CarrierTextSettings
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect

/**
 * 控制中心运营商名称的四选一（见 `core/CarrierTextSettings` 的分层约定）。
 *
 * ## 只在安装时读一次 mode
 *
 * 与目录里其它功能相同：设置读一次、按档决定挂哪几处。运行期不再读设置，因为
 * 三档挂的是**不同的方法**，中途换档等于要求已经挂上的 hook 自己失效，
 * 而那会让「关掉的档仍在改写文本」这类状态泄漏进宿主。换档需要重启系统界面。
 *
 * ## 任一档的靶点缺失就整档不装
 *
 * 靶点解析不到或 `HookRuntime.hook` 返回 false 时本函数返回 0，
 * `installSystemUiFeature` 会记一条 `no compatible hooks installed`。
 * 宁可功能不生效，也不要「挂了一半」：`1` 档只挂上文本改写、没挂上分隔符隐藏，
 * 用户看到的是「名字并上了但中间还剩一条线」，比完全不生效更难解释。
 */
internal object CarrierTextHooks {
    /** 老「隐藏名称」卡的靶点，仍然是 `2` 档唯一的实现。 */
    internal val headerTarget = SystemUiMethod(CarrierTextTargets.HEADER, "updateCarrierAndPrivacyVisible", "void", listOf(
        "com.android.systemui.plugins.miui.statusbar.MiuiPrivacyController\$MiuiPromptInfo",
        "com.android.systemui.plugins.miui.statusbar.MiuiPrivacyController\$AndroidPromptInfo"))

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        val mode = CarrierTextSettings.modeOf(settings.string(CarrierTextSettings.MODE))
        // 分层由目录说了算，不在这里再抄一遍「哪个档动哪一层」：
        // 抄的那份加一档一定会漏一处，表现是「选了那一档却毫无效果」。
        if (CarrierTextSettings.hidesCarrierBlock(mode)) return hideBlock(loader)
        if (!CarrierTextSettings.rewritesText(mode)) {
            // 卡片开着但处理方式停在「保持原生」：这是用户能真实构造出来的状态（拨了总开关没选档）。
            // 单独记一行，否则日志里只剩一条笼统的 `no compatible hooks installed`，
            // 看的人无法区分「档没选」与「宿主靶点没了」——后者才是需要报上去的故障。
            ModuleLog.warn("control_center_hide_carrier: mode is native, nothing to install")
            return 0
        }
        return rewriteText(loader, settings, mode)
    }

    /** `2`：只收起名称区域，保留隐私提示与占位。完全不碰文本。 */
    private fun hideBlock(loader: ClassLoader): Int {
        val type = Reflect.loadClass(loader, CarrierTextTargets.HEADER) ?: return 0
        val target = type.declaredMethods.singleOrNull(headerTarget::matches) ?: return 0
        val field = Reflect.findField(type, "carrierLayout") ?: return 0
        return if (HookRuntime.hookAfter(target, "control_center_hide_carrier/update") { chain, original ->
                // Keep all privacy prompts and their visibility/spacing calculations intact.
                (field.get(chain.thisObject) as? View)?.let { if (it.visibility == View.VISIBLE) it.visibility = View.INVISIBLE }
                original
            }) 1 else 0
    }

    /**
     * `1` 与 `3`：改写发给控制中心文本监听器的那份文本；`1` 额外收起分隔符视图。
     *
     * `1` 的两处（文本 + 分隔条）必须都挂上才算生效：双卡那条线是独立的
     * `carrierSeparatorView`（ImageView）/ `carrierSeparatorText`（TextView），
     * 不由文本内容决定，只改文本会把它原样留在那儿。
     */
    private fun rewriteText(loader: ClassLoader, settings: HookSettings, mode: String): Int {
        val view = Reflect.loadClass(loader, CarrierTextTargets.CARRIER_VIEW) ?: return 0
        // 回调是 Kotlin 编译出来的匿名内部类，只能在拿到外部类之后按运行时类名解析：
        // 外部类的 Class 对象是确定的，用它自己的 classloader 去加载嵌套类才不会撞进模块加载器。
        val listener = Reflect.loadClass(view.classLoader, CarrierTextTargets.listener.owner) ?: return 0
        val target = listener.declaredMethods.singleOrNull(CarrierTextTargets.listener::matches) ?: return 0
        // 设备名只在需要它的档位解析一次：属性读取是跨进程 binder 无关的本地调用，
        // 但每次分发都读一遍仍然是白给，而进程内这个值不会变。
        val name = if (mode == CarrierTextSettings.MODE_DEVICE_NAME) deviceName(loader) else null
        val textRewritten = HookRuntime.hook(target, "control_center_hide_carrier/text") { chain ->
            if (!settings.isOn(CarrierTextSettings.FEATURE)) return@hook chain.proceed()
            val args = chain.args
            val count = args.getOrNull(0) as? Int
            val slot = args.getOrNull(2) as? Int
            val replacement = CarrierTextPolicy.transform(
                mode, count, slot, args.getOrNull(1) as? String, name,
            )
            // 两个 int 一律原样回传：数量与卡槽是宿主的判定依据，改任何一个都会让它重算可见性。
            if (replacement == null || replacement == args.getOrNull(1)) chain.proceed()
            else chain.proceed(arrayOf<Any?>(count, replacement, slot))
        }
        // 只有「去掉分隔符」这一档还需要动分隔条；设备名档留着它反而是对的
        // （两格各显示一次，中间那条线正是原生布局预期的样子）。
        if (!CarrierTextSettings.hidesSeparator(mode)) return if (textRewritten) 1 else 0
        if (!textRewritten) return 0
        val layout = Reflect.loadClass(loader, CarrierTextTargets.LAYOUT) ?: return 0
        val measure = layout.declaredMethods.singleOrNull(CarrierTextTargets.measure::matches) ?: return 0
        val separators = listOf(
            Reflect.findField(layout, CarrierTextTargets.SEPARATOR_VIEW),
            Reflect.findField(layout, CarrierTextTargets.SEPARATOR_TEXT),
        ).filterNotNull()
        if (separators.isEmpty()) return 0
        return if (HookRuntime.hookAfter(measure, "control_center_hide_carrier/measure") { chain, original ->
                // 先让宿主把宽度与可见性全部算完，只收回它刚刚置为 VISIBLE 的那条线：
                // INVISIBLE 保留它占的位，GONE 会让原生布局重排（那是改布局，不是改分隔符）。
                if (settings.isOn(CarrierTextSettings.FEATURE)) {
                    for (field in separators) {
                        (Reflect.attempt { field.get(chain.thisObject) } as? View)
                            ?.let { if (it.visibility == View.VISIBLE) it.visibility = View.INVISIBLE }
                    }
                }
                original
            }) 2 else 0
    }

    /**
     * 设备名：照宿主自己的读法读，读不到就**不伪造**。
     *
     * 宿主侧 `android.provider.SystemSettings$System.getDeviceName(Context)` 的实现是
     * `SystemProperties.get("persist.private.device_name", marketNameOrDefault)` ——
     * 它要一个 Context 才能取到「默认名」那一步，而这里拿不到（也**不该**去调它：
     * `SystemSettings` 是 hidden API，跨版本不稳）。所以直读同一条属性链：
     * 用户改过的设备名 → 产品市场名。两个都空则返回 null，让宿主的原生文本原样流过去。
     */
    private fun deviceName(loader: ClassLoader): String? = Reflect.attempt {
        val clazz = Class.forName("android.os.SystemProperties", false, loader)
        val getter = clazz.getDeclaredMethod("get", String::class.java, String::class.java)
        val custom = getter.invoke(null, CarrierTextTargets.DEVICE_NAME_PROPERTY, "") as? String
        if (!custom.isNullOrBlank()) custom
        else getter.invoke(null, CarrierTextTargets.MARKET_NAME_PROPERTY, "") as? String
    }?.trim()?.takeIf { it.isNotEmpty() }
}
