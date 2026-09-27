# 对外协议 v1 草案

日期：2026-09-06。状态必须区分“已实现”与“计划”，不能仅凭本文件认为端点可用。

## 已实现的 HTTP

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| GET | /api/system/bootstrap | stage=scaffold、protocolVersion=1；authentication、rooms、gameplay、roomText、teamText 反映 GAME_AUTH_ENABLED；teamVoice 还要求 TEAM_VOICE_ENABLED |
| GET | /api/system/session | game侧已验证的userId/sessionId/nickname/clientType/expiresAt；需要真实Web或App会话 |
| GET | /api/auth/status | 返回backend-auth状态，loginAvailable/registrationAvailable取决于AUTH_ENABLED，默认false |
| GET | /actuator/health | 各 Java 进程的基础健康检查；网关不聚合下游就绪情况 |
| GET | /actuator/health/readiness | identity/game 直连探针，包含数据库；不可用返回503，仅含status |
| GET | /actuator/health/liveness | identity/game 直连探针，不依赖数据库；仅含status |

未登录访问受保护路径默认 401/403；没有开放示例账号或语音令牌。`/ws/game` 已由 game-service 处理，其余 `/ws/**` 没有业务 handler。

identity 认证、game 会话校验与经典对局已接入，见 [账号与会话](../authentication.md) 和 [跨服务身份](../service-authentication.md)。bootstrap 的 `stage` 字段仍保留 scaffold 历史值，实际可用性应看逐项功能标识和验收记录。

## 账号HTTP（已实现，默认关闭）

GET /api/auth/csrf；POST /api/auth/register、/api/auth/login、/api/auth/refresh、/api/auth/logout、/api/auth/verification/request、/api/auth/verify-email、/api/auth/password/forgot、/api/auth/password/reset；GET /api/users/me；POST /api/users/me/profile，请求 `{nickname}` 并返回更新后的本人资料。昵称去首尾空白后为 1–40 个 Unicode 码点，不能含控制字符；其他已登录设备下次读取资料可见。房间成员昵称是入房时快照，更新资料不改当前房间或历史战绩昵称。

AUTH_ENABLED=true且安全配置有效时开放；原生App须带X-UNO-Client: APP，Web须Cookie/Origin/CSRF。game另需GAME_AUTH_ENABLED和内部服务凭证；外网邮件尚未交付。

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

## 经典与 2v2 对局 HTTP（已实现初始切片）

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| POST | /api/rooms/{roomId}/start | 房主提交 `{expectedVersion}`；经典房间 2–6 人或 2v2 房间四人全员准备可启动，返回 `{matchId,view,roomVersion,deadlineAt}`；重复启动返回原对局 |
| GET | /api/rooms/{roomId}/match | 对局成员发现进行中的对局并取回个人视图；没有则 204 |
| GET | /api/matches/{matchId}/state | 仅对局成员可读；返回 `{view,deadlineAt,status,interruptionReason}`，其中 `view` 是当前用户的 `UnoView`，其他人只见手牌数量 |
| POST | /api/matches/{matchId}/commands | 成员提交 `{protocolVersion:1,commandId,expectedVersion,type,...}`；按规则执行，返回动作版本、事件、当前个人视图与仅本人可见的质疑证据 |
| POST | /api/matches/{matchId}/leave | 对局成员主动退出进行中对局；事务内中断本局、记 `PLAYER_LEFT`、释放房间并移除退出者，返回个人状态；重复请求不重复中断 |

动作 `type` 支持 `PLAY`（`cardId,chosenColor,callUno`）、`DRAW`、`PASS`、`SAY_UNO`、`CATCH_UNO`（`targetUserId`）、`ACCEPT_DRAW_FOUR`、`CHALLENGE_DRAW_FOUR`、`CHOOSE_INITIAL_COLOR`（`chosenColor`）、`NEXT_ROUND`。`actor` 从已验证会话推导，不能由客户端指定。对局行锁串行化动作；版本不符或同一 `commandId` 换内容返回 409，规则拒绝返回 422；同一动作重试返回 `duplicate:true` 且不重放效果。`view` 是响应时的最新个人视图，`appliedVersion` 是此命令首次落地的版本。启动、当前对局、状态、动作回执及 WebSocket 快照均提供 UTC `deadlineAt`；回合结束或整局结束时为 `null`。

普通回合从开始起计 30 秒；摸到可出的牌进入 `AFTER_DRAW` 时不重置这 30 秒。+4 回应窗口为 8 秒；窗口到期但尚未裁决的玩家新命令返回 409 `TURN_EXPIRED`，已落地的同一命令仍可重试取得回执。服务端在超时后按当前持久化状态执行默认动作：普通回合自动摸 1 张并结束（若规则引擎允许出刚摸的牌，会在同一事务自动 `PASS`，版本因此前进两次）；已摸牌等待选择时自动 `PASS`；+4 回应自动接受；开局万能牌选色默认红色。超时动作写入命令记录并推送个人快照，重复扫描不会重复摸牌或裁决。`SAY_UNO`、`CATCH_UNO` 与摸牌后等待选择不延长原截止时间。

牌堆、其他玩家手牌和加四质疑证据都只保存在服务器；质疑证据仅随质疑者的动作响应返回。2v2 单轮决胜，任一队员出完则同队获胜，`NEXT_ROUND` 不适用；队伍在 `match_players.team_snapshot` 固化，具体见 [团队规则](../rules-team-v1.md)。进行中的房间不能直接调用房间退房接口；玩家可通过对局退出接口中断本局，其他人回到等待室。Web 与 Flutter 已接入双模式牌桌；Web 与 iOS 模拟器同局 2v2 已验收，Android/iOS 真机联动仍待验收。

## 个人对局战绩 HTTP（已实现初始切片）

`GET /api/matches/history?cursor=...&limit=20` 仅按当前认证身份返回自己参与且 `state=ENDED|INTERRUPTED` 的经典或 2v2 对局，默认 20 条、最多 50 条；`nextCursor` 为不透明的稳定分页位置，末页为 `null`。每项包含 `matchId,mode,endedAt,rounds,winnerUserId,result,players`，`result` 为当前用户的 `WIN`、`LOSS` 或 `INTERRUPTED`；2v2 中 `winnerUserId` 是实际出完牌的队员，胜负按开局队伍快照判断。中断时赢家为 `null`。玩家列表含开局时保存的昵称、座位和最终积分，不返回牌库或私有手牌。旧对局若没有昵称快照，`nickname` 为 `null`，客户端显示匿名席位。进行中对局不入历史。

`GET /api/matches/stats` 仅按当前认证身份从权威对局快照汇总 `{classic:{wins,losses,interrupted},team2v2:{wins,losses,interrupted}}`。无记录时计数均为 0；客户端只用 `wins + losses` 作为完赛胜率分母，中断另计。统计不依赖历史分页，也不使用当前房间队伍关系。Web/App 账号页展示两个模式的独立汇总；该界面的真机验收仍待完成。

## 房间文字 HTTP（已实现）

`POST /api/rooms/{roomId}/messages` 请求 `{clientMessageId,content}`；`GET /api/rooms/{roomId}/messages?after=0&limit=50` 按递增频道序号分页，`latest=true` 取最近 50 条并返回后续补取游标。默认 `ROOM` 频道；响应含服务器生成的消息 ID、发送者 ID/昵称、时间、序号和原样纯文本。请求不得指定发送者或收件人；每次发送和读取都要求有效会话及当前房间成员资格，重新加入后无法读取这次加入前的历史。同一发送者在同一房间重试相同 `clientMessageId` 与正文返回原消息，换正文返回 409。服务端每秒至多接受两条新消息，每条最多 500 个 Unicode 码点；默认 30 天后删除。Web 使用 Cookie/CSRF，App 使用 Bearer。双端以 WebSocket 实时接收，并用 HTTP 游标补偿断线期间的消息。

2v2 房间可在发送体附 `channel:"TEAM"` 或在历史请求使用 `channel=TEAM`。服务器从当前成员席位推导实际 `TEAM_A` 或 `TEAM_B`，客户端不能指定 A/B、发送者或接收者；经典房间请求团队频道返回 400。房间与队伍各自维护序号和游标，换队/重新加入时的 `team_join_sequence` 阻止读取该队此前消息；同一消息 ID 不能跨频道重用。双端使用 WebSocket 实时接收和 2 秒 HTTP 游标补取，维护独立未读数。

`POST /api/rooms/{roomId}/messages/{messageId}/reports` 只接受 `{reason:"SPAM"|"ABUSE"|"OTHER"}`。服务器验证当前成员能读取被举报消息；返回 `{id,status}`，同一用户对同一消息的重试返回同一记录。每人每 24 小时最多 20 份举报。运营限时禁言使 HTTP `send` 返回 403 `CHAT_MUTED`、WebSocket `CHAT_SEND` 返回 `CHAT_REJECTED`；已成功消息按原 `clientMessageId` 重试仍可读取原结果。

消息 `item.redacted` 是服务器布尔字段。运营撤回后，原消息保持 ID 与频道序号，`content` 变为“[消息已移除]”，`redacted=true`；两端每约 10 秒重新核对目前缓存的最近 100 条消息以替换旧正文，不推进新消息游标。老版本服务端若没有该字段，客户端按未撤回处理。举报证据快照独立按 30 天期限清理。

## 队友语音 HTTP（服务端与 Web 首版已实现，默认关闭）

`POST /api/voice/token` 请求仅需 `{matchId}`，返回 `{url,token,expiresAt}`，响应禁止缓存。Web 使用 Cookie/Origin/CSRF，App 使用 Bearer；必须另启 `TEAM_VOICE_ENABLED` 和 LiveKit 配置。服务端从当前已验证的身份、进行中的 2v2 对局、四个固定席位及当前房间成员推导 A/B 队和媒体房间，忽略请求中的伪造 teamId/roomName。对手各在独立 LiveKit 房间；令牌只含 `roomJoin`、指定 `room`、`canSubscribe` 和 `canPublishSources:["microphone"]`，不授予数据、视频、建房或管理权限。JWT 至多 60 秒可用于初始连接，同一玩家每 3 秒最多取得一次。

对局结束时，事务内写入媒体房间清理任务，后台按幂等方式调用 LiveKit `DeleteRoom`，失败会重试；清理任务保留至少 70 秒并重复删除可能被旧短令牌重建的终局房间。已连接者的签名元数据绑定账号会话 ID；后台轮询确认会话仍有效。登出、过期、账号禁用或无法核验在场会话时，服务端换 `voice_generation` 并删除旧队伍房间，有效队员以新凭证重新加入；旧代次持续清理至旧 JWT 到期。Web 与 Flutter 只在玩家主动加入后自动换代次，保留静音选择；退出和终局释放本地采集轨道。自托管 LiveKit 的 `RemoveParticipant` 不会让已签发 JWT 失效，旧令牌到期前仍可能短暂重建隔离的旧房间。**人工听感、Flutter 真机和公网 HTTPS/TURN 媒体部署尚未验收。**详见 [Web/后端语音验收](../verification-stage13-14-voice.md)与 [Flutter 语音验收](../verification-stage15-voice.md)。

Web 的 Cookie 登录需要 CSRF 防护；Flutter 的令牌流程需要明确刷新、撤销和安全存储。WebSocket 浏览器连接使用允许的 `Origin` 和会话 Cookie；原生 App 连接使用 `X-UNO-Client: APP` 和 Bearer 访问凭证。Gateway 的 Reactor Netty 上游 WebSocket 会为原本无 `Origin` 的原生请求补充上游端点的同源 `Origin`，game-service 仅接受空值或与实际上游地址完全一致的值；其他来源、Cookie 或 Fetch Metadata 混用仍拒绝。握手、每条消息及连接定期核验会话；URL 查询参数不允许携带凭证。

## 经典对局 WebSocket（已实现）

连接 `/ws/game` 后，发送订阅消息；服务端仅在当前身份属于该对局时返回 `MATCH_SNAPSHOT`。重新连接后重新订阅，从 PostgreSQL 恢复最新个人视图。每条消息和服务端响应均带 `protocolVersion: 1`。

```json
{"protocolVersion":1,"type":"SUBSCRIBE","matchId":"server-issued-uuid"}
{"protocolVersion":1,"type":"COMMAND","matchId":"server-issued-uuid","command":{"protocolVersion":1,"commandId":"client-generated-uuid","expectedVersion":1,"type":"DRAW"}}
```

动作内容与 HTTP `/api/matches/{matchId}/commands` 相同；`callUno` 缺省为 `false`。服务器只向 WebSocket 提交者发送 `COMMAND_ACK`（含个人视图和可能的私有质疑证据）或 `COMMAND_REJECTED`（含 `commandId` 与错误代码）；WebSocket 或 HTTP 动作成功后向同局已订阅连接分别发送各自的 `MATCH_SNAPSHOT`。开始响应、状态查询、动作回执及快照均含 `status: PLAYING|ENDED|INTERRUPTED` 和 `deadlineAt`。状态查询与快照还含可空的 `interruptionReason`，目前为 `PLAYER_LEFT`、`REPEATED_TURN_TIMEOUT` 或 `null`。主动退出和超时裁决向同局全部已订阅连接推送私有快照。重复命令不重新广播。非法订阅/格式返回 `ERROR`。

普通回合与开局选色限时 30 秒，+4 回应限时 8 秒。每位玩家第三次连续错过本人回合后，服务器将对局置为 `INTERRUPTED`、取消截止时间并释放房间；玩家主动退出也立即中断当前局并从房间移除，剩余玩家可重新准备。两种中断均不计胜负，历史记录的 `result=INTERRUPTED` 且 `winnerUserId=null`。本人成功提交动作清零本人计数；+4 自动接受不计漏回合。经典局单轮结束 120 秒无人开始下一轮时由服务器自动开局。这些是产品超时策略，不是官方 UNO 规则。

单条文本消息上限 8192 字节，每连接每 10 秒最多 30 条；速率超限以 1008、`RATE_LIMITED` 关闭连接，客户端可重新订阅并同步状态，不能自动重发未确认命令。同一用户对同一局的新 WebSocket 订阅会通过数据库归属记录接管旧连接：同实例旧连接立即以 4001、`TAKEN_OVER` 关闭；其他实例的旧连接在下一条命令或订阅轮询时关闭，接管后不能再通过旧 WebSocket 提交动作。Web/App 停止自动重连，用户可以手动在当前端重新接管。每连接同时只订阅一局，不能指定其他身份或接收队列。服务端每次推送前重新核验会话；失效或撤销的连接会关闭。超时扫描默认约每秒执行一次；每个 Game 实例默认每秒检查本地订阅的归属，并比较进行中对局的持久化快照（版本、状态、截止时间和中断原因），为其他实例提交或本机推送遗漏的变更补发私有快照。终局快照不继续比较，但归属仍会检查。此方案每个订阅每秒增加数据库读取；多实例容量尚未验收。HTTP 动作入口在同账号有 WebSocket 归属时返回 409 `MATCH_SOCKET_OWNED`；没有归属时仍按会话与对局权限接受动作。连接关闭会释放自身归属，旧连接不能删除新连接的归属。

## 房间与队伍聊天协议

`/ws/chat` 复用游戏 WebSocket 的 Web Cookie／App Bearer 握手认证和 Origin 检查。客户端先发送 `{protocolVersion:1,type:"SUBSCRIBE",roomId}`；服务器核对当前成员后返回 `CHAT_SUBSCRIBED`。每连接只能订阅一个房间。客户端可发送 `{protocolVersion:1,type:"CHAT_SEND",roomId,clientMessageId,channel:"ROOM"|"TEAM",content}`；成功时服务器向当前有权成员推送 `{protocolVersion:1,type:"CHAT_MESSAGE",roomId,item}`，向发送方另发 `CHAT_ACK`（含同一 item）。失败返回 `CHAT_REJECTED` 或 `ERROR` 及错误码。单条 WebSocket 文本上限 8192 字节，每连接每 10 秒最多 30 条入站消息。

指令不能含 senderId、teamId、接收者列表；服务器从会话及当前房间席位推导身份和队伍，并生成消息 ID、时间和频道序号。发送重试沿用 `clientMessageId` 幂等键。HTTP 发送也会触发同实例实时推送；两端目前仍以 HTTP 游标补取恢复断线、跨实例或漏收消息。聊天序号与游戏状态版本独立，UI 发送仍走 HTTP，以便在无实时连接时保持确认和同 ID 重试。跨实例共享广播尚未实现。

## 语音后续验收

请求 `POST /api/voice/token` 只包含 matchId，身份由有效会话确定。服务器确认进行中的 2v2、四个席位、每队两人、请求者仍在席，再返回只能加入本人队伍房间的凭证。

当前 grant 已按 roomJoin、绑定 room 和 identity、canSubscribe、仅 microphone 的 canPublishSources 签发；禁止 camera/screen_share/data/admin。不能从客户端输入直接拼接目标 roomName。

返回中的服务地址只含客户端连接地址，永远不返回 LiveKit API secret。离队/结束后的移除、房间代次切换、旧令牌重放处理属于必须验收的服务器行为，不是 UI 隐藏按钮即可完成。
