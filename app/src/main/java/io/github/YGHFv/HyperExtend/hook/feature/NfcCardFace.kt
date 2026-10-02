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
 *
 * ---------------------------------------------------------------------------
 * 功能来源：DIY NFC 卡面图片（zhizi42，GPL-3.0）。
 *
 * ## 挂载点是怎么定的（这一段是核对真实钱包 APK 之后的结论，别再凭形状猜）
 *
 * 核对对象：设备上真实安装的 `com.miui.tsmclient`，`versionName=26.08.28.4.f`（Android 16）。
 * 三条挂载点各自的**唯一解**已用源码文件名（R8 保留的 `source_file` 属性）交叉验证过：
 *
 * | 挂载点 | 类 | 源文件 | 方法 |
 * |---|---|---|---|
 * | ① 卡面地址构造点 | `com.miui.tsmclient.util.n0` | `CustomGlideUrl.java` | `public static i(String): Object` |
 * | ② Glide 载入入口 | `com.bumptech.glide.l` | `RequestManager.java` | `public u(String): com.bumptech.glide.k` |
 * | ③ 超级岛卡面 | `com.miui.tsmclient.util.k3` | `SimpleCardSwipingNotificationUtils.java` | `private static k(CardInfo): String` |
 *
 * 三处都**必须**带上下面的限定词才是唯一的，少一个就变成多候选：
 *
 * 1. ① 要 `public + static`。少了 `public` 会多出私有工厂方法。
 * 2. ③ 要 `private + static`。上游也是 `PRIVATE | STATIC` —— **少写 `private` 就会有 6 个候选**
 *    （`com.miui.tsmclient.util.v0#f/i/l`、`v3#h/l` 都是 `(CardInfo) -> String` 的公开工具方法，
 *    它们返回的是卡号、设备号之类，不是卡面地址）。
 * 3. ② 不能只看「`(String) -> com.bumptech.glide.*`」：这样会命中 **10** 个，其中 8 个是
 *    R8 给枚举合成的 `valueOf(String)`（8 个里有 5 个还是内部类，会被 `DexScan` 的
 *    「不含内部类」过滤掉）。剔掉静态方法与 `valueOf` 之后还剩 2 个 ——
 *    `RequestManager#load(String)` 与 `RequestBuilder#load(String)`，签名一模一样，
 *    **纯签名无法区分**。所以这里加了一条结构判据：`RequestManager` 自己持有 Glide 的
 *    tracker/lifecycle 字段（`com.bumptech.glide.manager.j/p/o/r/b`），`RequestBuilder` 只持有
 *    `requestOptions` / `context` / `parent`，一个都没有 —— 见 [ownsRequestTrackers]。
 *
 * ## 与上游的交叉验证（这是这三条判据「不是猜」的依据）
 *
 * 上游 v2.4 用 DexKit 的 `ClassMatcher.source()` + `MethodMatcher` 定位同一批靶子，
 * 逐条比对过，**三条判据与上游逐字等价**（`Hook#positionMethod`）：
 *
 * | 挂载点 | 上游的类判据 | 上游的方法判据 |
 * |---|---|---|
 * | ① | `searchPackages("com.miui.tsmclient.util")` + `source("CustomGlideUrl.java")` | `paramCount(1)`, `paramTypes(String)`, `PUBLIC \| STATIC`, `returnType(Object)` |
 * | ② | `searchPackages("com.bumptech.glide")` + `source("RequestManager.java")` | `paramCount(1)`, `paramTypes(String)`, `PUBLIC`, `returnType("com.bumptech.glide", StartsWith)` |
 * | ③ | `searchPackages("com.miui.tsmclient.util")` | `paramCount(1)`, `paramTypes("…entity.CardInfo")`, `PRIVATE \| STATIC`, `returnType(String)` |
 *
 * 两处**刻意的差异**，都不是放松判据：
 *
 * - 上游靠 `source()`（源文件名）收敛到唯一；运行时拿不到 `Class` 的 `source_file` 属性
 *   （那是 dex 文件里的信息，`Class` 对象上没有），所以这里改成「扫完整个包，命中必须唯一」
 *   —— 落在同一个包上、结论同样是唯一解（见 [uniqueCandidate]）。
 * - ② 用结构判据（[ownsRequestTrackers]）代替 `source("RequestManager.java")`。
 *   好处是**不依赖 R8 是否保留源文件名** —— 那个属性是可选优化，哪天不留了判据就没了。
 *
 * 列表只接受本地、已开通、非占位 CardInfo 的原图。替换按原地址精确映射，
 * 与参考项目一致：相同原图地址共用一个配置条目。
 */

package io.github.YGHFv.HyperExtend.hook.feature

import android.app.Application
import android.content.Context
import android.os.SystemClock
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.core.NFC_IMAGE_KEY
import io.github.YGHFv.HyperExtend.core.CardFaceMappings
import io.github.YGHFv.HyperExtend.core.NfcCardImage
import io.github.YGHFv.HyperExtend.core.WalletCardFaceCapture
import io.github.YGHFv.HyperExtend.hook.DexScan
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 「NFC 卡面自定义」。作用域：com.miui.tsmclient。 */
internal object NfcCardFace {

    private const val FEATURE = "nfc_card_face"

    /** 超级岛那一处的开关。配置型功能没有主开关，见 `HookSettings.isOn`。 */
    private const val OPT_SUPER_ISLAND = "nfc_card_face.super_island"

    private const val PKG_TSM_UTIL = "com.miui.tsmclient.util"
    private const val PKG_GLIDE = "com.bumptech.glide"

    /** `RequestManager` 持有这些字段；见 [ownsRequestTrackers]。 */
    private const val GLIDE_MANAGER_PKG = "com.bumptech.glide.manager."

    private const val CARD_INFO_CLASS = "com.miui.tsmclient.entity.CardInfo"

    private const val MANIFEST_NAME = "hyperextend_wallet_face.txt"
    private const val TIER_CARD_FACE = 3
    private const val TIER_GENERIC = 2
    private const val RETRY_INTERVAL_MS = 10_000L
    private const val MAX_TRACKED_ADDRESSES = 64

    @Volatile
    private var configuredImages: Map<String, String> = emptyMap()

    @Volatile
    private var replaceSuperIsland = false

    @Volatile
    private var hostContext: Context? = null


    private data class CaptureAttempt(val tier: Int, val attemptedAt: Long, var completed: Boolean = false)

    private val attempts = LinkedHashMap<String, CaptureAttempt>(MAX_TRACKED_ADDRESSES, 0.75f, true)
    private val loggedOnce = ConcurrentHashMap.newKeySet<String>()
    private val captureExecutor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(32),
        { runnable -> Thread(runnable, "hyperextend-nfc-capture").apply { isDaemon = true } },
    )

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        configuredImages = CardFaceMappings.read(settings.string(NFC_IMAGE_KEY))
        replaceSuperIsland = settings.isOn(OPT_SUPER_ISLAND)
        loggedOnce.clear()
        hostContext = currentApplication()
        var installed = installHooks(loader)
        if (installed > 0) {
            installed += installCaptureContext(loader)
        }
        ModuleLog.info(
            "$FEATURE: $installed hook(s), " +
                "mappedImages=${configuredImages.size}, superIsland=$replaceSuperIsland",
        )
        return installed
    }

    // ---------------------------------------------------------------- 值的判据

    /**
     * 这个值看起来是不是一张图片地址。
     *
     * 这是整套判据的核心（见文件头「语义守卫」）：只认这几种形状，
     * 其余一概不动。判据宽松一点没关系（顶多是多捕获一张无关的图），
     * 但**绝不能把普通字符串当成图片地址去替换** —— 那会把卡号、卡名换成一张图的地址。
     */
    private fun looksLikeImageAddress(value: Any?): Boolean {
        val text = (value as? String)?.trim().orEmpty()
        if (text.length < 8) return false
        val lower = text.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return true
        if (lower.startsWith("file://") || lower.startsWith("content://")) return true
        if (lower.startsWith("assets://") || lower.startsWith("asset://")) return true
        if (lower.startsWith("android.resource://")) return true
        if (!text.startsWith("/")) return false
        return lower.endsWith(".png") ||
            lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") ||
            lower.endsWith(".webp") ||
            lower.endsWith(".gif")
    }

    /**
     * 「这是注册候选卡 / 小图标，不是本机卡面」的地址特征。
     *
     * 参考项目默认关闭「显示所有卡片」时会排除这三类地址：
     * `w270h480` 是注册交通卡列表使用的服务端缩略图，`door-card-img/logo` 和
     * `Mibi` 是卡包图标。此前为了避免空列表错误地放进了 `w270h480`，实机验证后
     * 确认这会把注册交通卡候选项当成本机卡面，因此必须恢复上游过滤语义。
     */
    private val NOT_CARD_FACE_TOKENS = arrayOf("w270h480", "/door-card-img/logo/", "/mibi/")

    /**
     * 这个值看起来是不是**卡面**的地址。
     *
     * 这是整套判据的核心（见文件头「语义守卫」）：先要求它是一张图片地址
     * （[looksLikeImageAddress]），再排掉已知的小图标特征（[NOT_CARD_FACE_TOKENS]）。
     * 判据宽松一点没关系（顶多是多捕获一张无关的图），但**绝不能把普通字符串
     * 当成图片地址去替换** —— 那会把卡号、卡名换成一张图的地址。
     */
    private fun isCardFaceAddress(value: Any?): Boolean {
        val text = (value as? String)?.trim().orEmpty()
        if (!looksLikeImageAddress(text)) return false
        if (!WalletCardFaceCapture.isSupportedAddress(text)) return false
        if (text.startsWith("content://${NfcCardImage.AUTHORITY}/")) return false
        val lower = text.lowercase()
        return NOT_CARD_FACE_TOKENS.none { lower.contains(it) }
    }

    private fun capture(value: Any?, tier: Int, forceRetry: Boolean = false) {
        val candidate = (value as? String)?.trim() ?: return
        if (!WalletCardFaceCapture.isCandidateAddress(candidate) || tier != TIER_CARD_FACE) return
        val address = candidate
        val now = SystemClock.elapsedRealtime()
        val attempt = synchronized(attempts) {
            val previous = attempts[address]
            if (previous != null && previous.tier <= tier &&
                (previous.completed || (!forceRetry && now - previous.attemptedAt < RETRY_INTERVAL_MS))) return
            CaptureAttempt(minOf(tier, previous?.tier ?: tier), now).also {
                attempts[address] = it
                if (attempts.size > MAX_TRACKED_ADDRESSES) {
                    val iterator = attempts.entries.iterator()
                    iterator.next()
                    iterator.remove()
                }
            }
        }
        try {
            captureExecutor.execute {
                val delivered = runCatching {
                    val context = hostContext ?: currentApplication()?.also { hostContext = it }
                        ?: return@runCatching false
                    WalletCardFaceCapture.publish(context, address, attempt.tier)
                }.getOrElse {
                    logOnce("capture/${it.javaClass.name}") {
                        "$FEATURE: card face delivery failed (${it.javaClass.simpleName}); will retry"
                    }
                    false
                }
                synchronized(attempts) {
                    if (attempts[address] === attempt) attempt.completed = delivered
                }
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(address.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
                logOnce("delivery/$delivered/$digest") {
                    "$FEATURE: delivery accepted=$delivered imageId=$digest protocol=${WalletCardFaceCapture.PROTOCOL_VERSION}"
                }
            }
        } catch (failure: Throwable) {
            synchronized(attempts) {
                if (attempts[address] === attempt) attempts.remove(address)
            }
            logOnce("capture/queue") { "$FEATURE: capture queue busy; keeping wallet image unchanged" }
        }
    }

    private fun currentApplication(): Context? = Reflect.attempt {
        Class.forName("android.app.ActivityThread").getDeclaredMethod("currentApplication")
            .also { it.isAccessible = true }.invoke(null) as? Application
    }

    private fun installCaptureContext(loader: ClassLoader): Int {
        val application = Reflect.loadClass(loader, "android.app.Application") ?: return 0
        val attach = Reflect.firstMethod(application, "attach") {
            it.parameterTypes.contentEquals(arrayOf(Context::class.java))
        } ?: return 0
        return if (HookRuntime.hookAfter(attach, "$FEATURE/Application#attach") { chain, original ->
                val context = chain.thisObject as? Application ?: chain.args.firstOrNull() as? Context
                if (context != null) {
                    hostContext = context
                    val retry = synchronized(attempts) { attempts.map { it.key to it.value.tier } }
                    retry.forEach { (address, tier) -> capture(address, tier, forceRetry = true) }
                }
                original
            }) 1 else 0
    }


    // ---------------------------------------------------------------- 拦截形状

    /**
     * 图片地址在**返回值**里：先让它正常算完（顺便捕获原值），再按需顶掉结果。
     *
     * @param addressFromArgs 该用**哪一个值**判断「这是不是卡面」：
     *   - `true`（①）：地址在参数里。`CustomGlideUrl` 的返回值是一个 `GlideUrl` 对象
     *     （`com.miui.tsmclient.util.n0 extends com.bumptech.glide.load.model.GlideUrl`），
     *     拿它去比「像不像图片地址」永远为假 —— 早先版本就是这么写的，结果是**替换从不发生**。
     *   - `false`（③）：地址就是返回值本身（一个 `String`）。
     *
     * @param gatedBySuperIsland 是否受「超级岛卡面」开关管辖。只有 ③ 受它管 ——
     *   ① 是钱包自己的卡面入口，用户关掉超级岛开关时不该连带把它关掉。
     */
    private fun installResultHook(
        method: Method,
        label: String,
        gatedBySuperIsland: Boolean,
        addressFromArgs: Boolean,
        tier: Int,
    ): Boolean = HookRuntime.hook(method, "$FEATURE/$label#${method.name}") { chain ->
        if (addressFromArgs) {
            val address = (chain.args.getOrNull(0) as? String)?.trim()
            val replacement = configuredImages[address]
            if (replacement != null) {
                capture(address, TIER_CARD_FACE)
                return@hook replacement
            }
            return@hook chain.proceed()
        }
        val original = chain.proceed()
        val address = original
        // 「装上了但根本没被调用」和「被调用了但判据没通过」必须能分开 —— 日志只有后者时，
        // 排查方向会整个歪掉（去改判据，而真正的问题是挂载点已经不是那个方法了）。
        // 所以首次进入无条件记一行；只记首次，因为 ② 在热路径上。
        logOnce("$label/reached") {
            "$FEATURE: $label reached, address=${preview(address)}"
        }
        if (WalletOwnedCard.accepts(chain.args.firstOrNull())) capture(address, TIER_CARD_FACE)
        val replacement = configuredImages[(address as? String)?.trim()]
        if (replacement == null ||
            (gatedBySuperIsland && !replaceSuperIsland) ||
            address !is String
        ) {
            original
        } else {
            logOnce("$label/replaced") { "$FEATURE: replace card face ($label)" }
            replacement
        }
    }

    /**
     * **只捕获、不替换**。
     *
     * 给 ② 用：它是 Glide 的通用载入入口，而本模块只有一张全局图 ——
     * 在这一层替换等于把钱包里所有图片都换成用户的卡面（见文件头）。
     * 只捕获就够有价值了：它能把「不走 `CustomGlideUrl` 的那几张卡面」也带回来。
     */
    private fun installCaptureOnlyHook(method: Method, label: String): Boolean =
        HookRuntime.hook(method, "$FEATURE/$label#${method.name}") { chain ->
            val address = (chain.args.firstOrNull() as? String)?.trim()
            val replacement = configuredImages[address]
            if (replacement == null) chain.proceed()
            else chain.proceed(arrayOf<Any?>(replacement))
        }

    /** 同一个键只记一次。 */
    private fun logOnce(key: String, message: () -> String) {
        if (loggedOnce.add(key)) ModuleLog.info(message())
    }

    /**
     * 日志里显示的值。**截断**：卡面地址是网络地址，可能带签名参数，
     * 全文进日志没有额外价值，只有泄漏面。前 72 个字符足够看出「是不是图片地址」。
     */
    private fun preview(value: Any?): String {
        val text = value?.toString() ?: return "null"
        val type = value.javaClass.simpleName
        return if (text.length <= 72) "$type($text)" else "$type(${text.take(72)}…)"
    }

    // ---------------------------------------------------------------- 挂载点

    /** 日志里的挂载点名。同时被 [HookRuntime.hook] 当作 hook 的稳定 id（每代必须一致）。 */
    private const val LABEL_FACTORY = "cardFace/returnValue"
    private const val LABEL_SUPER_ISLAND = "superIsland/returnValue"
    private const val LABEL_GLIDE = "glide/captureOnly"

    /**
     * 三条挂载点，逐条定位（判据与唯一性的依据见文件头）。
     *
     * @return 装上的 hook 数
     */
    private fun installHooks(loader: ClassLoader): Int {
        var count = installOwnedCardCapture(loader)
        var located = 0

        locateCardFaceFactory(loader)?.let {
            located++
            if (installResultHook(it, LABEL_FACTORY, gatedBySuperIsland = false, addressFromArgs = true, tier = TIER_CARD_FACE)) count++
        }
        locateSuperIsland(loader)?.let {
            located++
            if (installResultHook(it, LABEL_SUPER_ISLAND, gatedBySuperIsland = true, addressFromArgs = false, tier = TIER_CARD_FACE)) count++
        }
        locateGlideLoader(loader)?.let {
            located++
            if (installCaptureOnlyHook(it, LABEL_GLIDE)) count++
        }

        ModuleLog.info("$FEATURE: located $located/3 mount point(s), installed $count hook(s)")
        if (count == 0) {
            ModuleLog.warn("$FEATURE: no hook installed — host version likely differs")
        }
        return count
    }

    private fun installOwnedCardCapture(loader: ClassLoader): Int {
        var installed = 0
        val names = listOf("CardInfo", "BankCardInfo", "MifareCardInfo", "PayableCardInfo")
        for (name in names) {
            val type = Reflect.loadClass(loader, "com.miui.tsmclient.entity.$name") ?: continue
            val observers = type.declaredMethods.filter {
                (it.name == "getImageUrl" && it.parameterCount == 0 && it.returnType == String::class.java) ||
                    (it.name == "parse" && it.parameterTypes.contentEquals(arrayOf(org.json.JSONObject::class.java)) &&
                        it.returnType.name == CARD_INFO_CLASS) ||
                    (it.name == "updateInfo" && it.parameterCount == 1 && it.parameterTypes[0].name == CARD_INFO_CLASS)
            }
            for (observer in observers) {
                if (HookRuntime.hookAfter(observer, "$FEATURE/owned/$name/${observer.name}") { chain, original ->
                        val accepted = WalletOwnedCard.accepts(chain.thisObject)
                        val address = if (observer.name == "getImageUrl") original as? String
                            else WalletOwnedCard.imageAddress(chain.thisObject)
                        val digest = address?.let {
                            java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray())
                                .take(8).joinToString("") { byte -> "%02x".format(byte) }
                        } ?: "none"
                        logOnce("owned/$name/${observer.name}/$accepted/$digest") {
                            "$FEATURE: source=$name.${observer.name} owned=$accepted imageId=$digest"
                        }
                        if (accepted) capture(address, TIER_CARD_FACE)
                        original
                    }) installed++
            }
            val method = type.declaredMethods.firstOrNull {
                it.name == "getCardArt" && it.parameterCount == 0 && it.returnType == String::class.java
            } ?: continue
            if (HookRuntime.hookAfter(method, "$FEATURE/owned/$name") { chain, original ->
                    val accepted = WalletOwnedCard.accepts(chain.thisObject)
                    val digest = (original as? String)?.let { address ->
                        java.security.MessageDigest.getInstance("SHA-256")
                            .digest(address.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
                    } ?: "none"
                    logOnce("owned/$name/$accepted/$digest") {
                        "$FEATURE: source=${chain.thisObject?.javaClass?.name} owned=$accepted imageId=$digest"
                    }
                    if (accepted) capture(WalletOwnedCard.imageAddress(chain.thisObject) ?: original, TIER_CARD_FACE)
                    original
                }) installed++
        }
        return installed
    }





    /** ① `com.miui.tsmclient.util.n0#i(String)` —— `CustomGlideUrl` 的卡面地址构造点。 */
    private fun locateCardFaceFactory(loader: ClassLoader): Method? =
        uniqueCandidate(loader, LABEL_FACTORY, PKG_TSM_UTIL) { method ->
            Modifier.isPublic(method.modifiers) &&
                Modifier.isStatic(method.modifiers) &&
                method.parameterCount == 1 &&
                method.parameterTypes[0] == String::class.java &&
                method.returnType == Any::class.java
        }

    /** ③ `com.miui.tsmclient.util.k3#k(CardInfo)` —— 超级岛通知上的卡面。 */
    private fun locateSuperIsland(loader: ClassLoader): Method? =
        uniqueCandidate(loader, LABEL_SUPER_ISLAND, PKG_TSM_UTIL) { method ->
            Modifier.isPrivate(method.modifiers) &&
                Modifier.isStatic(method.modifiers) &&
                method.parameterCount == 1 &&
                method.parameterTypes[0].name == CARD_INFO_CLASS &&
                method.returnType == String::class.java
        }

    /**
     * ② Glide 的载入入口（`com.bumptech.glide.l#u(String)`，即 `RequestManager#load`）。
     *
     * 判据分三层，缺一层就会多候选（数字见文件头）：
     * 1. 签名：public、非静态、单 `String` 参数、返回类型在 `com.bumptech.glide` 下；
     * 2. 剔掉 `valueOf` —— R8 给枚举合成的静态工厂，命名固定、参数正好也是 `String`；
     * 3. 结构：声明类自己持有 `com.bumptech.glide.manager.*` 字段，见 [ownsRequestTrackers]。
     */
    private fun locateGlideLoader(loader: ClassLoader): Method? {
        val hits = collectCandidates(loader, PKG_GLIDE) { method ->
            Modifier.isPublic(method.modifiers) &&
                !Modifier.isStatic(method.modifiers) &&
                method.parameterCount == 1 &&
                method.parameterTypes[0] == String::class.java &&
                method.returnType.name.startsWith("$PKG_GLIDE.") &&
                method.name != "valueOf" &&
                ownsRequestTrackers(method.declaringClass)
        }
        return when (hits.size) {
            1 -> hits[0].also {
                ModuleLog.info("$FEATURE: $LABEL_GLIDE -> ${it.declaringClass.name}#${it.name}")
            }

            0 -> {
                ModuleLog.warn("$FEATURE: $LABEL_GLIDE not located under $PKG_GLIDE")
                null
            }

            else -> {
                ModuleLog.warn(
                    "$FEATURE: $LABEL_GLIDE ambiguous (${hits.size}) — refusing to guess",
                )
                null
            }
        }
    }

    /**
     * `RequestManager` 的结构特征：它自己持有 Glide 的 tracker / lifecycle 字段
     * （`com.bumptech.glide.manager.j/p/o/r/b` 这些）。
     *
     * 为什么需要这条：`RequestManager#load(String)` 与 `RequestBuilder#load(String)` 的
     * 签名**逐字相同**（都返回 `com.bumptech.glide.k`），光看方法无法区分。
     * 而 `RequestBuilder` 的字段只有 `requestOptions`、`context`、`parent` 这类，
     * 没有任何 `manager.*` 字段 —— 于是这条判据把两者干净地分开。
     *
     * 判据只用于缩小范围：取不到、或者将来宿主改了字段布局，这里返回 false 的结果是
     * 「② 不装」，而 ① 与 ③ 照常工作。
     */
    private fun ownsRequestTrackers(clazz: Class<*>): Boolean =
        Reflect.attempt {
            clazz.declaredFields.any { it.type.name.startsWith(GLIDE_MANAGER_PKG) }
        } == true

    /** [packagePrefix] 下满足 [predicate] 的全部方法（顺序稳定：先按 dex 的类表顺序）。 */
    private fun collectCandidates(
        loader: ClassLoader,
        packagePrefix: String,
        predicate: (Method) -> Boolean,
    ): List<Method> {
        val out = ArrayList<Method>()
        forEachCandidate(loader, packagePrefix) { method ->
            if (Reflect.attempt { predicate(method) } == true) out += method
        }
        return out
    }

    /**
     * 只在**唯一**命中时才返回那个方法。
     *
     * 0 个与多个都返回 null，并把候选名字打进日志。坚持这条的理由：宿主里大部分
     * 「形状相同」的方法都在干别的事（返回卡号、设备号、开关状态），随手挂一个上去的后果是
     * 「功能生效了但界面坏了一块」—— 那是最难归因的一类故障。定位失败是可诊断的
     * （日志里有一行明确的 not located / ambiguous），挂错不是。
     */
    private fun uniqueCandidate(
        loader: ClassLoader,
        label: String,
        packagePrefix: String,
        predicate: (Method) -> Boolean,
    ): Method? {
        val hits = collectCandidates(loader, packagePrefix, predicate)
        return when (hits.size) {
            1 -> hits[0].also {
                ModuleLog.info("$FEATURE: $label -> ${it.declaringClass.name}#${it.name}")
            }

            0 -> {
                ModuleLog.warn("$FEATURE: $label not located under $packagePrefix")
                null
            }

            else -> {
                ModuleLog.warn(
                    "$FEATURE: $label ambiguous (${hits.size}: " +
                        hits.take(8).joinToString { "${it.declaringClass.simpleName}#${it.name}" } +
                        ") — refusing to guess",
                )
                null
            }
        }
    }

    /**
     * 把 [packagePrefix] 包下所有**顶层**类逐个交给 [action]。
     *
     * 类名表来自宿主的 dex（[DexScan]），类本身用宿主的 loader 加载 ——
     * 用模块自己的 loader 也能解析到宿主类，但解析失败时没有任何退路。
     *
     * 不含内部类（`DexScan.classNamesUnder` 的过滤）：三条挂载点都是顶层类，
     * 而 util 包下有几十个内部类，扫描它们只会多花时间与多制造候选。
     *
     * 遍历**全部** codePath 而不是只看第一个：智能卡目前是单 APK，但 split 里的类不会
     * 出现在 base 的 dex 表里（`DexFile(base.apk)` 覆盖的是 base 自己那几个 dex），
     * 只认第一个的话，将来宿主一旦拆包，功能会**静默失效**。
     */
    private inline fun forEachCandidate(
        loader: ClassLoader,
        packagePrefix: String,
        action: (Method) -> Unit,
    ) {
        val codePaths = HookRuntime.codePaths
        if (codePaths.isEmpty()) {
            ModuleLog.warn("$FEATURE: host dex path unknown, cannot scan $packagePrefix")
            return
        }
        val classNames = LinkedHashSet<String>()
        for (codePath in codePaths) {
            classNames += DexScan.classNamesUnder(codePath, packagePrefix)
        }
        if (classNames.isEmpty()) {
            ModuleLog.warn("$FEATURE: no classes under $packagePrefix")
            return
        }
        for (name in classNames) {
            val clazz = Reflect.attempt { Class.forName(name, false, loader) } ?: continue
            for (method in clazz.declaredMethods) {
                Reflect.attempt { action(method) }
            }
        }
    }
}
