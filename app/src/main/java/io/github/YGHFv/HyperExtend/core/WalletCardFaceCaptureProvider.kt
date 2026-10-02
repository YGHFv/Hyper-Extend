package io.github.YGHFv.HyperExtend.core

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

class WalletCardFaceCaptureProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val providerContext = checkNotNull(context)
        val callerUid = Binder.getCallingUid()
        val callerPackages = providerContext.packageManager.getPackagesForUid(callerUid).orEmpty()
        if (callerUid != Process.myUid() && WalletCardFaceCapture.WALLET_PACKAGE !in callerPackages) {
            throw SecurityException("wallet capture caller not allowed")
        }
        require(method == WalletCardFaceCapture.METHOD_CAPTURE)
        val accepted = runCatching {
            val payload = requireNotNull(extras)
            require(payload.getInt("protocol", 0) == WalletCardFaceCapture.PROTOCOL_VERSION) {
                "outdated capture protocol; restart wallet processes"
            }
            val address = requireNotNull(payload.getString("address"))
            WalletCardFaceCapture.accept(
                providerContext, address, payload.getInt("tier", 2), payload.getByteArray("preview"),
            )
        }.getOrElse {
            ModuleLog.warn("nfc_card_face: capture rejected (${it.javaClass.simpleName})")
            false
        }
        if (accepted) providerContext.contentResolver.notifyChange(WalletCardFaceCapture.URI, null)
        return Bundle().apply { putBoolean("accepted", accepted) }
    }

    override fun getType(uri: Uri): String? = null

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = unsupported()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = unsupported()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("wallet capture is call-only")
}
