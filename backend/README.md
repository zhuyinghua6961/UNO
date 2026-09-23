# Spring Cloud 后端骨架

Java 17 / Spring Boot 4.0.8 / Spring Cloud 2025.1.3，Maven 多模块。

| 模块 | 默认端口 | 现状 |
| --- | --- | --- |
| gateway | 8080 | Spring Cloud Gateway，HTTP 与 `/ws/game` WebSocket 路由 |
| identity-service | 8081 | 默认关闭的邮箱认证、会话、邮件与受限内部身份核验 |
| game-service | 8082 | 状态、受保护session、等待房间与经典对局 HTTP API；`/ws/game` 认证订阅、动作回执及私有推送 |
| game-core | 无 | 无 Spring 依赖的通信权限策略、消息约束及经典 UNO 纯规则引擎 |
| integration-tests | 无 | 真实三进程＋隔离数据库/SMTP的跨服务验收，不参与部署 |

```sh
mvn verify
```

普通 `mvn verify` 运行单元测试；数据库验收必须运行 `mvn -Pdatabase-it verify`，要求 Docker，使用临时 PostgreSQL 17，不能用共享数据库替代。

identity/game已接入JDBC + Flyway，启动前配置各自DB_URL/DB_PASSWORD。identity V1/V2为账号凭证基础、V3增加认证生命周期；game V1建schema、V2建等待房间、V3建经典对局与席位快照。identity和game身份链路均须显式配置启用；Redis/LiveKit仍未接入。见 [数据库指南](../docs/persistence.md)、[认证指南](../docs/authentication.md) 与 [跨服务认证](../docs/service-authentication.md)。

identity显式启用后开放注册/登录/验证等入口并对me/logout逐请求验证会话，Web同时要求CSRF/Origin；没有默认管理员。game放行健康与bootstrap，启用认证后session与房间接口逐请求调用identity核验，其他业务请求仍拒绝。就绪探针包含数据库，存活探针不依赖数据库，响应不公开详情。

跨服务集成测试必须从聚合根运行 `mvn -f backend/pom.xml -Pdatabase-it clean verify`（在仓库根执行），不要只运行integration-tests模块。测试使用新建容器和不可变JAR副本；生产TLS/邮件供应商未纳入本地验收。

game-core 的 CommunicationPolicy 必须只接收服务器构造的可信房间快照。不能把客户端提交的 players/team/generation 直接反序列化后调用它并认为完成授权。经典 UNO 规则引擎另有独立测试；完整 `UnoState` 只能由服务器保存，客户端仅接收各自的 `UnoView`。见 [规则说明](../docs/rules-classic-v1.md)。

已完成账号身份与等待房间成员 API、经典纯规则引擎及 HTTP/WebSocket 对局动作；后续顺序：服务器计时与双端牌桌 → 2v2 规则与文字 → LiveKit 准入与撤销。Spring Cloud 注册中心和配置中心暂不引入；部署用环境变量与 Docker DNS。
