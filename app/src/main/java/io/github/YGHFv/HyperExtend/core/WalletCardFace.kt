/*
 * Copyright (C) 2026 YGHFv
 *
 * 本文件是「澎湃补全计划」（HyperExtend）的一部分。
 *
 * 本程序是自由软件：你可以依据 GNU Affero 通用公共许可证（由自由软件基金会发布）
 * 的条款重新发布和/或修改它，无论是许可证的第 3 版，还是（由你选择）任何更新的版本。
 *
 * 本程序基于「希望它有用」而分发，但不提供任何担保；甚至不包括对适销性或特定用途
 * 适用性的默示担保。详见 GNU Affero 通用公共许可证。
 */

package io.github.YGHFv.HyperExtend.core

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

object WalletCardFace {
    private const val MAX_DOWNLOAD_BYTES = 8 * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val READ_TIMEOUT_MS = 8_000

    data class Item(val address: String, val bitmap: ImageBitmap?)
    data class Result(val items: List<Item>, val hint: String)

    fun read(context: Context): Result = runCatching {
        val capturedRecords = WalletCardFaceCapture.read(context)
        val mappedAddresses = CardFaceMappings.read(
            io.github.YGHFv.HyperExtend.config.HyperSettings.localPrefs(context).getString(NFC_IMAGE_KEY, "").orEmpty(),
        ).keys
        val captured = (mappedAddresses.map { address ->
            capturedRecords.firstOrNull { it.address == address }
                ?: WalletCardFaceCapture.Item(address, 3, null)
        } + capturedRecords).distinctBy { it.address }
        if (captured.isEmpty()) {
            return@runCatching Result(
                emptyList(),
                "暂无卡面。重启钱包并打开已办理的卡片后刷新。",
            )
        }
        val items = captured.map { item ->
            val bitmap = item.preview?.let(::decodeScaled) ?: if (
                item.address.startsWith("https://") || item.address.startsWith("http://")
            ) fetch(item.address)?.let(::decodeScaled) else null
            Item(item.address, bitmap)
        }
        val loaded = items.count { it.bitmap != null }
        val hint = if (loaded == items.size) {
            "${items.size} 张卡面 · 相同原图合并显示"
        } else {
            "收到 ${items.size} 张卡面，已加载 $loaded 张。未加载的本地卡面请在钱包中再次打开，网络卡面请检查网络后刷新。"
        }
        Result(items, hint)
    }.getOrElse {
        ModuleLog.error("nfc_card_face: read captured faces failed", it)
        Result(emptyList(), "卡面缓存读取失败，请在钱包中重新打开卡片列表后刷新。")
    }

    private fun fetch(address: String): ByteArray? = runCatching {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        try {
            if (connection.responseCode !in 200..299 || connection.contentLengthLong > MAX_DOWNLOAD_BYTES) {
                return@runCatching null
            }
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    if (output.size() + count > MAX_DOWNLOAD_BYTES) return@runCatching null
                    output.write(chunk, 0, count)
                }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun decodeScaled(bytes: ByteArray): ImageBitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }.getOrNull()
}
