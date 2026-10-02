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

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import java.io.File

/**
 * NFC 卡面图片的**本地副本与授权**。
 *
 * ## 为什么要把图片复制进来
 *
 * 用户是从系统相册选择器里选图的，那个 `content://` 只在一个 Uri 授权（通常还带时间限制）内有效，
 * 而卡面图要在**钱包进程**里反复加载 —— 授权早就过期了。所以选完之后第一件事是把字节复制进
 * 本应用自己的 `files/nfc/`，之后一切都基于这个副本。
 *
 * ## 跨进程怎么给钱包读到
 *
 * 钱包是另一个 uid，读不到我们的私有目录。唯一不需要 root 的通道是：由本模块自己的
 * [NfcCardImageProvider] 把它暴露成一个 `content://` Uri，再把**读权限显式授予钱包包名**
 * （[grantToHosts]）。于是：
 *
 * - 模块侧只需要存一个字符串（那个 Uri），它就是最终交给宿主去加载的地址；
 * - 宿主拿到的是一个标准的 `content://`，和它自己平时加载的图片走同一条 Glide 路径；
 * - 我们的私有目录仍然是私有的，没有 `exported` 的 provider，也没有任何存储权限。
 *
 * 授权由 AMS 记录，与我们的进程死活无关；但**重启后是否仍然有效取决于 AMS 的实现**，
 * 所以启动时用 [ensureGranted] 无条件补一次 —— 补授权是幂等的，代价只有一次 binder 调用。
 */
object NfcCardImage {

    /** provider 的 authority。与 AndroidManifest 里声明的那一个必须逐字一致。 */
    const val AUTHORITY = "io.github.YGHFv.HyperExtend.nfc"

    /** Uri 的路径段。只有这一个，且不可由宿主指定 —— 见 [NfcCardImageProvider]。 */
    const val PATH = "card"

    /**
     * 需要读这张图的宿主。
     *
     * 两个都在，且都是必需的：
     * - `com.miui.tsmclient` —— 钱包自己加载卡面（`CustomGlideUrl` / `RequestManager` 两条路都在这个进程里）。
     * - `com.android.systemui` —— 「超级岛卡面」那一处：钱包只是把地址塞进通知，
     *   真正把图解码出来的是**系统界面**。少了这条授权，超级岛上的卡面会是一片空白
     *   （而不是明确报错，所以特别难归因）。
     *
     * 加宿主要同时确认它**确实会去读那个解析出来的地址**。
     */
    val HOST_PACKAGES = listOf("com.miui.tsmclient", "com.android.systemui")

    private const val DIR_NAME = "nfc"
    private const val FILE_STEM = "card"

    /** 存放卡面副本的目录。 */
    fun directory(context: Context): File = File(context.filesDir, DIR_NAME)

    /**
     * 当前这张卡面副本。没有就返回 null。
     *
     * 用「目录里第一个文件」而不是写死文件名：扩展名跟着用户选的图片格式走
     * （`.png` / `.jpg` / `.webp`），写死一个名字就得在换格式时改名、顺便让缓存失效。
     */
    fun currentFile(context: Context): File? =
        directory(context).listFiles()?.firstOrNull { it.isFile && it.name.startsWith(FILE_STEM) }

    /** 卡面副本对应的稳定 Uri。文件不存在时也返回它 —— 宿主拿到 404 比拿不到地址好归因。 */
    fun uri(): Uri = Uri.parse("content://$AUTHORITY/$PATH")

    fun mappedFile(context: Context, address: String): File? = runCatching {
        val uri = Uri.parse(address)
        require(uri.authority == AUTHORITY && uri.pathSegments.size == 2 && uri.pathSegments[0] == PATH)
        val name = uri.pathSegments[1]
        require(name.matches(Regex("[0-9a-f-]{36}\\.png")))
        File(directory(context), name).takeIf { it.isFile }
    }.getOrNull()

    fun importForCard(context: Context, picked: Uri): Result<String> = runCatching {
        val bytes = context.contentResolver.openInputStream(picked)?.use { it.readBytesLimited() }
            ?: error("图片不可读或超过 8MB")
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无效图片" }
        var sample = 1
        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
        val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: error("图片解码失败")
        try {
            val directory = directory(context)
            check(directory.isDirectory || directory.mkdirs())
            val file = File(directory, "${java.util.UUID.randomUUID()}.png")
            try {
                file.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
                val uri = Uri.parse("content://$AUTHORITY/$PATH/${file.name}")
                grantToHosts(context, uri)
                uri.toString()
            } catch (failure: Throwable) {
                file.delete()
                throw failure
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun java.io.InputStream.readBytesLimited(): ByteArray? {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16384)
        while (true) {
            val count = read(buffer)
            if (count < 0) return output.toByteArray()
            if (output.size() + count > 8 * 1024 * 1024) return null
            output.write(buffer, 0, count)
        }
    }

    /**
     * 把用户选的图片复制进来，替换掉旧的那张，并重新授权。
     *
     * @return 成功时是给宿主用的 `content://` 地址；失败时是给用户看的一句话。
     */
    fun import(context: Context, picked: Uri): Result<String> = runCatching {
        val dir = directory(context)
        if (!dir.exists() && !dir.mkdirs()) error("无法创建 ${dir.absolutePath}")

        val extension = extensionOf(context.contentResolver, picked)
        val target = File(dir, "$FILE_STEM.$extension")

        context.contentResolver.openInputStream(picked)?.use { input ->
            // 先写临时文件再改名：中途失败（空间不足、源被关掉）时不会留下半张图，
            // 而半张图在宿主那边表现为「卡面空白」，比明确的失败难查得多。
            val temp = File(dir, "$FILE_STEM.tmp")
            temp.outputStream().use { output -> input.copyTo(output) }
            if (target.exists() && !target.delete()) error("旧的卡面副本删不掉")
            if (!temp.renameTo(target)) error("卡面副本落盘失败")
        } ?: error("选中的图片打不开")

        // 换图后扩展名可能变，旧的那张要清掉，否则 [currentFile] 会随机取到其中一张。
        dir.listFiles()?.forEach { if (it != target && it.isFile) it.delete() }

        val uri = uri()
        grantToHosts(context, uri)
        ModuleLog.info("nfc card face imported: ${target.name} (${target.length()} bytes)")
        uri.toString()
    }.onFailure { ModuleLog.error("nfc card face import failed", it) }

    /** 删除卡面副本并撤掉授权。返回是否真的删掉了东西。 */
    fun clear(context: Context): Boolean {
        val files = directory(context).listFiles().orEmpty()
        var removed = false
        for (file in files) {
            if (!file.isFile) continue
            val uri = if (file.name.startsWith(FILE_STEM)) uri()
                else Uri.parse("content://$AUTHORITY/$PATH/${file.name}")
            revokeFromHosts(context, uri)
            removed = file.delete() || removed
        }
        ModuleLog.info("nfc card face cleared: $removed")
        return removed
    }

    /**
     * 启动时补一次授权。
     *
     * 授权在 AMS 那边是持久记录，正常不会丢；但「丢了」的代价是钱包加载卡面失败、
     * 而用户看到的现象是「卡面变回原样」（他多半会以为是模块坏了）。所以这里不判断是否已授权，
     * 直接重授 —— 幂等，且只在模块 App 启动时发生一次。
     */
    fun ensureGranted(context: Context) {
        directory(context).listFiles()?.filter { it.name.matches(Regex("[0-9a-f-]{36}\\.png")) }
            ?.forEach { grantToHosts(context, Uri.parse("content://$AUTHORITY/$PATH/${it.name}")) }
        if (currentFile(context) == null) return
        grantToHosts(context, uri())
    }

    /** 把读权限授予全部宿主。个别宿主没装时只记日志 —— 那本来就该被 [HostApps] 过滤掉。 */
    private fun grantToHosts(context: Context, uri: Uri) {
        for (packageName in HOST_PACKAGES) {
            runCatching {
                context.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.onFailure {
                ModuleLog.warn("grant $uri to $packageName failed: ${it.javaClass.simpleName}")
            }
        }
    }

    private fun revokeFromHosts(context: Context, uri: Uri) {
        for (packageName in HOST_PACKAGES) {
            runCatching { context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    /** 按 MIME 决定扩展名。认不出来时给 `.png`：解码器靠文件头判断格式，扩展名只影响展示。 */
    private fun extensionOf(resolver: ContentResolver, uri: Uri): String {
        val mime = runCatching { resolver.getType(uri) }.getOrNull() ?: return "png"
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.lowercase() ?: "png"
    }

    /** 文件扩展名对应的 MIME。给 provider 的 `getType` 用。 */
    fun mimeOf(file: File?): String {
        val extension = file?.extension?.lowercase() ?: return "image/png"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "image/png"
    }
}
