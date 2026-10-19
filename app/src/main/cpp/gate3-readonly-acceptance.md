# 门槛 3：安全通道的只读验收准备

当前交付的是检查规则，不是实机验收结论。`install_permitted` 仍恒为 `false`，
门槛 1、3、5 均未关闭；不写安装路径、不链接蹦床。

## 1. 执行边界

- 没有用户当次明确的「安装」指令，不执行安装。给出安装许可只授权安装，不授权改作用域、
  设置、停止进程、重启或其它设备写操作。
- 本工具只读本机已有文本和仓库源码，不调用设备命令，不自动采集、刷新或清理日志。
- 原始日志、APK、本机路径与采集记录只放 `.workbuddy/` 或仓库外；不能提交到公开仓库。
- 先核对日志可观测性，再讨论功能。冻结日志、轮转或缺行都不等于端点不存在，
  更不能通过清日志、重启或自动重试“制造”一组通过记录。

## 2. 安装前检查清单

- [ ] 已明确本轮材料对应哪个模块 APK，并在本机记录摘要；不能仅凭 APK 文件名认版本。
- [ ] 已对本机模块 APK 运行 `tools/launcher_native_package_audit.py --apk <模块包>`；
  诊断原型加 `--prototype`。这只是打包检查，不证明该包已在任何进程加载。
- [ ] 已区分应答端与请求端：普通包包含 Kotlin 端点，但不含原生 worker；诊断原型含
  worker，作用域恰好两项、顺序固定：桌面 `com.miui.home` 在前、system_server `system` 在后。
  原型 APK 存在不能证明 system_server 已装上端点，也不能证明桌面侧原生库已加载。
- [ ] 已明确两端实际代码的来源与加载阶段；若使用此前已加载的端点，单独记录这一事实。
  当前日志不含构建摘要，工具不能替代版本归属审核，也不会替用户调整作用域。
- [ ] 已确认使用默认 `unarmed` 场景还是只分析已有 `accepted` 场景。工具不打开主开关、
  子项或武装状态；读到已采纳也不会放行安装。
- [ ] 已留存两份基线：system_server 的完整 `ModuleLog` 文件日志、
  桌面 `HELauncherProbe` 的完整 `threadtime` 日志。保留原始行和会话头，不只摘录成功行。
- [ ] 已确认两路日志是否有能力反映新事件。`ModuleLog` 文件镜像不覆盖纯原生
  `__android_log_print`；文件日志正常不能证明原生 logcat 正常。
- [ ] 对于必要的加载或进程生命周期步骤，只记录用户自行安排和确认的事实，不由代理执行。

基线为空必须是真实采集结果，不能手工清空以绕过旧日志检查。工具不能认证采集动作或
时间；缺少真实基线时只能做合成回归，不能把输出当作实机证据。

## 3. 后置日志检查清单

- [ ] 在不修改设备状态的前提下，分别取得两端后置日志，使用与基线相同来源和格式。
- [ ] 两路后置文本都应是基线的完整追加版本；相同文本视为无新增。轮转、裁剪、换源、
  不完整末行均拒绝，不能自动丢掉基线后再判通过。
- [ ] 服务端使用最后一个 `==== attach pid=… (… buffered) ====` 会话，
  其中须有 system_server 模块身份、启动记录和唯一的端点 `installed=true`。
  不能跨进程重启借用旧安装日志；同一会话内安装记录可以早于本轮基线。
- [ ] 服务端事务码必须为 `0x00484541`。当前会话出现端点异常、安全阻断、安装失败或
  未知端点日志时停止核对，不用后面的成功行覆盖错误。
- [ ] 原生新增窗口必须恰好一组“开始单次快照查询”和“查询完成”，PID、TID 均相同。
  开始在基线中、只有完成在新增窗口中，不构成完整的一次观测。
- [ ] 开始日志为 `abi=2`；原生日志均保留 `install_permitted=false`。拒绝其它 ABI、
  未识别格式、工作线程身份失败或创建失败，不能当成端点配置问题。
- [ ] 新增 `served` 的修订号、功能位、数量上限与原生完成字段全部一致；
  当前会话已记录的 served 修订号须严格递增，不跨会话或回绕匹配。
- [ ] 默认未武装结果必须为 `channel=0x10 snapshot=0x40`，且修订号有效、功能位为 0、
  数量上限在 5..20、armed=0。不能用全零拒绝载荷凑成未武装通过。
- [ ] 已采纳场景必须显式使用 `--expect accepted`，结果为 `channel=0 snapshot=0`、
  armed=1，其他可见字段合法。功能位可以为 0；“采纳配置”不等于功能生效。

`LauncherSnapshotEndpoint.reportOnce()` 的 served/rejected 日志共享 **8 次上限**。
超过上限后可以应答而不再记录；缺少 served 时只能判证据不足，不能认定事务没发生。
两端没有日志级事务唯一标识；字段一致只是必要的交叉检查，不是调用身份认证。

## 4. 离线命令

无需设备材料即可先检查日志合同与当前源码：

```powershell
python tools/launcher_gate3_log_audit.py check-contract
```

以下文件名是本机材料占位路径；不预置、不伪造任何实机结果：

```powershell
python tools/launcher_gate3_log_audit.py audit `
  --before-server .workbuddy/gate3/server-before.log `
  --after-server .workbuddy/gate3/server-after.log `
  --before-launcher .workbuddy/gate3/launcher-before.log `
  --after-launcher .workbuddy/gate3/launcher-after.log `
  --expect unarmed
```

退出码 0 只表示该组文本通过所选场景的核对；仍输出 `门槛3已关闭=false`、
`安装许可=false`、未关闭门槛 `[1,3,5]`。退出非零表示规则不满足或材料不足，不自动归因。
成功报告包含摘要、匹配字段和证据行号，不输出 PID、UID、TID 或原始路径。

## 5. 阻断位的代码依据

| 观测 | 能得出的结论 | 代码位置 |
| --- | --- | --- |
| channel=0 | 本次快照采纳；不含本地熔断验收 | `native_entry.cpp::query_configuration_once` |
| channel=0x1 | 一项或多项必需 Binder 符号不可用 | `native_entry.cpp::query_snapshot` |
| channel=0x2 | window 服务缺失，或描述符/接口类关联失败；不能再细分 | `native_entry.cpp::query_snapshot` |
| channel=0x4 | prepare、transact、输出句柄路径失败；不能仅凭此位归因 | `native_entry.cpp::query_snapshot` |
| channel=0x8 | 回复未完整读到 10 个字 | `native_entry.cpp::query_snapshot` |
| channel=0x10 | 完整回复被快照契约拒绝，须继续看 snapshot 位 | `channel.h::channel_decision` |
| channel=0x20 | 初始/JNI 的尚未查询状态；不是正常 worker 完成结果 | `channel.h::kChannelNotQueried` |
| channel=0x40 / 0x80 | 重入或在途 / 进程归属不符，不是新快照成功 | `native_entry.cpp::query_configuration_once` |
| snapshot=0x4 | 可见或未记录字段畸形；全零 denied 载荷属于此类 | `snapshot_wire.h::snapshot_errors` 的提前返回 |
| snapshot=0x40 | 单独此位且其他字段合法时，才是仅未武装 | `snapshot_wire.h::snapshot_errors` |
| snapshot=0x1 / 0x2 | 确认码 / 协议不符 | `snapshot_wire.h::snapshot_errors` |
| snapshot=0x8 / 0x10 / 0x20 | 序号未递增 / 时钟或租期异常 / 到期 | `snapshot_wire.h::snapshot_errors` |

`LauncherSnapshotWire.denied()` 保留确认码、协议，其他字全零；revision=0、limit=0
已构成畸形，`snapshot_errors()` 会在检查未武装之前返回 `kSnapshotMalformed`。
`launcher_channel_test.cpp` 用编译期断言分别锁定 denied 与正常未武装两条路径。

只有开始而没有完成不能叫“已超时”：`checkService` 不轮询，不等于同步 Binder 有
硬超时；worker 没有取消、重试或刷新逻辑。缺完成可能是阻塞、线程/进程结束、日志丢失
或观察窗口不足，现有日志不能独立区分。

## 6. 通过日志核对后仍未覆盖的项目

- 日志来源、完整性、采集新鲜度，以及日志与实际加载 APK/进程生命周期的对应。
- 真正的 Binder/SELinux 行为、调用方身份可信度；日志匹配不能代替身份设计审核。
- 未记录字段的独立复算：确认码、租期、恢复令牌等不能从当前日志重新计算。
- 本地熔断状态的读写、跨重启持久化、时钟变化与人工恢复；一次快照采纳没有验证这些。
- 安装瞬间的并发执行、模块中继发布与代码页生命周期。门槛 1、5 仍需各自独立验收。

日志核对工具本身不采集或新增设备日志。2026-10-14 的入口只读预检新增
`HELauncherSafety` 标签，但既有 `HELauncherProbe` 模板不变；本工具忽略该新标签，
不能用其阻断位替代本地熔断持久化、调用方认证或运行期安全协议的验收。
