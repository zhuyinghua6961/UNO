# stage17 本地容器栈增量验收

日期：2026-09-23。版本：本记录对应本次提交的源码；没有验证公网部署或媒体链路。

## 环境与操作

- 用 `docker compose -p uno-stage17-check` 启动独立测试项目，使用新建的 `uno-stage17-check_postgres-data` 卷，未修改既有 `uno-dev` 数据卷。组合文件为 `deploy/compose.yaml` 和 `deploy/compose.auth-local.yaml`，环境变量来自未入库的 `deploy/.env`。
- Docker Hub 令牌端点在本机拒绝连接；从公共 ECR 镜像源拉取相同官方镜像标签并在本地重新标记后，四个应用镜像均完成构建。后端 Dockerfile 使用共享 Maven 构建缓存及 `maven.test.skip`；独立的 `mvn -Pdatabase-it verify` 已在前序验收运行，不把镜像构建当成测试通过。
- `docker compose ... up -d --wait` 后 PostgreSQL、Mailpit、Identity、Game、Gateway、Web 六个服务均健康。Web `http://127.0.0.1:8088/` 返回 200，经 Web Nginx 的 `/api/system/bootstrap` 返回已启用的 authentication、rooms、gameplay、roomText。
- `EXPECT_AUTH_AVAILABLE=true EXPECT_GAME_AUTH_AVAILABLE=true node tools/smoke-api.mjs` 通过，包含 Gateway 路由、功能标识和未登录默认拒绝。
- `node tools/smoke-auth-chat.mjs` 通过：两个随机测试账号通过 Mailpit 收到验证邮件，验证并以 App 凭证登录；创建/加入经典房间，双向发消息，检查同一 `clientMessageId` 幂等、消息顺序、历史读取和退出后失去权限。脚本只输出结果，不输出邮件令牌或登录凭证。

## 范围与后续

此验收证明当前账号、房间和房间文字的镜像与本地 Compose 路径可用。未验证容器内整局对战、Flutter 连接该栈、2v2、队伍文字、语音、TLS/TURN、跨网连接、备份恢复、CI、正式发布及长期运行。基础镜像网络可达性仍依赖运行环境；镜像源绕过是本机准备步骤，未写入生产构建配置。
