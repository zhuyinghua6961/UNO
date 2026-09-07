# UNO 好友牌桌

Vue + Spring Cloud + Flutter 的多人卡牌游戏开发骨架。当前为 **0.1.0 scaffold，不是完整可玩的游戏**。

## 目录

```text
backend/    Maven 多模块：gateway、identity-service、game-service、game-core
web/        Vue 3 + TypeScript + Vite + Pinia + Vue Router
flutter/    Android / iOS Flutter 工程
deploy/     Dockerfile、Compose、Nginx、可选 LiveKit
release/    本地发布归档；二进制默认不提交 Git
assets/     已获取素材及来源许可
docs/       设计、领域术语、接口与发布约定
tools/      素材同步、本地配置初始化、发布归档
```

## 当前可用范围

- Web：大厅、经典/2v2 模式选择、牌桌与聊天区布局预览、禁用态登录页面。
- Flutter：对应的大厅、牌桌、账号入口；Android/iOS 工程与麦克风用途声明。
- 后端：三个可启动进程、Spring Cloud Gateway 路由、健康检查和公开骨架状态接口。
- 数据库：PostgreSQL 17 双服务独立库与角色、账号/会话/验证令牌表、Flyway 迁移、内部账号仓储；并非已开放登录。
- 规则基础：房间/队伍消息受众、2v2 队友语音隔离策略、消息格式约束及单元测试。**尚未连接网络或真实身份，因此不是已经落地的通信授权系统。**
- 部署：Web、三个 Java 进程和必需 PostgreSQL 的开发 Compose；Redis、LiveKit 仍为可选配置。

尚未实现：注册登录、发牌与完整规则引擎、真实房间、实时 WebSocket、文字消息传输/存储、LiveKit 令牌签发和两端语音 SDK、战绩持久化、生产 HTTPS/TURN 和正式移动端签名。

数据库新增 14 个真实 PostgreSQL 集成测试与 2 个配置工具测试，原有 16 个骨架测试保留。完整 Docker 应用镜像构建仍未验收，数据库容器验证不等于全栈容器联调。具体证据见 `docs/verification-stage2.md`，历史记录见 `docs/verification.md`。

所有功能占位都标注待接入；受保护的后端路径默认拒绝访问，没有演示账号、模拟登录或开放令牌签发。

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

identity/game 现在必须配置数据库才能启动。先按 [数据库与运行指南](docs/persistence.md) 启动 PostgreSQL、载入连接配置，再分别运行三个模块的 JAR。默认端口 8081、8082、8080；可以通过 SERVER_PORT、IDENTITY_URL、GAME_URL、GAME_WS_URL 覆盖。接口仍只展示健康/骨架状态，不提供登录或对局能力。工程约定与环境问题见 [工程基线](docs/engineering-baseline.md)。

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
