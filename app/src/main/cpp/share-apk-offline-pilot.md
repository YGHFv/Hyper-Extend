# 分享 APK 的最小离线行为试点

本试点只读本机已留存的宿主 ELF，在模拟器内比较两种调用方式；没有新增分享功能、
安装路径、文件服务或 Android 调用。`install_permitted=false`，门槛 1、3、5 不关闭。
不改 `isValid` 可见性链，不以单字调用点候选替换既有 24 字节入口候选。

## 证据对象

- 宿主身份沿用 `launcher-share-apk-pilot-contract.json` 的完整文件摘要、Build ID 和大小。
- 真实执行 `ValidShareAppShortcutMenu.shareApk`：相对地址 `0x17fda70`，188 字节，
  SHA256 为 `a6e69442573201ce3bdc5079701944eb0b3ea0e7f48deb861f5ecc7144ebd1a1`。
- 调用点为 `0x17fd75c`、`0x17fda5c`；各自在“调用点 + 4”停止，不执行调用者余下代码。
  接续指令身份与旧返回值合同交叉校验；两条调用者都丢弃该返回值。
- 直接执行 `BL 原目标`，对照 `BL 16字节叶蹦床 -> BR 原目标`。调用点改字、蹦床重定位
  和所有内存写入都只发生在 Unicorn 虚拟内存中，不改宿主文件、不写用户设备。
- **基础函数体试验的外部调用、对象池和对象句柄仍为替身**。下文对象边界试验进一步
  执行 Bundle 包装器和 Intent 分配快路径，但不执行完整运行时或真实分享链路。

工具 `tools/launcher_share_apk_flow_audit.py` 与合同
`app/src/test/resources/launcher-share-apk-flow-contract.json` 固定上述范围。
入口、返回值、叶蹦床三份旧合同必须同意宿主/目标/调用点；全部八条 `BL` 必须与真实
函数体一致，六条已命名调用还要核对符号表。不能删除符号名以绕过核验。

## 已复核的数据流

以下 `入口 x1/x2`、`x22`、池槽值均为测试装置提供的数值；不据此猜测字符串内容。
`makeBundle`、`makeRecord` 是模型角色名，不是目标地址的 Dart 符号名；
它们与命名 API 和 Intent 类型的对应关系见“对象边界补充取证”。

| 真实调用指令 | 目标 | 检查的边界参数或结果 |
| --- | --- | --- |
| `0x17fda8c` | `0x9a2c58`，未命名 | `x0=入口x1`、`x1=x22`、`x3=入口x2`；替身返回 bundle 句柄 |
| `0x17fdaa4` | `BundleImpl.putString` | `x1=bundle`、`x2=池槽+0xb9458` 的值、`x3=入口x2` |
| `0x17fdab8` | `BundleImpl.putString` | 值仍是入口 x2，键改为池槽 `+0xb9460` 的值 |
| `0x17fdabc` | `getFrbAppInterface` | 替身返回非空、null 或低 32 位与 null 相同的数值 |
| `0x17fdad0` | `AppInterfaceRefImpl.context` | `x1=接口句柄`、`x2=bundle` |
| `0x17fdad8` | `ContextImpl.activityManager` | `x1=context` |
| `0x17fdae0` | `0x18c00d8`，未命名 | 替身返回 record 句柄 |
| `0x17fdb18` | `ActivityManagerImpl.startActivity` | `x1=manager`、`x2=record`；记录对象已按表写好 |

`0x17fdac0` 是 `cmp w0,w22`，后面的 `b.eq` 跳至返回段 `0x17fdb1c`。
因此空接口路径**已经执行前四次外部调用**，不是无副作用地提前返回；
只跳过后四次调用和记录对象写入。
`low32-alias` 专门验证 32 位比较：只改高 32 位仍走空分支。
这不是“可以构造这样一个合法 Dart 对象”的证明。

非空路径对 tagged record 句柄执行四次真实存储：

| 指令地址 | 相对句柄偏移 | 宽度 | 模型值 |
| --- | --- | --- | --- |
| `0x17fdaf0` | `+7` | 4 字节 | 池槽 `+0xb9468` 的低 32 位 |
| `0x17fdaf8` | `+15` | 8 字节 | 整数位模式 `1` |
| `0x17fdb00` | `+27` | 4 字节 | bundle 句柄低 32 位 |
| `0x17fdb0c` | `+39` | 4 字节 | 池槽 `+0xb9470` 的低 32 位 |

只证明偏移、宽度、位模式与执行顺序，不给字段起未经确认的 action、MIME、URI
或 chooser 名称。`0x17fdb1c` 的 `mov x0,x22` 返回 Dart null，
`0x17fdb20..0x17fdb28` 恢复 Dart 栈、帧和链接寄存器后返回；C 栈不变。

## 模型、故障与停止条件

- 矩阵为 2 个调用点 × 3 个接口场景 × 2 个虚拟布局 × 3 组初始 NZCV × 2 种调用方式：
  **72 次执行、36 对逐项比较**。不声称覆盖任意参数、对象形态或线程时序。
- 替身故意破坏 `x0..x14`、`x16/x17`、`Q0..Q7` 和 NZCV，再填入模型返回值；
  防止“替身保留一切”掩盖真实帧槽恢复错误。其余保留集合只属本试验模型，
  不扩大为完整 Dart ABI 或所有外部函数的证明。
- 客体代码页只读可执行，池槽/原目标槽只读，Dart 栈/C 栈/对象页不可执行。
  写入钩子只允许本函数 32 字节 Dart 栈帧及表中的对象字段，字段邻接字节也必须不变。
- 正常完成必须到达原调用点接续，调用顺序、返回值、两栈、帧、寄存器和对象均符合模型。
  异常访存、离开代码范围、重复进入、超过 512 条指令预算均拒绝，不能被当成“跳过”。
- 每次命令执行 8 个错误机器码对照：错入参帧槽、重复池键、反转空分支、4 字节误写为
  8 字节、常数 1 改 2、Dart null 改零、尾跳改 BLR、调用点 BL 改 B。
  必须被同一核对器按预期原因拒绝；任意无关异常不能充当检出成功。
- 另在八个外部边界分别主动中止，确保不报告正常完成；这**不模拟 Dart 异常展开**，
  也**不证明失败后可以安全回退原函数**。

## 本机复现

依赖沿用 `tools/requirements-launcher-emulation.txt` 对应的已有环境。
以下只编译离线对象、读取已有 ELF；没有日志采集、设备访问或安装命令。

```powershell
# 环境变量只在本机提供，不把真实 SDK 路径或材料路径写入仓库。
New-Item -ItemType Directory -Force .workbuddy/tmp/share-apk-flow | Out-Null
& $env:NDK_CLANG --target=aarch64-linux-android35 -c `
  app/src/test/cpp/launcher_menu_island_arm64.S `
  -o .workbuddy/tmp/share-apk-flow/menu_island.o
& .workbuddy/venv-launcher-count/Scripts/python.exe tools/launcher_share_apk_flow_audit.py `
  --host-elf $env:LAUNCHER_LIBAPP --object .workbuddy/tmp/share-apk-flow/menu_island.o
& .workbuddy/venv-launcher-count/Scripts/python.exe -m unittest discover `
  -s tools -p 'test_launcher_share_apk_flow_audit.py'
```

49 项单测用稀疏合成映像与合成 ELF 对象，不需要本机宿主材料；CLI 正向测试替换
ELF 装载器并提供合成宿主身份的四份合同，仍运行合同核对及全部行为/故障矩阵。
真实宿主复核必须另跑上面的审计命令。
CMake 隔离检查只扫描当前 `CMakeLists.txt` 非整行注释里的叶蹦床源文件名；
不把这种文本检查夸大为完整构建图或产物隔离证明。

## 对象边界补充取证（2026-10-13）

新增 `tools/launcher_share_apk_object_audit.py`、
`app/src/test/resources/launcher-share-apk-object-contract.json` 和 42 项合成单测。
新试验沿用基础函数体的核对器，不修改旧矩阵或安装合同；真实宿主复核为
**144 次执行、72 对直接/叶蹦床比较、8 项拒绝对照通过**。
两个 API 状态（已就绪、需要迟初始化）分别覆盖原来的全部 72 次执行。

### 两个目标的身份

- `0x9a2c58` 的 108 字节包装器从线程字段表取得运行时接口，正常路径在
  `0x9a2c98` 调用 **`RustLibApiImpl.hyperApiBundleBundleNew`（`0x9a2cc4`）**。
  因而不再只把它当成任意“返回 bundle 句柄”的黑箱；该地址本身仍无命名符号，
  不给它虚构 `Bundle.new` 符号。
- `0x18c00d8` 的 12 字节桩把 **`0xd7831c`** 装入 x2，尾跳至 `0x1b57110`。
  `Intent.==` 的 `0x1798258/0x179825c` 从对象头提取类 ID 并比较 **`0xd78`**，
  与这个 tag 相符。命名 SSE 解码函数的 `0x8ccb88` 和 DCO 解码函数的
  `0x965320` 也调用同一分配桩，构成独立调用点交叉证据。
- 通用快路径从 tag 的位 8..11 取长度单位再乘 16，得到 **48 字节**；
  写对象头、初始化压缩 null、返回原始对象地址 +1。本次确认的是 Intent 对象分配，
  不是 chooser 已构造，也不等同于执行 Intent 的业务构造逻辑。

静态合同固定上述八段函数证据共 **2188 字节**。其中纳入模拟执行的新增代码范围是
108 字节 Bundle 包装器 +12 字节 Intent 分配桩 +76 字节通用分配路径，共 **196 字节**；
这不是逐指令覆盖率，异常构造边界之后的包装器指令没有执行。
仍执行原来的 188 字节 shareApk。`hyperApiBundleBundleNew`、迟初始化服务和其它外部
调用保留替身，编解码函数与 `Intent.==` 只作静态证据，不能混报为已执行。

### 八字段的编解码类型

`RustLibApiImpl.sse_encode_intent`（`0x8cbf64`）与 232 字节
`RustLibApiImpl.sse_decode_intent`（`0x8ccaf0`）的字段访问、完整 BL 集合及目标符号
逐项核对。同名的 40 字节解码包装器位于 `0xee6970`，不能用最近符号或仅凭名字混同。

| 序号 | tagged 句柄偏移 | 内存宽度 | 命名编解码边界给出的类型 | shareApk 写入 |
| --- | --- | --- | --- | --- |
| 0 | `+7` | 4 | 可空 String | 池槽 `+0xb9468` 的值 |
| 1 | `+11` | 4 | 可空 RustOpaque Uri | 保持 null |
| 2 | `+15` | 8 | `WriteBuffer.putInt32` / `ReadBuffer.getInt32` | 位模式 1 |
| 3 | `+23` | 4 | 可空 String 列表 | 保持 null |
| 4 | `+27` | 4 | 可空 RustOpaque Bundle | bundle 句柄 |
| 5 | `+31` | 4 | 可空 component_name | 保持 null |
| 6 | `+35` | 4 | 可空 String | 保持 null |
| 7 | `+39` | 4 | 可空 String | 池槽 `+0xb9470` 的值 |

**内存中的 8 字节整数槽不等于 Int64 编解码边界。** 这里按宿主命名目标取证，
不由存储宽度猜线格式，更不直接将这个槽命名为 flags。
本节使用的旧对象边界模型没有解码池内容，所以
`launcher-share-apk-object-contract.json` 仍要求 `poolContentsResolved=false`、所有
`semanticName=null`；不能把后来取得的静态快照证据倒灌成该替身模型已经执行真实池对象。
独立的新快照审计已解析四槽，见“快照对象池顺序解码”一节。

### 分配与拒绝边界

新模拟器为线程字段、根对象、压缩指针高位及堆 top/end 提供合成环境：

- Bundle 的就绪和迟初始化分支均执行真实包装器指令；迟初始化替身只提供结果，
  不证明真实初始化、持久化、线程互斥或异常处理。
- 正常分配核对 top 更新、tag 和每次初始化写入；未被 shareApk 改写的四个字段必须
  保持压缩 null，不能靠上一轮对象内存中的 `0xa5` 填充值继续通过。
- `0x1b5713c` 的三次 STP 从原始对象 `+8` 初始化到 `+56`，比新的 top（`+48`）
  多写 8 字节。本模型明确预留并核对这段区域；这是该样本的可观察指令行为，
  **不是实机越界漏洞结论，也不是允许自制分配器只预留 48 字节**。
- 堆 end 恰好等于或小于 `top+48` 时，`0x1b57124` 的 `b.ls` 进入慢路径。
  工具在合成慢路径目标处拒绝成功判定，不模拟 GC 或分配失败后的恢复。
- 缺运行时接口时，在 `0x9a2ca8` 的异常对象分配调用边界停止；
  不实际执行后续异常构造/抛出，也不能认为这条路径成功返回。

八项拒绝对照为：改错类 tag、分配尺寸、null 初值、迟初始化元数据槽、API 字段偏移，
以及接口缺失、堆余量恰等于/小于分配大小。前五项改模拟器中的机器码，后三项改合成环境；
均由同一核对器按预期原因拒绝，不把任意异常或指令未完成当成通过。

```powershell
& .workbuddy/venv-launcher-count/Scripts/python.exe tools/launcher_share_apk_object_audit.py `
  --host-elf $env:LAUNCHER_LIBAPP --object .workbuddy/tmp/share-apk-flow/menu_island.o
& .workbuddy/venv-launcher-count/Scripts/python.exe -m unittest discover `
  -s tools -p 'test_launcher_share_apk_object_audit.py'
```

## 快照对象池顺序解码（2026-10-14）

要把四个对象池槽解析成真实对象内容，先要回答「公开 Dart 3.10.1 的解码规则能不能
直接套在宿主快照上」。本节的结论是**不能假设**，这是后续池槽取证的前置门禁。

- 快照里的版本串来自 `Version::snapshot_hash_`，由 `tools/make_version.py` 把 15 个
  格式源文件按固定顺序原始字节拼接后取 MD5；快照写入与校验两端使用同一常量。
- 留存 `libapp.so` 的 VM 与 isolate 两处快照版本串同为
  `5d85d85296400e9b3992193deed317c9`；而从官方 tag `3.10.1` 取同样 15 个文件
  （逐个用 blob SHA-1 复核字节一致）算出的 MD5 是
  `1ce86630892e2dca9a8543fdb8ed8e22`。
- 两者不同，说明宿主引擎的 VM 源不是公开 3.10.1 的逐字副本。引擎虽然自报
  `3.10.1 (stable)`，**公开 SDK 的解码规则仍只能当待验假设**：必须逐条用宿主字节
  自洽性和交叉引用确认，不得据同名版本宣称格式兼容。
- 新增 `tools/launcher_share_apk_snapshot_audit.py`，从 `.dynsym` 唯一定位
  `_kDartIsolateSnapshotData`，顺序执行 1465 个簇的 `ReadAlloc` 和 `ReadFill`。
  读取失败、未知预定义 cid、引用越界、簇计数或可变长度不一致均立即拒绝。
- 117870 个 canonical String 的 alloc/fill 编码长度逐项一致；唯一 ObjectPool 的
  alloc/fill length 都是 123596，条目分类为 tagged object 121933、immediate 797、
  skip 683、native 183。所有填充引用均按头声明限制在 `1..277591`。
- 按 `element_offset(i)=16+8*i` 核对四个 `kTaggedObject` 槽，得到完整链：
  `firstKey`（`0xb9458`，idx 94857，ref 8340）=
  `android.intent.extra.PACKAGE_NAME`；`secondKey`（`0xb9460`，idx 94858，ref 88650）=
  `com.miui.mishare.extra.MISHARE_APK_PACKAGE_NAME`；`firstField`（`0xb9468`，
  idx 94859，ref 101022）=`android.intent.action.SEND`；`lastField`（`0xb9470`，
  idx 94860，ref 105341）=`application/vnd.android.package-archive`。
- 这是**顺序解码与引用交叉核对形成的宿主自洽证据**，不是字符串扫描命中；但版本摘要
  仍不一致，所以不证明宿主格式与官方 tag 3.10.1 兼容，也不覆盖真实 Intent 组装、
  chooser、文件授权、线程时序或分享可用。

新证据由独立合同 `launcher-share-apk-snapshot-pool-contract.json` 固定。旧
`launcher-share-apk-object-contract.json` 的 `poolContentsResolved=false` 必须保留：
它描述的是替身执行模型，不与静态快照合同合并，也不据此给旧模型补语义字段名。

## 未解决项与后续准入

1. 四槽静态快照内容已经按引用号解析；仍需在真实运行期核实 `x27`、对象生命周期与
   Intent 组装时序。静态值不证明 chooser 构造、URI 授权或最终分享可用。
2. 替换分享实现仍需独立设计文件来源、异步 IO、跨用户/URI 访问授权、对象保活、
   生命周期与失败处理。Java/ART 参考不能直接套在无 ART 的桌面进程。
3. 不能承诺“失败就再调 original”：前四个边界乃至启动边界可能已有副作用；
   需要先划清可回退阶段、不可回退阶段与防重复要求。本轮不实现回退路径。
4. 仅在合成堆中执行了分配快路径；GC、真实分配器状态、写屏障、异常展开、Binder、
   文件准备、真正 startActivity 与用户可见结果未执行。关闭门槛 1、3、5 后才讨论
   实机挂载，仍须用户明确要求安装。

既有入口合同的 24 字节窗口、目标摘要、可见性结论保持不变。
这份试点新增的是**宿主原行为在受控边界下的证据**，不是替换功能完成度。
