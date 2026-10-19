package io.github.YGHFv.HyperExtend.hook.launcher

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LauncherIdentityTest {
    @get:Rule val temporary = TemporaryFolder()

    private val process = LauncherProcessSnapshot(100, 10001, 54321, "com.miui.home", "/system/bin/app_process64", true)
    private val application = LauncherApplicationSnapshot("com.miui.home", "com.miui.home", 10001, 801027726,
        false, true, "libapp_launcher.so", "/data/app/example/base.apk", "1:2:3:4:5", 1, false, null)

    private inner class Source : LauncherIdentitySource {
        var processValue = process
        var applicationValue = application
        var processAfter: LauncherProcessSnapshot? = null
        var applicationAfter: LauncherApplicationSnapshot? = null
        var compatible = true
        var processReads = 0
        var applicationReads = 0
        var digestReads = 0
        override fun process(): LauncherProcessSnapshot {
            processReads++
            return if (processReads > 1) processAfter ?: processValue else processValue
        }
        override fun application(): LauncherApplicationSnapshot {
            applicationReads++
            return if (applicationReads > 1) applicationAfter ?: applicationValue else applicationValue
        }
        override fun imageCompatible(sourceApk: String): Boolean {
            assertEquals(applicationValue.sourceApk, sourceApk)
            digestReads++
            return compatible
        }
    }

    @Test fun directProcessProducesEvidenceOnlyAfterStableIdentity() {
        val source = Source()
        val captured = LauncherIdentity.capture(source).getOrThrow()
        assertTrue(captured.configurationEvidence().mayReadConfiguration())
        assertEquals(process.startTicks, captured.process.startTicks)
        assertEquals(2, source.processReads)
        assertEquals(2, source.applicationReads)
        assertEquals(1, source.digestReads)
    }

    @Test fun spawnerUnknownExecutableSubprocessAndInvalidGenerationStopBeforeApplicationRead() {
        for (invalid in listOf(process.copy(executable = "/system_ext/bin/hyos_spawner"),
            process.copy(executable = "/system_ext/bin/unknown"), process.copy(executable = "/system/bin/app_process32"),
            process.copy(command = "com.miui.home:remote"), process.copy(command = "usap64"),
            process.copy(pid = 0), process.copy(uid = 1000), process.copy(startTicks = 0), process.copy(is64Bit = false))) {
            val source = Source().apply { processValue = invalid }
            assertTrue(LauncherIdentity.capture(source).isFailure)
            assertEquals(0, source.applicationReads)
            assertEquals(0, source.digestReads)
        }
    }

    @Test fun artApexExecutableAndSecondaryUserCanMatchWithoutHardcodedUid() {
        val source = Source().apply {
            processValue = process.copy(uid = 1010001, executable = "/apex/com.android.art/bin/app_process64")
            applicationValue = application.copy(uid = 1010001)
        }
        assertTrue(LauncherIdentity.capture(source).isSuccess)
    }

    @Test fun metadataMismatchNeverReadsLargeImage() {
        for (invalid in listOf(application.copy(packageName = "other"), application.copy(processName = "other"),
            application.copy(uid = 10002), application.copy(hasCode = true),
            application.copy(hyperOsPackage = false), application.copy(entryLibrary = "other.so"),
            application.copy(sourceApk = "relative.apk"), application.copy(sourceStamp = ""),
            application.copy(lastUpdateTime = -1), application.copy(hasSplits = true),
            application.copy(sharedUserId = "android.uid.system"))) {
            val source = Source().apply { applicationValue = invalid }
            assertTrue(LauncherIdentity.capture(source).isFailure)
            assertEquals(0, source.digestReads)
        }
    }

    @Test fun malformedImageDoesNotProduceEvidence() {
        assertTrue(LauncherIdentity.capture(Source().apply { compatible = false }).isFailure)
    }

    @Test fun hostVersionDoesNotDecideCompatibility() {
        for (version in listOf(1L, 801027726L, 801027751L, Long.MAX_VALUE)) {
            val source = Source().apply { applicationValue = application.copy(version = version) }
            assertTrue(LauncherIdentity.capture(source).getOrThrow().configurationEvidence().mayReadConfiguration())
        }
    }

    @Test fun processChangesOrPackageReplacementDuringHashingAreRejected() {
        for (changed in listOf(process.copy(pid = 101), process.copy(uid = 10002),
            process.copy(startTicks = 54322), process.copy(command = "other"))) {
            assertTrue(LauncherIdentity.capture(Source().apply { processAfter = changed }).isFailure)
        }
        for (changed in listOf(application.copy(sourceStamp = "changed"), application.copy(lastUpdateTime = 2),
            application.copy(sourceApk = "/data/app/updated/base.apk"))) {
            assertTrue(LauncherIdentity.capture(Source().apply { applicationAfter = changed }).isFailure)
        }
    }

    @Test fun sourceFailureDoesNotCachePreviousSuccess() {
        assertTrue(LauncherIdentity.capture(Source()).isSuccess)
        val failing = object : LauncherIdentitySource {
            override fun process(): LauncherProcessSnapshot = error("模拟读取失败")
            override fun application(): LauncherApplicationSnapshot = error("不得调用")
            override fun imageCompatible(sourceApk: String): Boolean = error("不得调用")
        }
        assertTrue(LauncherIdentity.capture(failing).isFailure)
    }

    @Test fun statParserUsesField22DespiteSpacesAndClosingParenthesesInName() {
        val fields = MutableList(50) { "0" }.apply { this[0] = "S"; this[19] = "54321" }
        val stat = "100 (name ) with ( parentheses) ${fields.joinToString(" ")}\n"
        assertEquals(54321L, LauncherIdentity.startTicks(stat, 100))
        assertTrue(runCatching { LauncherIdentity.startTicks(stat, 101) }.isFailure)
        assertTrue(runCatching { LauncherIdentity.startTicks("100 (truncated) S 1", 100) }.isFailure)
        for (value in listOf("0", "-1", "invalid", "9223372036854775808")) {
            fields[19] = value
            assertTrue(runCatching { LauncherIdentity.startTicks("100 (name) ${fields.joinToString(" ")}", 100) }.isFailure)
        }
    }

    @Test fun commandLineRequiresNullTerminationAndNoOtherArguments() {
        assertEquals("com.miui.home", LauncherIdentity.commandName("com.miui.home\u0000\u0000".toByteArray()))
        for (raw in listOf(byteArrayOf(), byteArrayOf(0), "com.miui.home".toByteArray(),
            "com.miui.home\u0000arg".toByteArray(), byteArrayOf(-1, 0))) {
            assertTrue(runCatching { LauncherIdentity.commandName(raw) }.isFailure)
        }
    }

    @Test fun boundedReadsRejectOversizeAndNoProgress() {
        assertArrayEquals(byteArrayOf(1, 2), LauncherIdentity.boundedBytes(ByteArrayInputStream(byteArrayOf(1, 2)), 2))
        assertTrue(runCatching { LauncherIdentity.boundedBytes(ByteArrayInputStream(ByteArray(3)), 2) }.isFailure)
        val stalled = object : InputStream() {
            override fun read(): Int = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
        }
        assertTrue(runCatching { LauncherIdentity.boundedBytes(stalled, 2) }.isFailure)
    }

    @Test fun apkWithoutReviewedManifestAndImageOrWithDexCannotMatch() {
        for (names in listOf(emptyList(), listOf("classes.dex"), listOf("classes2.dex"),
            listOf(LauncherHostContract.IMAGE_ENTRY), listOf("AndroidManifest.xml"))) {
            val apk = temporary.newFile()
            ZipOutputStream(apk.outputStream()).use { output ->
                names.forEach { name -> output.putNextEntry(ZipEntry(name)); output.write(1); output.closeEntry() }
            }
            assertTrue(runCatching { LauncherIdentity.apkImageCompatible(apk.path) }.isFailure)
        }
    }

    @Test fun elfArchitectureNotArchiveFingerprintControlsContainerCheck() {
        val header = ByteArray(64).apply {
            byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 2, 1, 1).copyInto(this)
            this[16] = 3
            this[18] = 0xb7.toByte()
        }
        for (valid in listOf(true, false)) {
            val apk = temporary.newFile()
            ZipOutputStream(apk.outputStream()).use { output ->
                output.putNextEntry(ZipEntry("AndroidManifest.xml"))
                output.write(byteArrayOf(1, 2, 3))
                output.closeEntry()
                output.putNextEntry(ZipEntry(LauncherHostContract.IMAGE_ENTRY))
                output.write(header.copyOf().apply { if (!valid) this[18] = 0 })
                output.closeEntry()
            }
            assertEquals(valid, LauncherIdentity.apkImageCompatible(apk.path))
        }
    }
}
