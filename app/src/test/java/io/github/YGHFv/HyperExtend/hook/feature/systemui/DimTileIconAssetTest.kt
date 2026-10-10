/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import java.io.File
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

class DimTileIconAssetTest {
    private val android = "http://schemas.android.com/apk/res/android"
    private fun vector(): Element {
        val path = "src/main/res/drawable/${DimTileIconPolicy.ASSET}.xml"
        val file = listOf(File(path), File("app/$path")).first { it.exists() }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(file).documentElement
    }

    @Test fun vectorHasNative24DpSizeAndTintableWhitePaths() {
        val vector = vector()
        assertEquals("vector", vector.tagName)
        assertEquals("24dp", vector.getAttributeNS(android, "width"))
        assertEquals("24dp", vector.getAttributeNS(android, "height"))
        assertEquals("24.0", vector.getAttributeNS(android, "viewportWidth"))
        assertEquals("24.0", vector.getAttributeNS(android, "viewportHeight"))
        val paths = vector.getElementsByTagName("path")
        assertEquals(9, paths.length)
        for (i in 0 until paths.length) assertEquals("#FFFFFFFF", (paths.item(i) as Element).getAttributeNS(android, "fillColor"))
    }

    @Test fun allNinePathsMatchTheAttributedUpstreamAsset() {
        val paths = vector().getElementsByTagName("path")
        val text = (0 until paths.length).joinToString("\n") { (paths.item(it) as Element).getAttributeNS(android, "pathData") }
        val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        val expected = javaClass.getResourceAsStream("/dim-tile-icon-vector.sha256")!!.bufferedReader().use { it.readText().trim() }
        assertEquals(expected, hash)
    }
}
