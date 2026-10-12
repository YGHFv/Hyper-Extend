/* Copyright (C) 2023-2026 HyperCeiler Contributions; Copyright (C) 2026 YGHFv;
 * SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object GmsTilePolicy {
    enum class Action { TOGGLE, SETTINGS }
    const val FEATURE = "control_center_gms_tile"
    const val SPEC = "hyperextend_gms"
    const val CORE = "com.google.android.gms"
    val packages = listOf(CORE, "com.google.android.gsf", "com.android.vending",
        "com.google.android.syncadapters.contacts", "com.google.android.backuptransport",
        "com.google.android.onetimeinitializer", "com.google.android.partnersetup",
        "com.google.android.configupdater", "com.google.android.ext.shared",
        "com.google.android.printservice.recommendation")
    data class App(val name: String, val uid: Int, val version: Long, val state: Int, val enabled: Boolean)
    fun usable(apps: List<App>) = apps.any { it.name == CORE } && apps.size <= packages.size &&
        apps.map { it.name }.distinct().size == apps.size &&
        apps.all { it.name in packages && it.uid in 0..99999 && it.state in 0..4 }
    fun target(apps: List<App>): Int? = if (!usable(apps)) null else
        if (apps.single { it.name == CORE }.enabled) 3 else 1
    fun plan(apps: List<App>): List<App> {
        val target = target(apps) ?: return emptyList()
        // Disable the core last; enable it first. Missing optional packages are not installed.
        return apps.filter { it.state != target }.sortedBy {
            if (it.name == CORE) if (target == 3) 1 else -1 else 0
        }
    }
    fun canAct(feature: Boolean, safe: Boolean, owner: Boolean, unlocked: Boolean,
        restricted: Boolean, permission: Boolean, listening: Boolean, age: Long) =
        feature && !safe && owner && unlocked && !restricted && permission && listening && age in 0..1500
    fun transactionAge(age: Long) = age in 0..5000
    fun shouldWrite(action: Action, valid: Boolean, usable: Boolean, unchanged: Boolean) =
        action == Action.TOGGLE && valid && usable && unchanged
    fun same(expected: List<App>, current: List<App>) = usable(current) &&
        expected.sortedBy { it.name } == current.sortedBy { it.name }
    fun append(native: String?): String? {
        if (native.isNullOrBlank() || native.length > 16384 || SPEC in native.split(',').map { it.trim() }) return native
        return native + (if (native.endsWith(',')) "" else ",") + SPEC
    }
}

/** No automatic retries. Roll back only the values written here and still owned. */
internal object GmsPackageTransaction {
    interface Backend {
        fun allowed(): Boolean
        fun read(name: String): GmsTilePolicy.App?
        fun write(name: String, state: Int)
    }
    data class Result(val complete: Boolean, val restored: Boolean, val changed: Int)
    fun apply(snapshot: List<GmsTilePolicy.App>, backend: Backend): Result {
        val target = GmsTilePolicy.target(snapshot) ?: return Result(false, true, 0)
        val changed = mutableListOf<GmsTilePolicy.App>()
        try {
            if (!backend.allowed() || snapshot.any { backend.read(it.name) != it }) return Result(false, true, 0)
            for (app in GmsTilePolicy.plan(snapshot)) {
                check(backend.allowed() && backend.read(app.name) == app)
                // Include a setter which throws after persisting; read-back owns rollback.
                changed += app
                backend.write(app.name, target)
                check(backend.read(app.name) == app.copy(state = target, enabled = target == 1))
            }
            check(backend.allowed() && snapshot.all {
                backend.read(it.name) == it.copy(state = target, enabled = target == 1)
            })
            check(backend.allowed())
            return Result(true, false, changed.size)
        } catch (_: Throwable) {
            var restored = true
            for (app in changed.asReversed()) {
                try {
                    val actual = backend.read(app.name)
                    if (actual == app) continue
                    if (!backend.allowed() || actual != app.copy(state = target, enabled = target == 1)) {
                        restored = false; continue
                    }
                    backend.write(app.name, app.state)
                    if (backend.read(app.name) != app) restored = false
                } catch (_: Throwable) { restored = false }
            }
            return Result(false, restored, changed.size)
        }
    }
}
