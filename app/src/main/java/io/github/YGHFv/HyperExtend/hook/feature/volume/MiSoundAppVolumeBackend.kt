/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.volume

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.media.AudioPlaybackConfiguration
import android.os.Bundle
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.lang.reflect.Method
import java.util.UUID

/** MiSound 260903's exact per-player setter plus its own persistence path; never setStreamVolume. */
internal class MiSoundAppVolumeBackend(loader: ClassLoader, private val controller: Class<*>) {
    private val column = Class.forName(controller.name + "\$h", false, loader)
    private val ctor = column.getDeclaredConstructor(controller, AudioPlaybackConfiguration::class.java).apply { isAccessible = true }
    private val volume = column.getDeclaredField("h").apply { isAccessible = true }
    private val packageField = column.getDeclaredField("f").apply { isAccessible = true }
    private val icon = column.getDeclaredField("g").apply { isAccessible = true }
    private val audio = controller.getDeclaredField("e").apply { isAccessible = true }
    private val setter = controller.getDeclaredField("i").apply { isAccessible = true }
    private val persist = controller.getDeclaredMethod("w", String::class.java, Float::class.javaPrimitiveType).apply { isAccessible = true }
    private val status = controller.getDeclaredField("a").apply { isAccessible = true }
    private val ball = controller.getDeclaredField("m").apply { isAccessible = true }
    private val remove = controller.getDeclaredMethod("D", android.view.View::class.java).apply { isAccessible = true }
    private val nativeSources = Class.forName("com.miui.misound.playervolume.f", false, loader)
        .getDeclaredMethod("a", Context::class.java).apply { isAccessible = true }
    private val disabled = Class.forName("z.m", false, loader).getDeclaredMethod("m", android.content.ContentResolver::class.java)
        .apply { isAccessible = true }
    private val lease = AppVolumeBridgeLease()
    private var identities = emptyMap<Int, String>()

    fun clear() { lease.close(); identities = emptyMap() }
    fun active() = lease.accepts(lease.token, SystemClock.elapsedRealtime())

    private fun sources(host: Service): List<AudioPlaybackConfiguration> =
        (nativeSources.invoke(null, host) as? List<*>)?.filterIsInstance<AudioPlaybackConfiguration>().orEmpty().filter {
            val uid = uid(it)
            val packages = host.packageManager.getPackagesForUid(uid)?.toList().orEmpty()
            AppVolumeBridgePolicy.writable(uid, host.applicationInfo.uid, packages, packages.firstOrNull().orEmpty())
        }
    private fun uid(config: AudioPlaybackConfiguration) = config.javaClass.getMethod("getClientUid").invoke(config) as Int

    fun handle(host: Service, target: Any, intent: Intent): Bundle? {
        val now = SystemClock.elapsedRealtime()
        val token = intent.getStringExtra(AppVolumeBridge.TOKEN)
        if (intent.action == AppVolumeBridge.END) {
            if (lease.accepts(token, now)) clear()
            return null
        }
        val unavailable = AppVolumeBridgePolicy.unavailableReason(MediaPlayback.locked(host),
            host.getSystemService(android.os.PowerManager::class.java).isInteractive,
            disabled.invoke(null, host.contentResolver) == true)
        if (unavailable != null) {
            clear(); return AppVolumeBridge.failure(unavailable)
        }
        val active = sources(host)
        if (intent.action == AppVolumeBridge.QUERY) {
            if (token == null && active()) return AppVolumeBridge.failure("busy")
            if (token != null && !lease.accepts(token, now)) return AppVolumeBridge.failure("expired")
            // Do not replace a native MiSound panel that is already being used or animated.
            if (status.getInt(target) !in listOf(0, 2000)) return AppVolumeBridge.failure("native_busy")
            val apps = active.distinctBy(::uid).take(AppVolumeBridgePolicy.MAX_APPS).mapNotNull { config ->
                val data = ctor.newInstance(target, config)
                val pkg = packageField.get(data) as? String ?: return@mapNotNull null
                val id = uid(config)
                if (!AppVolumeBridgePolicy.writable(id, host.applicationInfo.uid,
                        host.packageManager.getPackagesForUid(id)?.toList().orEmpty(), pkg)) return@mapNotNull null
                val ratio = volume.getFloat(data)
                if (!ratio.isFinite() || ratio !in 0f..1f) return@mapNotNull null
                val label = runCatching { host.packageManager.getApplicationLabel(host.packageManager.getApplicationInfo(pkg, 0)).toString() }
                    .getOrDefault(pkg).take(100)
                AppVolumeBridge.App(id, pkg, label, (ratio * AppVolumeBridgePolicy.MAX_LEVEL).toInt(), encodeIcon(icon.get(data) as? Drawable))
            }
            if (apps.isEmpty()) { clear(); return AppVolumeBridge.failure("no_apps") }
            (ball.get(target) as? android.view.View)?.takeIf { it.isAttachedToWindow }?.let { remove.invoke(target, it) }
            status.setInt(target, 0)
            if (token == null) lease.open(UUID.randomUUID().toString(), now) else lease.touch(now)
            identities = apps.associate { it.uid to it.packageName }
            return AppVolumeBridge.encode(AppVolumeBridge.Snapshot(lease.token!!, apps))
        }
        if (intent.action != AppVolumeBridge.WRITE) return null
        val id = intent.getIntExtra("uid", -1)
        val level = intent.getIntExtra("level", -1)
        val pkg = identities[id] ?: return AppVolumeBridge.failure("identity_changed")
        if (!AppVolumeBridgePolicy.levelValid(level) || !lease.write(token, intent.getLongExtra("sequence", -1), now))
            return AppVolumeBridge.failure("invalid_write")
        val configs = active.filter { uid(it) == id }
        if (configs.isEmpty() || !AppVolumeBridgePolicy.writable(id, host.applicationInfo.uid,
                host.packageManager.getPackagesForUid(id)?.toList().orEmpty(), pkg)) return AppVolumeBridge.failure("identity_changed")
        val method = setter.get(target) as Method
        check(method.parameterTypes.contentEquals(arrayOf(AudioPlaybackConfiguration::class.java, Float::class.javaPrimitiveType)))
        val ratio = level.toFloat() / AppVolumeBridgePolicy.MAX_LEVEL
        configs.forEach { method.invoke(audio.get(target), it, ratio) }
        persist.invoke(target, pkg, ratio)
        return Bundle().apply { putInt("version", AppVolumeBridge.VERSION); putString(AppVolumeBridge.TOKEN, token) }
    }

    private fun encodeIcon(drawable: Drawable?): ByteArray? = runCatching {
        if (drawable == null) return null
        val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        try {
            drawable.setBounds(0, 0, 48, 48); drawable.draw(Canvas(bitmap))
            ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray().takeIf { it.size <= 8192 } }
        } finally { bitmap.recycle() }
    }.getOrNull()
}
