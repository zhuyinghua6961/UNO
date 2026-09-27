# stage17 本地试玩包增量验收

验收日期：2026-09-25（2026-09-27 复核归档校验和）。这是受控本地预览，不是 stage17 或 stage18 的最终验收。

## 来源与内容

- 源码提交：`2fe5ffa8e377c51379586b0ba17d06ae93e477a5`；打包时工作区干净，见包内 `manifest.json`。
- 产物：`release/0.3.2-local-playtest.tar.gz`，内含 Web 静态资源、Gateway/Identity/Game JAR、部署配置参考及 `flutter/android/uno-emulator-debug.apk`。Android APK 使用 `http://10.0.2.2:28080`，仅适配连接本机 Compose 网关的模拟器。
- 归档 SHA-256：`85ffd45e64322cdad1bb2fe92b2bd4d20bbbf2f7575e165c48b3947b9f5a0875`。
- APK SHA-256：`adfd12faf5cebe6d768ec823861e6898f85730b30362b80f9f0b21981ec3bd56`。
- 包内 `SHA256SUMS` 对所有文件的检查通过；归档的 `.sha256` 检查通过；APK ZIP 完整性检查通过。`release/` 的二进制产物由本机保留，不纳入 Git 提交。

## 构建和设备验证

从上述干净提交执行 `node tools/package-release.mjs 0.3.2-local-playtest` 成功。打包过程执行了：

| 检查 | 结果 |
| --- | --- |
| Web 测试和构建 | 15 个测试文件、93 个测试通过，构建成功 |
| Maven `-Pdatabase-it clean verify` | Reactor 构建成功；Identity、Game 与跨服务 PostgreSQL 集成测试通过；依赖实际媒体服务的 `LiveKitVoiceMediaIT` 跳过 1 个 |
| Flutter `dart analyze lib test integration_test` | 无问题 |
| Flutter widget/unit 测试 | 33 个通过 |
| Android `flutter build apk --debug --no-pub` | 实际生成 debug APK |

归档中的 APK 在 Android 模拟器 `emulator-5554` 上使用 `adb install -r` 安装成功，随后启动应用并打开账号登录页；[登录页截图](evidence/stage17-android-local-playtest-account.png)留存。运行中的本地 Compose 网关在 `127.0.0.1:28080` 对 `/api/system/bootstrap` 返回了账号、房间、对局、文字和语音功能开关。此设备检查只证明该 APK 能安装、启动并显示账号界面；本次**没有**用该归档 APK 完成账号登录或整局对战。

## 后续门槛

- 在明确的 Android/iOS 目标设备与网络上，验证安装、登录、组局、经典局、四人 2v2、文字、重连和战绩；已有模拟器端到端记录来自较早提交，不能充作这个归档的整体验收。
- 实际验证 Web 与 App 队友双向音频、麦克风拒绝与中断、弱网切换、跨网 LiveKit/TURN/TLS，以及跨服务实例事件同步。
- 生成并验证正式签名安装包、iOS IPA、不可变容器镜像和生产 HTTPS/WSS 配置；完成 CI、秘密扫描、回滚与恢复演练。此归档没有这些产物，也不能用于实机或公网。

本地 Android 构建通过，但 `flutter_webrtc` 和 `livekit_client` 对后续 Flutter Kotlin 构建方式提出兼容性警告；升级 Flutter 前须复核。当前机器的 Xcode/CocoaPods 环境尚不足以为此归档构建 iOS 安装包。
