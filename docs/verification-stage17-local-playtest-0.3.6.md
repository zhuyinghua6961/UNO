# stage17 本地试玩包 0.3.6 验收

日期：2026-09-28。本包将 V17 已发语音令牌的会话撤销检查、跨端真实界面回合自动化与 Android/Web 双向音轨订阅增量对应到同一干净源码提交。它仅适用于本机模拟器与本地服务。

## 来源与完整性

- 源码提交：`61c3d2e3cd64f27fd220c93ca6d2906d43bb3898`，打包时工作区干净。
- 构建命令：`PATH=/tmp/uno-xcode-tools:$PATH DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer PACKAGE_IOS_SIMULATOR=true node tools/package-release.mjs 0.3.6-local-playtest`，退出码 0。Xcode 临时选择方式见 [stage4 验收](verification-stage4-flutter.md)。
- 本地归档：[0.3.6-local-playtest.tar.gz](../release/0.3.6-local-playtest.tar.gz)，SHA-256 `d597d874345de8f65ff6fdcba8f7c3c66bf5ecaf9b464debb685734dbe9e555e`；包内 `manifest.json` 明确记录源码提交和执行的构建检查。
- 包内 Android debug APK SHA-256：`0bf2c26e8ada7cdb8a6d4e319b96b12a831f999fd6f74e6a619db1c053645bde`。iOS Simulator App 的 `App.framework/App` SHA-256：`2158e2ae62b9a8f60382200df23160a4fb4e959d792d91d9ec74e91befc028db`。完整 bundle 按包内逐文件清单校验；`codesign` 显示 `Signature=adhoc`、`TeamIdentifier=not set`。
- 归档 `.sha256` 通过；独立解包后 `SHA256SUMS` 所列 240 个文件全部通过，manifest 的 `sourceCommit` 与提交一致，Android APK ZIP 完整。

## 构建与安装

| 检查 | 结果 |
| --- | --- |
| Web | Vitest 15 个文件、93 项通过；生产构建成功 |
| 后端 | Maven `-Pdatabase-it clean verify` 通过；单元 59 项、集成 88 项，失败/错误 0；媒体集成测试跳过 1 项；Game JAR 含 V17 迁移 |
| Flutter | 静态分析无问题，widget 测试 33 项通过；Android debug 与 iOS Simulator 构建成功 |
| Android 包内安装 | API 36.1 模拟器安装、启动并显示首页，[截图](evidence/stage17-android-0.3.6-launch.png) |
| iOS 包内安装 | iPhone 17 Pro / iOS 26.5 模拟器安装、启动并显示首页，[截图](evidence/stage17-ios-0.3.6-launch.png) |

[V17 安全与混合端验收](verification-stage18-security-v17.md)在本包对应的业务源码上通过真实服务浏览器撤销恢复、Android/Web 双向音轨订阅，以及 Android/iOS 分别与 Web 的文字、2v2、结算和战绩整局。那些端到端测试使用测试构建 App 和本机 Compose 栈，**不是包内普通启动 App 的整局测试**；本包本轮对普通启动 App 只确认安装和首页。

## 限制

Android 为指向模拟器宿主机的 debug APK；iOS 为指向 localhost 的模拟器临时签名 App，没有 IPA 或 iPhone 可安装包。本包不含正式签名、同版不可变容器镜像、生产 HTTPS/WSS/TURN、密钥或回滚演练。目标真机互听、iOS 音轨、弱网与容量尚未验收，见 [0.3.6 综合矩阵](verification-stage18-0.3.6-candidate.md)。
