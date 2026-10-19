/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.os.UserManager
import io.github.YGHFv.HyperExtend.core.AudioMuteSettings
import io.github.YGHFv.HyperExtend.hook.Reflect

/**
 * 「静音模式」磁贴 —— 上游「雪豹模式」的有界等价物。
 *
 * 上游把音频 HAL 杀掉再把 `/dev/snd/pcm*` chmod 000；关闭时 chmod 回硬编码的 0660。
 * 那条路有两个不可接受的缺陷（见 [AudioMuteSettings]）：恢复用的权限位是硬编码的，
 * 而「当前处于雪豹模式」只存在进程字段里 —— 进程一重启，节点还是 000，模块却以为没开过。
 *
 * 这里改用系统自己的主静音：
 * `AudioManager.setMasterMute(Z, I)` → `IAudioService.setMasterMute`，
 * 其 `_enforcePermission()` 校验的正是 `MODIFY_AUDIO_ROUTING`，而真机
 * `dumpsys package com.android.systemui` 已确认该权限 granted=true。
 * 于是不需要 root、不改任何节点权限、不杀 HAL，而且随时可以解静音。
 *
 * 主静音是运行期标志（真机 `volume_master_mute` 三个设置域都是 null），**不落盘**：
 * 重启即自动解除。这一点在这里是优点 —— 上游那种「面板停在禁用状态而进程忘了」的死局
 * 被结构性消除。代价是不覆盖原生排除的闹钟/通话等流，也不等价于「杀死音频 HAL」本身。
 */
internal object AudioMuteHooks {
    /**
     * `isMasterMute()` / `setMasterMute(Z,I)` 在 android.jar 里的 SDK 存根中不存在（@hide），
     * 所以编译期只能反射。真机 `dumpsys package com.android.systemui` 的
     * `hiddenApiEnforcementPolicy=0` 表示该系统应用不受非 SDK 接口限制；
     * 拿不到方法时 [supported] 直接返回 false，磁贴不显示，而不是给一个点了没反应的入口。
     */
    private fun isMasterMute(audio: AudioManager): Boolean? = runCatching {
        Reflect.findMethod(AudioManager::class.java, "isMasterMute")?.invoke(audio) as? Boolean
    }.getOrNull()

    private fun setMasterMute(audio: AudioManager, enabled: Boolean): Boolean = runCatching {
        val method = Reflect.findMethod(AudioManager::class.java, "setMasterMute",
            java.lang.Boolean.TYPE, java.lang.Integer.TYPE)
            ?: return false
        method.invoke(audio, enabled, 0)
        true
    }.getOrDefault(false)

    private val adapter = NativeToggleTileHooks(object : NativeToggleBackend {
        override val feature = AudioMuteSettings.FEATURE
        override val spec = AudioMuteSettings.SPEC
        override val label = AudioMuteSettings.LABEL
        override val iconName = AudioMuteSettings.ICON
        override val settingsComponent: ComponentName? =
            ComponentName("com.android.settings", "com.android.settings.MiuiSoundSettingsActivity")
        override fun supported(context: Context): Boolean {
            val user = context.getSystemService(UserManager::class.java) ?: return false
            // 主静音与音频输出直连，受管资料不允许在这里改。
            if (user.isManagedProfile) return false
            val audio = context.getSystemService(AudioManager::class.java) ?: return false
            return isMasterMute(audio) != null
        }
        override fun read(context: Context): Boolean? =
            context.getSystemService(AudioManager::class.java)?.let(::isMasterMute)
        override fun write(context: Context, enabled: Boolean) {
            val audio = requireNotNull(context.getSystemService(AudioManager::class.java))
            check(setMasterMute(audio, enabled)) { "AudioManager#setMasterMute unavailable" }
            // 原生 setter 经 binder 异步落地：立刻回读可能还是旧值，由调用方的事务确认收尾。
        }
    })
    private val editor = NativeToggleTileEditorHooks(AudioMuteSettings.FEATURE, AudioMuteSettings.SPEC) { adapter.ready }
    fun install(loader: ClassLoader) = adapter.install(loader) + editor.installClassic(loader)
    fun installPlugin(loader: ClassLoader) = editor.installPlugin(loader)
}
