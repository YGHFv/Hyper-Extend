/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class DimTileIconCacheTest {
    private val cache = DimTileIconCache<String, Any>()

    @Test fun sameTileAndConfigurationKeepIconIdentityForStateDiffing() {
        val tile = Any()
        val icon = cache.get(tile, "normal") { Any() }
        assertSame(icon, cache.get(tile, "normal") { error("must reuse") })
    }

    @Test fun separateTilesDoNotShareTintableDrawableIdentity() {
        val first = cache.get(Any(), "normal") { Any() }
        val second = cache.get(Any(), "normal") { Any() }
        assertNotSame(first, second)
    }

    @Test fun configurationChangeCreatesAFreshIcon() {
        val tile = Any()
        val first = cache.get(tile, "portrait") { Any() }
        val second = cache.get(tile, "landscape") { Any() }
        assertNotSame(first, second)
        assertSame(second, cache.get(tile, "landscape") { error("must reuse") })
    }

    @Test fun missingAssetCanBeRetried() {
        val tile = Any()
        assertNull(cache.get(tile, "normal") { null })
        assertNotNull(cache.get(tile, "normal") { Any() })
    }

    @Test fun failedNewConfigurationNeverReusesTheOldConfigurationIcon() {
        val tile = Any()
        val previous = cache.get(tile, "old") { Any() }
        assertNull(cache.get(tile, "new") { null })
        assertSame(previous, cache.get(tile, "old") { error("must reuse") })
        assertNotSame(previous, cache.get(tile, "new") { Any() })
    }

    @Test fun constructionExceptionDoesNotPoisonCache() {
        val tile = Any()
        assertTrue(runCatching { cache.get(tile, "normal") { error("asset failure") } }.isFailure)
        assertNotNull(cache.get(tile, "normal") { Any() })
    }

    @Test fun concurrentRequestsPublishOneIdentity() {
        val tile = Any()
        val values = java.util.Collections.synchronizedList(mutableListOf<Any>())
        val threads = List(8) { Thread { values.add(cache.get(tile, "normal") { Any() }!!) } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(8, values.size)
        values.forEach { assertSame(values.first(), it) }
    }
}
