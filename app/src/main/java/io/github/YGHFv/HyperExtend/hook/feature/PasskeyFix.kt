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
 * 功能来源：修复澎湃系统通行密钥（Howard20181，GPL-3.0）。
 *
 * 四组挂载点均取自上游 `PasskeyHook`：
 *
 *   ① com.android.settings —— 在若干「枚举/绑定凭据提供方」的调用期间，把
 *      `miui.os.Build.IS_INTERNATIONAL_BUILD` 临时掰成 true（国际版分支才会列出
 *      Google 的提供方），并在 Android 16 上补一次 CombiPreference 的开关绑定。
 *   ② com.miui.securitycenter —— 拦掉开机时把 autofill_service / credential_service
 *      覆写回小米默认值的写入。
 *   ③ com.xiaomi.scanner —— MiFiDoBean.getAppPackageName 返回空，避免扫描器带错调用方。
 *   ④ system_server —— RequestSession 的 mHybridService 指向 Google 的远程提供方；
 *      IntentFactory 的 OEM 覆盖组件名指向 Google 的凭据选择器。
 *
 * ⚠️ 与上游的两处差异（刻意）：
 *
 * 1. **securitycenter 不用 DexKit 找方法体，而是直接拦 Settings.Secure 的写入本身**。
 *    上游靠字节码特征（「调用了 Resources.getStringArray」+「含 credential_service 字符串」）
 *    定位那个覆写方法，这需要 native 的 DexKit。本模块改为挂
 *    `Settings.Secure.putString / putStringForUser`，并按**键名**过滤只拦那三个键。
 *    效果等价（甚至更精确：上游拦整个方法，本实现只拦这几个键的写入），
 *    且不依赖任何 native 库、不受 R8 改名影响。
 * 2. **只做「找不到就跳过」**。上游有若干版本分支（SDK 34 / 35 / 36 各挂一组），
 *    本实现一律按「类/方法存在与否」现场判断，而不是先看 SDK 号再去猜 ——
 *    HyperOS 的 SDK 号与功能开关不总是一一对应。
 */

package io.github.YGHFv.HyperExtend.hook.feature

import android.content.ComponentName
import android.content.ContentResolver
import android.os.Build
import android.provider.Settings
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.DexScan
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.HostPlatform
import io.github.YGHFv.HyperExtend.hook.Reflect
import io.github.YGHFv.HyperExtend.hook.UnsafeAccess
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.util.concurrent.locks.ReentrantLock

/** 「通行密钥修复」。作用域：系统设置 / 安全中心 / 小米扫描器 / system_server。 */
internal object PasskeyFix {

    private const val FEATURE = "passkey_fix"

    /** Google Play 服务的远程凭据提供方（凭据会话要指向它）。 */
    private const val GOOGLE_REMOTE_SERVICE =
        "com.google.android.gms/.auth.api.credentials.credman.service.RemoteService"

    /** Google 的凭据选择器界面。 */
    private const val GOOGLE_CREDENTIAL_CHOOSER =
        "com.google.android.gms/.identitycredentials.ui.CredentialChooserActivity"

    private const val GOOGLE_PACKAGE = "com.google.android.gms"

    /** 被安全中心覆写、必须拦下的三个 Settings.Secure 键。 */
    private val PROTECTED_SETTINGS_KEYS = setOf(
        "autofill_service",
        "credential_service",
        "credential_service_primary",
    )

    // ------------------------------------------------------------------ 入口

    fun installPackage(packageName: String, loader: ClassLoader, settings: HookSettings): String {
        // 这个功能修的是「澎湃把凭据提供方指成小米自己的实现」。非小米 ROM 上没有这个问题，
        // 而下面的靶子里 RequestSession / IntentFactory 都是 **AOSP 类** —— 在类原生系统上挂它们
        // 会把凭据会话错误地重定向到 Google GMS，属于「修出一个原本不存在的故障」。先问 ROM 再动手。
        if (!HostPlatform.isXiaomiRom(loader)) {
            HostPlatform.logSkip(FEATURE, "not a Xiaomi ROM — $packageName skipped")
            return "$packageName: skipped (not xiaomi)"
        }
        return when (packageName) {
            HostPlatform.SETTINGS -> {
                if (!settings.isOn("passkey_fix.settings")) {
                    "settings: disabled"
                } else {
                    "settings=${installSettings(loader)}"
                }
            }

            HostPlatform.SECURITY_CENTER -> {
                if (!settings.isOn("passkey_fix.security_center")) {
                    "securitycenter: disabled"
                } else {
                    "securitycenter=${installSecurityCenter()}"
                }
            }

            HostPlatform.XIAOMI_SCANNER -> {
                if (!settings.isOn("passkey_fix.scanner")) {
                    "scanner: disabled"
                } else {
                    "scanner=${installScanner(loader)}"
                }
            }

            else -> "unsupported host"
        }
    }

    fun installSystemServer(loader: ClassLoader, settings: HookSettings): String {
        // 同 installPackage：system_server 侧的 RequestSession / IntentFactory 是 AOSP 类，
        // 在非小米 ROM 上挂它们等于凭空改掉原生凭据行为。
        if (!HostPlatform.isXiaomiRom(loader)) {
            HostPlatform.logSkip(FEATURE, "not a Xiaomi ROM — system_server skipped")
            return "system_server: skipped (not xiaomi)"
        }
        return if (!settings.isOn("passkey_fix.system_server")) {
            "system_server: disabled"
        } else {
            "system_server=${installSystemServerHooks(loader)}"
        }
    }

    // ------------------------------------------------------------------ ③ 扫描器

    /**
     * 小米扫描器的「被扫描对象包名」。
     *
     * 扫描器拉起通行密钥时会把自己算出来的调用方包名传下去，而这个值在澎湃上算错，
     * 于是凭据管理器判定调用方不可信、直接不给凭据。返回空串即「没有可用的调用方」，
     * 由系统退回默认的调用方判定。
     */
    private fun installScanner(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(
            loader,
            "com.xiaomi.scanner.module.code.utils.bean.MiFiDoBean",
        ) ?: run {
            ModuleLog.warn("$FEATURE: MiFiDoBean not found (scanner version changed?)")
            return 0
        }
        val method = Reflect.findMethods(clazz, "getAppPackageName", paramCount = 0).firstOrNull()
        if (method == null) {
            ModuleLog.warn("$FEATURE: MiFiDoBean#getAppPackageName not found")
            return 0
        }
        return if (HookRuntime.hookReturning(method, "$FEATURE/scanner#getAppPackageName", "")) 1 else 0
    }

    // ------------------------------------------------------------------ ② 安全中心

    /**
     * 拦掉安全中心对 autofill / credential 配置的覆写。
     *
     * 做法：挂 `Settings.Secure` 的写入入口，**只拦** [PROTECTED_SETTINGS_KEYS] 里的键，
     * 返回 `true` 假装写成功 —— 若返回 false，安全中心可能会重试或改变后续流程。
     *
     * 这个进程里除了安全中心自己不会有别人调这两个 API（作用域就只有它），
     * 不会有误伤别人的风险。
     */
    private fun installSecurityCenter(): Int {
        var count = 0
        val putString = Reflect.findMethod(
            Settings.Secure::class.java,
            "putString",
            ContentResolver::class.java,
            String::class.java,
            String::class.java,
        )
        if (putString != null) {
            count += if (HookRuntime.hook(putString, "$FEATURE/securitycenter#Settings.Secure.putString") { chain ->
                    val key = chain.args.getOrNull(1) as? String
                    if (key != null && key in PROTECTED_SETTINGS_KEYS) {
                        ModuleLog.info("$FEATURE: blocked Settings.Secure write of $key")
                        true
                    } else {
                        chain.proceed()
                    }
                }
            ) 1 else 0
        } else {
            ModuleLog.warn("$FEATURE: Settings.Secure#putString not found")
        }

        // putStringForUser 是另一条入口（安全中心在部分版本上走它）。
        val putStringForUser = Reflect.firstMethod(Settings.Secure::class.java, "putStringForUser") {
            it.parameterCount >= 3 &&
                it.parameterTypes[0] == ContentResolver::class.java &&
                it.parameterTypes[1] == String::class.java
        }
        if (putStringForUser != null) {
            count += if (HookRuntime.hook(putStringForUser, "$FEATURE/securitycenter#Settings.Secure.putStringForUser") { chain ->
                    val key = chain.args.getOrNull(1) as? String
                    if (key != null && key in PROTECTED_SETTINGS_KEYS) {
                        ModuleLog.info("$FEATURE: blocked Settings.Secure.putStringForUser of $key")
                        true
                    } else {
                        chain.proceed()
                    }
                }
            ) 1 else 0
        }
        return count
    }

    // ------------------------------------------------------------------ ① 系统设置

    /**
     * 系统设置里的凭据提供方枚举与绑定。
     *
     * 核心手法：`IS_INTERNATIONAL_BUILD` **临时**掰成 true。
     * 用 [InternationalBuildScope] 保证「进入时改、退出时还原」，且嵌套调用只改一次 ——
     * 若改成永久 true，设置进程里所有按这个常量分支的逻辑都会走到国际版路径，
     * 而它们的国际版路径依赖的组件在国行 ROM 上并不存在。
     */
    private fun installSettings(loader: ClassLoader): Int {
        val internationalField = Reflect.staticFieldOrNull(
            Reflect.loadClass(loader, "miui.os.Build") ?: return 0,
            "IS_INTERNATIONAL_BUILD",
        )
        if (internationalField == null) {
            ModuleLog.warn("$FEATURE: miui.os.Build.IS_INTERNATIONAL_BUILD not found")
            return 0
        }
        if (!UnsafeAccess.isAvailable) {
            // 没有 Unsafe 就改不动 static final，这一组 hook 挂了也不会有效果 ——
            // 与其装上一堆「看起来成功了」的空 hook，不如明确报出来。
            ModuleLog.error("$FEATURE: Unsafe unavailable, settings hooks cannot take effect")
            return 0
        }

        val scope = InternationalBuildScope(internationalField)
        var count = 0

        // 默认凭据提供方选择器：确定默认项时会按 IS_INTERNATIONAL_BUILD 过滤候选。
        val picker = Reflect.loadClass(
            loader,
            "com.android.settings.applications.credentials.DefaultCombinedPicker",
        )
        count += hookUnderInternational(scope, picker, "setDefaultKey", 1, "DefaultCombinedPicker")

        // 提供方列表的计算入口。
        val controller = Reflect.loadClass(
            loader,
            "com.android.settings.applications.credentials.DefaultCombinedPreferenceController",
        )
        count += hookUnderInternational(
            scope,
            controller,
            "getCombinedProviderInfos",
            2,
            "DefaultCombinedPreferenceController",
        )

        // 点左侧（进入提供方自己的设置页）那一处。
        val credentialsPackage = "com.android.settings.applications.credentials"
        count += hookScannedMethodUnderInternational(scope, loader, credentialsPackage, "onLeftSideClicked")

        // Android 14 的旧路径（类存在就挂，不存在自动跳过）。
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val defaultApp = Reflect.loadClass(
                loader,
                "com.android.settings.applications.defaultapps.DefaultAppPreferenceController",
            )
            count += hookUnderInternational(scope, defaultApp, "updateState", 1, "DefaultAppPreferenceController")
        }

        // Android 16 的开关绑定修补。
        count += installCombiPreferenceBinding(loader)

        if (count == 0) ModuleLog.warn("$FEATURE: no settings hook installed")
        return count
    }

    /** 在 [clazz] 上按名字/参数个数找方法，找到就在「国际版作用域」里挂上。 */
    private fun hookUnderInternational(
        scope: InternationalBuildScope,
        clazz: Class<*>?,
        methodName: String,
        paramCount: Int?,
        label: String,
    ): Int {
        if (clazz == null) {
            ModuleLog.warn("$FEATURE: $label class not present")
            return 0
        }
        val methods = Reflect.findMethods(clazz, methodName, paramCount)
        if (methods.isEmpty()) {
            ModuleLog.warn("$FEATURE: ${clazz.simpleName}#$methodName not found")
            return 0
        }
        return methods.count { method ->
            HookRuntime.hook(method, "$FEATURE/$label#$methodName") { chain ->
                scope.around { chain.proceed() }
            }
        }
    }

    /** `onLeftSideClicked` 在哪个类里不好写死（上游也得靠字节码特征找），按包扫描唯一命中。 */
    private fun hookScannedMethodUnderInternational(
        scope: InternationalBuildScope,
        loader: ClassLoader,
        packagePrefix: String,
        methodName: String,
    ): Int {
        // 类名在宿主自己的 dex 里找（`HookRuntime.codePaths`），类本身用宿主的 loader 加载：
        // 用模块自己的 loader 也能解析到宿主类，但一旦解析不了就只能靠 `!!` 崩掉，
        // 而这里完全没有「加载失败就不能继续」的理由 —— 扫不到就少装一个，记日志即可。
        val candidates = HookRuntime.codePaths
            .flatMap { path -> DexScan.classNamesUnder(path, packagePrefix) }
        var installed = 0
        for (name in candidates) {
            val clazz = Reflect.attempt { Class.forName(name, false, loader) } ?: continue
            val method = Reflect.findMethods(clazz, methodName, paramCount = 0).firstOrNull() ?: continue
            if (HookRuntime.hook(method, "$FEATURE/${clazz.simpleName}#$methodName") { chain ->
                    scope.around { chain.proceed() }
                }
            ) installed++
        }
        if (installed == 0) ModuleLog.warn("$FEATURE: $methodName not found under $packagePrefix")
        return installed
    }

    /**
     * Android 16 上给凭据提供方那一行补上开关的绑定。
     *
     * 系统给 `CombiPreference` 用的 switch 是「自己建的」，在某些构建上 `mSwitch` 没被赋上，
     * 结果那一行开关既不显示当前状态、点了也没反应。做法是在 `onBindViewHolder` 返回后，
     * 从 itemView 里把 switch 找出来，手动接上 checked 与点击。
     *
     * 这一处涉及的字段/资源 id 最多，所以每一步都单独兜底：任何一步拿不到就放弃，
     * 绝不半途改一半 —— 半改的状态比不改更难用。
     */
    private fun installCombiPreferenceBinding(loader: ClassLoader): Int {
        val preferenceClass = Reflect.loadClass(
            loader,
            "com.android.settings.applications.credentials.CredentialManagerPreferenceController\$CombiPreference",
        ) ?: return 0
        val viewHolderClass = Reflect.loadClass(loader, "androidx.recyclerview.widget.RecyclerView\$ViewHolder")
            ?: return 0
        val settingsLibIdClass = Reflect.loadClass(loader, "com.android.settingslib.R\$id")
            ?: return 0

        val onBind = Reflect.findMethods(preferenceClass, "onBindViewHolder", paramCount = 1).firstOrNull()
        val checkedField = Reflect.findField(preferenceClass, "mChecked")
        val clickListenerField = Reflect.findField(preferenceClass, "mOnClickListener")
        val switchField = Reflect.findField(preferenceClass, "mSwitch")
        val switchIdField = Reflect.staticFieldOrNull(settingsLibIdClass, "switchWidget")
        val itemViewField = Reflect.findField(viewHolderClass, "itemView")

        val missing = buildList {
            if (onBind == null) add("onBindViewHolder")
            if (checkedField == null) add("mChecked")
            if (clickListenerField == null) add("mOnClickListener")
            if (switchField == null) add("mSwitch")
            if (switchIdField == null) add("settingslib:switchWidget")
            if (itemViewField == null) add("ViewHolder.itemView")
        }
        if (missing.isNotEmpty()) {
            ModuleLog.warn("$FEATURE: CombiPreference binding skipped, missing: ${missing.joinToString()}")
            return 0
        }

        return if (HookRuntime.hook(onBind, "$FEATURE/CombiPreference#onBindViewHolder") { chain ->
                val result = chain.proceed()
                bindCombiSwitch(
                    preference = chain.thisObject,
                    viewHolder = chain.args.getOrNull(0),
                    checkedField = checkedField!!,
                    clickListenerField = clickListenerField!!,
                    switchField = switchField!!,
                    switchIdField = switchIdField!!,
                    itemViewField = itemViewField!!,
                )
                result
            }
        ) 1 else 0
    }

    /**
     * 把 switch 接上。全部走反射 + 鸭子类型：这些类型（`CompoundButton`、
     * `PreferenceViewHolder`）在设置进程里存在，但 `androidx.preference` 的
     * `PreferenceViewHolder` 与 `CompoundButton` 都是公开类型，可以正常引用。
     */
    private fun bindCombiSwitch(
        preference: Any?,
        viewHolder: Any?,
        checkedField: Field,
        clickListenerField: Field,
        switchField: Field,
        switchIdField: Field,
        itemViewField: Field,
    ) {
        if (preference == null || viewHolder == null) return
        // 已经绑过就不再绑：onBindViewHolder 会被 RecyclerView 反复调用。
        if (Reflect.readField(preference, "mSwitch") != null) return

        val itemView = Reflect.attempt { itemViewField.get(viewHolder) as? android.view.View } ?: return
        val switchId = Reflect.attempt { switchIdField.getInt(null) } ?: return
        val switchView = Reflect.attempt { itemView.findViewById<android.view.View>(switchId) }
            as? android.widget.CompoundButton ?: return

        switchView.isChecked = Reflect.attempt { checkedField.getBoolean(preference) } ?: false
        switchView.setOnClickListener { button ->
            val listener = Reflect.attempt { clickListenerField.get(preference) } ?: return@setOnClickListener
            // 这里调用的是系统自己的 onCheckChanged(CombiPreference, boolean)，
            // 它需要的是「用户把开关掰成了什么」。调用失败时把开关恢复原状态，
            // 避免界面上显示成「开」而实际没生效。
            val accepted = Reflect.attempt {
                Reflect.callWith(listener, "onCheckChanged", preference, switchView.isChecked)
            }
            if (accepted == null) {
                ModuleLog.warn("$FEATURE: onCheckChanged failed, restoring switch state")
                switchView.isChecked = !switchView.isChecked
            }
        }
        Reflect.writeField(switchField, preference, switchView)
    }

    // ------------------------------------------------------------------ ④ system_server

    /**
     * system_server 侧的两处。
     *
     * ⚠️ 这里崩溃 = 开机循环，所以每一步都必须在「找不到就跳过」的路径上，
     * 并且拦截体内部绝不允许抛异常（[HookRuntime.hook] 已经兜住了）。
     */
    private fun installSystemServerHooks(loader: ClassLoader): Int {
        var count = 0
        count += installRequestSessionHook(loader)
        count += installIntentFactoryHook(loader)
        return count
    }

    /**
     * `RequestSession` 构造完成后，把 `mHybridService` 指向 Google 的远程提供方。
     *
     * 澎湃把「混合服务」默认指成小米自己的实现，而国行 ROM 上那个实现不完整，
     * 于是凭据会话既拿不到 Google 的凭据、也起不了 Google 的选择器。
     *
     * 构造函数的参数个数在各版本间会变（Android 16 多了一个 boolean），
     * 所以不写死签名，只要求「参数里出现 `CallingAppInfo`」——
     * 那个参数是凭据会话独有的，足以把主构造函数从重载里挑出来。
     */
    private fun installRequestSessionHook(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "com.android.server.credentials.RequestSession")
        if (clazz == null) {
            ModuleLog.warn("$FEATURE: RequestSession not found in system_server")
            return 0
        }
        val field = Reflect.findField(clazz, "mHybridService")
        if (field == null) {
            ModuleLog.warn("$FEATURE: RequestSession.mHybridService not found")
            return 0
        }
        val serviceValue = hybridServiceValue(field)
        if (serviceValue == null) {
            ModuleLog.warn(
                "$FEATURE: unsupported RequestSession.mHybridService type ${field.type.name}",
            )
            return 0
        }
        val callingAppInfo = Reflect.loadClass(loader, "android.service.credentials.CallingAppInfo")
        val constructor: Constructor<*>? = clazz.declaredConstructors
            .filter { ctor ->
                callingAppInfo == null || ctor.parameterTypes.any { it == callingAppInfo }
            }
            .maxByOrNull { it.parameterCount }
            ?: run {
                ModuleLog.warn("$FEATURE: RequestSession primary constructor not identified")
                return 0
            }
        return if (HookRuntime.hook(constructor, "$FEATURE/RequestSession#<init>") { chain ->
                chain.proceed()
                if (!UnsafeAccess.putObjectField(field, chain.thisObject, serviceValue)) {
                    // Unsafe 不可用时退回普通反射：mHybridService 不是 final 的话也能成。
                    Reflect.writeField(field, chain.thisObject, serviceValue)
                }
                ModuleLog.info("$FEATURE: RequestSession.mHybridService -> $serviceValue")
                null
            }
        ) 1 else 0
    }

    /** 字段声明成 String 还是 ComponentName，取决于版本 —— 按其声明类型投喂。 */
    private fun hybridServiceValue(field: Field): Any? = when {
        ComponentName::class.java.isAssignableFrom(field.type) ->
            ComponentName.unflattenFromString(GOOGLE_REMOTE_SERVICE)
        field.type.isAssignableFrom(String::class.java) -> GOOGLE_REMOTE_SERVICE
        else -> null
    }

    /**
     * `IntentFactory.getOemOverrideComponentName(...)`：让凭据选择器指向 Google 的界面。
     *
     * 澎湃在这个方法里会把 OEM 覆盖组件名交给小米自己的 UI，而那个 UI 在国行上拿不到
     * Google 的凭据。这里改成返回 Google 的 CredentialChooserActivity ——
     * 但只在它确实存在且启用时才返回（否则会让选择器指向一个不存在的界面，直接报错）。
     *
     * 参数个数按 SDK 分两种（Android 17 起多了一个 int），并严格校验参数类型。
     */
    private fun installIntentFactoryHook(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "android.credentials.selection.IntentFactory")
        if (clazz == null) {
            ModuleLog.warn("$FEATURE: IntentFactory not found (Android < 14?)")
            return 0
        }
        val builderClass = Reflect.loadClass(
            loader,
            "android.credentials.selection.IntentCreationResult\$Builder",
        )
        if (builderClass == null) {
            ModuleLog.warn("$FEATURE: IntentCreationResult.Builder not found")
            return 0
        }
        val expectedParameterCount =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) 3 else 2
        val method = Reflect.firstMethod(clazz, "getOemOverrideComponentName") {
            it.parameterCount == expectedParameterCount &&
                it.parameterTypes[0] == android.content.Context::class.java &&
                it.parameterTypes[1] == builderClass &&
                (expectedParameterCount == 2 ||
                    it.parameterTypes[2] == Int::class.javaPrimitiveType)
        }
        if (method == null) {
            ModuleLog.warn("$FEATURE: IntentFactory#getOemOverrideComponentName not found")
            return 0
        }
        return if (HookRuntime.hook(method, "$FEATURE/IntentFactory#getOemOverrideComponentName") { chain ->
                val context = chain.args.getOrNull(0) as? android.content.Context
                val builder = chain.args.getOrNull(1)
                val component = resolveGoogleChooser(context)
                if (component == null) {
                    chain.proceed()
                } else {
                    markBuilderSuccess(builderClass, builder)
                    component
                }
            }
        ) 1 else 0
    }

    /** Google 的凭据选择器是否存在且启用。不存在就返回 null（让原逻辑继续）。 */
    private fun resolveGoogleChooser(context: android.content.Context?): ComponentName? {
        if (context == null) return null
        val component = ComponentName.unflattenFromString(GOOGLE_CREDENTIAL_CHOOSER) ?: return null
        return Reflect.attempt {
            val info = context.packageManager.getActivityInfo(component, 0)
            val runtimeEnabled = context.packageManager.getComponentEnabledSetting(component)
            val enabled = when (runtimeEnabled) {
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
                else -> info.enabled
            }
            if (enabled && info.exported) component else null
        }
    }

    /**
     * 顺手把 builder 上的「OEM UI 使用状态」标成成功。
     *
     * 不标的话，系统会认为 OEM 界面不可用而去做一次额外的回退探测 —— 那一步会把
     * 上面刚返回的组件名绕过去。字段/枚举都按名字找，任一步失败都只是少一层优化，
     * 不影响主效果（返回组件名）。
     */
    private fun markBuilderSuccess(builderClass: Class<*>?, builder: Any?) {
        if (builderClass == null || builder == null) return
        Reflect.attempt {
            builderClass.getMethod("setOemUiPackageName", String::class.java)
                .invoke(builder, GOOGLE_PACKAGE)
        }
        Reflect.attempt {
            val statusClass = Class.forName("android.credentials.selection.IntentCreationResult\$OemUiUsageStatus")
            val success = statusClass.enumConstants?.firstOrNull { it.toString() == "SUCCESS" }
                ?: return@attempt
            builderClass.getMethod("setOemUiUsageStatus", statusClass).invoke(builder, success)
        }
    }

    // ------------------------------------------------------------------ 国际版作用域

    /**
     * 「让一段调用看到国际版分支」的作用域。
     *
     * 三条纪律，缺一不可：
     *
     * 1. **可重入**：`setDefaultKey` 内部可能又调到被我们挂了 hook 的
     *    `getCombinedProviderInfos`。用 depth 计数保证「进最外层时改、出最外层时还原」，
     *    内层不再动它 —— 否则内层退出就还原了，外层剩下的代码又跑在国行分支上。
     * 2. **线程隔离**：设置进程是多线程的，绝不能把全局状态改成 true 然后等回来再改回 ——
     *    这期间别的线程看到的也是国际版。用 `ThreadLocal` 的 depth + prev。
     * 3. **局部加锁**：真正的字段写入是全局的，所以写入段用锁串起来，
     *    让「改 → 跑 → 还原」三段不会被别的线程插进来。锁的粒度是一次调用，可接受。
     */
    private class InternationalBuildScope(private val field: Field) {

        private val lock = ReentrantLock(true)
        private val depth = ThreadLocal.withInitial { 0 }
        private val previous = ThreadLocal<Boolean>()

        fun <T> around(block: () -> T): T {
            lock.lock()
            try {
                val current = depth.get() ?: 0
                if (current == 0) {
                    val original = Reflect.attempt { field.getBoolean(null) } ?: false
                    previous.set(original)
                    if (!original) {
                        UnsafeAccess.putStaticBoolean(field, true)
                    }
                }
                depth.set(current + 1)
                try {
                    return block()
                } finally {
                    val remaining = (depth.get() ?: 1) - 1
                    if (remaining <= 0) {
                        depth.remove()
                        val original = previous.get()
                        previous.remove()
                        // 还原：只有当初真的改过才写回，避免把别人（比如另一个模块）设的值覆盖掉。
                        if (original != null && !original) {
                            UnsafeAccess.putStaticBoolean(field, original)
                        }
                    } else {
                        depth.set(remaining)
                    }
                }
            } finally {
                lock.unlock()
            }
        }
    }
}
