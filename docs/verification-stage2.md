# stage2 增量验收记录

日期：2026-09-06。范围：工程基线补充、数据库持久化底座，不是可玩游戏或生产部署。

这是stage2历史验收，后续认证修改见 [stage3验收](verification-stage3.md)；本页的无登录描述及32个测试计数只描述当时版本。

## 自动检查

| 检查 | 实际结果 |
| --- | --- |
| Maven clean verify，启用 database-it | 四个子模块通过；Java 17 目标；三个可执行 JAR 重新生成 |
| game-core 单元测试 | 原有10个通过 |
| identity DatabaseIT | 13个通过，失败0、错误0、跳过0 |
| game GameDatabaseIT | 1个通过，失败0、错误0、跳过0 |
| 本地秘密初始化工具测试 | 2个通过：新建/重复执行不变，旧配置补缺失且保留秘密与端口 |
| Vue | 3个测试通过，TypeScript + Vite 生产构建通过 |
| Flutter | 3个 widget 测试通过；dart analyze --format=machine 退出0 |
| 总计 | 32个测试通过：原有16个 + 本轮16个 |

数据库测试使用 Testcontainers 临时 PostgreSQL **17**，逐测试隔离 schema，不使用开发 .env 或已有数据库。初始化脚本与 Compose 共用真实 SQL 文件。

覆盖空库迁移与重复执行、identity V1→V2保留账号、邮箱唯一/规范化、字段检查、凭证外键/摘要/过期约束、事务回滚、故意失败的 V3 迁移回滚 DDL、服务账号不能跨库或创建角色、真实 Spring 服务关闭后重启保留账号、连接池关闭时503就绪与200存活、健康响应无内部详情、pg_dump/pg_restore 恢复至新库、game 基线重启。

测试用的失败 V3 仅位于 src/test/resources/db/failing，不进入生产 JAR。业务迁移未启用 clean / baseline-on-migrate；没有静默跳过数据库测试。

本机 Maven 下载使用个人临时代理 settings（未写入仓库）；等价验证命令为：

```sh
mvn -f backend/pom.xml -Pdatabase-it clean verify
node --test tools/init-local-env.test.mjs
npm --prefix web run build
npm --prefix web test
cd flutter
dart analyze --format=machine
flutter test
```

报告位于 backend/identity-service/target/failsafe-reports 与 backend/game-service/target/failsafe-reports；均为被忽略的构建产物。前端和脚本使用本机已安装的 Node24，不把个人路径写入工程。

## 本地联调

- PostgreSQL17 官方镜像通过 public.ecr.aws 镜像源获取并使用；本机数据库容器版本输出17.11。不把旧的 PostgreSQL16 缓存结果当作17验收。
- Compose 配置检查通过；本项目 PostgreSQL 在回环地址25432，健康检查通过，两个独立数据库和角色完成初始化。
- 初次绑定挂载 shell 初始化遇到执行权限错误，改为直接 SQL 初始化消除 shell 执行权限依赖；保留原卷，核对库/角色均未创建后补执行 SQL，没有删除数据卷或影响其他项目。
- 本项目原生 identity/game 服务使用新 JAR 和各自凭证启动，端口29081/29082；网关沿用29080。不将这项原生联调说成 Java 容器验收。
- 通过网关执行 tools/smoke-api.mjs：两个公开状态接口正常、功能开关全部 false、受保护路径拒绝匿名请求。
- 直连两个服务的 readiness/liveness 正常且只返回 status；/actuator/env 拒绝匿名访问。
- 实际停止本项目 PostgreSQL 后，两项服务 readiness 均返回503/DOWN、liveness 保持200/UP；重新启动数据库后，就绪状态恢复200，网关冒烟检查再次通过，未重启 Java 服务。
- 检查生产 identity JAR 只包含 V1/V2 迁移，不含故意失败的测试迁移；文档本地链接均可解析，.env、备份目录、测试报告与旧发布包符合 Git 忽略规则。

## 未交付与后续

- stage1 产品规则、登录方式与三个 Git 分支仍待确认；stage3 尚未开始。
- 无实际注册登录、密码验证、会话轮换/撤销、邮件发送、UNO 规则/联机、文字/语音或战绩。
- 未重新验证完整 Web/Java Docker 镜像构建；未验证 Android/iOS 安装包、真机和公网网络。
- 未实现生产运行/迁移角色拆权、自动加密备份/保留期、RPO/RTO 和恢复切换；已提供本地最小操作文档与隔离恢复测试。
- `.env` 新增三个缺失设置：POSTGRES_PORT、IDENTITY_DB_PASSWORD、GAME_DB_PASSWORD；旧秘密未覆盖，文件权限0600；不记录任何真实值。
- 未提交、推送、建分支或重新发布。release/0.1.0-scaffold.tar.gz 仍是数据库接入前的历史归档，不代表本轮交付。
