# stage17 — Docker、环境与持续集成

[返回总索引](../README.md)

- 状态：进行中（0.3.18 本地包及镜像、独立空库整局与语音授权、业务路由就绪门槛和同 schema 本地镜像回退已验收；远端全分支 CI 已运行，生产网络、签名、制品发布与迁移兼容回退待做）。
- 前置依赖：stage14、stage15、stage16；基础环境准备可提前进行。
- 目标：把完整功能部署到明确的测试环境，形成可复现的构建与交付过程。

## 任务清单

- [ ] 解决镜像/依赖获取问题，实际构建并启动 Web、Gateway、Identity、Game、数据和媒体基础设施。
- [ ] 区分开发/测试/生产配置，完善健康/就绪、启动依赖、资源限制和失败恢复。
- [ ] 在明确域名和网络环境配置 HTTPS/WSS、反向代理、可信来源和安全 Cookie。
- [ ] 配置 LiveKit 可达媒体地址、端口、防火墙和 TURN/TLS，并以实际跨网音频验证，不照搬 127.0.0.1。
- [ ] 落实数据库迁移、备份恢复、日志脱敏、基本告警和秘密注入，记录运行维护责任。
- [ ] 按已确认的 `master` 加功能分支策略运行 CI；完成远端三端测试/构建、依赖和秘密扫描。
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

同日从提交 `42336ca` 生成 [0.3.8 包和镜像](../verification-stage17-local-playtest-0.3.8.md)，打包先从跟踪素材重建并核对 Web/Flutter 资源，旧文件会阻断发布；所有自动测试、双平台模拟器构建与 247 个包内文件校验通过。[同 schema 本地回退演练](../verification-stage17-local-rollback-0.3.8.md)使进行中经典局经历 0.3.7、0.3.8、再回 0.3.7，手牌和版本保留，终局战绩一致，停机后 V17 数据仍在。新迁移的兼容回退、失败发布和生产网络仍待完成。

同日从提交 `02c0d0f` 生成 [0.3.9 包和镜像](../verification-stage17-local-playtest-0.3.9.md)。Identity/Game/Gateway/Web 的本地健康检查现在依次覆盖数据库和实际业务路由，`up --wait` 在故障时阻断；停 Game 后 Gateway readiness 返回 503 而 liveness 保持 200，重启后全部恢复健康。[就绪门槛故障注入](../verification-stage17-compose-readiness.md)和解包部署均已通过。生产媒体、SMTP、TLS/TURN 和自动恢复仍没有纳入这套门槛。

同日从提交 `2b594f5` 生成 [0.3.10 包和镜像](../verification-stage17-local-playtest-0.3.10.md)。打包脚本现从锁文件安装 Web 依赖，并在选定完整 Xcode 下重建 iOS Swift 插件链接；归档 234 个文件和四镜像校验通过。独立解包栈完成账号、文字、经典和 2v2 整局。包内普通 iOS Simulator App 也完成登录、开局和服务端确认的摸牌，但使用进程内临时会话；正式签名、真机与生产媒体仍缺。

同日从提交 `afa7c53` 生成 [0.3.11 包和镜像](../verification-stage17-local-playtest-0.3.11.md)，将 iOS Debug 临时音效偏好入包。包内普通模拟器 App 登录、开局、出牌和音效切换通过；四个同源码镜像从独立空库完成经典/2v2 整局和战绩。正式设备与生产网络门槛仍缺。

同日从提交 `7ba0bbd` 生成 [0.3.12 包和镜像](../verification-stage17-local-playtest-0.3.12.md)，修复自定义 Web 端口下的浏览器 Origin 默认值。包内独立空库栈在 `59088` 端口完成真实浏览器注册、断网恢复、继续出牌，以及经典/2v2 整局 API 烟测；正式网络与真机门槛仍缺。

同日从提交 `65820f6` 生成 [0.3.13 包和镜像](../verification-stage17-local-playtest-0.3.13.md)，修复内部鉴权共享额度误伤有效游戏会话的机制。归档、247 个包内文件、四个镜像及导入均校验；独立空库栈完成经典/2v2 整局，iOS 模拟器测试 App 完成前后台恢复、再次出牌与结算。正式网络、签名真机和容量验收仍缺。

随后从提交 `e498aa1` 生成 [0.3.14 包和镜像](../verification-stage17-local-playtest-0.3.14.md)，将 iOS 远端语音离会顺序修复纳入移动包。归档、247 个包内文件、四个镜像及导入校验通过；独立空库语音栈完成经典/2v2、浏览器双向音频，双平台模拟器测试 App 各与包内 Web 完成语音和 2v2 整局。普通模拟器安装包已安装启动，完整界面整局与真机发布仍待验收。

同日添加 [GitHub Actions 验证流水线](../verification-stage17-ci.md)，对任意分支 push/PR 做 Web、Maven 数据库集成和 Android debug 构建，手动触发 iOS 模拟器构建；外部 Action 固定完整 SHA，`actionlint` 静态检查通过。尚未推送或在远端 Runner 实跑，CI 发布门槛保持未通过。

2026-09-29 从提交 `d79cc12` 生成 [0.3.15 本地试玩包与四镜像](../verification-stage17-local-playtest-0.3.15.md)，247 个包内文件及两个归档校验通过；独立空库语音栈健康，经典与 2v2 整局、文字及语音授权烟测通过。Android 普通 APK 在同版栈完成建房、开局和一次服务端确认出牌，整局结算与真机发布仍待验收。

同日从提交 `ad566a2` 生成 [0.3.16 本地试玩包与四镜像](../verification-stage17-local-playtest-0.3.16.md)，将小屏首回合操作区自动可见修复纳入包内。247 个包内文件及两个归档校验通过；独立空库语音栈健康，经典与 2v2 整局、文字及语音授权烟测通过。普通 Android APK 在同版栈完成登录、建房、开局和一次服务端确认摸牌，整局结算与真机发布仍待验收。

同日从功能分支 `codex/match-context-at-controls` 快进合入提交 `1815450`，生成 [0.3.17 本地试玩包与四镜像](../verification-stage17-local-playtest-0.3.17.md)。小屏操作区增加桌面牌、生效颜色、当前玩家与倒计时；247 个包内文件及两个归档校验通过，独立空库语音栈完成经典/2v2 整局、文字及语音授权烟测。普通 Android 界面确认紧凑布局；同版普通安装包整局、真机发布和远端 CI 仍待验收。

同日从提交 `1ee82bb` 生成 [0.3.18 本地试玩包与四镜像](../verification-stage17-local-playtest-0.3.18.md)。PostgreSQL 时间精度与 Flutter 房间入口修复进入包内；247 个包内文件及两个归档校验，离线镜像导入、独立空库语音栈经典/2v2 整局、文字、语音授权和浏览器断网恢复通过。Android 同源码集成测试 App 在同版栈完成经典整局；包内普通 APK 完成账号、建房、开局，并显示 API 客户端推进后的同局战绩。GitHub Actions 已在功能分支和 `master` 的 push 自动执行且修正后均通过；逐次结果见 [CI 记录](../verification-stage17-ci.md)。普通 APK 双方界面全程整局、真机和生产网络仍待验收。
