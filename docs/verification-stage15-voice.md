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

2026-09-24 iOS 模拟器增量：App 面板新增“仅收听”，`flutter test test/team_voice_test.dart` 6 项通过，验证不采集麦克风即可加入，且随后可主动开麦。隔离 Compose 栈、三名 Web 浏览器玩家和 iPhone 17 Pro iOS 26.5 模拟器运行 `cd web && UNO_E2E_DEVICE_ID=<booted-simulator-id> npm run test:e2e:ios-voice-signaling` 通过：iOS 界面用真实账号取得团队语音凭证，进入 LiveKit 队伍频道并主动退出，随后从界面提交回合并与 Web 玩家完成同一场 2v2；四人战绩与结算一致。对局 ID `a642874e-e69f-4189-9696-cd22c4f6b712`。该测试没有让 Web 玩家发布音轨，不能证明 iOS 收听或双方互听。

测试入口改为移动端通用文件后再次在相同 iOS 模拟器通过，仅收听加入、退出及完整混合 2v2 均成功，对局 ID `72e4bc31-272d-4b34-b5fc-42212454a9c3`；媒体播放和采集范围仍同上。

权限弹窗生命周期与滚动保活修复后，iOS 26.5 模拟器再次通过同一仅收听信令与 Web 混合整局，对局 ID `cfc032fb-7f1d-4ee9-8138-7f8842503ccc`。E2E 自动回合现与正式 Web 客户端一样逐次获取新 CSRF 令牌；系统键盘在输入房间码后由测试主动收起。仍未验证 iOS 音轨收发。

Android API 36.1 `Medium_Phone_API_36.1` 模拟器增量：App 首次加入仅收听时触发系统“附近设备”蓝牙权限弹窗，Flutter 生命周期短暂进入 `inactive`。原面板将任何非 `resumed` 状态误判为退后台，授权尚未结束就退出；已调整为 `hidden`、`paused`、`detached` 才退出。另用 `AutomaticKeepAliveClientMixin` 保持长牌桌滚动离屏的语音连接；Widget 测试分别覆盖权限弹窗与滚动，`flutter test test/team_voice_test.dart` 8 项、全量 `flutter test` 29 项及 `dart analyze lib test integration_test` 均通过。加入状态文字改为“正在连接语音…”，避免仅收听时误提示申请麦克风。

隔离 Compose 栈下，Android 模拟器通过 ADB 反向转发 `tcp:28080`、`tcp:28025`、`tcp:7880` 和 `tcp:7881` 接入 Gateway、Mailpit、LiveKit。运行 `cd web && UNO_E2E_DEVICE_ID=emulator-5554 npm run test:e2e:android-voice-signaling`，在系统“附近设备”权限弹窗出现后点击“Allow”，测试通过：App 界面显示“已加入 · 麦克风关闭”，随后主动退出；与三名 Web 浏览器玩家完成同场 2v2，四份战绩一致。对局 ID `fe2c8bde-61ea-4c08-bff1-2263b9d7a1b9`。本测试没有发布或接收音轨，不能证明 Android 扬声器或麦克风实际工作；真机、非队友音频隔离和网络切换仍需验收。

2026-09-28 复测：原 Android 自动化仍需人工处理“附近设备”运行时权限，首次将文字与语音合并运行时卡在语音连接。`web/e2e/mixed-mobile-team.cjs` 现等 App 安装启动后，通过 ADB 仅给本地测试包授予 `BLUETOOTH_CONNECT`；未修改产品权限流程。API 36.1 模拟器（`emulator-5554`）在原有 `uno-stage17-check` Compose 栈上运行 `UNO_E2E_DEVICE_ID=emulator-5554 UNO_E2E_MOBILE_CHAT=1 UNO_E2E_MOBILE_VOICE=1 npm --prefix web run test:e2e:mixed-android-team` 通过：四个真实账号、Web/App 房间与队伍文字隔离、App 仅收听加入/退出、两端操作、2v2 结算和四份战绩一致；对局 ID `77a0ff3a-ea4d-492e-b103-967bb349b1b2`。本次模拟器以无窗口、无宿主音频模式运行，仍不证明 Android 音轨采集或实际可听。

同日 iOS 复测：iPhone 17 Pro / iOS 26.5 模拟器运行 `UNO_E2E_DEVICE_ID=00AF3E75-A77A-4F3F-BCBF-FF3E12CD6BBC UNO_E2E_MOBILE_CHAT=1 UNO_E2E_MOBILE_VOICE=1 npm --prefix web run test:e2e:mixed-ios-team` 通过。三个 Web 账号与一个 iOS UI 账号完成同场 2v2、房间与队伍文字隔离、iOS 仅收听语音加入/退出、双方动作、结算和四份战绩；对局 ID `0ec6f9ea-5057-4038-9f33-82b4b51b3a5b`。本机全局 `xcode-select` 仍指向 CommandLineTools；首次只设置外层 `DEVELOPER_DIR` 时，`objective_c` 原生 hook 无法取得 SDK 路径。按 [stage4 的临时 `xcrun` 包装脚本](verification-stage4-flutter.md)在当前命令的 PATH 中补充 Xcode 选择后，最小复现和完整用例均通过，未修改系统全局设置。仍未发布或接收音轨，也未生成签名 IPA。

媒体试验边界：同一模拟器上尝试 iOS 开麦时，面板曾返回权限拒绝；手动安装 Runner 后用 `simctl privacy grant microphone com.example.unoApp` 授权，模拟器 TCC 记录显示允许，但启用麦克风仍使 Runner 在系统 `AURemoteIO::Initialize` 超时处中止。仅收听状态下让 Web 队友发布音轨，也触发同类系统崩溃（本机诊断报告 `Runner-2026-09-24-141431.ips`、`Runner-2026-09-24-141920.ips`）。这些失败没有证明真机上的同一故障，也没有形成音频互听证据；必须在目标真机与可达媒体网络重新验证。

- Android/iOS 真实设备上与 App、Web 队友双向听见声音，并确认非队友无法订阅；本机 `127.0.0.1` LiveKit 媒体地址不适合外部设备。
- 系统权限拒绝/永久拒绝、来电与音频占用、耳机/蓝牙/扬声器切换、锁屏、网络切换以及恢复后的实际麦克风指示灯和音轨状态。
- Android 蓝牙耳机的运行时授权路径已接入，实际权限拒绝、蓝牙路由和耳机切换仍待设备验证。
- 服务端会话撤销与媒体代次轮换已在本机 LiveKit + Web 浏览器验收；Flutter 端仅有注入式自动测试，尚未在真实设备验证收到 `roomDeleted` 后的恢复及旧令牌隔离。
- HTTPS/WSS、可达 ICE/TURN 的测试媒体环境，以及 Android/iOS 安装、登录、牌局、文字、语音的混合端整局记录。
