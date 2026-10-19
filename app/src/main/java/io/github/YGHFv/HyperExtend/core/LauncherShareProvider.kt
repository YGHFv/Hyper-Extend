/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.File
import java.io.FileNotFoundException

/** system_server 到模块进程的唯一分享控制入口。 */
class LauncherShareProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val providerContext = checkNotNull(context)
        enforceCaller()
        require(method == LauncherShareBridge.METHOD_SHARE) { "unsupported share method" }
        val payload = requireNotNull(extras) { "missing share payload" }
        require(payload.getInt("protocol", 0) == LauncherShareBridge.PROTOCOL) {
            "outdated share protocol"
        }
        val packageName = requireNotNull(
            LauncherShareBridge.packagePolicy(payload.getString("package")),
        ) { "invalid share package" }
        val main = requireNotNull(payload.getParcelable<ParcelFileDescriptor>("main")) {
            "missing main APK"
        }
        val splits = payload.getParcelableArrayList<ParcelFileDescriptor>("splits").orEmpty()
        val labels = payload.getStringArrayList("labels").orEmpty()
        ModuleLog.info("launcher share provider: begin package=$packageName")
        val file = try {
            LauncherShareBridge.materialize(providerContext, packageName, main, splits, labels)
        } finally {
            runCatching { main.close() }
            splits.forEach { fd -> runCatching { fd.close() } }
        }
        ModuleLog.info("launcher share provider: file ready package=$packageName")
        return Bundle().apply {
            putBoolean("accepted", true)
            putString("file", file.name)
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = unsupported()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = unsupported()
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
    ): Int = unsupported()

    private fun enforceCaller() {
        val uid = Binder.getCallingUid()
        if (uid == Process.SYSTEM_UID || uid == Process.myUid()) return
        throw SecurityException("launcher share caller not allowed")
    }

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("launcher share provider is call-only")
}

/** 固定私有目录的只读文件出口，URI 只包含一个文件名。 */
class LauncherShareFileProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r") { "launcher share files are read-only" }
        val providerContext = checkNotNull(context)
        val name = uri.lastPathSegment ?: throw FileNotFoundException("missing share file")
        val file: File = LauncherShareBridge.fileFor(providerContext, name)
            ?: throw FileNotFoundException("share file not found")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? {
        val providerContext = context ?: return null
        val name = uri.lastPathSegment ?: return null
        return LauncherShareBridge.fileFor(providerContext, name)?.let(LauncherShareBridge::mimeOf)
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = unsupported()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = unsupported()
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
    ): Int = unsupported()

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("launcher share files are read-only")
}
