# 对外协议 v1 草案

日期：2026-09-06。状态必须区分“已实现”与“计划”，不能仅凭本文件认为端点可用。

## 已实现的 HTTP

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| GET | /api/system/bootstrap | stage=scaffold、protocolVersion=1；authentication和rooms反映GAME_AUTH_ENABLED，gameplay等仍为false |
| GET | /api/system/session | game侧已验证的userId/sessionId/nickname/clientType/expiresAt；需要真实Web或App会话 |
| GET | /api/auth/status | 返回backend-auth状态，loginAvailable/registrationAvailable取决于AUTH_ENABLED，默认false |
| GET | /actuator/health | 各 Java 进程的基础健康检查；网关不聚合下游就绪情况 |
| GET | /actuator/health/readiness | identity/game 直连探针，包含数据库；不可用返回503，仅含status |
| GET | /actuator/health/liveness | identity/game 直连探针，不依赖数据库；仅含status |

未登录访问受保护路径默认 401/403；没有开放示例账号或语音令牌。当前 `/ws/**` 仅配置网关预留路由，没有 WebSocket handler。

2026-09-07新增可关闭的identity认证后端及game会话校验，见 [账号与会话](../authentication.md) 和 [跨服务身份](../service-authentication.md)。bootstrap仍是scaffold，不因为身份可验证就宣称游戏可玩。

## 账号HTTP（已实现，默认关闭）

GET /api/auth/csrf；POST /api/auth/register、/api/auth/login、/api/auth/refresh、/api/auth/logout、/api/auth/verification/request、/api/auth/verify-email、/api/auth/password/forgot、/api/auth/password/reset；GET /api/users/me。

AUTH_ENABLED=true且安全配置有效时开放；原生App须带X-UNO-Client: APP，Web须Cookie/Origin/CSRF。game另需GAME_AUTH_ENABLED和内部服务凭证；客户端UI与外网邮件尚未交付。

内部`POST /internal/auth/introspect`只允许专用game服务凭证，不是玩家接口；gateway没有此路由，不能使用用户Bearer调用。具体请求/响应和TLS要求见跨服务身份说明。

## 房间 HTTP（已实现，须启用账号与game鉴权）

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| GET | /api/rooms/current | 当前用户房间；没有则 204 |
| POST | /api/rooms | 创建 `{mode,maxPlayers}`；已有房间返回当前房间 |
| POST | /api/rooms/join | 以 `{code}` 加入；房间码区分大小写输入但服务端统一大写 |
| GET | /api/rooms/{id} | 仅成员可读取 |
| POST | /api/rooms/{id}/leave | 离开，成功 204；空房删除，房主离开自动移交 |
| POST | /api/rooms/{id}/ready | `{ready,expectedVersion}`；版本不一致返回 409 |
| POST | /api/rooms/{id}/team | `{team:"A"|"B",expectedVersion}`，仅 2v2 |
| POST | /api/rooms/{id}/settings | `{maxPlayers,expectedVersion}`，仅房主 |

房间响应含 `id,code,mode,maxPlayers,hostUserId,state,version,expiresAt,canStart,members`；成员含 `userId,nickname,seat,team,ready`。经典局 2–6 人，2v2 固定四人；座位从 0 开始，偶数为 A 队、奇数为 B 队。成员/设置改变会重置所有准备状态。邀请码在等待状态下 24 小时有效，同账号只能在一间房且只占一个席位。Web 写操作须先取 `/api/auth/csrf` 并携 Cookie、Origin 和 `X-CSRF-TOKEN`；原生 App 须用 `X-UNO-Client: APP` 与 Bearer 令牌。

## 经典对局 HTTP（已实现初始切片）

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| POST | /api/rooms/{roomId}/start | 房主提交 `{expectedVersion}`；仅全员准备的经典房间可启动，返回 `{matchId,view,roomVersion}`；重复启动返回原对局 |
| GET | /api/rooms/{roomId}/match | 对局成员发现进行中的对局并取回个人视图；没有则 204 |
| GET | /api/matches/{matchId}/state | 仅对局成员可读；返回当前用户 `UnoView`，其他人只见手牌数量 |
| POST | /api/matches/{matchId}/commands | 成员提交 `{protocolVersion:1,commandId,expectedVersion,type,...}`；按规则执行，返回动作版本、事件、当前个人视图与仅本人可见的质疑证据 |

动作 `type` 支持 `PLAY`（`cardId,chosenColor,callUno`）、`DRAW`、`PASS`、`SAY_UNO`、`CATCH_UNO`（`targetUserId`）、`ACCEPT_DRAW_FOUR`、`CHALLENGE_DRAW_FOUR`、`CHOOSE_INITIAL_COLOR`（`chosenColor`）、`NEXT_ROUND`。`actor` 从已验证会话推导，不能由客户端指定。对局行锁串行化动作；版本不符或同一 `commandId` 换内容返回 409，规则拒绝返回 422；同一动作重试返回 `duplicate:true` 且不重放效果。`view` 是响应时的最新个人视图，`appliedVersion` 是此命令首次落地的版本。

牌堆、其他玩家手牌和加四质疑证据都只保存在服务器；质疑证据仅随质疑者的动作响应返回。2v2 房间不能启动对局。进行中的房间暂不能离开，避免席位与权威状态脱节。当前只可通过 HTTP 轮询状态，尚无 WebSocket 推送、服务器计时和双端牌桌动作界面。

## 计划中的游戏 HTTP

| 方法 | 路径 | 预期用途 |
| --- | --- | --- |
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
