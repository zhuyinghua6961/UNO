# 工程基线与未决事项

更新：2026-09-06。本文约定本地开发方式，不表示产品规则已获确认。

## 决策边界

| 项目 | 当前处理 | 确认截止点 |
| --- | --- | --- |
| 技术栈与目录 | 沿用用户指定的 Vue / Spring Cloud / Flutter / Docker 与五个业务目录 | 已确定 |
| 登录 | 为邮箱＋密码建立可调整的数据基础，不开放注册；昵称不是登录标识 | stage3 开始前 |
| 组队 | 暂按四人 2v2、座位交替设计 | stage5 前 |
| 团队胜利 | 暂建议任一队员先出完即整队胜利，不共享手牌 | stage10 前 |
| 经典规则 | 牌组版本、质疑、UNO 抓漏、罚牌叠加是否允许需先冻结规则清单 | stage6 前 |
| 时限与断线 | 不擅自加入机器人；回合时长、重连保留、判负规则待定 | stage7 前 |
| 语音与平台 | Android/iOS，先做前台队友语音；后台策略待确认 | stage9 / stage15 前 |
| Git 三分支 | 名称与用途待用户指定；只保留现有 origin | 分支、推送、CI 操作前 |

stage1 仍为部分完成。允许先执行不依赖规则与分支决定的 stage2 本地数据库工作，这是明确的依赖例外，不是把 stage1 偷标完成。邮箱字段和令牌用途是本地验证方案；若改为其他登录方式，在 stage3 增量迁移，不重写已使用的迁移。

## 协议与错误约定

- Java 类型 UpperCamelCase，字段 lowerCamelCase；JSON lowerCamelCase；协议枚举 UPPER_SNAKE_CASE；数据库 snake_case。
- 使用服务器生成的 UUID 标识对象；外部身份不能只凭请求体中的 userId/teamId 判断。
- HTTP 业务错误计划为 `code / message / requestId`，稳定 code 供客户端判断；401 未登录、403 无权限、409 业务冲突、429 限流、503 依赖不可用。
- 目前仅健康和能力状态接口可用，安全层仍可返回无正文的 401/403；业务错误封装在 stage3 实现，不宣称已有统一错误处理器。
- 对外禁止返回 SQL、约束名称、数据库地址、堆栈和密码。数据库异常在业务边界转换；重复账号要兼顾账号枚举风险，不能直接把唯一约束异常返回客户端。
- 实时命令沿用 [协议草案](contracts/README.md)；幂等键、expectedVersion、私有视图必须由实际业务验证，不只靠字段存在。

## 配置与日志

- 版本受项目依赖文件管理，不追随浮动最新版本；不在仓库内硬编码个人代理。
- 普通默认值放 application.yml；连接地址、服务密码来自环境。必需的数据库配置缺失则启动失败，不退回内存库。
- 本地秘密保存在被忽略的 deploy/.env；初始化工具仅补缺失键，不修改已有值。不要在日志、截图或聊天中展示展开后的 Compose 配置和进程环境。
- 测试使用隔离容器和固定的测试专用假密码，绝不读取开发或生产数据库连接。
- 生产配置从受控秘密管理注入，仅给各服务本身需要的凭证；TLS、迁移角色/运行角色分离与轮换在 stage17 验收。
- 不记录密码、原始令牌、Cookie、Authorization、聊天内容和手牌。默认业务日志只记录请求标识、事件、耗时和错误分类；不启用 SQL 参数追踪。
- 目前使用框架日志，无统一 requestId 传播、日志收集或告警平台；这些不作为已交付能力。

## 复现与测试

1. 准备 Java 17+、Maven 3.9+、Node 24、Flutter/Dart 和可用 Docker；按依赖文件安装。
2. 根目录执行 `node tools/sync-assets.mjs`，然后分别安装 web / flutter 的依赖。
3. 按 [数据库与运行指南](persistence.md) 启动 PostgreSQL、设置连接，再启动三项 Java 服务。
4. Web 使用 `GATEWAY_URL` 指向实际网关；默认 Vite 5173，已有占用时换端口，不停止他人服务。Flutter 的网络登录仍未实现。
5. 运行下面的验证命令；Maven 默认不包含数据库集成测试，阶段验收必须额外带 `-Pdatabase-it`。

```sh
mvn -f backend/pom.xml verify
mvn -f backend/pom.xml -Pdatabase-it verify
node --test tools/init-local-env.test.mjs
npm --prefix web ci
npm --prefix web run build
npm --prefix web test
cd flutter
flutter pub get
dart analyze --format=machine
flutter test
```

测试优先行为和边界：规则模块纯单元测试，数据库用真实 PostgreSQL，HTTP 验证权限与信息泄露，双端测用户可见行为。数据库测试不得因 Docker 不可用而静默跳过后算成功。

### 已知环境差异

- 当前机器 Node 23 不作为支持基线；本轮使用已安装的 Node 24。仓库不写死个人运行时路径。
- Maven/npm 依赖访问失败时检查本机网络与代理；若需代理，只使用个人临时 settings 或单次命令配置，不改全局设置。
- Docker daemon 访问 Docker Hub 曾发生 DNS 超时。可明确选用官方公共镜像镜像源，例如将 `POSTGRES_IMAGE` 设置为 `public.ecr.aws/docker/library/postgres:17-alpine`；首次获取仍需网络。禁止拿已有 PostgreSQL 16 数据卷直接启动 PostgreSQL 17。
- 当前 Flutter SDK 的 `flutter analyze` 包装命令曾在 LSP 初始化抛异常，替代检查为 `dart analyze --format=machine`；不等同于已修复 SDK。
- 本轮数据库验证不代表完整 Web/后端镜像构建、手机安装和公网部署已经通过。
