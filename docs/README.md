# UNO 实现阶段总索引

更新：2026-09-28。本文是后续实现的统一入口，按 **stage1 → stage18** 拆分，不代表已经执行所有阶段。

技术约束：Vue Web、Spring Cloud 后端、Flutter Android/iOS、Docker 部署；业务目录为 backend、web、flutter、deploy、release。范围包括独立账号、经典对局、2v2、房间/队伍文字、队友语音和发布交付。

## 1. 当前进度

- 已有可构建的三端骨架、素材、状态接口、通信权限基础、16 个骨架测试和开发归档。
- GitHub 私有仓库 `zhuyinghua6961/UNO` 已创建并关联 origin；2026-09-07 按用户授权进行首次本地提交（以 git log 为准），未推送，三个分支的名称和用途仍未确认。
- **stage1部分完成、stage2已完成（本地）、stage3技术项已实现但保留登录渠道确认、stage4部分完成、stage5部分完成、stage6部分完成、stage7部分完成、stage8已完成（本地验收）、stage9–stage15进行中（stage13/14 的撤销换房已本机验收，人工听感仍待验收）、stage16经典/团队战绩与昵称进行中、stage17本地容器增量进行中、stage18验收矩阵进行中。**Web 分别与 Android/iOS 模拟器完成四人 2v2 整局和房间/队伍文字互发；本机浏览器双向收到虚拟麦克风音频样本。Android/Web 模拟器双向音轨订阅、iOS 模拟器仅收听信令已验收；真实麦克风互听与真机弱网仍待验收。
- stage1 的工程约定和复现指南已补充，产品规则/登录渠道/三个分支仍待确认；stage2 按 [工程基线](engineering-baseline.md) 中的明确依赖例外推进。
- Docker Hub 网络阻碍与 Flutter 分析命令差异见 [骨架验收记录](verification.md)；本地容器栈的后续实测见 [stage17 增量验收](verification-stage17-container.md)。

## 2. 阶段目录

| 阶段 | 实现主题 | 交付后新增的能力 | 前置依赖 | 状态 |
| --- | --- | --- | --- | --- |
| [stage1](stages/stage1.md) | 工程基线与需求收口 | 明确范围、运行方式、规则决策与安全底线 | 无 | 部分完成 |
| [stage2](stages/stage2.md) | 数据库与迁移基础 | 账号和业务数据有可靠存储与版本迁移 | stage1（见依赖例外） | 已完成（本地） |
| [stage3](stages/stage3.md) | 后端账号与会话 | 真实注册、登录、验证、找回与退出 | stage2 | 部分完成（技术项已实现，渠道待确认） |
| [stage4](stages/stage4.md) | Web／App 账号闭环 | 两端可用同一账号体系登录与恢复会话 | stage3 | 部分完成（Flutter设备与跨端验收待做） |
| [stage5](stages/stage5.md) | 房间与组队准备 | 邀请、加入、准备、房主移交与 2v2 席位 | stage4 | 部分完成（设备界面待验收） |
| [stage6](stages/stage6.md) | 经典 UNO 规则引擎 | 可独立测试的发牌、出牌、罚牌、质疑、胜负 | stage1 | 部分完成（产品边界待确认） |
| [stage7](stages/stage7.md) | 实时对局后端 | 服务器裁决、私有视图、命令去重与状态同步 | stage3、stage5、stage6 | 部分完成（跨实例广播与完整断线策略待 stage12） |
| [stage8](stages/stage8.md) | Web 经典对局 | 网页多个真实账号可以打完经典局 | stage7 | 已完成（本地验收） |
| [stage9](stages/stage9.md) | Flutter 经典对局 | App 可以和 Web 玩家同桌对战 | stage7 | 进行中（Android/Web 触控整局、iOS 模拟器真实开局与结算已验收） |
| [stage10](stages/stage10.md) | 四人 2v2 对局 | 两端支持队伍比赛和队伍结算 | stage8、stage9 | 进行中（Web 与 Android/iOS 模拟器混合整局已验收） |
| [stage11](stages/stage11.md) | 房间／队伍文字 | 真实发送、接收、限流、历史补偿与隔离 | stage5、stage7、stage10 | 进行中（实时文字、跨实例补偿与运营撤回已接通；本地双容器已联调，真机弱网及隐私说明待做） |
| [stage12](stages/stage12.md) | 重连与对局生命周期 | 刷新、掉线、超时、退房和重复连接可控 | stage10、stage11 | 进行中（跨实例接管、HTTP 归属校验、中断与快照补偿；本地双容器已联调，设备故障待做） |
| [stage13](stages/stage13.md) | 队友语音后端 | 受限语音凭证、独立队伍频道与退出撤销 | stage3、stage10、stage12 | 进行中（准入、撤销换房本机验收；容量待验） |
| [stage14](stages/stage14.md) | Web 队友开麦 | 浏览器主动授权、开麦、静音与退出 | stage8、stage13 | 进行中（本机实连与换房通过，人工听感待验） |
| [stage15](stages/stage15.md) | Flutter 队友开麦 | App 队友语音与 Web/App 互通 | stage9、stage13、stage14 | 进行中 |
| [stage16](stages/stage16.md) | 个人中心与对局记录 | 真实资料、偏好、个人/队伍战绩和历史 | stage10、stage12 | 进行中（经典/团队/中断历史、分模式统计与昵称；其他偏好待做） |
| [stage17](stages/stage17.md) | Docker、环境与持续集成 | 可复现的测试部署、TLS/TURN、备份和构建流水线 | stage14、stage15、stage16 | 进行中（本地容器栈已验收） |
| [stage18](stages/stage18.md) | 综合验收与发布 | 安全、弱网、容量、真机验证和可追溯安装制品 | stage17 | 进行中（候选矩阵建立，发布门槛未通过） |

阶段编号是推荐组织顺序，实际依赖以上表为准。例如 stage6 可在账号开发期间独立推进；stage8/stage9、stage14/stage15 是同一后端能力的不同端交付；stage16 不必等待语音。这不是自动开启并行代理或额外任务的授权。

## 3. 可体验里程碑

| 里程碑 | 必须完成 | 玩家实际能体验什么 |
| --- | --- | --- |
| M0 开发底座 | stage1–stage2 | 开发者可复现环境，尚不可玩 |
| M1 真实组局 | stage3–stage5 | 两端登录、邀请、进入等待房间 |
| M2 经典跨端对战 | stage6–stage9 | Web/App 在正常网络下打完经典局；尚未完成完整弱网验收 |
| M3 组队＋文字 | stage10–stage12 | 2v2、文字交流和基本重连可用，适合受控测试 |
| M4 队友开麦 | stage13–stage15 | Web/App 队友语音互通，非队友不能进入 |
| M5 内测交付 | stage16–stage18 | 资料战绩、部署、质量验收与正式内测制品齐备 |

完成阶段不自动等于开放公网。注册、聊天、语音涉及的身份校验和安全约束必须在对应阶段实现，stage18 是综合验证，不是首次补安全。

## 4. 文档使用与阶段完成规则

每个 stage 文件包含：目标、前置条件、任务清单、涉及目录/协议、验收场景、不包含内容、完成记录。

统一状态：**未开始 / 进行中 / 部分完成 / 待验收 / 已完成 / 阻塞**。有骨架但仍缺关键项用“部分完成”，代码写完未验证用“待验收”，不得根据估计随意标完成。

执行一个阶段时：

1. 先核对前置阶段、现有代码和本阶段决策项，不重新创建已有骨架。
2. 更新索引与阶段文件状态；发现需求变化先修改方案，再修改实现。
3. 按任务实现并补相应测试，维护真实协议与功能开关；仅在功能可用后开放 UI 入口。
4. 验收通过后填写日期、实际变更、命令/报告、结果、遗留问题；所有必须项完成才标“已完成”。
5. 同步索引、架构/协议文档和验收记录。提交、推送、创建分支或发布仍需明确授权，不由阶段状态触发。

阶段完成记录格式：

```text
验收日期：
实际交付：
验证命令与结果：
证据位置：
遗留问题／明确排除项：
版本或提交：没有提交则写“未提交”，不得伪造
```

不承诺固定工期；待确认登录渠道、发布平台、网络和并发目标后，再给每个阶段估时。按阶段推进不等于对公网可用性作保证。

## 5. 前置决策与默认方向

| 问题 | 当前默认方向 | 必须确定的阶段 |
| --- | --- | --- |
| “两人组队”的含义 | 四位真人组成两支双人队伍 | stage1，最迟 stage5 前 |
| 团队获胜规则 | 任意一名队员先出完，整队获胜；不自动共享手牌 | stage1，最迟 stage10 前 |
| 登录渠道 | 邮箱＋密码，Web Cookie 与 App 凭证统一映射 userId | stage3 前 |
| 断线与超时 | 先明确定时和中断策略，不默认机器人接管 | stage7 前，stage12 完整验收 |
| 三个 Git 分支 | 名称和用途待用户指定；已有远程 origin | 远程协作/CI 操作前 |
| Flutter 目标 | Android＋iOS；前台语音优先 | stage9/stage15 前 |
| 发布地区与容量 | 未确定，不以骨架性能冒充承载能力 | stage17 前 |
| 用户内容管理 | 30 天保存、举报/禁言及运营撤回已实现；申诉和正式隐私说明待完成 | stage11/stage18 前 |

未定项允许用清楚标注的本地验证方案推进无关任务；涉及真实账户、公开发布、签名和远程仓库操作时不得擅自替用户作不可逆决定。

## 6. 需求到阶段的对应关系

- 用户登录：stage2–stage4；会话撤销在 stage12/stage13 联动验证。
- 房间与 UNO：stage5–stage10，可靠性由 stage12 补全。
- 游戏内文字：stage11；必须沿用 stage3/stage5 的身份和成员关系。
- 队友直接开麦：stage10 的队伍关系 → stage13 的准入 → stage14/stage15 的双端体验。
- Docker 部署：stage1/stage2 的本地基础 → stage17 的环境交付 → stage18 的综合验收。
- release 发布包：stage17 定义产物流水线，stage18 输出实际验收后的制品。
- 私有仓库/三个分支：stage1 记录与确认，stage17 经授权配置 CI；不按三个目录各建一个 Git 仓库。

## 7. 其他文档入口

- [账号与会话](authentication.md)：当前后端认证协议、开关、Cookie/App凭证与本地邮件。
- [跨服务身份](service-authentication.md)：game与identity的核验协议、默认TLS、开发配置和失败行为。
- [跨服务验收记录](verification-service-auth.md)：真实三进程联调与异常场景。
- [stage3验收记录](verification-stage3.md)：本轮实际验证与剩余范围。
- [stage8 Web 验收](verification-stage8-web.md)：两个真实账号完整经典局、第二局与桌面/窄屏证据。
- [stage9 Flutter 增量验收](verification-stage9-flutter.md)：App 牌桌、实时协议、双平台构建和设备限制。
- [stage9 iOS 模拟器增量验收](verification-stage9-ios.md)：真实本地服务的账号、组房、回合动作、整局结算与第二局。
- [stage11 房间文字增量验收](verification-stage11-room-text.md)：消息权限、幂等、游标、双端界面与未验收范围。
- [stage11 实时文字增量验收](verification-stage11-realtime.md)：WebSocket 订阅、接收权限、双端客户端与网关实测。
- [stage11 跨实例文字补偿验收](verification-stage11-cross-instance-text.md)：数据库轮询补发、队伍隔离、换队与乱序发布测试。
- [stage11 内容治理增量验收](verification-stage11-moderation.md)：举报权限、限时禁言、账号停用与运营脚本实测。
- [stage11 消息撤回增量验收](verification-stage11-redaction.md)：撤回标记、双端缓存核对与网关运营实测。
- [文字举报与运营处理](chat-moderation.md)：30 天保存、受限运营 SQL 与未覆盖范围。
- [stage12 对局生命周期增量验收](verification-stage12-lifecycle.md)：连续漏回合中断、轮间自动推进、历史语义与剩余弱网验收。
- [stage12 跨实例接管验收](verification-stage12-cross-instance-takeover.md)：数据库归属锁、双 Game 实例的旧连接撤权与新连接出牌。
- [stage13–14 队友语音增量验收](verification-stage13-14-voice.md)：LiveKit 准入、队伍隔离、WebRTC 实连与撤销换房。
- [stage15 Flutter 语音增量验收](verification-stage15-voice.md)：App SDK/UI、自动化生命周期与未完成的真机互听。
- [stage16 经典战绩增量验收](verification-stage16-history.md)：终局快照、私有历史、双端账号页与容器整局核对。
- [stage16 统计增量验收](verification-stage16-stats.md)：经典/团队胜负与中断的独立汇总、双端展示及未覆盖范围。
- [stage17 容器栈增量验收](verification-stage17-container.md)：独立 Compose 环境、镜像构建与真实账号/房间/文字流程。
- [stage17 双 Game 容器联调](verification-stage17-multi-instance.md)：两个独立 JVM 的文字补偿、接管、经典/2v2 整局与战绩。
- [stage17 本地预览包验收](verification-stage17-preview-package.md)：干净提交构建、清单、校验和与未覆盖的发布门槛。
- [stage17 本地试玩包验收](verification-stage17-local-playtest.md)：包含 Android 模拟器 APK 的归档、全量构建检查和设备安装启动证据。
- [stage17 0.3.4 本地试玩包验收](verification-stage17-local-playtest-0.3.4.md)：同一归档包含 Android debug APK 与 iOS Simulator App，两个平台的安装启动及校验记录。
- [stage17 0.3.5 本地试玩包验收](verification-stage17-local-playtest-0.3.5.md)：同源码的 Android/Web 双向音轨增量、双平台模拟器包安装启动及归档校验。
- [stage17 0.3.6 本地试玩包验收](verification-stage17-local-playtest-0.3.6.md)：V17 语音撤销修复入包，双平台模拟器安装启动与归档校验。
- [stage17 镜像包与容器地址变更验收](verification-stage17-image-bundle-dns.md)：从镜像包复现代理旧 IP 故障，修复并强制换址复测。
- [stage17 0.3.7 本地试玩包验收](verification-stage17-local-playtest-0.3.7.md)：同源码包和离线镜像在独立解包目录启动、整局/语音/换址复测，以及双平台模拟器包安装。
- [stage17 0.3.8 本地试玩包验收](verification-stage17-local-playtest-0.3.8.md)：受控素材重建、同源码包/镜像校验及与上一版移动可执行文件比对。
- [stage17 Compose 就绪门槛验收](verification-stage17-compose-readiness.md)：真实路由探针、Game/Identity 停服和恢复时的健康状态。
- [stage17 0.3.9 本地试玩包验收](verification-stage17-local-playtest-0.3.9.md)：包内 Compose 故障注入、Android 普通 APK 的界面操作与战绩，以及 iOS Keychain 缺口。
- [stage17 0.3.10 本地试玩包验收](verification-stage17-local-playtest-0.3.10.md)：锁定依赖重建、同源码镜像与解包整局，以及包内 iOS 普通 App 登录和摸牌。
- [stage17 0.3.11 本地试玩包验收](verification-stage17-local-playtest-0.3.11.md)：同源码包/镜像、独立解包整局及包内 iOS 普通 App 出牌和音效修复。
- [stage17 0.3.12 本地试玩包验收](verification-stage17-local-playtest-0.3.12.md)：自定义端口浏览器注册、断网恢复、同版包/镜像及独立解包整局。
- [stage17 0.3.13 本地试玩包验收](verification-stage17-local-playtest-0.3.13.md)：内部鉴权共享额度修复、同版包/镜像、独立空库整局与 iOS 模拟器恢复。
- [stage17 GitHub Actions 验证流水线](verification-stage17-ci.md)：Web、后端、Android 常规检查与手动 iOS 模拟器构建；远端 Runner 待验收。
- [stage17 本地镜像回退验收](verification-stage17-local-rollback-0.3.8.md)：进行中牌局跨两个镜像版本切换、回退、结算及停机后 V17 数据核对。
- [stage18 0.3.4 候选验收矩阵](verification-stage18-0.3.4-candidate.md)：逐项列出 Web/Android/iOS 当前证据和仍未达到的发布门槛。
- [stage18 0.3.5 候选验收矩阵](verification-stage18-0.3.5-candidate.md)：上一版本地试玩能力与目标真机发布门槛的对照。
- [stage18 0.3.6 候选验收矩阵](verification-stage18-0.3.6-candidate.md)：当前版本的跨端、安全与发布门槛对照。
- [stage18 0.3.7 候选验收矩阵](verification-stage18-0.3.7-candidate.md)：同源码包和镜像的本地可玩证据、移动包与正式发布缺口。
- [stage18 0.3.8 候选增量矩阵](verification-stage18-0.3.8-candidate.md)：素材完整性、同 schema 回退和剩余真机发布缺口。
- [stage18 0.3.9 候选增量矩阵](verification-stage18-0.3.9-candidate.md)：部署就绪、Android 包内玩法增量和 iOS 普通 App 缺口。
- [stage18 0.3.10 候选增量矩阵](verification-stage18-0.3.10-candidate.md)：iOS 本机模拟器包内试玩及仍未达到的签名真机与发布门槛。
- [stage18 0.3.11 候选增量矩阵](verification-stage18-0.3.11-candidate.md)：包内 iOS 音效修复、独立解包整局与真机发布缺口。
- [stage18 0.3.12 候选增量矩阵](verification-stage18-0.3.12-candidate.md)：浏览器自定义端口和断网恢复，以及仍缺的真机发布门槛。
- [stage18 0.3.13 候选增量矩阵](verification-stage18-0.3.13-candidate.md)：身份链路修复与本机可玩证据，以及仍缺的真机发布门槛。
- [stage12 iOS 前后台恢复补验](verification-stage12-ios-lifecycle.md)：模拟器真实牌桌暂停、权威动作、恢复后 UI 出牌和结算；系统级切网仍待验收。
- [stage18 iOS 模拟器临时会话验收](verification-stage18-ios-ephemeral-preview.md)：未签名包的 Keychain 错误、Debug 试玩修复和正式签名边界。
- [stage18 安全与 V17 语音撤销补验](verification-stage18-security-v17.md)：房外账号拒绝、私有手牌、断开后的旧令牌撤销和双平台混合端复测。
- [stage17 隔离备份恢复演练](verification-stage17-backup-restore.md)：两服务库的压缩备份、校验、新库恢复与数据核对。
- [Flutter账号增量验收](verification-stage4-flutter.md)：自动测试、原生构建与未完成的设备/跨端验证。
- [工程基线与未决事项](engineering-baseline.md)：约定、前置决策、环境差异和复现路径。
- [数据库、迁移与恢复](persistence.md)：配置、数据关系、集成测试和备份恢复步骤。
- [stage2 验收记录](verification-stage2.md)：当前数据库交付及实际验证范围。
- [当前整体设计 v0.2](architecture-v0.2.md)：设计依据与系统职责。
- [协议与实现状态](contracts/README.md)：已实现和计划端点的区别。
- [Git 与发布约定](git-and-release.md)：私有仓库和分支待办。
- [当前验收记录](verification.md)：已有骨架验证，不作为未来阶段通过证据。
- [领域术语](../CONTEXT.md)：房间、对局、队友等词的统一定义。
- [历史方案 v0.1](product-design-v0.1.md)：仅供追溯，不再指导实现。

需求变化先更新整体设计；实现顺序和进度以本索引及 stage 文档为准；接口实际可用性以协议状态和验收证据为准。
