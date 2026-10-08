/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Unlock behavior references HyperCeiler UnlockCopyPicture
 * (Copyright (C) 2023-2026 HyperCeiler Contributions, AGPL-3.0).
 */
package io.github.YGHFv.HyperExtend.hook.feature

import android.content.ClipData
import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.MediaStore
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.Reflect
import java.lang.reflect.Modifier

internal object ScreenshotClipboard {
    private val lock = Any()
    private var generation = 0L
    private val replay = ThreadLocal<Boolean>()
    private val worker by lazy {
        Handler(HandlerThread("HyperExtend-clipboard").apply { start() }.looper)
    }

    fun install(loader: ClassLoader): Int {
        val current = Reflect.attempt {
            Class.forName("android.app.ActivityThread", false, loader)
                .getDeclaredMethod("currentApplication").invoke(null) as? Application
        }
        if (current?.packageName == "com.miui.screenshot") return installWithContext(loader, current)
        // PackageReady may precede Application creation. Install only after attach has
        // supplied a real host Context, not by assuming currentApplication is non-null.
        val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
        return if (HookRuntime.hookAfter(attach, "screenshot_clipboard/applicationAttach") { chain, result ->
                val app = chain.thisObject as? Application
                if (app?.packageName == "com.miui.screenshot") {
                    installWithContext(loader, app)
                }
                result
            }) 1 else 0
    }

    private fun installWithContext(loader: ClassLoader, app: Context): Int {
        // Match the known target signature, not the host's version string.
        val util = Reflect.loadClass(loader, "com.miui.screenshot.util.Util") ?: return 0
        val gate = util.declaredMethods.singleOrNull {
            it.name == "o" && Modifier.isStatic(it.modifiers) &&
                it.returnType == Boolean::class.javaPrimitiveType &&
                it.parameterTypes.contentEquals(arrayOf(Context::class.java))
        } ?: run {
            ModuleLog.warn("screenshot_clipboard: target signature missing or ambiguous; skipped")
            return 0
        }
        val write = ClipboardManager::class.java.getDeclaredMethod("setPrimaryClip", ClipData::class.java)
        if (!HookRuntime.hook(write, "screenshot_clipboard/publishGuard") { chain ->
                if (replay.get() == true) {
                    chain.proceed()
                } else synchronized(lock) {
                    val token = ++generation
                    worker.removeCallbacksAndMessages(null)
                    val clip = chain.args[0] as? ClipData
                    val manager = chain.thisObject as? ClipboardManager
                    if (clip == null || manager == null || !hasMediaImage(clip)) {
                        chain.proceed()
                    } else {
                        // Never publish from inside bitmap compression: the stream is still open
                        // and MediaStore still considers this row private to the screenshot UID.
                        val copy = ClipData(clip)
                        defer(app, manager, copy, token, SystemClock.uptimeMillis() + 15_000)
                        null
                    }
                }
            }) return 0
        return 1 + if (HookRuntime.hookReturning(gate, "screenshot_clipboard/unlock", false)) 1 else 0
    }

    private fun hasMediaImage(clip: ClipData): Boolean = (0 until clip.itemCount).any {
        val uri = clip.getItemAt(it).uri
        uri?.scheme == "content" && uri.authority?.substringAfter('@') == "media" &&
            uri.pathSegments.contains("images")
    }

    private fun defer(context: Context, manager: ClipboardManager, clip: ClipData, token: Long, deadline: Long) {
        worker.postDelayed({
            try {
                synchronized(lock) {
                    if (token != generation) return@synchronized
                    if ((0 until clip.itemCount).all { published(context, clip.getItemAt(it).uri) }) {
                        replay.set(true)
                        try {
                            manager.setPrimaryClip(clip)
                        } finally {
                            replay.remove()
                        }
                    } else if (SystemClock.uptimeMillis() < deadline) {
                        defer(context, manager, clip, token, deadline)
                    } else {
                        ModuleLog.warn("screenshot_clipboard: image not published; copy skipped")
                    }
                }
            } catch (t: Throwable) {
                ModuleLog.warn("screenshot_clipboard: copy skipped (${t.javaClass.simpleName})")
            }
        }, 150)
    }

    private fun published(context: Context, uri: Uri?): Boolean {
        if (uri == null) return true
        if (uri.scheme != "content" || uri.authority?.substringAfter('@') != "media") return false
        return context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.IS_TRASHED),
            null, null, null,
        )?.use { cursor ->
            cursor.moveToFirst() && !cursor.isNull(0) && !cursor.isNull(1) &&
                cursor.getInt(0) == 0 && cursor.getInt(1) == 0
        } == true
    }
}
