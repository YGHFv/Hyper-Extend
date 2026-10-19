# 桌面原生诊断原型

当前交付已接入快捷菜单数量上限的原生生产路径、离线合同、纯原生单次快照诊断、Java 备用预检及独立试验保护。
数量蹦床、近距岛页和安装事务均已进入原型库，但安全与后端验收阶段锁仍保留，**尚未在真实桌面代码页生效**。
不支持的宿主或框架不会降级为猜地址挂载。本目录未复制上游的布局、Dock、属性 Hook 或补丁恢复逻辑。

## 当前挂载规则

- 不检查桌面版本号、build-id、整库或清单摘要，也不维护分版本地址表。
- `hook_points.h` 从经过映射核验的只读可执行段中定位唯一的参数搬运序列，
  再核对 BL 调用关系、数量结果的普通整数消费方式、菜单运行时帧的初始化和退栈。
  指令位移和对象池槽位不作为版本指纹；参数寄存器、调用类型或返回约定不符时拒绝该点。
- 小窗与多开的序言相同，必须进一步核对 Intent 标志及三参数状态检查，不能取第一个匹配。
- 菜单有效性只在宿主帧初始化后转入替换代码，真值交给原运行时退栈入口，
  不在函数入口直接 `ret` 一个布尔对象。
- 无旧状态的首次启用自动建立安全状态，并在同次启动继续挂载；
  旧状态损坏、异常残留和真实熔断仍拒绝，不自动清除。
- 模块内主动重启先提交一次性请求，再结束桌面。已部署的配置端点验证、消费请求后，
  原生端再次核对令牌、操作、PID、UID 和租期；只有这一次启动免计异常次数。
  未经请求的重启仍累计，普通重启不能解除已有熔断。
- 为兼容已加载且尚不转发新请求的 system_server，模块内桌面重启/恢复使用 root 发布的
  独立只读请求文件。原生端验证 root 所有者、文件类型、链接数、UID、操作及租期，
  原子移动到已消费名并同步目录后才使用授权。请求文件不是熔断状态文件，不需要重启系统服务。
- 模块 App 的 root 读取须使用全局挂载命名空间（`su -M`）；普通 root 仍可能处于
  Android 应用目录隔离内。读取失败不能推断“正常”或把确认恢复降级成普通重启。
- 安全模式页直接合并桌面原生状态；关闭桌面的安全模式开关并确认后走独立恢复事务，
  不另设初始化按钮或平行的保护开关。手动开启时只遮蔽远端功能投影，保留原配置。
  完整状态与新令牌校验、落盘和读回成功后，同次启动继续挂载，无需再次重启。
- 主开关关闭、功能全关时也能只恢复保护而不挂载功能；状态损坏和未完成事务仍拒绝。
- Java 系统界面主动重启、模块内确认的设备重启也传递一次性请求，并持久化消费记录。
  已消费请求不能豁免随后的异常启动；这不改变任何既有熔断阈值。

验证命令：

```powershell
python tools/launcher_hook_points_audit.py --elf <只读宿主libapp.so>
python -m unittest discover -s tools -p "test_launcher*.py"
```

后文保留早期静态样本和后端研究记录。旧合同中的版本、地址和摘要仅标识离线证据，
不是当前运行期的兼容门禁。原生框架接口的 ABI 协议校验与桌面 APK 版本号无关。

## 构建边界

普通构建不需要此原型，也不打包原型的 SO、JNI 桥和 `native_init.list`。
仅显式传入 `-PlauncherNativePrototype=true` 时启用 CMake、固定 NDK 和 ARM64 构建。
原型构建会额外把 `META-INF/xposed/scope.list` 收敛成**恰好两个宿主、顺序固定**：
第 1 行为桌面 `com.miui.home`，第 2 行为 system_server `system`。
这是让桌面侧原生库与 system_server 侧端点在同一 APK 内同时生效的前提；
多项、缺项、换序或空行干扰均拒绝。该文件只在原型构建
打包，普通构建的作用域不受影响（产物审计会分别核对两种模式）。
两个模式共用 APK 输出路径，构建后必须用对应模式的审计命令检查，不要混淆产物。

```powershell
.\gradlew.bat :app:assembleRelease :app:testDebugUnitTest -PlauncherNativePrototype=true
python tools/launcher_native_package_audit.py --apk app/build/outputs/apk/release/app-release.apk --prototype

.\gradlew.bat :app:assembleRelease
python tools/launcher_native_package_audit.py --apk app/build/outputs/apk/release/app-release.apk
```

这些命令只在本机构建、读取 APK，均不安装。产物审计验证 JNI 名称、原生声明、
架构、导入导出以及普通构建的隔离边界，不能替代设备验收。

## 原型能力

- Kotlin 桥无静态加载器；没有接入 `HyperXposedEntry`，未给普通构建增加桌面作用域或正式开关。
- `loadForDiagnostics()` 是后续验收使用的显式诊断入口，不会被本模块自动调用。
  显式加载前后均核验进程、绑定应用及磁盘 APK；任何失败均拒绝返回诊断成功。
- `native_init` 记录当前进程与版本，仅在 ABI 精确匹配时**保存** hook/unhook 指针，
  从不调用它们。返回框架现有的库加载通知，并尝试调度单次只读 worker；
  预派生进程不创建线程，不扫描宿主、不改写代码页。
- 已取证的原生 ABI 版本是 `2`（设备持久化 logcat `hook API version=2`），
  只接受精确匹配；版本受支持**只解除 ABI 那一项阻断**，不构成安装许可。
  Java API 的 102 与 HyperCeiler 日志里的 `native v34` 都不是原生 ABI 版本。
- JNI 加载、原生回调到达和继承自父进程的诊断状态分别报告；父进程状态不算子进程证据。
- 已有数量配置快照、安装事务和数量 Hook 入口；当前阶段锁会拒绝真实安装，不能把原型包安装成功或离线审计通过当成快捷菜单已经生效。

诊断位：`1` 原型模式、`2` 本进程 JNI 已加载、`4` 本进程原生回调到达、
`8` 原生 ABI 被拒绝、`16` 存在继承状态、`0x800` 原生 ABI 版本已核验
（只解除 ABI 阻断）。未取得本进程有效版本时返回 `-1`。
位定义见 `launcher/probe_state.h`，进程隔离与布局由交叉编译期断言校验。

## 桌面配置通道（两侧已实现，实机验收待做）

桌面是无 ART 的纯原生进程，Kotlin 侧的 `SafeModeRuntime`、`BootLoopGuard`、
`SharedPreferences`、`RemotePreferences` 与 `ContentResolver` 在本进程里都不可用。
设备只读取证把唯一可行通道定位为：**桌面发起同步 Binder 事务，system_server
应答一份设置快照**（桌面 maps 已加载 `libbinder.so`/`libbinder_ndk.so`；上游
HyperCeiler 的 `layout_config_cache.bin` 正落在桌面私有目录）。实机日志已证明
`native_init`/模块回调可达，但 `RTLD_DEFAULT` 与 `RTLD_NEXT` 均未解析出 Binder API；
查询端现改为按需 `dlopen("libbinder_ndk.so")` 并从句柄解析，仍不产生 `DT_NEEDED`。
系统属性作废：
模块与桌面不共享 UID，模块 App 无法替桌面写属性。

`launcher/snapshot_wire.h` 固定回复快照的字节契约与 fail-closed 判定：确认码、
协议、序号、签发/到期（开机单调时钟）、功能位、数量上限、已武装、恢复令牌、
保留位；`ordinary_snapshot_errors()` 在形态校验外拒绝普通通道中的非零恢复令牌，
`snapshot_safety_permits()` 再把「普通快照有效」与「本地熔断允许」与起来，
并区分两个时钟——开机时钟管租期、墙上时钟管熔断。安全维度仍只能由门禁结论
产生，调用方不能自填。

**两侧实现现状：**

- 桌面侧发起事务与解析回复已实现（`native_entry.cpp` 的 `query_snapshot` +
  `launcher/channel.h`），原型通过纯原生 worker 调用，不依赖 JNI，**没有接进安装路径**。
- system_server 侧应答端点已实现（`hook/feature/launcher/LauncherSnapshotEndpoint`，
  挂在 `IWindowManager$Stub#onTransact` 上，只对自定义码写回复、其它码一律透传）。
- 因此两侧代码已具备请求与解析快照的静态链路，**尚未实机证明桌面能取回合法快照**。
  `install_permitted` 仍要求宿主、ABI、安全、后端四项证据，后两项未关闭；数量安装路径已接线，
  但阶段锁仍使其不可达。

### 两处必须知道的实现细节

1. **时间字段是整秒，不是毫秒。** 回复体只有 10 个 int32，时间字段本身也是 int32；
   开机毫秒数在 uptime 超过约 24.85 天（2^31/86400000）后就越过上界，会让整条
   通道在没有任何故障的情况下永久失效。`elapsed_seconds()` 与 ReplyWire 一律用
   整秒，`launcher_snapshot_wire_test.cpp` 用「开机 300 万秒仍可采纳」钉死这一点。
   JNI 配置预检那条路径的字段全是 64 位，继续用毫秒，两者不要互串。
2. **window 句柄必须先关联类。** `AServiceManager_checkService("window")` 返回的是
   远端 `BpBinder`，它在 NDK 侧**没有关联的类**，而 `AIBinder_prepareTransaction`
   要求句柄已关联类（否则 STATUS_INVALID_OPERATION，事务根本发不出去）。因此
   先按描述符 `android.view.IWindowManager` 核对，未关联时用 `AIBinder_Class_define`
   定义并关联。类的缓存用**手工无锁原子**而不是函数内 `static`——后者会引入
   `__cxa_guard_acquire/release`，产物审计已把这两个列为禁止导入。

### 纯原生单次诊断（2026-10-13）

入口是 `native_init -> maybe_schedule_diagnostic -> diagnostic_worker`，不是
`System.loadLibrary`。框架后续库加载通知也可以触发身份复核，但不挂接
`setprogname` 或系统属性函数。只有 cmdline 精确为桌面、普通应用 UID 与 EUID
一致、当前 PID 自己收到 ABI=2 的入口记录，才允许调度。

调度记录以 PID 和阶段整体 CAS 发布；失败、完成均不重置。特化前不启动线程，
重复通知不新增线程，fork 继承的回调不能充当子进程入口证据。worker 开始时
再次核对身份。若框架只在父进程调用入口而未在子进程重新调用，或特化后没有
任何可达通知，本原型宁可不查询，不用安装 Hook 的办法强行补特化通知。

加载回调只创建分离线程，不等待 worker，不同步访问 Binder。worker 最多尝试
一次查询，无轮询、自动重试或后台刷新。`checkService` 不轮询等待服务注册，
但服务管理器和快照事务仍是同步 IPC，**没有硬超时承诺**；若阻塞，可能留下
一个未完成线程，不通过 `pthread_cancel` 强行终止。

`HELauncherProbe` 仅记录阶段、通道阻断位、快照阻断位、修订号、功能位、数量上限
与 armed，不记录 PID、UID、路径或恢复令牌。所有日志标注 `install_permitted=false`。
`channel=0` 只表示本次采纳；`channel=0x10 snapshot=0x40` 表示回复未武装，
不能混同于没有端点或升级为安装许可。新增状态位：

| 通道状态 | 含义 |
| --- | --- |
| `0x20` | 尚未查询 |
| `0x40` | 查询在途或重入被拒绝，不等待 |
| `0x80` | 拒绝复用 fork 继承的通道状态 |

成功修订号和功能位用一个 64 位原子记录发布。所有实际查询共享非等待闸门，
旧回复不能在新回复之后覆盖字段；JNI 两个独立 getter 仍只供人工观察，不提供
跨调用一致性事务，更不能成为安装依据。

产物导入白名单包含 UID 查询、四个 pthread 调度符号与 `__android_log_print`；
只读安全预检另允许 `openat`（及其 FORTIFY 包装）与 `fstat`。动态依赖只新增
`liblog.so`。继续拒绝代码页改写、任意动态加载、写文件、线程等待
与取消；导入审计不是调用时机证明，后者另用入口试验覆盖。

普通包不含此 worker。原型作用域按固定顺序包含桌面 `com.miui.home` 与
system_server `system`，但产物存在不等于两端已加载或已验收，也不能据此声称端点已启用。
实机验收必须分别确认应答端点与请求端实际加载；仅安装普通包或
只看到一端日志都不足以关闭门槛 3。本轮没有安装，也没有修改设备作用域。

### 门槛 3 的双端日志验收准备（2026-10-13）

检查清单见 `app/src/main/cpp/gate3-readonly-acceptance.md`，合同在
`app/src/test/resources/launcher-gate3-log-contract.json`。新增
`tools/launcher_gate3_log_audit.py` 将现有 9 个源码文件中的日志格式、11 个模板、
事务码、阻断位与待验收阶段锁一并核对，不修改设备端日志或查询行为。

```powershell
# 尚无新实机日志时，只检查合同与源码，不能冒充实机验收
python tools/launcher_gate3_log_audit.py check-contract
```

有完整的安装前/后材料后，用 `audit` 子命令传入两端各两份日志；默认核对 `unarmed`，
只有显式 `--expect accepted` 才核对已采纳场景。后置文本必须完整追加于基线，
原生端新增窗口恰好一个同 PID/TID 的开始/完成对，服务端只使用最后一个 attach 会话，
并要求新增 served 的修订号/功能位/上限逐项匹配。不跨会话借旧成功日志，不跳过缺失材料。

**重要区分**：正常未武装是 `channel=0x10 snapshot=0x40`，而全零 denied 载荷因
revision/limit 非法先得到 `snapshot=0x4`，不能当成正常未武装。端点 served/rejected
日志共享 8 次限额；缺日志、冻结日志或只有开始均是证据不足，不直接判端点缺失或超时。

输出只有“日志核对通过”，始终保留 `门槛3已关闭=false`、`安装许可=false` 与
未关闭门槛 `[1,3,5]`；不能认证日志采集时间、实际加载版本、调用方身份或本地熔断持久化。

### 原生入口的离线行为复核

`launcher_diagnostic_entry_trial.cpp` 直接包含生产 `native_entry.cpp`，以受控替身
实现系统调用、线程创建和 Binder。测试库只在模拟器中执行，不进入 CMake/APK。
70 个场景检查身份、单次调度、继承拒绝、Parcel 释放、重入、快照整体发布与只读安全预检；
不模拟实际线程调度、加载锁、SELinux、文件系统或 Binder 驱动。
`test_launcher_diagnostic_emulate.py` 的生产入口测试每次重新编译该试验，再逐个执行场景，
不把已存在的 ELF 或合成报告当成本轮结果；缺少 NDK 时显式跳过，不算执行通过。

```powershell
# $env:ANDROID_NDK_HOME 指向本机已安装的 NDK；输出全部留在不入库目录。
$compiler = Join-Path $env:ANDROID_NDK_HOME 'toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe'
New-Item -ItemType Directory -Force .workbuddy/tmp/launcher-channel-trial | Out-Null
& $compiler --target=aarch64-linux-android26 -std=c++17 -O1 -Wall -Wextra -Werror `
  -fno-exceptions -fno-rtti -fno-stack-protector -fvisibility=hidden -mno-outline-atomics `
  -fno-builtin -U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=0 -shared -nostdlib `
  '-Wl,-Bsymbolic' '-Wl,--no-undefined' app/src/test/cpp/launcher_diagnostic_entry_trial.cpp `
  -o .workbuddy/tmp/launcher-channel-trial/diagnostic-trial.so
# 使用已安装 requirements-launcher-emulation.txt 的本地 Python 环境。
python tools/launcher_diagnostic_emulate.py --elf .workbuddy/tmp/launcher-channel-trial/diagnostic-trial.so
```

## 配置预检

`LauncherConfiguration` 要求经过核验的桌面子进程证据，然后只读取一次偏好快照。
证据对象仅是后续加载适配器的内部输入合同，不是不可伪造的安全令牌。
直接 Java 路径已有只读采集器，但尚未实机验收；不能从偏好设置或进程名前缀伪造身份。
正式设置页尚未接入这些原型键。

- `launcher_native_protocol` 必须是整数 1；四个菜单开关默认关闭，并受独立总开关控制。
- 类型错误、读取失败、缺失协议都返回失败，不复用上次成功的配置。
- JNI 使用七个 64 位整数：协议、PID、发送序号、签发时间、到期时间、功能位、数量上限。
- 功能位按数量、分享、小窗、新任务依次占位；上限范围 5–20，保持 raw 整数语义。
- 发送方每次提交（包括相同设置的重新预检）都必须递增序号；它不是偏好版本号。
- 使用开机单调时钟，租期最多 30 秒；拒绝过期、未来、错误进程、回放和竞争更新。
- 父进程的发送高水位不能作为子进程的配置会话；本进程必须重新收到 JNI 和原生回调。
- 原生端仅保留防回放高水位，不保留功能配置。全关配置也推进高水位。

所有原生预检结果都包含 `0x100` 宿主未核验、`0x200` 安全许可未核验、
`0x400` 后端未核验。`configurationValid` 只说明输入检查通过，不是安装令牌；
未知状态位、缺少上述阻断位也不能被当作有效预检结果。

原生 CAS 使用 `-mno-outline-atomics`，避免编译器辅助库通过加载期构造器查询
CPU 与系统属性。产物审计拒绝加载期构造器和未审核导入，保留栈保护失败处理。

## 独立试验记录

`LauncherTrialGuard` 不复用会修改系统属性的旧保护逻辑。它也没有被桥或生命周期
自动调用：未来只有在其他安装前置条件成立后，才允许进行一次 `arm()`。

安装前先持久化待确认票据；下次进程发现未完成票据就拒绝新试验。恢复需要桌面
专用且严格递增的人工恢复令牌；损坏的文件不会被新令牌自动覆盖。健康确认要求
匹配当前票据，旧进程的确认不能清除后续试验记录。加载或配置成功不能调用健康确认。

文件实现要求已核验宿主的现成私有目录，使用非阻塞文件锁、受限读取、同目录临时
文件、文件同步、原子替换、目录同步和写后核对。读写异常、锁竞争、非普通文件或
不支持原子替换均返回失败；不改设置、不重启进程、不启动 Activity。

本地 JVM 测试实际执行文件操作，但目录同步使用测试替身。Android 的目录 fsync、
文件系统崩溃一致性和健康判定仍待运行期验收；不能据此宣称已具备实机防崩溃能力。

## 离线宿主合同

`app/src/test/resources/launcher-shortcut-native-contract.json` 保存已审核 SO 的摘要、
Build ID、精确函数范围、调用边、少量指令窗口及完整二进制清单的长度和摘要。
完整宿主文件不进入仓库。

```powershell
python -m unittest discover -s tools -p 'test_*.py'
python tools/launcher_native_audit.py --elf $env:LAUNCHER_LIBAPP
python tools/launcher_native_audit.py --elf $env:LAUNCHER_LIBAPP --mcp-url $env:MT_MCP_URL
python tools/launcher_native_audit.py --elf $env:LAUNCHER_LIBAPP --entry-elf $env:LAUNCHER_ENTRY_LIB --mcp-url $env:MT_MCP_URL
```

可用 `--workspace` 选择已有工作区，但仍重新核对包名、版本和无 DEX 条件。
地址只通过参数传入，不会写入合同或报告。材料缺失、摘要不符、符号不唯一、
映射异常、指令不一致均返回失败；不会自动更新合同或跳过真实宿主校验。
可选的 MCP 阶段只读取宿主信息、二进制清单与反汇编，不调用设备控制工具。

合同中的 `continuation` 固定描述数量 getter 调用后的候选：调用点 `0x1147018`、
覆盖窗口 `0x114701c` 起 **24 字节**、恢复位置 `0x1147034`。24 字节不是估计值，
而是上游 backend 常规中继的实测足迹：`trampoline_arm64.cc` 按源到目标距离二选一
（近距 `adrp+add+br` = 12 字节，远距 `ldr+br+字面量` = 24 字节），桌面 Dart AOT
函数到中继页的距离必然命中远距分支；同一后端在本设备桌面进程内 hook 的 libc
函数（`__system_property_get`、`setprogname`）代码页开头是
`91 00 00 58` = `ldr x17, #16 ; br x17 ; nop ; nop ; <8 字节字面量>`，
字面量落在偏移 16（窗口末尾的 8 字节），覆盖 24 字节。

审计精确检查覆盖窗口的字节前缀与所属函数范围，要求窗口内全部为位置无关指令
（任何 B/BL、B.cond、CBZ/CBNZ、TBZ/TBNZ、ADR/ADRP、字面量 LDR 都会让中继副本
偏离目标，直接拒绝），并扫描所有文件支持的执行段内对齐机器字中的
B、BL、B.cond/BC.cond、CBZ/CBNZ、TBZ/TBNZ 编码：getter 必须只有该 BL 直接入口，
覆盖窗口不能有直接跳入。指令或入口集合变化时失败，不自动换地址或退回入口替换。

`CONTINUATION_COVERAGE` 同时保留了 12/16/24 三种已审核足迹，三者共享
`mov x2,x0` 开头，因此候选 2「在窗口前改写 x0、由窗口首条指令转发新值」的
语义对覆盖长度鲁棒；合同固定取 24，不自动切换。

这个扫描会保守地把执行段内数据中的分支编码也当作候选边，不能识别间接入口、
异常恢复入口或证明**并发安装**安全。通过时依然返回 `安装许可=false`。

### 数量行为的离线执行试验

`tools/launcher_shortcut_count_emulate.py` 使用固定版本 Unicorn 执行交叉汇编的
`.o`、真实宿主调用前缀、24 字节重放窗口和裁剪比较。只在模拟器内解析重定位，
不生成或链接 SO，不修改宿主 ELF，不连接设备。材料缺失或仿真依赖缺失直接失败，
不以跳过测试代替通过。

先在本地虚拟环境安装 `tools/requirements-launcher-emulation.txt`；下列环境变量
均由调用者提供，工具不保存本机路径：

```powershell
& $env:LAUNCHER_TEST_PYTHON -m pip install -r tools/requirements-launcher-emulation.txt
& $env:NDK_CLANG --target=aarch64-linux-android35 -c app/src/main/cpp/launcher/shortcut_count_arm64.S -o "$env:LAUNCHER_TRIAL_DIR/continuation.o"
& $env:NDK_CLANG --target=aarch64-linux-android35 -c app/src/test/cpp/launcher_count_branch_trial_arm64.S -o "$env:LAUNCHER_TRIAL_DIR/branch.o"
& $env:LAUNCHER_TEST_PYTHON tools/launcher_shortcut_count_emulate.py --elf $env:LAUNCHER_LIBAPP --object "$env:LAUNCHER_TRIAL_DIR/continuation.o" --branch-object "$env:LAUNCHER_TRIAL_DIR/branch.o"
```

试验覆盖原上限 3/4、合法上限 5..20、关闭与异常状态、长度边界及全部 16 种初始
NZCV。检查原 getter/长度查询各到达一次、比较和帧中的裸整数上限、裁剪分支、
活寄存器、向量寄存器、Dart 栈、C 栈和返回地址。另故意移除范围检查、标志恢复、
栈恢复或帧写回，要求测试能够发现破坏。

**边界：**getter 和长度查询采用返回值替身，不执行完整 Dart 运行时；只走到
保留/裁剪的分支入口，不执行后续排序、对象分配和菜单 UI。重放中继为模型，
也不模拟真实后端或多核并发提交。因此通过仍然不是安装许可。

`app/src/test/cpp/launcher_count_branch_trial_arm64.S` 是**独立离线候选**：
将 `0x1147054` 的原 `B.gt` 在模拟器内改为单条 `B`，处理已解箱长度与帧上限后，
回到 `0x1147058` 或 `0x1147068`。它不引入 getter 包装调用或新的返回地址。
该文件没有接入 CMake，也不替换现有 `0x114701c`/24 字节合同。真实提交协议、
代码页生命周期、异步栈回溯与实机验收均待审核，禁止自动切换策略。

### 独立单分支的发布与生命周期审计

`app/src/test/resources/launcher-count-branch-contract.json` 单独固定了 `0x1147054`
的原 `B.gt`、两条原路径、56 字节试验代码摘要和四处重定位，不修改原 24 字节
宿主合同。ARM DDI0487 L.a 的 AArch64 第 B2.2.5 节（第 230 页）明确包含
`B.cond` 与 `B`，所以本候选符合该节的指令对要求；普通 `MOV/LDR -> B`
不因此获得相同保证。**指令对合格不等于实际后端验收通过。**

```powershell
python tools/launcher_branch_publication_audit.py --elf $env:LAUNCHER_LIBAPP --object "$env:LAUNCHER_TRIAL_DIR/branch.o"
```

此命令只读宿主与交叉汇编对象，额外扫描执行段内跳入比较前置窗口的直接分支，
验证代码/数据分离和往返分支的地址范围，并运行两个抽象执行者的有限状态检查。
它允许“执行新指令之后再执行旧指令”、关闭时已有调用读取旧值，以及恢复原指令
后仍进入旧代码页；因此拒绝把“当前活跃数为零”当成可以回收的证据。

待实现的发布协议必须先初始化关闭状态、写完整代码、完成缓存同步、封为 RX，
再提交一条对齐指令。发布后代码不可改写，代码/数据页一直保留；关闭只更新单字
配置。发布后失败或补丁丢失转为关闭，不自动重装，也不因失败而释放曾发布的页面。

有限状态检查中的缓存同步、单字提交和宿主映射有效都是**抽象前提**，不是硬件
实测，也不是 herd7 证明。实际内存属性、写入独占性、宿主映射生命周期及 Dart
异步执行兼容性仍须验收；`install_permitted` 的安全和后端阻断位没有移除。

### 「改调用点」单字候选（2026-10-13 更正后新增）

早期交接曾把四项功能的 6 条挂载调用边也写成「不在 B2.2.5 例外集合内」。核对
ARM DDI0487 L.a 第 230 页原文后更正：该节把
`B, B.cond, BL, BRK, CBNZ, CBZ, HVC, ISB, NOP, SMC, SVC, TBNZ, TBZ, TRCIT`
一并列为例外集合，官方博客的经典例子正是 `BL old` 改成 `BL new`；
**不在集合内的是被调目标函数的序言 `stp`，不是调用边本身。**

因此除 24 字节 Dobby 足迹外，另有一条相互独立的候选：只把**调用点那一条 `BL`**
改成指向自有蹦床的 `BL`。旧、新机器字都属于例外集合，只需一个对齐的 32 位存储，
不必一次覆盖多条指令。它不关闭门槛 1、5：蹦床必须显式把 `x30` 恢复成
「调用点+4」以回到同一接续指令，并复现原被调函数的入参、返回值、副作用与
Dart 栈/GC 约定；新代码仍须先完成缓存同步，宿主映射生命周期与写入独占性仍未验收。

```powershell
python tools/launcher_menu_singleword_audit.py --host-elf $env:LAUNCHER_LIBAPP
```

只读工具固定 4 个目标函数的摘要与入口机器字、6 条 `BL` 调用点的机器字与目标、
调用点的 `returnAddress`/接续指令、调用点自身分支入口数与目标指针引用数，
并要求目标入口**不在**例外集合。真实宿主复核通过时仍返回 `安装许可=false`，
不接入安装路径、不链接蹦床。

合同还必须显式保留控制流/发布要求（返回地址 `x30`、32 位单字提交、缓存同步）
与未关闭门槛 1、门槛 5；任一项缺失直接失败。工具同时把通用单字发布有限状态模型
（1164 状态 / 6188 迁移 / 三项关键交错）与其 `early_publish`、`early_release`
故障对照跑成回归。该模型只是策略层「单条对齐 32 位提交 + 代码页永久保留 + 关闭只改
状态」的抽象检查，**不**模拟硬件缓存、宿主映射或 Dart 运行时，因此不关闭门槛 5。

单字候选的**蹦床落点**另有一条独立只读工具：

```powershell
python tools/launcher_trampoline_landing_audit.py `
  --host-elf $env:LAUNCHER_LIBAPP --maps $env:LAUNCHER_MAPS --base $env:LAUNCHER_BASE
```

`B`/`BL` 只有 26 位有符号立即数（±2^27 字节、按 4 对齐），所以「蹦床必须落在调用点
±128MB 内」是编码硬约束。工具解析本机 `/proc` 映射快照（该文本与加载基址属本机
运行期信息，只作参数、不入库），要求存在**页对齐、非 W+X、长度不小于合同下限**的
未映射空洞，并用「编码 + 独立反解」核对三类跳转都能编码：调用点 `BL 蹦床`、
蹦床内 `BL 原目标`、蹦床内 `B 调用点+4`。三者按蹦床首字与末字两种最坏情况取范围。
同一份快照还用于核对候选调用点的映射归属：必须是**私有（`p`）、可读可执行、有文件
支撑、且路径属于已审核宿主包**的映射。这条不是风格要求：后端提交路径是
`mprotect` + `memcpy`，只有 `MAP_PRIVATE` 文件映射才会把写入限制在本进程 COW 副本里。
真实宿主复核通过时仍返回 `安装许可=false`；**存在可达空洞不等于该空洞在安装窗口内
仍空闲**，分配竞态、缓存同步、映射生命周期与写入独占性仍未验收。

### 单字提交的发布协议（2026-10-13 新增：门槛 5 的可关闭路径）

上面那条「改调用点单字 `BL`」候选发布时到底安不安全，由一个独立的只读策略工具回答：

```powershell
python tools/launcher_singleword_publication_audit.py --host-elf $env:LAUNCHER_LIBAPP
```

合同 `app/src/test/resources/launcher-singleword-publication-contract.json`
（策略 `singleword-call-site-publication-offline-v1`，`installPermitted=false`）
把依据钉成两份，缺一即失败：

- **ARM DDI 0487 L.a 第 B2.2.5 节（第 230 页）**：`BL` 属于无同步并发修改例外集合；
  旧字与新字都在集合内时，架构保证「取到旧或新之一」，但**不**保证多条被改指令之间一致。
- **Arm Community 官方文章**《Caches and Self-Modifying Code: Working with Threads》
  （Jacob Bramley，2025-01-21）：给出**预取推测保护**——新代码先用
  `dc cvau / dsb ish / ic ivau / dsb ish` 完成同步，**再**改那一条分支，则任何取到新分支的
  线程必定也能取到同步后的新代码；并明确警告多字修改会出现新旧混合执行，
  旧指令可能在新指令已被执行之后仍被执行，没有时间上限。

工具据此把发布协议固定为四步：先分配并写好蹦床页 → 完成缓存同步 → 封为 RX →
用**单条对齐 32 位存储**把调用点 `BL 原目标` 换成 `BL 蹦床`；蹦床页永久保留到进程结束，
关闭只写状态字、不回写机器字。同一份合同还要求 6 条调用点都是例外集合内的 `BL`、
每条只写 4 字节且对齐、返回地址严格等于调用点+4。

先在抽象策略模型里做有限状态穷举（两个调用线程 + 一个作者线程 + 状态字
`{0, 5, 20}`）：正确协议下无危险交错（**179 状态 / 1128 迁移**，并覆盖四类关键交错：
由旧字进入、由新字进入、关闭后已有调用仍在蹦床内、启用后已有调用读到新值）；
三个预置故障必须各被检出——`early_commit`（未同步就提交 → 「新跳转进入尚未缓存
同步的蹦床」）、`free_live_page`（发布后回收页 → 「旧跳转进入已回收的蹦床页」）、
`multi_word_commit`（多字覆盖 → 「新旧混合执行」）。

**边界（重要）**：这只把门槛 5 从「多字 memcpy 不可原子」收窄成「单字提交 + 架构
保证」，**不关闭门槛 5**。仍缺三项**实机**证据：蹦床页的实际内存属性与写入独占性、
宿主映像（`libapp.so`）的映射生命周期、以及我们自己的提交是否真只发出一条对齐 32 位
存储（编译器/后端不得拆成多字）。因此在拿到这些证据前，本模块仍不写安装路径，
`install_permitted` 恒为 `false`。数量蹦床、代码页发布和近距岛式后端现已链入原型 SO，
并接受生产目标的机器码与故障审计；但阶段锁仍不授予后端授权。模型本身也
不是 ARM 硬件或 herd7 证明。

### 门槛 5 三项残余的只读取证（2026-10-13 新增）

上一节列出的三项残余由 `tools/launcher_gate5_residual_audit.py` 逐项变成机器可查的
证据。合同是 `app/src/test/resources/launcher-gate5-residual-contract.json`
（策略 `gate5-residual-offline-v1`，`installPermitted=false`，显式保留门槛 3、5）；
证据源是**生产实现** `app/src/main/cpp/launcher/singleword_publish.cpp`
（协议声明在 `launcher/singleword_publish.h`）。

> 2026-10-13 升级：残余项 (c) 原先编译的是测试目录里的等价写法
> `launcher_singleword_commit_trial.cpp`，只能证明「协议这么写会得到单条存储」；
> 现在编译的是**真正要安装的那份实现**，机器码证据与产物一一对应。合同新增
> `trialSource` 字段固定该路径，工具会核对三条：合同声明的路径、命令行传入的文件、
> 以及「不得落在测试目录」，任一条不符直接失败（否则把证据来源换回替身也能通过）。
> 旧试验源已删除，避免两份实现同时存在后各自漂移。

同一份实现的**协议判定**（例外集合分类、可达性、提交门禁的每一条拒绝路径）由
`app/src/test/cpp/launcher_singleword_publish_test.cpp` 在原型构建的
`launcher_probe_contract` 目标里做编译期断言，因此它不只被离线工具看过一遍。

`launcher/singleword_publish.cpp` **刻意不进任何可执行构建目标**：它含代码页写入，
而完整的安装流程还缺「分配蹦床页并封 RX」那一半、安装许可也恒为 false。
本地工具 `verify_no_product_write_path()` 会把这条约束变成机器断言——CMakeLists 里
任何非注释行引用该文件即失败。

```powershell
python tools/launcher_gate5_residual_audit.py --host-elf $env:LAUNCHER_LIBAPP `
  --host-apk $env:LAUNCHER_APK --maps $env:LAUNCHER_MAPS --clang $env:ANDROID_NDK_CLANG
```

**(a) 蹦床页内存属性与写入独占性**：要求映射快照里**没有任何**可写可执行段；6 条
调用点的文件页按 **设备号 + inode + 文件偏移区间**扫描全进程别名（包括远处虚拟地址、
不同路径与不同权限），发现可写别名或第二个映射实体即拒绝；并要找到「RX 代码页紧邻
同一实体的 RW 数据页」的既有先例。该先例只说明快照时存在这类布局，**不证明新蹦床
分配必然获准，也不证明实际内存类型或未来写入独占**。全部通过时 `W+X 段数=0`。

**(b) 宿主映像映射生命周期**：宿主 ELF 层面要求无「可写可执行 PT_LOAD」，动态段
不得含 `NEEDED / INIT / FINI / INIT_ARRAY / FINI_ARRAY / RELA / REL / JMPREL / PLTGOT`
并必须含 `SYMTAB/STRTAB`。这些只是生命周期相关结构检查，**无这些标记不等于不能卸载**。
新增必需输入 `--host-apk`：核对 `lib/arm64-v8a/libapp.so` 唯一、未压缩、页对齐，
本地条目头与 ZIP 目录一致，条目内容与合同固定 ELF 完全一致。条目起点由 APK 独立确定，
再按 `条目起点 + ELF.file_offset(调用点)` 校验 maps 中对应字节的文件偏移。
全部文件支撑 `PT_LOAD` 逐片核对，不只检查六条调用点；允许同段拆成多条映射，
不允许缺页、权限漂移或跨设备号/inode/路径混拼。包路径严格匹配宿主包目录与
`base.apk`，不接受相似包名前缀、分包或已删除文件。

`--base` 现为可选断言；省略时由条目起点和全部文件 `PT_LOAD` 唯一推导，零候选或
多个候选均失败。原先的页内等式代入 `address = base + offset` 后恒成立，不能校验
基址；现已移除。也不再假定 ELF 虚拟地址等于文件偏移。

**(c) 提交确为单条对齐 32 位存储**：用真实 NDK clang 以固定参数
（`--target=aarch64-linux-android29 -O3 -std=c++17 -Wall -Wextra -Werror -fno-exceptions
-fno-rtti -fvisibility=hidden -mno-outline-atomics`）交叉编译**生产实现**并反汇编：
`launcher_commit_call_site` 必须**恰好**是「一条 32 位存储 + `ret`」——本设备工具链
产出 `stlr w1, [x0]`（`0x889ffc01`，`__ATOMIC_RELEASE`）；`launcher_publish_trampoline`
必须是 `dc cvau → dsb ish → ic ivau → dsb ish` 且**不含任何数据存储**；
`launcher_publish_then_commit` 里同步序列必须全部早于存储。多字存储（`stp` 等）
会被直接判失败。

诊断负例（都不入库、只在本机跑）：`installPermitted=true`、把调用点改成相邻地址、
删掉门槛 5、替换试验符号、抹掉调用点映射页、把调用点页改成 `W+X`、去掉 RW 先例、
把证据来源换回测试替身、把写入实现链进构建，改动全部被拒绝；
`test_launcher_gate5_residual_audit.py` 另有 42 项单测（含证据来源与安装路径隔离两组）。

**边界（重要，未变）**：三项证据都只是**只读静态**。映射快照不能证明完整采集、
当前仍有效、实际内存类型、未来重映射/卸载或写入独占；编译器对象不覆盖实机安装实现、
运行期竞争或其它线程的实际执行时机，因此**不构成门槛 5 关闭，也不构成安装许可**。

### 映射快照的可重复回归（2026-10-13 新增）

`tools/launcher_gate5_snapshot_audit.py` 把 (a)/(b) 独立成不依赖 NDK 的回归入口：
首次审核本机留存材料后封存路径与 SHA-256；回放时逐一校验宿主 ELF、APK、maps 与合同
摘要，并重新执行检查，**不是读取旧报告判通过**。支持多份快照各自推导 ASLR 基址，
不接受重复快照、缺失材料、摘要漂移或安装许可变化；任何一项失败返回非零，不跳过。

```powershell
# 首次封存；只写已忽略的 .workbuddy，不复制或采集设备材料
python tools/launcher_gate5_snapshot_audit.py record --host-elf $env:LAUNCHER_LIBAPP `
  --host-apk $env:LAUNCHER_APK --maps $env:LAUNCHER_MAPS

# 后续一键回归，无需再填快照路径、基址或编译器
python tools/launcher_gate5_snapshot_audit.py replay
```

默认清单 `.workbuddy/launcher-gate5-snapshots.json` 不入库，已有清单不覆盖。
追加新一批材料时用 `--output .workbuddy/<新清单>.json`，回放用 `--manifest` 指定；
多快照重复传 `--maps`。合同变化也需重新审核、封存，旧证据不会自动“升级”。
成功报告只含摘要、计数、基址来源与拒绝对照结果，不含本机路径、地址或 inode。

每份快照自动执行 **9 项拒绝对照**：调用点 W+X、删除调用点映射、文件偏移漂移一页、
远处可写别名、远处只读别名、相似包名、移除 RX/RW 先例、删除非调用点的文件 PT_LOAD
页、显式基址漂移一页。对照只修改内存中的离线文本结构，不修改原始材料；必须在预期
检查处失败，错误放行或不相关错误均使整个回归失败。

`tools/test_launcher_gate5_snapshot_audit.py` 覆盖独立偏移、拆分段、ASLR、多候选、
别名与设备号区分、APK 条目异常、清单篡改、多快照、CLI 退出码及对照本身的失效检查。
该入口**不执行 (c)**；完整三项仍使用上一节命令。两者都保持 `安装许可=false`，
本工具报告未关闭门槛 `[3,5]`，不改变项目门槛 1、3、5 的状态。

### 提交实现的真实工具链故障对照（2026-10-13 新增）

上一节的残余项 (c) 只用**合成机器字**证明判定会拒绝多字存储。为了把证据提到
「判定在真实编译器输出上也成立」，新增 `tools/launcher_singleword_commit_fault_audit.py`
与合同 `app/src/test/resources/launcher-gate5-fault-contract.json`
（策略 `gate5-commit-faults-offline-v1`，`installPermitted=false`）。故障源是
`app/src/test/cpp/launcher_singleword_commit_faults.cpp`（同样不参与 Android 构建），
里面故意写了六种常见错误实现，全部由真实 NDK clang 编译后用**同一套判定函数**去跑：

```powershell
python tools/launcher_singleword_commit_fault_audit.py --clang $env:ANDROID_NDK_CLANG
```

| 错误实现 | 实测机器码 | 被哪条判定拒绝 |
| --- | --- | --- |
| 分两条 `str w` 写两个字 | `b9000001` / `b9000402` | 提交函数「恰好两条指令」 |
| 一条 `stp` 写两个字 | `29000801` | 「不是单条 32 位存储」 |
| 64 位 `str x` 覆盖两个字 | `f9000008` | 提交函数「恰好两条指令」 |
| 退化成 `memcpy` | `ldr w8` / `str w8` | 提交函数「恰好两条指令」 |
| 缺 `ic ivau` 的同步序列 | 只有 `dc cvau` + 两条 `dsb` | 「缺少 ic ivau」 |
| 先提交、后同步 | `str w3` 在 `dc` 之前 | 「先缓存同步、再提交」 |

合同把实测使用的 clang 版本与编译参数一并固定：参数与工具当前使用的参数不一致时
直接失败，避免换了编译器却沿用旧结论。另有
`tools/test_launcher_singleword_commit_fault_audit.py`（12 项）覆盖「关键字不匹配
必须失败」这一元判定，防止把任意异常都当成通过。

### 蹦床自身的控制流语义（2026-10-13 新增）

`tools/launcher_menu_island_audit.py` 与合同
`app/src/test/resources/launcher-menu-island-contract.json` 把**蹦床本身**的语义
钉死，素材是 `app/src/test/cpp/launcher_menu_island_arm64.S`（只交叉汇编，
不接入 CMake、不链入任何 SO）：

```powershell
& $env:NDK_CLANG --target=aarch64-linux-android35 -c `
  app/src/test/cpp/launcher_menu_island_arm64.S -o "$env:LAUNCHER_TRIAL_DIR/menu_island.o"
python tools/launcher_menu_island_audit.py --host-elf $env:LAUNCHER_LIBAPP `
  --object "$env:LAUNCHER_TRIAL_DIR/menu_island.o"
```

先做了一条只读实验确认 `x30` 语义：`BL 蹦床` 由硬件把 x30 设成「调用点+4」，
蹦床是叶函数（没有 BL、没有 RET、不写任何内存），用 `br` 尾跳原目标，原目标
照常按 x30 返回。**结论是这里不需要“显式恢复 x30”，只需要保证不破坏它**——
早期文档里“蹦床必须显式把 x30 恢复成调用点+4”的说法会诱导实现去写 x30，
方向是错的，已更正。

工具在隔离模拟器里真实执行 `合成调用方 → bl 蹦床 → br 原目标(替身) → ret`，
逐项核对：进入原目标时 x30 == 调用点+4、Dart 栈指针与帧指针未变、条件标志
原样保留、除 x16/x17 外没有任何通用寄存器被改、全部向量寄存器不变、C 栈指针
不动，并要求这段路径上除测试装置外**零内存写入**。另有三个故障对照
（尾跳写成 `blr` 破坏 x30、调用点写成 `B` 而不是 `BL`、蹦床指向错误目标）
必须被检出。真实宿主复核：6 条调用点 × 5 组条件标志 = 30 个穿越用例通过。

**边界**：目标函数**函数体**没有执行（它依赖 Dart 运行期对象，不伪造整套运行时
就跑不通），所以本工具证明的是蹦床自身的控制流与寄存器契约，**不是**四项功能的
端到端行为，也不证明安装窗口无并发执行、新代码缓存同步或宿主映射生命周期。

### 分享 APK 真实函数体的最小离线试点（2026-10-13）

`tools/launcher_share_apk_flow_audit.py` 在上述叶蹦床试验之外，执行真实宿主
`ValidShareAppShortcutMenu.shareApk` 的全部 188 字节函数体；**外部调用与对象池
仍为受控替身**。详细指令、模型、复现命令与未解决项见
`app/src/main/cpp/share-apk-offline-pilot.md`，合同为
`app/src/test/resources/launcher-share-apk-flow-contract.json`。

- 两个调用点各比较直接调用与经叶蹦床调用；三种接口结果、两个虚拟布局、三组 NZCV
  共 **72 次执行 / 36 对逐项比较**，核对入参、八次外部调用、四次对象写入和栈/返回。
- `0x17fdac0` 是 `cmp w0,w22`：空接口及合成低 32 位别名只执行前四次边界；
  非空路径写字段宽度为 4/8/4/4，最终由 `0x17fdb1c` 返回 Dart null。
- 每次执行带 8 个错误机器码对照和 8 个边界中止对照；中止不能冒充成功或失败回退。
  新增 49 项合成单测，不依赖本机真实 ELF，也不把替身当作实机行为证据。
- 与旧入口/返回值/叶蹦床合同交叉核验身份、调用点和命名目标；旧 24 字节候选未改。

**尚未实现分享功能**：基础函数体工具仍把外部调用和对象池当作替身；两个未命名目标的
补充取证见下一节，独立的静态快照池内容见再下一节。静态池值不能证明 chooser 或文件授权。
不模拟 GC、异常展开、Binder、真实启动与线程时序。
不动 `isValid`、不接安装路径、不链接蹦床，门槛 1/3/5 和 `install_permitted=false` 不变。

### 分享 APK 对象边界的补充取证（2026-10-13）

`tools/launcher_share_apk_object_audit.py` 与
`app/src/test/resources/launcher-share-apk-object-contract.json` 固定八段函数、
2188 字节静态证据，并在原试验上将 **196 字节真实代码范围**纳入模拟执行：

- `0x9a2c58` 是转入命名 `RustLibApiImpl.hyperApiBundleBundleNew` 的 108 字节包装器，
  执行就绪/迟初始化两条分支；实际 Rust 调用和初始化服务仍为替身。
- `0x18c00d8` 的 12 字节分配桩和 `0x1b57110` 的 76 字节通用快路径产生
  48 字节、tag 为 `0xd7831c` 的对象；与 `Intent.==` 的类 ID `0xd78`、
  两种 Intent 解码路径对同一分配桩的调用一致。
- 八字段的偏移、宽度及命名编解码类型由 SSE 编码/解码两侧互查；
  `+15` 是 8 字节内存槽，但边界符号是 `putInt32/getInt32`，不猜成 Int64 或 flags。
- **144 次执行 / 72 对比较、8 项拒绝对照、42 项新增单测通过**。接口缺失或进入
  分配慢路径只能报未建模，不能冒充成功；基础 72 次函数体试验仍独立保留。

详见 `app/src/main/cpp/share-apk-offline-pilot.md` 的“对象边界补充取证”。
旧对象模拟合同仍强制 `poolContentsResolved=false`、字段 `semanticName=null`，因为该模拟器
没有执行真实池对象；后续静态快照合同是独立证据，不能倒灌成替身模型已解析池内容。
模拟堆不是实机分配器，仍不覆盖 GC、异常展开或端到端分享，所有门槛与安装边界不变。

### 快照对象池顺序解码（2026-10-14 新增）

要继续把四个池槽解析成真实对象内容，必须先确认公开 Dart 3.10.1 的解码规则能否直接
套在宿主快照上。本轮只读复核的结论是**不能假设**：快照版本串由
`tools/make_version.py` 把 15 个格式源文件按固定顺序拼接取 MD5，宿主两处快照的版本串
都是 `5d85d85296400e9b3992193deed317c9`，而官方 tag `3.10.1` 同样 15 个文件
（逐个用 blob SHA-1 复核字节一致）算出的却是 `1ce86630892e2dca9a8543fdb8ed8e22`。
宿主引擎自报 `3.10.1 (stable)`，但格式摘要不同，因此**公开 SDK 的解码规则只能当待验
假设**，必须逐条用宿主字节自洽性确认；不得据同名版本宣称格式兼容。

`tools/launcher_share_apk_snapshot_audit.py` 从 `.dynsym` 定位 isolate 数据并顺序执行
1465 个簇的 `ReadAlloc` 和 `ReadFill`。117870 个 canonical String 的 alloc/fill 编码长度
逐项一致；唯一 ObjectPool 的两次长度都是 123596，分类计数为
`obj=121933, imm=797, skip=683, native=183`。四个槽均为 `kTaggedObject`，结果为：

```powershell
& .workbuddy/venv-launcher-count/Scripts/python.exe `
  tools/launcher_share_apk_snapshot_audit.py --host-elf $env:LAUNCHER_LIBAPP
```

- `firstKey`：`0xb9458` / idx 94857 / ref 8340 →
  `android.intent.extra.PACKAGE_NAME`
- `secondKey`：`0xb9460` / idx 94858 / ref 88650 →
  `com.miui.mishare.extra.MISHARE_APK_PACKAGE_NAME`
- `firstField`：`0xb9468` / idx 94859 / ref 101022 →
  `android.intent.action.SEND`
- `lastField`：`0xb9470` / idx 94860 / ref 105341 →
  `application/vnd.android.package-archive`

这是槽位、引用号与 String 内容逐级交叉核对形成的**宿主自洽证据**，不是字符串扫描；
仍不证明官方格式兼容，也不覆盖实机运行期、线程时序、真实 Intent 组装、chooser、
文件授权或分享可用。新合同与旧 `poolContentsResolved=false` 替身合同保持分离。详见
`app/src/main/cpp/share-apk-offline-pilot.md` 的“快照对象池顺序解码”。

### 四项功能的返回值用法（2026-10-13 新增）

叶蹦床转发原目标之后，**调用者怎样使用这次调用的返回值**决定了蹦床允不允许改写
`x0`。四项功能在这里分成两类，必须分开固定：

- **数量点**：调用点之后是 `mov x2, x0`（`0x114701c = 0xaa0003e2`），getter 返回的是
  **未装箱整数**，紧接着被送进裁剪窗口。数量点专用蹦床**就是要改写 `w0`**。
- **分享 APK / 小窗 / 新任务**：接续要么是 `b <尾部>`（返回值被丢弃、尾部换成 Dart
  空对象 `mov x0, x22`），要么是 `mov x1, x0`（把返回的 Dart 对象存进帧槽）。
  这三项的蹦床**不得改写 `x0`**，否则会把 Dart 对象换成别的东西。

`tools/launcher_menu_return_contract_audit.py` 与合同
`app/src/test/resources/launcher-menu-return-contract.json`
（策略 `menu-call-site-return-contract-offline-v1`，`installPermitted=false`）
把这条区分钉成机器断言：

```powershell
python tools/launcher_menu_return_contract_audit.py --host-elf $env:LAUNCHER_LIBAPP `
  --object "$env:LAUNCHER_TRIAL_DIR/menu_island.o"
```

工具做三件事：(1) 读每条调用点接续指令，判定返回值是「转发」还是「丢弃」，并要求
与合同一致；(2) 要求「蹦床可改写 `x0`」与返回值用法自洽，且**只有数量点**允许；
(3) 逐条按**白名单**解码叶蹦床的 4 条指令（`adrp/add/ldr/br`，寄存器必须是 `x16`），
任何不在白名单内的机器字直接失败——不使用「匹配不到写 `x0` 就放过」的写法。
真实宿主复核通过：数量点=转发且允许改写、其余三项=转发或丢弃但**不允许**改写；
叶蹦床 4 条指令零写 `x0`、只用 `x16`。另有
`tools/test_launcher_menu_return_contract_audit.py`（21 项）覆盖掩码必须包含 `Rd`
的回归（`add x16, x16, #0` 不得被误判成 `add x0`）。

**边界**：只固定调用点返回值用法与叶蹦床不改写 `x0`；不证明蹦床已接入安装路径、
不证明四项功能端到端行为，也不改变门槛 1/3/5 的状态。

### 数量点专用蹦床（2026-10-13 新增）

上面那个是**叶蹦床**（透明转发，不动 `x30`）。数量点不能那样做：被替换的调用点
原本是 `BL _getMaxCountInCurrentOrientation`，而行为改写要发生在 getter **返回之后**，
所以数量点蹦床必须自己改写 `x30`，让 getter 返回到岛内接续点。素材是
`app/src/test/cpp/launcher_count_island_arm64.S`（同样只交叉汇编、不接入 CMake、
不链入任何 SO），合同是 `app/src/test/resources/launcher-count-island-contract.json`
（策略 `count-call-site-island-offline-v1`，`installPermitted=false`，64 字节、
sha256 `1b5fa8d8…75645`）：

```powershell
& $env:NDK_CLANG --target=aarch64-linux-android35 -c `
  app/src/test/cpp/launcher_count_island_arm64.S -o "$env:LAUNCHER_TRIAL_DIR/count_island.o"
python tools/launcher_count_island_audit.py --host-elf $env:LAUNCHER_LIBAPP `
  --object "$env:LAUNCHER_TRIAL_DIR/count_island.o" --require-both-classes
```

控制流是：`BL 蹦床`（硬件把 x30 设成调用点+4）→ 蹦床首条 `adr x30, .Lcount_after`
把返回点改成岛内偏移 20 → `br` 原 getter → getter 照常跑完整副作用并 `ret` 到
`.Lcount_after` → 用 `ldar` 读一次性原子字、`sub/cmp/b.hi` 做无符号 5..20 判定，
只有成立才 `mov w0, w17` → 从数据槽取回调用点+4 并 `br x30`。全程无 `RET`、无 `BL`、
无栈写入；只碰 `x16/x17`。**条件标志不在承诺范围内**：被替换的本身就是一次函数调用，
原 getter 也会破坏 `NZCV`，审计因此不检查它（叶蹦床那条要求保留标志，两者不要混用）。

工具在隔离模拟器里执行真实的三段流程，逐项核对：进入 getter 前 `x30 == 岛内接续点`、
`x15/x29` 与除 `x16/x17/x30` 外全部通用寄存器、全部向量寄存器不变；回到接续处
`x30 == 调用点+4`、只有 `x0/x16/x17` 可变、`x0 == 状态字在 5..20 时取状态字否则取原
getter 返回值；全程零内存写入。四个故障对照（去掉范围判定、覆盖源写成已减 5 的
中间值、末条 `br x30` 改成 `blr`、不恢复 `x30`）必须被检出。真实宿主复核：
状态矩阵 `{0,4,5,20,21,0x7fffffff,0xffffffff}` × 原生返回值 `{3,4}` = 14 个用例通过。
注意状态字 4 与原生返回值 4 数值相同，但语义是“不干预”，合同与工具把它算作透传
而不是覆盖——这正是 `覆盖` 判定只能看状态字范围、不能写成 `state == 生效上限` 的原因。

**边界**：getter 用返回值替身（函数体依赖 Dart 运行期对象），只证明蹦床的控制流、
寄存器与覆盖契约；不证明四项功能端到端、安装窗口无并发执行、新代码缓存同步、
宿主映射生命周期与写入独占性。合同仍显式保留门槛 1/3/5 未关闭。

可选 `--entry-elf` 核验 `libapp_launcher.so` 的完整摘要、动态符号定义、
`app_entry_point` 的完整 960 字节及关键返回指令；MCP 再逐字节复核该入口。
该入口库没有 GNU Build ID，因此使用独立的长度和完整摘要合同，**没有放宽**
主目标 `libapp.so` 的 Build ID 要求。

入口 `0x9b544c` 在 `0x9b5464` 将 x8 保存到 x19，`0x9b56c4` 与 `0x9b56d4`
经 x19 写回偏移 0、8、16 的三个机器字。这否定普通 `void (*)()` 调用的假设，
但不确定返回对象类型、所有权或完整函数原型。未发现该库定义 `JNI_OnLoad`，
也不能据此推断进程没有 JVM。当前没有调用、替换这个入口或把它用作加载引导。

## 身份采集边界

`LauncherAndroidIdentity` 只支持已完成绑定的 ARM64 Java 应用进程：精确包名、
主进程名、UID、进程启动代际、可执行文件路径及绑定应用必须一致。通过包管理器
核验元数据、无共享 UID、无分包，再校验 APK 内清单存在且目标 SO 为 ARM64 ELF。
采样前后重新读取进程与应用记录；读取失败或宿主变化时不缓存成功结果。

已取证清单声明 `hasCode=false`、必需运行库 `hyperos.rustruntime.v5`、
入口 `libapp_launcher.so`。运行期读取应用元数据，不再绑定某份清单摘要。
**尚未证明该宿主会到达 Java 应用路径**；Spawner、无 Java Application 或未知入口
继续拒绝，不能把此采集器视为已打通原生桌面加载路径。

容器检查不证明当前执行映射、Dart ABI、GC 或框架后端安全；真正的 Hook 点
由原生解析器按现场参数和调用关系检查，未通过时不安装。

系统侧静态取证已补齐 `ProcessList.startProcess` 经
`RustProcessManagerImpl.startProcess` 到 `RustProcessImpl.startRustProcess` 的
条件分支：启用 Rust 运行时、应用 Rust 标记和运行时激活状态均满足时，向
`hyos_spawner` 本地套接字发送包含入口库名的启动请求。这里的 Java 代码运行在
系统服务侧，不代表桌面子进程可获得 Java Application。现有身份采集器仍拒绝
spawner；后续必须独立核验套接字接收端和实际框架注入路径，不能仅放宽白名单。

## 后续硬门槛

下一阶段先证明实际框架的加载、子进程身份、配置、安全许可与原生 ABI，
再审计 continuation 发布和并发补丁提交。上述条件未成立前，不进入菜单挂载。
数量候选在原 getter 调用后消费 raw 整数，不能把普通 C++ 调用约定套在 Dart 上，
也不能把静态字节一致或构建成功写成运行期安全证明。

## 宿主身份与框架 ABI（2026-10-13 实测）

桌面主进程实测为 `usap64` 池 fork 出的进程：`/proc/<pid>/exe` 仍是
`/system_ext/bin/hyos_spawner`，`cmdline` 被 `setprogname` 特化为
`com.miui.home`，进程内没有 ART。`host_identity.h` 据此只认
cmdline 判据，并把 usap 池、其它同 exe 的 Rust 应用和未知进程分别拒绝；
`exe` 不是排除桌面的条件。

框架入口通过 LSPosed 原生模块 API 到达：`native_init` 收到
`NativeApiEntries{version, hook_func, unhook_func}`。`native_entry.cpp` 现在会
记录版本并在精确匹配后保存两个指针用于只读诊断，但**从不调用**它们；
返回的加载通知只用于单次诊断调度，保存指针不会跳过版本门禁。

设备持久化 logcat（`/data/adb/ksu/log/logcat*.log`）里 HyperCeiler 原生入口
自报 `native v34 hook API version=2 unhook=1 hyosSpawner=1 launcher=1`：
框架实际填进 `NativeAPIEntries.version` 的值是 **2**，`native v34` 只是
HyperCeiler 自己的版本标记。据此 `native_api.h` 只接受**精确等于 2** 的版本
（`kSupportedNativeAbiVersion`），`probe_status` 把它单列为 `kAbiVerified`
（位 `0x800`）；不匹配（含空入口参数的版本 0）仍置 `kAbiRejected`（位 `0x8`）。
版本受支持**只解除 ABI 那一项阻断**，宿主、安全、后端三项阻断位不变。

`nativeStatus` 额外以高位报告：保存的 hook/unhook 指针与本进程身份分类
（已特化桌面 / usap 池 / 其它 spawner / 未知）。身份成立只是安装前置条件之一，
安全许可与后端许可仍未实现。

## 后端发布语义与 Dart 调用约定（2026-10-13 离线取证）

**更正：门槛 1（模块 `original` 发布顺序）重新列为未通过。** 旧版上游
`native_util.h` 的直接转发实现不能代替实际留存框架样本的包装层。
样本 Build ID 为 `4bb85072ddafabcc9cd1cc1e449831846804c931`；尚未重新证明其与
当前设备映像一致，不从上游源码推定设备正在运行同一实现。

`source/InterceptRouting/Routing/FunctionInlineHook/FunctionInlineHook.cc` 的顺序是
`DispatchRouting()`（生成重定位代码）→ 回填 `*origin_func = entry->relocated_addr`
→ `Commit()`（`InterceptRouting::Active()` 才把跳转补丁写进目标代码页）。也就是说
**Dobby 内部先回填传入槽，再提交补丁**。该事实没有改变，但不能推出模块的
槽也已发布：样本 `0x7d2a0` 传给 Dobby 的是包装器栈内临时槽，`0x9d678`
写这个临时槽；实际补丁提交完成后，外层才在 `0x89380` 写模块传入的槽。
`original` 指向重定位副本，副本本身由 `FinalizeFromTurboAssembler` 经
`DobbyCodePatch` 写入并在其末尾做 `ClearCache`（`dc cvau`/`dsb ish`/`ic ivau`/`isb`），
这说明参考源码包含中继代码写入与缓存维护步骤，不单独证明并发执行安全。

样本 API 构造器将 `hook_func=0x8d764` 写入 ABI 页面，经 `0x194a10` 传给
`native_init`。实际调用链是：

```text
0x8d764 -> 0x89358 -> 0x7d298 -> 0x9d498
 -> 0x9d678（只回填临时槽）
 -> 0x9de74 -> 0x9dd20 -> 0xa5324（提交目标补丁）
 -> 底层返回 -> 0x89380（回填模块 original）
```

`tools/launcher_backend_publication_audit.py` 对完整样本、槽和关键指令做校验。
加 `--emulate` 时执行真实 API 构造/入口取参片段、包装与 Dobby 控制流、
routing 构造和提交/复制指令。24 个离线场景均观察到：目标补丁已完整写入，
模块槽仍保持初始零值或旧值；随后外层才回填。4 组错误指令对照须被检出。
对象分配、前置改页、登记、重定位生成为替身；未执行真实中继、并发线程或
完整加载流程。此结果不是实机崩溃证明，也不排除完整运行时另有外部执行互斥。

不能靠安装返回后检查非空来消除这个发布窗口。**门槛 1、3、5 都未通过**；
`install_permit.h` 的后端阻断位涵盖门槛 1 和 5，阶段锁继续恒拒绝安装。
现有数量蹦床仍不链接，也不自动切换独立单分支候选。

```powershell
.workbuddy/venv-launcher-count/Scripts/python.exe tools/launcher_backend_publication_audit.py `
  --framework .workbuddy/tmp/lspd-bin/lspd_v8a.so `
  --libc .workbuddy/tmp/lspd-bin/libc.so `
  --host-elf .workbuddy/tmp/home/lib/libapp.so --emulate
```

Dart 调用约定依据 `dart-lang/sdk` 的 `runtime/vm/constants_arm64.h`：`R15` 是 Dart 栈
（不是 C 栈），`R16/R17` 是编译器暂存，`kAbiPreservedCpuRegs = R19..R28`，而
`R9..R11` 属于 Dart 可用寄存器、可承载活值。`dart_abi.h` 把这条结论与
`shortcut_count_arm64.S` 的逐条编码一起钉成编译期断言。

## 数量试点：两个候选的区别（`dart_abi.h`、`shortcut_count_arm64.S`）

| 候选 | 挂载点 | 语义 | 代价 |
| --- | --- | --- | --- |
| 候选 1：getter 入口替换 | `0x11474f4` 函数入口 | 直接返回新的裸整数上限 | **跳过**原 getter 的字段读取与方向查询；必须先证明被跳过的副作用对本调用不必要 |
| 候选 2：调用后接续点 | `0x114701c` 起 24 字节 | 原 getter 照常执行，只改写它返回给本次裁剪的标量 | 需要后端支持内部挂载并正确重定位窗口内 6 条指令 |

两者不是同一个方案的技术降级：候选 1 改变执行语义，候选 2 保留语义只改数据。
当前实现只做候选 2，理由是不必先证明 getter 副作用可省略。

对 getter 全体逐条反汇编后已确认：候选 1 会跳过一次 `Inst|find`、一次
`DeviceConfig.isScreenOrientationLandscape` 方向查询和多次运行期字段读取，
**不能仅凭“返回裸整数”证明其副作用可省略**，因此候选 1 保持“未采用”。
候选 2 则对覆盖长度鲁棒：Dobby 常规中继副本开头总是窗口首条 `mov x2, x0`，
覆盖长度取 12/16/24 时都能把新上限经 `x2` 送进本帧，故“精确覆盖长度”不再是
候选 2 的正确性阻塞。实测该宿主命中的是远距分支的 24 字节足迹，合同据此固定为
24（仍待实测的是安装时机的并发安全）。

蹦床按 Dart ABI 保存 `x9/x10/x11` 与 `nzcv`，读一次性原子字（0 = 不干预，
5..20 = 直接作为 `x0`），再用 `br` 尾跳 `original`（不用 `blr`，不写 `x30`）。
`mov w0, w10` 写入合法上限并清零 X0 高 32 位，因此传给窗口的是 5..20 的裸整数。

该汇编当前只在 `launcher_shortcut_trampoline` 这个 OBJECT 目标里交叉汇编，
**没有链入诊断库**：链接就意味着存在可被触发的替换入口，在宿主许可、安全许可
与实测安装时机验收完成前不引入。

## 安全门禁与安装许可（门槛 1、3、5 的阻断）

桌面原生进程没有 ART，Kotlin 侧的 `SafeModeRuntime` / `BootLoopGuard` 在本进程
里跑不起来。`launcher/safety_gate.h` 提供了桌面等价物：作用域标识与 launcherNative
注入作用域的桌面项（`scope.list` 第 1 项与 `LauncherHostContract.PACKAGE`，**不是**主模块 `SCOPES`）
一致、状态行与 `BootFuseState` 同构、决策函数与 `BootFusePolicy.next`
同构（读不到旧状态、时钟异常、已熔断一律拒绝），五分钟内三次重启熔断、
恢复需要严格递增令牌。`launcher/install_permit.h` 把宿主、ABI、安全、后端
四类证据收敛成唯一判定点 `install_permitted`，安全证据只能来自
`safety_evidence(decision)`，调用方不能自填。

这套逻辑的合法/畸形输入、三类拒绝、窗口计数与“待验收阶段一律拒绝”都由
`launcher_safety_gate_test.cpp`、`launcher_install_permit_test.cpp` 的
编译期断言钉死。入口已接只读安全预检；持久化后端与受控维护事务也已接入原型入口，
但维护授权只来自设置页的一次性短时请求，不能由普通配置快照伪造。
`install_permitted` 在当前阶段锁存在时仍为 `false`。

### 原生入口只读安全预检（2026-10-14）

实际调用链为 `native_init → maybe_schedule_diagnostic → diagnostic_worker →
query_configuration_once → safety_preflight_now`。JNI 人工查询复用同一查询闸门，
不能并发推进旧快照。预检使用**采纳前**的修订号，而非刚发布的修订号。

- 只有快照采纳成功且身份/ABI 再次通过、功能非全关，才读取本地熔断状态。
- 非零恢复令牌在读取状态前由 `ordinary_snapshot_errors` 拒绝；正值、上界值、
  与本地相同或较旧的非零值都不是恢复授权。
- 根据已核验的 UID 选择 `/data/user/<用户编号>/com.miui.home/files/hyperextend_launcher_bootguard`；
  包目录名来自 `kLauncherSafetyScope`。用户根目录用 `O_PATH` 打开，允许系统维护的别名；
  包目录、`files` 和状态文件通过 `openat` 逐级拒绝符号链接，不创建缺失目录或文件。
- 检查目录属主与写权限；文件必须是同 UID、单硬链接、私有权限的普通文件，
  长度为 1–127 字节。读取错误、短读到 EOF、超长、尾部多余内容和关闭失败均拒绝。
- I/O 后重新读取开机秒钟与墙上毫秒钟，分别核对快照租期和熔断。
  调用 `safety_fuse_next(..., true)` 与 `snapshot_safety_permits(..., true)`，
  本次启动达到阈值也会拒绝；随后再次复核身份。
- 计算正常仍带 `kPreflightPersistencePending`，安全证据不能为真；
  `install_permitted` 的返回值控制拒绝分支，后端与阶段锁没有解除。

`HELauncherSafety` 新标签输出本次查询的只读阻断位，不记录路径、UID 或恢复令牌。
日志固定保留 `persisted=false`、`install_permitted=false`；不改变 `HELauncherProbe`
的既有模板，门槛 3 日志审计也不把新标签当作持久化验收证据。

| 预检位 | 含义 |
| --- | --- |
| `0x1 / 0x2` | 未运行 / 通道拒绝 |
| `0x4 / 0x8 / 0x10` | 身份不符 / 快照拒绝 / 功能全关 |
| `0x20 / 0x40` | 本地状态不可读 / 本地熔断判定拒绝 |
| `0x80 / 0x100` | 持久化待完成 / 安装许可拒绝 |

合法输入是 `0x180`，不是成功许可；本地文件缺失是 `0x1e0`，不会偷偷初始化。
快照 `channel=0` 只表示通道校验当时采纳成功，I/O 期间过期仍由预检拒绝。
本读取器不加文件锁，也不保证并发变化中的一致快照，因此只能提供诊断，不能给安装提供安全证据。
只读原型没有初始化/写回 API，不应手工创建状态文件来绕过阻断。
线路形态有效的非零恢复字段仍可能显示 `channel=0`、`snapshot=0`，但用途校验会报告
`HELauncherSafety blockers=0x8`，并且不打开状态文件。这不是恢复成功，不能只看通道日志。

**安装接线仍未完成**：独立持久化后端与受控武装/残留恢复已接入原型入口，
但安装路径仍受阶段锁阻断；还需要真实文件系统、Binder、SELinux 与并发时机的运行期验收。
离线替身不证明真实开机循环或实机文件系统行为，不能关闭门槛 1/3/5，也不构成安装授权。

### 独立持久化后端（2026-10-14，已链入原型 APK）

`launcher/safety_startup.cpp` 的 `commit_safety_startup_once` 实现启动事务，
`safety_startup.h` 定义结果与阻断位；它已由 `native_entry.cpp` 的唯一安装链调用并进入
原型 APK。启动事务结果仍受阶段锁控制，不能把“已链入”或离线通过说成已经取得安装许可。

**现成文件与身份要求：**

- 沿用只读预检的用户目录、`kLauncherSafetyScope` 和状态名
  `hyperextend_launcher_bootguard`；目录必须归属当前 UID 且不可被其他身份写入。
- 状态与稳定锁 `hyperextend_launcher_bootguard.lock` 必须已存在、同 UID、
  普通文件、单硬链接、权限恰为 `0600`；状态为 1–127 字节，锁必须为空。
  缺失或损坏一律拒绝，较新的恢复令牌也不能覆盖损坏状态。
- 本启动事务自己不创建目录、状态或锁，也不提供删除接口；
受控创建与残留续写由下一节的维护事务处理，普通启动路径仍不创建缺失状态。
  内部 `context` 与修订号来自未来入口，不是不可伪造凭证；后端复核实时 PID/UID/EUID，
  不独立认证宿主 APK、重新解析 cmdline 或证明快照的服务端来源。

**提交顺序：**

1. 身份、普通快照用途/租期/修订号与功能位通过后，用无锁 CAS 消耗本后端实例的进程机会。
   同实例内重复调用、重入和失败重试均拒绝；继承父进程记录的子进程也拒绝。
   非零恢复令牌在文件 I/O 和机会消耗之前拒绝；零令牌不清除本地已有恢复高水位。
2. 通过 `LOCK_EX | LOCK_NB` 取得稳定锁，核对锁路径仍指向同一 inode；
   同步锁及目录项，再在锁内读取旧状态、复核时钟并调用 `safety_fuse_next(..., true)`。
3. 以 `O_CREAT | O_EXCL | O_NOFOLLOW` 创建同目录
   `hyperextend_launcher_bootguard.pending`，写完整的五列状态，同步并关闭临时文件。
4. 再次核对身份、租期及锁/旧状态/临时文件 inode，执行 `renameat`，同步目录，
   读回并比较已发布 inode 与五个字段；整个过程保持锁。
5. 关闭锁和目录后再次核验时钟及身份。`close` 也可能耗时，清理前的快照不能冒充最终许可。
   只关闭描述符，不显式 `LOCK_UN`，避免 fork 子进程解除父进程共享的锁。

**必须保留的失败语义：**

- 本次启动到达熔断阈值时，先提交 `disabled=true`，再拒绝继续。
  不能因为 `safety_permits_install` 为 false 就提前跳过锁存写入。
- `published=true` 只表示替换已经发生；之后同步、读回或关闭失败时，
  `committed=false`，不回滚、不重试。提交完成后租期过期或身份变化，
  可以是 `committed=true` 但 `safety_ready()=false`。
- 发布前失败留下的临时文件不自动删除、覆盖或复用，下一次启动也会被它阻断。
  这会停用模块功能直至受控恢复真正接线（离线事务已实现，
生产授权方与入口接线仍未完成），不是让用户手工删文件的操作指引。
- 进程机会保存在本次加载的后端实例中，不是跨卸载/重载、跨模块副本的唯一启动凭证。
  文件锁仅约束遵守同一协议的写入方，不防御同 UID 恶意改写、目录迁移或宿主自身篡改。

`launcher_safety_startup_trial.cpp` 直接包含该实现，以内存文件系统、锁和时钟替身执行
85 个场景，包括连续独立进程的计数/锁存、每个提交阶段失败、最后一次关闭时的过期与
身份变化，以及非零恢复令牌拒绝和零令牌保留本地恢复高水位。
Python 单测每次重新交叉编译，另在临时副本注入 5 种错误
（阈值提前早退、跳过目录同步、每次重置进程记录、跳过清理后租期复核、
把普通启动用途校验退回单纯的线路形态校验），
要求它们均编译成功、随后被实际执行的断言拦下，不能拿编译错误冒充检出。

```powershell
& .workbuddy/venv-launcher-count/Scripts/python.exe -m unittest discover -s tools `
  -p 'test_launcher_safety_startup.py' -v
```

这些场景不运行真实文件系统、掉电、Binder、SELinux 或并发调度器。
只有锁竞争明确不等待；普通文件 I/O 与 `fsync` 没有硬超时保证，未来接线不能把事务
放到库加载锁或主线程里同步执行，实际调度时机仍须验收。
`safety_ready()` 仅是后端结果，不是安装令牌；阶段锁、`防砖已确认=false`、
`安装许可=false` 与未关闭门槛 `[1,3,5]` 均保持不变。

### 受控武装与残留恢复（2026-10-14，已链入原型 APK）

`launcher/safety_maintenance.h` 定义 `maintain_safety_once`，与独立启动事务共用同一份
`safety_startup.cpp` 实现和同一把稳定锁。它已由 `native_entry.cpp` 通过独立维护事务码接入
原型 APK；端点只在设置页写入一次性请求后签发短时材料，普通快照不具备该能力。

**四种受控操作**（`SafetyMaintenanceOperation`）：

- `kArmAbsent`：状态与残留都不存在时，用 `O_CREAT | O_EXCL | 0600` 只创建一次稳定锁，
  再写入武装状态 `0,0,0,<令牌>,0`。它不伪造一次真实启动，下一个独立进程记录启动时
  计数仍从 0 开始。
- `kRearmKnown`：已有合法状态、没有残留时，用严格递增的新令牌重写状态。
- `kResumePending`：状态与完整五列残留都在、且残留恰等于
  `safety_fuse_next(state, true, pending.last_start_ms, 0, true).next` 时，只 `rename`
  续完残留，不写 pending 内容（`writes==0`），也不重建状态或锁。
- `kResumeArming`：续完一条武装残留，要求 `last_start_ms==0 && window_start_ms==0 &&
  rapid_restarts==0 && !disabled && 0 < last_reset_token <= INT32_MAX`，且已有状态时令牌
  必须严格更大；授权令牌必须等于残留令牌。

**授权接口不是认证实现。** `SafetyMaintenanceAuthorizer` 是可信入口注入的函数指针，
回调必须自行核验当次人工授权、宿主/用户/操作、观察到的状态、跨重启代际与令牌防回放；
`nullptr` 一律拒绝。结构体与函数指针都不是防伪凭证，当前没有生产授权方，回调也不得持锁
等待用户交互。授权检查逐项复核 PID/UID/操作、`issued_s` 落在 `[0, boot_now]`、
`expires_s > boot_now` 且租期不超过 `kSnapshotLeaseSeconds`；重置类操作要求令牌严格递增
且不超过 `INT32_MAX`，禁止饱和复用与回绕。

**残留与锁的 fail-closed 判据：**

- 残留必须能被重新编码成与原始字节逐字节一致的完整五列记录（canonical）；两列前缀
  `last_start_ms,rapid_restarts` 即使旧版解码器能解析，也一律拒绝。状态文件仍按非 canonical
  读取，以保留旧版两列状态的升级容忍。
- 锁已存在时不替换 inode，缺失时只有 `kArmAbsent` 能创建一次；创建失败或后续失败都不删除
  已创建的锁与残留。锁访问错误（非 `ENOENT`）、锁不是普通文件、多硬链接、非 `0600`、
  非空、`flock(LOCK_EX|LOCK_NB)` 竞争失败全部拒绝，不等待。
- 维护与普通启动**共用** `g_startup_owner` 的一次机会；维护成功后同进程仍不能启动或安装。
  发布后同步、读回、关闭失败不回滚、不重试，`published=true` 但 `committed=false`。
- 时间复核：`wall < kSafetyMinTimeMs` 拒绝；`kResumePending` 还要求墙上时间不早于残留的
  `last_start_ms`。维护结束后再次取时钟，不能在清理前签发结果。

`launcher_safety_maintenance_trial.cpp` 直接包含该实现，复用启动试验的内存文件系统、
锁与时钟替身执行 **74 个场景**，覆盖四种操作的成功路径，
授权方缺失/拒绝、PID/UID/操作不符、签发在未来、租期超长与授权过期等拒绝，令牌零/负/上界/
相同/旧值/耗尽回绕、损坏与两列前缀残留、锁缺失与竞争、各阶段 I/O 失败、发布后不回滚、
发布前后授权过期、授权期间状态被换 inode 与新残留出现、维护与启动重入共用机会。
Python 单测每次重新交叉编译，另在临时副本注入 6 类危险变异
（略过人工授权、把两列前缀当完整残留、残留计数不核对、重置令牌允许复用、
维护完成后放开同进程启动、用另一令牌认领武装残留），要求它们均编译成功、
随后被实际执行的断言拦下。

```powershell
& .workbuddy/venv-launcher-count/Scripts/python.exe -m unittest discover -s tools `
  -p 'test_launcher_safety_maintenance.py' -v
```

这些场景不运行真实文件系统、掉电、Binder、SELinux 或并发调度器；实机入口另行复核
Binder 调用方的 PID、UID、操作和租期，但不把离线授权替身当作 Binder 来源认证。
`maintenance_complete()` 只是事务结果，不是安装令牌，更不是
`防砖已确认`；阶段锁、`安装许可=false` 与未关闭门槛 `[1,3,5]` 均保持不变。
维护事务与完整链路验证器加入后，全量 Python 为 652 项（无跳过、失败或错误），
普通模式 Kotlin 单测仍为 869 项；维护代码与审计器变更不改变 Kotlin 计数。

### 恢复授权来源隔离（2026-10-14，受控恢复已接线）

本轮代码取证发现 `LauncherSnapshotEndpoint.install` 曾读取
`settings.safeModeReset("system_server")`，再截到 `Int.MAX_VALUE` 发送给桌面。
`HookSettings.safeModeReset` 还会合并通用框架恢复键；
`SafeModeSettings.setDisabled` 在恢复宿主时用 `System.currentTimeMillis()` 生成 `Long` 令牌。
这不是桌面专用授权：系统框架恢复可能变成桌面的新令牌，毫秒值截断后还会长期
饱和为同一个上界。原生 `safety_fuse_next` 在检查 `previous.disabled` 之前消费
严格较新的令牌，因此不能把这一来源接到未来写入路径。
当前入口仍保留持久化阻断与阶段锁；维护成功也不是已经发生实机解锁的证据。

当前已移除端点的恢复令牌缓存、读取与截断；普通 `buildLauncherSnapshot` 不再接收
恢复参数，回复第九个字固定为 `0`。十个 `int32` 字段、保留字为 `0` 的要求及底层熔断算法不变。
主开关只表示配置意愿，不能授权重建缺失/损坏状态、解除锁存或删除 `.pending`。
现有较大 `last_reset_token` 不迁移、不清零；本地已熔断的状态继续拒绝。
原生新增 `ordinary_snapshot_errors`，在底层 `snapshot_errors` 的形态/租期校验外，
用 `kSnapshotRecoveryUnsupported` 拒绝所有非零恢复令牌。只读预检、普通安全计算、
独立启动事务均采用此门禁；后端不再消费普通快照中的新令牌。
用途位单独归于 `OrdinarySnapshotBlocker`，不并入通道日志的七位 `SnapshotBlocker`；
既有门槛 3 日志合同及其审计判据保持不变。
底层 `safety_fuse_next` 的恢复算法及一致性基线不变，不能拿底层算法断言冒充恢复授权。

`LauncherSnapshotPolicyTest` 新增其他宿主/框架恢复键不读取不转发、主开关与
全部功能位组合不签发恢复、修订号/开关循环不生成令牌的回归。此前审批服务故障
阻断的执行已在 2026-10-14 补齐：12 个 Kotlin 用例全部通过，无跳过、失败或错误。
随后新增的原生用例先在旧实现上实际失败（入口 4 个、后端 5 个场景），再在新实现上通过。
入口共 75 个 ARM64 场景、后端共 85 个；两套试验均重新编译真实实现。
额外撤掉真实入口的用途门禁，要求其编译成功、再被执行断言拒绝；
加上后端的 5 类变异，避免只测到一份同步修改的“期望值”。
这些结果不认证 Binder 应答来源，不运行真实文件系统、掉电或 Android。

当时最终验证：全量 Python 644 项通过，AST 核对 644 个测试方法且无重名；
原型模式 Kotlin 907 项、普通模式 869 项均通过，XML 无跳过、失败或错误。
两种 release 构建与打包审计通过；原型包含持久化与维护事务的写入导入，
默认 APK 输出仍为普通包；本轮已安装原型包用于日志验收。
防砖审计成功运行、66 条编译期断言通过，但 `防砖已确认=false`、
`安装许可=false`，唯一聚合缺口仍为原生入口安全门禁完整接线。

受控武装与残留恢复已有离线事务实现（见下一节）：专用授权依赖接口、
有限宽度令牌的耗尽拒绝、canonical 残留判据与持稳定锁的事务。
但它仍缺跨重启防回放证据与实机并发验收；设置页请求只是受控维护的用户触发入口，
不是安装授权，也不能解除阶段锁。
不得把主开关或普通配置回复当作授权，
也不得通过删除 `.pending`、替换锁 inode 或清零计数获取假绿。
本轮把后端与维护事务链入原型 APK，但不关闭门槛 1/3/5。

### 防砖一致性的离线只读审计（2026-10-14 新增）

#### 2026-10-14 完整链路验证器（前一轮新增）

此前审计器声明自己“尚无完整链路验证器”，只能把调用与早退当语法线索。
本轮实现并接入了该验证器，它不改任何生产行为，只把接线状态变成可失败的只读断言：

1. `function_spans` / `reachable_functions` 以 `native_init`、`JNI_OnLoad` 为起点做本文件函数
   可达性搜索（只统计本文件定义的函数，不会借用同名变量）。
2. 在可达函数体内要求七项同时成立：`entry_reachable`、`snapshot_gated`（快照用途校验在提交之前）、
   `commit_result_controlled`（提交结果进入早退控制流）、`install_dominated`（安装许可证据来自提交结果）、
   `failure_blocks_install`、`install_path_present`（真正调用已保存的 hook 指针）、
   `install_path_exclusive`（全文仅一处 hook 调用点，不得另开绕过分支）。
3. 合同声明的 `nativeWiring.wired` 必须与实测一致，否则硬失败。新增五类反向固件
   （缺门禁、缺提交、缺支配、缺安装四类缺环节变体，以及全文另开第二处
   hook 调用点这一类绕过诱饵）与三类顺序诱饵（快照门禁放在提交之后、
   安装许可计算在提交之前、提交失败不早退）各自必须被单独拒绝，并新增两个正向固件：
   一个证明完整接线会翻绿，一个证明真实仓库形状下整体审计可以确认。
   审计单测由 82 项增至 88 项（含五类反向固件、三类顺序诱饵与两个正向固件）。
4. 实测结果：真实仓库七项全部为假，即原生入口仍未接入持久化提交与安装路径，
   因此缺口如实保留。

#### 2026-10-14 完整链路验证器首轮加固（本轮第一次）

上一版验证器存在**假绿路径**：只按「出现位置先后」配对，以下写法都能骗过它。
本轮逐条复现并全部拒绝，且都补成回归固件：

1. 安装调用被挪到「安装许可早退」**之前**（未受许可支配）；
2. 快照门禁调用**丢弃返回值**，或误用底层 `snapshot_errors`（不拒绝恢复用途）；
3. 安装许可证据**自填 `true`**，只留一处悬空 `safety_evidence(result)` 字样；
4. 安装调用落在 `if (0)`、`while (0)` 恒假分支；
5. 安装调用落在无条件 `return` / `goto` **之后**（永远执行不到）；
6. 安装许可早退条件被 `|| true` 短路成恒真、提交守卫被 `&& false` 短路成恒假；
7. **先取 `hook_func` 别名再调用**，绕过「全文仅一处直接调用点」的唯一性判据；
8. `PermitEvidence` 的**安全证据字段错位**（第 4 字段必须是 `safety_evidence(...)`）。

加固后的判据：门禁必须是**用途**校验且在提交之前；提交守卫、安装许可早退必须
出现在**真分支**且条件非常量；安装调用必须严格位于安装许可早退之后、且不在
恒假分支或无条件跳转之后；安装点全文唯一（含别名）；证据第 4 字段引用提交结果。
审计单测由 88 项增至 **92 项**，全量 Python 由 653 项增至 **657 项**。

#### 2026-10-14 完整链路验证器二次加固

首轮加固后继续对抗性探测，又发现 6 条假绿路径，同样逐条复现、逐条拒绝并补回归：

9. 快照门禁**极性反转**（`== 0` 就早退）或被 `0 &&` 短路成恒假；
10. 快照门禁校验的是**另一个**快照对象（与即将提交的不是同一个）；
11. 提交守卫**极性反转**（提交成功反而早退）或被 `true ||` 短路成恒真；
12. 提交失败的早退写成**空真分支**或从 `else` 继续（`if (!committed) {} else return;`）；
13. 安装调用写进「**许可失败**」的真分支（位置在许可之后，却属于错误分支）；
14. 安装调用经 `auto&` 引用或 `typedef` 函数指针**别名**后调用。

加固后判据：门禁必须是**用途**校验、校验**同一对象**、极性为「非零即早退」且
条件非常量；提交失败必须在**真分支**里返回；安装许可判定必须进入真分支早退；
安装调用严格在其后、不在恒假分支/跳转之后、也不落在许可判定那条语句内部；
安装点全文唯一（含 `auto&`、typedef 别名）。审计单测由 92 项增至 **95 项**，
全量 Python 由 657 项增至 **660 项**。

#### 2026-10-14 完整链路验证器三次加固（本轮再增）

继续探测又发现 1 条：**提交调用本身落在恒假分支里**（`if (0) {
commit_safety_startup_once(...); ... }`），或整条链路写在**不可达函数**里而导出入口
什么都不做。旧实现只检查「提交字样是否出现在可达函数体内」，不检查提交那一行是否
真的可达。加固后统一用可达性判定：提交、门禁、安装调用落在恒假分支或无条件跳转之后
一律不算。审计单测由 95 项增至 **96 项**，全量 Python 由 660 项增至 **661 项**。

#### 2026-10-14 完整链路验证器四次加固

继续对抗性探测，又发现 **3 条假绿**与 **1 条假红**（误判），全部修正：

16. 安装调用只在**未被调用的 lambda** 里（`auto f = [&]{ hook_func(...); };` 后不调用）；
17. 快照门禁唯一一次出现在 `if (0)` 恒假分支里；
18. 安装许可早退唯一一次出现在 `if (0)` 恒假分支里。
- **假红修正**：提交结果用**显式类型**声明（`const launcher::SafetyStartupResult result = ...`）
  此前不被识别，会把真实接线误判为未接线。现按位与指定初始化、`auto` 与显式类型均认。

可达性判定现覆盖：提交调用、快照门禁、安装许可早退、安装调用四处位置；不落
恒假分支、无条件跳转之后、未被调用的 lambda 体内。

#### 2026-10-15 完整链路验证器六次加固

继续对抗性探测上一轮刚加固完的验证器，发现它仍存在**系统性假绿**：旧实现只在
函数体内找「调用点是否出现在字面量 `if (0)` / `while (0)` 分支、或在无条件
`return`/`goto` 之后」，而**调用点自身是否可达、条件是否恒假**根本没判。
于是下面这些写法都能把 `wired` 判成 `true`，本轮全部修掉并各自补成回归固件：

19. 整条链路（提交、门禁、许可、安装）被 `if constexpr (false)` 包住；
20. 安装调用被 `if constexpr (false)` / `for (; false;)` / `while (false)` 包住；
21. 安装调用落在 `if (true) { return; } else { ... }` 的 else 分支里；
22. 安装调用落在常量 `switch` 的不匹配分支里（`switch (1) { case 2: ... }`）；
23. 恒假分支条件来自宏定义（`#define NEVER 0` 后写 `if (NEVER)`）；
24. 安装调用落在**无 `break`** 的 `while (true) { }` 之后；
25. 安装调用落在**嵌套块里的无条件 `return`** 之后；
26. 导出入口本身只在 `if (0)` / `if constexpr (false)` 里调用整条链路；
27. 整条链路只经「被恒假条件调用的中间函数」间接到达（调用图搜索不再跟随死调用点）。

修法是把「字面量嗅探」换成 **三值常量求值 + 语句级可达性分析**：
`constant_value` 认整数、`true`/`false`、`#define 名字 数字` 宏、`!`、比较、
`&&`/`||`（含 `x || true`、`x && false` 这类短路常量），`dead_ranges` 顺着
`if`/`while`/`for`/`do`/`switch`/块/跳转/标签遍历，标出永远执行不到的区间；
`reachable_functions` 只跟随**调用点自身可达**的调用。

同时修正本轮对抗探测新暴露的 **4 条假红/缺陷**（把真实接线误判或直接报错）：

- `if constexpr (false) {` 会被函数定义正则误当成「名为 `constexpr` 的函数」，
  让调用图顺着一具假函数把死分支里的调用标成可达；已把 `constexpr` 计入关键字。
- `constant_value` 曾把结果强制成 0/1，导致 `switch (1) { case 2: ... }` 的
  不匹配分支被误判成匹配；现保留真实常量值。
- `do { ... } while (false)` 只是把语句包成块（块体必然执行一次），旧实现把它
  当恒假分支，会把整段接线判成不可达；现已按普通块处理。
- 旧 `install_inside` 把「门禁与安装同处一个顶层语句」都算错误分支，
  `do { 许可判定; hook(); } while (false)` 这类正常写法会被误杀；现只针对
  许可判定那条 `if` 的**真分支**（含无花括号单语句分支）。
- 可达 `goto` 的目标标签会让其后代码恢复可达（两趟分析：只有本身可达的 `goto`
  才算数），避免把 `goto install; install: hook();` 的真实接线误杀。

加固后实测：防砖审计单测由 **100 项增至 104 项**，全量 Python 由 **665 项增至
669 项**（均 OK）；真实仓库仍 `审计器已成功运行=true`、`防砖已确认=false`、
`安装许可=false`、未关闭门槛 `[1,3,5]`，唯一缺口仍是「原生入口安全门禁接线」，
七项链路全为假。`native_entry.cpp` 未改，未连接设备、未安装、未提交、未推送。

**本轮仍保留的限制（如实记录，不靠猜测补齐）**：条件编译只删除能静态判定的
`#if 0` / `#if false` 分支；`#ifdef X` / `#ifndef X` 按「可能编译」处理，因为审计器
没有解析构建系统的宏定义集，把 `hook_func` 调用写进未定义宏的 `#ifdef` 里仍会被
算作一处可达安装点。常量求值是表达式级近似，无法求值的条件一律按「可达」处理
（不漏放的保守一侧，不等于证明分支真的会执行）。

#### 2026-10-15 完整链路验证器七次加固

继续对抗性探测第六轮刚加固完的验证器，发现**早退语句本身的可达性与语义**仍未判：
旧实现只要求「真分支文本里出现过 `return`」，于是下面 9 条假绿路径全部成立，
本轮逐条修掉并各自补成回归固件：

28. 门禁早退写在恒假分支里：`if (errors(...) != 0) { if (0) return 1; }`；
29. 提交失败早退写在恒假分支里：`if (!committed) { if (0) { return 2; } }`；
30. 安装许可早退写在恒假分支里（同上形态）；
31. 早退被运行期条件削弱：`if (!committed) { if (revision > 0) return 2; }`，
    提交失败时可能不早退，等于放行；
32. 裸 `break` 当早退（不在任何循环 / `switch` 里，不会离开安装路径）；
33. `goto` 回跳到安装**之前**的标签（没有跳过安装）；
34. 安装许可判定被运行期条件包住：`if (revision > 0) { if (!permit(...)) return 3; }`；
35. 快照门禁被运行期条件包住（同上形态）；
36. 提交结果守卫被运行期条件包住（同上形态）。

修法是引入 `live_abort_in_consequent`：要求早退**自身可达**、真分支**必然终止**
（`_block_terminates` 逐条扫描，`return` 之后的死代码不再干扰判断），且跳转真的
跳过安装——`return`/`throw` 直接成立；`break` 必须处在一个同时包含安装调用的
循环 / `switch` 里（`enclosing_containers`）；`goto` 的目标标签必须在安装调用
**之后**（`goto_target_position`）。同时新增 `dominates`（锚点是否无条件先于目标
执行）与 `_control_regions`：条件分支、有界循环、`switch` 段落里的锚点不支配
目标；常量条件选中的分支、无 `break` 的恒真循环、`do` 块体算必然执行。门禁、
提交守卫、许可判定三处的「必须支配其后环节」都改用该判据。

本轮同时修正 5 条假红（把真实接线误杀）：

- `while (true) { return; }` 这类必然终止的早退；
- 早退块 `{ if (1) { return 2; } }` 里常量 `if` 的终止性；
- 早退块 `{ return 2; (void)x; }` 里 `return` 之后的死代码；
- `do { ... } while (false)` 里用 `break` 跳过安装；
- `goto` 跳到安装调用**之后**的标签。

实测：防砖审计单测由 **104 项增至 105 项**，全量 Python 由 **669 项增至 670 项**
（均 OK）。真实仓库仍 `审计器已成功运行=true`、`防砖已确认=false`、
`安装许可=false`、未关闭门槛 `[1,3,5]`，唯一缺口仍是「原生入口安全门禁接线」。

#### 2026-10-15 完整链路验证器八次加固

第七轮之后继续探测，发现验证器仍有两类系统性漏洞，共 9 条假绿路径，全部修掉并
补回归固件：

37. **安装点不保证必然执行**：把安装调用包进运行期 `if (revision > 0) { hook(); }`
    或有界循环 `while (revision > 0) { hook(); break; }`，都存在一条不安装也能走
    到底的路径；旧实现只判「可达 + 在许可之后」，这类写法会翻绿。现要求安装点
    同时满足 `unconditionally_reached`。
38. 提交结果在守卫**之前**被改写：`auto result = commit...; result.committed = true;`
    之后守卫读到的不是提交的真实结果，等于把「提交失败放行」伪装成有守卫。
    新增 `result_overwritten`，只认写入形态（成员赋值、整体赋值、`reset()`/
    `clear()`/`assign()`），不把 `result.safety_ready()` 这类只读调用当写入。
39. 提交守卫被**额外合取条件削弱**：`if (!committed && revision > 0) return 2;`；
40. 提交守卫**双重取反**：`if (!(!committed)) return 2;`；
41. 快照门禁被额外合取条件削弱：`if (errors(...) != 0 && revision > 0) return 1;`；
42. 快照门禁双重取反：`if (!(errors(...) != 0)) return 1;`；
43. 快照门禁夹带**其他对象**的用途校验：`if (errors(snapshot, ...) != 0 || errors(other, ...) != 0)`，
    旧实现只看第一个实参就判绿；
44. 安装许可判定被额外合取条件削弱：`if (!permit(...) && !trusted) return 3;`；
45. 提交调用本身被运行期条件包住（`if (revision > 0) { result = commit(...); }`）。

修法是把三处守卫的**纯文本极性嗅探**换成符号化**蕴含判定**
（`condition_implies_unsafe`）：把不安全谓语替换成布尔符号 `UNSAFE`
（`xxx_errors(...)` 非零才不安全；`install_permitted(...)` 返回 0 才不安全，
方向相反，用 `unsafe_when_zero` 区分），把与 `0`/`false`/`true` 的比较归一成
布尔取反，再对剩余未知原子**穷举真值表**：只要存在一种「不安全成立、条件却是假」
的赋值就不算早退。旧的两个正则（`GATE_NEGATED`/`GATE_ZERO_EQUAL`）与
`gate_polarity_ok` 已失去调用方并删除。守卫与门禁的定位也改为遍历
`if_conditions`，不再依赖会漏掉括号 / 换行形态的单条正则。

本轮同时修正 5 条假红/缺陷：

- 安装调用写成 `(entries->hook_func)(...)` 的等价括号形态；
- 早退用 `throw` 离开安装路径（此前不被认作跳转）；
- 提交守卫写成括号包裹的取反 `!(result.committed)`；
- `_eval_bool` 遇到 `true`/`false`/整数字面量直接 `KeyError`（缺陷）；
- `switch` 的「常量条件下唯一会拿到控制权的那一段」此前一律按「不必然执行」，
  会把 `switch (1) { case 1: hook(); }` 这类真实接线误判；现按常量匹配 / default
  判定必然性。

固化后的固件区间为 `BA`–`CG`。实测：防砖审计单测由 **105 项增至 108 项**，
全量 Python 由 **670 项增至 673 项**（均 OK）；真实仓库仍
`审计器已成功运行=true`、`防砖已确认=false`、`安装许可=false`、未关闭门槛
`[1,3,5]`，七项链路全为假，唯一缺口仍是「原生入口安全门禁接线」。
`native_entry.cpp` 未改，未连接设备、未安装、未提交、未推送。

**七、八两轮仍保留的限制（如实记录，不靠猜测补齐）**：条件编译只删除能静态判定的
`#if 0` / `#if false` 分支；`#ifdef X` / `#ifndef X` 按「可能编译」处理，因为审计器
没有解析构建系统的宏定义集。蕴含判定最多穷举 8 个未知原子，表达式写得更复杂或
无法切分 token 时一律返回「不成立」（保守拒绝，宁可误杀不漏放）。`dominates` 与
`unconditionally_reached` 是语句级近似，不建模异常、并发、别名的副作用；
`result_overwritten` 只看词法形态，名字被换绑到别的对象不再追踪。

#### 2026-10-15 完整链路验证器九次加固（本轮最新）

八轮之后继续对抗性探测，发现 `jumped_over` 的**扫描范围本身写窄了**，于是
「锚点之前的 `goto`」能整条绕过后面的环节，共 4 条假绿路径，全部修掉并各自
补成回归固件：

46. 快照门禁**之前**一条运行期 `goto` 直接落到安装点：门禁、提交、提交守卫、
    安装许可四环全被绕过（`if (revision > 0) goto install_now;`）；
47. 门禁之后、提交**之前**的运行期 `goto` 落到安装点：跳过提交、守卫与许可；
48. 提交守卫**之前**的运行期 `goto` 跳过守卫：提交失败也能走到安装；
49. 安装许可判定**之前**的运行期 `goto` 跳过许可：未许可也能走到安装。

旧实现只扫描 `[anchor_at, target_at)` 区间、且要求 `goto` 目标 `>= target_at`，
所以「跳转点在锚点之前、目标在锚点之后」这条路径从来没被枚举。现改为扫描
**整个函数体**：只要 `goto` 自身可达、出现在锚点之前，而目标标签落在锚点之后
且不超过目标点，就判定「存在一条不经过锚点也能到达目标代码的路径」，`dominates`
随即为假。跳转点本身在锚点之后时仍然豁免——执行到它必然先经过锚点，属于
「锚点先执行、再按结果跳过安装」的合法早退（`if (!permit) goto denied;`）。

固化后的固件区间由 `BA`–`CG` 扩到 **`BA`–`CK`**。实测：防砖审计单测由
**108 项增至 109 项**，全量 Python 由 **673 项增至 674 项**
（均 OK）；真实仓库仍 `审计器已成功运行=true`、`防砖已确认=false`、
`安装许可=false`、未关闭门槛 `[1,3,5]`，七项链路全为假，唯一缺口仍是
「原生入口安全门禁接线」。`native_entry.cpp` 未改，未连接设备、未安装、
未提交、未推送。

**本轮同时说明一件事**：审计器文件曾被外部脚本破坏（`if_consequent` 定义行被
整行替换、尾部被 `src[start:-1]` 截断），本轮已按字节比对恢复并通过全部回归；
九次加固中的判据语义只做上述一处收窄修正，其余为原样复原，不构成「本来就通过」。

**本轮保留的限制（如实记录，不靠猜测补齐）**：快照门禁只要求校验**同一个快照
对象**，不比对修订号实参，因此 `ordinary_snapshot_errors(snapshot, revision - 1, 0)`
这类「对象对、修订号错」的写法仍会被判绿——判据的既定范围是用途校验与对象一致性，
不是逐实参等价；`dominates` 仍是语句级近似，不建模异常、并发与别名副作用；
`install_path_exclusive` 只统计直接调用点与「先取别名再调用」，不追踪指针的二次
转存。

#### 2026-10-14 完整链路验证器五次加固

又修正 **1 条假红**：快照门禁的等价 truthy 写法 `if (ordinary_snapshot_errors(...))`
与 `!= 0U` 此前不被识别，会把真实接线误判为未接线。现接受 truthy 与 `!= 0` 两种
等价极性，仍拒绝 `== 0`（极性反转）与 `!errors(...)`（取反）。

审计单测由 99 项增至 **100 项**，全量 Python 由 664 项增至 **665 项**。

五轮加固累计：**18 条假绿路径**全部闭合、**2 条假红**（误判）修正；反向固件与
正向固件并存，保证验证器既不漏放也不误杀。（第六轮加固见本节末尾的小节。）

**边界不变**：这仍是静态结构/text 绊线，不是编译器或运行期证明；它只保证
「源码里存在一条形态与顺序都成立的接线」，不证明真实时序、SELinux、Binder 来源或
掉电行为。当前真实源码仍未接线，故 `wired=false`、`防砖已确认=false`。

#### 2026-10-14 本轮审计器修复

- 关键期望锚定独立只读基线，合同不能自行定义通过标准。
- 审计工具内嵌基线指纹：常量表与文件指针表 `HOST` 都纳入摘要，并额外断言
  `SOURCES is baseline.HOST`、`HOST` 键集合/顺序、路径须为仓库内相对路径且文件存在。
  只改合同、只改基线常量、只改 `HOST` 指针（把被审计对象换成伪造文件）、或
  合同+源码+基线三者同改都会硬失败。该指纹是仓库内明文常量，属「一致性绊线」而非
  防篡改机制，不能当作不可伪造证据。
- 原生接线由**完整链路验证器**核对：以导出入口（`native_init`/JNI）为起点
  做本文件函数可达性搜索，在可达函数体内逐项要求快照**用途**校验在提交之前、
  提交结果进入早退控制流、安装许可证据来自提交结果、失败时返回、
  安装许可早退本身进入真分支、安装调用严格在其后、真正调用已保存的 hook 指针、
  以及全文仅一处 hook 调用点（别名调用另算）。孤立函数、字符串诱饵、只 include、
  不进控制流的调用、丢弃返回值的门禁、恒假分支/跳转后的安装、恒定条件短路
  以及合同/基线同改都不能翻绿；详见下方各节的假绿加固（累计 49 条假绿 + 16 条假红/缺陷修正）。
- 逃生门测试去除注释，按**测试方法体**聚合，要求同一方法体内既有属性字面量断言、
  又有对真实 API 的带期望值断言；注释、单字符串、只调用不给期望值、两条证据分散在
  不同方法/文件里都不算。证据来源包含 `tools/test_*.py` 与 Kotlin 测试目录。
- README 只审计“防砖→逃生门”章节内、且落在对应属性名近邻的恢复步骤。
- 熔断使用有序结构和函数体摘要校验，不按出现次数放行。

真实仓库仍为 `防砖已确认=false`。此前把四个缺口收敛为**一个**（原生入口完整接线），
并修正一条本身写错的判据；当时审计单测为 80 项、全量 Python 为 631 项。
初次新增只读预检与反假绿回归时入口试验为 70 个场景；恢复用途隔离后增至 75 个，
且纳入全量 Python 的重新编译执行。

#### 2026-10-14 缺口收敛：关闭两项、修正一项判据

**① 作用域判据修正（原判据写错，不是仓库写错）。** 旧判据要求原生
`kLauncherSafetyScope` 出现在**主模块** `ScopeCatalog.kt` 的 `SCOPES` 进程名里。
这是错的：主模块 `SCOPES` 是**界面侧「可重启宿主」清单**，桌面进程
（`com.miui.home`）本来就不在其中。真正声明桌面注入作用域的是
`app/src/launcherNative/resources/META-INF/xposed/scope.list`（现为恰好两项、顺序固定：
桌面 `com.miui.home` 在前、system_server `system` 在后）与桌面的 Kotlin 身份契约
`LauncherHostContract.PACKAGE = "com.miui.home"`。新判据
（`verify_scope_consistency`）要求原生常量**同时等于**桌面项与身份契约，`scope.list`
必须逐项且按顺序等于基线的两项清单，多项、缺项、换序或空行干扰均报缺口。
修正后该项通过，`safety_gate.h` 的注释已指向这两个真实来源。
该常量现已被只读预检用于打开包私有目录，但这不等于完整接线验收。

**② 逃生门测试引用（原缺口 2）已关闭。** 新增
`app/src/test/java/.../hook/EscapeGateTest.kt`，4 个 JVM 单测反射逐字断言
`KillSwitch.PROPERTY_NAME` / `FLAG_FILES`、`BootLoopGuard.STATE_FILE` /
`DISABLED_PROPERTY`，并断言读不到属性时 `isEngaged()`/`reportOnce()` 返回 `true`、
`readDisabledProperty()` 返回 `null`（fail-closed），以及两条属性互不相同、互不为前缀。
刻意不调用 `shouldSkipSystemServerHooks` / `shouldSkipSystemUiHooks`，避免单测创建
`/data/system` 之类的目录。实测 `tests="4" skipped="0" failures="0"`。

**③ README 逃生属性覆盖（原缺口 4）已关闭。** `README.md`「防砖 → 逃生门」补写
`persist.sys.hyperextend.framework_disabled` 设回 `0`、删除
`/data/system/hyperextend_bootguard` 的恢复步骤，并写明它与
`persist.sys.hyperextend.disabled` 是两个不同标记、各管一侧。

**④ 仍保留的唯一验收缺口（原缺口 1）。** `launcher/native_entry.cpp`
已经接入启动状态提交和受其结果支配的数量安装结构；合同 `nativeWiring.wired=true`，
但 `防砖已确认` 仍为 `false`，不能把静态接线当成实机验收或缺口关闭。

上面「作用域标识与注入作用域一致」「与 `BootFuseState`/`BootFusePolicy.next`
同构」原先只是注释里的承诺，没有任何机器断言。`tools/launcher_safety_parity_audit.py`
（合同 `launcher-safety-parity-contract.json`）把它们变成可失败的只读检查，并**区分
「审计器成功运行」与「防砖已确认」**：全部比对通过只说明结论可信，不等于防砖成立。

已核验通过的部分：两侧熔断窗口/最小可信时间三个来源逐项相等；状态行五字段顺序
一致；熔断判定式按有序结构与三个函数体摘要校验通过；原生解码器与 13 个边界用例由真实 NDK clang
编译期求值（66 条断言）；逃生门属性/文件与 fail-closed 读取；阶段锁仍保留
安全与后端待验收位。缺口收敛后，当前实测 `防砖已确认=false`，只剩一个如实缺口：

- **原生入口实机验收未完成**：完整链路验证器的
  `entry_reachable`、`snapshot_gated`、`commit_result_controlled`、`install_dominated`、
  `install_plan_gated`、`failure_blocks_install`、`install_path_present`、
  `install_path_exclusive` 八项均为真；但安全与后端阶段锁仍保留，尚无真实桌面代码页
  写入、并发窗口和掉电一致性的验收证据（合同 `nativeWiring.wired=true`）。

原「逃生门无测试引用」已由 `EscapeGateTest.kt` 关闭、「README 逃生属性覆盖不足」
已由 README 补写关闭；原「作用域令牌对不上 `SCOPES`」是判据写错，已改为按
launcherNative 身份契约与 `scope.list` 核对并通过（该常量已有只读预检调用点）。

```powershell
& .workbuddy/venv-launcher-count/Scripts/python.exe tools/launcher_safety_parity_audit.py
```

只读：不执行 Kotlin/JUnit、不执行原生判定、不运行 Android、不接触设备、不安装；
不覆盖实机运行期时序、共享首选项损坏、属性读写权限或真实开机循环。`installPermitted=false`
与未关闭门槛 `[1,3,5]` 全部不变，本工具不为任何门槛销项。

### 待验收阶段锁（2026-10-13 补充）

`kPendingAcceptanceBlockers` 无条件保留安全与后端阻断位。即使调用方把
`safety_verified`、`backend_verified` 都传为 `true`，也不能放行；纯函数测试
通过不等于实机验收。编译期断言枚举宿主、版本与三个布尔值的 200 种组合，
并检查最容易误放行的“四项全真”输入。

本地参考 Dobby 的 `DobbyCodePatch` 是保持页面可执行的 `mprotect + memcpy`，
没有与目标执行线程协作的协议。参考 LSPosed 的 Dobby 子模块提交与本地参考
Dobby 提交也不同，因此只记录已读源码的事实，不将其升级为设备后端一致性证明。

HyperChanger 的文件夹/材质路径提供了另一种参考：先准备永久近距代码页，再原子
写入单条 `B`，保留原计算。但普通指令到 `B` 的替换不能仅凭 32 位原子写入与
刷缓存就认定可安全并发执行；需要独立核验 ARM64 指令修改规则、执行线程同步
与映射生命周期。当前没有因此变更 24 字节合同、链入蹦床或接入安装路径。

### 留存框架样本的补丁反例（2026-10-13）

`launcher-backend-sample-contract.json` 与 `tools/launcher_backend_audit.py`
固定了留存框架/系统库的完整摘要、Build ID、指令范围和 ELF 指针槽。
样本补丁例程 `0xa5324` 在开放 RWX 后复制窗口，再恢复 RX、做缓存维护；
它没有检查 mprotect 的返回值。该地址是被审计样本的相对地址，不是运行期挂载地址。

离线执行真实框架和 libc 指令，三条常规复制路径都在两条存储之间出现
“新 LDR/BR 头部、旧 8 字节字面量”。4K/16K、同页/跨页和两类权限失败
共 36 个场景复现；恢复 RX 失败仍返回 0，不能把返回成功当作安全证据。

测试使用受控页大小/权限替身，缓存指令只记事件，不模拟硬件取指、MOPS、
完整框架初始化或上层执行互斥，也未重新证明样本与当前设备映射一致。
**这不是门槛 5 通过**；不提供代码页写入、停止线程或安装接口。

同一审计还会按函数边界扫描模块 hook 包装、模块回填包装、Dobby 局部 backup
包装、Dobby 控制流、routing 构造与提交、补丁例程与复制/缓存适配：整条链只有
`sysconf`、`mprotect`、`__memcpy_chk` 三个导入，没有锁、线程创建/挂起、
条件变量、睡眠或 `syscall`。它证明后端自身不与正在执行目标函数的线程协作，
因此门槛 5 只能靠安装窗口无并发执行的证据，或换用不含覆盖写窗口的发布协议。

```powershell
python tools/launcher_backend_audit.py --framework $env:LAUNCHER_FRAMEWORK `
  --libc $env:LAUNCHER_LIBC --host-elf $env:LAUNCHER_LIBAPP --emulate
```

执行 `--emulate` 需要已有的 `requirements-launcher-emulation.txt` 环境。
不带该开关只读校验合同，任何模式都不加载目标库到本机进程或设备。
