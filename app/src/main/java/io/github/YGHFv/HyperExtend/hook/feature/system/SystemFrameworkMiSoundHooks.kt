/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.github.YGHFv.HyperExtend.hook.feature.system

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothLeAudio
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import io.github.YGHFv.HyperExtend.core.MISOUND_DEFAULT_EFFECT_KEY
import io.github.YGHFv.HyperExtend.core.MISOUND_EARPHONE_STATE_KEY
import io.github.YGHFv.HyperExtend.core.MISOUND_EFFECT_DOLBY
import io.github.YGHFv.HyperExtend.core.MISOUND_EFFECT_KEEP
import io.github.YGHFv.HyperExtend.core.MISOUND_EFFECT_MISOUND
import io.github.YGHFv.HyperExtend.core.MISOUND_EFFECT_NONE
import io.github.YGHFv.HyperExtend.core.MISOUND_REMEMBER_DEVICE_KEY
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect
import org.json.JSONObject
import java.io.File

/**
 * 系统框架端的耳机音效切换。
 *
 * 这里只负责真实切换与耳机状态广播，不向其它进程暴露 Binder。SystemUI 与 MiSound
 * 都通过 `Settings.Global.auto_effect_switch_earphone_state` 读取同一个锁定状态，
 * 这三端因此不依赖模块私有接口，也不会因为某一端先启动而丢失状态。
 */
internal object SystemFrameworkMiSoundHooks {
    private const val FEATURE = "system_framework_misound_effect"
    private const val SESSION_FILE = "/data/system/hyperextend_misound_effects.json"
    private const val REFRESH_DELAY_MS = 350L

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        if (!settings.isOn(FEATURE)) return 0
        val context = systemContext()
        if (context == null) {
            ModuleLog.warn("misound_effect: system context unavailable; automatic switching skipped")
            return 0
        }
        val controller = EffectController(context, loader)
        if (!controller.available) {
            ModuleLog.warn("misound_effect: no supported audio effect backend")
            return 0
        }
        val policy = Policy.from(settings)
        val memory = DeviceEffectMemory(File(SESSION_FILE))
        val bridge = AudioStateBridge(
            context = context,
            controller = controller,
            policy = policy,
            memory = memory,
        )
        bridge.start()
        ModuleLog.info(
            "misound_effect: receiver installed (fw=${controller.frameworkEffect}, " +
                "lock=${policy.lockSelection}, remember=${policy.rememberDevice}, default=${policy.defaultEffect})",
        )
        return 1
    }

    private fun systemContext(): Context? {
        val appGlobals = Reflect.attempt {
            Class.forName("android.app.AppGlobals")
                .getDeclaredMethod("getInitialApplication")
                .invoke(null) as? Context
        }
        if (appGlobals != null) return appGlobals
        return Reflect.attempt {
            val threadClass = Class.forName("android.app.ActivityThread")
            val thread = threadClass.getDeclaredMethod("currentActivityThread").invoke(null)
            threadClass.getDeclaredMethod("getSystemContext").invoke(thread) as? Context
        }
    }

    private data class Policy(
        val lockSelection: Boolean,
        val rememberDevice: Boolean,
        val defaultEffect: String,
    ) {
        companion object {
            fun from(settings: HookSettings): Policy = Policy(
                lockSelection = settings.string(
                    io.github.YGHFv.HyperExtend.core.MISOUND_LOCK_SELECTION_KEY,
                ).ifBlank { "1" } != "0",
                rememberDevice = settings.string(MISOUND_REMEMBER_DEVICE_KEY).ifBlank { "0" } == "1",
                defaultEffect = settings.string(MISOUND_DEFAULT_EFFECT_KEY)
                    .takeIf { it in supportedEffects }
                    ?: MISOUND_EFFECT_NONE,
            )
        }
    }

    private class AudioStateBridge(
        private val context: Context,
        private val controller: EffectController,
        private val policy: Policy,
        private val memory: DeviceEffectMemory,
    ) {
        private val handler = Handler(Looper.getMainLooper())
        private val audioManager = context.getSystemService(AudioManager::class.java)
        private var connected = false
        private var deviceId: String? = null
        private var speakerEffect: String? = null
        private var started = false

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                scheduleRefresh()
            }
        }

        private val refresh = Runnable { refreshFromAudioManager(initial = false) }

        fun start() {
            if (started) return
            started = true
            val filter = IntentFilter().apply {
                addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED)
                addAction(AudioManager.ACTION_HEADSET_PLUG)
            }
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            refreshFromAudioManager(initial = true)
        }

        private fun scheduleRefresh() {
            handler.removeCallbacks(refresh)
            handler.postDelayed(refresh, REFRESH_DELAY_MS)
        }

        private fun refreshFromAudioManager(initial: Boolean) {
            val device = currentEarphoneDevice()
            val nextId = device?.stableId()
            when {
                nextId == null && connected -> onDisconnected()
                nextId != null && !connected -> onConnected(nextId, initial)
                nextId != null && nextId != deviceId -> {
                    onDisconnected()
                    onConnected(nextId, initial = false)
                }
            }
            connected = nextId != null
            deviceId = nextId
            writeState(connected)
        }

        private fun onConnected(id: String, initial: Boolean) {
            val current = controller.currentEffect()
            if (!initial) {
                speakerEffect = current
                if (policy.rememberDevice) memory.saveSpeakerEffect(current)
            }
            val remembered = if (policy.rememberDevice) memory.effectForDevice(id) else null
            val target = remembered ?: policy.defaultEffect
            if (!initial && target != MISOUND_EFFECT_KEEP) controller.applyEffect(target)
            ModuleLog.info(
                "misound_effect: connected id=$id initial=$initial remembered=$remembered target=$target",
            )
        }

        private fun onDisconnected() {
            val closingId = deviceId
            if (policy.rememberDevice && closingId != null) {
                memory.saveDeviceEffect(closingId, controller.currentEffect())
            }
            val restore = if (policy.rememberDevice) {
                memory.speakerEffect() ?: speakerEffect
            } else {
                speakerEffect
            }
            if (restore != null) controller.applyEffect(restore)
            ModuleLog.info("misound_effect: disconnected id=$closingId restore=$restore")
        }

        private fun currentEarphoneDevice(): AudioDeviceInfo? = runCatching {
            audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                ?.firstOrNull { it.type in EARPHONE_TYPES }
        }.getOrNull()

        private fun AudioDeviceInfo.stableId(): String {
            val address = runCatching { address }.getOrNull().orEmpty()
            val name = runCatching { productName?.toString() }.getOrNull().orEmpty()
            return "${type}:${address.ifBlank { name.ifBlank { id.toString() } }}"
        }

        private fun writeState(value: Boolean) {
            runCatching {
                Settings.Global.putInt(
                    context.contentResolver,
                    MISOUND_EARPHONE_STATE_KEY,
                    if (value) 1 else 0,
                )
            }.onFailure { ModuleLog.error("misound_effect: write earphone state failed", it) }
        }
    }

    private class EffectController(
        private val context: Context,
        private val loader: ClassLoader,
    ) {
        val frameworkEffect: Boolean = Reflect.attempt {
            val properties = Class.forName("android.os.SystemProperties", false, loader)
            properties.getDeclaredMethod(
                "getBoolean",
                String::class.java,
                Boolean::class.javaPrimitiveType,
            ).invoke(null, "ro.vendor.audio.fweffect", false) as? Boolean
        } == true

        private val centerClass = if (frameworkEffect) {
            Reflect.loadClass(loader, "android.media.audiofx.AudioEffectCenter")
        } else {
            null
        }
        private val miSoundClass = Reflect.loadClass(loader, "android.media.audiofx.MiSound")
        private val dolbyClass = Reflect.loadClass(loader, "com.dolby.dax.DolbyAudioEffect")
        private var center: Any? = null

        val available: Boolean
            get() = centerClass != null || miSoundClass != null || dolbyClass != null

        fun currentEffect(): String {
            if (frameworkEffect) {
                val instance = ensureCenter()
                if (instance != null && isActive(instance, MISOUND_EFFECT_DOLBY)) {
                    return MISOUND_EFFECT_DOLBY
                }
                if (instance != null && isActive(instance, MISOUND_EFFECT_MISOUND)) {
                    return MISOUND_EFFECT_MISOUND
                }
                return MISOUND_EFFECT_NONE
            }
            val dolby = createEffect(dolbyClass)
            if (dolby != null) return try {
                val enabled = Reflect.callWith(dolby, "getDsOn") as? Boolean == true
                if (enabled) MISOUND_EFFECT_DOLBY else {
                    val miSound = createEffect(miSoundClass)
                    val miEnabled = miSound?.let { Reflect.callWith(it, "getEnabled") as? Boolean == true }
                    release(miSound)
                    if (miEnabled == true) MISOUND_EFFECT_MISOUND else MISOUND_EFFECT_NONE
                }
            } finally {
                release(dolby)
            }
            val miSound = createEffect(miSoundClass)
            return try {
                if (Reflect.callWith(miSound, "getEnabled") as? Boolean == true) {
                    MISOUND_EFFECT_MISOUND
                } else {
                    MISOUND_EFFECT_NONE
                }
            } finally {
                release(miSound)
            }
        }

        fun applyEffect(effect: String) {
            when (effect) {
                MISOUND_EFFECT_KEEP -> Unit
                MISOUND_EFFECT_DOLBY, MISOUND_EFFECT_MISOUND -> setMainEffect(effect)
                else -> setMainEffect(MISOUND_EFFECT_NONE)
            }
        }

        private fun setMainEffect(effect: String) {
            if (frameworkEffect) {
                val instance = ensureCenter()
                if (instance == null) {
                    ModuleLog.warn("misound_effect: AudioEffectCenter unavailable")
                    return
                }
                val presenter = Reflect.readField(instance, "mPresenter")
                if (effect == MISOUND_EFFECT_NONE && presenter != null) {
                    Reflect.callWith(presenter, "setEffectDeactivate")
                } else {
                    Reflect.callWith(instance, "setEffectActive", effect, true)
                }
                return
            }

            val dolby = createEffect(dolbyClass)
            val miSound = createEffect(miSoundClass)
            runCatching {
                if (dolby != null) Reflect.callWith(dolby, "setDsOn", effect == MISOUND_EFFECT_DOLBY)
                if (miSound != null) Reflect.callWith(miSound, "setEnabled", effect == MISOUND_EFFECT_MISOUND)
            }.onFailure { ModuleLog.error("misound_effect: apply $effect failed", it) }
            release(miSound)
            release(dolby)
        }

        private fun ensureCenter(): Any? {
            center?.let { return it }
            val clazz = centerClass ?: return null
            val method = Reflect.findMethod(clazz, "getInstance", Context::class.java)
            center = method?.let { Reflect.attempt { it.invoke(null, context) } }
            return center
        }

        private fun isActive(center: Any, effect: String): Boolean =
            Reflect.callWith(center, "isEffectActive", effect) as? Boolean == true

        private fun createEffect(clazz: Class<*>?): Any? {
            if (clazz == null) return null
            val constructor = clazz.declaredConstructors.firstOrNull {
                it.parameterTypes.contentEquals(
                    arrayOf(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
                )
            } ?: return null
            return Reflect.attempt {
                constructor.isAccessible = true
                constructor.newInstance(0, 0)
            }
        }

        private fun release(instance: Any?) {
            if (instance != null) Reflect.callWith(instance, "release")
        }
    }

    private class DeviceEffectMemory(private val file: File) {
        private val root: JSONObject = runCatching {
            if (file.isFile) JSONObject(file.readText()) else JSONObject()
        }.getOrElse { JSONObject() }

        @Synchronized
        fun effectForDevice(id: String): String? =
            root.optJSONObject("devices")?.optString(id)?.takeIf { it in supportedEffects }

        @Synchronized
        fun saveDeviceEffect(id: String, effect: String) {
            val devices = root.optJSONObject("devices") ?: JSONObject().also { root.put("devices", it) }
            devices.put(id, effect)
            persist()
        }

        @Synchronized
        fun speakerEffect(): String? =
            root.optString("speaker").takeIf { it in supportedEffects }

        @Synchronized
        fun saveSpeakerEffect(effect: String) {
            root.put("speaker", effect)
            persist()
        }

        @Synchronized
        private fun persist() {
            runCatching {
                file.parentFile?.mkdirs()
                val temporary = File(file.path + ".tmp")
                temporary.writeText(root.toString())
                if (!temporary.renameTo(file)) {
                    file.writeText(root.toString())
                    temporary.delete()
                }
            }.onFailure { ModuleLog.error("misound_effect: persist device memory failed", it) }
        }
    }

    private val EARPHONE_TYPES = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
    )

    private val supportedEffects = setOf(
        MISOUND_EFFECT_NONE,
        MISOUND_EFFECT_DOLBY,
        MISOUND_EFFECT_MISOUND,
        MISOUND_EFFECT_KEEP,
    )
}
