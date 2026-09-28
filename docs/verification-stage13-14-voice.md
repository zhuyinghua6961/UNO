# stage13–14 队友语音增量验收（2026-09-24）

## 当前交付

- game-service 接入 LiveKit Java 服务端 SDK 0.16.0；`POST /api/voice/token` 只接受 matchId，凭真实会话、对局模式/状态、四个固定席位、当前房间成员和持久化 `voice_generation` 决定媒体房间。A/B 使用不同房间，房间上限两人。
- JWT 只授予指定房间的加入、订阅和麦克风发布，初始连接有效期不超过 60 秒。PostgreSQL 限制同一用户/对局 3 秒内重复签发。LiveKit 不可达返回 `VOICE_UNAVAILABLE`；对局仍可出牌和发文字。
- 终局事务写入 `voice_cleanup`，后台删除两个媒体房间；删除失败按 1–60 秒间隔重试，重复删除视为成功。V10 迁移后清理任务保留至少 70 秒，每 3 秒重复删除到短令牌过期宽限期结束，以压缩旧 JWT 重建终局房间的窗口；单轮最多处理 8 局。
- V11 为媒体房间增加按代次保留的清理任务和会话复核时间。JWT 的签名元数据绑定账号会话 ID；后台每约 5 秒检查已连接的 2v2 房间，向 identity-service 查询会话是否有效。发现登出、过期、账号禁用或无法验证的在场会话时，先把对局切到新 `voice_generation`，再删除 A/B 两个旧房间并持续清理旧代次到令牌到期。旧 JWT 即使重新创建旧房间，也无法进入有效队友的新房间。媒体管理接口故障时保持复核待办，服务恢复后重试。
- Web 2v2 牌桌加入、静音、重新开麦、退出、音频订阅、说话状态、播放限制提示和连接状态已接入。只有点击加入后才申请麦克风；静音、退出、组件卸载和终局会停掉本地轨道。HTTP 提交的牌局动作也会广播给已订阅的 WebSocket 连接，这对 Web/Flutter 混合对局及终局语音释放是必要的。
- Web 收到 LiveKit 房间删除事件后延迟约 3.3 秒获取新凭证并加入新代次；只有此前主动加入语音的页面会这样恢复，用户退出或卸载组件会取消恢复，原本静音者不会重新申请麦克风。
- `deploy/compose.voice-local.yaml` 把 game-service 接到同一 Compose 网络的 LiveKit，浏览器媒体地址仍只广告 `127.0.0.1`。

## 已执行验证

1. `mvn -q -pl game-service -am -Pdatabase-it verify -Dtest=none -Dit.test=VoiceServiceIT,GameDatabaseIT,MatchServiceIT -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`：PostgreSQL V10 迁移与重启、A/B 令牌隔离、经典/终局/非成员拒绝、麦克风 grant、限流、媒体故障与删除重试均通过。增量测试覆盖保留期内重复删除和一个房间失败时继续处理其他对局。
2. LiveKit 1.13.6 容器在本机隔离 Compose 项目启动。`UNO_LIVEKIT_IT=true` 运行 `LiveKitVoiceMediaIT`：真实 API 建立 `maxParticipants=2` 的房间并删除，1 项通过。曾发现 `createRoom(name, 2)` 的第二参数实际上是空房间超时，现明确使用 `createRoom(name, 300, 2)`。
3. `SMOKE_TEAM_MATCH=true SMOKE_TEAM_VOICE=true node tools/smoke-auth-chat.mjs`：四个真实注册/邮件验证/App 登录身份启动 2v2；A/B JWT 的媒体房间不同，同队相同，伪造 teamId/roomName 无法改变选择；整局结束后不能再取令牌，文字和战绩仍正确。通过。
4. `npm --prefix web run test:e2e:voice`：三名 Chromium 玩家用 fake audio device 真实连接两个 LiveKit 房间。同队订阅到对方的远端音轨，对手房间没有该音轨。加入前 `getUserMedia` 计数为零；静音、退出和整局终局后，捕获到的本地 `MediaStreamTrack.readyState` 均为 `ended`。四名玩家的 App Bearer HTTP 出牌触发 WebSocket 终局更新和组件卸载。V10 增量用例在终局后用仍有效的旧令牌重新连接旧房间，确认保留中的清理任务随后再次删除房间、断开连接。通过。测试脚本缓存 Web CSRF token，并用 App Bearer 执行快速大量出牌，避免测试自动化耗尽账号服务的 15 分钟 IP 限流。
5. Web `npm test`：12 个文件、78 项通过；`npm run build` 通过。game-service 目标 PostgreSQL 集成测试（VoiceServiceIT、MatchServiceIT、RoomServiceIT、ChatServiceIT、GameDatabaseIT）通过；迁移增加的外键已同步更新测试数据清理。Compose 配置、脚本语法和 `git diff --check` 通过。
6. 隔离的 `uno-stage17-check` Compose 栈原有 PostgreSQL 卷直接升级 game-service 镜像，Flyway 从 V9 迁移到 V10；网关 `bootstrap` 保持账号、房间、牌局、文字、语音功能开启。本机测试账号服务的速率限制表仅在隔离栈内为重复自动化运行清空过。
7. 2026-09-24 在同一隔离栈升级 Identity、Game、Web 镜像，Flyway 从 V10 迁移到 V11。`AuthIT`、`VoiceServiceIT`、`GameDatabaseIT` 验证内部批量会话核验、吊销后代次轮换、身份服务不可用时关闭在场房间及旧代次清理。`npm --prefix web run test:e2e:voice-revoke` 使用四个真实账号和 LiveKit：A1 Web 会话登出后，旧连接释放麦克风；仍有效且已静音的 A2 自动进入新房间，未重新调用 `getUserMedia`；重放 A1 尚未到期的 JWT 不能看到新房间的队友。`npm --prefix web run test:e2e:voice` 再次通过完整终局/旧令牌清理回归。Web 78 项测试和生产构建通过。重复自动化触发账号 IP 限流后，仅清空隔离栈的测试限流记录再执行。
8. 2026-09-28 浏览器双向音频补验：`web/e2e/team-voice.cjs` 在同队 A1、A2 均加入后，从各自订阅的远端 `MediaStream` 用 Web Audio 取 PCM 时域样本，分别确认均方根幅度大于 0.001；B1 所在对手房间没有对应音轨。`npm --prefix web run test:e2e:voice` 与 `npm --prefix web run test:e2e:voice-revoke` 在原有本地 `uno-stage17-check` LiveKit 测试栈均通过，分别覆盖整局/终局清理及会话吊销/旧令牌隔离。这证明虚拟麦克风样本双向到达浏览器，未测真实扬声器听感。
9. 同日 V17 补验：发现已撤销会话若先断开媒体房间，V11 只看在场参与者会漏掉其旧 JWT。服务端现登记当前代次已发令牌的会话并持续复核，升级旧数据时主动换代；真实浏览器撤销恢复、房外账号拒绝和对手手牌隔离再次通过。V16 起对局 WebSocket 拥有出牌权时 HTTP 不能绕过，语音整局脚本已改为 Web 界面操作。详见 [stage18 安全补验](verification-stage18-security-v17.md)。
10. [0.3.13 同版语音栈补验](verification-stage18-0.3.13-voice.md)用已校验的离线镜像在空库重做 Web 双向虚拟音频、撤销隔离、Android/Web 双向音轨订阅及 iOS 仅收听连接；仍是本机模拟器环境。

## 尚未达到 stage13/14 验收的项目

- 自托管 LiveKit 的 `RemoveParticipant` 不会撤销已签发 JWT；旧 JWT 到期前仍可能短暂重建旧房间，因此旧代次重复清理仍必要。会话检查采用轮询，吊销到旧房间断开有数秒延迟；单轮最多复核 8 局。跨节点、高并发以及媒体管理接口故障时的时延还没有压测。
- 未用两套真实麦克风、扬声器和人耳验证双向可听效果；fake audio 测试证明本机真实 WebRTC 房间连接、双向音频样本到达、远端订阅和本地采集轨道生命周期。权限拒绝、设备占用、播放限制、网络切换和 Safari 等浏览器行为仍需测试。
- Flutter 已有语音 SDK/UI 和房间删除后的自动重连，但还没有真实设备互听。当前回环媒体地址不适用于另一台手机或公网。发布前需 HTTPS/WSS、可达 ICE/TURN、密钥管理、网络与设备矩阵验收。

LiveKit 的 [服务端令牌文档](https://docs.livekit.io/home/server/generating-tokens/) 与 [RoomService API](https://docs.livekit.io/reference/other/roomservice-api/) 是权限和撤销行为依据；Web SDK 使用 [官方 JavaScript 客户端](https://github.com/livekit/client-sdk-js)。
