/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.mishare

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.Reflect
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal object MiShareReceiveGuard {
    private const val HOST = "com.miui.mishare.connectivity"
    private const val LABEL = "mishare_receive_guard"
    private val initialized = AtomicBoolean()
    private val warned = AtomicBoolean()

    fun install(loader: ClassLoader): Int {
        if (Build.VERSION.SDK_INT < 30) return 0
        val app = Reflect.attempt {
            Class.forName("android.app.ActivityThread", false, loader)
                .getDeclaredMethod("currentApplication").invoke(null) as? Application
        }
        if (app?.packageName == HOST) return installWithContext(loader, app)
        val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
        return if (HookRuntime.hookAfter(attach, "$LABEL/applicationAttach") { chain, result ->
                val context = chain.args.firstOrNull() as? Context
                if (context?.packageName == HOST) installWithContext(loader, context)
                result
            }) 1 else 0
    }

    private fun installWithContext(loader: ClassLoader, context: Context): Int {
        if (!initialized.compareAndSet(false, true)) return 0
        val target = MiShareTargets.locate({ Class.forName(it, false, loader) })
        if (target == null) {
            ModuleLog.warn("$LABEL: target pair missing or ambiguous; skipped")
            return 0
        }
        val (allocator, receiver) = target
        // Deoptimize the caller too: an already-inlined allocator would otherwise bypass the hook.
        if (!HookRuntime.deoptimize(receiver, "$LABEL/receiver")) return 0
        val resolver = context.contentResolver
        val root = File(Environment.getExternalStorageDirectory().canonicalFile, "Download/MiShare")
        return if (HookRuntime.hookAfter(allocator, "$LABEL/destination") { chain, result ->
                val path = result as? String
                val directory = chain.args.firstOrNull() as? String
                if (path == null || directory != root.path ||
                    Thread.currentThread().stackTrace.none {
                        it.className == receiver.declaringClass.name && it.methodName == receiver.name
                    }
                ) result else {
                    val deadline = SystemClock.uptimeMillis() + 1_500
                    val policy = MiShareDestinationPolicy(::onDisk, { file ->
                        if (SystemClock.uptimeMillis() >= deadline) PathPresence.UNKNOWN
                        else inMediaStore(resolver, file)
                    })
                    val selected = policy.select(path, directory, root)
                    if (selected != path) ModuleLog.info("$LABEL: stale index collision avoided; alternate receive name selected")
                    selected
                }
            }) 1 else 0
    }

    private fun onDisk(file: File): PathPresence = try {
        // Unlike File.exists(), lstat distinguishes inaccessible paths and dangling symlinks.
        Os.lstat(file.absolutePath)
        PathPresence.PRESENT
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) PathPresence.ABSENT else PathPresence.UNKNOWN
    }

    private fun inMediaStore(resolver: ContentResolver, file: File): PathPresence = try {
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.DATA} = ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(file.absolutePath))
            putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
        resolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            arrayOf(MediaStore.MediaColumns._ID), args, null,
        )?.use { if (it.moveToFirst()) PathPresence.PRESENT else PathPresence.ABSENT }
            ?: PathPresence.UNKNOWN
    } catch (error: Exception) {
        if (warned.compareAndSet(false, true)) {
            ModuleLog.warn("$LABEL: index check unavailable (${error.javaClass.simpleName}); keeping host behavior")
        }
        PathPresence.UNKNOWN
    }
}
