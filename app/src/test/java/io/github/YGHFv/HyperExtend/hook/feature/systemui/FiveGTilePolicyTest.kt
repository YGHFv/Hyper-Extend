/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.featureById
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class FiveGTilePolicyTest {
    @Test fun candidateIsAppendedOnlyWhenItsIndependentFactoryIsReady() {
        assertFalse(StockTilesPolicy.append("wifi")!!.contains(FiveGTilePolicy.SPEC))
        val expanded = StockTilesPolicy.append("wifi,nfc", true)!!
        assertTrue(expanded.endsWith(",${FiveGTilePolicy.SPEC}"))
        assertEquals(expanded, StockTilesPolicy.append(expanded, true))
        assertEquals(1, expanded.split(',').count { it == "nfc" })
        assertFalse(FiveGTilePolicy.SPEC.startsWith("custom("))
    }
    @Test fun onlyUnlockedOwnerWithReadyDefaultDataSimMayToggle() {
        for (slot in 0..1) assertTrue(FiveGTilePolicy.canToggle(true, false, true, false, false, false, slot, true))
        assertFalse(FiveGTilePolicy.canToggle(false, false, true, false, false, false, 0, true))
        assertFalse(FiveGTilePolicy.canToggle(true, true, true, false, false, false, 0, true))
        assertFalse(FiveGTilePolicy.canToggle(true, false, false, false, false, false, 0, true))
        assertFalse(FiveGTilePolicy.canToggle(true, false, true, true, false, false, 0, true))
        assertFalse(FiveGTilePolicy.canToggle(true, false, true, false, true, false, 0, true))
        assertFalse(FiveGTilePolicy.canToggle(true, false, true, false, false, true, 0, true))
        assertFalse(FiveGTilePolicy.canToggle(true, false, true, false, false, false, 0, false))
        for (slot in listOf(-1, 2, Int.MAX_VALUE))
            assertFalse(FiveGTilePolicy.canToggle(true, false, true, false, false, false, slot, true))
    }
    @Test fun namesMatchTheSystemAndDescribeNetworkWriteBoundaries() {
        assertEquals("极暗磁贴图标替换", featureById("control_center_dim_tile_icon")!!.title)
        val requirement = featureById(StockTilesPolicy.FEATURE)!!.requirement!!
        for (boundary in listOf("默认数据卡", "不借用 NFC", "不自动改变网络开关", "管理员限制"))
            assertTrue(boundary, requirement.contains(boundary))
    }
    @Test fun delayedClickCannotFollowAnotherSubscriptionOrReverseANewerValue() {
        val selected = FiveGTilePolicy.Selection(7, 0, false)
        assertTrue(FiveGTilePolicy.sameSelection(selected, selected, 0))
        assertTrue(FiveGTilePolicy.sameSelection(selected, selected, 2000))
        for (changed in listOf(null, selected.copy(subscription = 8), selected.copy(slot = 1), selected.copy(on = true)))
            assertFalse(FiveGTilePolicy.sameSelection(selected, changed, 10))
        for (age in listOf(-1L, 2001L, Long.MAX_VALUE))
            assertFalse(FiveGTilePolicy.sameSelection(selected, selected, age))
        assertFalse(FiveGTilePolicy.sameSelection(selected.copy(subscription = -1), selected.copy(subscription = -1), 0))
    }
    @Test fun changedProxyContractFailsClosedIncludingReturnTypesAndOverloads() {
        val signatures = FiveGTileContract.signatures.toList()
        assertEquals(34, signatures.size)
        assertTrue(FiveGTileContract.accepts(signatures))
        assertFalse(FiveGTileContract.accepts(signatures.drop(1)))
        assertFalse(FiveGTileContract.accepts(signatures + "futureMethod():boolean"))
        assertFalse(FiveGTileContract.accepts(signatures.map { if (it == "isAvailable():boolean") "isAvailable():int" else it }))
        assertTrue(signatures.contains("getDetailsViewModel(java.util.function.Consumer):boolean"))
        assertTrue(signatures.contains("getDetailsViewModel():com.android.systemui.plugins.qs.TileDetailsViewModel"))
    }
    @Test fun telephonySingletonIsNotInvokedDuringHookInstallation() {
        val source = File("src/main/java/io/github/YGHFv/HyperExtend/hook/feature/systemui/FiveGTileHooks.kt").readText()
        val initializer = source.substringAfter("private val managerGet =").substringBefore('\n')
        assertTrue(initializer.contains("static = true)"))
        assertFalse(initializer.contains(".invoke("))
        val binder = source.substringAfter("fun binder(): Any? {").substringBefore("\n        }")
        assertTrue(binder.indexOf("if (!applicationReady()) return null") >= 0)
        assertTrue(binder.indexOf("if (!applicationReady()) return null") < binder.indexOf("managerGet.invoke(null)"))
    }
    @Test fun resourceOnlyUserContextCannotVetoAnInitializedProcessApplication() {
        val source = File("src/main/java/io/github/YGHFv/HyperExtend/hook/feature/systemui/FiveGTileHooks.kt").readText()
        assertFalse(source.contains("context.applicationContext"))
        val initializer = source.substringAfter("private val currentApplication =").substringBefore('\n')
        assertTrue(initializer.contains("\"android.app.ActivityThread\", \"currentApplication\", \"android.app.Application\", static = true"))
        assertFalse(initializer.contains(".invoke("))
        assertTrue(source.contains("(currentApplication.invoke(null) as? Context)?.packageName == \"com.android.systemui\""))
        assertTrue(source.indexOf("if (!api.applicationReady())") >= 0)
        assertTrue(source.indexOf("if (!api.applicationReady())") < source.indexOf("val binder = api.binder()"))
    }
    @Test fun longPressNeverFallsBackToAnUnknownOrUnexportedActivity() {
        fun allowed(pkg: String = FiveGSettingsTargets.PACKAGE, name: String = FiveGSettingsTargets.ACTIVITY,
            system: Boolean = true, app: Boolean = true, activity: Boolean = true,
            exported: Boolean = true, permission: Boolean = true) =
            FiveGTilePolicy.settingsTarget(pkg, name, system, app, activity, exported, permission)
        assertTrue(allowed())
        assertFalse(allowed(pkg = "other")); assertFalse(allowed(name = "other"))
        assertFalse(allowed(system = false)); assertFalse(allowed(app = false))
        assertFalse(allowed(activity = false)); assertFalse(allowed(exported = false))
        assertFalse(allowed(permission = false))
    }
    @Test fun longPressUsesAnExactExplicitUserLaunchAndNeverTheNetworkWriteBranch() {
        fun descriptor(type: String) = when (type) {
            "void" -> "V"; "int" -> "I"; "boolean" -> "Z"
            else -> "L${type.replace('.', '/')};"
        }
        val signatures = javaClass.getResourceAsStream("/five-g-settings-host-members.txt")!!
            .bufferedReader().use { it.readLines().toSet() }
        for (method in listOf(FiveGSettingsTargets.start, FiveGSettingsTargets.falsing)) {
            val signature = descriptor(method.owner) + "->" + method.name + "(" +
                method.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(method.returns)
            assertTrue(signature, signature in signatures)
        }
        for ((owner, name, type) in FiveGSettingsTargets.fields)
            assertTrue(descriptor(owner) + "->" + name + ":" + descriptor(type) in signatures)
        val source = File("src/main/java/io/github/YGHFv/HyperExtend/hook/feature/systemui/FiveGTileHooks.kt").readText()
        assertTrue(source.contains("click != null && !click.openSettings && valid(click, first)"))
        val launch = source.substringAfter("private fun openSettings(click: Click)").substringBefore("private fun event(")
        assertFalse(launch.contains("setEnabled.invoke"))
        assertTrue(launch.contains("UserHandle.getUserHandleForUid(0)"))
    }

    /**
     * 「内置移动网络磁贴」这一档曾静默不生效：`setItems` 的数组形参写成了 `[$ITEM;`，
     * 少了 `L`，于是 `SystemUiMethod.matches` 永远不成立，hook 装不上，
     * 真机日志只有一行 `systemui target missing: ...QSDetailContent#setItems[[com...Item;`。
     *
     * 这条断言同时钉住两件事：数组形参必须是 `[L<类名>;`，且报告里给出的
     * 形参字符串与宿主成员清单逐字一致。
     */
    @Test fun nativeDetailCarriersMatchTheHostExactly() {
        fun descriptor(type: String) = when (type) {
            "void" -> "V"; "int" -> "I"; "boolean" -> "Z"
            else -> if (type.startsWith("[")) type.replace('.', '/')
            else "L${type.replace('.', '/')};"
        }
        assertEquals("[Lcom/android/systemui/qs/QSDetailContent\$Item;",
            descriptor(FiveGDetailTargets.ITEM).let { "[" + it })
        val fixture = javaClass.getResourceAsStream("/five-g-detail-host-members.txt")!!
            .bufferedReader().use { it.readLines().toSet() }
        for (method in FiveGDetailTargets.methods) {
            val signature = "L${method.owner.replace('.', '/')};->" + method.name + "(" +
                method.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(method.returns)
            assertTrue(signature, signature in fixture)
        }
        // 数组形参必须带 L，否则 matches() 永不成立、整档静默失效。
        // 注意：SystemUiMethod.parameters 用 **点号** 写法（`Class.getName()` 的形状），
        // 形如 `[Lcom.android.systemui.qs.QSDetailContent$Item;`；带斜杠的是成员清单里的签名。
        assertEquals(listOf("[Lcom.android.systemui.qs.QSDetailContent\$Item;"),
            FiveGDetailTargets.setItems.parameters)
        assertFalse(FiveGDetailTargets.setItems.parameters.any { it.startsWith("[") && !it.startsWith("[L") })
        // 这条 hook 必须挂在主线程也会走的那条路径上：详情页组装/点击都在主线程。
        val source = File("src/main/java/io/github/YGHFv/HyperExtend/hook/feature/systemui/FiveGDetailHooks.kt").readText()
        assertFalse("主线程闸门会让这条 hook 永不触发", source.contains("backgroundThread"))
        assertFalse(source.contains("Looper.myLooper()"))
        // 「5G 开关被塞进 SIM 卡片」的根因：宿主 `Adapter.updateGroupInfo()` 只按
        // `getType()` 分组（Selectable=0xe9 / Toggle=0x91d / Divider=0x5b25），
        // 而 `ToggleItem.isForceSingle()` 恒为 true；只有「紧跟一条 TextDividerItem」
        // 才会被宿主当成新的一组。所以插入点必须取**最后一条分组头之后**，
        // 不能退回「最后一个 SelectableItem 之后」，否则又会被并进 SIM 那张卡。
        assertTrue("插入点必须优先取最后一条 TextDividerItem 之后",
            source.contains("items.indices.lastOrNull { i -> items[i]?.let(isDivider) == true }"))
        assertTrue(source.contains("val insertIndex = afterDivider ?: afterSelectable ?: return null"))
        // 分隔条不再由模块自造，而是复用宿主原生「其他设置」分组头 ⇒ 参数里不再有 divider 构造项。
        val spliceApi = source.substringAfter("fun splice(items: Array<Any?>): Array<Any?>?")
            .substringBefore("fun toggle()")
        assertTrue(spliceApi.contains("spec.divider::isInstance"))
        assertFalse("不要再把 extra 位留给 null 分隔条", source.contains("dividerSeparator"))
        assertTrue("DIVIDER 常量必须在安装时解析出来", source.contains("FiveGDetailTargets.DIVIDER"))
        // 粘连的根因：宿主 toggle 分支只设 bottomMargin 且仅末位给值、从不设 topMargin，
        // 所以两个相邻 toggle 之间恒为 0 间距。模块必须自己给注入行补间距，
        // 且必须挂在「非 bridge 的」onBindViewHolder(ItemHolder,int) 上。
        assertTrue("必须挂上条目绑定收尾 hook 来补间距",
            source.contains("HookRuntime.hookAfter(onBind, \"${'$'}{FiveGTileSettings.STYLE}/space\")"))
        assertTrue(source.contains("api.space(chain.thisObject"))
        assertTrue("间距只能改我们注入的那一行", source.contains("if (!isInjected(item)) return"))
        assertTrue(source.contains("params.bottomMargin = gap"))
        // 按 position 从宿主 items 数组取回条目，才能确认「这一行是不是我们插的」。
        assertTrue(source.contains("FiveGDetailTargets.ITEMS_FIELD"))
        assertTrue(source.contains("FiveGDetailTargets.HOLDER_ITEM_VIEW"))
        assertTrue(source.contains("FiveGDetailTargets.CARD_MARGIN_DIMEN"))
        for (entry in listOf(
            "Lcom/android/systemui/qs/QSDetailContent\$Adapter;->onBindViewHolder(" +
                "Lcom/android/systemui/qs/QSDetailContent\$ItemHolder;I)V",
            "Lcom/android/systemui/qs/QSDetailContent\$Adapter;->this\$0:Lcom/android/systemui/qs/QSDetailContent;",
            "Lcom/android/systemui/qs/QSDetailContent;->items:[Lcom/android/systemui/qs/QSDetailContent\$Item;",
        )) assertTrue(entry, entry in fixture)
        // ToggleItem 的 getType() 是 0x91d，宿主对该类型的原生分支写的是「智能双卡」而不是 5G；
        // 所以命中我们插入的那一项之后必须吞掉事件，绝不能落到原生写路径上。
        val clickBlock = source.substringAfter("\"${'$'}{FiveGTileSettings.STYLE}/click\"").substringBefore("ready =")
        // 非我方条目照旧放行；我方条目被命中之后的那一段绝不能有 chain.proceed()。
        assertTrue(clickBlock.contains("!isInjected(item)) return@hook chain.proceed()"))
        val handled = clickBlock.substringAfter("!isInjected(item)) return@hook chain.proceed()")
        assertFalse("命中后仍放行原生分支会写坏智能双卡开关", handled.contains("chain.proceed()"))
        assertTrue(handled.contains("api.toggle()"))
    }

    /**
     * `spliceItem` 是纯函数，这里直接跑真逻辑（不读源码）钉住插入位置：
     * 有原生「其他设置」分组头时必须紧跟在它后面；没有分组头时退回最后一个
     * `SelectableItem` 之后（否则 5G 入口会消失）。
     *
     * 这条同时挡住回归：`ToggleItem.isForceSingle()` 恒 true，插在 SIM 那串
     * `SelectableItem` 之后会被宿主并进同一张卡，所以「有没有分组头」必须是
     * 两种不同的插入点，不能共用。
     */
    @Test fun fiveGEntryJoinsTheNativeGroupHeaderAndFallsBackWithoutOne() {
        class Row(val kind: String)
        val selectable = { o: Any -> (o as Row).kind == "selectable" }
        val divider = { o: Any -> (o as Row).kind == "divider" }
        val sim1 = Row("selectable"); val sim2 = Row("selectable")
        val header = Row("divider"); val smart = Row("toggle"); val fiveG = Row("toggle")

        val withHeader = FiveGDetailHooks.spliceItem(
            arrayOf<Any?>(sim1, sim2, header, smart), Row::class.java, fiveG, selectable, divider)
        assertEquals(listOf(sim1, sim2, header, fiveG, smart), withHeader!!.toList())
        // 5G 项必须紧跟分组头（index+1），这样才能和「智能双卡」同组。
        assertEquals(header, withHeader[2]); assertEquals(fiveG, withHeader[3])

        // 没有分组头（例如单卡详情页）时退回「最后一个 SelectableItem 之后」，入口仍出现。
        val noHeader = FiveGDetailHooks.spliceItem(
            arrayOf<Any?>(sim1, sim2), Row::class.java, fiveG, selectable, divider)
        assertEquals(listOf(sim1, sim2, fiveG), noHeader!!.toList())

        // 既没有分组头也没有可选行时不要乱插，交给调用方保持原数组。
        assertNull(FiveGDetailHooks.spliceItem(arrayOf<Any?>(smart), Row::class.java, fiveG, selectable, divider))
    }

    /**
     * 宿主 `QSDetailContent$ToggleItem` 自己实现了 `equals/hashCode`，所以
     * 「这一项是不是我们插的」只能用对象身份（`===`）判定。退化成 `equals`
     * 会把内容相同的**原生智能双卡**也算成我们的条目，点击就会去写 5G。
     *
     * 这条直接跑真逻辑：两个字段完全相等但对象不同的实例必须被判成不同。
     */
    @Test fun injectedIdentityIsObjectIdentityNotEquality() {
        class Row(val kind: String) {
            override fun equals(other: Any?) = other is Row && other.kind == kind
            override fun hashCode() = kind.hashCode()
        }
        val injected = Row("toggle")
        val nativeSameContent = Row("toggle")
        assertEquals(injected, nativeSameContent) // 先确认这两个对象按 equals 是相等的
        assertTrue(FiveGDetailHooks.identityContains(listOf(injected), injected))
        assertFalse("equals 相等的原生条目不能被当成注入项",
            FiveGDetailHooks.identityContains(listOf(injected), nativeSameContent))
        assertFalse(FiveGDetailHooks.identityContains(emptyList(), injected))
    }

    /**
     * 开启动画异常的两条根因，全部来自「模块做的比宿主多」：
     *
     * 1. 宿主一次开页会走到 `setItems` 2–3 次（`handleSecondaryClick` → `showDetail`
     *    + `updateItems`；`createDetailView` 在 `mShowingDetail` 时再 `updateItems`；
     *    `CellSignalCallback` 各一次），每次新建 `ToggleItem` 就会让 `notifyDataSetChanged`
     *    按「换了条目」全量重绑，与 `QSDetailClipper.animateCircularClip` 的揭示窗口交错。
     *    因此每个 `QSDetailContent` 必须复用同一个注入实例。
     * 2. 宿主 ToggleItem 分支补间距时**只写 `bottomMargin` 字段**，从不
     *    `setLayoutParams`／`requestLayout`；模块多一次 `view.layoutParams = params`
     *    就会在揭示动画期间再触发一次布局。
     */
    @Test fun detailInjectionStaysIdempotentAndNeverRelayoutsDuringReveal() {
        val source = File("src/main/java/io/github/YGHFv/HyperExtend/hook/feature/systemui/FiveGDetailHooks.kt").readText()

        // 1) 每个详情页只保留一个注入实例，重复 setItems 复用同一对象。
        assertTrue("必须按 content 缓存注入实例", source.contains("injectedToggles"))
        assertTrue(source.contains("WeakHashMap"))
        assertTrue(source.contains("val previous = content?.let { injectedToggles[it] }"))
        assertTrue("复用已有实例时不能再新建", source.contains("val toggle = previous ?: UnsafeAccess.allocateInstance"))
        assertTrue(source.contains("reused = previous != null"))
        assertTrue("必须记录这次是不是复用", source.contains("val reused: Boolean"))
        // 重复注入不应该再刷日志，否则日志看起来像「注入失控」。
        assertTrue(source.contains("if (!outcome.reused)"))

        // 2) 补间距只写字段，绝不回写 layoutParams（宿主自己也不这么做）。
        // 只看代码行：注释里会引用这行旧实现来说明它为什么被删掉。
        val codeLines = source.lines().filterNot { it.trimStart().startsWith("*") }
        assertFalse("setLayoutParams 会在揭示动画期间再触发一次布局",
            codeLines.any { it.contains("layoutParams = params") })
        assertTrue(source.contains("params.bottomMargin = gap"))
    }
}
