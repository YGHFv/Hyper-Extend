/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.github.YGHFv.HyperExtend.hook.feature.system

import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.core.SYSTEM_FRAMEWORK_DEFAULT_VOLUME_STREAM
import io.github.YGHFv.HyperExtend.core.SYSTEM_FRAMEWORK_VOLUME_MEDIA_STEPS
import io.github.YGHFv.HyperExtend.core.SYSTEM_FRAMEWORK_VOLUME_SAFE_MODE
import io.github.YGHFv.HyperExtend.core.SYSTEM_FRAMEWORK_VOLUME_STEPS
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect
import kotlin.math.roundToInt

/** 系统框架音量功能。 */
internal object SystemFrameworkVolumeHooks {
    private const val FEATURE_DEFAULT_STREAM = "system_framework_volume_default_stream"
    private const val FEATURE_FIRST_PRESS = "system_framework_volume_first_press"
    private const val FEATURE_STEPS = "system_framework_volume_steps"
    private const val FEATURE_MEDIA_STEPS = "system_framework_volume_media_steps"
    private const val FEATURE_SAFE = "system_framework_volume_disable_safe"

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        var count = 0
        if (settings.isOn(FEATURE_DEFAULT_STREAM)) count += installDefaultStream(loader, settings)
        if (settings.isOn(FEATURE_FIRST_PRESS)) count += installFirstPress(loader)
        if (settings.isOn(FEATURE_STEPS)) count += installSteps(loader, settings)
        if (settings.isOn(FEATURE_MEDIA_STEPS)) count += installMediaSteps(loader, settings)
        if (settings.isOn(FEATURE_SAFE)) count += installSafeVolume(loader, settings)
        return count
    }

    private fun installDefaultStream(loader: ClassLoader, settings: HookSettings): Int {
        val stream = settings.string(SYSTEM_FRAMEWORK_DEFAULT_VOLUME_STREAM).toIntOrNull() ?: 0
        if (stream == 0) return 0
        val audio = Reflect.loadClass(loader, "com.android.server.audio.AudioService")
        val method = audio?.let {
            Reflect.findMethods(it, "getActiveStreamType", paramCount = 1)
                .firstOrNull { candidate -> candidate.returnType == Int::class.javaPrimitiveType }
        }
        if (method == null) {
            ModuleLog.warn("volume_default_stream: AudioService#getActiveStreamType not found")
            return 0
        }
        return if (HookRuntime.hookReturning(method, "volume_default_stream/AudioService#getActiveStreamType", stream)) {
            1
        } else {
            0
        }
    }

    private fun installFirstPress(loader: ClassLoader): Int {
        val controller = Reflect.loadClass(loader, "com.android.server.audio.AudioService\$VolumeController")
        val method = controller?.let {
            Reflect.findMethods(it, "suppressAdjustment", paramCount = 3)
                .firstOrNull { candidate -> candidate.returnType == Boolean::class.javaPrimitiveType }
        }
        if (method == null) {
            ModuleLog.warn("volume_first_press: VolumeController#suppressAdjustment not found")
            return 0
        }
        return if (HookRuntime.hook(method, "volume_first_press/VolumeController#suppressAdjustment") { chain ->
                val original = chain.proceed()
                val stream = chain.args.getOrNull(0) as? Int
                val muteAdjust = chain.args.getOrNull(2) as? Boolean ?: false
                val controllerObject = Reflect.readField(chain.thisObject, "mController")
                if (stream == 3 && !muteAdjust && controllerObject != null) false else original
            }
        ) 1 else 0
    }

    private fun installSteps(loader: ClassLoader, settings: HookSettings): Int {
        val percent = settings.number(SYSTEM_FRAMEWORK_VOLUME_STEPS, 100, 25, 500)
        if (percent == 100) return 0
        val audio = Reflect.loadClass(loader, "com.android.server.audio.AudioService")
        val method = audio?.let {
            Reflect.findMethods(it, "createStreamStates", paramCount = 0).firstOrNull()
        }
        val field = audio?.let { Reflect.findField(it, "MAX_STREAM_VOLUME") }
        if (method == null || field == null) {
            ModuleLog.warn("volume_steps: AudioService#createStreamStates or MAX_STREAM_VOLUME not found")
            return 0
        }
        return if (HookRuntime.hook(method, "volume_steps/AudioService#createStreamStates") { chain ->
                val result = chain.proceed()
                val maxVolumes = Reflect.attempt { field.get(null) as? IntArray }
                if (maxVolumes != null) {
                    for (index in maxVolumes.indices) {
                        maxVolumes[index] = (maxVolumes[index] * percent / 100.0f).roundToInt()
                    }
                }
                result
            }
        ) 1 else 0
    }

    private fun installMediaSteps(loader: ClassLoader, settings: HookSettings): Int {
        val desired = settings.number(SYSTEM_FRAMEWORK_VOLUME_MEDIA_STEPS, 15, 15, 29)
        if (desired <= 15) return 0
        val clazz = Reflect.loadClass(loader, "android.os.SystemProperties")
        val method = clazz?.let {
            Reflect.findMethod(
                it,
                "getInt",
                String::class.java,
                Int::class.javaPrimitiveType!!,
            )
        }
        if (method == null) {
            ModuleLog.warn("volume_media_steps: SystemProperties#getInt not found")
            return 0
        }
        return if (HookRuntime.hook(method, "volume_media_steps/SystemProperties#getInt") { chain ->
                val original = chain.proceed()
                val key = chain.args.getOrNull(0) as? String
                val current = original as? Int ?: return@hook original
                if (key == "ro.config.media_vol_steps" && desired > current) desired else original
            }
        ) 1 else 0
    }

    private fun installSafeVolume(loader: ClassLoader, settings: HookSettings): Int {
        val mode = settings.string(SYSTEM_FRAMEWORK_VOLUME_SAFE_MODE).toIntOrNull() ?: 0
        if (mode == 0) return 0
        var count = 0
        for (className in listOf(
            "com.android.server.audio.SoundDoseHelperStubImpl",
            "com.android.server.audio.SoundDoseHelper",
        )) {
            val clazz = Reflect.loadClass(loader, className) ?: continue
            for (name in listOf("updateSafeMediaVolumeIndex", "safeMediaVolumeIndex")) {
                for (method in Reflect.findMethods(clazz, name, paramCount = 1)) {
                    if (method.returnType == Int::class.javaPrimitiveType &&
                        HookRuntime.hookReturning(
                            method,
                            "volume_safe/$className#$name",
                            Int.MAX_VALUE - 1,
                        )
                    ) count++
                }
            }
        }
        return count
    }
}
