# stage15 Flutter 队友语音增量验收（2026-09-24）

## 已接入

- Flutter `livekit_client` 2.13.0 在进行中的 2v2 牌桌显示队友语音。客户端只提交 `matchId`；服务端仍决定队伍、房间和受限媒体凭证。
- 点击“加入队友语音”后才连接媒体房间并申请麦克风。支持静音、重新开麦、退出、队友说话提示、重连状态和播放受限提示。静音后重连按 SDK 的实际本地麦克风状态恢复按钮。
- 退后台、页面销毁、退出牌桌、账号失效、对局结束时离开语音并关闭本地麦克风。加入与退出并发时按具体房间清理，旧请求不能清理新房间。语音错误只显示在语音面板，不阻止出牌或文字消息。
- Android 已声明 `RECORD_AUDIO`，iOS 已声明 `NSMicrophoneUsageDescription`。当前前台策略为进入后台即退出，返回后须再次点击加入。
- Android 12+ 在点击加入语音时通过原生平台通道申请蓝牙连接权限；拒绝只提示并继续尝试扬声器语音。低版本使用清单中的旧版蓝牙权限。Android 合并清单不请求摄像头权限。
- LiveKit 删除旧代次房间后，前台已加入的客户端延迟约 3.3 秒申请新凭证并重连。用户此前关闭麦克风时，只恢复收听，不重新采集；退出、退后台、账号失效和页面销毁都会取消自动恢复。

## 自动化证据

- `dart analyze lib test`：无问题；`flutter test`：21 项通过。
- `flutter test test/team_voice_test.dart`：API Bearer/APP 身份与仅 matchId 请求、点击前不加入/不开麦、静音重连、退后台与页面销毁释放、加入中退出，3 项通过。媒体 transport 在这些 Widget 测试中由 fake 注入；它们不证明真实设备采集或能听见声音。
- `flutter build apk --debug`：通过，生成本地 `app-debug.apk`（约 217 MB，未签名为正式发布包）。Android 合并清单包含 `RECORD_AUDIO`、`ACCESS_NETWORK_STATE` 和 `MODIFY_AUDIO_SETTINGS`。Flutter 3.47 对上游 LiveKit/WebRTC 插件使用旧 Kotlin Gradle 插件给出未来兼容性警告；本次构建仍成功。
- 增加蓝牙接入后再次执行 `flutter build apk --debug`：通过；合并清单包含 `BLUETOOTH_CONNECT` 以及限定到 Android 11 及以下的 `BLUETOOTH` / `BLUETOOTH_ADMIN`。Android API 36.1 模拟器上安装调试包、预先授予蓝牙连接权限，再执行 `flutter test integration_test/voice_device_permission_test.dart -d emulator-5554`：1 项通过，证实 Dart 到原生权限通道可调用。尚未在真实蓝牙设备上验证请求弹窗、拒绝后的路由或耳机切换。
- `PATH=/tmp/uno-xcode-tools:$PATH DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer flutter build ios --simulator --no-codesign`：通过，生成本地 `Runner.app`。本机全局 Xcode 选择指向 CommandLineTools；已有的 `/tmp/uno-xcode-tools/xcrun` 包装脚本为子进程设置 `DEVELOPER_DIR`（写法见 [stage4 Flutter 验收](verification-stage4-flutter.md)），无需修改全局设置。仅设置外层 `DEVELOPER_DIR` 的首次尝试失败于 `objective_c` 原生构建钩子取得空 SDK 路径；加包装脚本重试成功。该模拟器产物未进行 iOS 设备签名与安装。
- 增加 Android 原生权限通道后，`dart analyze lib test integration_test`、全量 `flutter test`（21 项）及 iOS 模拟器构建再次通过。Flutter 测试和 iOS 构建同时运行会争用 `ios/Flutter/ephemeral`；并发尝试失败后顺序重跑成功。
- V11 代次恢复后再次执行 `dart analyze lib test integration_test`、全量 `flutter test`（23 项）、Android 调试 APK 与 iOS 模拟器构建，均通过。Widget 测试新增静音换房和退出时取消换房；真实 Android/iOS 音频行为仍待设备实测。

## 仍需验收

- Android/iOS 真实设备上与 App、Web 队友双向听见声音，并确认非队友无法订阅；本机 `127.0.0.1` LiveKit 媒体地址不适合外部设备。
- 系统权限拒绝/永久拒绝、来电与音频占用、耳机/蓝牙/扬声器切换、锁屏、网络切换以及恢复后的实际麦克风指示灯和音轨状态。
- Android 蓝牙耳机的运行时授权路径已接入，实际权限拒绝、蓝牙路由和耳机切换仍待设备验证。
- 服务端会话撤销与媒体代次轮换已在本机 LiveKit + Web 浏览器验收；Flutter 端仅有注入式自动测试，尚未在真实设备验证收到 `roomDeleted` 后的恢复及旧令牌隔离。
- HTTPS/WSS、可达 ICE/TURN 的测试媒体环境，以及 Android/iOS 安装、登录、牌局、文字、语音的混合端整局记录。
