/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.mishare

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MiShareDestinationPolicyTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root: File get() = temporary.root.canonicalFile
    private val original: String get() = File(root, "base.apk").absolutePath
    private val token = "0123456789ab"
    private val alternate: String get() = File(root, "base (HE-$token).apk").absolutePath

    private fun policy(
        disk: (File) -> PathPresence = { PathPresence.ABSENT },
        index: (File) -> PathPresence = {
            if (it.path == original) PathPresence.PRESENT else PathPresence.ABSENT
        },
        nextToken: () -> String = { token },
    ) = MiShareDestinationPolicy(disk, index, nextToken)

    @Test fun cleanPathIsUnchanged() {
        assertEquals(original, policy(index = { PathPresence.ABSENT }).select(original, root.path, root))
    }

    @Test fun orphanRowSelectsAlternateWithoutCreatingFiles() {
        assertEquals(alternate, policy().select(original, root.path, root))
        assertEquals(0, root.listFiles()!!.size)
    }

    @Test fun realFileIsNeverReplacedOrQueried() {
        File(original).writeText("keep")
        val policy = policy(disk = { PathPresence.PRESENT }, index = { error("must not query") })
        assertEquals(original, policy.select(original, root.path, root))
        assertEquals("keep", File(original).readText())
    }

    @Test fun ordinaryDuplicateSelectedByHostIsUnchanged() {
        val duplicate = File(root, "base(1).apk").absolutePath
        assertEquals(duplicate, policy(index = { PathPresence.ABSENT }).select(duplicate, root.path, root))
    }

    @Test fun unknownDiskStateDoesNotLookLikeMissingFile() {
        assertEquals(original, policy(disk = { PathPresence.UNKNOWN }).select(original, root.path, root))
    }

    @Test fun unknownIndexStateKeepsOriginal() {
        assertEquals(original, policy(index = { PathPresence.UNKNOWN }).select(original, root.path, root))
    }

    @Test fun permissionFailureKeepsOriginal() {
        assertEquals(original, policy(index = { throw SecurityException() }).select(original, root.path, root))
    }

    @Test fun ioFailureKeepsOriginal() {
        assertEquals(original, policy(disk = { throw IOException() }).select(original, root.path, root))
    }

    @Test fun candidateWithRealFileIsSkipped() {
        var counter = 0
        val policy = policy(
            disk = { if (it.path == alternate) PathPresence.PRESENT else PathPresence.ABSENT },
            nextToken = { if (counter++ == 0) token else "abcdef012345" },
        )
        assertEquals(File(root, "base (HE-abcdef012345).apk").path, policy.select(original, root.path, root))
    }

    @Test fun candidateWithIndexRowIsSkipped() {
        var counter = 0
        val policy = policy(
            index = { if (it.path == original || it.path == alternate) PathPresence.PRESENT else PathPresence.ABSENT },
            nextToken = { if (counter++ == 0) token else "abcdef012345" },
        )
        assertEquals(File(root, "base (HE-abcdef012345).apk").path, policy.select(original, root.path, root))
    }

    @Test fun candidateQueryFailureDoesNotSelectUncheckedPath() {
        val policy = policy(index = { if (it.path == original) PathPresence.PRESENT else PathPresence.UNKNOWN })
        assertEquals(original, policy.select(original, root.path, root))
    }

    @Test fun finalFilesystemRecheckRejectsNewlyCreatedFile() {
        var candidateChecks = 0
        val policy = policy(disk = {
            if (it.path == original || candidateChecks++ == 0) PathPresence.ABSENT else PathPresence.PRESENT
        })
        assertEquals(original, policy.select(original, root.path, root))
    }

    @Test fun searchIsBounded() {
        var queries = 0
        val policy = policy(index = { queries++; PathPresence.PRESENT })
        assertEquals(original, policy.select(original, root.path, root))
        assertEquals(9, queries)
    }

    @Test fun customDirectoryIsIgnoredWithoutQueries() {
        val other = temporary.newFolder("custom")
        val path = File(other, "base.apk").absolutePath
        assertEquals(path, policy(index = { error("must not query") }).select(path, other.path, root))
    }

    @Test fun siblingPrefixIsNotTreatedAsRoot() {
        val path = File(root.path + "-other", "base.apk").absolutePath
        assertEquals(path, policy(index = { error("must not query") }).select(path, root.path, root))
    }

    @Test fun traversalAndRelativeResultsAreIgnored() {
        val policy = policy(index = { error("must not query") })
        val traversal = File(root, "../base.apk").path
        assertEquals(traversal, policy.select(traversal, root.path, root))
        assertEquals("base.apk", policy.select("base.apk", root.path, root))
    }

    @Test fun nestedDirectoryIsIgnored() {
        val path = File(root, "sub/base.apk").absolutePath
        assertEquals(path, policy(index = { error("must not query") }).select(path, root.path, root))
    }

    @Test fun malformedTokenKeepsOriginal() {
        assertEquals(original, policy(nextToken = { "../bad" }).select(original, root.path, root))
    }

    @Test fun extensionAndHiddenNamesArePreserved() {
        val policy = policy()
        assertEquals("archive.tar (HE-$token).gz", policy.fallbackName("archive.tar.gz", token))
        assertEquals("README (HE-$token)", policy.fallbackName("README", token))
        assertEquals(".hidden (HE-$token)", policy.fallbackName(".hidden", token))
        assertEquals("file. (HE-$token)", policy.fallbackName("file.", token))
    }

    @Test fun longUtf8NameFitsFilesystemWithoutBreakingCodePoints() {
        val name = "\u6587\ud83d\ude80".repeat(60) + ".apk"
        val result = policy().fallbackName(name, token)!!
        assertTrue(result.toByteArray(Charsets.UTF_8).size <= 255)
        assertEquals(result, String(result.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        assertTrue(result.endsWith(" (HE-$token).apk"))
    }

    @Test fun maxLengthAsciiNameIsTruncatedOnlyAtStem() {
        val result = policy().fallbackName("x".repeat(251) + ".apk", token)!!
        assertEquals(255, result.toByteArray(Charsets.UTF_8).size)
        assertTrue(result.endsWith(" (HE-$token).apk"))
    }

    @Test fun oversizedExtensionIsNotSilentlyChanged() {
        assertNull(policy().fallbackName("x." + "a".repeat(240), token))
    }
}
