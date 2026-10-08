/*
 * Copyright (C) 2026 zhhhyyyyyy (HyperVolumeANC)
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: Apache-2.0
 * Adapted from MediaPlaybackWatcher; callbacks are owned by each attached view.
 */
package io.github.YGHFv.HyperExtend.hook.feature.volume

import android.app.KeyguardManager
import android.content.Context
import android.media.AudioManager
import io.github.YGHFv.HyperExtend.hook.Reflect

internal object MediaPlayback {
    fun locked(context: Context): Boolean = Reflect.attempt {
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked
    } != false

    fun active(context: Context): Boolean = Reflect.attempt {
        context.getSystemService(AudioManager::class.java)?.activePlaybackConfigurations?.any { config ->
            Reflect.attempt {
                val uid = config.javaClass.getDeclaredMethod("getClientUid").invoke(config) as Int
                val state = config.javaClass.getDeclaredMethod("getPlayerState").invoke(config) as Int
                val attrs = config.audioAttributes
                AppVolumePolicy.activeMedia(uid, state, attrs.usage, attrs.volumeControlStream,
                    context.packageManager.getPackagesForUid(uid)?.toList().orEmpty())
            } == true
        }
    } == true
}
