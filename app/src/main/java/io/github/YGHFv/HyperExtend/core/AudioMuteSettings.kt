/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

/**
 * 「静音模式」—— 上游「雪豹模式」在当前固件上的**有界等价物**。
 *
 * ## 上游做了什么，为什么不能照抄
 *
 * 上游 `SnowLeopardModeTile` 的开关体是
 * `kill $(pidof android.hardware.audio.service_64) && chmod 000 /dev/snd/pcm*`，
 * 关闭时再 `chmod 660` 并让 HAL 重新起来。这条路上有两个不可接受的缺陷：
 *
 * - **权限位被改成了硬编码的 0660**。真机 `/dev/snd/pcmC0D*` 现在是
 *   `0660 system:audio u:object_r:audio_device:s0`，但那是**碰巧**和硬编码值一致；
 *   换一个固件、换一套 SELinux 标签，`chmod 660` 就会把节点恢复成错误属主，
 *   而模块这一侧没有存下任何 owner/mode 快照。
 * - **进程内的布尔量活不过重启**。一旦 SystemUI 在「节点是 000」的状态下被杀掉重启，
 *   `isInSnowLeopardMode` 回到 false，节点却还是 000 —— 音频彻底哑掉且没有任何恢复路径。
 *
 * ## 当前固件里真正可用、可逆的载体
 *
 * 真机取证确认：`com.android.systemui` 持有
 * `android.permission.MODIFY_AUDIO_ROUTING`（granted=true），而 framework 里
 * `Landroid/media/AudioManager;->setMasterMute(ZI)V` →
 * `IAudioService.setMasterMute` → `AudioService` 的静音链是现成的原生接口
 * （`IAudioService$Stub.setMasterMute_enforcePermission()` 校验的正是
 * `MODIFY_AUDIO_ROUTING`）。也就是说，**不需要 root、不需要 chmod、不需要杀 HAL**，
 * SystemUI 自己就能把全部音频输出静音，而且随时可以解静音。
 *
 * 这仍然不是「杀死音频 HAL」本身：主静音不覆盖闹钟/通话等被原生排除的流，
 * 也**不落盘**（`volume_master_mute` 在真机上为空，说明它是运行期状态）。
 * 后者在本功能里是**优点**：重启即解除，不存在上游那种「节点停在 000 而进程忘了自己开过」的死局。
 *
 * ## 为什么不与其它卡片合并
 *
 * 本功能是「一键静音全部输出」，与「亮屏时静音」「勿扰修复」「音量面板」都不共用任何一个
 * 宿主位置或设置键，属于独立的一键开关，因此单独成卡。
 *
 * ## 为什么不给「关闭时恢复」做一个选项
 *
 * 磁贴的 on/off 就是主静音的真实状态：关掉磁贴必须解静音，否则磁贴显示为关而系统还在静音，
 * 这正是不该被配置出来的状态。所以这里没有从属开关，也就没有详情页 ——
 * 「一键静音 / 解除静音」两件事各占磁贴的一个状态，不存在第二个实现方式可以选。
 */
object AudioMuteSettings {
    const val FEATURE = "systemui_snow_leopard_mute"

    /** 磁贴 spec：与上游 `custom_SnowLeopardMode` 不同名，避免与宿主同名磁贴冲突。 */
    const val SPEC = "hyperextend_mute"
    const val LABEL = "静音模式"
    const val ICON = "ic_qs_mute_mode"
}
