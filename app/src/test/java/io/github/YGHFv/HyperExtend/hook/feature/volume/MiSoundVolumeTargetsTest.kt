/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.volume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiSoundVolumeTargetsTest {
    class Controller
    class CurrentService { @JvmField var e: Controller? = null }
    class Os3Service {
        @JvmField var d: Controller? = null
        @JvmField var e: String = "VolumeUIService"
    }
    class AmbiguousService {
        @JvmField var d: Controller? = null
        @JvmField var e: Controller? = null
    }
    class WrongService { @JvmField var d: Any? = null }
    class StaticService { companion object { @JvmField var d: Controller? = null } }

    @Test fun currentMappingIsPreserved() {
        val (layout, field) = MiSoundVolumeTargets.resolve(CurrentService::class.java, Controller::class.java)
        assertEquals(MiSoundVolumeTargets.current, layout)
        assertEquals("e", field.name)
        assertEquals("z.m", layout.postureClass)
        assertEquals("m", layout.postureMethod)
        assertEquals("e", layout.adapterPages)
        assertEquals("g", layout.adapterController)
        assertEquals("e", layout.holderItem)
    }

    @Test fun os3UsesAuditedFieldsAndPostureMethod() {
        val (layout, field) = MiSoundVolumeTargets.resolve(Os3Service::class.java, Controller::class.java)
        assertEquals(MiSoundVolumeTargets.os3, layout)
        assertEquals("d", field.name)
        assertEquals("H.j", layout.postureClass)
        assertEquals("j", layout.postureMethod)
        assertEquals("d", layout.adapterPages)
        assertEquals("f", layout.adapterController)
        assertEquals("d", layout.holderItem)
    }

    @Test fun unknownAmbiguousAndStaticFieldsFailClosed() {
        for (service in listOf(WrongService::class.java, AmbiguousService::class.java, StaticService::class.java)) {
            assertTrue(runCatching { MiSoundVolumeTargets.resolve(service, Controller::class.java) }.isFailure)
        }
    }

    @Test fun os3NamesHaveReadOnlyHostEvidence() {
        val signatures = javaClass.getResourceAsStream("/app-volume-os3-host-members.txt")!!
            .bufferedReader().use { it.readLines().toSet() }
        val targets = MiSoundVolumeTargets.os3
        val controller = "Lcom/miui/misound/playervolume/a;"
        for (signature in listOf(
            "instance Lcom/miui/misound/playervolume/VolumeUIService;->${targets.serviceController}:$controller",
            "static L${targets.postureClass.replace('.', '/')};->${targets.postureMethod}(Landroid/content/ContentResolver;)Z",
            "instance Lcom/miui/misound/playervolume/a\$i;->${targets.adapterPages}:Ljava/util/List;",
            "instance Lcom/miui/misound/playervolume/a\$i;->${targets.adapterController}:$controller",
            "instance Lcom/miui/misound/playervolume/a\$i\$a;->${targets.holderItem}:Landroid/view/ViewGroup;",
            "instance $controller->i:Ljava/lang/reflect/Method;",
            "instance $controller->w(Ljava/lang/String;F)V",
        )) assertTrue(signature, signature in signatures)
    }
}
