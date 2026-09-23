# stage3：后端账号与会话

更新：2026-09-23。邮箱＋密码为**可关闭的本地开发方案**，不是已经获得确认的公开注册策略。Web 与 Flutter 账号 UI 已接入。默认 `AUTH_ENABLED=false`，只有显式配置后才接受账号操作。

## 数据与凭证

- identity V3 增加邮箱验证时间、App 刷新凭证链、持久化限流桶与加密邮件发件箱；V1/V2不修改。
- 密码要求15–128个Unicode字符，使用Spring Security的PBKDF2-HMAC-SHA256，600,000次迭代、随机16字节盐和带版本前缀的编码。没有明文密码或自制密码散列。
- 邮箱规范化为首尾去空白并转小写；昵称只是展示字段。注册创建PENDING账号，验证后ACTIVE。未验证或DISABLED账号不能登录。
- 验证凭证24小时，重置凭证30分钟，均为32字节安全随机数的URL安全编码；库内仅保存SHA-256摘要。新申请作废同用途旧凭证，消费在账号行锁保护下完成。
- 对PENDING邮箱重复注册会更新待验证密码、昵称并重新签发验证凭证，旧邮件失效；避免攻击者预注册的旧密码被邮箱持有人意外激活。ACTIVE重复注册不会修改密码。
- Web会话固定12小时，通过host-only、HttpOnly、SameSite=Strict Cookie传递；HTTPS模式Cookie带Secure和`__Host-`前缀。不向JSON返回Web访问凭证。
- App访问凭证15分钟，刷新凭证所属会话最多30天；刷新同时轮换访问与刷新凭证，不滑动延长30天上限。过期访问凭证仍可用尚有效的刷新凭证换新。
- 刷新旧凭证重放会撤销其整个会话，且撤销事务提交后才返回401；两个并发刷新只允许一个成功，另一请求按重放撤销该会话。App必须串行刷新，不能盲目并发重试；响应丢失时可能需要重新登录。
- 退出撤销当前会话；密码重置撤销账号所有旧会话；禁用账号即刻阻止后续身份检查与刷新。已开始的请求不具备中途撤销保证，实时连接撤销在stage12/13实现。
- 同账号可多设备登录，每次登录是独立会话；没有“最后登录者踢掉前者”的隐式规则。

## HTTP接口

所有写接口使用JSON。Web写请求需要明确允许的Origin和CSRF令牌；原生App使用`X-UNO-Client: APP`，不得同时携带Origin、Sec-Fetch-Site或任何Cookie。

| 方法与路径 | 请求 | 响应 |
| --- | --- | --- |
| GET /api/auth/status | 无 | backend-auth阶段；loginAvailable/registrationAvailable反映开关，不保证邮件投递 |
| GET /api/auth/csrf | Web同源请求 | headerName、token；浏览器保存HttpOnly CSRF Cookie |
| POST /api/auth/register | email,password,nickname | 202通用文案，无论重复邮箱与否，不返回userId或邮件凭证 |
| POST /api/auth/verification/request | email | 202通用文案 |
| POST /api/auth/verify-email | token | 204；无效/过期/已用400 |
| POST /api/auth/login | email,password | Web：user,expiresAt＋Cookie；App：user,accessToken,tokenType,expiresAt,refreshToken,refreshExpiresAt |
| POST /api/auth/refresh | token（刷新凭证） | 仅App；返回新的完整凭证组 |
| GET /api/users/me | Web Cookie或App Bearer | id,email,nickname；不读客户端传入的userId |
| POST /api/auth/logout | 当前有效会话，无需body | 204；Web同时清除会话与CSRF Cookie |
| POST /api/auth/password/forgot | email | 202通用文案 |
| POST /api/auth/password/reset | token,password | 204，旧会话全部撤销 |
| POST /api/users/me/profile | nickname | 仅当前登录用户；返回更新后的 id,email,nickname，其他有效会话重新读取后可见 |

Web在登录前先GET csrf，将返回的token放入`X-CSRF-TOKEN`；登录成功会清除旧CSRF Cookie，应再次GET csrf获取新token，再进行退出等写操作。不能只取Cookie内容代替接口返回的token。前端不得将长期身份凭证放localStorage。

昵称更新沿用注册时的校验：首尾空白剔除后为 1–40 个 Unicode 码点，不能含控制字符；不修改邮箱、密码或会话凭证。房间成员昵称与历史战绩昵称保留当时的快照，下一次入房才使用新昵称。

App Bearer仅接受APP会话；Web Cookie仅接受WEB会话。`X-UNO-Client`只选择传输方式，不证明身份，也不授予权限。浏览器伪装为App时仍因Origin/Fetch Metadata/Cookie组合而拒绝；没有启用宽泛CORS。

错误结构`code,message,requestId`；请求ID由服务端生成，不信任外部值。常见400 INVALID_INPUT/INVALID_TOKEN、401 INVALID_CREDENTIALS、403 REQUEST_NOT_ALLOWED、413 BODY_TOO_LARGE、429 RATE_LIMITED、503 AUTH_UNAVAILABLE。错误密码、未知账号、未验证和禁用状态共享401反馈；429带Retry-After。数据库异常不返回SQL、堆栈或配置。HTTP响应不缓存。

## 邮件与本地运行

邮件通过数据库事务发件箱异步发送，不在注册请求内等待SMTP，不把原始验证凭证返回HTTP或写到日志。发件箱正文用AES-256-GCM加密，随机nonce且绑定消息ID/收件人；独立`AUTH_MAIL_KEY`来自环境。SMTP投递成功即清除加密正文；SMTP失败仅记录DELIVERY_FAILED并指数退避，最多5次；到期不再发送。

这是至少一次投递：SMTP已接受而数据库提交失败时可能收到重复邮件，但凭证只可消费一次。202只表示接收请求，并不表示邮件已送达。失败超过重试次数时用户可重新申请；本轮没有邮件运维UI或投递保证。

本地测试使用Mailpit，不发送到互联网邮箱，不将开发收件箱当成正式邮件供应商。不要配置SMTP转发或暴露Mailpit端口到公网。

```sh
node tools/init-local-env.mjs
docker compose --env-file deploy/.env -f deploy/compose.yaml -f deploy/compose.auth-local.yaml up --build -d
```

上述是本地组合配置，完整镜像构建仍需本机网络可用；并不代表本轮完成全部容器验收。Mailpit网页回环端口28025，SMTP回环端口21025；可使用MAILPIT_WEB_PORT/MAILPIT_SMTP_PORT覆盖。该配置关闭SMTP TLS仅用于同机测试；Java默认要求STARTTLS。

只运行原生Java后端时，启动项目自己的postgres和mailpit容器，载入数据库指南中的连接变量，再设置：

```sh
export AUTH_ENABLED=true
export AUTH_COOKIE_SECURE=false
export AUTH_ALLOWED_ORIGINS=http://localhost:5179,http://127.0.0.1:5179
export SMTP_HOST=127.0.0.1
export SMTP_PORT=21025
export SMTP_STARTTLS=false
```

AUTH_MAIL_KEY由初始化脚本生成，需从受信本地.env载入，但不要在终端输出它。禁用认证时无需这个密钥；启用时无有效密钥/Origin会启动失败。不安全Cookie只允许loopback Origin，其他Origin必须HTTPS且启用Secure。

Mailpit为无鉴权本地测试工具，机器上其他用户能看到邮件凭证，不用于真实账号。邮件提供供用户复制的一次性凭证，不生成含凭证的URL。在Web的 `/login` 页面选择“验证邮箱”或“设置新密码”，粘贴最新邮件的Token字段；开发收件箱端口以Compose配置为准（当前本机28025）。

Web已实现注册到退出的流程、`/account`用户状态及游戏身份核对。前端先检查功能开关，每次写请求从csrf端点取新凭证；密码/邮件凭证只用于当前提交，不写入Web Storage，不自动重试写请求。刷新、聚焦及可见时60秒轮询恢复当前用户，网络不明时隐藏资料；退出必须收到成功或已失效反馈才清除状态。详见 [Web验收](verification-stage4-web.md)。Flutter账号页也已接入同一API，原生凭证保存在系统安全存储，轮换刷新由单进程会话模块串行化；设备和跨端联调状态见 [Flutter验收](verification-stage4-flutter.md)。

## 权限、限流与运维边界

- identity服务逐请求查询会话和账号状态；绕过gateway直连也不能跳过校验。网关移除常见伪造身份头，identity本身完全不使用它们。
- game-service已接入逐请求远程会话校验并开放只读`GET /api/system/session`；`bootstrap.features.authentication`反映GAME_AUTH_ENABLED，默认false。其他游戏操作仍拒绝。
- identity到game的内部协议与独立服务凭证已实现，不共享用户数据库、不信任网关userId头；默认要求HTTPS，仅显式本地配置允许明文。详细协议、超时及安全范围见 [跨服务身份说明](service-authentication.md)，没有宣称mTLS或公网部署已经交付。
- 限流记录存PostgreSQL，15分钟窗口；默认同远端连接地址120次、同邮箱同类请求10次。密码散列前先限流；失败不会回滚计数，进程重启也保留。
- 不信任X-Forwarded-For/Forwarded；经过网关时IP限流会聚合网关来源，这是保守开发限制，不适用于公网规模。生产须明确可信代理链与边缘限流，不能直接信任任意转发头。
- 认证请求体上限16KiB（包括无Content-Length的请求）；字符串限制另行校验。反向代理请求时限、连接限流和公网抗滥用仍需stage17。
- 基础禁用能力是服务内部`disableAccount`事务；没有可供普通用户调用的管理员API或默认管理员。生产管理入口/审计另行设计，不开放调试路由。
- 发件箱密钥不能随意轮换，否则旧排队邮件不能解密；轮换需先排空/过期或准备兼容密钥方案。数据库备份与密钥分离保存。
- 用户资料、凭证摘要和邮件元数据仍属敏感数据；保存周期、审计日志、自动清理和真实邮件域名配置需上线前确定，不宣称本地默认配置已满足生产要求。

## 验证

```sh
mvn -f backend/pom.xml -Pdatabase-it clean verify
node --test tools/init-local-env.test.mjs
EXPECT_AUTH_AVAILABLE=true EXPECT_GAME_AUTH_AVAILABLE=true API_BASE_URL=http://127.0.0.1:29080 node tools/smoke-api.mjs
```

认证集成测试使用独立PostgreSQL17与Mailpit容器，不写开发/生产数据。没有Docker时不能将数据库/SMTP测试算作通过。后续跨服务测试会在backend/integration-tests启动三个真实JAR进程，不通过模拟身份接口代替端到端验证。实际结果见stage3文档与增量验收记录。
