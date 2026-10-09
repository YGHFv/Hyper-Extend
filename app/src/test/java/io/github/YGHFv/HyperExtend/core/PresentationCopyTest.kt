/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.core

import org.junit.Assert.*
import org.junit.Test

class PresentationCopyTest {
    @Test fun featureListDescriptionsStayShort() {
        FEATURES.forEach { feature ->
            assertTrue("${feature.id}: ${feature.summary}", feature.summary.length <= 28)
            assertFalse(feature.summary.contains('\n'))
        }
    }

    @Test fun projectAcknowledgementsAreUniqueAndLinked() {
        assertTrue(OPEN_SOURCE_PROJECTS.isNotEmpty())
        assertEquals(OPEN_SOURCE_PROJECTS.size, OPEN_SOURCE_PROJECTS.map { it.name }.toSet().size)
        assertEquals(OPEN_SOURCE_PROJECTS.size, OPEN_SOURCE_PROJECTS.map { it.url }.toSet().size)
        OPEN_SOURCE_PROJECTS.forEach {
            assertTrue(it.name.isNotBlank())
            assertTrue(it.url.startsWith("https://"))
        }
        assertTrue(OPEN_SOURCE_PROJECTS.any { it.name == "HyperCeiler" })
        assertFalse(OPEN_SOURCE_PROJECTS.any { it.name == "HideLine" || it.name.contains("原创") })
    }

    @Test fun shortPresentationRetainsProvenanceAndRequirements() {
        FEATURES.forEach {
            assertTrue(it.origin.isNotBlank())
            assertTrue(it.license.isNotBlank())
        }
        val guard = featureById("mishare_receive_guard")!!
        assertTrue(guard.requirement!!.contains("接收端"))
        assertTrue(guard.requirement.contains("目标方法不匹配时跳过"))
        assertEquals(listOf("systemui", "screenshot"), featureById("status_bar_screenshot_hide")!!.scopes)
    }

    @Test fun volumeReferenceHasOneAcknowledgementWithItsRepositoryLink() {
        val project = OPEN_SOURCE_PROJECTS.single { it.name == "HyperVolumeANC" }
        assertEquals("https://github.com/zhhhyyyyyy/HyperVolumeANC", project.url)
    }
}
