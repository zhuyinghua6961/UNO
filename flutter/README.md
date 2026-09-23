# Flutter App 骨架

使用 Flutter 3.47 / Dart 3.13 创建 Android 与 iOS 工程。Web 由根目录 web/ 的 Vue 应用负责，不重复生成 Flutter Web。

在根目录执行 `node tools/sync-assets.mjs` 后：

```sh
flutter pub get
flutter analyze
flutter test
flutter run
```

当前已有大厅、模式选择、牌桌预览、真实账号入口、好友房等待室、经典牌桌和房间文字。账号页支持注册、邮箱验证/重发、登录、找回/重置密码与退出；好友房支持房间码加入、准备、选队、人数调整和离开。经典房间可启动或恢复对局；原生 WebSocket 接收个人牌面并提交触控动作。房间文字在等待室及牌桌每两秒补取新消息，发送失败保留消息 ID 供重试。2v2 对局、队伍文字和 LiveKit SDK 尚未接入；系统级邀请深链未配置。服务端默认关闭认证，需按 `../docs/authentication.md` 显式启用本地测试配置。Android 模拟器与 Web 的混合整局、结算和第二局已验收；iOS 对局及房间文字设备界面仍待验收，见 `../docs/verification-stage9-flutter.md` 与 `../docs/verification-stage11-room-text.md`。

本机可用 `dart analyze --format=machine` 完成静态检查；原有 Flutter LSP 异常见 `../docs/verification.md`。

默认开发网关地址：Android 模拟器 `http://10.0.2.2:29080`，iOS 模拟器 `http://localhost:29080`。实机须传入设备可达的 HTTPS 地址，例如 `flutter run --dart-define=API_BASE_URL=https://your-test-host`。正式构建拒绝 HTTP 地址；调试版的 Android 明文流量仅在 debug manifest 放行，iOS 仅允许本地网络。账号和对局接口使用 `X-UNO-Client: APP` 与 Bearer 凭证，不传 Cookie/Origin。网关未启动或认证开关关闭时会显示不可用，不模拟登录成功。实时连接在恢复时先同步权威牌面，再重新订阅；未确认动作不会自动重发。

访问与刷新凭证作为单个记录保存在 Android Keystore/iOS Keychain 支撑的 `flutter_secure_storage` 中；Android 禁用应用备份，避免恢复加密数据后密钥不匹配。启动时读取记录并向后端确认身份，过期时串行刷新并保存轮换后的整组凭证；401 清除已失效会话，网络故障保留凭证供重试。退出只有在服务端确认或已失效时才清除本地凭证。密码及邮件凭证只用于当前提交，不持久保存。正式设备上的安全存储和双端同一用户联调仍待验收，见 `../docs/verification-stage4-flutter.md`。

Android 声明 INTERNET/RECORD_AUDIO，iOS 声明 NSMicrophoneUsageDescription；声明权限不表示会自动录音。本骨架没有运行时申请或开麦行为。未来应只在点击加入队友语音时申请权限，拒绝后允许继续游戏。

应用标识暂为 com.example.*，不是正式包名。已移除工具自动填入的个人 Apple team，未使用签名证书、未生成 IPA。正式构建需确认包名、签名、隐私说明、服务地址和麦克风流程。

```sh
flutter build apk --debug --dart-define=API_BASE_URL=http://10.0.2.2:29080
flutter build ios --simulator --dart-define=API_BASE_URL=https://example.invalid
```

以上仅验证调试包和iOS模拟器编译，不能替代 Android 正式签名与 iOS 分发签名。本机Xcode工具选择的临时处理见 `../docs/verification-stage4-flutter.md`。release 目录不会自动出现这些产物；需要实际构建后再归档。首次构建需要 Android SDK / Xcode 及相关工具链。
