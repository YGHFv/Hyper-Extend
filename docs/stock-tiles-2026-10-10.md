# 补全磁贴列表：有界迁移（2026-10-10，未部署）

本批继续前两批手势提示线与锁屏壁纸动画，保留已有未提交修改。
新增默认关闭 `control_center_fix_tiles_list`：系统界面 → 控制中心 → 磁贴。
只补充编辑候选，用户手动添加；不直接创建/点击磁贴，不写入已保存布局。

## 上游与当前差异

参考 `.workbuddy/refsrc/HyperCeiler` 的 `FixTilesList.java` 及 libhook 两份 stock 字符串。
上游是整体替换 `miui_quick_settings_tiles_stock` / `_pad`。当前 ROM 原生列表已有
卫星、录屏、投屏等上游旧列表没有的条目，不能整表替换造成回退。

当前 SystemUI `202602260` 手机/平板资源相对上游都少八个候选。其中
`MiuiQSFactory.createTile(String)` 有六个明确分支，本地仅补这些：

| spec | 候选 | 原生 provider |
| --- | --- | --- |
| reduce_brightness | 降低亮度 | reduceBrightColorsTileProvider |
| inversion | 颜色反转 | colorInversionTileProvider |
| saver | 流量节省 | dataSaverTileProvider |
| dark | 深色模式 | uiModeNightTileProvider |
| onehanded | 单手模式 | oneHandedModeTileProvider |
| color_correction | 颜色校正 | colorCorrectionTileProvider |

`user`、`dnd` 无当前原生工厂分支，不加入。原生 `quietmode`、`batterysaver` 等保持，
不能仅按中文相近就替换或去重。六个候选仍受各自 `isAvailable()`、设备配置、插件排除项
等限制，不保证每台设备都显示六个，不绕过管理员或工作资料策略。

## 新旧控制中心入口审计

### 新控制中心插件 183022200

- `TileQueryHelper.queryTiles` 仅投递任务，实际在 worker 的 `addStockTiles` 查询候选。
- `getTilesStock` 通过 `MiuiQSHostCompat.getStockTilesCompat` 反射调用宿主
  `MiuiQSHostAdapter.getStockTiles`，失败才使用 `staticStockTiles`。
  早期搜索 `->getStockTiles()` 零命中不是链路失效，实际使用反射方法名。
- `addStockTiles` 前后建立 ThreadLocal 范围，只有同一 helper 的第一次 `getTilesStock`
  返回值追加缺失项；不 Hook 宿主 getter、不修改 `staticStockTiles`。
- `getTilesStock` 也服务第三方磁贴去重和 MiShare 迁移检测；这些调用位于范围外，保留原生。
- 原生依次处理当前已添加项、排除项、custom 项、createTile/isAvailable/destroy、TileInfo、
  liveTiles、监听刷新。编辑结束的 destroy 和用户 saveSpecs 链保持原生。

### 经典控制中心

- 当前查询已内联进 `MiuiQSCustomizerController.show(II)`，不是旧 queryTiles 方法。
- `show` 同步调用 `tileQueryHelper.mContext.getString(0x7f140a06)` 后创建候选。
  仅该主线程范围、同一个 Context 对象身份、精确资源 ID/name 的一次读取追加缺失项。
- `Context.getString(int)` 是 final；对 show 及其 handleMessage/onLayoutChange 调用方
  去优化，使用原方法返回结果，不修改 Context、Resources 或缓存。
- 通用字符串 Hook 先查线程范围，不在编辑查询时不读取模块设置或解析字符串。
  已添加项、第三方项、可用性、TileCollector、销毁与通知保持原生。
- 此 ROM 经典页原生固定读手机资源，新页经宿主 repository 按 IS_PAD 选手机/平板。
  本功能不改变两条原生选择逻辑，不宣称已在实体平板上验收。

### 不能改共享列表的原因

`MiuiStockTilesRepository.stockTiles$delegate` 同时被
`QSSettingsRestoredRepositoryExt.changeToRestoreValue` 用于备份恢复过滤。
全局资源替换或修改这个 Lazy 会让恢复链也发生变化。本批只改编辑查询的返回值，保持
repository、Lazy、资源、默认布局与恢复过滤不变。因此不承诺本次手动添加的候选在未来
备份恢复中一定保留，仍由原生恢复策略决定。

## 门控、生命周期与恢复

- 两个适配器各自两 Hook，必须该适配器全部安装完成才启用；任一失败只禁用本适配器，
  不影响另一个已就绪的编辑器。
  主包版本精确为 202602260，新控制中心另要求插件 183022200；六 provider 字段类型检查。
- 新页复用 `SystemUiPluginHooks` 的单一 loader 发现与已有缓存回放；新增功能也要求
  createPlugin/createPluginContext 去优化成功，未加第二个抢占 loader 的 Hook。
- 新页 getter/addStockTiles/query lambda/桥接方法/runnable 调用链去优化，避免调用方内联。
- 追加算法保留原始字符串顺序、未知项、自定义项与原有重复项，只防止新候选重复追加。
  null/空白/超大列表保留原生，重复查询幂等。
- ThreadLocal 精确匹配身份与 key、只消费一次；其他线程、嵌套排除域不继承，finally 恢复。
  没有模块 worker、监听器、Context 长期缓存或永久宿主字段修改。
- 关闭开关/安全模式后新查询不再追加；已打开编辑页可能保留本次生成的候选直到退出重进。
  已由用户保存的磁贴不会被模块主动移除，不把撤回布局当作安全恢复操作。

## 测试与证据

- `docs/stock-tiles-systemui-evidence.json`：19 个方法/字段及两份资源名称和值。
- `docs/stock-tiles-plugin-evidence.json`：13 个方法/字段，含反射 getter、工作队列、
  编辑保存、第三方候选和结束清理。合计 32 个代码靶点全部实时只读核验，无分页/截断。
- fixture `stock-tiles-host-members.txt` 对照正式证据生成，不提交宿主全文或 APK。
- 新增 20 项测试，覆盖候选边界/幂等/顺序/版本、单次身份匹配、嵌套/异常/线程隔离、
  精确方法/字段/provider、入口与关闭后语义。
- 当前全量单元测试 506 项通过，0 失败/错误/跳过。最终 R8 Release、APK 哈希及
  lintVital 状态见 `docs/stock-tiles-build.json`；全量 lint 未重跑，历史 22 个错误未解决。

## 清单与部署

最终 R8 Release 与 lintVital 成功，`git diff --check` 通过；本地 APK `0.1.3 / 4`，
2696133 bytes，SHA-256：
`7e5d3fe73c0c6065c160e393112fe299172fbd8671eef7464ac18ca0bace5c38`。

清单总计仍 337 条配置/导航/依赖记录：pending 80 → 79，有界静态 96 → 97，
待审计 132、修复待真机 19、部分静态 8、其他 2 不变。不是 337 个功能，也不表示原版
八个候选全量对齐或磁贴分类页整体迁完。

没有安装、重启/热重载、修改设备开关/作用域/SIM/网络，没有子代理、提交或推送。
设备仍是 19:29 通知重要程度图标修复包；本批没有 Hook 加载或视觉验收证据。

统一验收时再检查：新旧编辑页、手机/平板原有候选与顺序、六类候选的系统可用性、
无重复项、手动添加/删除/保存后重进、关闭开关/安全模式、反复开关编辑页、旋转、
工作资料/受限用户和第三方磁贴、无额外监听/活磁贴残留。MT 与 JVM 测试不能代替这些验收。
