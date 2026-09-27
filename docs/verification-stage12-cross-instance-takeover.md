# stage12 跨实例对局连接接管增量验收（2026-09-27）

## 实际变更

Game 数据库 V16 为每位对局玩家增加一条 WebSocket 操作连接归属记录。订阅时以随机连接标识认领；同账号在另一 Game 实例订阅后，旧连接的下一条命令会先锁定归属记录并核验，因此不能在接管完成后提交动作。旧实例的订阅轮询也会检查归属；发现已被接管时以 WebSocket 4001、`TAKEN_OVER` 关闭。归属记录与对局玩家绑定，对局数据删除时级联清理。

数据库行锁让“旧命令提交”和“新订阅接管”具有明确先后顺序：已经取得归属锁并完成的命令可先于接管落库；接管提交后的旧命令被拒绝。玩家仍可在新设备主动接管，并按现有命令 ID 幂等规则重试未确认动作。

## 已执行检查

- `mvn -f backend/pom.xml -pl game-service -am -Pdatabase-it -Dit.test=GameWebSocketIT -Dfailsafe.failIfNoSpecifiedTests=false verify -q`：退出码 0；`GameWebSocketIT` 10 项通过，`GameWebSocketSubscriptionTest` 1 项通过；PostgreSQL V16 迁移成功。
- 新集成场景在共享 PostgreSQL 上启动两个独立 Spring Game 应用上下文和 WebSocket 端口。同一行动者先连接实例 A，再连接实例 B；A 的旧连接发有效命令被 4001 关闭，数据库对局版本仍为 1。随后重新从 A 接管，B 的空闲连接经轮询关闭，新连接的动作成功，版本变为 2。

## 剩余门槛

两个应用上下文仍在同一测试 JVM。独立 Game 进程经真实 Gateway/负载均衡器、真机弱网切换和竞争条件压力测试尚未完成。`POST /api/matches/{matchId}/commands` 的 HTTP 动作入口目前按账号认证与对局行锁执行，不受 WebSocket 连接归属约束；发布前须统一操作权规则，不能把本次 WebSocket 接管验收推广到 HTTP。多连接轮询的数据库容量也未测量。
