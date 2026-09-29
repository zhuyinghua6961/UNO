# stage17 本地试玩包 0.3.18 验收

日期：2026-09-29。包与离线镜像从干净提交 `1ee82bb7465c4a70158e9b2f18a8a7ba2deed404` 生成。相对 [0.3.17](verification-stage17-local-playtest-0.3.17.md)，Game 返回 PostgreSQL 实际保存的对局截止时间和聊天创建时间，避免首次响应与重连快照的微秒精度不一致；Flutter 房间入口在初始“当前房间”查询完成后重绘可操作状态。

## 制品与构建

- [试玩归档](../release/0.3.18-local-playtest.tar.gz) SHA-256：`55812d7838ca949f78814366041f9dbfd399025f2325ec666877ef1b6d1e9a30`。`manifest.json` 指向上述干净提交；解包后 `SHA256SUMS` 的 247 个文件全部通过。
- [离线镜像归档](../release/0.3.18-local-playtest-images/images.tar.gz) SHA-256：`6b396d6c4aec6a837c4eca68ceb8664d74f24c57bb7e45feec93284d1dd8ff`。四个 `linux/arm64` 镜像实际 `docker load` 成功，标签为 `0.3.18-local-playtest-1ee82bb7465c`；镜像清单记录同一源码提交和包哈希。
- 包含 Web、后端 JAR、[Android 模拟器 debug APK](../release/0.3.18-local-playtest/flutter/android/uno-emulator-debug.apk)、未签名 iOS Simulator App 和本地 Compose。APK SHA-256：`39db1d51f12d51fd2c468274db379576efbc15e903bb5ef71396700d841af203`；iOS `App.framework/App` SHA-256：`16bb26e81c2d90f4a5715c619d6e4c9c6cae18ffb30490abc15b3d7e0fba9a5c`。
- 打包实际通过 Web 94 项测试与构建、Maven `database-it clean verify`、Dart 静态检查、Flutter 35 项 widget/单元测试及 Android/iOS 模拟器构建。Android 房间入口回归测试核对初始查询完成后“创建好友房”可用。iOS 模拟器包仍使用临时进程会话。

## 同版独立空库栈

将归档解到 `/tmp/uno-0318-root/0.3.18-local-playtest`，用包内 `init-local-env.mjs` 生成新随机本地密钥；四镜像归档导入后，以 `--no-build -d --wait` 启动 `uno-package-0318-voice`。Web `127.0.0.1:56088`、Gateway `28080`、Mailpit `56025`、LiveKit `7900/7901/7902`；PostgreSQL、Identity、Game、Gateway、Web、Mailpit、LiveKit 均健康。Web 首页和 bootstrap 为 HTTP 200，bootstrap 的账号、玩法和队友语音开关均为 true。

在新空库运行 `SMOKE_FULL_MATCH=true SMOKE_TEAM_MATCH=true SMOKE_TEAM_VOICE=true` 的真实账号烟测：邮件验证、App 登录、资料、双向房间文字、经典整局和双方历史、四人 2v2 整局与队伍文字隔离均通过；四名玩家获得按队伍隔离且仅准发布麦克风的语音授权。包内 Web 的双账号 Playwright 测试从断网前版本 1 恢复后推进到版本 3。语音授权不等于真实设备的音频听感。

## Android 界面范围

从本包安装普通 debug APK 到 API 36.1 模拟器并从桌面打开，[大厅截图](evidence/stage18-android-ordinary-0.3.18-lobby.png)显示经典与 2v2 模式、好友房入口。普通 APK 完成真实账号注册、邮件验证和登录；用该 App 创建双人经典房，另一名经 API 登录的测试玩家加入并准备，App 房主准备、开局并显示实时牌桌。App 返回等待室后，API 测试客户端在服务端执行余下 1111 条合法指令，直至该局 19 轮结算；普通 APK 的[账号战绩截图](evidence/stage18-android-ordinary-0.3.18-result.png)显示对应的 0 胜 1 负及 371:508 最终比分。结算截图 SHA-256：`68092461e9b4cc0ca70794cba1a79466dc8b6a4919d8048cafbcbf27cd3493cd`。

与包同一 Flutter/Game 源码的集成测试 App 在上述同版栈完成登录、建房、开局、服务端确认的界面操作、前后台恢复、经典整局结算及再次开局。该集成测试 App 与归档内普通 APK 不是同一二进制文件；普通 APK 已验证开局与结算后战绩展示，**由普通 APK 双方界面全程操作直到结算仍未验收**。

同源码远端 CI 状态另见 [GitHub Actions 验证记录](verification-stage17-ci.md)。本包为本机预览，不含签名 IPA/Android release 包；尚无 Apple Developer 团队和目标 iPhone。目标真机、正式 Keychain、跨网 ICE/TURN 与 TLS/WSS、真实扬声器/麦克风、正式容量目标、目标环境部署和发布授权均未通过。
