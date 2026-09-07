# 对外协议 v1 草案

日期：2026-09-06。状态必须区分“已实现”与“计划”，不能仅凭本文件认为端点可用。

## 已实现的 HTTP

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| GET | /api/system/bootstrap | 返回 game-service、stage=scaffold、protocolVersion=1 与均为 false 的功能开关 |
| GET | /api/auth/status | 返回账号模块骨架状态，loginAvailable/registrationAvailable=false |
| GET | /actuator/health | 各 Java 进程的基础健康检查；网关不聚合下游就绪情况 |
| GET | /actuator/health/readiness | identity/game 直连探针，包含数据库；不可用返回503，仅含status |
| GET | /actuator/health/liveness | identity/game 直连探针，不依赖数据库；仅含status |

未登录访问受保护路径默认 401/403；没有开放示例账号或语音令牌。当前 `/ws/**` 仅配置网关预留路由，没有 WebSocket handler。

stage2 的账号/会话/令牌数据表与内部仓储不构成对外认证接口；所有业务能力开关仍为 false。业务错误结构约定见 [工程基线](../engineering-baseline.md)，尚未实现全局业务异常转换。

## 计划中的 HTTP（均未实现）

| 方法 | 路径 | 预期用途 |
| --- | --- | --- |
| POST | /api/auth/register | 注册 |
| POST | /api/auth/login | 登录 |
| POST | /api/auth/refresh | 轮换 App 凭证 |
| POST | /api/auth/logout | 撤销会话及实时连接 |
| GET | /api/users/me | 当前身份和偏好 |
| POST | /api/rooms | 创建 CLASSIC 或 TEAM_2V2 房间 |
| POST | /api/rooms/{roomId}/join | 房间码/邀请验证后加入 |
| POST | /api/rooms/{roomId}/leave | 离开并撤销通信资格 |
| GET | /api/matches/{matchId}/state | 当前用户可见的状态 |
| GET | /api/rooms/{roomId}/messages | 按本人权限和游标取房间/队伍消息 |
| POST | /api/voice/token | 从已认证身份和 matchId 推导队伍，返回受限短期媒体凭证 |

Web 的 Cookie 登录需要 CSRF 防护；Flutter 的令牌流程需要明确刷新、撤销和安全存储。WebSocket 使用同站会话或一次性短期连接票据，禁止在 URL 中放长期访问/刷新凭证。

## 实时指令草案

WebSocket 路径拟定 `/ws/game`；连接先认证，再执行每条动作的对象级授权。禁止传任意订阅目标或自选他人的私有队列。

```json
{
  "protocolVersion": 1,
  "commandId": "client-generated-uuid",
  "type": "CHAT_SEND",
  "roomId": "server-issued-uuid",
  "payload": {"channel": "TEAM", "content": "我来配合你"}
}
```

聊天指令没有 senderId、teamId、接收者列表；服务端从会话和房间状态推导。消息幂等按身份/频道/commandId 处理，服务器分配消息 ID、时间与频道序号。

对局指令另外携带 matchId、expectedVersion；类型计划包括 READY、SELECT_TEAM、START_MATCH、PLAY_CARD、DRAW_CARD、PASS_AFTER_DRAW、CALL_UNO、CATCH_UNO、CHALLENGE_DRAW_FOUR、ACCEPT_DRAW_FOUR。出万能牌和选色一次性提交。

服务器事件计划包括 COMMAND_ACK、COMMAND_REJECTED、ROOM_SNAPSHOT、MATCH_SNAPSHOT、CHAT_MESSAGE、VOICE_ELIGIBILITY_CHANGED、MATCH_ENDED。公共视图和本人手牌分别生成，不广播全量秘密状态。

游戏状态与聊天使用不同的序号和补偿机制；聊天不应因为游戏 expectedVersion 改变而重复发送。网络重试不直接当作新操作。

## 语音准入草案

请求 `POST /api/voice/token` 只包含 matchId，身份由有效会话确定。服务器确认进行中的 2v2、四个席位、每队两人、请求者仍在席，再返回只能加入本人队伍房间的凭证。

预期 grant：roomJoin、绑定 room 和 identity、canSubscribe、仅 microphone 的 canPublishSources；禁止 camera/screen_share/data/admin。不能从客户端输入直接拼接目标 roomName。

返回中的服务地址只含客户端连接地址，永远不返回 LiveKit API secret。离队/结束后的移除、房间代次切换、旧令牌重放处理属于必须验收的服务器行为，不是 UI 隐藏按钮即可完成。
