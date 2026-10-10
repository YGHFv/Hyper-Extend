# 小磁贴图标颜色与自动收起审计（2026-10-10，22:45）

按用户要求继续逐项迁移、仅做代码逻辑验证。本批先补普通启用小磁贴图标颜色，再审计
现有点击自动收起功能。未执行Gradle、编译、单元测试、lint、打包、安装、重启/热重载、
设备开关/作用域/SIM/网络修改；未委派代理、提交或推送。保留所有既有未提交成果。
没有新APK，历史545项测试通过不覆盖本批和此前四批未编译源码。

## 1. 普通启用小磁贴通用图标颜色

沿用默认关闭的 `control_center_tile_color`，新增参数
`control_center_tile_color.icon`。功能标题改为“已启用小磁贴颜色”，背景与图标分别
留空跟随系统，只接受不透明RGB，功能目录数量仍48。与圆角开关独立，不增加第二套
插件发现或背景拥有权表。

参考HyperCeiler `5e4686069dd7ab1f3697e256d5fc7d68fb73e317` 的QSColor；不照搬全局
qs_icon_enabled_color资源替换。精确SystemUI 202602260 / 插件183022200、主线程、
主屏、默认主题、非card/非detail且普通背景；不强关材质。只在state2、activeBgColor0、
非disabledByPolicy、非isTransient、非原生isRestrictedState时新应用自定义色。

### 原生着色与对象身份

- 当前 `QSTileItemIconView.drawableTint(State, Drawable)` 原生先mutate，再设置
  autoMirrored，按状态和spec选择颜色并setTint；普通启用分支读当前视图的
  defaultIconColor。两个实际调用点为liteIconUpdate和updateIconInternal。
- 仅接受精确VectorDrawable或AnimatedVectorDrawable，不处理bitmap、第三方custom
  spec或其他Drawable类型。quietmode、papermode、mute、cell、autobrightness、
  flashlight、batterysaver七个专用分支保留原生颜色。
- 在原生drawableTint调用期间临时覆盖当前视图defaultIconColor；finally仅在字段
  仍等于本次应用值时恢复原值，若原生写入其他新值则保留。同视图嵌套调用不重复覆盖，
  ThreadLocal在finally恢复/移除；异常由公共单次proceed保护处理，不重放原生调用。
- 不替换图标、不克隆动画矢量、不改callback、不直接写ColorFilter，不主动刷新磁贴。
  原生继续mutate/tint及组合图层；已核对DrawableUtils.combine保留传入Drawable身份。
  不改共享资源或QSTile.State，降低亮度图标适配器仍使用原有原生着色链。

与背景/圆角共享TileCornerHooks，现为八Hook整体ready：active background、icon tint、
两个背景setter、四个原生刷新/回收入口。全部23个方法和构造精确解析及去优化，实际
tint caller已在集合中。插件发现只安装一次；增加defaultIconColor和主包State.spec
精确字段，State仍经插件ClassLoader委托解析，不假造插件DEX类。

### 恢复边界

**这是部分迁移，未编译未测试。** defaultIconColor字段立即恢复，不代表已显示图标
立即恢复。原生updateIconInternal可能对相同状态早返回，因此改色、清空、关闭开关或
转入排除条件后，旧图标色可能保留到下一次真正执行drawableTint。没有用“强刷”或
替换图标身份掩盖这个限制。文案明确等待原生重新着色；不保证颜色对比度或完整转场。

背景与半径独立拥有权和回收清理保持上一批实现。背景关闭随原生刷新恢复，启用等待
原生背景创建；安全模式全局绕过Hook，完整清理可能需要重建/重启系统界面。卡片颜色、
经典控制中心颜色、材质/玻璃和其他图标类型仍未迁移；不声称全上游颜色覆盖。

## 2. 点击磁贴自动收起审计修复

保留默认关闭 `control_center_auto_collapse`，拆到AutoCollapseHooks，移除
ControlCenterHooks旧private分支，仍只Hook一次 `QSTileImpl.click(Expandable)`。

旧实现缺少精确版本、动态开关、主线程主屏、临时状态、锁屏、详情、调用前状态检查，
且未补实际caller去优化。新实现精确解析11个方法、9个字段，核对字段类型及实例属性；
八个主包实际caller含一个static蜂窝桥接，全部concrete方法去优化成功才安装。
StatusBarStateController.getState是abstract，只解析查询，不错误要求其去优化成功。

每次原生调用前后均检查：动态开关、主包202602260、主线程、Context.displayId为0、
已知可用state1/2、非policy/transient、非空且非edit spec、未开详情、status为0，
并要求mHost可用于原生collapsePanels且前后同一身份。缺少显示上下文则保持原生。
同tile嵌套保护在finally清理；安全模式阻止新的收起请求。不改变长按、副按钮或磁贴值。

### 发出请求不等于操作成功

已核对完整调用链：

1. QSTileImpl.click记录日志并发送what2消息，不直接执行磁贴handleClick。
2. H.handleMessage中策略禁用会转管理员支持提示，否则调用handleClick；其catchall
   捕获异常并记录警告。因此不能把handler方法返回也当作磁贴执行成功。
3. MiuiQSHostAdapter.collapsePanels转ShadeExpansionInteractor，再由主线程协程
   按useControlCenter选择新/旧面板收起，保留系统原生路径。

本批保留上游“发出点击请求后收起”语义，没有改成虚假的成功回调、跳过认证或改写
handler消息。面板收起时操作仍可能被拒绝或失败；方法前后状态读取也不是跨线程事务，
不保证捕获观察间全部变化。模块不新增异步任务，但已交给原生协程的收起请求不会被
之后关闭开关取消。

主包dispatch引用八条完整；插件内base-class dispatch及QSTile接口exact查询零条完整。
零引用不能证明运行时没有跨loader/桥接调用，所以明确标注“当前插件调用链未确认
覆盖”，不扩展未经证实的插件Hook，不保证新版所有磁贴命中。

## 证据与代码逻辑检查

- `docs/tile-icon-plugin-evidence.json`：41个完整方法/字段正文、5个背景AXML；本批
  重新读取正文。资源名/路径映射及前八组dispatch引用继承前批，新增tint两caller。
- `docs/tile-icon-systemui-evidence.json`：6个State字段/构造正文。
- `docs/auto-collapse-systemui-evidence.json`：23个完整方法/字段正文，其中20个安装
  依赖、3个后续执行路径；八个主包caller与插件覆盖限制独立记录。
- 共75条证据记录（70个成员、5个AXML，跨manifest重复字段分别计数）；完整正文
  仅存忽略目录 `.workbuddy/tmp/mt-systemui`，仓库仅保存签名、关键片段、属性与哈希。
- 新增9个测试定义但未执行：图标spec/临时恢复/异常和新写入/嵌套/文案5项，自动收起
  状态/排除/精确签名及static/文案4项。同步颜色设置及共享圆角fixture，方法数断言23。
- 手工审查配置接线、单一adapter、finally恢复、原生异常不重放、所有方法owner/static、
  状态门控与异步收起语义。证据完整性、sha256、关键片段、static及fixture元数据一致；
  清单重新生成，git diff --check通过。这些不等于编译、测试或运行时验收通过。

清单337条：**65待迁移、132待审计、94有界静态、19修复待真机、9部分静态、
12逻辑部分未编译、4逻辑修复未编译、2其他**。本批新增一个图标参数映射，自动收起
从旧静态状态改为逻辑修复未编译；不是迁完全部磁贴。

交接见 `docs/migration-handoff-2026-10-10.md`。设备仍19:29包，通知suppression已有
19:30命中；最新本地APK仍21:13长按音量批次。全量lint历史22错误未解决。后续继续
其他pending和已有项审计，默认不编译、不部署；完整视觉和Settings保存重进待安排。
