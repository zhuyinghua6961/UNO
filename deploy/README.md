# Docker 开发部署

这些配置用于本地开发与集成验证，不是公网生产配置。经典对局、四人 2v2、房间与队伍文字已有可用路径；Web 队友语音已在本机媒体环境验证，Flutter Android/iOS 模拟器的仅收听信令已通过，真实麦克风互听仍待验证。

## 启动主栈

在仓库根目录执行：

```sh
node tools/init-local-env.mjs
docker compose --env-file deploy/.env -f deploy/compose.yaml config --quiet
docker compose --env-file deploy/.env -f deploy/compose.yaml up --build -d
```

Web 默认 `http://localhost:8088`，Gateway 默认 `http://localhost:28080`；Java 内部进程不发布宿主端口。端口已被占用时修改 deploy/.env，不要终止其他项目的进程。

构建需要拉取基础镜像和 Maven/npm 依赖。后端镜像共享 BuildKit Maven 缓存，加快三个服务的连续构建；镜像内跳过测试，提交前仍须独立运行 Maven 测试。网络代理应按本机 Docker/包管理器配置，不把个人代理地址硬编码进镜像或仓库。若 Docker Hub 令牌服务不可达，可在可信镜像源预取相同官方标签并在本机核对后重新标记；[容器栈增量验收](../docs/verification-stage17-container.md)记录了一次实际构建。

```sh
docker compose --env-file deploy/.env -f deploy/compose.yaml logs --tail=100
docker compose --env-file deploy/.env -f deploy/compose.yaml down
```

不要随意增加 `-v`，以免删除启用基础设施后创建的数据库卷。启动完成仍要检查 bootstrap/auth 状态和真实业务链路。可运行 `EXPECT_AUTH_AVAILABLE=true EXPECT_GAME_AUTH_AVAILABLE=true node tools/smoke-api.mjs`；合并账号覆盖配置并启用 Mailpit 时，再运行 `node tools/smoke-auth-chat.mjs`。若需额外验证真实经典局从开局至战绩，运行 `SMOKE_FULL_MATCH=true node tools/smoke-auth-chat.mjs`，它会建立两个随机测试账号并执行整局。Compose 健康检查只证明服务就绪，不能替代业务验收。

## 数据库与可选基础设施

默认账号功能关闭。若需本地测试真实账号后端，显式合并 `compose.auth-local.yaml`，它启用邮箱认证并添加仅回环可访问的Mailpit，不发送公网邮件。先重新运行配置初始化脚本补充AUTH_MAIL_KEY，旧密钥不会覆盖。详见 [账号运行指南](../docs/authentication.md)。

PostgreSQL 17 现在是默认必需服务；identity/game 使用不同的库、非超级用户角色和随机密码，启动依赖数据库健康。空卷通过 `postgres/10-create-service-databases.sql` 初始化，后续应用启动执行 Flyway。

`node tools/init-local-env.mjs` 会保留已有秘密，仅补缺失的服务密码等设置。已有数据卷不会自动重跑初始化，环境变量也不会自动修改库内密码。**禁止为解决初始化问题删除数据卷**；升级、原生 Java 连接、备份恢复及权限说明见 [数据库指南](../docs/persistence.md)。

只启动本机数据库并开放回环端口：

```sh
docker compose --env-file deploy/.env -f deploy/compose.yaml -f deploy/compose.local.yaml up -d --wait postgres
```

```sh
docker compose --env-file deploy/.env -f deploy/compose.yaml --profile infrastructure up -d
docker compose --env-file deploy/.env -f deploy/compose.yaml --profile voice up -d
```

infrastructure 额外提供 Redis 7.4，当前 Java 进程还没有使用它。voice 提供 LiveKit 1.13.6；只启动媒体容器不会打开应用语音。要在本机对 Web 牌桌启用服务端准入和开麦界面，合并 `compose.auth-local.yaml` 与 `compose.voice-local.yaml` 并启用 voice profile：

```sh
docker compose --env-file deploy/.env -f deploy/compose.yaml -f deploy/compose.auth-local.yaml -f deploy/compose.voice-local.yaml --profile voice up --build -d
SMOKE_TEAM_MATCH=true SMOKE_TEAM_VOICE=true node tools/smoke-auth-chat.mjs
npm --prefix web run test:e2e:voice
```

Web 首次点击“加入队友语音”才请求麦克风。语音令牌从真实对局和队伍席位签发，LiveKit API 凭证只在 game-service 环境中。默认服务未启用语音；App 已接入，服务端会话撤销和对局结束时的媒体撤销换房已在本机验证。真实设备互听与跨网恢复仍需验收。[语音增量验收](../docs/verification-stage13-14-voice.md)记录了实际媒体测试和限制。

本地 LiveKit 广告媒体 IP 固定 127.0.0.1，端口 7880（信令）、7881/TCP、7882/UDP，仅用于同一台机器验证。容器内连接、手机模拟器或实机不应直接照搬这个广告地址。

## 生产上线前必须另行完成

- 域名、HTTPS/WSS、Cookie/CSRF/Origin 校验，账号和业务对象授权。
- LiveKit 的公网或内网可达地址、TLS、媒体端口、防火墙、TURN/TLS 中继与跨网测试。
- 权限最小化、密钥管理与轮换；任何真实 .env 不入 Git、不进 release。
- 数据库迁移、备份、Redis 安全策略、健康就绪探针、资源限制和日志脱敏。
- 媒体成员封禁、真机异常退出与跨网恢复验收；不能仅凭 JWT 有效期控制已连接用户。
- 镜像仓库、不可变 digest、漏洞扫描、灰度与回滚。

不要用 Java WebSocket 转发音频二进制，也不要认为 Nginx 配好了 WebSocket 就解决了 WebRTC 连通问题。两条通道独立部署与验证。
