# stage3：账号与游戏服务之间的身份校验

更新：2026-09-07。已实现默认关闭的跨服务身份校验；不是游戏房间授权、WebSocket登录或公网生产部署。

## 请求链路

```text
浏览器Cookie / App Bearer
  → Gateway（仅转发公开路径，不创造身份）
  → game-service（校验客户端类型、Origin与CSRF策略）
  → identity-service /internal/auth/introspect（独立服务凭证＋真实用户凭证）
  → 查询会话、过期时间、撤销时间和账号状态
  → game-service获得服务器确认的userId/sessionId
```

客户端提交的userId、昵称、X-User-Id、X-Authenticated-User等都不能生成或覆盖登录身份。game不读账号数据库；identity不向game提供密码、邮箱、昵称或刷新凭证。直接访问game与经过gateway访问遵循同样的身份校验。

## 已实现的公开接口

`GET /api/system/session`：在身份确认后返回当前调用者的四个字段：

```json
{
  "userId": "server-verified-user-uuid",
  "sessionId": "server-verified-session-uuid",
  "clientType": "APP",
  "expiresAt": "UTC ISO-8601 timestamp"
}
```

这不是可由玩家选择的身份，也不会回显用户凭证。成功响应禁止缓存。缺失、过期、已撤销、错误类型或已禁用账号返回401；身份依赖故障返回503；不允许的Origin、Cookie/App混用及无权限操作返回403。错误结构为code/message/requestId，不返回内部响应正文、凭证、SQL或堆栈。

Web沿用identity签发的host-only HttpOnly Cookie，名为`__Host-UNO-SESSION`；仅本地不安全模式改为`UNO_SESSION_DEV`。两端必须使用相同Secure模式及Origin白名单。所有未来Web写请求仍经过CSRF过滤器，不能因已通过远程身份校验就禁用CSRF。

原生App须带`X-UNO-Client: APP`与`Authorization: Bearer <访问凭证>`，不能同时带Cookie、Origin或Sec-Fetch-Site。刷新凭证不能当作访问凭证；Web会话也不能作为App Bearer使用。

当前只开放这个只读身份接口；房间、文字、语音和WebSocket操作仍拒绝。**身份校验不等于房间成员授权**，后续必须继续依据可信房间/对局状态验证每次操作。

`GET /api/system/bootstrap`的stage仍为scaffold；features.authentication反映GAME_AUTH_ENABLED配置，其他功能仍false。该开关表示功能配置，不是对下游可用性的实时健康保证。

## 仅服务内部可用的协议

路径：`POST /internal/auth/introspect`，只允许game-service的独立凭证，最大请求体1024字节。

- 服务身份：HTTP Basic的用户名固定game-service，密码来自独立的IDENTITY_GAME_SERVICE_KEY。该密钥由配置工具生成32字节随机值，与邮件加密密钥、数据库密码分开。
- 用户请求体：`{"token":"<用户访问凭证>","clientType":"APP或WEB"}`。凭证放正文，不放URL；不转发用户原始头部到内部调用。
- 有效响应：active=true、protocolVersion=1、audience=game-service、userId、sessionId、clientType、expiresAt。
- 无效用户凭证：HTTP200、`{"active":false}`；无效服务凭证401，禁止的传输方式403，限流429，依赖不可用503。
- game逐项检查响应类型、协议版本、受众、UUID、客户端类型与过期时间；非200、无效JSON、错误Content-Type或缺字段都拒绝，不把“解析失败”当作匿名成功。
- 读取上限4096字节；默认连接超时1秒、完整请求/响应预算2秒，不自动重试，不跟随重定向。已经返回响应头但一直不结束的正文也受总超时限制。
- 没有正向身份缓存，因此退出、刷新轮换、密码重置、禁用会在下一次游戏HTTP身份检查生效。已经开始执行的请求不保证中途取消，WebSocket连接撤销仍由stage12/13实现。

identity对内部路径使用单独SecurityFilterChain，不接受用户Bearer、浏览器Cookie、Origin或Fetch Metadata。只有服务凭证通过后才解析JSON和访问数据库。内部调用计数存PostgreSQL，默认game-service共用15分钟10000次窗口，达到限制后game返回503；这是开发上限，不是生产容量承诺。

Gateway没有`/internal/**`路由，访问得到404；常见伪造身份头被剥离，但即使绕过gateway也不能靠它们授权。没有对浏览器公开内部凭证交换入口。

## TLS与配置边界

默认要求HTTPS：game校验identity的服务器证书与主机名，identity要求请求本身为TLS。不信任X-Forwarded-Proto将明文请求伪装成HTTPS。生产应直接启用identity的TLS或保证最后一跳也是TLS，不能把开发明文开关当作生产方案。

本轮实现的是“HTTPS服务器身份＋独立game服务密钥”，**不是双向TLS证书认证**。正式CA/证书轮换、mTLS、密钥轮换重叠窗口、秘密管理平台和公网网络验收仍属于部署阶段。

| 环境变量 | 作用 | 默认 |
| --- | --- | --- |
| IDENTITY_INTERNAL_AUTH_ENABLED | identity启用内部核验 | false |
| GAME_AUTH_ENABLED | game启用会话校验 | false |
| IDENTITY_GAME_SERVICE_KEY | 两个服务共用的专用随机密钥 | 未设置，启用后必填 |
| IDENTITY_INTERNAL_URL | game访问的identity源地址，不允许路径、用户信息、query或fragment | https://identity-service |
| IDENTITY_INTERNAL_ALLOW_HTTP | identity允许开发明文内网调用 | false |
| GAME_AUTH_ALLOW_HTTP | game允许开发明文调用 | false |
| AUTH_COOKIE_SECURE / AUTH_ALLOWED_ORIGINS | 与identity的浏览器策略一致 | Secure且白名单为空 |

开发明文客户端地址只接受loopback或Compose的identity-service主机名，且必须显式开启；不能配置任意外部HTTP地址。生产凭证应由环境或秘密管理注入，不能进入源码、前端、日志和release。

## 本地启动与更新

在项目根目录：

```sh
node tools/init-local-env.mjs
docker compose --env-file deploy/.env -f deploy/compose.yaml -f deploy/compose.auth-local.yaml config --quiet
docker compose --env-file deploy/.env -f deploy/compose.yaml -f deploy/compose.auth-local.yaml up --build -d
```

初始化工具只补缺失的IDENTITY_GAME_SERVICE_KEY，不覆盖已有秘密。auth-local覆盖文件会同时配置identity、game和Mailpit；密钥不传给gateway、web或mailpit。基础Compose保持认证默认关闭。全栈镜像构建依赖本机网络，不因为配置解析通过就视为容器验收通过。

原生Java启动时，在既有数据库与邮件变量之外：

```sh
export IDENTITY_INTERNAL_AUTH_ENABLED=true
export IDENTITY_INTERNAL_ALLOW_HTTP=true
export GAME_AUTH_ENABLED=true
export GAME_AUTH_ALLOW_HTTP=true
export IDENTITY_INTERNAL_URL=http://127.0.0.1:29081
```

两服务分别从受信.env载入同一个IDENTITY_GAME_SERVICE_KEY，game的Origin/Cookie配置与identity保持一致。gateway无需该密钥。启动前先构建，再从不可变JAR副本运行，避免后续clean/package覆盖正在运行的文件。

`/actuator/health/readiness`目前包含本服务数据库，不聚合远程identity；identity故障时game的健康可能仍为UP，但受保护请求明确503。`/api/system/session`用于验证完整身份链路，不能仅检查bootstrap来宣称身份依赖正常。

## 测试与下一阶段

从backend根聚合构建运行，而不是只启动integration-tests模块：

```sh
mvn -f backend/pom.xml -Pdatabase-it clean verify
EXPECT_AUTH_AVAILABLE=true EXPECT_GAME_AUTH_AVAILABLE=true API_BASE_URL=http://127.0.0.1:29080 node tools/smoke-api.mjs
```

backend/integration-tests只存测试，不是部署服务。它使用隔离PostgreSQL17与Mailpit，顺序启动真实identity/game/gateway JAR副本，验证用户注册邮件、两类会话、权限隔离、撤销和依赖中断，并清理测试进程/容器。无Docker或缺构建JAR时应失败，不跳过。

Web已按本地邮箱方案接入账号UI与会话恢复，见 [Web验收](verification-stage4-web.md)。下一步为Flutter登录UI、安全存储与刷新轮换。邮箱渠道与是否公开注册仍需最终确认；当前阶段不默认为已获产品批准。
