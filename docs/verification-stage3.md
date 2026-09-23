# stage3 增量验收记录

历史快照：本文保留首次账号后端验收（50项）的原始边界。后续已接入game身份和Web账号UI，当前状态以 [跨服务验收](verification-service-auth.md)、[Web验收](verification-stage4-web.md) 和 [阶段索引](README.md) 为准。

日期：2026-09-07。基于本地提交bc1ddbc继续开发，范围为可关闭的邮箱认证后端；不是全阶段完成、双端登录或公网发布。

## 自动检查

| 检查 | 实际结果 |
| --- | --- |
| Maven clean verify，database-it profile | 全部模块通过，重新生成三个JAR |
| 原game-core测试 | 10个通过 |
| AuthPrimitivesTest | 新增4个通过：随机凭证、AES-GCM篡改/收件人绑定、安全配置、密码编码 |
| AuthIT | 新增14个通过：真实HTTP、PostgreSQL17、Mailpit SMTP，无跳过 |
| DatabaseIT | 原13个通过，升级验证已覆盖identity V3 |
| GameDatabaseIT | 原1个通过 |
| 本地配置工具 | 2个通过，新增AUTH_MAIL_KEY仍保持旧值不覆盖 |
| Vue | 3个测试通过，TypeScript/Vite生产构建通过 |
| Flutter | 3个测试通过，dart analyze --format=machine退出0 |
| 总计 | 50个测试通过：原32个＋新增18个 |

认证集成测试覆盖：两个独立真实账号、未验证拒绝登录、重复注册不覆盖已激活密码、待验证重注册使旧邮件失效、未知/错误/禁用账号通用失败、凭证单次使用和过期、App刷新轮换与旧凭证重放撤销、并发刷新、退出撤销、密码重置后全设备失效、Web Origin/CSRF与HttpOnly Cookie、不混用Web/App凭证、拒绝伪造身份头、限流持久化且不信任X-Forwarded-For、请求体大小与错误脱敏、加密发件箱SMTP重试与发出后清除正文、服务重启后会话仍有效。

测试隔离在Testcontainers容器，不读取开发.env或写共享数据库。SMTP使用本项目独立Mailpit容器，不复用机器上的其他项目服务，也不发互联网邮件。

命令：

```sh
mvn -f backend/pom.xml -Pdatabase-it clean verify
node --test tools/init-local-env.test.mjs
npm --prefix web run build
npm --prefix web test
cd flutter
dart analyze --format=machine
flutter test
```

本机Maven使用临时代理settings访问依赖，未写入仓库；Maven运行JDK23，编译target17，本机预览JAR由Java17运行。Web/工具检查使用Node24。报告位于identity-service的target/surefire-reports、target/failsafe-reports及其他模块对应目录，不纳入Git。

## 本地实际联调

- 原有PostgreSQL17卷由identity V1/V2增量迁移到V3，没有删除或重建开发卷。迁移前确认accounts为空。
- 本地.env只补充AUTH_MAIL_KEY，未打印真实密钥、覆盖旧密码或修改全局网络设置。
- 默认Compose继续关闭认证；显式auth-local组合的配置解析通过，Mailpit仅绑定127.0.0.1的28025/21025。实际启动本项目uno-dev-mailpit-1。
- 原生identity与gateway用新JAR启动，端口29081/29080；game继续29082。identity仅回环监听，允许的Web Origin仅localhost/127.0.0.1:5179，SMTP只向本地收件箱发送。
- 本地Vue预览恢复到127.0.0.1:5179，仍为原占位UI，未把按钮伪装成已接入账号功能。
- `EXPECT_AUTH_AVAILABLE=true API_BASE_URL=http://127.0.0.1:29080 node tools/smoke-api.mjs`通过：认证开关真实、游戏开关全false、拒绝匿名及伪造身份头。
- 经网关实际完成：随机临时邮箱注册→Mailpit收验证码→验证→登录→当前用户→刷新→旧访问凭证失效→退出→新访问凭证失效。密码与令牌没有输出到日志/最终回复；测试账号由本轮创建，不是演示账号。
- 流程结束后仅清理本轮随机邮箱对应的账号和发件箱行，未清空数据库；开发收件箱可能保留已使用且失效的测试邮件。

SMTP验证初次遇到本地反向DNS/短时响应超时：测试Mailpit关闭反向DNS查询、集成测试设置有界10秒SMTP超时并使用明确localhost标识。最终完整回归与实际网关流程均通过，生产默认SMTP超时仍为3秒且默认要求STARTTLS。

最后补充配置对象日志脱敏测试并重新打包时，覆盖了预览进程正在使用的JAR，触发类加载失败及504。已只重启本项目三个后端进程，并改从独立临时目录的不可变JAR副本运行，避免后续构建再覆盖正在运行的包；不是数据库迁移丢失数据。

## 未完成与边界

- stage3保持部分完成：邮箱登录渠道待最终确认，identity到game的内网认证/服务认证尚未交付。
- game只拒绝业务请求，不通过信任网关userId头或共享账号库冒充已接入认证。
- Web/Flutter账号UI、安全存储、自动刷新和端上完整账号流程属于stage4，未实现；Flutter真机与浏览器真实UI操作未在本轮验收。
- 未配置真实SMTP供应商、域名、邮件送达率、公开注册或生产TLS；没有邮件管理/账号管理后台、审计平台和数据保留自动化。
- 单IP限流目前不信任转发头，网关后会聚合来源，需要后续可信代理链/边缘限流方案；不是公网容量方案。
- 未重新构建并联调全部Docker应用镜像、未构建APK/IPA、未发布新制品。
- 本轮未自动提交、推送或创建分支；release旧scaffold制品仍保留，不代表本轮版本。
