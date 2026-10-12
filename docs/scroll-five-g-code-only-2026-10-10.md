# 页面返回位置、极暗名称与 5G 磁贴（2026-10-10，仅源码）

## 最新安装验证（2026-10-11 00:27）

用户随后授权安装。资源Context误判修复通过606测试/R8/双lint（0错误、411警告、2提示），
已覆盖安装，最终哈希 `a4b9381e97dacf75c66ab89ebf7f6b2b8e3f9c8e281f5dcf272ef6b26c6c3a1d`。
00:27:25候选包含/工厂到达/磁贴创建三个日志阶段均命中；00:28:09新进程再次创建成功。
未操作5G或视觉验收。SystemUI有SIGKILL退出、发送者不明，助手未请求重启，未见新增
crash-buffer FATAL。详见最新交接和five-g-context-validation构建/安装记录，下文是历史。

## 最新反馈修复（2026-10-11，仅源码）

用户反馈00:10包编辑列表仍无5G。进一步核对发现用户资源Context的applicationContext
可为空，之前防早期初始化的门控误用了它。现在改查进程ActivityThread.currentApplication，
在binder获取处也保留门控，安装Hook时仍不调用MIUI单例；增加候选包含/工厂到达/创建/
拒绝原因日志。新增1个结构测试并修改旧守卫，均未运行；未重新编译安装。详见最新
交接及 `docs/five-g-missing-context-evidence-2026-10-11.json`。没有新真机显示结论。

## 后续安装更新（2026-10-11 00:10）

下文为最初源码批次历史边界。用户随后授权安装，当前605测试/R8/双lint零错误已通过，
修复包已覆盖安装。先补API30保护；首包仍暴露MIUI单例在onPackageReady过早初始化、
污染Holder并引发SystemUI崩溃的问题。已恢复备份并修为仅解析方法、待工厂应用上下文
就绪再调用，新增第7个测试后重新完整验证。最终SHA-256为
`a94fb33e60a946f27cb9226ace288ecb631ab09999274c5bad3ffe98512a4fb5`。
修复包新SystemUI进程已加载5G Hook；监测无新增FATAL，不等于网络/界面验收。失败与
恢复均如实记录，详见最新交接及2026-10-11构建/安装证据；本轮未推送、未改网络。

本轮响应用户这三项反馈，不扩展迁移其他功能。不运行 Gradle、编译、单元测试、lint、
打包，不安装/重启/热重载，不修改模块开关、作用域、SIM 或网络，不提交推送。
仅做源码逻辑审查、宿主/框架只读证据核对及清单生成。没有启动子代理。

## 页面返回位置

- 原因：HyperApp 用 early return 切换整页，原页面离开 composition 后，普通 key 不能
  保存其 rememberScrollState。现在把 rememberSaveableStateHolder 放在导航分支之前，
  用 scope+group、feature、app settings、search、tab 的独立 key 保存状态。
- 作用域主页、二级分类、功能详情和各标签不串位置；详情返回恢复来源页。搜索打开
  详情时不再提前关闭搜索，返回仍回搜索结果并保留关键词与滚动位置。
- 新增 PageStateRetentionTest 结构守卫；不是实际 Compose 测试。返回按钮/系统返回、
  长列表、切组、旋转及进程恢复的真实像素位置仍待后续 UI 验收。

## 名称

“降低亮度磁贴图标替换”改为系统用语“极暗磁贴图标替换”，补全列表描述同步。
原 control_center_dim_tile_icon 键和图形不变，不丢设置，也没有额外改屏幕亮度。

## 独立 5G 磁贴

- 合并进已有默认关闭的 control_center_fix_tiles_list，不新增独立功能开关。
  六类系统候选保持原实现；额外加入 hyperextend_5g，仍需用户进入编辑页手动添加。
  仅 SystemUI 202602260 / 控制中心插件 183022200；工厂/接口不匹配就不提供 5G，
  不让可选 5G 初始化失败拖垮原六项候选。
- 当前原生工厂没有 5G 分支。上游借用 NFC provider，本实现拒绝此方案，独立创建
  实现宿主 QSTile 接口的 Java Proxy。校验全部 34 个方法签名/返回值/重载，使用
  宿主 BooleanState、DrawableIcon、InstanceIdSequence；不调用 NFC 实例、服务或
  开关。5G 矢量图沿用有许可声明的上游 ic_control_center_5g_toggle_v2_on.xml。
- 工厂一个 Hook，四个主包实际 caller、两个插件 caller 去优化；只拦截自身 spec，
  其他 spec 走原生。插件/classic 编辑器与已检查的 pipeline 都接受 QSTile 接口，
  无需伪装 QSTileImpl。保留原生候选可用性、布局保存、状态着色与无障碍消费。
- TelephonyManager 基类有空实现，TelephonyManagerEx 便捷 getter 又把异常当 false。
  因此通过当前框架 Ex 获取 IMiuiTelephony binder，直接读能力/指定 slot 状态；异常
  变成“不可用”，不能把未知当关闭再误开。工厂只读能力查询是同步的；初始状态为
  不可用，实际状态读写使用单线程有界 worker，不在主线程切网络。
- 只控制当前默认数据卡，检查主用户/非工作资料、解锁、非飞行模式、SIM ready 与
  UserManager 的移动网络配置限制。显示状态是“允许使用 5G”，不是当前是否驻网 5G。
  不修改默认数据卡、双卡模式、数据总开关、SA/5GA，长按和详情不提供。
- 点击固定当时 subscriptionId、slot 和 on 值，2 秒内复查同一选择；任何变化丢弃。
  一次写入无重试，不做乐观翻转；刷新期间最多保留一次短时点击，写入期间不堆积
  点击。原生 falsing 判断保留。没有真实网络切换验收，不保证运营商/企业策略支持。
- callbacks/listeners 按对象身份管理，主线程分发状态副本；仅监听期间每 1500ms
  刷新，无常驻观察者。destroy/userSwitch/停止监听用原子 generation 使旧任务失效，
  设置关闭与安全模式阻止新写入；网络服务已收到的请求无法撤回。跨服务快照不是
  原子事务，不能承诺最后一次检查后完全无竞态。
- 关闭不主动改布局，但宿主可能因模块工厂不可用移除已添加的 5G；重新打开可能需
  手动添加。资源/主题变化依赖宿主重建磁贴。独立 Proxy 不走 QSTileImpl 专属 Hook，
  不承诺已有“点击自动收起”等基类功能也覆盖它。上游双图标样式及蜂窝详情 mode 3
  未迁移，不算整项完整对等。

## 证据与验证边界

- `docs/five-g-tile-systemui-evidence.json`：21 个完整正文，含 34 方法接口、状态、
  工厂字段/调用者、宿主消费者、NFC 审计（不是安装靶点），以及 2 次完整 xref。
- `docs/five-g-tile-plugin-evidence.json`：4 个完整正文和 1 次完整 xref（2 callers）。
- `docs/five-g-tile-framework-evidence.json`：只读设备框架哈希、12 个精确方法签名、
  Ex 关键正文片段及限制。未在设备调用网络 getter/setter；无网络开关变化。
- 新增 6 个测试定义（页面结构 1、5G 策略/契约 5），**均未执行**。旧测试中的名称
  和关闭布局描述断言同步。清单生成/证据元数据核对/diff check 不是编译或测试通过。
- 337 条清单：64 待迁移、132 待审计、93 有界静态、19 修复待真机、9 部分静态、
  14 逻辑部分未编译、4 逻辑修复未编译、2 其他。5G pending 转有界源码部分；stock
  因新增适配降为未编译。旧 16 条构建提升因累计输入哈希失配回退，不表示代码丢失。
- 最近安装仍是 23:18 的 0.1.3/4，旧 598 测试、R8/lint 结果**不覆盖本轮源码**。
  下一轮读交接后按用户授权决定是否构建；未经再次要求，不自动部署或实测切网络。
