# stage17 本地试玩包 0.3.7 验收

日期：2026-09-28。源码提交和镜像构建提交均为 `1559785bc1255345e951e325f23b0617d0773091`；打包与镜像生成时工作区干净。本版包含 Gateway 与 Web 容器地址刷新修复、V17 语音撤销代码和本地离线镜像流水线。

## 制品与完整性

- [试玩归档](../release/0.3.7-local-playtest.tar.gz)：SHA-256 `36b9ee21a1c523d78e4e15a9a503d8411dbfcf3820adfbce4cfe91a6d06f5a4b`。归档校验通过；独立解包后 `SHA256SUMS` 所列 247 个文件全部通过。包内包括 Web 静态文件、三个 JAR、Android debug APK、iOS Simulator App、本地 Compose 配置、PostgreSQL 初始化 SQL 和本地密钥生成脚本，不含 `.env` 或签名私钥。
- [离线镜像归档](../release/0.3.7-local-playtest-images/images.tar.gz)：SHA-256 `4832913ac36a40e352ac98933571e6a670f381c35b12176a179b392420dfc527`。[镜像 manifest](../release/0.3.7-local-playtest-images/manifest.json) 记录包归档哈希、固定基础镜像 digest、四个镜像 ID、`linux/arm64` 平台及镜像标签 `0.3.7-local-playtest-1559785bc125`。镜像清单/归档校验通过，`docker load -i` 导入四个标签成功。
- 包内 Android APK 的 SHA-256 为 `0bf2c26e8ada7cdb8a6d4e319b96b12a831f999fd6f74e6a619db1c053645bde`，ZIP 结构完整。iOS Simulator `App.framework/App` 的 SHA-256 为 `2158e2ae62b9a8f60382200df23160a4fb4e959d792d91d9ec74e91befc028db`；`Runner.app` 为 ad-hoc 签名，无 TeamIdentifier。两个移动可执行文件与 0.3.6 的字节相同，本版改动集中在部署代理和打包工具。

## 构建与模拟器

运行 `PACKAGE_IOS_SIMULATOR=true node tools/package-release.mjs 0.3.7-local-playtest`（当前命令环境使用 Xcode 临时 `DEVELOPER_DIR`，见 [stage4 记录](verification-stage4-flutter.md)），退出码 0：Web 15 个 Vitest 文件/93 项通过并完成生产构建；Maven 数据库集成 `clean verify` 单元 59 项、集成 88 项通过，LiveKit 媒体集成测试跳过 1 项；Flutter 静态分析无问题、widget 33 项通过；Android debug 与 iOS Simulator 构建成功。

从**包内**安装 Android APK 到 API 36.1 模拟器，启动 `com.example.uno_app` 后显示首页，[截图](evidence/stage17-android-0.3.7-launch.png)。从**包内**安装 `Runner.app` 到 iPhone 17 Pro / iOS 26.5 模拟器，启动 `com.example.unoApp` 后显示首页，[截图](evidence/stage17-ios-0.3.7-launch.png)。这两项只证明安装与普通启动；本版包内移动 App 尚未完成整局或实际音频验收。

## 从独立解包目录部署

把归档解到 `/tmp/uno-037-standalone/`，逐文件复核后在解包目录运行 `node tools/init-local-env.mjs`，生成新的本地密钥。加载镜像归档，并在该目录合并包内 `compose.yaml`、`compose.auth-local.yaml`、`compose.images-local.yaml`，指定 manifest 中的镜像标签，用 `up --no-build -d --wait` 启动新数据库卷与 Web/Gateway/Identity/Game/Mailpit。四个业务容器都带 `1559785` 源码标签；Game 从空库成功迁移到 V17。实际验收：

- Gateway 路由与默认拒绝检查通过；经独立栈真实随机账号和 Mailpit 完成注册、验证、登录、更新资料、组房、双向文字、WebSocket ACK 与广播。
- 经典局与四人 2v2 均打完并核对双方/四人私有战绩、队伍文字隔离和房间复位。
- 启用包内 `compose.voice-local.yaml` 与 LiveKit 后，Game 重建而 Gateway 保持可用；浏览器四人对局、双方虚拟麦克风 PCM 到达、房外账号拒绝和 V17 撤销旧 JWT 测试通过。媒体地址仍是本机 `127.0.0.1`。
- 人为占用原 Gateway IP `172.18.0.6` 并重建为 `172.18.0.9`，Web 容器未重启；Web `/api/system/bootstrap` 和 `/api/auth/status` 均返回 200，经 Web `/ws/` 的文字订阅、发送、ACK 和广播复测通过。故障发现与候选修复过程见 [容器地址变更验收](verification-stage17-image-bundle-dns.md)。

验收后独立 Compose 容器已停止，测试数据库卷保留，原 stage17 本机媒体容器已恢复。归档和镜像保留在 `release/`，没有上传或对公网发布。

## 交付边界

Web 可在本地部署后完整试玩；Android/iOS 模拟器测试构建已有跨端整局证据，但本版**包内普通启动移动 App**只验收到首页。APK 是模拟器地址的 debug 包；iOS App 只能装模拟器，没有 IPA。尚未完成真机互听、iOS 实际音轨、跨网 ICE/TURN、HTTPS/WSS、正式签名、容量目标、生产密钥/备份和兼容回滚。镜像只验证了本机 `linux/arm64`，没有仓库 digest。当前仍不是可正式分发的完整版本。
