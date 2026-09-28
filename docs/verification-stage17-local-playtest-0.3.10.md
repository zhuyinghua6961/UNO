# stage17 本地试玩包 0.3.10 验收

日期：2026-09-28。源码提交 `2b594f5018c5bf9e43bab4e81655034bc39e5568`，打包与镜像构建时工作区均干净。此版给未签名 iOS Simulator Debug App 加入显式的进程内登录会话，并让打包脚本从锁文件安装 Web 依赖、在完整 Xcode 下重建 Flutter 的 iOS Swift 插件链接。

- [试玩归档](../release/0.3.10-local-playtest.tar.gz) SHA-256：`c0d2f91ca8f0cd04b82356d1cd1fd2d2718434518b48e9312b7d95db76cde23a`。外层归档与包内 `SHA256SUMS` 的 234 个文件全部校验通过。Android debug APK SHA-256：`02756c5a20cbd71db692b4436143e0f441b03ea601213b0057f81178c0ef6a24`；iOS `App.framework/App` SHA-256：`25c6d0c9a8d874cf3e3e2ea0efd927d43533f26475bf69a2db3193afb495481c`。
- [离线镜像归档](../release/0.3.10-local-playtest-images/images.tar.gz) SHA-256：`c8a4fd3264f52a46a134d17c39f98c336eddde7b3758f72b0d04e3db521b7697`。[镜像清单](../release/0.3.10-local-playtest-images/manifest.json)把四个 `linux/arm64` 镜像、源码提交与包哈希绑定，`SHA256SUMS` 校验及 `docker load -i` 导入均通过。标签为 `0.3.10-local-playtest-2b594f5018c5`。
- 打包期间 Web 15 个测试文件 / 93 项测试与构建、Maven 数据库集成 profile 全部通过；Dart 静态分析无问题，Flutter 33 项测试、Android debug APK 和 iOS Simulator 构建通过。第一次 0.3.10 尝试因旧 LiveKit Swift 临时链接失效而在 iOS 阶段失败；加入完整 Xcode 下的 `flutter pub get` 后，在 Web 阶段又发现当前工作区未安装 `node_modules`。两项可复现性修复提交后，从干净源码完整重跑成功；失败尝试没有生成同名发布目录。
- 从包内镜像、Compose 配置和新生成的本地随机密钥启动独立项目 `uno-package-0310`，使用本机端口 Web `58088`、Gateway `58080`、Mailpit `58025`。`up --no-build -d --wait` 等到 PostgreSQL、Identity、Game、Gateway、Web、Mailpit 全部 healthy。对这个解包栈执行 `SMOKE_FULL_MATCH=true SMOKE_TEAM_MATCH=true node tools/smoke-auth-chat.mjs`：注册/验证、App 登录、双向房间文字、经典整局及双方战绩、四人 2v2 整局及双方队伍文字隔离均通过。该独立栈没有启用 LiveKit，不作为语音验收证据。

## 包内普通 iOS App

在 iPhone 17 Pro / iOS 26.5 模拟器安装**归档中的** `Runner.app`，非 `integration_test` 注入版本。通过普通界面注册并用本地 Mailpit 验证测试邮箱、登录、看到账号和空战绩；创建双人经典房 `4YP2AC99HE`，另一 App API 客户端入房并准备，iOS 界面准备、开局，收到实时牌面。在对局 `bcf49e25-02f5-4267-af68-d8343cea1bcc` 中，包内 iOS 界面主动点“摸 1 张”，显示“操作已由服务器确认”，手牌 8→9。终止进程再启动后，界面回到未登录大厅，符合临时会话预期。该场没有由普通 iOS 界面打到终局；不据此宣称 iOS 包内整局、重连或战绩通过。

此 App 由 `--no-codesign` 构建，仅供本机模拟器试玩；进程退出即丢失登录。音效偏好仍提示安全存储不可用并使用默认设置。正式 iPhone 版本需要真实签名、Keychain 授权与目标设备验证，详见 [iOS 临时会话记录](verification-stage18-ios-ephemeral-preview.md)。

## 范围与清理

0.3.10 的 Android 包已构建但未重新进行完整普通 APK 界面验收；上一版 [0.3.9 Android 操作与战绩证据](verification-stage17-local-playtest-0.3.9.md)不能冒充本版结果。正式签名、真机互听、跨网弱网、生产 TLS/TURN、容量和新版本回滚仍未通过。Android 模拟器测试所用的临时 `28080`/`49000` 桥接已停止，原 `uno-stage17-check` Gateway 已恢复并返回 bootstrap；0.3.9 隔离栈已停机保留数据，0.3.10 隔离栈在完成验收后执行 `down` 并保留数据卷，由 [0.3.11 独立栈](verification-stage17-local-playtest-0.3.11.md)接替本机试玩端口。
