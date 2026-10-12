/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class MediaPaletteTest {
    private val accent = List(13) { 0xff100000.toInt() + it }
    private val secondary = List(13) { 0xff200000.toInt() + it }
    private val neutral = List(13) { 0xff300000.toInt() + it }
    private val neutral2 = List(13) { 0xff400000.toInt() + it }
    private fun colors(mode: Int, reverse: Boolean, brightness: Int) =
        MediaPalettePolicy.colors(mode, reverse, brightness, accent, secondary, neutral, neutral2)!!

    @Test fun linearReversalHasExactThresholdAndNeverChangesOtherModes() {
        assertEquals(accent[2], colors(4, true, 191).primary)
        assertEquals(accent[8], colors(4, true, 192).primary)
        assertEquals(accent[3], colors(4, true, 192).start)
        assertEquals(accent[2], colors(4, false, 255).primary)
        for (mode in 1..3) assertEquals(colors(mode, false, 255), colors(mode, true, 255))
    }
    @Test fun upstreamPaletteRolesAreNotHardcodedBlackAndWhite() {
        assertEquals(MediaPalette(accent[2], accent[2], accent[8], accent[8]), colors(1, false, 50))
        for (mode in 2..3) assertEquals(MediaPalette(neutral[1], neutral2[3], secondary[9], accent[9]), colors(mode, false, 50))
    }
    @Test fun invalidPalettesKeepNativeRatherThanGuessingIndices() {
        assertNull(MediaPalettePolicy.colors(4, true, 255, accent.take(12), secondary, neutral, neutral2))
        assertNull(MediaPalettePolicy.colors(4, true, 256, accent, secondary, neutral, neutral2))
        assertNull(MediaPalettePolicy.colors(0, false, 50, accent, secondary, neutral, neutral2))
    }
    @Test fun boundedSamplingAndBrightnessAreDeterministic() {
        assertEquals(255, MediaPalettePolicy.brightness(intArrayOf(-1)))
        assertEquals(0, MediaPalettePolicy.brightness(intArrayOf(0xff000000.toInt())))
        assertNull(MediaPalettePolicy.brightness(intArrayOf()))
        val pixels = intArrayOf(0xffff0000.toInt(), 0xffff0000.toInt(), 0xff0000ff.toInt())
        assertEquals(0xffff0000.toInt(), MediaPalettePolicy.dominant(pixels))
        assertNull(MediaPalettePolicy.dominant(intArrayOf()))
    }
    @Test fun colorTransitionSharesPixelTimelineAndHasExactEndpoints() {
        val from = colors(4, false, 20); val to = colors(4, true, 255)
        assertEquals(from, from.blend(to, 0)); assertEquals(to, from.blend(to, 255))
        val frames = MediaArtworkTransition(intArrayOf(from.primary))
        frames.update(intArrayOf(to.primary), 1000, true)
        val alpha = frames.alpha(1166)
        assertEquals(frames.snapshot(1166).single(), from.blend(to, alpha).primary)
        assertEquals(0x2e123456, MediaPalettePolicy.withAlpha(0xff123456.toInt(), 46))
    }
    @Test fun nativeShaderCallIsNeverReplayedAndTemporaryStateRestoresOnFailure() {
        var filter: Any? = null
        val owned = Any()
        var calls = 0
        val failure = IllegalStateException("host draw")
        try {
            withMediaPaletteOverride({ filter = owned }, { if (filter === owned) filter = null }) {
                calls++; assertSame(owned, filter); throw failure
            }
            fail("expected host exception")
        } catch (actual: IllegalStateException) { assertSame(failure, actual) }
        assertEquals(1, calls); assertNull(filter)
    }
    @Test fun anotherNativeFilterWriteWinsOverModuleRestoration() {
        var filter: Any? = null
        val owned = Any(); val native = Any()
        withMediaPaletteOverride({ filter = owned }, { if (filter === owned) filter = null }) { filter = native }
        assertSame(native, filter)
    }

    private fun descriptor(type: String) = when (type) {
        "void" -> "V"; "int" -> "I"; "boolean" -> "Z"
        else -> "L${type.replace('.', '/')};"
    }
    @Test fun missingArtUsesOnlyAlreadyResolvedUserBoundIconNeverOldArt() {
        val art = Any(); val icon = Any()
        val user = MediaBackgroundPolicy.identity(10, 1_010_042, "example.player")!!
        assertSame(art, MediaBackgroundPolicy.source(true, true, art, icon, user))
        assertSame(icon, MediaBackgroundPolicy.source(true, false, art, icon, user))
        assertSame(icon, MediaBackgroundPolicy.source(true, true, null, icon, user))
        assertNull(MediaBackgroundPolicy.source(true, false, art, icon, null))
        assertNull(MediaBackgroundPolicy.source(false, true, art, icon, user))
        assertNull(MediaBackgroundPolicy.identity(0, 1_010_042, "example.player"))
        assertNull(MediaBackgroundPolicy.identity(-1, -1, "example.player"))
        assertNull(MediaBackgroundPolicy.identity(0, 10_042, " "))
    }
    @Test fun sourceUserAndPackageSwitchesAreDistinctAndFallbackHasExactFields() {
        assertNotEquals(MediaBackgroundPolicy.identity(0, 10_042, "a"),
            MediaBackgroundPolicy.identity(10, 1_010_042, "a"))
        assertNotEquals(MediaBackgroundPolicy.identity(0, 10_042, "a"),
            MediaBackgroundPolicy.identity(0, 10_042, "b"))
        val signatures = javaClass.getResourceAsStream("/media-fallback-host-members.txt")!!
            .bufferedReader().use { it.readLines().toSet() }
        for ((owner, name, type) in MediaPaletteTargets.fallbackFields) {
            val signature = descriptor(owner) + "->" + name + ":" + descriptor(type)
            assertTrue(signature, signature in signatures)
        }
        assertEquals(6, signatures.size)
    }
    @Test fun everyReflectedPaletteMemberHasAnExactCompleteHostFixture() {
        val signatures = javaClass.getResourceAsStream("/media-palette-host-members.txt")!!.bufferedReader().use { it.readLines().toSet() }
        for (method in MediaPaletteTargets.methods + MediaPaletteTargets.shaderDraw + MediaProgressTargets.draw) assertTrue(descriptor(method.owner) + "->" + method.name +
            "(" + method.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(method.returns) in signatures)
        for ((owner, name, type) in MediaPaletteTargets.fields) assertTrue(descriptor(owner) + "->" + name + ":" + descriptor(type) in signatures)
        assertTrue(descriptor(MediaPaletteTargets.SCHEME) + "-><init>(" +
            MediaPaletteTargets.constructorParameters.joinToString("") { descriptor(it) } + ")V" in signatures)
        assertEquals(18, MediaPaletteTargets.fields.size)
        assertEquals(5, MediaPaletteTargets.methods.size)
    }
}
