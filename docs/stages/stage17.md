# stage17 — Docker、环境与持续集成

[返回总索引](../README.md)

- 状态：进行中（0.3.7 同源码试玩包和四个离线镜像已从独立解包目录部署并验收；生产网络、签名、CI 和回滚待做）。
- 前置依赖：stage14、stage15、stage16；基础环境准备可提前进行。
- 目标：把完整功能部署到明确的测试环境，形成可复现的构建与交付过程。

## 任务清单

- [ ] 解决镜像/依赖获取问题，实际构建并启动 Web、Gateway、Identity、Game、数据和媒体基础设施。
- [ ] 区分开发/测试/生产配置，完善健康/就绪、启动依赖、资源限制和失败恢复。
- [ ] 在明确域名和网络环境配置 HTTPS/WSS、反向代理、可信来源和安全 Cookie。
- [ ] 配置 LiveKit 可达媒体地址、端口、防火墙和 TURN/TLS，并以实际跨网音频验证，不照搬 127.0.0.1。
- [ ] 落实数据库迁移、备份恢复、日志脱敏、基本告警和秘密注入，记录运行维护责任。
- [ ] 确认 Git 大仓库及三个分支用途后编写 CI 配置，运行三端测试、构建、依赖/秘密扫描。
- [ ] 定义可追溯版本、协议兼容矩阵、不可变镜像标识、移动包签名入口和制品目录。
- [ ] 优化发布脚本，拒绝未经确认的脏/过期产物，保留源码版本、构建来源和校验和。
- [ ] 演练失败发布、数据库兼容性检查和回滚；生产发布不由本阶段自动执行。

## 交付与范围

主要修改 deploy、tools、经确认的 CI 配置、各端构建脚本及运维文档。分支创建、提交、推送、外部服务器操作或实际发布仍须有明确授权。不要为实现三端分工创建三个嵌套 Git 仓库。

## 验收标准

- [ ] 干净环境按文档启动完整测试栈，状态接口与真实功能就绪一致。
- [ ] 实际镜像构建和容器联调通过，不再只提供 Compose 语法校验。
- [ ] Web/App 经测试服务地址完成登录、对局、文字和跨网队友音频。
- [ ] 密钥不出现在仓库、前端包、镜像层或 release；数据库备份可恢复。
- [ ] CI 对明确的源码版本生成对应制品；失败构建不能进入可发布产物集合。
- [ ] 回滚方法和移动端兼容要求可执行，不能回滚程序却留下不兼容数据库状态。

## 本阶段不包含

未经用户确认的公网正式开放、大规模多区域集群和自动购买云资源。测试部署通过仍需 stage18 综合验收。

## 完成记录

2026-09-23 在隔离 Compose 项目中完成 Web、Gateway、Identity、Game、PostgreSQL、Mailpit 镜像构建与健康启动；Gateway 基础检查和真实注册、邮件验证、登录、组房、双向房间文字联调通过。命令与未覆盖范围见 [容器栈增量验收](../verification-stage17-container.md)。LiveKit、TLS/TURN、CI、备份恢复与回滚仍待实施；记录不得包含秘密值。

2026-09-24 本地预览包增量：发布脚本从干净提交运行 Web 测试/构建和 Maven `clean verify`，拒绝复用旧编译产物及覆盖已有归档。实际生成 `release/0.2.0-local-preview.tar.gz`，manifest 指向提交 `3547f896c514e719b0e6c8d602264370e7f0a6c6`，包内文件和归档校验和均通过。见 [本地预览包验收](../verification-stage17-preview-package.md)。此包没有移动端安装包、镜像和生产部署配置；数据库集成、真机互听、TLS/TURN、备份恢复与 CI 仍待完成。

同日备份增量：隔离 Compose 栈的 identity/game 库分别完成压缩 dump、SHA-256 校验、唯一新库恢复与源/恢复表行数及迁移数核对，演练库已清理，源数据卷未动。见 [备份恢复演练](../verification-stage17-backup-restore.md)。这仅覆盖本地路径；生产加密异地备份、自动保留、应用切换和回滚仍待实施。

2026-09-25 本地试玩包增量：从干净提交 `2fe5ffa` 运行 Web、Maven 数据库集成、Flutter 静态检查/测试及 Android debug 构建，生成 `0.3.2-local-playtest` 归档；归档与包内校验和复核通过，实际 APK 在 Android 模拟器安装、启动并打开账号页。见 [本地试玩包验收](../verification-stage17-local-playtest.md)。该包的 Android 地址只适配本机 Compose 网关；本次未用此包完成整局，真机、iOS IPA、互听、TLS/TURN、CI 和生产交付仍待验收。

2026-09-28 双实例部署增量：从提交 `15ef055` 构建相同 Game 镜像的两个独立容器，连接同一 PostgreSQL V16。经真实随机账号、Mailpit、Gateway 和指定实例 WebSocket 验证跨容器文字、接管、HTTP 操作权、经典局、四人 2v2、结算与历史；命令、镜像标识和未覆盖范围见 [本地双 Game 容器联调](../verification-stage17-multi-instance.md)。生产负载均衡、容量、TLS/TURN 与真机仍待验收。

同日单实例停止补验：在隔离栈中停止持有对局连接的 Game A，Game B 接管后保持私有状态并继续出牌，重复命令幂等，A 重启就绪；普通双实例联调复跑通过。脚本对重启后 Docker 动态端口重新发现，结果与限制见 [本地双 Game 容器联调](../verification-stage17-multi-instance.md)。

同日从干净提交 `096a32e` 生成 `0.3.3-local-playtest`，Web、Maven 数据库集成、Flutter 检查与 Android debug 构建通过；归档/逐文件校验和与 APK ZIP 检查通过，包内 APK 在 API 36.1 模拟器安装并启动。见 [0.3.3 本地试玩包验收](../verification-stage17-local-playtest-0.3.3.md)。该归档仍没有 iOS IPA、正式签名、同版镜像或生产网络配置。

随后从干净提交 `98142cf` 生成 `0.3.4-local-playtest`，可选 iOS 模拟器构建加入同一脚本；Web/Maven/Flutter/Android/iOS 构建通过，归档及 240 个包内文件校验通过，从包内安装 iOS Simulator App 并打开首页。见 [0.3.4 本地试玩包验收](../verification-stage17-local-playtest-0.3.4.md)。iOS 真机 IPA、正式签名、同版镜像和跨网配置仍缺。

同日从干净提交 `dea3750` 生成 `0.3.5-local-playtest`，纳入 Android/Web 双向音轨订阅改动。Web 93 项、Maven 单元 59/集成 87 项、Flutter 33 项通过；归档与解包后 240 个文件校验通过；包内 Android debug APK 和 iOS Simulator App 均安装启动并截图。见 [0.3.5 本地试玩包验收](../verification-stage17-local-playtest-0.3.5.md)。仍没有真机 IPA、正式签名、同版镜像或生产网络配置。

同日从干净提交 `61c3d2e` 生成 `0.3.6-local-playtest`，纳入 V17 已发语音令牌的会话撤销修复。Web 93 项、Maven 单元 59/集成 88 项、Flutter 33 项通过；归档与解包后 240 个文件校验通过；包内 Android debug APK 和 iOS Simulator App 均安装启动并显示首页。见 [0.3.6 本地试玩包验收](../verification-stage17-local-playtest-0.3.6.md)。真机 IPA、正式签名、同版镜像和生产网络仍缺。

同日开始 [本地镜像包与容器地址变更验收](../verification-stage17-image-bundle-dns.md)：从已校验的 `0.3.6` 包生成四个带源码标签的离线镜像并完成账号、经典整局、四人 2v2 和文字 WebSocket 联调；发现 Game 重建后 Gateway 缓存旧 IP 返回 500、Gateway 换 IP 后 Web 代理返回 502。Gateway 与 Web DNS 刷新候选修复在强制 IP 变更后复测通过；生产网络、真机和回滚门槛未完成。

随后从修复提交 `1559785` 生成 [0.3.7 本地试玩包与镜像](../verification-stage17-local-playtest-0.3.7.md)。归档、247 个文件、离线镜像的校验和与 `docker load` 均通过；在独立解包目录生成新本地密钥和空数据库，用 `--no-build` 启动，完成账号、经典/2v2 整局、文字、浏览器语音撤销，并在 Game/Gateway 更换容器 IP 后复测。Android/iOS 包内应用已分别安装到模拟器并显示首页。仍缺生产网络、签名、真机音频、容量和兼容回滚。
