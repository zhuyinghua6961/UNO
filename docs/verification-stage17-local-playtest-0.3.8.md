# stage17 本地试玩包 0.3.8 验收

日期：2026-09-28。源码与镜像构建提交均为 `42336ca5b33468178a9fdbcc7f6c728c817d1767`，打包和镜像生成时工作区干净。本版应用逻辑沿用 0.3.7，补强了从跟踪素材生成 Web/Flutter 资源的打包检查。

- [试玩归档](../release/0.3.8-local-playtest.tar.gz) SHA-256：`cca160170ac55fc01f88c1be778776fd4e72bfe50f453f78a61f0d160d1376d9`。归档校验通过，包内 `SHA256SUMS` 的 247 个文件逐一校验通过。
- [离线镜像归档](../release/0.3.8-local-playtest-images/images.tar.gz) SHA-256：`b2493a6fa6613e82803b0767f83f2b8ae6b4994a9c34473e29edbaff365f192c`。[镜像清单](../release/0.3.8-local-playtest-images/manifest.json) 记录四个 `linux/arm64` 镜像 ID、源码/构建提交、基础镜像 digest 和原包哈希；清单与镜像归档校验通过，`docker load -i` 导入成功。标签为 `0.3.8-local-playtest-42336ca5b334`。
- 打包前 `tools/sync-assets.mjs` 从已跟踪的 `assets/ready` 与素材许可生成 Web 和 Flutter 资源并逐文件比对。额外放置一个旧素材文件时脚本明确失败；清除后重新运行通过。不能将遗留的本机忽略文件悄悄带入发布包。
- 实际执行 Web 15 个文件/93 项测试、Maven 单元 59 项/集成 88 项测试（LiveKit 媒体集成跳过 1 项）、Flutter 静态分析与 33 项 widget 测试，Android debug APK 和 iOS Simulator App 构建成功。Android APK SHA-256 `0bf2c26e8ada7cdb8a6d4e319b96b12a831f999fd6f74e6a619db1c053645bde`，iOS `App.framework/App` SHA-256 `2158e2ae62b9a8f60382200df23160a4fb4e959d792d91d9ec74e91befc028db`，与已在模拟器安装启动的 [0.3.7 对应文件](verification-stage17-local-playtest-0.3.7.md)逐字节相同。本轮没有重复安装 0.3.8 包内移动 App，更没有将首页安装证据当作整局证据。

0.3.8 镜像参与了 [进行中牌局的升级与回退验收](verification-stage17-local-rollback-0.3.8.md)。Web 本机完整玩法与浏览器语音、移动模拟器测试构建的前期证据见 [0.3.7 综合矩阵](verification-stage18-0.3.7-candidate.md)。本包仍非签名真机版本，不含生产 TLS/TURN、正式密钥、跨架构镜像或公网部署。
