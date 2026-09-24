# stage15 Flutter 队友语音增量验收（2026-09-24）

## 已接入

- Flutter `livekit_client` 2.13.0 在进行中的 2v2 牌桌显示队友语音。客户端只提交 `matchId`；服务端仍决定队伍、房间和受限媒体凭证。
- 点击“加入队友语音”后才连接媒体房间并申请麦克风。支持静音、重新开麦、退出、队友说话提示、重连状态和播放受限提示。静音后重连按 SDK 的实际本地麦克风状态恢复按钮。
- 退后台、页面销毁、退出牌桌、账号失效、对局结束时离开语音并关闭本地麦克风。加入与退出并发时按具体房间清理，旧请求不能清理新房间。语音错误只显示在语音面板，不阻止出牌或文字消息。
- Android 已声明 `RECORD_AUDIO`，iOS 已声明 `NSMicrophoneUsageDescription`。当前前台策略为进入后台即退出，返回后须再次点击加入。

## 自动化证据

- `dart analyze lib test`：无问题；`flutter test`：21 项通过。
- `flutter test test/team_voice_test.dart`：API Bearer/APP 身份与仅 matchId 请求、点击前不加入/不开麦、静音重连、退后台与页面销毁释放、加入中退出，3 项通过。媒体 transport 在这些 Widget 测试中由 fake 注入；它们不证明真实设备采集或能听见声音。
- `flutter build apk --debug`：通过，生成本地 `app-debug.apk`（约 217 MB，未签名为正式发布包）。Android 合并清单包含 `RECORD_AUDIO`、`ACCESS_NETWORK_STATE` 和 `MODIFY_AUDIO_SETTINGS`。Flutter 3.47 对上游 LiveKit/WebRTC 插件使用旧 Kotlin Gradle 插件给出未来兼容性警告；本次构建仍成功。
- `PATH=/tmp/uno-xcode-tools:$PATH DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer flutter build ios --simulator --no-codesign`：通过，生成本地 `Runner.app`。本机全局 Xcode 选择指向 CommandLineTools；已有的 `/tmp/uno-xcode-tools/xcrun` 包装脚本为子进程设置 `DEVELOPER_DIR`（写法见 [stage4 Flutter 验收](verification-stage4-flutter.md)），无需修改全局设置。仅设置外层 `DEVELOPER_DIR` 的首次尝试失败于 `objective_c` 原生构建钩子取得空 SDK 路径；加包装脚本重试成功。该模拟器产物未进行 iOS 设备签名与安装。

## 仍需验收

- Android/iOS 真实设备上与 App、Web 队友双向听见声音，并确认非队友无法订阅；本机 `127.0.0.1` LiveKit 媒体地址不适合外部设备。
- 系统权限拒绝/永久拒绝、来电与音频占用、耳机/蓝牙/扬声器切换、锁屏、网络切换以及恢复后的实际麦克风指示灯和音轨状态。
- Android 蓝牙耳机所需的额外权限与运行时引导仍待设备验证和实现；合并清单目前未包含蓝牙权限。
- 服务端会话撤销和媒体代次轮换；当前短期 JWT 与终局删除尚未构成完整撤销。
- HTTPS/WSS、可达 ICE/TURN 的测试媒体环境，以及 Android/iOS 安装、登录、牌局、文字、语音的混合端整局记录。
