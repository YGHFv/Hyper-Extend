# Launcher Hook 后端安全评估

更新日期：2026-10-16

本文记录调用点后端的失败语义与候选方案淘汰原因，避免把“离线测试通过”误当成
“可解除阶段锁”。本文不是安装许可，也不授权设备写操作。

## 当前代码状态

- `launcher/count_backend.cpp` 是隔离的离线实现，没有链入 `HyperExtendLauncherNative`。
- 后端安装事务现在用进程内原子占用标记串行化；重入会返回
  `InstallError::kInstallBusy`，不会再次申请 RWX 或重复提交调用点。这只覆盖本后端的
  互斥，不等价于对其它模块、宿主线程暂停或映像卸载的实机合同。
- `launcher/install_permit.h` 继续保留 `kPermitSafetyUnverified` 与
  `kPermitBackendUnverified`；`install_permitted` 仍恒为 `false`。
- `launcher_count_backend_fault_emulate.py` 在 ARM64 Unicorn 中执行真实事务代码，覆盖
  权限申请失败、调用点漂移、回滚冲突和 RX 恢复持续失败。持续失败时，事务虽回滚原
  `BL`，仍可能留下 RWX 宿主页；这被分类为危险终态，不能重试或释放可能可达的岛页。

## 已排除的方案

### 框架 `hook_func` / Dobby

`launcher_backend_publication_audit.py` 对留存框架样本的离线控制流审计显示：框架先在
内部临时槽回填中继地址，再提交目标补丁，最后才回填模块传入的 `original` 槽。模块侧
在 `hook_func` 返回后检查非空槽，无法消除该发布窗口。

留存 Dobby 实现的 POSIX 代码路径还存在更直接的问题：`DobbyCodePatch` 将宿主页改为
RWX、用 `memcpy` 覆盖补丁、再恢复 RX；权限修改结果未被检查，函数最终仍报告成功。
`DobbyHook` 在 `routing->Commit()` 前回填中继槽，而 `Commit` 不向调用方返回补丁提交
状态。因此，返回成功且 `original` 非空不能作为补丁已安全提交的证据。

### `/proc/self/mem` 强制写入

不采用 `FOLL_FORCE` 写 RX 映射作为绕开权限切换的实现。该行为受内核配置、ptrace 权限
和 SELinux 策略影响，不是 Android 上稳定的模块 API；较新的 SELinux 方案还允许策略
拒绝此类强制访问。通用 `copy_to_user` 路径也没有提供本项目所需的“对齐 32 位指令
原子提交”接口合同。

参考：

- Linux 内核 `/proc/<pid>/mem` 强制访问配置：
  https://android.googlesource.com/kernel/common/+/4a55880fa90902939c48b7d9035dc1ea4ee3a71f
- SELinux 针对 `/proc/self/mem` 的强制访问控制讨论：
  https://lwn.net/Articles/1090577/

### `MAP_FIXED` / `mremap` 替换宿主页

不采用复制整页后以固定地址映射替换的方式。固定地址替换并非对其他线程可见的原子
事务；替换失败时目标映射可能已被移除，因而不能满足失败保持原映像可执行的要求。

参考：

- Linux 内核关于 `MAP_FIXED` / `MREMAP_FIXED` 失败后目标映射状态的讨论：
  https://lkml.org/lkml/2023/4/3/519

## 解锁前必须满足的后端合同

任何候选实现都必须同时证明：

1. 宿主调用点提交为单条对齐 32 位 `BL`；不能用多字 `memcpy` 覆盖。
2. 中继代码、`original` 与 continuation 槽在分支可达前完整发布，并完成指令缓存同步。
3. 不存在“失败后仍可能保持 RWX”的可达状态；错误返回必须对应明确、可证明的页面权限
   与调用点状态。
4. 对并发调用、竞争写入、映像重映射和卸载有明确的互斥/生命周期合同。
5. 真实 ARM64 构建产物、留存宿主映像与只读运行证据通过对应审计；静态模拟不能替代
   Gate 1、Gate 3 或 Gate 5 实机验收。

若候选方案不能满足其中任何一项，应继续隔离后端并保留阶段锁，而不是以“功能已接入”
或“失败时会重试”替代安全证明。
