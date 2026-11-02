/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.system

import org.junit.Assert.*
import org.junit.Test

class CorePatchInstallTargetsTest {
    interface ParsingPackage {
        fun getPackageName(): String
    }
    interface ParsedPackage : ParsingPackage
    class Request
    class Helper {
        private fun doesSignatureMatchForPermissions(owner: String, pkg: ParsedPackage, flags: Int) = false
        private fun preparePackage(request: Request) = Unit
        private fun prepareInstallPackages(requests: List<Request>) = true
    }
    class WrongOverload {
        private fun doesSignatureMatchForPermissions(owner: String, pkg: Any, flags: Int) = false
        private fun preparePackage(request: Request) = Unit
        private fun prepareInstallPackages(requests: List<Request>) = true
    }
    class WrongReturn {
        private fun doesSignatureMatchForPermissions(owner: String, pkg: ParsedPackage, flags: Int) = 0
        private fun preparePackage(request: Request) = Unit
        private fun prepareInstallPackages(requests: List<Request>) = true
    }
    class MissingCaller {
        private fun doesSignatureMatchForPermissions(owner: String, pkg: ParsedPackage, flags: Int) = false
        private fun preparePackage(request: Request) = Unit
    }

    @Test fun resolvesExactPrivateTargetAndBothInstallationCallers() {
        val targets = CorePatchInstallTargets.resolve(Helper::class.java, ParsedPackage::class.java, Request::class.java)
        assertEquals(listOf(String::class.java, ParsedPackage::class.java, Int::class.javaPrimitiveType),
            targets.match.parameterTypes.toList())
        assertEquals(false, targets.match.invoke(Helper(), "com.example.app",
            object : ParsedPackage { override fun getPackageName() = "com.example.app" }, 0))
        assertEquals(listOf("preparePackage", "prepareInstallPackages"), targets.callers.map { it.name })
    }

    @Test fun packageNameGetterCanBeInheritedFromParsingInterface() {
        val targets = CorePatchInstallTargets.resolve(Helper::class.java, ParsedPackage::class.java, Request::class.java)
        assertEquals(ParsingPackage::class.java, targets.packageName.declaringClass)
        assertEquals("com.example.app", targets.packageName.invoke(
            object : ParsedPackage { override fun getPackageName() = "com.example.app" }))
    }

    @Test fun wrongOverloadsReturnTypesOrMissingCallerFailClosed() {
        for (helper in listOf(WrongOverload::class.java, WrongReturn::class.java, MissingCaller::class.java))
            assertTrue(helper.name, runCatching {
                CorePatchInstallTargets.resolve(helper, ParsedPackage::class.java, Request::class.java)
            }.isFailure)
    }

    @Test fun exactTargetsHaveHostDexMemberEvidence() {
        val fixture = javaClass.getResourceAsStream("/core-patch-install-host-members.txt")!!
            .bufferedReader().use { it.readLines().toSet() }
        val t = CorePatchInstallTargets
        fun descriptor(name: String) = "L${name.replace('.', '/')};"
        val helper = descriptor(t.HELPER)
        assertTrue("instance $helper->doesSignatureMatchForPermissions(Ljava/lang/String;${descriptor(t.PARSED)}I)Z" in fixture)
        assertTrue("instance $helper->preparePackage(${descriptor(t.REQUEST)})V" in fixture)
        assertTrue("instance $helper->prepareInstallPackages(Ljava/util/List;)Z" in fixture)
        assertTrue("instance Lcom/android/internal/pm/pkg/parsing/ParsingPackage;->getPackageName()Ljava/lang/String;" in fixture)
    }
}
