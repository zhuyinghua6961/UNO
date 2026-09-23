# stage7 初段验证：经典对局后端

日期：2026-09-23。范围：经典等待室启动、服务器牌局快照、成员私有读取、HTTP/WebSocket 动作、命令去重与基本服务器计时。

## 已验证

- `mvn -f backend/pom.xml test -q`：后端单元测试通过。
- `mvn -f backend/pom.xml -pl game-service -am -Pdatabase-it verify -q -Duno.postgres.image=postgres:16-alpine`：game-service 的 15 个 PostgreSQL 集成测试及其依赖模块测试通过。对局服务测试覆盖准备/版本/房主限制、重复启动、席位空缺重新编号、个人视图与非成员拒绝、状态 JSONB 恢复、动作落地、重复 `commandId`、旧版本及换内容重试拒绝；原有房间和迁移测试也通过。
- 真实 WebSocket 测试客户端分别以两位玩家及局外人身份连接：验证原生 App Bearer 与浏览器 Cookie/Origin 握手、非法 Origin 拒绝、身份隔离、私有快照、动作确认与对手更新、重复命令不再广播、重连后恢复最新视图、会话撤销后关闭连接。测试使用可控身份核验替身，未声称完成真实跨服务账号握手联调。
- 对局状态只通过个人 `UnoView` 包装后返回；完整手牌与牌堆保存在服务器的 `UnoSnapshot`。测试检查了序列化响应含本人手牌，不含 `drawPile` 字段。
- V4 迁移和重启通过。测试覆盖普通回合到期后摸到可出牌仍结束回合、并发扫描只落地一次、过期新命令拒绝与旧命令回执重试、+4 的 8 秒窗口和默认接受、超时后向两位 WebSocket 订阅者推送同版本的个人快照。
- 双人 WebSocket 终局链路从合法的临近终局快照出发：最后一张牌经网络出牌，两个连接收到 `MATCH_OVER`，数据库写入 `ENDED` 和 `ended_at`，房间回到 `WAITING` 且准备状态清空；重复动作不重复结算。该测试未模拟从发牌开始的完整 500 分对局。
- 两个 WebSocket 测试客户端还从一副固定种子的完整发牌开始，经网络完成整轮，直至服务器判定赢家。为让单轮达到 `MATCH_OVER`，测试将双方起始积分设置为 499；身份核验使用可控替身，因此仍不能算作真实账号从零积分打到 500 分的跨服务验收。

本机拉取默认 `postgres:17-alpine` 无进展，改用已有 PostgreSQL 16 镜像完成上述集成验证；不能把这次结果算作 PostgreSQL 17 验证。

## 尚未验证或实现

此处记录的是 stage7 初段当时的验证范围。后续 [stage8 Web 验收](verification-stage8-web.md) 已补两个真实账号经 HTTP/WebSocket 从 0 分到 500 分终局并开始第二局。Flutter 牌桌仍待 stage9；连续超时中断、断线保留和跨实例广播仍待 stage12。WebSocket 已实现单连接消息体积与速率限制，HTTP 同类限制仍待补。当前 `GAME_AUTH_ENABLED` 默认关闭，真实会话与 game 鉴权须按现有配置显式启用。
