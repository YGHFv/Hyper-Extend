package io.github.YGHFv.HyperExtend.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal object WalletCardFaceCapture {
    const val WALLET_PACKAGE = "com.miui.tsmclient"
    const val AUTHORITY = "io.github.YGHFv.HyperExtend.wallet.capture"
    const val METHOD_CAPTURE = "capture"
    const val PROTOCOL_VERSION = 8
    val URI: Uri = Uri.parse("content://$AUTHORITY")
    const val MAX_PREVIEW_BYTES = 256 * 1024
    private const val MAX_SOURCE_BYTES = 8 * 1024 * 1024
    private const val MAX_ENTRIES = 256
    private const val PREFS = "wallet_card_faces"
    private const val RECORDS = "records"
    private const val RECORDS_SCHEMA = "records_schema"
    private const val CURRENT_RECORDS_SCHEMA = 8
    private const val LOCAL_ADDRESSES = "local_addresses"
    private const val HIDDEN_ADDRESSES = "hidden_addresses"

    fun isCandidateAddress(address: String): Boolean = isSupportedAddress(address) &&
        !address.startsWith("content://${NfcCardImage.AUTHORITY}/")

    @Synchronized
    fun hide(context: Context, address: String): Boolean {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val hidden = preferences.getStringSet(HIDDEN_ADDRESSES, emptySet()).orEmpty().toMutableSet()
        if (hidden.size >= 256 && address !in hidden) return false
        hidden.add(address)
        val saved = preferences.edit().putStringSet(HIDDEN_ADDRESSES, hidden).commit()
        if (saved) AtomicFile(previewFile(context, address)).delete()
        return saved
    }

    @Synchronized
    fun restoreHidden(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().remove(HIDDEN_ADDRESSES).commit()


    data class Item(val address: String, val tier: Int, val preview: ByteArray?)

    fun isSupportedAddress(address: String): Boolean {
        if (address.length !in 8..4096 || address.any { it == '\n' || it == '\r' || it == '\u0000' }) {
            return false
        }
        return address.startsWith("/") || Uri.parse(address).scheme?.lowercase() in setOf(
            "http", "https", "file", "content", "asset", "assets", "android.resource",
        )
    }

    fun publish(context: Context, address: String, tier: Int): Boolean {
        require(isCandidateAddress(address) && tier == 3)
        val preview = thumbnail(context, address)
        val extras = Bundle().apply {
            putInt("protocol", PROTOCOL_VERSION)
            putString("address", address)
            putInt("tier", tier)
            preview?.let { putByteArray("preview", it) }
        }
        val accepted = context.contentResolver.call(
            URI, METHOD_CAPTURE, null, extras,
        )?.getBoolean("accepted", false) == true
        val remote = Uri.parse(address).scheme?.lowercase() in setOf("http", "https")
        return accepted && (preview != null || remote)
    }

    @Synchronized
    fun accept(context: Context, address: String, tier: Int, preview: ByteArray?): Boolean {
        require(isCandidateAddress(address)) { "filtered card face address" }
        require(tier == 3) { "local card confirmation required" }
        require(preview == null || preview.size in 1..MAX_PREVIEW_BYTES) { "invalid preview size" }
        val records = records(context).toMutableList()
        val hidden = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(HIDDEN_ADDRESSES, emptySet()).orEmpty()
        if (address in hidden) return true
        val existing = records.firstOrNull { it.address == address }
        if (existing == null && records.size >= MAX_ENTRIES) {
            ModuleLog.warn("nfc_card_face: capture capacity reached; preserving existing cards")
            return false
        }
        if (preview != null) {
            val file = AtomicFile(previewFile(context, address))
            val output = file.startWrite()
            try {
                output.write(preview)
                file.finishWrite(output)
            } catch (failure: Throwable) {
                file.failWrite(output)
                throw failure
            }
        }
        records.removeAll { it.address == address }
        records += Item(address, minOf(tier, existing?.tier ?: tier), null)
        val json = JSONArray()
        for (record in records) {
            json.put(JSONObject().put("address", record.address).put("tier", record.tier))
        }
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(RECORDS_SCHEMA, CURRENT_RECORDS_SCHEMA)
            .putString(RECORDS, json.toString()).commit()
        return saved
    }

    @Synchronized
    fun read(context: Context): List<Item> = records(context).map { record ->
        val file = AtomicFile(previewFile(context, record.address))
        val preview = runCatching {
            file.openRead().use { readLimited(it, MAX_PREVIEW_BYTES) }
        }.getOrNull()
        record.copy(preview = preview)
    }.sortedBy { it.tier }

    private fun records(context: Context): List<Item> = runCatching {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (preferences.getInt(RECORDS_SCHEMA, 0) != CURRENT_RECORDS_SCHEMA) {
            check(preferences.edit()
                .remove(RECORDS)
                .remove(LOCAL_ADDRESSES)
                .remove(HIDDEN_ADDRESSES)
                .putInt(RECORDS_SCHEMA, CURRENT_RECORDS_SCHEMA)
                .commit()) { "capture migration failed" }
            File(context.filesDir, "wallet_card_faces").listFiles()?.forEach { file ->
                if (file.isFile) file.delete()
            }
            return@runCatching emptyList()
        }
        val hidden = preferences.getStringSet(HIDDEN_ADDRESSES, emptySet()).orEmpty()
        val raw = preferences.getString(RECORDS, "[]") ?: "[]"
        require(raw.length <= 2 * 1024 * 1024) { "capture index too large" }
        val json = JSONArray(raw)
        val result = ArrayList<Item>()
        for (index in 0 until minOf(json.length(), MAX_ENTRIES)) {
            val record = json.optJSONObject(index) ?: continue
            val address = record.optString("address")
            val tier = record.optInt("tier", 2)
            if (isCandidateAddress(address) && address !in hidden && tier == 3) result += Item(address, tier, null)
        }
        result.distinctBy { it.address }
    }.getOrElse {
        ModuleLog.warn("nfc_card_face: invalid capture index; waiting for fresh wallet capture")
        emptyList()
    }

    private fun previewFile(context: Context, address: String): File {
        val directory = File(context.filesDir, "wallet_card_faces")
        check(directory.isDirectory || directory.mkdirs()) { "cannot create card face cache" }
        val digest = MessageDigest.getInstance("SHA-256").digest(address.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return File(directory, "$name.jpg")
    }

    private fun thumbnail(context: Context, address: String): ByteArray? = runCatching {
        val uri = Uri.parse(address)
        val input = when (uri.scheme?.lowercase()) {
            "http", "https" -> return@runCatching null
            "asset", "assets" -> context.assets.open(address.substringAfter("://").trimStart('/'))
            "file" -> {
                val path = uri.path ?: return@runCatching null
                if (path.startsWith("/android_asset/")) {
                    context.assets.open(path.removePrefix("/android_asset/"))
                } else {
                    context.contentResolver.openInputStream(uri)
                }
            }
            "content", "android.resource" -> context.contentResolver.openInputStream(uri)
            null -> File(address).inputStream()
            else -> return@runCatching null
        } ?: return@runCatching null
        val bytes = input.use { readLimited(it, MAX_SOURCE_BYTES) } ?: return@runCatching null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return@runCatching null
        try {
            val output = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output)) return@runCatching null
            output.toByteArray().takeIf { it.size <= MAX_PREVIEW_BYTES }
        } finally {
            bitmap.recycle()
        }
    }.getOrElse {
        ModuleLog.warn("nfc_card_face: local preview unavailable (${it.javaClass.simpleName})")
        null
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray? {
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(chunk)
            if (count < 0) return output.toByteArray()
            if (output.size() + count > limit) return null
            output.write(chunk, 0, count)
        }
    }
}
