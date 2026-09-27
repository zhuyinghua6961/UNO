# stage17 本地双 Game 容器联调（2026-09-28）

## 环境与构建来源

在干净提交 `15ef055` 上，从源码重新构建本地 Docker 镜像，在隔离 Compose 项目 `uno-stage12-multi` 启动 Identity、两个 Game、Gateway、PostgreSQL 17 和 Mailpit。两个 Game 容器使用同一镜像 `sha256:d6b661f070dbc94ed1f0b5d02b7bed697b6dd8cafa08ffc1da219f9978ccf3a0`，各有独立 JVM/WebSocket 连接表，共用 game 数据库；Flyway 版本为 16。Gateway 仅绑定回环地址 `38080`，两个 Game 实例各由 Docker 分配临时回环端口（本次为 `65340`、`65341`）。没有启动 Web 或 LiveKit 容器。

本次使用后来纳入仓库的 [双实例本地端口配置](../deploy/compose.multi-instance-local.yaml) 同等内容启动；启动时该覆盖文件位于临时目录。复跑方法见 [部署指南](../deploy/README.md)。测试脚本不会输出随机账号密码、会话令牌或邮件验证令牌。

## 实际业务验证

- `SMOKE_MULTI_INSTANCE=true node tools/smoke-auth-chat.mjs`：真实随机账号完成注册、Mailpit 验证、App Bearer 登录、组房。实例 A 通过 HTTP 提交房间文字，实例 B 的 WebSocket 订阅者收到；同账号先订阅 A、再订阅 B，A 的旧连接以 4001 关闭，旧连接与 HTTP 均不能绕过当前操作权，B 成功提交动作；B 关闭后，HTTP 按原命令 ID 返回 `duplicate:true`。主动退局返回中断，退出者失去聊天访问权。退出码 0。
- `SMOKE_FULL_MATCH=true SMOKE_TEAM_MATCH=true node tools/smoke-auth-chat.mjs`：通过 Gateway 的真实账号、房间与 HTTP 命令路径完成经典局和四人 2v2 整局。经典局双方历史与统计一致；2v2 队伍文字隔离、获胜队伍、四人历史与统计一致，房间回到等待状态。退出码 0。
- `SMOKE_CHAT_WS=true node tools/smoke-auth-chat.mjs`：通过 Gateway 的房间 WebSocket 订阅、发送、ACK、在线广播与 HTTP 历史回读通过，退出码 0。
- 两个 Game 实例的 `/actuator/health/readiness` 均返回 `UP`；测试数据库 V16 迁移完成。

## 单实例停止与恢复补验（2026-09-28）

在提交 `1448a16` 的后端源码上重建同一隔离栈，Game 镜像 ID 为 `sha256:65756352b08ec5aa073e44a8464a71d56515fca30784f3e6d3289664ef8be3c8`。新增 `SMOKE_GAME_CRASH=true` 路径先核对容器的 Compose 项目、服务标签和 Game A 回环端口，再用 `docker stop --time 0` 停止持有对局 WebSocket 的 Game A。Game B 的 HTTP 命令因数据库中保留的连接归属返回 409；同账号在 B 新建 WebSocket 订阅后，私有手牌、状态版本与回合截止时间保持一致，动作得到版本 2 ACK，断开 B 后同命令 ID 经 HTTP 返回 `duplicate:true`。脚本在清理阶段重新启动 A，并等待其 readiness 为 `UP`；随后主动退局及退出者聊天授权检查通过。退出码 0。

第一次执行暴露了测试脚本对 Docker 随机发布端口的错误假设：A 重启后从 `49557` 改绑 `49584`，旧端口轮询超时。脚本现于重启后读取新端口并通过补验；再次执行普通双实例接管脚本也通过。本补验针对单个 Game 进程停止、另一个实例持续可用的情况；未覆盖数据库失效、双实例同时停止、移动设备网络切换或生产网关故障转移。复跑命令见 [部署指南](../deploy/README.md)。

这些检查只证明本机 Docker 网络与回环端口中的业务路径。Gateway 可能将一条连接固定到其中一台 Game；独立容器间的接管和消息补偿使用指定实例端口验证，不能把结果说成已经测过生产负载均衡器或公网。

## 剩余验收

尚未测量多连接轮询的吞吐/延迟，未完成真机网络切换、TLS/WSS、跨网 LiveKit/TURN、双向麦克风互听、正式签名 App 包和生产发布。此项目使用本地生成的开发秘密和测试数据；无真实用户数据。测试完成后可对该隔离项目执行 `docker compose ... down`，不加 `-v` 保留其测试卷。

本次验收后已执行 `down`：六个测试容器与专用网络关闭，`uno-stage12-multi_postgres-data` 测试卷保留；原有 `uno-stage17-check` 栈仍运行。

单实例停止补验及普通双实例复测后，再次对隔离项目执行不带 `-v` 的 `down`；测试容器和专用网络已关闭，数据库卷保留，`uno-stage17-check` 未改动。
