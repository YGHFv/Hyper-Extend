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

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * 把卡面副本以只读方式交给钱包进程。
 *
 * ## 这个 provider 能做的最小集合
 *
 * 只有一件东西可以访问：[NfcCardImage.uri] 那一个地址，对应的永远是 `files/nfc/` 下那一个文件。
 * 路径由这个类自己拼、不接受任何来自对方的路径片段，
 * 所以**不存在路径穿越**这个问题 —— 连「校验参数」这一步都没有可校验的东西。
 *
 * 除了读，别的操作（插入/更新/删除/列举）一律抛 `UnsupportedOperationException`：
 * 一个只该被读的通道出现写入口，只会是将来某次改动的失误。
 *
 * ## 访问控制
 *
 * `exported=false` + `grantUriPermissions=true`（见 AndroidManifest）：别的应用**默认一个字节都读不到**，
 * 只有被 `Context.grantUriPermission` 显式授权过的包名（见 [NfcCardImage.HOST_PACKAGES]）才行。
 * 授权由 AMS 持有，与本进程的存活无关。
 *
 * ## 为什么不用 FileProvider
 *
 * 那个类能做的事（按 `file_paths.xml` 映射整棵子树、支持写入模式、支持多 authority）
 * 这里一样都不需要，而它引入的是「配置错一个通配符就把整个 files 目录暴露出去」的风险面。
 * 自己写一个固定地址的只读 provider，逻辑短到可以一眼看完。
 */
class NfcCardImageProvider : ContentProvider() {

    /**
     * 唯一接受的 mode。
     *
     * `openFile` 的这个参数是**字符串**（`"r"` / `"w"` / `"rw"` / `"rwt"`），而不是
     * `ParcelFileDescriptor.MODE_*` 那套常量 —— 后者是下面对文件描述符用的。
     * 只放行 `"r"`：连 `"rwt"`（截断式写）都不给，免得将来有人以为这里是可写的通道。
     */
    private val readOnlyMode = "r"

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String {
        enforceCaller()
        requireCardUri(uri)
        return NfcCardImage.mimeOf(cardFile(uri))
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        enforceCaller()
        requireCardUri(uri)
        if (mode != readOnlyMode) {
            throw FileNotFoundException("NfcCardImageProvider is read-only (requested: $mode)")
        }
        val file = cardFile(uri) ?: throw FileNotFoundException("no card face configured")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = unsupported("insert")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        unsupported("delete")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = unsupported("update")

    private fun cardFile(uri: Uri): File? = context?.let {
        if (uri.path == NfcCardImage.uri().path) NfcCardImage.currentFile(it)
        else NfcCardImage.mappedFile(it, uri.toString())
    }

    private fun enforceCaller() {
        val uid = android.os.Binder.getCallingUid()
        if (uid == android.os.Process.myUid()) return
        val packages = context?.packageManager?.getPackagesForUid(uid).orEmpty()
        if (packages.none { it in NfcCardImage.HOST_PACKAGES }) {
            throw SecurityException("card image caller not allowed")
        }
    }

    /**
     * 只认 [NfcCardImage.uri] 这一个地址。别的路径一律当作不存在 ——
     * 这样「地址拼错」在宿主那边表现为明确的读失败，而不是读到别的东西。
     */
    private fun requireCardUri(uri: Uri) {
        val expected = NfcCardImage.uri()
        if (uri.authority != expected.authority || (uri.path != expected.path && cardFile(uri) == null)) {
            throw FileNotFoundException("unknown uri: $uri")
        }
    }

    private fun unsupported(operation: String): Nothing =
        throw UnsupportedOperationException("NfcCardImageProvider does not support $operation")
}
