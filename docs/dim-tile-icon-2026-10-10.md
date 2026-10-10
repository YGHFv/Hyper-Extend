# 降低亮度磁贴图标替换（2026-10-10，未部署）

新增默认关闭 `control_center_dim_tile_icon`，系统界面 → 控制中心 → 磁贴。
与 `control_center_fix_tiles_list` 独立：图标开关不会加入候选列表，也不会添加磁贴、
点击磁贴、开启降低亮度或修改亮度强度。保留前三批未提交成果，不重复迁移候选列表。

## 上游与范围

参考 HyperCeiler `5e4686069dd7ab1f3697e256d5fc7d68fb73e317`：

- `rules/systemui/controlcenter/tiles/ReduceBrightColorsTile.java`：只配置同一套开启/关闭图标。
- `appbase/systemui/TileUtils.java` 的 `hookHandleUpdateState` / `applyConfigIcons`：
  原生状态更新后只替换 icon，不需要搬整个自定义 TileUtils 框架。
- `library/libhook/src/main/res/drawable/ic_reduce_bright_colors.xml`：24dp 九路径矢量图。
  本地 `app/src/main/res/drawable/ic_qs_extra_dim_hyperceiler.xml` 保留全部路径与版权标记，
  不使用位图或 AI 重画。路径串 SHA-256：
  `3e97346bf970227d00f3d6c8b09b96f9e093337411ca07b7bde49b4b7127db36`。

仅 SystemUI `202602260` 的 `ReduceBrightColorsTile`，spec 精确 `reduce_brightness`，
BooleanState、原生 0/1/2 状态、有原始 icon 且无 iconSupplier。颜色和图形大小由原生
视图决定。名称、value、contentDescription、无障碍 class、可用性、点击、长按、
原生升级弹窗与系统设置跳转均未替换。

## 当前宿主适配

当前原生更新链：

1. `QSTileImpl$H.handleMessage` 或 `handleUserSwitch` → `handleRefreshState`。
2. `ReduceBrightColorsTile.handleUpdateState` 先读当前用户的降低亮度状态，填写 value、
   state、系统标签、无障碍描述，调用 `maybeLoadResourceIcon` 写入新的原生图标。
3. 本批唯一 Hook 在第 2 步结束后只改 icon；原方法异常时不执行后处理，不吞异常重跑。
4. 原生 `State.copyTo` 比较 icon，再向视图和编辑候选回调 `onStateChanged`。

旧 `ResourceIcon.get(moduleRId)` 不再适合：当前宿主使用 `DrawableIconWithRes`，
其 equals 只比较资源 ID。若借用原生 ID 包装新图，关闭后可能仍被比较为相同而不重绘；
若传模块 ID 给宿主资源，也可能读取错误的资源。

本批按名称从已安装模块的独立包 Context 加载矢量图，再用宿主 `DrawableIcon(Drawable)`
包装，不伪造资源 ID、不 Hook 全局 Resources 或其他磁贴。普通 DrawableIcon 使用图形
对象身份比较，和原生 DrawableIconWithRes 的开启/关闭恢复比较不会误判相等。

经典 `MiuiQSIconViewImpl.updateIcon` 与新插件 `QSTileItemIconView.updateIconInternal`
都调用 QSTile.Icon 的 drawable 接口。普通 DrawableIcon 同时提供独立 invisibleDrawable；
未全局修改图标绘制器或插件 getter。Compose 的 `toIconProvider` 也保留非 WithRes 的
原始 icon，但本批不据此宣称 Compose 视觉路径或其他设备已经验收。

## 缓存、恢复与失败边界

- 对 update、refresh、用户切换与消息分发全部精确签名解析并去优化；任一失败不安装本项。
- 每个 tile 与 configuration/density 缓存单独图标，活跃状态引用期间保持身份稳定，
  避免每次同状态刷新都被当成图形变化。配置改变创建新图标，独立 mutate，不共享跨磁贴 tint。
- 缓存键和值都是弱引用（key 为 WeakHashMap）；value 不持有 Context/tile。弱 value 防止
  Drawable.callback → View → tile 的间接环把弱 key 变成强保留。没有新线程/监听/定时任务。
- 资源按模块包中的名称解析，并套用当前配置；无资源、版本不符、构造失败等返回原生图标。
  不把失败永久缓存为成功，后续原生刷新允许重试。完整写入只发生在构造全部成功后。
- 关闭功能或 HookRuntime 安全模式后不再覆盖；原生下一次 handleUpdateState 会写回
  原生图标并触发差异通知。不会主动刷新/清空宿主已显示视图，所以不承诺立刻恢复，
  旧视图和正在进行的原生动画可能保留到下一次刷新/重建。
- 不更改原生数据、用户偏好、Tile 列表、标签/提示或系统亮度；升级模式的原生弹窗依旧有效。

## 证据与测试

- `docs/dim-tile-icon-systemui-evidence.json`：22 个方法/字段（17 方法、5 字段）。
- `docs/dim-tile-icon-plugin-evidence.json`：2 个插件消费者方法，无新增插件 Hook。
- 两份 manifest 全部只读实时核验，无截断/分页；fixture 与实时正文哈希匹配。
- 新增 17 项测试：版本/spec/状态/供图方式边界、按 tile/config 的身份缓存与失败重试、
  并发创建、全部反射签名、24dp/白色九路径及上游路径哈希、目录默认关闭与副作用说明。
- 当前全量单元测试 523 项通过，0 失败/错误/跳过。最终构建结果见
  `docs/dim-tile-icon-build.json`；R8 Release 日志 `build/dim-tile-icon-build.log`。
- 全量 lint 未重跑，历史 22 个错误仍未解决，不把 lintVital 通过等同全量 lint 干净。

## 清单与部署

最终 R8 Release、lintVital、`git diff --check` 成功。本地 APK `0.1.3 / 4`，
2697298 bytes，SHA-256：
`d14b168619fd2731089fdff22f5c6b99b57af170cf61faaf0c05d4b767c1cc30`。
AAPT2 核对资源名 `drawable/ic_qs_extra_dim_hyperceiler`，打包后路径优化为 `res/k81.xml`，
九路径与 24dp 尺寸仍在，不以 ZIP 原始文件名不存在误判资源被删除。

337 条配置/导航/依赖记录：pending 79 → 78，有界静态 97 → 98；
待审计 132、修复待真机 19、部分静态 8、其他 2 不变。这是一个图标项，不是整个磁贴页
或音量面板迁移完成。早期浏览了音量长按上游源码，但未实现该项，仍留 pending。

本批未安装、重启/热重载、改设备开关/作用域/SIM/网络，未启动子代理，未提交或推送。
设备仍为最后授权的 19:29 通知图标修复包。没有本批 Hook 加载或视觉结果证据。

统一真机验收留后：新旧控制中心编辑候选与已添加磁贴、开/关/不可用状态的原生 tint、
名称及 TalkBack、原生点击/长按与升级弹窗、切换主题/深浅色/横竖屏、关闭功能/安全模式后
原生刷新恢复、反复进入编辑页及不同用户无图标/回调残留。不得用 MT/JVM 通过替代这些验收。
