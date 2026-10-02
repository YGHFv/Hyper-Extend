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

package io.github.YGHFv.HyperExtend.hook

import android.content.pm.ApplicationInfo
import dalvik.system.DexFile
import io.github.YGHFv.HyperExtend.core.ModuleLog

/**
 * 宿主 APK 的类名扫描。
 *
 * ## 为什么需要它
 *
 * 模块所有的靶子都靠写死的类名去找，而澎湃 OS 每代都会把若干内部类**换包名**——
 * 同一个类在 OS1 叫 `a.b.C`、在 OS3 叫 `a.c.d` 是家常便饭。写死名字的后果是
 * 「升级系统后功能静默失效」，而用户根本拿不到任何提示。
 *
 * 这里给出的是一条**兜底路**：按「类名后缀」在宿主自己的 dex 里扫一遍。
 * 对标的是上游 HyperWallpaperMonet 对 `ThemeOverlayController$` 内部类的处理方式
 * （它就是这么找到壁纸颜色监听器的），做法本身与实现无关，属于通用技术手段。
 *
 * ## 只在精确名字失败后才用
 *
 * 扫描要打开宿主 APK 的全部 dex，在 SystemUI 进程里是几十毫秒级的一次性开销。
 * 放在启动路径上无谓地做，只会拖慢状态栏首次出现。所以 feature 里的用法一律是
 * 「先按已知名字加载；失败才扫」。
 *
 * ⚠️ 唯一的例外是 NFC 卡面：它的靶子（卡面地址的构造点）在混淆后面目全非、没有可用的
 * 全限定名，只能按「包前缀 + 方法签名」扫。那种情况下扫描是主路径，速度就更要紧了 ——
 * 这也是 [prefixCache] 存在的原因。
 *
 * ## 枚举发生在宿主的主线程上
 *
 * `onPackageReady` 是在宿主进程主线程上回调的，所以这里每一次枚举都是**阻塞主线程**的。
 * 状态栏、设置界面的首次绘制都在那之后，多枚举一次就是多几十毫秒的白屏。
 * 缓存的收益因此不是「省点 CPU」，而是直接决定宿主启动那一下卡不卡。
 *
 * ## 后缀匹配的边界
 *
 * 只接受**最内层简单名完全相同**的类（`a.b.NavigationHandle` 命中 `NavigationHandle`，
 * `a.b.NavigationHandleKt` 不命中）。放宽到 `endsWith("Handle")` 会把一堆无关类捞进来，
 * 而捞错了的代价是在错误的类上挂 hook —— 比不挂更糟。
 */
internal object DexScan {

    private const val CACHE_LIMIT = 40_000

    /**
     * 全量类名表，**只在一次分派内有效**。
     *
     * 为什么需要它：枚举一个 dex 要把整个 dex 文件打开读一遍，SystemUI 那种体量是几十毫秒级，
     * 而一次分派里可能要问好几次（卡面要按两个包前缀各问一次、壁纸要找内部类、手势横条要找兜底类）。
     * 不缓存的话这些开销会**全部发生在宿主进程的主线程上**（`onPackageReady` 就在主线程），
     * 表现出来就是「启用了模块之后系统界面启动变慢、整机跟着一顿」。
     *
     * 为什么不是「永久缓存」：SystemUI 有上万条类名，全部驻留是接近 1MB 的字符串。
     * 所以它的生命周期只在一次分派内 —— 分派结束时由 [clearCache] 清空。
     */
    private val fullCache = HashMap<String, List<String>>(2)

    /**
     * 按包前缀过滤后的小表，**进程生命周期内一直保留**。
     *
     * 与 [fullCache] 分开的理由是**内存与收益的比值完全不同**：全量表动辄上万条、
     * 用完就没用；而一个包前缀下的类通常只有几十到几百条（`com.bumptech.glide`、
     * `com.miui.tsmclient.util` 都是这个量级），留下来的代价可以忽略，
     * 换到的是「热重载之后再问同一批类名不必再枚举一遍 dex」。
     *
     * 键里带着 dex 路径：同一台设备上会有多个被注入进程，各自读自己的宿主 APK。
     */
    private val prefixCache = HashMap<String, List<String>>(8)

    private fun fullNames(codePath: String): List<String> {
        synchronized(fullCache) { fullCache[codePath] }?.let { return it }
        val names = enumerate(codePath)
        synchronized(fullCache) { fullCache[codePath] = names }
        return names
    }

    /** 清空全量类名缓存。分派流程结束时调用 —— 理由见 [fullCache] 的注释。 */
    fun clearCache() {
        synchronized(fullCache) { fullCache.clear() }
    }

    /**
     * [packagePrefix] 包下（不含内部类）的全部类名。
     *
     * 给「按包前缀定位方法」的调用方用（卡面、通行密钥的设置侧各有一处）：
     * 它们要的是「这个包里所有候选类」，由这里统一做过滤与缓存，
     * 免得每个调用点各枚举一遍、各写一次过滤条件。
     */
    fun classNamesUnder(codePath: String, packagePrefix: String): List<String> {
        val key = "$codePath|$packagePrefix"
        synchronized(prefixCache) { prefixCache[key] }?.let { return it }
        val filtered = fullNames(codePath).filter { it.startsWith(packagePrefix) && !it.contains('$') }
        synchronized(prefixCache) { prefixCache[key] = filtered }
        return filtered
    }

    /** 按简单名找类。命中多个时返回第一个能加载的（同名不同包的情况极少，交给加载成功与否裁决）。 */
    fun findBySimpleName(
        loader: ClassLoader,
        codePaths: List<String>,
        simpleName: String,
        packagePrefix: String? = null,
    ): Class<*>? {
        for (codePath in codePaths) {
            for (name in fullNames(codePath)) {
                if (!name.endsWith(".$simpleName") && name != simpleName) continue
                if (packagePrefix != null && !name.startsWith(packagePrefix)) continue
                val clazz = Reflect.attempt { Class.forName(name, false, loader) }
                if (clazz != null) {
                    ModuleLog.info("dex scan hit: $simpleName -> $name")
                    return clazz
                }
            }
        }
        ModuleLog.warn("dex scan miss: $simpleName (paths=${codePaths.size})")
        return null
    }

    /** 简单名以 [prefix] 开头的全部类名（例如 `ThemeOverlayController$` 的内部类）。 */
    fun namesWithSimpleNamePrefix(
        codePaths: List<String>,
        prefix: String,
        limit: Int = 64,
    ): List<String> {
        val result = ArrayList<String>()
        for (codePath in codePaths) {
            for (name in fullNames(codePath)) {
                val simple = name.substringAfterLast('.')
                if (simple.startsWith(prefix)) {
                    result += name
                    if (result.size >= limit) return result
                }
            }
        }
        return result
    }

    /**
     * 一个 APK 里的**全部** dex 的类名 —— 多 dex 也在内。
     *
     * 这一条是实测过的，不是推断：Android 16 上 `new DexFile(<多 dex APK>)` 的 `entries()`
     * 覆盖全部分片。用探针（`dalvikvm -cp probe.jar DexProbe <apk>`，源码在
     * `.workbuddy/tmp/dexprobe/`）在真实智能卡 APK 上量到 **18568** 个类，
     * 恰好等于 classes.dex 8922 + classes2.dex 7965 + classes3.dex 1681。
     * 所以「卡面的 ① 与 ③ 落在 classes2.dex」不会让扫描漏掉它们。
     *
     * 网上流传的「`new DexFile(apkPath)` 不适用于多 dex」在本机不成立；照那个说法去改，
     * 反而要引入 `DexPathList#dexElements` 那套灰名单反射（探针里试过，本机
     * `dexElements.length == 1`，比 `DexFile` 这条路更绕且收益为零）。
     *
     * ⚠️ `DexFile(String)` / `entries()` / `close()` 在 Android 10+ 被标了 deprecated，
     * 但**没有替代品**：`DexFile` 至今仍是「拿到一个 dex 里全部类名」的唯一公开入口，
     * 新 API（`DexFile.loadDex`）同样返回 DexFile。所以这里显式压制警告，
     * 而不是换成别的写法 —— 换过去只是把 deprecated 换到另一个方法上。
     */
    @Suppress("DEPRECATION")
    private fun enumerate(codePath: String): List<String> {
        val names = ArrayList<String>(2048)
        var dex: DexFile? = null
        try {
            dex = DexFile(codePath)
            val entries = dex.entries()
            while (entries.hasMoreElements() && names.size < CACHE_LIMIT) {
                names += entries.nextElement()
            }
        } catch (t: Throwable) {
            ModuleLog.warn("dex enumerate failed: $codePath (${t.javaClass.simpleName})")
        } finally {
            Reflect.attempt { dex?.close() }
        }
        return names
    }

    /** 从 ApplicationInfo 收集主 APK 与全部 split 的路径。 */
    fun codePathsOf(info: ApplicationInfo?): List<String> {
        if (info == null) return emptyList()
        val paths = ArrayList<String>(4)
        info.sourceDir?.let { paths += it }
        info.splitSourceDirs?.forEach { if (it != null) paths += it }
        return paths
    }
}
