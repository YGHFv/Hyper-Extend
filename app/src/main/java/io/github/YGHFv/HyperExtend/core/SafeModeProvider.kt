/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

/** Host reports may disable only their own scope, never enable hooks or reset protection. */
class SafeModeProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val context = requireNotNull(context)
        require(method == "report")
        val scope = requireNotNull(scopeById(arg.orEmpty()))
        val uid = Binder.getCallingUid()
        val packages = context.packageManager.getPackagesForUid(uid).orEmpty().toSet()
        require(uid / 100000 == Process.myUid() / 100000) { "cross-user report rejected" }
        if (!SafeModeCallerPolicy.allowed(scope.id, scope.process, uid, packages)) throw SecurityException("untrusted safety report")
        val text = extras?.getString("incident").orEmpty()
        require(text.length <= 2048)
        val incident = requireNotNull(SafeModeIncident.decode(text))
        // Framework projection is a module operation, not a nested call as the host UID.
        val identity = Binder.clearCallingIdentity()
        return try {
            Bundle().apply { putBoolean("accepted", SafeModeSettings.accept(context, scope, incident)) }
        } finally { Binder.restoreCallingIdentity(identity) }
    }
    override fun getType(uri: Uri): String? = null
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
