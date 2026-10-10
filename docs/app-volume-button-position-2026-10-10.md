# 多应用音量按钮位置（2026-10-10）

## 设置

- 功能名改为「统一多应用音量调节样式」，沿用主开关键 `volume_app_entry`，不改变默认关闭状态。
- 新增字符串配置 `volume_app_entry.position`，标题「多应用音量调节按钮位置」。
- 按用户列出的六项提供选择（原描述写五项，但枚举为六项）：
  `above_volume` 新增·音量条上方；`above_silent` 新增·静音按钮上方；
  `above_dnd` 新增·勿扰按钮上方；`below_dnd` 新增·勿扰按钮下方；
  `replace_silent` 替换·静音按钮；`replace_dnd` 替换·勿扰按钮。
- 空值或未知值回落 `above_volume`，保持旧配置行为。配置经 HyperChoice 自动纳入读写、框架投影和备份导入导出。
- 复用 Miuix `OverlayDropdownPreference`，含浮层遮罩、圆角菜单和当前项勾选，不使用普通内联列表。

## 宿主实现

- 继续限制音量插件 183022200 和音质音效 260903。对 MT 已打开的插件 APK 做只读核验，
  不安装 APK、不修改宿主 APK、不重启连接的真机。
- 原顶部入口保留原动画和边距规则。其余入口放入原生静音/勿扰的 LinearLayout，跟随底部按钮区自己的动画，
  避免再叠加顶部入口的手动缩放。
- 使用 `ringer_layout`、`dnd_layout` 解析直接父容器与插入位置，保留原分隔空间。
  若目标不存在、不共享容器或布局不是收起态垂直排列，则不插入/不显示，不猜测另一个位置。
- 替换通过单独的模块按钮和原按钮临时 GONE 实现，不覆盖原点击监听、图标或无障碍语义。
  无播放、锁屏、展开、结束显示、启动原生多应用面板前均恢复原按钮；恢复不强行覆盖宿主更新后的可见性。
- 所有位置共享现有 `MediaPlayback.active` / `AppVolumePolicy.visible` 判定，即活动媒体应用输出；
  没有改成“有安装应用就显示”或常驻按钮。已有的壁纸/系统 UID 过滤保持不变。
- 竖屏底部新增不挪媒体滑块，检查底部是否放得下；横屏用新增行一半高度居中并检查上下空间。
  替换不增加行高。恢复过程不累加位移。现有总音量、应用音量面板、分页、写入通道不变。
- 若用户同时开启「隐藏静音和勿扰按钮」，底部位置随整个原生底部区隐藏；可选择音量条上方位置。
  这一组合限制在功能说明中明示，不擅自关闭另一个功能或反向显示宿主隐藏的按钮。
- 安全模式触发后，入口的已注册绘制回调也会取消请求、隐藏入口并恢复临时替换。

## 验证

- `docs/app-volume-position-evidence.json`：5 个方法/布局目标、3 个 ID 映射、1 个布局资源映射核验通过。
- `:app:testDebugUnitTest`：250 项通过，0 失败/错误/跳过；按钮位置新增 9 项测试。
  覆盖六项/默认值/配置登记、插入索引、显示条件、横竖屏边界、替换与宿主可见性恢复。
- `:app:assembleRelease`：通过；`git diff --check`：通过。
- 尚未安装真机验收六个位置的最终像素、展开/收起动画、连续音源变化与点击命中。
  静态核验和纯逻辑测试不等于这些运行时测试已完成。

```powershell
python tools/mt_systemui_probe.py --workspace gqbnd0ih --manifest docs/app-volume-position-evidence.json
.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease --console=plain
```
