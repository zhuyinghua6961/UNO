# stage11 跨实例文字补偿增量验收（2026-09-27）

## 实际变更

`/ws/chat` 订阅后，Game 服务按独立房间和队伍频道游标定时读取持久化消息。默认间隔为 1 秒，可通过 Spring 属性 `uno.chat.message-poll-ms` 调整；单次每频道最多取 100 条，后续轮询继续读取。HTTP/WebSocket 在本实例成功提交的消息仍立即推送。推送和轮询共享每个连接的频道游标，避免重复事件；较晚序号的本地发布不会跳过先提交但尚未推送的消息。队伍席位变更会重置当前队伍游标，并继续按加入序号与当前成员关系过滤历史。

## 已执行检查

- `mvn -f backend/pom.xml -pl game-service -am -Pdatabase-it -Dit.test=ChatWebSocketIT,ChatServiceIT -Dfailsafe.failIfNoSpecifiedTests=false verify -q`：退出码 0；`ChatWebSocketIT` 3 项、`ChatServiceIT` 6 项均通过。
- PostgreSQL/WebSocket 测试绕过本机 `publish` 钩子直接提交消息，随后触发订阅轮询，验证当前队伍收到、另一队与非成员收不到、重复轮询不重发。测试还覆盖乱序本地发布后的按序补偿，以及换队时旧队历史不可见、新队新消息可见。
- 另一个测试在同一 PostgreSQL 上启动两个独立 Spring Game 应用上下文及 WebSocket 监听端口：第一个实例提交 B 队消息，第二个实例的 B 队订阅者收到，A 队订阅者未收到；重复轮询没有重发。

## 范围与剩余验收

两个应用上下文仍处在同一测试 JVM，尚未通过独立 Game 进程、真实 Gateway 或负载均衡器验收。现有 Web/Flutter 客户端继续保留各频道 HTTP 游标补取，以修复订阅建立前或网络断开期间的空窗。尚需独立进程实测、真机弱网/网络切换观察、连接数与数据库轮询负载测量；容量尚未声明。文字内容治理和正式隐私说明仍按 stage11/stage18 路线图处理。

2026-09-28 后续补验：两个独立 Game 容器之间的房间消息补偿、Gateway 聊天 WebSocket 和经典/2v2 文字历史已通过，见 [本地双 Game 容器联调](verification-stage17-multi-instance.md)。上段保留的是 2026-09-27 原验收边界；生产负载均衡、真机弱网与容量仍未验证。
