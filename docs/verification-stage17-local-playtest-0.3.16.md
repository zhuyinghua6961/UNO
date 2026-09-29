# stage17 本地试玩包 0.3.16 验收

日期：2026-09-29。源码提交 `ad566a25ddc8ec1b4f97f585b0722b53dd0a057d` 修复 Flutter 小屏牌桌的首个己方回合：自动滚动到手牌和操作区，使出牌、摸牌按钮进入视野。本包仍是本机预览制品。

## 制品与构建

- [试玩归档](../release/0.3.16-local-playtest.tar.gz) SHA-256：`1e3ce8a5c3be05cb9ad12cceeeeba9f4a3eecd22d0c75c4166eaa05ff15cc95f`。`manifest.json` 记录干净源码提交，归档及 247 个包内文件的 `SHA256SUMS` 校验通过。
- [离线镜像归档](../release/0.3.16-local-playtest-images/images.tar.gz) SHA-256：`e3c68e852671bdad9504f7a44fef1c6807a4e0daeca51647d68a6b8a17092a64`；镜像清单及自身校验和通过。四个 `linux/arm64` 镜像标签为 `0.3.16-local-playtest-ad566a25ddc8`，镜像清单的源码提交和包哈希一致。
- 包含 Web 静态文件、后端 JAR、[Android 模拟器 debug APK](../release/0.3.16-local-playtest/flutter/android/uno-emulator-debug.apk)、未签名 iOS Simulator App 与本地 Compose。APK SHA-256：`6c2792bf781530b42a7c8f7b13ef5b65d9f2b63bf1e07729e0641fc33b341e4b`；iOS `App.framework/App` SHA-256：`7f417f18bc3e92c2ee91d07edbc576ddb8851c80287688aba13aa0d51414a141`。
- 打包流程通过 Web 94 项测试与构建、Maven `database-it` 全量验证、Dart 静态检查、Flutter 34 项测试和 Android/iOS 模拟器构建。Flutter 小屏测试使用 420×620 视口，核对首回合手牌标题及出牌按钮都处于可见区域。iOS 构建沿用完整 Xcode 与本机临时 `xcrun` 包装器；包装器不在仓库或归档内。

## 同版独立空库栈

从归档解压，在独立目录运行包内 `tools/init-local-env.mjs` 生成随机本地密钥，加载同版四镜像，以 `--no-build -d --wait` 启动 `uno-package-0316-voice`。本机端口为 Web `127.0.0.1:56088`、Gateway `127.0.0.1:28080`、Mailpit `56025`、LiveKit `7900/7901/7902`。PostgreSQL、Identity、Game、Gateway、Web、Mailpit 和 LiveKit 均健康；Web 首页与 bootstrap 返回 HTTP 200。

在新空库运行 `SMOKE_FULL_MATCH=true SMOKE_TEAM_MATCH=true SMOKE_TEAM_VOICE=true node tools/smoke-auth-chat.mjs`：真实账号注册与邮件验证、App 登录、资料、双向房间文字、经典整局和双方历史、四人 2v2 整局与队伍文字隔离均通过；四名玩家取得按队伍隔离、仅准发布麦克风的语音授权。此项验证了 API 与授权，未测真实设备音频听感。

## Android 普通 APK 界面

安装到 Android API 36.1 模拟器的 APK 与包内 APK 字节哈希相同。普通界面连接本版独立栈，完成真实账号登录、创建双人经典房、双方准备和开局；首个己方回合自动展示手牌与操作按钮。[小屏操作区截图](evidence/stage18-android-first-turn-controls-0.3.16.png)来自同一 APK 连接上一版本地服务时的修复验证，本版独立栈又在普通界面复核了相同可见效果。

本版独立栈对局 `69de43b5-e613-4d53-a41b-0db2fd05dd98` 的数据库命令记录中，该 Android 账号 `c7a2f010-9bca-48a1-90f8-6d6832c25c2a` 在 `applied_version=3` 有一条 `PLAYER/DREW`。人工操作随后两次超时，并由该账号主动退出；对局状态为 `INTERRUPTED/PLAYER_LEFT`。**本次未完成普通 Android APK 整局与结算。**上一版普通 iOS Simulator App 的 [8 轮经典整局](verification-stage17-local-playtest-0.3.14.md)证据延续；0.3.16 iOS App 只完成构建。

本包限定本机模拟器和 `linux/arm64` 镜像。正式签名、iPhone/Android 真机、跨网 ICE/TURN 与 TLS/WSS、真实扬声器/麦克风、远端 CI 和生产发布尚未验收；容量仍仅有[可复现本机基线](verification-stage18-capacity-baseline.md)，没有承载上限结论。
