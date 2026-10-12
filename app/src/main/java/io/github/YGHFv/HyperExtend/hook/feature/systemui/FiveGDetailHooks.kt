/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.app.KeyguardManager
import android.content.Context
import android.os.UserManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.view.View
import android.view.ViewGroup
import io.github.YGHFv.HyperExtend.core.FiveGTileSettings
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.core.compatibleVersionCode
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime
import io.github.YGHFv.HyperExtend.hook.UnsafeAccess
import java.lang.reflect.Array as ReflectArray
import java.util.Collections

/**
 * 「5G 开关样式」的第三个档：**内置移动网络磁贴**（上游 mode 3）。
 *
 * 这一档**不新增磁贴**，而是往宿主自己的移动网络磁贴二级详情页里插入一项
 * 「使用 5G 网络」。上游正是这么做的：hook
 * `MiuiCellularTile$CellularDetailAdapter` 的点击与条目组装两处，
 * 再加上 `QSDetailContent.setItems`，把 `TextDividerItem` + `ToggleItem` 一起插进去。
 *
 * ## 与上游的三处差异（都来自本机 dex 取证，见 docs/five-g-style-evidence.json）
 *
 * 1. **`ToggleItem` 没有构造器**。上游反射 `getConstructor(...)`，本机这个类
 *    （dex outline 与 smali 双重确认）只声明了 `equals/getTag/getType/hashCode/
 *    isForceSingle/toString` 六个方法，**没有任何 `<init>`** —— 宿主自己也是
 *    `new-instance` → `Object.<init>` → 逐个 `iput` 填充。所以这里改用
 *    `UnsafeAccess.allocateInstance`，再显式写 `title/summary/isChecked`。
 * 2. **并入宿主自己的「其他设置」分组**。上游插的是 `TextDividerItem("移动网络")` 给整页加高度；
 *    本机原生详情页本来就有 `TextDividerItem("其他设置")`（资源
 *    `quick_settings_other_settings`）打头、紧跟着「智能双卡」这个 `ToggleItem`。
 *    本模块把 5G 项插在**这条原生分组头正后面**，让它和「智能双卡」同处「其他设置」分组，
 *    而不是被并进上面那张 SIM 卡（原因见 [spliceItem] 注释）。
 * 3. **点击按对象身份判定，不按标题字符串**。上游拿模块资源做本地化标题比对，
 *    语言一变就失配；这里插入时登记对象、点击时按 `===` 命中，
 *    只有我们插进去的那一项会触发网络开关。
 *
 * 开关本身仍走 `IMiuiTelephony`，与独立 5G 磁贴同一条路径：只切当前默认数据卡的
 * `fiveg_user_enable`，不写别的全局键、不动双卡策略。
 */
internal object FiveGDetailHooks {
    @Volatile var ready = false
        private set

    /** 条目文案；宿主资源里没有对应项（上游是 `dashboard_5g`），由模块提供。 */
    const val LABEL = "5G网络"

    /** 点击间隔下限，避免详情页把一次点按派发两次。 */
    private const val CLICK_GUARD_MILLIS = 350L

    /**
     * 每个 `QSDetailContent` 只保留**一个**注入实例：`content -> ToggleItem`。
     *
     * ## 为什么必须复用实例（开启动画异常的根因之一）
     *
     * 宿主一次开页会走到 `setItems` **2–3 次**（dex 取证）：
     * `MiuiCellularTile.handleSecondaryClick` 先 `showDetail(true)` 再 `updateItems()`；
     * 随后 `MiuiQSDetail$2$$ExternalSyntheticLambda1.run()` 调
     * `CellularDetailAdapter.createDetailView`，而它又在 `mShowingDetail == true` 时再调一次
     * `updateItems()`；`CellSignalCallback.setAllSubs/setDefaultSim` 在详情页显示期间同样各调一次
     * （`CellularDetailAdapter.txt:54-102`、`CellSignalCallback.txt:16-153`）。
     *
     * 每次 `setItems` 都会走 `handleSetItems` → `updateGroupInfo()` + `updateVisible()` +
     * `notifyDataSetChanged()`（`QSDetailContent.txt:433-466`、`703`）。
     * 如果这里每次都用 `UnsafeAccess.allocateInstance` 新建 `ToggleItem`，
     * 这 2–3 次就是 2–3 个不同对象，`RecyclerView` 每次都要按“换了条目”全量重绑；
     * 而整页此时正被 `QSDetailClipper.animateCircularClip` 做圆形揭示
     * （`MiuiQSDetail_2_lambda1.txt:507`、`QSDetailClipper.txt:67`），
     * 两者交错就表现为“先整块显示 → 内容消失 → 再从右上角展开”。
     *
     * 复用同一实例后，多次 `setItems` 对 `adapter` 而言是同一行，重绑不再产生新对象。
     * 用 `WeakHashMap` 是因为详情页会随 `metricsCategory` 长期缓存在
     * `MiuiQSDetail.mDetailViews`（`MiuiQSDetail_2_lambda1.txt:239-262`），
     * 键一弱引用，宿主丢弃详情页时这里也会自动释放。
     */
    private val injectedToggles = Collections.synchronizedMap(java.util.WeakHashMap<Any, Any>())

    @Volatile private var lastClick = 0L

    /**
     * 按**对象身份**判定「这一项是我们插进去的那一个」。
     *
     * 不用 `equals` 是因为宿主 `ToggleItem` 自己实现了 `equals/hashCode`
     * （`FiveGDetailTargets` 取证里那份成员清单包含它们），
     * 两个内容相同的 `ToggleItem` 会互相相等 —— 那会让点击判定误伤原生条目。
     */
    internal fun isInjected(item: Any?): Boolean {
        if (item == null) return false
        synchronized(injectedToggles) { return identityContains(injectedToggles.values, item) }
    }

    internal fun clearInjected() = injectedToggles.clear()

    /**
     * 按**对象身份**（`===`，不是 `equals`）判断 [item] 是否在 [values] 里。
     *
     * 单独抽成纯函数有两个理由：一是点击判定与间距判定共用同一条语义；
     * 二是它可以被单测直接钉住 —— 宿主 `ToggleItem` 自己实现了 `equals/hashCode`
     * （见 [FiveGDetailTargets] 的成员清单），若这里退化成 `equals`/`contains`，
     * 一个内容相同的原生条目就会被误判成我们的注入项，点击会去写 5G 而不是智能双卡。
     */
    internal fun identityContains(values: Collection<Any>, item: Any): Boolean = values.any { it === item }

    /**
     * 把 [toggle] 插进「其他设置」分组，返回新数组；找不到插入点返回 null。
     *
     * ## 插入点：紧跟原生那条 `TextDividerItem` 之后
     *
     * 宿主 `QSDetailContent$Adapter.updateGroupInfo()` 只按 `getType()` 分组
     * （`SelectableItem`=0xe9 / `ToggleItem`=0x91d / `TextDividerItem`=0x5b25），
     * 而 `ToggleItem.isForceSingle()` 恒为 `true`。宿主自己的「智能双卡」就是
     * `TextDividerItem("其他设置")` **紧跟**一个 `ToggleItem` 写在
     * `MiuiCellularTile$CellularDetailAdapter.updateItems` 末尾的。
     * 所以「跟在原生分组头后面」正是宿主认的「其他设置」分组形态。
     *
     * 宿主没有分组头时（`shouldHideIntelligentDualSimButton()` 为 true，例如单卡），
     * 退回「最后一项 `SelectableItem` 之后」，保证入口仍然出现。
     *
     * ## 间距不在这里解决
     *
     * 粘连不是插入位置造成的：宿主 `onBindViewHolder` 的 `ToggleItem` 分支
     * **从不设置 `topMargin`**，`bottomMargin` 也只在这一项是末位时才给 22dp。
     * 也就是说两个相邻 toggle 之间永远是 0 间距。这段间距由 [install] 里那条
     * `onBindViewHolder` 收尾 hook 单独补（见该处注释）。
     *
     * 纯函数，单测直接覆盖。找不到插入点时返回 null，由调用方保持原数组 ——
     * 「宿主改了详情页结构」的表现是「少一个入口」，而不是抛异常或把原生条目挤掉。
     */
    fun spliceItem(
        items: Array<Any?>,
        itemClass: Class<*>,
        toggle: Any,
        isSelectable: (Any) -> Boolean,
        isDivider: (Any) -> Boolean,
    ): Array<Any?>? {
        val afterDivider = items.indices.lastOrNull { i -> items[i]?.let(isDivider) == true }?.plus(1)
        val afterSelectable = items.indices.lastOrNull { i -> items[i]?.let(isSelectable) == true }?.plus(1)
        val insertIndex = afterDivider ?: afterSelectable ?: return null
        val typed = ReflectArray.newInstance(itemClass, items.size + 1) as Array<Any?>
        System.arraycopy(items, 0, typed, 0, insertIndex)
        typed[insertIndex] = toggle
        if (insertIndex < items.size) System.arraycopy(items, insertIndex, typed, insertIndex + 1, items.size - insertIndex)
        return typed
    }

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        if (!settings.isOn(StockTilesPolicy.FEATURE)) return 0
        if (!FiveGTilePolicy.usesNativeDetail(settings.string(FiveGTilePolicy.STYLE))) return 0
        val spec = runCatching {
            Specs(
                content = Class.forName(FiveGDetailTargets.CONTENT, false, loader),
                item = Class.forName(FiveGDetailTargets.ITEM, false, loader),
                selectable = Class.forName(FiveGDetailTargets.SELECTABLE, false, loader),
                toggle = Class.forName(FiveGDetailTargets.TOGGLE, false, loader),
                divider = Class.forName(FiveGDetailTargets.DIVIDER, false, loader),
                adapter = Class.forName(FiveGDetailTargets.CONTENT_ADAPTER, false, loader),
            )
        }.getOrElse {
            ModuleLog.warn("${FiveGTileSettings.STYLE}: native detail classes unavailable")
            return 0
        }
        val api = runCatching { Api(loader, spec, settings) }.getOrElse {
            ModuleLog.error("${FiveGTileSettings.STYLE}: native detail members unresolved", it)
            return 0
        }
        val setItems = NotificationHooks.resolve(loader, FiveGDetailTargets.setItems) ?: return 0
        val onClick = NotificationHooks.resolve(loader, FiveGDetailTargets.onDetailItemClick) ?: return 0
        if (!listOf(setItems, onClick).map { HookRuntime.deoptimize(it, "${FiveGTileSettings.STYLE}/detail") }.all { it }) return 0
        val insert = HookRuntime.hook(setItems, "${FiveGTileSettings.STYLE}/insert") { chain ->
            val raw = chain.args.getOrNull(0)
            if (!enabled(settings) || !api.fiveGCapable() ||
                !api.isCellularSuffix(api.suffix(chain.thisObject)) || raw == null ||
                !raw.javaClass.isArray || ReflectArray.getLength(raw) == 0) return@hook chain.proceed()
            val items = ReflectArray.newInstance(spec.item, ReflectArray.getLength(raw)) as Array<Any?>
            for (i in 0 until ReflectArray.getLength(raw)) ReflectArray.set(items, i, ReflectArray.get(raw, i))
            when (val outcome = api.splice(chain.thisObject, items)) {
                SpliceResult.Skipped -> chain.proceed()
                is SpliceResult.Injected -> {
                    // 只在**第一次真正插入**时记账。宿主一次开页会来 2–3 次 setItems
                    // （见 SpliceResult / injectedToggles 的注释），后续几次只是把同一个
                    // 实例按最新状态放回去；把它们也打出来只会让日志看起来像「注入失控」。
                    if (!outcome.reused)
                        ModuleLog.info("${FiveGTileSettings.STYLE}: native 5G detail item injected")
                    chain.proceed(arrayOf<Any?>(outcome.items))
                }
            }
        }
        // 命中我们插的那一项就自己处理并拦住原生分支；其余条目一律原样放行。
        val click = HookRuntime.hook(onClick, "${FiveGTileSettings.STYLE}/click") { chain ->
            val item = chain.args.getOrNull(0)
            if (!enabled(settings) || !isInjected(item)) return@hook chain.proceed()
            // 命中即吞掉：这一项是 ToggleItem（getType() == 0x91d），
            // 宿主对 0x91d 的原生分支**不是** 5G，而是「智能双卡」——
            // 会写 Settings.Global 的 dual_mobile_data / smart_dual_sim /
            // VICE_SLOT_VOLTE_DATA_ENABLED（见 onDetailItemClick 的 0x91d 分支）。
            // 所以「想动手但前置不满足」时也**必须**吞掉，绝不能让它落到那条写路径上。
            // 返回 null 表示不再调用原方法；详情页刷新由既有 updateItems 链路负责。
            api.toggle()
            null
        }
        // 只给「我们注入的那一行」补卡片间距。见 FiveGDetailTargets.onBindViewHolder 的注释：
        // 宿主 toggle 分支从不设 topMargin，非末位 toggle 的 bottomMargin 也是 0，
        // 于是 5G 与智能双卡会贴成一片。这条 hook 是**尽力而为**：装不上只是间距回到原生值，
        // 不该让整个功能失效，所以不计入 ready。
        val onBind = NotificationHooks.resolve(loader, FiveGDetailTargets.onBindViewHolder)
        val space = HookRuntime.hookAfter(onBind, "${FiveGTileSettings.STYLE}/space") { chain, _ ->
            api.space(chain.thisObject, chain.args.getOrNull(0), (chain.args.getOrNull(1) as? Int) ?: -1)
            null
        }
        if (!space) ModuleLog.warn("${FiveGTileSettings.STYLE}: detail spacing hook unavailable; rows keep native margins")
        ready = insert && click
        return if (ready) 2 else 0
    }

    /**
     * 每个 hook 调用都重读设置：功能刚被关掉时 [ready] 仍是 true（安装期定的），
     * 用它做短路会让「关掉功能」在下一次重启系统界面之前不生效。安全模式同理。
     */
    private fun enabled(settings: HookSettings): Boolean =
        settings.isOn(StockTilesPolicy.FEATURE) && !SafeModeRuntime.blocked

    private class Specs(
        val content: Class<*>, val item: Class<*>, val selectable: Class<*>,
        val toggle: Class<*>, val divider: Class<*>, val adapter: Class<*>,
    )

    /**
     * 一次 `setItems` 的注入结果。**区分「插入了新实例」和「复用了已有实例」**
     * 是让开启动画保持稳的关键：只有前者才应该按「换了条目」处理。
     */
    private sealed interface SpliceResult {
        /** 不满足前置条件，调用方原样放行宿主数组。 */
        data object Skipped : SpliceResult
        /** 已经拼好的数组；`reused == true` 表示这次只是把上次那个条目按新状态放回去。 */
        data class Injected(val items: Array<Any?>, val reused: Boolean) : SpliceResult
    }

    /** 反射面。每个成员都逐条核过名字与类型，证据见 docs/five-g-style-evidence.json。 */
    private class Api(loader: ClassLoader, private val spec: Specs, private val settings: HookSettings) {
        private val suffixField = spec.content.getDeclaredField("suffix").apply {
            require(type == String::class.java && !java.lang.reflect.Modifier.isStatic(modifiers)); isAccessible = true
        }
        /** `Adapter.this$0` → `QSDetailContent`，用来读 `items` 数组。 */
        private val adapterOuter = spec.adapter.getDeclaredField("this\$0").apply {
            require(!java.lang.reflect.Modifier.isStatic(modifiers)); isAccessible = true
        }
        /** `QSDetailContent.items`：按 position 取条目，用于确认这一行是不是我们插进去的。 */
        private val contentItems = spec.content.getDeclaredField(FiveGDetailTargets.ITEMS_FIELD).apply {
            require(type.isArray && !java.lang.reflect.Modifier.isStatic(modifiers)); isAccessible = true
        }
        private val toggleTitle = spec.toggle.getDeclaredField("title").apply {
            require(type == CharSequence::class.java && !java.lang.reflect.Modifier.isStatic(modifiers)); isAccessible = true
        }
        private val toggleSummary = spec.toggle.getDeclaredField("summary").apply {
            require(type == CharSequence::class.java && !java.lang.reflect.Modifier.isStatic(modifiers)); isAccessible = true
        }
        private val toggleChecked = spec.toggle.getDeclaredField("isChecked").apply {
            require(type == Boolean::class.javaPrimitiveType && !java.lang.reflect.Modifier.isStatic(modifiers)); isAccessible = true
        }
        private val managerGet = NotificationHooks.resolve(loader, FiveGDetailTargets.managerGet)
        private val binderGet = NotificationHooks.resolve(loader, FiveGDetailTargets.binderGet)
        private val capable = NotificationHooks.resolve(loader, FiveGDetailTargets.capable)
        private val isEnabled = NotificationHooks.resolve(loader, FiveGDetailTargets.enabled)
        private val setEnabled = NotificationHooks.resolve(loader, FiveGDetailTargets.setEnabled)
        private val currentApplication = NotificationHooks.resolve(loader, FiveGDetailTargets.currentApplication)

        fun suffix(target: Any?): String? = runCatching { target?.let { suffixField.get(it) as? String } }.getOrNull()

        fun isCellularSuffix(value: String?): Boolean = value == "Cellular"

        /** 详情页组装与点击都发生在宿主工作线程；这里只做「哪台设备/哪个版本」的能力复核。 */
        fun fiveGCapable(): Boolean = runCatching { capable?.invoke(binder() ?: return false) == true }
            .getOrDefault(false)

        private fun applicationReady(): Boolean =
            runCatching { (currentApplication?.invoke(null) as? Context)?.packageName == "com.android.systemui" }
                .getOrDefault(false)

        private fun binder(): Any? {
            if (!applicationReady()) return null
            return runCatching { binderGet?.invoke(managerGet?.invoke(null)) }.getOrNull()
        }

        /** 只对当前默认数据卡所在槽位动手；默认卡无效时不动手。 */
        private fun slot(context: Context): Int? {
            val active = context
            val user = active.getSystemService(UserManager::class.java) ?: return null
            if (!user.isSystemUser || user.isManagedProfile ||
                user.hasUserRestriction(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)) return null
            if (active.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false) return null
            val sub = SubscriptionManager.getDefaultDataSubscriptionId()
            val index = SubscriptionManager.getSlotIndex(sub)
            if (index !in 0..1) return null
            val sim = active.getSystemService(TelephonyManager::class.java)?.getSimState(index)
            if (sim != TelephonyManager.SIM_STATE_READY) return null
            return index
        }

        /**
         * 组装并插入 5G 开关。拿不到当前值时按关闭渲染，不猜。
         *
         * [content] 是这次 `setItems` 的接收者（`QSDetailContent`），用作
         * 「每个详情页只保留一个注入实例」的键；见 [FiveGDetailHooks.injectedToggles]。
         */
        fun splice(content: Any?, items: Array<Any?>): SpliceResult {
            val previous = content?.let { injectedToggles[it] }
            val toggle = previous ?: UnsafeAccess.allocateInstance(spec.toggle) ?: return SpliceResult.Skipped
            val on = runCatching { binder()?.let { isEnabled?.invoke(it, 0) } == true }.getOrDefault(false)
            runCatching {
                toggleTitle.set(toggle, LABEL)
                toggleSummary.set(toggle, null)
                toggleChecked.setBoolean(toggle, on)
            }.onFailure { return SpliceResult.Skipped }
            // 插到宿主原生「其他设置」分组头之后（见类注释第 2 条与 spliceItem 注释）。
            val spliced = spliceItem(items, spec.item, toggle, spec.selectable::isInstance, spec.divider::isInstance)
                ?: return SpliceResult.Skipped
            if (content != null) injectedToggles[content] = toggle
            return SpliceResult.Injected(spliced, reused = previous != null)
        }

        /**
         * 给**我们注入的那一行**补卡片间距，其余位置一律不动。
         *
         * 为什么必须在模块侧补：宿主 `onBindViewHolder` 的 `ToggleItem` 分支
         * **只设 `bottomMargin`**（且只在「这一项正好是最后一项」时给 22dp），
         * **从不设 `topMargin`**。注入的 5G 排在智能双卡前面，是非末位 toggle，
         * 于是上下间距全 0 —— 两张独立圆角卡贴成一片，就是真机看到的「粘连」。
         *
         * 间距取原生 SIM 卡之间的同一基准：`2 × qs_detail_selectable_item_margin_vertical`
         * （宿主 selectable 分支就是 `getDimensionPixelSize(...)` 再 `mul-int/2addr`）。
         * 取不到资源时按 8dp 兜底，宁可间距略偏也不与上一行贴死。
         *
         * 这是**尽力而为**的收尾：位置不对、不是我们那一项、拿不到 view 时直接返回，
         * 绝不影响宿主原本的绑定结果。
         *
         * ## 只写字段，不调 `setLayoutParams`（开启动画异常的根因之二）
         *
         * 宿主的 ToggleItem 分支本身就是**只写字段**：`Adapter.txt` 的
         * `onBindViewHolder(ItemHolder,int)` 在该分支末尾是
         * `iput v2, v5, LViewGroup$MarginLayoutParams;->bottomMargin:I`，
         * 之后直接 `return-void`，**没有任何 `setLayoutParams` / `requestLayout`**。
         * 这是安全的：`RecyclerView` 在 `layoutChunk` 里是「先 bind 再 measure」，
         * `measureChildWithMargins` 读的就是刚写好的 `layoutParams`。
         *
         * 之前这里多写了一次 `view.layoutParams = params`，语义上等于在绑定收尾
         * 再加一次 `requestLayout()`。开页时这条收尾正落在
         * `QSDetailClipper.animateCircularClip` 的揭示窗口里（`MiuiQSDetail_2_lambda1.txt:507`），
         * 额外布局与圆形揭示交错，就是「先整块显示 → 内容消失 → 再从右上角展开」的第二条来源。
         * 结果对齐宿主：只改字段，剩下的交给同一次 measure/layout。
         */
        fun space(adapter: Any?, holder: Any?, position: Int) {
            if (!enabled(settings) || position < 0 || adapter == null || holder == null) return
            // 这个 hook 挂在每次行绑定上，先用「有没有注入过」做最便宜的短路。
            if (injectedToggles.isEmpty()) return
            val item = runCatching {
                val content = adapterOuter.get(adapter) ?: return
                val array = contentItems.get(content) ?: return
                if (position >= ReflectArray.getLength(array)) return
                ReflectArray.get(array, position)
            }.getOrNull() ?: return
            if (!isInjected(item)) return
            val view = runCatching {
                itemViewField(holder.javaClass)?.get(holder) as? View
            }.getOrNull() ?: return
            val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            val gap = cardGapPixels(view) ?: return
            if (params.bottomMargin == gap) return
            params.bottomMargin = gap
        }

        @Volatile private var itemViewField: java.lang.reflect.Field? = null
        @Volatile private var gapCache: Int? = null

        private fun itemViewField(holderClass: Class<*>): java.lang.reflect.Field? =
            itemViewField ?: Reflect.findField(holderClass, FiveGDetailTargets.HOLDER_ITEM_VIEW)
                ?.also { itemViewField = it }

        /** `2 × qs_detail_selectable_item_margin_vertical`；资源缺失时按 8dp 兜底。 */
        private fun cardGapPixels(view: View): Int? = gapCache ?: runCatching {
            val resources = view.context.resources
            val id = resources.getIdentifier(
                FiveGDetailTargets.CARD_MARGIN_DIMEN, "dimen", "com.android.systemui")
            val gap = if (id != 0) resources.getDimensionPixelSize(id) * 2
            else (resources.displayMetrics.density * 8f).toInt()
            gap.coerceAtLeast(1).also { gapCache = it }
        }.getOrNull()

        /**
         * 翻转当前默认数据卡的 5G。**没有返回值**是刻意的。
         *
         * 调用方命中我们插进去的那一项后**无条件吞掉事件**，不依赖这个方法的成败：
         * 这一项是 `ToggleItem`（`getType() == 0x91d`），宿主对 0x91d 的原生分支写的是
         * 「智能双卡」那几个全局键，而不是 5G。所以「前置不满足所以不写」这件事，
         * 表现必须是「什么都不发生」，绝不是「交回宿主去写别的开关」。
         *
         * 这里仍然返回 `Any?`（固定 null）只是为了让调用点读起来是「取结果」，
         * 真正的语义由上面的吞事件边界承担。
         */
        fun toggle(): Any? = runCatching {
            val holder = detailContext() ?: return@runCatching
            val index = slot(holder) ?: return@runCatching
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastClick < CLICK_GUARD_MILLIS) return@runCatching
            val target = binder() ?: return@runCatching
            if (capable?.invoke(target) != true) return@runCatching
            lastClick = now
            setEnabled?.invoke(target, isEnabled?.invoke(target, index) != true, index)
            ModuleLog.info("${FiveGTileSettings.STYLE}: toggled 5G on slot $index")
        }.getOrNull()

        /**
         * 详情页所在 Context。这里刻意不缓存：详情页可能属于不同用户（多用户切换、工作资料），
         * 缓存一次会把「哪个用户、锁没锁」也一起冻住。
         */
        private fun detailContext(): Context? = runCatching {
            (currentApplication?.invoke(null) as? Context)?.takeIf { it.packageName == "com.android.systemui" }
        }.getOrNull()?.takeIf {
            runCatching { it.packageManager.getPackageInfo("com.android.systemui", 0).compatibleVersionCode }.getOrNull() == 202602260L
        }
    }
}
