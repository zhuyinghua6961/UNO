# stage17 本地容器栈增量验收

日期：2026-09-23。版本：本记录对应本次提交的源码；没有验证公网部署或媒体链路。

## 环境与操作

- 用 `docker compose -p uno-stage17-check` 启动独立测试项目，使用新建的 `uno-stage17-check_postgres-data` 卷，未修改既有 `uno-dev` 数据卷。组合文件为 `deploy/compose.yaml` 和 `deploy/compose.auth-local.yaml`，环境变量来自未入库的 `deploy/.env`。
- Docker Hub 令牌端点在本机拒绝连接；从公共 ECR 镜像源拉取相同官方镜像标签并在本地重新标记后，四个应用镜像均完成构建。后端 Dockerfile 使用共享 Maven 构建缓存及 `maven.test.skip`；独立的 `mvn -Pdatabase-it verify` 已在前序验收运行，不把镜像构建当成测试通过。
- `docker compose ... up -d --wait` 后 PostgreSQL、Mailpit、Identity、Game、Gateway、Web 六个服务均健康。Web `http://127.0.0.1:8088/` 返回 200，经 Web Nginx 的 `/api/system/bootstrap` 返回已启用的 authentication、rooms、gameplay、roomText。
- `EXPECT_AUTH_AVAILABLE=true EXPECT_GAME_AUTH_AVAILABLE=true node tools/smoke-api.mjs` 通过，包含 Gateway 路由、功能标识和未登录默认拒绝。
- `node tools/smoke-auth-chat.mjs` 通过：两个随机测试账号通过 Mailpit 收到验证邮件，验证并以 App 凭证登录；创建/加入经典房间，双向发消息，检查同一 `clientMessageId` 幂等、消息顺序、历史读取和退出后失去权限。脚本只输出结果，不输出邮件令牌或登录凭证。

## 范围与后续

初次验收证明账号、房间和房间文字的镜像与本地 Compose 路径可用；当时尚未验证容器内整局。Flutter 连接该栈、2v2、队伍文字、语音、TLS/TURN、跨网连接、备份恢复、CI、正式发布及长期运行仍未验证。基础镜像网络可达性仍依赖运行环境；镜像源绕过是本机准备步骤，未写入生产构建配置。

## 后续增量

同日更新 Game/Web 镜像并保留原隔离数据库卷，Flyway 从 V5 增量升级至 V6。`SMOKE_FULL_MATCH=true node tools/smoke-auth-chat.mjs` 经容器 Gateway 使用两个新账号完成一场经典局，终局后两个账号的私有历史包含相同对局 ID 且胜负相反；详情见 [stage16 增量验收](verification-stage16-history.md)。因此“容器内整局对战”已验证；Flutter 连接该容器栈及其余限制仍未验证。

2026-09-24 再次保留该卷并将 Game 迁移至 V7，重建 Game/Web 镜像。四个新账号通过公开 API 打完整场 2v2，各自历史胜负和房间重置均核对通过；见 [stage10 增量验收](verification-stage10-team.md)。

同日继续保留该卷并将 Game 迁移至 V8：四个新账号的 A/B 队文字历史互相隔离，伪造对方频道请求被拒，随后仍能正常打完 2v2；见 [stage11 队伍文字增量验收](verification-stage11-team-text.md)。
