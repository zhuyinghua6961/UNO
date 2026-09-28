# 发布包目录

本目录用于存放构建产物，不提交二进制包到 Git 主历史。未来 CI 将相同产物上传到大仓库的 Release / 制品库；这里仍保留本地归档。若必须跟踪二进制，先确认 Git LFS 策略。

在干净的已提交工作区，从根目录执行：

```sh
node tools/package-release.mjs 0.2.0-local-preview
```

脚本先运行 Web 测试与构建、Maven PostgreSQL 集成测试、Flutter 静态检查和 widget 测试，再构建指向本机网关的 Android 模拟器 debug APK。从这次构建生成 Web 静态文件、三个后端 JAR、模拟器 APK、部署配置参考、manifest、SHA-256 和 tar.gz。需要本机 Docker、Dart/Flutter 与 Android 构建工具。工作区脏、构建中提交变化、同名目录或归档已存在时拒绝打包，避免把旧制品标为当前提交。浏览器/设备端到端验收仍需另行执行。

macOS 完整 Xcode 环境可设置 `PACKAGE_IOS_SIMULATOR=true`，让同一脚本再构建并归档 `flutter/ios-simulator/Runner.app`。它只供 iOS 模拟器安装，仅带模拟器 ad-hoc 签名，不是开发者签名 IPA；本机全局开发目录若指向 CommandLineTools，按 [stage4 记录](../docs/verification-stage4-flutter.md)在当前命令的 PATH 中设置临时 `xcrun` 包装脚本。

`0.1.0-scaffold` 是历史骨架包。新脚本产物是**本地预览包，不是生产安装包**。其中 Android debug APK 仅供模拟器连接 Compose 默认的宿主机 `10.0.2.2:28080` 网关，不能用于实机或公网；不包括 IPA、正式签名、账号密钥、用户数据。manifest 记录实际提交号及此次脚本执行的检查。新版包包含本地 Compose 配置、数据库初始化 SQL 和生成本机密钥的脚本；用另行生成的镜像包和 `--no-build` 可从解包目录启动。源码构建 Dockerfile 仍需原仓库。

从已验证的本地试玩包生成四个离线镜像，在干净提交中执行 `node tools/build-local-images.mjs <包名>`。脚本先核对原始 tar.gz、包内清单及全部文件 SHA-256，只复制包内三个 JAR、Web 静态文件和 Nginx 配置进入 Docker 上下文，使用固定 digest 的基础镜像。输出 `release/<包名>-images/images.tar.gz`、镜像 ID/架构/源码与构建提交 manifest 及 SHA256SUMS。可用 `docker load -i` 导入。实际部署须将 Compose 的 `UNO_LOCAL_IMAGE_TAG` 设置为镜像 manifest 中的 `tag`，合并 `compose.images-local.yaml` 并执行 `up --no-build`；不能把未生成的镜像当成发布物。`linux/arm64` 镜像包不等于其他架构镜像，也不等于仓库 digest。详情见 [本地镜像包验收](../docs/verification-stage17-image-bundle-dns.md)。

2026-09-24 实际生成的 `0.2.0-local-preview` 来源提交为 `3547f896c514e719b0e6c8d602264370e7f0a6c6`，校验结果与剩余门槛见 [stage17 本地预览包验收](../docs/verification-stage17-preview-package.md)。

2026-09-25 实际生成的 `0.3.2-local-playtest` 来源提交为 `2fe5ffa8e377c51379586b0ba17d06ae93e477a5`。它包含已在 Android 模拟器安装和启动的 debug APK；源码/归档校验、执行的测试及未覆盖范围见 [stage17 本地试玩包验收](../docs/verification-stage17-local-playtest.md)。使用时以包内 manifest 和 SHA-256 为准。

2026-09-28 实际生成的 `0.3.3-local-playtest` 来源提交为 `096a32ec8a4ab88f64047f171668666451e07afb`。归档和包内全部文件的 SHA-256 已复核，debug APK 在 Android API 36.1 模拟器安装并启动；检查与限制见 [0.3.3 本地试玩包验收](../docs/verification-stage17-local-playtest-0.3.3.md)。本地归档未进入 Git，也不是正式签名包。

同日生成的 `0.3.4-local-playtest` 来源提交为 `98142cf51212bb639958e3e6f643f00e29f755cd`，额外包含仅带模拟器 ad-hoc 签名的 iOS App。包内 App 已在 iPhone 17 Pro 模拟器安装启动，归档与 240 个文件校验通过；见 [0.3.4 本地试玩包验收](../docs/verification-stage17-local-playtest-0.3.4.md)。

同日生成的 `0.3.7-local-playtest` 来自 `1559785bc1255345e951e325f23b0617d0773091`，试玩归档 SHA-256 为 `36b9ee21a1c523d78e4e15a9a503d8411dbfcf3820adfbce4cfe91a6d06f5a4b`，`linux/arm64` 离线镜像归档 SHA-256 为 `4832913ac36a40e352ac98933571e6a670f381c35b12176a179b392420dfc527`。已从独立解包目录用 `--no-build` 启动并完成 Web 整局、文字和本机浏览器语音验收；包内 Android/iOS 应用已在模拟器安装启动。[详细记录](../docs/verification-stage17-local-playtest-0.3.7.md)说明其非正式签名和未完成项。

同日生成的 `0.3.8-local-playtest` 来自 `42336ca5b33468178a9fdbcc7f6c728c817d1767`，试玩归档 SHA-256 为 `cca160170ac55fc01f88c1be778776fd4e72bfe50f453f78a61f0d160d1376d9`，`linux/arm64` 离线镜像归档 SHA-256 为 `b2493a6fa6613e82803b0767f83f2b8ae6b4994a9c34473e29edbaff365f192c`。打包会拒绝残留的非受控素材文件；镜像参与了进行中牌局的升级与回退演练。见 [包验收](../docs/verification-stage17-local-playtest-0.3.8.md)和 [回退记录](../docs/verification-stage17-local-rollback-0.3.8.md)。

同日生成的 `0.3.13-local-playtest` 来自 `65820f647ba1459bd64d220f28e556c6b96c6e50`，试玩归档 SHA-256 为 `c5e4d35c54f6b470e9f8ade3bec677dc20d6d573fe4eb499ee503b7905fe2ac5`，`linux/arm64` 离线镜像归档 SHA-256 为 `a46efcdb9e4b67bfc588bde1ab8b621c5bfa486e6fd8e3318ed1da3b84b5fbeb`。它修复有效会话触发内部鉴权共享额度后收到 503 的机制，并在独立空库包内栈完成整局与 iOS 模拟器测试 App 恢复流程；见 [包验收](../docs/verification-stage17-local-playtest-0.3.13.md)。

同日生成的 `0.3.14-local-playtest` 来自 `e498aa184ddc73042b3fe69d08885521066ea90a`，试玩归档 SHA-256 为 `a679eaf9a0b6d2a08b99b00f49df39f222ac93a4a79408f3746a6d48cf08a8a4`，`linux/arm64` 离线镜像归档 SHA-256 为 `b076e4238bc61c420c68c11545beb9e6e59a2f24c41668640591053509e1ea12`。本版装入 iOS 队友语音远端音轨清理顺序修复；独立解包栈完成经典/2v2、浏览器双向音频及 iOS/Android 混合端整局，普通移动包安装启动通过。实际范围和限制见 [包验收](../docs/verification-stage17-local-playtest-0.3.14.md)。

移动端归档约定：`<version>/flutter/android/` 存明确标记用途的 APK/AAB，`<version>/flutter/ios-simulator/` 存可选的模拟器 App，`<version>/flutter/ios/` 留给经用户签名的 IPA。Android debug 包和 release 包必须区分；签名密钥永远不放本目录。发布版本号以 manifest 为准，现阶段 JAR 内部仍为 0.1.0-SNAPSHOT。
