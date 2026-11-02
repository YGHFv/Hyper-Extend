/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.volume

import org.junit.Assert.*
import org.junit.Test

class AppVolumePluginTargetsTest {
    class CurrentDialog {
        fun `lambda$dismissH$0`(callback: Runnable) = callback.run()
        companion object {
            @JvmStatic fun a(dialog: CurrentDialog, callback: Runnable) = dialog.`lambda$dismissH$0`(callback)
        }
    }
    class Os3Dialog
    class Controller {
        fun `lambda$dismissVolumePanel$9`() = Unit
        companion object {
            @JvmStatic fun a(controller: Controller) = controller.`lambda$dismissVolumePanel$9`()
        }
    }
    class Unknown
    private val loader = javaClass.classLoader!!

    @Test fun currentDismissStillRestoresBeforeDialogCompletion() {
        val targets = AppVolumePluginTargets.resolve(loader, CurrentDialog::class.java, Controller::class.java)
        assertFalse(targets.os3)
        assertEquals(CurrentDialog::class.java, targets.finish.declaringClass)
        assertEquals(listOf(Runnable::class.java), targets.finish.parameterTypes.toList())
        assertEquals(listOf("run", "a"), targets.finishCallers.map { it.name })
        assertEquals(listOf("onMaterialModeChanged", "updateResources"), targets.resourceUpdates)
    }

    @Test fun os3DismissUsesControllerCompletionAndItsBridge() {
        val targets = AppVolumePluginTargets.resolve(loader, Os3Dialog::class.java, Controller::class.java)
        assertTrue(targets.os3)
        assertEquals(Controller::class.java, targets.finish.declaringClass)
        assertEquals("lambda\$dismissVolumePanel\$9", targets.finish.name)
        assertEquals(0, targets.finish.parameterCount)
        assertEquals(listOf(Controller::class.java), targets.finishCallers.last().parameterTypes.toList())
        assertEquals(listOf("updateResources"), targets.resourceUpdates)
    }

    @Test fun missingNativeCompletionFailsClosed() {
        assertTrue(runCatching {
            AppVolumePluginTargets.resolve(loader, Os3Dialog::class.java, Unknown::class.java)
        }.isFailure)
    }

    @Test fun os3CompletionLoaderAndButtonMotionHaveReadOnlyHostEvidence() {
        val signatures = javaClass.getResourceAsStream("/app-volume-os3-host-members.txt")!!
            .bufferedReader().use { it.readLines().toSet() }
        val controller = "Lcom/android/systemui/miui/volume/VolumePanelViewController;"
        val factory = "Lcom/android/systemui/shared/plugins/PluginInstance\$PluginFactory;"
        val supplier = "Lcom/android/systemui/shared/plugins/PluginInstance\$Factory\$\$ExternalSyntheticLambda0;"
        for (signature in listOf(
            "instance $controller->lambda\$dismissVolumePanel\$9()V",
            "static $controller->a($controller)V",
            "instance Lcom/android/systemui/miui/volume/J;->run()V",
            "instance $factory->mClassLoaderFactory:$supplier",
            "instance $supplier->f\$1:Landroid/content/pm/ApplicationInfo;",
            "instance $supplier->get()Ljava/lang/Object;",
            "static Lmiui/systemui/util/CommonUtils;->isScreenLayoutLarge(Landroid/content/Context;)Z",
            "instance Lmiui/systemui/util/CommonUtils;->getIS_FOLD()Z",
        ) + listOf("setDndY(FF)V", "setRingerY(FF)V", "setScale(FF)V", "resetView()V").map {
            "instance Lcom/android/systemui/miui/volume/VolumePanelViewController\$3;->$it"
        } + listOf("EXPAND_SIZE", "EXPAND_POSITION", "EXPAND_COLOR", "COLLAPSE_SIZE", "COLLAPSE_POSITION", "COLLAPSE_COLOR").map {
            "static Lmiui/systemui/animation/FolmeUtilsExtKt;->getEASE_$it()Lmiuix/animation/utils/EaseManager\$EaseStyle;"
        }) assertTrue(signature, signature in signatures)
    }
}
