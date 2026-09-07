# Flutter App 骨架

使用 Flutter 3.47 / Dart 3.13 创建 Android 与 iOS 工程。Web 由根目录 web/ 的 Vue 应用负责，不重复生成 Flutter Web。

在根目录执行 `node tools/sync-assets.mjs` 后：

```sh
flutter pub get
flutter analyze
flutter test
flutter run
```

当前只有大厅、模式选择、牌桌与账号预览。登录、消息、网络和 LiveKit SDK 均未接入，`core/api_config.dart` 仅保留地址配置入口。

本机 `flutter analyze` 存在 SDK 的 LSP 初始化异常，本次通过 `dart analyze --format=machine` 完成静态检查；详见 `../docs/verification.md`。这不影响已通过的 3 个 widget tests，不建议为了本项目修改全局 SDK。

后续接入网络时，Android 模拟器访问宿主机一般使用 10.0.2.2；iOS 模拟器可使用 localhost；实机使用可达的局域网/HTTPS 地址。地址可通过 `--dart-define=API_BASE_URL=...` 传入，但当前页面不读取网络。

Android 声明 INTERNET/RECORD_AUDIO，iOS 声明 NSMicrophoneUsageDescription；声明权限不表示会自动录音。本骨架没有运行时申请或开麦行为。未来应只在点击加入队友语音时申请权限，拒绝后允许继续游戏。

应用标识暂为 com.example.*，不是正式包名。已移除工具自动填入的个人 Apple team，未使用签名证书、未生成 IPA。正式构建需确认包名、签名、隐私说明、服务地址和麦克风流程。

```sh
flutter build apk --debug
flutter build ios --no-codesign
```

以上是可选的后续验证命令，不能替代 Android 正式签名与 iOS 分发签名。release 目录不会自动出现这些产物；需要实际构建后再归档。首次构建需要 Android SDK / Xcode 及相关工具链。
