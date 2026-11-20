# 微信气泡 · LSPosed 模块

## 下载 v1.2.0

- [不 Hook 微信版](dist/WeChatBubbles-v1.2.0-SystemUIOnly.apk)：LSPosed 仅勾选系统界面。
- [微信 Hook 版](dist/WeChatBubbles-v1.2.0-WeChatHook.apk)：LSPosed 勾选系统界面和微信。
- [验证记录与使用说明](dist/验证记录-v1.2.0.md)

两个 APK 均包含 ColorOS 17 与 HyperOS 适配路径，安装其中一个即可。

v1.2.0 增加重启系统界面、隐藏桌面图标及气泡位置记忆。两版仍共用 ColorOS 17 和 HyperOS 小窗适配，保留独立 SystemUI UID 的授权修复；已核对微信 8.0.71 和 8.0.80 的界面接口。

两个安装包共用包名、签名和 `versionCode 7`。

- `wechatHook`（`1.2.0-wechatHook`）使用系统界面和微信作用域。通过只读观察当前聊天，跟随小窗内手动切换会话。
- `systemuiOnly`（`1.2.0-systemuiOnly`）仅使用系统界面作用域。根据最后点开的气泡和原生小窗状态识别会话。

ColorOS 原生接口已在目标设备上核对，安装和完整交互由用户实机验证。详见 `dist/验证记录-v1.2.0.md`。

## 实现效果

- 微信新消息到达：在当前应用上方显示头像气泡、未读数和短时文字预览。
- 两版预览框共用内容自适应宽度：按 Android 对标题与正文的实际字形测量，短消息收紧，较长消息扩展；最多 264dp，并受屏幕可用宽度约束。达到上限后自动换行，保留原有最多三行和末尾省略规则。最窄 72dp，极窄可用空间以实际边界为准；使用测得高度约束底部位置，支持中英文、emoji 和系统字体缩放。
- 点按头像或预览：使用原通知的 Activity PendingIntent，通过 ROM 的原生小窗入口打开微信，并收起被点击的气泡。聊天跳转由微信自己的原通知决定，不构造联系人或发送接口。
- 小窗内正在显示同一会话时，新消息不再弹出该会话气泡；其他联系人或群的新消息仍然显示。退出小窗后恢复。
- `wechatHook` 可只读观察微信聊天 UI 当前已持有的 username，因此能跟随小窗内手动切换聊天；会话按微信真实路由 `Chat_User` 与当前聊天 UI 对应，不按昵称合并。
- `systemuiOnly` 仅在成功发出原生小窗请求后记忆最近点击气泡的 token，并只在实时检测到原生小窗时抑制同 token 通知；点击其他气泡会更新记忆，关闭小窗会清除记忆。等待小窗出现最多三秒，超时清除记忆；有记忆时每 750ms 观察一次窗口，清除后停止观察。它不知道微信内手动换聊天或从别处打开的聊天，因此属于最佳努力，不保证始终准确跟随当前会话。
- 两版仅在内存记录会话哈希；无法识别会话时正常显示通知。精确版状态六秒未更新即过期；仅 SystemUI 版记录持续到小窗关闭，受上述手动切换限制。
- 回复：在微信原生小窗内使用微信自己的输入框、键盘和发送按钮。模块不自动发送消息。
- 气泡可拖动贴边；松手后自动记住左右方向和相对高度，SystemUI 或手机重启后，下次消息在保存的位置显示。横竖屏、可用尺寸和气泡数量变化时自动限制在屏幕内。仅保存位置，不恢复重启前的通知内容。长按或拖到屏幕底部的关闭目标可收起。
- 设置页可隐藏桌面图标；隐藏后从 LSPosed 的模块设置进入，可再次关闭开关恢复。只禁用桌面启动器别名，不禁用设置 Activity 或模块。
- 设置页可重启系统界面，需确认并授予 Root；系统会重新拉起状态栏等界面。Root 操作在后台执行，等待最多 15 秒，仅向核对过身份的 SystemUI 进程发送 TERM。
- 最多同时保留四个最近会话，默认预览六秒；气泡仅存内存，半小时无更新清理。
- 保留通知栏通知、声音、振动和原有通知操作。只有气泡确实显示成功时，才尝试替代同条通知的普通顶部横幅。

参考图是其他系统/应用的界面。头像气泡、预览、点开真实聊天小窗是本项目目标；小窗边框、控制条和微信内部界面沿用这台手机实际的 ROM 和微信版本，不能声称与三星截图逐像素相同。

## 作用域与隐私

两版都只需要勾选 **系统界面（`com.android.systemui`）**，不勾选“系统框架”及其他应用。`wechatHook` 另外勾选 **微信（`com.tencent.mm`）**，仅只读观察当前聊天 Fragment 的可见性、窗口模式和其已持有的会话 username；`systemuiOnly` 不加载微信作用域，也不承诺准确跟随微信内手动换聊天或从别处打开聊天。已核对微信 8.0.71 和 8.0.80 的界面签名。模块没有聊天数据库访问、联网、读取通讯录、无障碍服务或后台发送消息的代码。

消息正文、头像和原通知 PendingIntent 只用于内存显示，不写入磁盘或日志。`wechatHook` 跨进程只传递带包名隔离的 SHA-256 会话标识，最多八项，六秒未更新即失效，不保存 username 或昵称；状态桥校验 Binder UID，微信仅可报告会话状态，不能读改模块配置与诊断，广播还要求模块签名权限。`systemuiOnly` 不启用这条微信状态桥，仅在 SystemUI 内存中记录上次点开的会话哈希。两版配置和诊断仅允许模块自身、系统 UID 1000 以及主用户预装的系统界面 UID 访问。

系统锁屏、切换用户或停用模块时清除气泡与预览；不绕过锁屏、FLAG_SECURE 或通知隐藏设置。免打扰和低重要性通知不额外弹出气泡。微信可能复用带 `FLAG_UPDATE_CURRENT` 的 PendingIntent；两版都会在创建时固定会话 token，并在点击前再次核对原通知路由，避免旧气泡指向后来会话。

本版本仅处理主用户 0 的微信；应用双开、工作资料和第二空间暂不纳入范围。

## 安装与启用

1. 选择并安装 `WeChatHook` 或 `SystemUIOnly` APK。两个 APK 使用相同 applicationId 和签名，可互相覆盖安装；不要同时安装为两个独立应用。
2. 在 LSPosed 模块列表启用“微信气泡”，两版都勾选“系统界面”；只有 `WeChatHook` 版勾选“微信”。不勾选“系统框架”或其他应用。
3. 切换 flavor 后重新载入作用域进程：切换到 `SystemUIOnly` 时，先取消 LSPosed 中的微信勾选，再重新启动微信和 SystemUI，避免已载入的旧 hook 残留；切回 `WeChatHook` 时恢复微信勾选并重新启动微信和 SystemUI。也可重启手机。
4. 打开“微信气泡”，确认状态为已加载、小窗接口可用。
5. 点“测试气泡”，回到桌面查看本机测试通知。点气泡进入标注清楚的本地测试会话，可验证小窗与键盘，不会发给任何微信联系人。
6. 微信启用系统消息通知，并在微信设置中允许展示新消息内容。收到真实消息后查看气泡并点开微信会话；点击微信“发送”才会发消息。

如果原通知本身隐藏了正文，模块无法还原内容；如果原通知仅指向微信消息列表，模块会保持该路由，不能保证跳入单个聊天。若原通知 PendingIntent 已失效，显示提示并收起该气泡，不尝试伪造聊天跳转。ROM 拒绝原生小窗时保留原通知，并显示失败提示。

## 停用和恢复

可在模块应用内关闭总开关，气泡会即时收起。完全卸载前建议先关闭总开关，再在 LSPosed 禁用本模块并重启 SystemUI/手机。模块不修改系统 APK、微信 APK 或聊天数据库，不需要恢复系统文件。

## 构建

环境：JDK 17 或 21、Android SDK 35、Gradle 8.13、Android Gradle Plugin 8.13.2。Windows 中文目录启用了 `android.overridePathCheck`。

```powershell
.\gradlew.bat :app:assembleWechatHookRelease :app:assembleSystemuiOnlyRelease
.\gradlew.bat :app:lintWechatHookRelease :app:lintSystemuiOnlyRelease
```

`app/libs/xposed-api-82.jar` 为官方 Xposed API，仅 `compileOnly` 使用，不会打入模块 APK。下载来源：https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar 。

v1.2.0 两个正式 release 包共用项目独立签名、applicationId 与 `versionCode 7`；versionName 分别为 `1.2.0-wechatHook` 和 `1.2.0-systemuiOnly`。源码包不包含私钥与本机路径配置。其他机器可构建 debug 包；若要在原设备上直接更新 release 包，需要保留本机 `.signing` 和 `local-signing.properties`，不要公开私钥。

构建完成后可运行 `python tools/package-deliverables.py` 打包既有 APK。脚本不触发构建，输入必须是 `app/build/outputs/apk/wechatHook/release/app-wechatHook-release.apk` 和 `app/build/outputs/apk/systemuiOnly/release/app-systemuiOnly-release.apk`。交付文件名为 `WeChatBubbles-v1.2.0-WeChatHook.apk`、`WeChatBubbles-v1.2.0-SystemUIOnly.apk`、`WeChatBubbles-v1.2.0-source.zip`；`dist/SHA256SUMS.txt` 为三项文件生成 SHA-256。私钥、本机配置、原微信 APK、设备截图均排除。

## 验证

纯 Java 策略测试位于 `tools/BubblePolicyTest.java`，共 52 项通过：原有 40 项加上 `systemuiOnly` 的 12 项 `LastOpenedChat` 生命周期断言，覆盖同会话匹配、其他会话放行、更新记忆、窗口关闭清空、启动宽限期与三秒超时、未知会话及锁屏/停用清理。Android 层原生窗口调用和 750ms 调度另作源代码核对，不由这些纯 Java 断言替代实机验证。`python tools/verify-editions.py` 可核对构建元数据、真实 APK 中的类定义隔离及 Xposed API 未打包。实际构建与手机历史测试结果见 `dist/验证记录-v1.2.0.md`。
