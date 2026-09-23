# stage2：数据库、迁移与恢复

更新：2026-09-06。真实接入 PostgreSQL 17；尚无注册、登录或游戏持久化业务接口。

历史范围说明：本页保留stage2的基础表设计；2026-09-07新增identity V3及可关闭的认证业务，当前行为以 [账号指南](authentication.md) 为准，不能再将下文“凭证尚未消费”理解为当前版本现状。V1/V2保持原样，game仍没有游戏业务表。

## 所有权与目录

| 服务 | 数据库 / 登录角色 | 迁移 |
| --- | --- | --- |
| identity-service | uno_identity / uno_identity | V1 accounts；V2 sessions、account_tokens |
| game-service | uno_game / uno_game | V1 创建 game schema，不提前创建房间或消息表 |

两个角色都不是超级用户，不能创建数据库或角色，不能连接对方数据库。开发环境使用同一 PostgreSQL 容器、不同数据库和密码；超级用户仅用于初始化与运维，不传给 Java 容器。生产需进一步分离迁移与运行账号，不能把开发 Compose 直接暴露公网。

迁移位置：各模块 `src/main/resources/db/migration/V<递增整数>__<说明>.sql`。两个服务独立版本。采用 JDBC + Flyway，不使用 ORM 自动建表，不混用 schema.sql/data.sql。应用启动自动迁移、检查校验和；已部署迁移不可修改，只追加新版本。不启用 clean、自动 baseline 或自动 repair。

Spring Boot 管理 Flyway 与 PostgreSQL JDBC 版本。参考依据：Spring Boot 官方 Data Initialization 文档；测试使用 Testcontainers 的 PostgreSQL 模块。实现以当前锁定依赖和实际测试为准。

## 已建数据结构

- accounts：UUID 主键、唯一规范化 email、password_hash、nickname、PENDING/ACTIVE/DISABLED 状态、创建/更新时间。
- sessions：账号外键、唯一 token_digest、WEB/APP 客户端、创建/过期/撤销时间。
- account_tokens：账号外键、VERIFY_EMAIL/RESET_PASSWORD 用途、唯一 token_digest、创建/过期/消费时间。
- 令牌只预留 64 位十六进制摘要字段；没有生成或消费业务。密码字段只接受编码结果，仓储不负责散列或验证；stage3 必须选择散列方案并实现入口校验，不允许将明文传入该仓储。
- JdbcAccountStore 只提供内部创建与按 ID 查询，查询结果不包含 password_hash；没有新增 HTTP 注册入口。

邮箱暂按去首尾空白、Locale.ROOT 小写处理，数据库重复做规范化及唯一约束。数据库的邮箱形状检查不是完整邮件地址验证。改登录渠道时需调整领域模型并追加迁移，不把暂定方案当作用户确认。

过期时间必须晚于创建时间；撤销/消费时间不能早于创建时间。约束只保证数据合法，**不会自动拒绝已过期会话或实现令牌单次消费**，这些由 stage3 在事务中校验。账号删除会级联删除关联凭证；真实账号删除政策与对局记录保留另行设计。

## 事务与查询约定

- 业务服务控制事务边界，仓储加入当前事务；账号创建与后续凭证写入必须作为整体提交。唯一性以数据库约束兜底，不依赖“先查再插”。
- 默认 READ COMMITTED；涉及令牌单次消费使用条件更新或行锁；涉及对局并发使用版本号，分别在后续阶段交付。
- 时间使用 TIMESTAMPTZ，Java 使用 Instant，外部协议传 UTC ISO-8601。updated_at 由更新 SQL 同时写入，不依赖客户端时钟；目前尚无更新账号入口。
- 列表默认建议 limit=20、最大100，使用 `(created_at,id)` 稳定游标；后续列表 API 实现前不得声称已支持分页。
- 所有变量值参数化；不得用用户输入拼接 SQL、排序列或 schema。
- 数据库启动连接/迁移失败，服务启动失败；运行期间数据库不可用，就绪探针为503，存活探针不依赖数据库。健康响应只含 status，不含组件、SQL 和连接信息。
- 数据库异常不直接面向 HTTP；房间 HTTP 边界映射安全错误，不开放调试端点。

## 已建关系与未来草案

game.rooms → game.room_members（roomId,userId,seat）已由 game V2 建表；一个账号仅占一个席位，房间含邀请码、模式、上限、状态、版本和到期时间。一个 room 可产生多个 matches（对局表尚未实现）。
matches → match_players（固定参与者快照）、match_teams（每局队伍与队员）、match_results。
messages 关联 roomId，可选 matchId/teamId，以及服务器推导的 senderId；队伍历史权限必须基于当时成员，不只看当前队伍。
identity.accounts.id 与 game 侧 userId 是跨服务逻辑引用，不跨数据库加外键；身份失效和数据删除通过后续服务协议处理，不通过直接查另一个数据库实现。

房间等待状态及索引已在 stage5 建立；牌堆/手牌快照、重连记录、消息幂等键和索引随 stage7/11/12/16 的真实访问模式追加。实时内存状态与持久化恢复边界在 stage7/12 定义，Redis 当前不参与读写。

## 本地启动

在仓库根目录执行：

```sh
node tools/init-local-env.mjs
docker compose --env-file deploy/.env -f deploy/compose.yaml -f deploy/compose.local.yaml up -d --wait postgres
mvn -f backend/pom.xml verify
```

`compose.local.yaml` 只增加 PostgreSQL 的本机端口，默认25432。可在 .env 设置 POSTGRES_PORT；正式 Compose 默认不发布数据库端口。初始化脚本仅在空数据卷第一次启动时执行。

以下是仅用于本机、由自己生成并信任的 .env 的启动方式；分别在 identity / game 的终端载入，然后启动对应 JAR：

```sh
set -a
. ./deploy/.env
set +a
export IDENTITY_DB_URL="jdbc:postgresql://127.0.0.1:${POSTGRES_PORT:-25432}/uno_identity"
export GAME_DB_URL="jdbc:postgresql://127.0.0.1:${POSTGRES_PORT:-25432}/uno_game"
java -jar backend/identity-service/target/identity-service-0.1.0-SNAPSHOT.jar
```

另一个终端载入同样配置并启动 game-service JAR；gateway 不需要数据库凭证。通过 SERVER_PORT、IDENTITY_URL、GAME_URL 和 GAME_WS_URL 调整后端端口，Web 的 GATEWAY_URL 跟随网关。

完整 Compose 使用容器内部连接地址，无需手工设置两个 DB_URL。修改 .env **不等于修改已存在数据库密码**。

### 已有数据卷

不要执行 `down -v` 来“修复”初始化。已有卷不会重跑 init 脚本：先备份、核对角色和数据库是否存在。如果这是旧骨架且还没有 uno_identity/uno_game，可由管理员在维护窗口通过容器内 psql 执行 `deploy/postgres/10-create-service-databases.sql`；环境中必须包含两项服务密码。该脚本遇到重复对象会中止，不会删库或覆盖密码。部分初始化失败需人工核对后补缺失项，不反复盲跑。存在账号但密码不一致时，走管理员凭证轮换流程，不删数据。

## 环境区分与探针

| 环境 | 连接与秘密 | 数据隔离 |
| --- | --- | --- |
| 本地 | deploy/.env，随机的两项服务密码；端口仅回环 | 项目专属 volume |
| 集成测试 | Testcontainers 动态端口；固定测试假密码；不读取 .env | 临时容器、逐测试随机 schema，结束删除 |
| 生产 | 环境注入独立 DB_URL/USER/PASSWORD，TLS verify-full 与受信 CA | 独立实例/网络及备份；禁止复用本地卷 |

生产部署建议在受控发布步骤使用迁移角色执行 Flyway，应用设置 `SPRING_FLYWAY_ENABLED=false` 并使用仅具有 DML 权限的运行角色。此生产拆权方案尚未自动化，归 stage17；当前已实现的是两个服务之间的隔离。

直连服务检查 `/actuator/health/readiness` 与 `/actuator/health/liveness`。Gateway 的 `/actuator/health` 不聚合下游，也未把内部探针代理到公共业务路径。其他 Actuator 端点仍拒绝匿名访问。

## 数据库测试

```sh
mvn -f backend/pom.xml -Pdatabase-it verify
node --test tools/init-local-env.test.mjs
```

必须有运行中的 Docker。默认镜像 postgres:17-alpine，可用 `-Duno.postgres.image=public.ecr.aws/docker/library/postgres:17-alpine` 明确替换官方镜像源。不能将换成16后的成功结果宣称为17验证。

覆盖：空库与重复迁移、V1→V2保留数据、唯一/检查/外键约束、事务回滚、失败迁移 DDL 回滚、真实初始化脚本的双角色隔离、Java 服务重启持久化、连接池不可用时就绪/存活与错误脱敏、备份恢复至隔离库、game 服务空基线。没有 Docker 时测试失败，不静默跳过。报告在各模块 target/failsafe-reports；不入 Git。

## 本地最小备份与恢复演练

此处仅适用于开发环境。备份含账号及摘要等敏感信息，必须限制文件权限，不入 Git/release，不上传公共存储。生产需加密、权限/保留期、异地存储与定期恢复，RPO/RTO 在 stage17 确定。

创建唯一目录，不覆盖旧备份；使用容器内的 PostgreSQL 17 工具：

```sh
umask 077
mkdir -p deploy/backups
backup_dir="$(mktemp -d deploy/backups/backup-XXXXXXXX)"
docker compose --env-file deploy/.env -f deploy/compose.yaml exec -T postgres \
  pg_dump -U uno -d uno_identity -Fc > "$backup_dir/identity.dump"
docker compose --env-file deploy/.env -f deploy/compose.yaml exec -T postgres \
  pg_dump -U uno -d uno_game -Fc > "$backup_dir/game.dump"
```

两条命令都必须退出0，记录备份日期、迁移版本与校验值；失败的 dump 不可使用。跨数据库备份不是一个原子快照，未来涉及跨服务一致性时需停写或指定一致性恢复点。

只恢复到**新的演练数据库**；已有同名库时命令应失败，禁止删掉原库图方便：

```sh
docker compose --env-file deploy/.env -f deploy/compose.yaml exec -T postgres \
  createdb -U uno --owner=uno_identity uno_identity_restore
docker compose --env-file deploy/.env -f deploy/compose.yaml exec -T postgres \
  psql -U uno -d uno -v ON_ERROR_STOP=1 -c 'REVOKE ALL ON DATABASE uno_identity_restore FROM PUBLIC'
docker compose --env-file deploy/.env -f deploy/compose.yaml exec -T postgres \
  pg_restore -U uno -d uno_identity_restore --exit-on-error --single-transaction --no-owner --role=uno_identity \
  < "$backup_dir/identity.dump"
```

对 game 用 uno_game_restore / uno_game / game.dump 重复同样流程。恢复库需要相应角色预先存在；迁移历史包含在备份中。把单独测试服务连接指向恢复库，核对 Flyway 校验、行数和只读抽样，不直接改正在使用的服务连接。清理演练库需人工确认目标，不提供自动删库命令。
