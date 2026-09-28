# stage17 本地试玩包 0.3.4 增量验收

日期：2026-09-28。本地归档增加可安装的 iOS Simulator App；其可执行文件只有模拟器临时签名，没有开发者设备签名或 IPA。

## 来源与产物

- 干净源码提交：`98142cf51212bb639958e3e6f643f00e29f755cd`。
- 命令：`PATH=/tmp/uno-xcode-tools:$PATH DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer PACKAGE_IOS_SIMULATOR=true node tools/package-release.mjs 0.3.4-local-playtest`，退出码 0。临时工具选择方式见 [stage4 验收](verification-stage4-flutter.md)。
- 归档：`release/0.3.4-local-playtest.tar.gz`，SHA-256 为 `eb1d77a56458cc2fe32f5ff45348cef560b9a059f708ada7720dc3cdeab8a617`。
- Android debug APK：`flutter/android/uno-emulator-debug.apk`，SHA-256 为 `adfd12faf5cebe6d768ec823861e6898f85730b30362b80f9f0b21981ec3bd56`。
- iOS Simulator App：`flutter/ios-simulator/Runner.app`，bundle ID `com.example.unoApp`，主执行文件 SHA-256 为 `4312dd4235f9b10ccd5ba3b58122ad92d1b4ad5208fb5d8a8677ebbb969b79ba`；完整 bundle 的文件逐个列在 `SHA256SUMS`，并非单个 IPA 文件。

归档 `.sha256` 通过；独立复算包内 240 个文件的 SHA-256 全部一致；从 tar 提取的 manifest 与 `SHA256SUMS` 和工作目录副本相同；Android APK ZIP 完整。包内 manifest 记录了上述源码提交、实际执行的检查和未包含产物。

## 构建与安装

| 检查 | 结果 |
| --- | --- |
| Web Vitest/生产构建 | 15 个文件、93 项通过，构建成功 |
| Maven `-Pdatabase-it clean verify` | 单元 59 项、集成 87 项，失败/错误 0；`LiveKitVoiceMediaIT` 跳过 1 项 |
| Flutter | 静态检查无问题，widget 测试 33 项通过 |
| Android | debug APK 构建成功；同一产品源码的 0.3.3 包内 APK 曾在 API 36.1 模拟器安装启动，[证据](verification-stage17-local-playtest-0.3.3.md) |
| iOS | `flutter build ios --simulator --no-codesign` 成功；**从 0.3.4 包内**安装 `Runner.app` 到 iPhone 17 Pro / iOS 26.5 模拟器，应用进程启动且首页实际显示，[截图](evidence/stage17-ios-0.3.4-launch.png) |

当前 Flutter 源码还通过了 [Android/iOS 与 Web 混合端 2v2、文字和仅收听语音验收](verification-stage15-voice.md)。这些测试使用 Flutter 集成测试构建的 App 与原有本地 Compose 服务，不能声称 0.3.4 包内普通启动 App 已完成同一整局。

## 未达发布条件

两个平台产物只适用于本机模拟器：Android 为 debug APK，iOS 为仅有 ad-hoc 模拟器签名的 App。`codesign` 显示 `TeamIdentifier=not set`；没有 iPhone IPA、正式签名 Android 包、同版不可变容器镜像或生产 HTTPS/WSS/TURN；没有目标真机安装、双向麦克风互听、弱网/网络切换、容量及回滚验收。参见 [stage18 候选矩阵](verification-stage18-0.3.4-candidate.md)。

包内 manifest 用 “unsigned” 泛指没有开发者分发签名；对实际可执行文件的 `codesign` 检查表明它带有模拟器使用的 ad-hoc 签名，不能据此安装到 iPhone。
