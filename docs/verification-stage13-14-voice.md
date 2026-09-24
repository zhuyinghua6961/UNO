# stage13–14 队友语音增量验收（2026-09-24）

## 当前交付

- game-service 接入 LiveKit Java 服务端 SDK 0.16.0；`POST /api/voice/token` 只接受 matchId，凭真实会话、对局模式/状态、四个固定席位、当前房间成员和持久化 `voice_generation` 决定媒体房间。A/B 使用不同房间，房间上限两人。
- JWT 只授予指定房间的加入、订阅和麦克风发布，初始连接有效期不超过 60 秒。PostgreSQL 限制同一用户/对局 3 秒内重复签发。LiveKit 不可达返回 `VOICE_UNAVAILABLE`；对局仍可出牌和发文字。
- 终局事务写入 `voice_cleanup`，后台删除两个媒体房间；删除失败按 1–60 秒间隔重试，重复删除视为成功。
- Web 2v2 牌桌加入、静音、重新开麦、退出、音频订阅、说话状态、播放限制提示和连接状态已接入。只有点击加入后才申请麦克风；静音、退出、组件卸载和终局会停掉本地轨道。HTTP 提交的牌局动作也会广播给已订阅的 WebSocket 连接，这对 Web/Flutter 混合对局及终局语音释放是必要的。
- `deploy/compose.voice-local.yaml` 把 game-service 接到同一 Compose 网络的 LiveKit，浏览器媒体地址仍只广告 `127.0.0.1`。

## 已执行验证

1. `mvn -q -pl game-service -am -Pdatabase-it verify -Dtest=none -Dit.test=VoiceServiceIT,GameDatabaseIT -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`：PostgreSQL V9 迁移与重启、A/B 令牌隔离、经典/终局/非成员拒绝、麦克风 grant、限流、媒体故障与删除重试均通过。
2. LiveKit 1.13.6 容器在本机隔离 Compose 项目启动。`UNO_LIVEKIT_IT=true` 运行 `LiveKitVoiceMediaIT`：真实 API 建立 `maxParticipants=2` 的房间并删除，1 项通过。曾发现 `createRoom(name, 2)` 的第二参数实际上是空房间超时，现明确使用 `createRoom(name, 300, 2)`。
3. `SMOKE_TEAM_MATCH=true SMOKE_TEAM_VOICE=true node tools/smoke-auth-chat.mjs`：四个真实注册/邮件验证/App 登录身份启动 2v2；A/B JWT 的媒体房间不同，同队相同，伪造 teamId/roomName 无法改变选择；整局结束后不能再取令牌，文字和战绩仍正确。通过。
4. `npm --prefix web run test:e2e:voice`：三名 Chromium 玩家用 fake audio device 真实连接两个 LiveKit 房间。同队订阅到对方的远端音轨，对手房间没有该音轨。加入前 `getUserMedia` 计数为零；静音、退出和整局终局后，捕获到的本地 `MediaStreamTrack.readyState` 均为 `ended`。HTTP 出牌后的 WebSocket 终局更新实际触发了组件卸载。通过。
5. Web `npm test`：12 个文件、78 项通过；`npm run build` 通过。game-service 目标 PostgreSQL 集成测试（VoiceServiceIT、MatchServiceIT、RoomServiceIT、ChatServiceIT、GameDatabaseIT）通过；迁移增加的外键已同步更新测试数据清理。Compose 配置、脚本语法和 `git diff --check` 通过。

## 尚未达到 stage13/14 验收的项目

- 自托管 LiveKit 的 `RemoveParticipant` 不会撤销已签发 JWT；终局删除房间后，旧 JWT 在到期前仍可能重新创建旧房间。当前没有账号撤销/封禁回调、成员代次轮换或对仍连接者的主动踢出。因此**不能称为完整撤销**。需要让有效队友迁移到新代次媒体房间，再删除旧房间，并检查旧令牌重放。
- 未用两套真实麦克风、扬声器和人耳验证双向可听效果；fake audio 测试只证明真实 WebRTC 房间连接、远端订阅和本地采集轨道生命周期。权限拒绝、设备占用、播放限制、网络切换和 Safari 等浏览器行为仍需测试。
- Flutter 尚无语音 SDK/UI。当前回环媒体地址不适用于另一台手机或公网。发布前需 HTTPS/WSS、可达 ICE/TURN、密钥管理、网络与设备矩阵验收。

LiveKit 的 [服务端令牌文档](https://docs.livekit.io/home/server/generating-tokens/) 与 [RoomService API](https://docs.livekit.io/reference/other/roomservice-api/) 是权限和撤销行为依据；Web SDK 使用 [官方 JavaScript 客户端](https://github.com/livekit/client-sdk-js)。
