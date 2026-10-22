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

private val SUPPORTED_EFFECTS = setOf(
    MISOUND_EFFECT_NONE,
    MISOUND_EFFECT_DOLBY,
    MISOUND_EFFECT_MISOUND,
    MISOUND_EFFECT_KEEP,
)

/**
 * 一次耳机音效切换需要恢复的完整状态。
 *
 * [spatial] 或 [surround] 为 null 表示不干预对应子项。旧版本只记忆主音效，
 * 因此从旧 JSON 读出的状态天然只有 [main]，不会把用户后来打开的空间音频关掉。
 */
internal data class MiSoundEffectState(
    val main: String,
    val spatial: Boolean? = null,
    val surround: Boolean? = null,
)

/** 耳机切换策略保持为纯函数，便于在没有 Android 运行时的单测里覆盖兼容分支。 */
internal object MiSoundEffectPolicy {
    fun connectionTarget(
        remembered: MiSoundEffectState?,
        defaultEffect: String,
    ): MiSoundEffectState = remembered ?: MiSoundEffectState(defaultEffect)

    /** 仅框架侧 AudioEffectCenter 能读写空间音频与环绕声。 */
    fun forBackend(state: MiSoundEffectState, supportsThreeState: Boolean): MiSoundEffectState =
        if (supportsThreeState) state else state.copy(spatial = null, surround = null)

    fun legacyEffect(value: String?): MiSoundEffectState? =
        value?.takeIf { it in SUPPORTED_EFFECTS }?.let(::MiSoundEffectState)
}

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
                    .takeIf { it in SUPPORTED_EFFECTS }
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
        private var speakerState: MiSoundEffectState? = null
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
            val current = controller.currentState()
            if (!initial) {
                speakerState = current
                if (policy.rememberDevice) memory.saveSpeakerState(current)
            }
            val remembered = if (policy.rememberDevice) memory.stateForDevice(id) else null
            val target = MiSoundEffectPolicy.connectionTarget(remembered, policy.defaultEffect)
            val effective = MiSoundEffectPolicy.forBackend(target, controller.frameworkEffect)
            controller.applyState(effective)
            ModuleLog.info(
                "misound_effect: connected id=$id initial=$initial remembered=$remembered target=$effective",
            )
        }

        private fun onDisconnected() {
            val closingId = deviceId
            if (policy.rememberDevice && closingId != null) {
                memory.saveDeviceState(closingId, controller.currentState())
            }
            val restore = if (policy.rememberDevice) {
                memory.speakerState() ?: speakerState
            } else {
                speakerState
            }
            restore?.let(controller::applyState)
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

        fun currentState(): MiSoundEffectState {
            val main = currentEffect()
            if (!frameworkEffect) return MiSoundEffectState(main)
            val instance = ensureCenter() ?: return MiSoundEffectState(main)
            return MiSoundEffectState(
                main = main,
                spatial = readOptionalEffect(instance, spatialEffectKey),
                surround = readOptionalEffect(instance, surroundEffectKey),
            )
        }

        fun applyEffect(effect: String) {
            when (effect) {
                MISOUND_EFFECT_KEEP -> Unit
                MISOUND_EFFECT_DOLBY, MISOUND_EFFECT_MISOUND -> setMainEffect(effect)
                else -> setMainEffect(MISOUND_EFFECT_NONE)
            }
        }

        fun applyState(state: MiSoundEffectState) {
            applyEffect(state.main)
            if (!frameworkEffect) return
            val instance = ensureCenter() ?: return
            state.spatial?.let { setOptionalEffect(instance, spatialEffectKey, it) }
            state.surround?.let { setOptionalEffect(instance, surroundEffectKey, it) }
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

        private val spatialEffectKey: String? by lazy { staticStringField("EFFECT_SPATIAL_AUDIO") }
        private val surroundEffectKey: String? by lazy { staticStringField("EFFECT_SURROUND") }

        private fun readOptionalEffect(center: Any, effect: String?): Boolean? =
            effect?.let { isActive(center, it) }

        private fun setOptionalEffect(center: Any, effect: String?, enabled: Boolean) {
            if (effect == null) {
                ModuleLog.warn("misound_effect: optional effect constant unavailable")
                return
            }
            Reflect.callWith(center, "setEffectActive", effect, enabled)
        }

        private fun staticStringField(name: String): String? =
            centerClass?.let { Reflect.findField(it, name) }?.let {
                Reflect.attempt { it.get(null) as? String }
            }

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
        fun stateForDevice(id: String): MiSoundEffectState? {
            val devices = root.optJSONObject("devices") ?: return null
            val value = devices.opt(id)
            return when {
                value is JSONObject -> parseState(value)
                value is String -> MiSoundEffectPolicy.legacyEffect(value)
                else -> null
            }
        }

        @Synchronized
        fun saveDeviceState(id: String, state: MiSoundEffectState) {
            val devices = root.optJSONObject("devices") ?: JSONObject().also { root.put("devices", it) }
            devices.put(id, encodeState(state))
            persist()
        }

        @Synchronized
        fun speakerState(): MiSoundEffectState? {
            root.optJSONObject("speaker_state")?.let { return parseState(it) }
            return MiSoundEffectPolicy.legacyEffect(root.optString("speaker"))
        }

        @Synchronized
        fun saveSpeakerState(state: MiSoundEffectState) {
            // 保留旧字段，让降级回旧版本时仍能恢复主音效。
            root.put("speaker", state.main)
            root.put("speaker_state", encodeState(state))
            persist()
        }

        private fun parseState(value: JSONObject): MiSoundEffectState? {
            val main = value.optString("main").takeIf { it in SUPPORTED_EFFECTS } ?: return null
            return MiSoundEffectState(
                main = main,
                spatial = value.optionalBoolean("spatial"),
                surround = value.optionalBoolean("surround"),
            )
        }

        private fun encodeState(state: MiSoundEffectState): JSONObject = JSONObject().apply {
            put("main", state.main)
            state.spatial?.let { put("spatial", it) }
            state.surround?.let { put("surround", it) }
        }

        private fun JSONObject.optionalBoolean(name: String): Boolean? =
            if (has(name)) optBoolean(name) else null

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
}
