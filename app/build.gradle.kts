plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.YGHFv.HyperExtend"
    // miuix 0.9.4 → Compose 这套栈要求 compileSdk 37（允许高于 targetSdk，不影响运行时行为）。
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.YGHFv.HyperExtend"
        // libxposed api 102 的 AAR 自己声明 minSdkVersion 26，低于它 Gradle 会拒绝合并。
        minSdk = 26
        // 基准平台是澎湃 OS 4（Android 16），targetSdk 停在 35 是刻意的：
        // 模块本体没有需要 36/37 行为变更的功能，抬上去只会多引入一层前台服务与通知的限制。
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        // 模块主界面（ui/SettingsActivity）用 miuix（Compose Multiplatform 库）绘制；
        // 注入宿主进程的部分不参与 Compose。
        compose = true
    }

    /**
     * 两个构建类型的差别只有一个：**压不压缩**。
     *
     * 这不是「优化与否」的偏好问题。模块的 dex 会被注入宿主进程（SystemUI、system_server），
     * debug 构建往里塞一份未压缩、含整个 Compose + miuix 的 dex，直接后果是宿主启动变慢、
     * 常驻内存抬高 —— 用户看到的就是「启用模块之后整机发卡」。所以**日常用的那个包是 release**。
     *
     * release 继续用 debug 签名：本模块只在自己设备上装，不需要发布用的密钥，
     * 而沿用同一个签名意味着切换构建类型时可以直接覆盖安装（不用先卸载）。
     */
    buildTypes {
        debug {
            // 只用于本地排查（断点、未混淆的堆栈）。**不要把它装到设备上日常使用**：
            // 没有 R8，注入宿主进程的是一份几十 MB 的未压缩 dex（实测未压缩 13 个 dex 合计约 31 MB，
            // release 压完只剩 2.4 MB），而宿主是 SystemUI / system_server ——
            // 这就是「启用模块之后整机发卡」的直接原因。日常装 app-release.apk，见下面 release 的注释。
        }
        release {
            isMinifyEnabled = true
            // 资源不压缩：真正拖慢宿主的是 dex，而资源里有一批只被 XML / manifest 引用的条目，
            // 为省几百 KB 去冒「某个资源被误删」的风险不划算。
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildTypes.configureEach {
        buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")
    }
}

dependencies {
    // 模块主界面：miuix（HyperOS 风格 Compose UI 库）+ activity-compose 提供的 setContent。
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.4")
    // 顶栏/底栏毛玻璃。用 miuix 官方 blur 库，与 miuix-ui 同版本、同一家发布，不引入第三套图形栈。
    // 它声明 minSdk 33，低版本走运行时门禁，manifest 里放行合并。
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.4")

    // XposedService / RemotePreferences：这个 AAR 自带一个 <provider>，要让 manifest 合并进模块 APK，
    // 框架才能在模块 App 进程里把 XposedService 的 binder 递过来（设置读写就靠它）。
    implementation("io.github.libxposed:service:102.0.0")

    // ANIP（Android Notification Icon Project）：通知图标库的资源来源与缓存。
    // 与上游 MIUINativeNotifyIcon 同一套 SDK：模块进程与被注入的 SystemUI 进程各自实例化，
    // 各自有私有缓存（跨 uid 不共享，见 core/NotifyIconLibrary 的注释）。
    implementation("com.highcapable.anip:anip-sdk:1.0.0")

    // 只存在于被注入的进程，绝不能打进模块 APK —— compileOnly 是硬要求，
    // 打成 implementation 会让模块进程加载到一份假的 API 实现。
    // ⚠️ 宿主的其他类同样不能编译依赖：模块类加载器在宿主进程里解析不到它们，
    // 直接引用会 NoClassDefFoundError —— 只能反射 / 鸭子类型。
    compileOnly("io.github.libxposed:api:102.0.0")
}
