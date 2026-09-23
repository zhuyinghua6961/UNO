# stage7 初段验证：经典对局后端

日期：2026-09-23。范围：经典等待室启动、服务器牌局快照、成员私有读取、HTTP/WebSocket 动作与命令去重。

## 已验证

- `mvn -f backend/pom.xml test -q`：后端单元测试通过。
- `mvn -f backend/pom.xml -pl game-service -am -Pdatabase-it verify -q -Duno.postgres.image=postgres:16-alpine`：game-service 的 10 个 PostgreSQL 集成测试及其依赖模块测试通过。对局服务测试覆盖准备/版本/房主限制、重复启动、席位空缺重新编号、个人视图与非成员拒绝、状态 JSONB 恢复、动作落地、重复 `commandId`、旧版本及换内容重试拒绝；原有房间和迁移测试也通过。
- 真实 WebSocket 测试客户端分别以两位玩家及局外人身份连接：验证原生 App Bearer 与浏览器 Cookie/Origin 握手、非法 Origin 拒绝、身份隔离、私有快照、动作确认与对手更新、重复命令不再广播、重连后恢复最新视图、会话撤销后关闭连接。测试使用可控身份核验替身，未声称完成真实跨服务账号握手联调。
- 对局状态只通过 `UnoView` 返回；完整手牌与牌堆保存在服务器的 `UnoSnapshot`。测试检查了序列化响应含本人手牌，不含 `drawPile` 字段。

本机拉取默认 `postgres:17-alpine` 无进展，改用已有 PostgreSQL 16 镜像完成上述集成验证；不能把这次结果算作 PostgreSQL 17 验证。

## 尚未验证或实现

HTTP 控制器的真实跨服务账号连接、服务器计时、多人经网络完整打完一局及双端牌桌仍在 stage7/8/9 后续范围。WebSocket 已实现单连接消息体积与速率限制，HTTP 同类限制仍待补。当前 `GAME_AUTH_ENABLED` 默认关闭，真实会话与 game 鉴权须按现有配置显式启用。
