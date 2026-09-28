# stage17 本地试玩包 0.3.3 增量验收

日期：2026-09-28。此包仅用于本机开发和 Android 模拟器，不是正式发布候选安装包。

## 构建来源与校验

- 干净源码提交：`096a32ec8a4ab88f64047f171668666451e07afb`。
- 命令：`node tools/package-release.mjs 0.3.3-local-playtest`，退出码 0。
- 归档：`release/0.3.3-local-playtest.tar.gz`；SHA-256：`78ae31fa1152487cf63c6a3a9f1327504f8cfe610209ca537546e1f0908779de`。
- 包内 Android debug APK：`flutter/android/uno-emulator-debug.apk`；SHA-256：`adfd12faf5cebe6d768ec823861e6898f85730b30362b80f9f0b21981ec3bd56`。Flutter 产品源码相对上个本地包未改变，因此 APK 字节与 0.3.2 包相同；源码提交和完整归档不同。
- `.tar.gz.sha256`、包内 `SHA256SUMS` 全部通过；从 tar 提取的 manifest 与校验清单和工作目录副本相同；APK ZIP 完整性检查通过。包内 manifest 明确记录源码提交、执行的检查和排除项。

## 实际检查

| 检查 | 结果 |
| --- | --- |
| Web Vitest 与生产构建 | 15 个文件、93 项通过；构建成功 |
| Maven `-Pdatabase-it clean verify` | 单元测试 59 项、集成测试 87 项；失败/错误 0，依赖真实媒体容器的 `LiveKitVoiceMediaIT` 跳过 1 项 |
| Flutter 静态检查与 widget 测试 | `dart analyze lib test integration_test` 无问题；33 项通过 |
| Android 构建与安装 | debug APK 构建成功；API 36.1 `emulator-5554` 安装、启动，进程及首页画面确认，[截图](evidence/stage17-android-0.3.3-launch.png) |
| Android/Web 混合业务 | 同一 Flutter 源码的集成测试 App 与三个浏览器账号完成 2v2、双向房间/队伍文字、App 仅收听语音加入/退出、结算与四份战绩；详见 [stage15 验收](verification-stage15-voice.md) |

混合业务测试运行在原有 `uno-stage17-check` Compose 服务上；该测试 App 由 Flutter 集成测试构建，不是从归档中安装的普通启动 APK。归档 APK 只验证了安装和启动，不能把混合业务结果写成“归档 APK 完成整局”。

## 未通过的发布门槛

包内没有 iOS IPA、正式签名 Android 包或不可变镜像。没有在真机上安装或验收双向麦克风互听、弱网切换、跨网 TLS/TURN 与生产部署；也没有从这份包独立完成完整账号及整局操作。Android debug 包指向模拟器的 `10.0.2.2:28080`，不能直接用作手机或公网安装包。Flutter 的 `flutter_webrtc` 与 `livekit_client` 构建时仍提示未来 Kotlin Gradle Plugin 兼容性风险。后续新增 iOS Simulator App 的包见 [0.3.4 验收](verification-stage17-local-playtest-0.3.4.md)及 [stage18 候选矩阵](verification-stage18-0.3.4-candidate.md)。
