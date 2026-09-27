# stage12 跨实例对局连接接管增量验收（2026-09-27）

## 实际变更

Game 数据库 V16 为每位对局玩家增加一条 WebSocket 操作连接归属记录。订阅时以随机连接标识认领；同账号在另一 Game 实例订阅后，旧连接的下一条命令会先锁定归属记录并核验，因此不能在接管完成后提交动作。旧实例的订阅轮询也会检查归属；发现已被接管时以 WebSocket 4001、`TAKEN_OVER` 关闭。归属记录与对局玩家绑定，对局数据删除时级联清理。

数据库行锁让“旧命令提交”和“新订阅接管”具有明确先后顺序：已经取得归属锁并完成的命令可先于接管落库；接管提交后的旧命令被拒绝。玩家仍可在新设备主动接管，并按现有命令 ID 幂等规则重试未确认动作。

## 已执行检查

- `mvn -f backend/pom.xml -pl game-service -am -Pdatabase-it -Dit.test=GameWebSocketIT -Dfailsafe.failIfNoSpecifiedTests=false verify -q`：退出码 0；`GameWebSocketIT` 10 项通过，`GameWebSocketSubscriptionTest` 1 项通过；PostgreSQL V16 迁移成功。
- 新集成场景在共享 PostgreSQL 上启动两个独立 Spring Game 应用上下文和 WebSocket 端口。同一行动者先连接实例 A，再连接实例 B；A 的旧连接发有效命令被 4001 关闭，数据库对局版本仍为 1。随后重新从 A 接管，B 的空闲连接经轮询关闭，新连接的动作成功，版本变为 2。

## 2026-09-28 HTTP 动作权补验

HTTP `/api/matches/{matchId}/commands` 现在与 WebSocket 动作共用对局行锁。HTTP 调用还锁定玩家行并检查当前连接归属；同账号有 WebSocket 操作连接时返回 409 `MATCH_SOCKET_OWNED`，不提交动作。WebSocket 连接关闭时只删除匹配该连接标识的归属记录，不能误删新设备的接管记录；无归属后 HTTP 按原命令 ID 重试仍返回幂等回执。玩家行锁同时封住“尚未有归属记录”时 HTTP 检查与新 WebSocket 认领之间的插入竞态。

真实 HTTP/WebSocket/PostgreSQL 集成测试在双 Spring Game 实例场景中验证：被接管期间 HTTP 返回 409、旧 WebSocket 也被关闭且对局版本未变；新连接提交动作后关闭，原命令 ID 可通过 HTTP 获得 `duplicate:true`。`mvn -f backend/pom.xml -pl game-service -am -Pdatabase-it verify -q` 通过：Game 服务 46 项集成测试中 45 项通过，依赖实际媒体服务的 `LiveKitVoiceMediaIT` 跳过 1 项；其余模块单元测试也通过。V16 带来的测试清理表清单及迁移/表数量断言已同步。

## 剩余门槛

两个应用上下文仍在同一测试 JVM。独立 Game 进程经真实 Gateway/负载均衡器、真机弱网切换、同时接管与 HTTP 命令的压力测试尚未完成。Web/App 正常对局使用 WebSocket；HTTP 在没有当前 WebSocket 归属时仍可按账号身份出牌。进程崩溃可能留下归属记录并暂时阻止 HTTP 出牌；新 WebSocket 订阅可重新认领。多连接轮询的数据库容量尚未测量。

2026-09-28 后续补验：两个独立 Game 容器对相同账号的 WebSocket 接管、旧命令拒绝、HTTP 归属检查与新连接命令已通过；经 Gateway 的经典/2v2 整局和战绩也通过，见 [本地双 Game 容器联调](verification-stage17-multi-instance.md)。上段保留的是较早的同 JVM 验收边界；生产负载均衡、真机网络切换、崩溃恢复和容量仍未测量。
