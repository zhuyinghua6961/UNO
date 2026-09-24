# stage11 实时文字增量验收（2026-09-24）

## 本次交付

- game-service 增加认证 `/ws/chat`：订阅当前房间、CHAT_SEND、CHAT_MESSAGE、CHAT_ACK；HTTP 发消息在事务提交后也触发推送。
- 服务端逐个接收者重查有效会话和当前成员／队伍／入队时间，离房与换队后不再接收旧队消息；未知字段和伪造 senderUserId 被拒绝。
- Web 和 Flutter 接收实时消息、维护当前频道未读与消息去重；断线后继续按 ROOM／TEAM 各自游标补取。发送仍走 HTTP，以同一个 clientMessageId 重试。
- 可复跑的 `SMOKE_CHAT_WS=true node tools/smoke-auth-chat.mjs` 经 Gateway 建立两个真实账号和房间，验证 WebSocket 发送、ACK、对方实时接收及 HTTP 历史回读。

## 已执行检查

- `mvn -f backend/pom.xml -Pdatabase-it -pl game-service -am verify -q`：通过；`ChatWebSocketIT` 1 项和 `ChatServiceIT` 5 项通过，覆盖队伍隔离、成员离开、会话撤销与伪造字段。
- `npm --prefix web run build` 与 `npm --prefix web test`：通过，15 个文件、88 项测试。新增订阅、重连和聊天界面推送去重测试。
- `cd flutter && dart analyze --format=machine && flutter test`：通过，31 项测试。新增本机原生 WebSocket 握手／认证头／实时事件测试。
- 本机 Compose 启用账号配置，使用独立回环端口 `18088/18080/18025/11025` 避免与已有 stage17 检查栈冲突；`API_BASE_URL=http://127.0.0.1:18080 MAILPIT_BASE_URL=http://127.0.0.1:18025 SMOKE_CHAT_WS=true node tools/smoke-auth-chat.mjs` 通过。

## 仍需验收

本次没有重新执行真机或模拟器画面上的 WebSocket 到达时间与弱网恢复观察；此前的跨端 UI 互发使用 HTTP 两秒补取。多 game-service 实例尚无共享推送，游标补取维持消息完整性。内容举报、禁言／封禁联动、最终保存与删除策略仍在 stage11 范围内，公开试用前必须完成。
