/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.FeaturePanel
import io.github.YGHFv.HyperExtend.core.featureById
import io.github.YGHFv.HyperExtend.core.panel
import org.junit.Assert.*
import org.junit.Test

class GmsTileMigrationTest {
    private val core = GmsTilePolicy.App(GmsTilePolicy.CORE, 10010, 0, true)
    private val store = GmsTilePolicy.App("com.android.vending", 10011, 0, true)
    private class Backend(values: List<GmsTilePolicy.App>) : GmsPackageTransaction.Backend {
        private val defaults = values.associate { it.name to (it.state != 0 || it.enabled) }
        val values = values.associateBy { it.name }.toMutableMap()
        val writes = mutableListOf<Pair<String, Int>>()
        var allowed = true
        var intercept: ((String, Int) -> Unit)? = null
        override fun allowed() = allowed
        override fun read(name: String) = values[name]
        override fun write(name: String, state: Int) {
            writes += name to state
            values[name] = values.getValue(name).copy(state = state,
                enabled = if (state == 0) defaults.getValue(name) else state == 1)
            intercept?.invoke(name, state)
        }
    }
    @Test fun fixedFamilyDoesNotInstallMissingAppsOrTreatManifestDisabledAsEnabled() {
        assertEquals(10, GmsTilePolicy.packages.size)
        assertEquals(3, GmsTilePolicy.target(listOf(core)))
        assertEquals(1, GmsTilePolicy.target(listOf(core.copy(enabled = false))))
        assertFalse(GmsTilePolicy.usable(listOf(store)))
        assertFalse(GmsTilePolicy.usable(listOf(core, core)))
        assertFalse(GmsTilePolicy.usable(listOf(core.copy(uid = 1010010))))
        assertFalse(GmsTilePolicy.usable(listOf(core, store.copy(name = "unrelated"))))
    }
    @Test fun disableCoreLastAndEnableCoreFirst() {
        assertEquals(listOf(store, core), GmsTilePolicy.plan(listOf(core, store)))
        val disabled = listOf(store, core).map { it.copy(state = 3, enabled = false) }
        assertEquals(GmsTilePolicy.CORE, GmsTilePolicy.plan(disabled).first().name)
    }
    @Test fun successfulTransactionIsExplicitAndNotAutomaticallyUndone() {
        val backend = Backend(listOf(core, store))
        val result = GmsPackageTransaction.apply(listOf(core, store), backend)
        assertTrue(result.complete)
        assertEquals(listOf(store.name to 3, core.name to 3), backend.writes)
        assertEquals(3, backend.values.getValue(core.name).state)
    }
    @Test fun setterThrowingAfterPersistingRollsBackExactOriginalOverrides() {
        val backend = Backend(listOf(core, store))
        backend.intercept = { name, state -> if (name == core.name && state == 3) error("persisted then failed") }
        val result = GmsPackageTransaction.apply(listOf(core, store), backend)
        assertFalse(result.complete); assertTrue(result.restored)
        assertEquals(core, backend.values[core.name]); assertEquals(store, backend.values[store.name])
    }
    @Test fun snapshotChangeAndExternalTakeoverNeverTriggerBlindRetryOrRollback() {
        val changed = Backend(listOf(core.copy(uid = 10099), store))
        assertFalse(GmsPackageTransaction.apply(listOf(core, store), changed).complete)
        assertTrue(changed.writes.isEmpty())
        val stateChanged = Backend(listOf(core.copy(state = 1, enabled = false), store))
        assertFalse(GmsPackageTransaction.apply(listOf(core, store), stateChanged).complete)
        assertTrue(stateChanged.writes.isEmpty())
        val backend = Backend(listOf(core, store))
        backend.intercept = { name, state -> if (name == core.name && state == 3) {
            backend.values[store.name] = store.copy(state = 2, enabled = false)
            error("external writer")
        } }
        val result = GmsPackageTransaction.apply(listOf(core, store), backend)
        assertFalse(result.complete); assertFalse(result.restored)
        assertEquals(2, backend.values.getValue(store.name).state)
        assertFalse((store.name to 0) in backend.writes)
    }
    @Test fun revokedPermissionOrLifecycleInvalidationStopsAllFurtherWrites() {
        val backend = Backend(listOf(core, store))
        backend.intercept = { _, _ -> backend.allowed = false }
        val result = GmsPackageTransaction.apply(listOf(core, store), backend)
        assertFalse(result.complete); assertFalse(result.restored)
        assertEquals(listOf(store.name to 3), backend.writes)
        assertTrue(GmsTilePolicy.canAct(true, false, true, true, false, true, true, 1500))
        assertFalse(GmsTilePolicy.canAct(true, false, true, true, false, true, true, 1501))
        assertFalse(GmsTilePolicy.canAct(true, true, true, true, false, true, true, 0))
        assertFalse(GmsTilePolicy.canAct(true, false, false, true, false, true, true, 0))
        assertFalse(GmsTilePolicy.canAct(true, false, true, false, false, true, true, 0))
        assertFalse(GmsTilePolicy.canAct(true, false, true, true, true, true, true, 0))
        assertTrue(GmsTilePolicy.transactionAge(5000))
        assertFalse(GmsTilePolicy.transactionAge(5001))
        assertFalse(GmsTilePolicy.transactionAge(-1))
    }

    @Test fun finalFamilyReadDetectsTakeoverAfterAnEarlierWriteSucceeded() {
        val backend = Backend(listOf(core, store))
        backend.intercept = { name, state -> if (name == core.name && state == 3) {
            backend.values[store.name] = store.copy(state = 2, enabled = false)
        } }
        val result = GmsPackageTransaction.apply(listOf(core, store), backend)
        assertFalse(result.complete)
        assertFalse(result.restored)
        assertEquals(core, backend.values[core.name])
        assertEquals(2, backend.values.getValue(store.name).state)
        assertFalse((store.name to 0) in backend.writes)
    }

    @Test fun matchingRawOverrideDoesNotProveTheEffectiveStateWasApplied() {
        val backend = Backend(listOf(core, store))
        backend.intercept = { name, state -> if (name == core.name && state == 3) {
            backend.values[core.name] = core.copy(state = 3, enabled = true)
        } }
        val result = GmsPackageTransaction.apply(listOf(core, store), backend)
        assertFalse(result.complete)
        assertFalse(result.restored)
        assertFalse((core.name to 0) in backend.writes)
        assertEquals(store, backend.values[store.name])
    }

    @Test fun finalReadAlsoChecksPackagesAlreadyAtTheRequestedOverride() {
        val stoppedStore = store.copy(state = 3, enabled = false)
        val backend = Backend(listOf(core, stoppedStore))
        backend.intercept = { name, state -> if (name == core.name && state == 3) {
            backend.values[store.name] = stoppedStore.copy(state = 2)
        } }
        val result = GmsPackageTransaction.apply(listOf(core, stoppedStore), backend)
        assertFalse(result.complete)
        assertEquals(core, backend.values[core.name])
        assertTrue(backend.writes.none { it.first == store.name })
    }

    @Test fun compensationPreservesManifestDisabledDefaultInsteadOfEnablingIt() {
        val disabledCore = core.copy(enabled = false)
        val disabledStore = store.copy(enabled = false)
        val backend = Backend(listOf(disabledCore, disabledStore))
        backend.intercept = { name, state -> if (name == store.name && state == 1) error("persisted then failed") }
        val result = GmsPackageTransaction.apply(listOf(disabledCore, disabledStore), backend)
        assertFalse(result.complete)
        assertTrue(result.restored)
        assertEquals(disabledCore, backend.values[core.name])
        assertEquals(disabledStore, backend.values[store.name])
    }

    @Test fun revocationDuringTheLastWriteCannotBeReportedAsCompleted() {
        val backend = Backend(listOf(core))
        backend.intercept = { _, _ -> backend.allowed = false }
        val result = GmsPackageTransaction.apply(listOf(core), backend)
        assertFalse(result.complete)
        assertFalse(result.restored)
        assertEquals(listOf(core.name to 3), backend.writes)
    }
    private fun descriptor(type: String): String = when (type) {
        "void" -> "V"; "int" -> "I"; "boolean" -> "Z"
        else -> "L${type.replace('.', '/')};"
    }
    private fun member(m: SystemUiMethod) = descriptor(m.owner) + "->" + m.name +
        "(" + m.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(m.returns)
    private fun fixture(name: String) = javaClass.getResourceAsStream("/gms-tile-$name-host-members.txt")!!
        .bufferedReader().use { it.readLines().toSet() }
    @Test fun fullCurrentProxyFactoryEditorAndFrameworkMembersAreAccountedFor() {
        val host = fixture("systemui") + fixture("framework")
        val plugin = fixture("plugin")
        for (m in GmsTileTargets.methods + StockTilesTargets.classicMethods) assertTrue(member(m), member(m) in host)
        for ((owner, name, type) in GmsTileTargets.fields + StockTilesTargets.classicFields)
            assertTrue(descriptor(owner) + "->" + name + ":" + descriptor(type) in host)
        for (m in GmsTileTargets.pluginCallers + StockTilesTargets.pluginMethods) assertTrue(member(m) in plugin)
        assertTrue("Landroid/app/ApplicationPackageManager;->setApplicationEnabledSetting(Ljava/lang/String;II)V" in host)
        assertTrue("Lcom/android/systemui/plugins/qs/QSTile\$BooleanState;-><init>()V" in host)
        assertEquals(34, FiveGTileContract.signatures.size)
        for (signature in FiveGTileContract.signatures) {
            val name = signature.substringBefore('(')
            val parameters = signature.substringAfter('(').substringBefore(')')
                .split(',').filter { it.isNotEmpty() }
            val expected = descriptor(GmsTileTargets.TILE) + "->" + name + "(" +
                parameters.joinToString("") { descriptor(it) } + ")" + descriptor(signature.substringAfter("):"))
            assertTrue(expected, expected in host)
        }
    }
    @Test fun editorIsAdditiveIndependentAndDefaultOff() {
        val source = "wifi,custom(example),,nfc"
        assertEquals(source + "," + GmsTilePolicy.SPEC, GmsTilePolicy.append(source))
        assertEquals(source + "," + GmsTilePolicy.SPEC, GmsTilePolicy.append(GmsTilePolicy.append(source)))
        assertNull(GmsTilePolicy.append(null))
        assertFalse(featureById(GmsTilePolicy.FEATURE)!!.defaultEnabled)
        assertEquals(FeaturePanel.TILES, featureById(GmsTilePolicy.FEATURE)!!.panel)
    }
}
