# 澎湃补全计划 · HyperExtend

把散落在若干个小模块里的澎湃 OS（HyperOS）补全功能，收进**一个** LSPosed 模块、**一套**开关面板。

基准平台：澎湃 OS 4（Android 16）。技术栈：libxposed API 102 + miuix（HyperOS 风格 Compose UI）。

---

## 为什么要有这个模块

这些功能原本各自是一个独立模块：每一个都要单独安装、单独在 LSPosed 里启用、单独配作用域。
功能本身都很小，但模块数量一多，维护成本（跟进每个上游、每个 ROM 版本、每次都重启）就盖过了功能价值。

本模块把它们聚合到一处：**一个 APK、一个作用域列表、一次重启**。

## 功能一览

| 功能 | 解决的问题 | 来源 | 许可 |
| --- | --- | --- | --- |
| 隐藏手势横条 | 去掉屏幕底部手势提示横条，不影响上滑手势 | HideLine | 未声明开源协议 —— 按逆向结果自行实现 |
| 壁纸取色修复 | 动态主题色不跟随壁纸、始终停在默认蓝 | HyperWallpaperMonet（PengDingkang） | Apache-2.0 |
| 原生通知图标 | 状态栏/通知面板用通知自带的单色小图标 | MIUI 原生通知图标（fankes） | AGPL-3.0 |
| 关闭旋转建议 | 关闭屏幕方向变化时出现在导航栏附近的旋转建议按钮 | Android SystemUI RotationButtonController | Apache-2.0 —— 按公开行为自行实现 |
| NFC 卡面自定义 | 把小米智能卡的卡面换成你自己选的图片 | DIY NFC 卡面图片（zhizi42） | GPL-3.0 |
| 通行密钥修复 | 通行密钥调不出凭据管理器，或调出来是空列表 | 修复澎湃系统通行密钥（Howard20181） | GPL-3.0 |

界面与布局参考了 **HyperCeiler** 与 **ReaPress-Extend**。

## 界面

底栏三页：

- **首页** —— 按**作用域**（被注入的宿主应用）收纳成入口行。每行带宿主的真实图标、
  「已启用 n / m」，以及未安装时的明确标注。点进去是该作用域下的功能开关。
  系统框架（system_server）排在最前。
- **设置** —— root 权限检测、批量启用/关闭、恢复默认设置。
- **关于** —— 版本与诊断、日志、功能来源与许可、接入须知、紧急停用。

**每个功能只在界面上出现一次。** 通行密钥同时在系统设置、安全中心、扫描器和系统框架里生效，
但它只显示在**系统框架**下面 —— 同一个开关在四个入口里各出现一遍，用户只会以为那是四件事。
归属规则：功能 `scopes` 列表里第一个宿主就是它的入口，所以主宿主写在最前；
跨宿主的完整清单在功能详情页的「生效范围」里如实列出。

每个作用域页面的**右上角是「让改动生效」按钮**（全页仅此一个）。开关的值是宿主进程**启动时**
读进内存的，所以改完必须让宿主重新读一遍；这个按钮就是做这件事的。

模块不再支持热重载：自动热重载已关闭，框架热重载请求会被拒绝。更新模块或修改设置后，请重启对应宿主进程。
系统框架只能随设备重启生效，需要用户明确确认；模块不会自动重启设备。

结束进程需要 root（结束别人的进程要 `KILL_ANY_PROCESS` 级别权限，普通应用拿不到），
失败时界面会如实说明是「没有 su」「授权被拒」还是「超时」——这三者的补救动作完全不同。
「设置」页有一项 root 检测，可以先把这件事查清楚。

## 许可

本模块整体采用 **AGPL-3.0**（见 `LICENSE`）。选它的理由是上游构成：
MIUI 原生通知图标为 AGPL-3.0，通行密钥与 NFC 卡面为 GPL-3.0，壁纸取色为 Apache-2.0 ——
AGPL-3.0 是唯一能同时吸收这几者的许可（AGPL-3.0 §13 明确允许与 GPL-3.0 组合）。

对上游的处理分两类，按「上游是否声明了开源协议」划分：

- **声明了开源协议**（Apache-2.0 / AGPL-3.0 / GPL-3.0）：直接参考其实现。
- **未声明协议**（HideLine）：不复制代码，按反编译得到的**行为**重新实现
  （挂在哪个类、哪个方法、返回什么）。这是实现思路，不是代码。

每个功能的来源与许可都写在 `core/FeatureCatalog.kt` 里，并在界面的功能详情页与「关于」页如实展示——
那不是装饰，是许可合规的一部分，改动功能实现时不要删掉。

## 作用域

`app/src/main/resources/META-INF/xposed/scope.list`：

```
com.android.systemui        手势横条 / 壁纸取色 / 原生通知图标
com.android.settings        通行密钥 - 默认凭据提供方
com.miui.securitycenter     通行密钥 - 阻止覆写配置
com.xiaomi.scanner          通行密钥 - 修复扫描器调用方
com.miui.tsmclient          NFC 卡面 - 小米智能卡
system                      通行密钥 - 系统服务侧远程提供方
```

`staticScope=true`：作用域由模块自己声明，用户在 LSPosed 里加不了作用域之外的包。
模块界面应当至少打开过一次（让模块脱离 stopped 状态）。

### NFC 卡面列表

卡面列表来自钱包实际加载过的图片，不扫描钱包的全部私有目录。更新模块后，让「小米智能卡」
作用域的改动生效，再进入钱包的卡片列表或卡面页；回到模块的卡面自定义页面查看，必要时下拉刷新。

钱包 Hook 在自己的进程里读取本地图片并生成缩略图，通过仅接受钱包 UID 的 Provider 回传模块；
模块直接读取自己的缓存，不再使用 `su` 跨 UID 读取钱包文件。图片加载失败时保留列表项，并允许后续重试。
回传与解码在有界后台队列中执行，失败不改变钱包的原始图片加载结果。当前按原始卡面地址分别保存替换图片，
每个条目独立选图和恢复默认；只有本地、已开通、非占位的 CardInfo 才能进入列表，通用图片入口不再回传卡面。相同原始卡面地址仍共用一个条目（与参考项目一致）。

## 防砖

作用域里有 `system`（system_server），所以「最坏情况」是开机循环。按下面五层防线处理：

**① 默认全关（最重要的一层）。** 五个功能出厂全部为关。模块装上去、在 LSPosed 里启用之后，
只要没有在界面上主动打开某个开关，**一个 hook 都不会装**。

这一层有一条容易写错的细节：功能有主开关与子项两级，而有的 hook 点是直接按子项 id 判断的
（`passkey_fix.system_server` 等）。子项的默认值是「开」
（主开关一打开，子项就该全部生效），于是**只读子项的那个键就会在用户从没进过界面的情况下读出 true**。
`HookSettings.isOn` 因此在内部强制「子项必须同时满足所属功能的主开关」，
把这件事收在一个地方，新增调用点不可能忘。

**② 不认识的宿主一概不碰。** 每个功能装 hook 前先确认宿主对不对：
系统界面侧的三个功能要求存在 `miui.os.Build`（即确实是小米 ROM）；
通行密钥要求同样是小米 ROM，因为它的靶子 `RequestSession` / `IntentFactory`
是 **AOSP 类**，在类原生系统上挂它们会把凭据会话错误地指向 Google GMS —— 那属于
「修出一个原本不存在的故障」。目标类/方法找不到时一律「记日志 + 跳过」，绝不报错。

**③ 异常不出圈。** `HookRuntime.hook` 统一 `try/catch` 一切 throwable，拦截体里再兜一层；
拦截体抛异常时让原方法照常跑完（`ExceptionMode.PROTECTIVE`）。
每个 feature 的安装过程各自 `runCatching`，入口的三个生命周期回调也全部 `runCatching`。

**④ 逃生门（恢复模式可用）。** 万一真进了开机循环，系统进不去 → 模块界面和 LSPosed Manager
都打不开，界面上的「停用」按钮恰好够不着。所以提供两个**不需要开机即可生效**的标记，
任何一个成立，所有 hook 一律不装：

```
# 方式一：属性（需要 root，persist. 前缀保证重启后仍生效）
adb shell setprop persist.sys.hyperextend.disabled 1

# 方式二：文件
adb shell touch /data/local/tmp/hyperextend.disabled
adb shell mkdir -p /sdcard/HyperExtend && adb shell touch /sdcard/HyperExtend/disable
```

撤销：属性设回 `0` 或删掉文件，重启即可。**不需要卸载模块、不需要清 LSPosed 数据。**

**⑤ 自动熔断。** 模块还会跨进程记录 `system_server` 启动事件；五分钟内出现三次重启后，
自动持久化禁用 `system_server` 与 `SystemUI` 的全部 Hook，防止下一次启动继续循环。
普通应用、设置页和模块界面不受这道闸影响。模块界面的“手动解除自动熔断并启用框架 Hook”会发起一次性解除请求，清零当前计数但不会关闭下一轮自动检测；若模块界面无法打开，再在 root/恢复环境清除下面两项后重启：

```
adb shell setprop persist.sys.hyperextend.framework_disabled 0
adb shell rm /data/system/hyperextend_bootguard
```

实现见 `hook/BootLoopGuard.kt`，检查点在 `system_server` 安装前与 `SystemUI` 分派前；正常启动走
`HookDispatcher.dispatch`。
实现见 `hook/KillSwitch.kt`，检查点在所有 hook 之前：正常启动走 `HookDispatcher.dispatch`
与 `installSystemServerHooks`（`dispatch` 里连 dex 扫描都跳过），热重载走
`onHotReloaded` → 同一个 `installSystemServerHooks`，两条路共用同一道闸。

## 构建

```
./gradlew :app:assembleRelease
```

**发布用 release，不要用 debug。** 模块的 dex 会被注入宿主进程（SystemUI、system_server），
debug 构建既没有 R8 压缩也不做优化，往每个宿主进程里塞一份几 MB 的未压缩 dex，
表现为「启用模块后整机发卡」；模块界面本身也会明显迟滞。release 走 R8，
`app/proguard-rules.pro` 里保住了入口类与 provider。

要求：

- JDK 17
- Android SDK：`compileSdk 37` / `minSdk 26` / `targetSdk 35`
- `local.properties` 里的 `sdk.dir` 指向本机 Android SDK

依赖里有一处是**硬约束**：`io.github.libxposed:api` 必须是 `compileOnly`。
它以及全部宿主类型都只存在于被注入的进程；打成 `implementation` 会让模块进程里
加载到一份假的 API 实现（或直接 `NoClassDefFoundError`）。
所有宿主类型一律走反射（见 `hook/Reflect.kt`），不要 `import`。

## 工程结构

```
app/src/main/
├── java/io/github/YGHFv/HyperExtend/
│   ├── HyperExtendApplication.kt     模块 App 进程：接上框架服务、投影设置
│   ├── core/
│   │   ├── FeatureCatalog.kt         功能目录 —— 界面的唯一数据源（功能 → 作用域）
│   │   ├── ScopeCatalog.kt           作用域目录 —— scope.list 在界面上的化身
│   │   ├── CardFaceMappings.kt       原卡面地址与独立自定义图片的映射
│   │   ├── ProcessRestarter.kt       结束宿主进程 / 重启设备（兜底，走 su）
│   │   ├── RootAccess.kt             su 定位与 root 检测
│   │   ├── NfcCardImage.kt           卡面副本（选图 → 落到私有目录 → 授权给钱包）
│   │   ├── NfcCardImageProvider.kt   卡面副本的只读 content:// 出口
│   │   ├── FrameworkBridge.kt        框架服务（XposedService）的唯一持有者
│   │   ├── HostApps.kt               查宿主应用装没装
│   │   └── ModuleLog.kt              日志（logcat + 内存环形缓冲，两个进程各一份）
│   ├── config/HyperSettings.kt       模块侧设置读写（本地 prefs 权威 + 投影给框架）
│   ├── ui/                           主界面（miuix / Compose），只在模块进程中运行
│   │   ├── SettingsActivity.kt       底栏三页骨架 + 首页（作用域入口）+ 设置
│   │   ├── ScopeDetailPage.kt        作用域页：功能开关 + 右上角重启
│   │   ├── FeatureDetailPage.kt      功能详情：来源 / 许可 / 子项
│   │   └── HyperSubPages.kt          搜索页 + 关于页
│   └── hook/                         被注入进程里的一切
│       ├── HyperXposedEntry.kt       入口（java_init.list 指向它）
│       ├── HookDispatcher.kt         按宿主包名分派功能
│       ├── HookRuntime.kt            挂 hook 的统一出口（失败绝不冒泡）
│       ├── HookSettings.kt           注入侧读开关（RemotePreferences）
│       ├── KillSwitch.kt             紧急停用闸（恢复模式可用的逃生门）
│       ├── Reflect.kt / DexScan.kt   宿主反射 / 类名兜底扫描
│       └── feature/                  五个功能各自一个文件
└── resources/META-INF/xposed/        module.prop / scope.list / java_init.list
```

`FeatureCatalog.kt` 与 `ScopeCatalog.kt` 之间的**双向引用**（功能声明它在哪些作用域里，
作用域反查它有哪些功能）是刻意的：界面两个方向都要用，而两份都是纯数据、无副作用，
不会有初始化顺序问题。加功能时只改 `FeatureCatalog.kt`，加宿主时只改 `ScopeCatalog.kt`。

### 两条必须遵守的纪律

1. **设置键就是功能 id**。`HyperFeature.id` / `HyperOption.id` 直接当 SharedPreferences 的键用。
   界面不写死任何一个 id —— 加功能只需要在 `FeatureCatalog.kt` 里加一条，
   界面、设置存取、搜索三处自动跟上。
2. **注入侧不许抛异常**。宿主的 SystemUI 崩了是状态栏反复重启，system_server 崩了是开机循环。
   所以 `HookRuntime.hook` 吞掉一切 throwable，每个 feature 的安装过程也各自 `runCatching`，
   「目标找不到」一律走「记日志并跳过」而不是报错。

## 排查

- **开关拨了没反应**：先看「关于」页里的「框架服务」是否已连接。
  未连接 = 设置推到不了注入侧（LSPosed 未启用模块，或模块不在作用域里）。
  已连接的话，多半是宿主进程还没重新加载 —— 进对应作用域页，点右上角那个按钮。
- **某个功能不生效**：看「已捕获异常」是否为 0，再看日志里对应 feature 那一行是
  `hook installed` 还是 `hook target missing`（后者说明这个 ROM 版本上靶子的类/方法名变了）。
- **重启按钮报「没能执行 su」**：设备没有 root，或者本应用还没被授权。
  在 root 管理器（Magisk / KernelSU / APatch）里给「澎湃补全计划」授权后重试。
- **注入侧的日志**：模块界面只能看到模块自身进程的日志。SystemUI / system_server 的日志在 logcat 里，
  按标签过滤：`adb logcat -s HyperExtend`。
