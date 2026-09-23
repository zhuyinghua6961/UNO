# UNO 好友牌桌

Vue + Spring Cloud + Flutter 的多人卡牌游戏开发骨架。当前为 **0.1.0 scaffold，不是完整可玩的游戏**。

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

- Web：大厅、模式/牌桌布局预览、好友房邀请与等待室；真实注册、验证、登录、找回/重置、退出、账号页和会话恢复（须显式启用后端）。
- Flutter：大厅、牌桌预览、账号入口和好友房等待室；Android/iOS 工程与麦克风用途声明。
- 后端：三个可启动进程、Spring Cloud Gateway 路由、健康检查和公开骨架状态接口。
- 数据库：PostgreSQL 17 双服务独立库与角色、账号/会话/验证令牌表、Flyway 迁移、内部账号仓储；并非已开放登录。
- 账号后端：注册、验证邮箱、登录、当前用户、退出、密码找回/重置；Web Cookie/CSRF与App刷新轮换。默认关闭，仅显式本地配置启用，详见 `docs/authentication.md`。
- 房间基础：真实账号可创建/加入等待室、准备、2v2 选队及离开；规则基础包含房间/队伍消息受众和语音隔离策略，但通信尚未连接网络。
- 经典规则：`game-core` 已实现独立的 108 张牌规则引擎，支持发牌、牌效、加四质疑、UNO 抓漏喊及计分；尚未接入实时对局。见 `docs/rules-classic-v1.md`。
- 部署：Web、三个 Java 进程和必需 PostgreSQL 的开发 Compose；Redis、LiveKit 仍为可选配置。

游戏服务已通过内部会话核验确认账号身份，不信任客户端身份头、不共享账号库；支持从Web账号页验证同一userId。详见 `docs/service-authentication.md`。

尚未实现：由等待房间启动真实对局、实时WebSocket、文字传输/存储、2v2 规则、语音SDK与准入、战绩、生产HTTPS/TURN和移动签名。邮箱渠道最终确认、外网邮件服务和设备界面验收仍待完成。

数据库新增 14 个真实 PostgreSQL 集成测试与 2 个配置工具测试，原有 16 个骨架测试保留。完整 Docker 应用镜像构建仍未验收，数据库容器验证不等于全栈容器联调。具体证据见 `docs/verification-stage2.md`，历史记录见 `docs/verification.md`。

2026-09-07：跨服务身份增量验收见 `docs/verification-service-auth.md`；Web账号验收见 `docs/verification-stage4-web.md`。stage3技术项已实现但渠道待确认，stage4仅Web已接入，不能当作双端完成。

未实现的对局与通信功能仍禁用；账号表单和好友房只在后端明确启用时开放；没有演示账号、模拟登录或语音令牌签发。房间实现与验收边界见 `docs/verification-stage5.md`。

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

identity/game必须配置数据库才能启动。先按 [数据库与运行指南](docs/persistence.md) 启动PostgreSQL、载入连接配置，再分别运行三个模块JAR。默认端口8081、8082、8080，可通过SERVER_PORT、IDENTITY_URL、GAME_URL、GAME_WS_URL覆盖。默认认证关闭；验证账号后端时额外按 [账号指南](docs/authentication.md) 配置本地邮件、开关与Origin。游戏仍无真实对局能力。

```sh
cd flutter
flutter pub get
flutter analyze
flutter test
flutter run
```

移动端真实网络接入尚未实现，`API_BASE_URL` 只保留配置位置。不要误将模拟器 localhost 当宿主机地址；详见 `flutter/README.md`。

## Docker 与发布

```sh
node tools/init-local-env.mjs
docker compose --env-file deploy/.env -f deploy/compose.yaml up --build -d
```

浏览器打开 `http://localhost:8088`；仅本机可访问。详见 `deploy/README.md`。此配置不是公网生产部署方案。

```sh
npm --prefix web run build
mvn -f backend/pom.xml verify
node tools/package-release.mjs 0.2.0-local
```

发布脚本不自动构建，也不保证已有 build 目录一定对应最新源码；按上述顺序执行，CI 后续应使用干净检出、测试、构建、打包的连续流程。当前归档不包含 APK/IPA 或 Docker 镜像。

`0.2.0-local` 仅是新版本号示例；实际发布前需指定尚未使用的版本号并执行数据库集成测试，本轮未运行该打包命令。

已有 `release/0.1.0-scaffold.tar.gz` 是数据库接入前的历史制品，本轮未重新发布或覆盖；新数据库配置不适用于把该旧包当成新版本运行。

## 设计与 Git

- 分阶段实现总索引：[stage1–stage18](docs/README.md)，每阶段任务、依赖、验收和进度统一在这里维护。
- 最新设计：`docs/architecture-v0.2.md`
- 协议计划及实现状态：`docs/contracts/README.md`
- 分支绑定待确认：`docs/git-and-release.md`
- 游戏术语：`CONTEXT.md`
- 当前验收记录：`docs/verification.md`

已于 2026-09-06 创建 GitHub 私有仓库 `zhuyinghua6961/UNO`，本地已初始化 Git 并关联 origin。2026-09-07 按用户授权进行首次本地提交，提交记录以 `git log` 为准；未推送代码，未创建三个业务分支。仍需确认三个分支名及对应关系，不要把整个 monorepo 的分支直接理解成三个独立目录版本。
