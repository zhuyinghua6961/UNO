# UNO 好友牌桌

Vue + Spring Cloud + Flutter 的多人卡牌游戏项目。Web 经典局、Android/Web 混合经典局和四账号 2v2 已完成本地整局验收；Web 分别搭配 iOS、Android 模拟器完成混合 2v2 与房间/队伍文字双向互发。Web 队友语音和会话撤销后换房已在本机 LiveKit 验证；Flutter 双平台模拟器已完成仅收听信令，真机互听、弱网与发布验收仍待完成。**完整版本尚未完成。**

## 目录

```text
backend/    Maven 多模块：三个服务、game-core、integration-tests（仅测试）
web/        Vue 3 + TypeScript + Vite + Pinia + Vue Router
flutter/    Android / iOS Flutter 工程
deploy/     Dockerfile、Compose、Nginx、可选 LiveKit
release/    本地发布归档；二进制默认不提交 Git
assets/     已获取素材及来源许可
docs/       设计、领域术语、接口与发布约定
tools/      素材同步、本地配置初始化、发布归档
```

## 当前可用范围

- Web：大厅、经典与 2v2 牌桌、好友房邀请、等待室、房间/队伍文字、2v2 队友语音首版；真实注册、验证、登录、找回/重置、退出、账号页和会话恢复（须显式启用后端）。
- Flutter：大厅、牌桌预览、账号入口、好友房等待室、经典/2v2 牌桌、房间/队伍文字和前台队友语音；Android/iOS 调试构建已通过，真机语音待验收。
- 后端：三个可启动进程、Spring Cloud Gateway 路由、健康检查和公开骨架状态接口。
- 数据库：PostgreSQL 17 双服务独立库与角色、账号/会话/验证令牌表、Flyway 迁移、内部账号仓储；并非已开放登录。
- 账号后端：注册、验证邮箱、登录、当前用户、退出、密码找回/重置；Web Cookie/CSRF与App刷新轮换。默认关闭，仅显式本地配置启用，详见 `docs/authentication.md`。
- 房间基础：真实账号可创建/加入等待室、准备、2v2 选队及离开；房间与队伍文字经认证、限流、幂等和游标查询接入 Web/App。
- 经典规则：`game-core` 已实现独立的 108 张牌规则引擎，支持发牌、牌效、加四质疑、UNO 抓漏喊及计分。见 `docs/rules-classic-v1.md`。
- 对局后端：已准备的经典或四人 2v2 房间可由房主启动，发牌和动作状态持久化；成员可经 HTTP 或 WebSocket 提交动作，WebSocket 按身份推送各自的私有状态；完赛及连续漏回合中断记录按本人查询。经典局轮间超时会自动开始下一轮。
- 队友语音：服务端从进行中的 2v2 席位签发仅麦克风、指定队伍房间的短期凭证；会话吊销后换代次、有效队友自动迁移和旧 JWT 隔离已在本机媒体环境验证。Flutter 客户端换房通过组件测试，真机互听待验收。
- 部署：Web、三个 Java 进程和必需 PostgreSQL 的开发 Compose；Redis、LiveKit 仍为可选配置。

游戏服务已通过内部会话核验确认账号身份，不信任客户端身份头、不共享账号库；支持从Web账号页验证同一userId。详见 `docs/service-authentication.md`。

尚未实现或验收：完整弱网恢复和跨实例接管、文字 WebSocket 消息事件与内容治理、生产 HTTPS/TURN、移动签名。真实麦克风互听、外网邮件服务和真机恢复边界仍待验收。

PostgreSQL 集成测试、容器化账号和四人整局冒烟、Web 浏览器端到端测试均已有记录；各项具体范围和剩余风险见 [阶段验收索引](docs/README.md)。

跨服务身份增量验收见 [身份验收](docs/verification-service-auth.md)，双端账号进度见 [stage4](docs/stages/stage4.md)。账号渠道与设备上的跨端验收仍待确认。

账号和游戏能力只在后端明确启用时开放，没有演示账号或模拟登录。语音默认关闭，本机启用方式与限制见 [部署说明](deploy/README.md) 和 [语音验收](docs/verification-stage13-14-voice.md)。房间与文字实现见 [stage5 验收](docs/verification-stage5.md)、[stage11 记录](docs/verification-stage11-room-text.md)。

## 本地开发

建议 Node.js 24 LTS、Java 17+、Maven 3.9+、Flutter 3.47 / Dart 3.13、Docker Compose。版本锁定见各端依赖文件；后端使用 Spring Boot 4.0.8 + Spring Cloud 2025.1.3。

```sh
node tools/sync-assets.mjs
cd web
npm ci
npm run dev
```

Vite 默认端口 5173，通过 `/api` 与 `/ws` 代理到本机 8080。若使用 Docker 后端，改为 `GATEWAY_URL=http://localhost:28080 npm run dev`。端口被占用时请指定 `-- --port <其他端口>`，不要停止其他项目。

```sh
mvn -f backend/pom.xml verify
mvn -f backend/pom.xml -Pdatabase-it verify
```

identity/game 必须配置数据库才能启动。先按 [数据库与运行指南](docs/persistence.md) 启动 PostgreSQL、载入连接配置，再分别运行三个模块 JAR。默认端口 8081、8082、8080，可通过 SERVER_PORT、IDENTITY_URL、GAME_URL、GAME_WS_URL 覆盖。默认认证关闭；验证账号后端时额外按 [账号指南](docs/authentication.md) 配置本地邮件、开关与 Origin。

```sh
cd flutter
flutter pub get
flutter analyze
flutter test
flutter run
```

移动端账号、房间和经典对局已接入真实网络，`API_BASE_URL` 指向网关。不要误将 Android 模拟器 localhost 当宿主机地址；详见 [Flutter 运行说明](flutter/README.md) 与 [stage9 增量验收](docs/verification-stage9-flutter.md)。

## Docker 与发布

```sh
node tools/init-local-env.mjs
docker compose --env-file deploy/.env -f deploy/compose.yaml up --build -d --wait
```

浏览器打开 `http://localhost:8088`；仅本机可访问。详见 `deploy/README.md`。此配置不是公网生产部署方案。

最新校验的 [0.3.12 本地试玩包](docs/verification-stage17-local-playtest-0.3.12.md) 含 Web、后端 JAR、Android 模拟器 debug APK、iOS Simulator App 和本地 Compose 配置；[同源码离线镜像包](release/0.3.12-local-playtest-images/images.tar.gz) 单独提供。包来自干净提交，打包脚本运行 Web、Maven 数据库集成和 Flutter 测试与构建；独立解包栈在自定义 Web 端口完成真实浏览器注册、断网恢复与继续出牌，以及经典和 2v2 整局。此前 [0.3.11 包内 iOS 普通模拟器 App](docs/verification-stage17-local-playtest-0.3.11.md) 已登录、开局并收到出牌确认，静音跨牌桌保留；它使用仅供 Debug 试玩的进程内会话，重启后需重新登录，仍不是签名真机版本。[0.3.9 Android 普通 APK](docs/verification-stage17-local-playtest-0.3.9.md) 已完成数百次界面动作及终局战绩显示，剩余回合由 API 补完。真实设备语音与生产部署仍需单独验收，详见 [候选矩阵](docs/verification-stage18-0.3.12-candidate.md)和[本地部署说明](deploy/README.md)。

## 设计与 Git

- 分阶段实现总索引：[stage1–stage18](docs/README.md)，每阶段任务、依赖、验收和进度统一在这里维护。
- 最新设计：`docs/architecture-v0.2.md`
- 协议计划及实现状态：`docs/contracts/README.md`
- 分支绑定待确认：`docs/git-and-release.md`
- 游戏术语：`CONTEXT.md`
- 当前验收记录：`docs/verification.md`

已于 2026-09-06 创建 GitHub 私有仓库 `zhuyinghua6961/UNO`，本地已初始化 Git 并关联 origin。2026-09-07 按用户授权进行首次本地提交，提交记录以 `git log` 为准；未推送代码，未创建三个业务分支。仍需确认三个分支名及对应关系，不要把整个 monorepo 的分支直接理解成三个独立目录版本。
