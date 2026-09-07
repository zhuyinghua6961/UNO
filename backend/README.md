# Spring Cloud 后端骨架

Java 17 / Spring Boot 4.0.8 / Spring Cloud 2025.1.3，Maven 多模块。

| 模块 | 默认端口 | 现状 |
| --- | --- | --- |
| gateway | 8080 | Spring Cloud Gateway，HTTP 与 WebSocket 预留路由 |
| identity-service | 8081 | 公开账号能力状态；真实身份功能未实现 |
| game-service | 8082 | 公开协议/功能状态；WebSocket handler 未实现 |
| game-core | 无 | 无 Spring 依赖的通信权限策略与消息约束，10 个测试 |

```sh
mvn verify
```

普通 `mvn verify` 运行单元测试；数据库验收必须运行 `mvn -Pdatabase-it verify`，要求 Docker，使用临时 PostgreSQL 17，不能用共享数据库替代。

identity/game 已接入 JDBC + Flyway，启动前必须配置各自 DB_URL/DB_PASSWORD。identity 的 V1/V2 建立账号、会话与一次性凭证表；game 的 V1 仅创建专属 schema。没有真实登录业务；Redis 和 LiveKit 仍未接入。运行步骤、约束与备份见 [数据库指南](../docs/persistence.md)。

安全配置仅放行健康/就绪/存活与骨架状态端点，其他请求拒绝；后续接认证时应替换为真实的逐对象校验，而不是简单 permitAll。就绪探针包含数据库，存活探针不依赖数据库，响应不公开详情。

game-core 的 CommunicationPolicy 必须只接收服务器构造的可信房间快照。不能把客户端提交的 players/team/generation 直接反序列化后调用它并认为完成授权。10 个测试覆盖通信受众和消息内容，**不覆盖尚未实现的 UNO 玩法**。

后续顺序：账号身份 → 房间成员 → 经典/2v2 规则引擎 → WebSocket 同步与文字 → LiveKit 准入与撤销。Spring Cloud 注册中心和配置中心暂不引入；部署用环境变量与 Docker DNS。
