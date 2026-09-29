# stage17 本地试玩包 0.3.17 验收

日期：2026-09-29。源码提交 `1815450c889cc34dcc21ec3d278c871c5eebea7f` 在 Flutter 操作区显示桌面牌、生效颜色、当前玩家和剩余秒数。小屏自动滚动后，玩家仍可看到决定出牌所需的信息。本包是本机预览制品。

## 制品与构建

- [试玩归档](../release/0.3.17-local-playtest.tar.gz) SHA-256：`9bb8b30543589dc2d5a87f968e2693beea629c05ad2e2ab65c61843e605d39bf`。包内 `manifest.json` 记录干净源码提交，归档及 247 个包内文件的 `SHA256SUMS` 校验通过。
- [离线镜像归档](../release/0.3.17-local-playtest-images/images.tar.gz) SHA-256：`2619660a3d129ec5521f219533f09c4189489df8b1a3dda154f1228d4ed41d95`。镜像归档、清单自身校验通过；四个 `linux/arm64` 镜像标签为 `0.3.17-local-playtest-1815450c889c`，清单中源码提交及包哈希一致。
- 包含 Web、后端 JAR、[Android 模拟器 debug APK](../release/0.3.17-local-playtest/flutter/android/uno-emulator-debug.apk)、未签名 iOS Simulator App 和本地 Compose。APK SHA-256：`5bb067cfffc7d6c1db1e5e05932f6fbbcfe4fb8ca9c51683e92293b859766a3b`；iOS `App.framework/App` SHA-256：`a6fd9a6a0d47fe3d00f7b26c1ac642857cdd75b7dc0c9c3c1185fc373a578155`。
- 打包流程通过 Web 94 项测试与构建、Maven `database-it` 全量验证、Dart 静态检查、Flutter 34 项测试及 Android/iOS 模拟器构建。420×620 视口测试核对首回合手牌标题、桌面信息及出牌按钮都在视野内。iOS 构建使用完整 Xcode；本机临时 `xcrun` 包装器不在仓库或归档内。

## 同版独立空库栈

从包解压，在独立目录运行包内 `tools/init-local-env.mjs` 生成随机本地密钥，并用上述同版四镜像以 `--no-build -d --wait` 启动 `uno-package-0317-voice`。Web `127.0.0.1:56088`、Gateway `127.0.0.1:28080`、Mailpit `56025`、LiveKit `7900/7901/7902`；PostgreSQL、Identity、Game、Gateway、Web、Mailpit 和 LiveKit 均健康。Web 首页与 bootstrap 返回 HTTP 200。

在新空库运行 `SMOKE_FULL_MATCH=true SMOKE_TEAM_MATCH=true SMOKE_TEAM_VOICE=true node tools/smoke-auth-chat.mjs`：真实账号注册与邮件验证、App 登录、资料、双向房间文字、经典整局和双方历史、四人 2v2 整局与队伍文字隔离均通过；四名玩家获得按队伍隔离、仅准发布麦克风的语音授权。这验证 API 与授权，不代表真实设备音频听感。

## 普通模拟器界面

Android API 36.1 模拟器安装了与本包 APK 字节哈希相同的本地构建文件。普通界面在上一版 0.3.16 服务上登录、建双人房、准备、开局；[操作区截图](evidence/stage18-android-compact-controls-0.3.17.png)展示手牌、桌面牌、生效颜色、当前玩家、剩余秒数和操作按钮同屏。该截图不作为 0.3.17 同版服务的普通界面整局证据。0.3.17 iOS 普通 App 只完成构建；先前 0.3.14 iOS 模拟器普通 App 的[经典整局证据](verification-stage17-local-playtest-0.3.14.md)延续。

普通 Android APK 整局与结算、目标真机安装升级、正式签名、跨网 ICE/TURN 与 TLS/WSS、真实扬声器/麦克风、远端 CI 和生产发布仍未通过。容量仍只有[可复现本机基线](verification-stage18-capacity-baseline.md)，没有承载上限结论。
