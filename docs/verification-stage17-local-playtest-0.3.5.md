# stage17 本地试玩包 0.3.5 验收

日期：2026-09-28。本包包含 Android/Web 双向音轨订阅指示及对应自动化检查；仍是仅供本机模拟器和本地服务使用的试玩包。

## 来源与产物

- 干净源码提交：`dea3750b04494e7c0546f21b490daf8791759ad6`。
- 构建命令：`PATH=/tmp/uno-xcode-tools:$PATH DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer PACKAGE_IOS_SIMULATOR=true node tools/package-release.mjs 0.3.5-local-playtest`，退出码 0。临时 Xcode 选择方式见 [stage4 验收](verification-stage4-flutter.md)。
- 本地归档：[0.3.5-local-playtest.tar.gz](../release/0.3.5-local-playtest.tar.gz)，SHA-256 `543cff7abdb32aeef1ba5e98841166f23d755bdef2a34371e5806f6da02d0bbc`；同目录有 `.sha256` 与展开后的 `manifest.json`、`SHA256SUMS`。
- Android 模拟器 debug APK：SHA-256 `0bf2c26e8ada7cdb8a6d4e319b96b12a831f999fd6f74e6a619db1c053645bde`。
- iOS Simulator `Runner.app`：bundle ID `com.example.unoApp`；主执行文件 SHA-256 `4312dd4235f9b10ccd5ba3b58122ad92d1b4ad5208fb5d8a8677ebbb969b79ba`。完整 bundle 各文件由包内 `SHA256SUMS` 单独校验；`codesign` 显示 `Signature=adhoc`、`TeamIdentifier=not set`。

## 构建与设备核对

| 检查 | 结果 |
| --- | --- |
| Web | Vitest 15 个文件、93 项通过；生产构建成功 |
| 后端 | Maven 数据库集成构建通过；单元 59 项、集成 87 项，失败/错误 0；媒体集成测试跳过 1 项 |
| Flutter | 静态分析无问题，widget 测试 33 项通过 |
| Android | debug APK 构建成功；**从本包**安装到 API 36.1 `emulator-5554` 并启动，首页截图见[Android 证据](evidence/stage17-android-0.3.5-launch.png) |
| iOS | Simulator App 构建成功；**从本包**安装到 iPhone 17 Pro / iOS 26.5 模拟器并启动，首页截图见[iOS 证据](evidence/stage17-ios-0.3.5-launch.png) |
| 完整性 | 归档 `.sha256` 通过；另行解包后 240 个文件的 `SHA256SUMS` 全部通过，manifest 与工作目录副本一致，APK ZIP 无错误 |

[stage15 音轨验收](verification-stage15-voice.md)在相同源码提交前通过 Android/Web 双向订阅及混合端 2v2、文字、结算和战绩；但使用 Flutter 集成测试构建，不等于本包普通启动的 APK 已完成整局。iOS 当前源码只通过仅收听信令与 2v2 整局，尚无 iOS 收发音轨证据。

## 限制

Android APK 使用 debug 签名且 API 地址指向模拟器宿主机；iOS App 只有模拟器临时签名和 localhost API 地址，不能安装到 iPhone。本包没有 IPA、正式签名包、同版容器镜像、生产 HTTPS/WSS/TURN 或部署密钥；未在真机验证声音可听、麦克风/蓝牙切换、弱网、容量与回滚。具体门槛见 [0.3.5 候选矩阵](verification-stage18-0.3.5-candidate.md)。
