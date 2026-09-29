# stage17 本地试玩包 0.3.15 验收

日期：2026-09-29。源码提交 `d79cc12db34c10a3130a2f6c6e349abeb762895a` 把 Flutter 大厅的模式与好友房入口移到介绍卡片之前，使 Android 模拟器首屏即可创建或加入房间。本包是本机预览制品，不是签名真机版本。

## 制品与构建

- [试玩归档](../release/0.3.15-local-playtest.tar.gz) SHA-256：`27815bd31555183140936b61b46e1d096fd21e17e76280b28418d8f02fa7c01c`。包内 `manifest.json` 记录干净源码提交；归档及 247 个包内文件的 `SHA256SUMS` 校验通过。
- [离线镜像归档](../release/0.3.15-local-playtest-images/images.tar.gz) SHA-256：`e260d08ea657c84f8e8a97ac58b6aab7c5de873a2367584b15f05814d87f5984`；[镜像清单](../release/0.3.15-local-playtest-images/manifest.json)与自身校验和通过。四个 `linux/arm64` 镜像的标签为 `0.3.15-local-playtest-d79cc12db34c`，源码提交和包哈希相同。
- 包含 Web 静态文件、后端 JAR、[Android 模拟器 debug APK](../release/0.3.15-local-playtest/flutter/android/uno-emulator-debug.apk)、未签名 iOS Simulator App 和本地 Compose。APK SHA-256：`a5575e9e217fb2f8cf41bf76c1839f2bd76cdc2390fccc6990e78597ec`；iOS `App.framework/App` SHA-256：`0d4d5ba6c5c83c8b60a3a545b218a758d9b8d0168dda8581aaee845f1c2edf3d`。
- 打包流程通过 Web 94 项测试与构建、Maven `database-it` 全量验证（跨服务 12 项）、Dart 静态检查、Flutter 34 项测试，以及 Android/iOS 模拟器构建。首次重跑后端集成测试时，Testcontainers 取得的本机端口 `63088` 与旧 0.3.13 Web 栈冲突；停止旧测试栈后同一提交全量通过。此机 `xcode-select` 默认为 Command Line Tools，iOS 构建使用完整 Xcode，并在原生资源 hook 调用 `xcrun` 时显式传入 `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`；临时包装器只在本机构建环境，不在仓库或归档中。

## 同版独立栈

从包解压，在独立目录运行包内 `tools/init-local-env.mjs` 生成随机本地密钥与新数据库卷，并用上述四镜像、包内 Compose、账号和语音覆盖配置以 `--no-build -d --wait` 启动项目 `uno-package-0315-voice`。本机端口为 Web `127.0.0.1:65088`、Gateway `127.0.0.1:28080`、Mailpit `65025`、LiveKit `7900/7901/7902`；PostgreSQL、Identity、Game、Gateway、Web、Mailpit 均健康，LiveKit 运行。Web 与 bootstrap 路由返回 HTTP 200。旧 0.3.13/0.3.14 测试容器已停止，数据卷保留。

对新空库运行 `SMOKE_FULL_MATCH=true SMOKE_TEAM_MATCH=true SMOKE_TEAM_VOICE=true node tools/smoke-auth-chat.mjs`：真实账号注册与邮件验证、App 登录、资料、双向房间文字、经典整局和双方历史、四人 2v2 整局与队伍文字隔离均通过；四名玩家取得按队伍隔离、仅准发布麦克风的语音授权。这是 API/授权烟测，不代表真实设备语音听感。

## Android 普通 APK 界面

安装到 Android API 36.1 模拟器的本地 APK 与包内 APK 字节哈希相同。经普通界面登录、首屏[选择经典模式并创建双人房](evidence/stage18-android-lobby-entry-0.3.15.png)、准备和开局；另一名真实测试账号由 API 加入并自动出牌。在同版独立栈的第二局，普通 App 界面选中万能牌、指定红色并提交；服务端 `game.match_commands` 对局 `c1023e4a-7c5b-46f4-994e-e9e8e8faec03` 的玩家记录在 `applied_version=12` 为 `PLAY/RED/PLAYED`。[牌桌截图](evidence/stage18-android-ordinary-0.3.15-match.png)记录普通安装包运行状态。首屏截图拍摄时连接的是旧 0.3.14 服务，牌桌截图与命令记录来自 0.3.15 同版栈。

人工界面操作间出现回合超时，首局连续三次超时中断，第二局在确认出牌后主动退出；该账号战绩为 `0 胜 0 负 2 中断`。**不能据此宣称普通 Android 安装包完成整局或结算。**上版包的普通 iOS App 已完成 [8 轮经典整局](verification-stage17-local-playtest-0.3.14.md)；0.3.15 iOS App 本次只完成构建，未重新安装和整局复测。

此包仍限定本机模拟器和 `linux/arm64` 镜像。正式签名、iPhone/Android 真机、跨网 ICE/TURN 与 TLS/WSS、真实扬声器/麦克风、远端 CI 和生产发布尚未验收；容量仅有[可复现本机基线](verification-stage18-capacity-baseline.md)，没有承载上限结论。
