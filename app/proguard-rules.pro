# 澎湃补全计划（HyperExtend）—— R8 规则。
#
# 为什么需要 R8：模块的 dex 会被**注入宿主进程**（SystemUI、system_server），
# 一份未压缩的 dex（里面有整个 Compose + miuix）会拖慢宿主启动、抬高宿主的常驻内存 ——
# 这是「启用模块之后整机发卡」最直接的原因。release 构建因此开启压缩。
#
# 但压缩对这件事有两条硬约束，错一条模块就完全不工作（而且症状是「装了没反应」，
# 极难联想到混淆）：—— 见下面两条 keep。

# ① 入口类必须保住原名。
#    META-INF/xposed/java_init.list 是按**全限定类名**引用它的，
#    框架据此反射创建模块实例。名字被改 = 模块永远加载不起来。
-keep class io.github.YGHFv.HyperExtend.hook.HyperXposedEntry { *; }

# ② provider 必须保住。
#    它由 AndroidManifest 按类名引用（AGP 也会为 manifest 里的类生成 keep 规则，
#    这里显式写一次是为了不依赖那条隐式行为）。
-keep class io.github.YGHFv.HyperExtend.core.NfcCardImageProvider { *; }
-keep class io.github.YGHFv.HyperExtend.core.WalletCardFaceCaptureProvider { *; }

# libxposed 的 api / service：前者由框架在运行时提供（compileOnly，本就不在 APK 里），
# 后者是模块与框架之间的 binder 协议，改名会让协议对不上。
-keep class io.github.libxposed.** { *; }
-dontwarn io.github.libxposed.**

# 保留行号，让 logcat 里的堆栈还能读 —— 被注入进程的日志是本模块唯一的第一现场，
# 去掉行号等于把唯一可用的诊断手段扔掉。体积代价只有几 KB。
-keepattributes SourceFile,LineNumberTable
