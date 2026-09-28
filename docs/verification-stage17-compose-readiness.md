# 本地 Compose 业务就绪门槛验收

日期：2026-09-28。测试使用隔离项目 `uno-readiness-check` 和独立 PostgreSQL 卷；Identity、Game、Web 使用已校验的 0.3.8 本地镜像，Gateway 使用本次工作区代码构建的临时镜像，Compose 配置使用本次工作区版本。测试没有修改原 `uno-stage17-check` 数据库。

## 本次调整

- Identity/Game 的容器健康检查调用包含数据库状态的 `/actuator/health/readiness`。
- Gateway 的 readiness 同时检查 Identity、Game 的 readiness；liveness 只检查 Gateway 自身。Gateway 容器健康检查另走自身的 `/api/system/bootstrap` 与 `/api/auth/status` 路由。
- Web 容器健康检查首页，以及经 Nginx/Gateway 转发的两条业务路由。容器内探针显式绕开系统代理。
- Compose 用 `service_healthy` 依次启动 Game、Gateway、Web；`up --wait` 只有在健康检查通过后才完成。应用启动宽限期 60 秒，健康后的连续三次失败会标记 `unhealthy`。

## 实测

在开启本地账号、Mailpit 的隔离栈中，`up --no-build -d --wait` 等待到 PostgreSQL、Identity、Game、Gateway、Web 全部为 `healthy`；Web 两条路由各返回 200。故障注入结果：

| 操作 | Gateway readiness | Gateway liveness | 容器健康与恢复 |
| --- | --- | --- | --- |
| 停止 Game | 503 | 200 | Gateway/Web 均变 `unhealthy`；重启 Game 后 readiness 和 Web Game 路由恢复 200 |
| 停止 Identity | 503 | 200 | 重启 Identity 后 readiness 和 Web Identity 路由恢复 200 |

再次运行 `up --no-build -d --wait`，所有容器恢复 `healthy`。现有 `CrossServiceAuthIT#unavailableIdentityFailsClosedAndRecoversWithoutGameRestart` 增加 readiness/liveness 断言，单例集成测试通过（1/1）。Gateway 编译、Compose 配置检查和 `git diff --check` 通过。

同一隔离项目保留数据卷、停止容器后，去掉账号覆盖文件再次运行默认配置；`up --no-build -d --wait` 也通过，Gateway readiness 为 200，Web 两条路由正常响应。此时 bootstrap/status 明确返回账号与玩法功能关闭，说明健康状态只表示路由与依赖可达；试玩必须合并账号配置并运行实际业务烟测。

第一次候选构建中，我把新配置的 `management.endpoint` 缩进错放到 `management.endpoints` 下，导致停用 Game 时 readiness 仍返回 200。故障注入发现这一问题后已修正缩进、重建镜像，并重新完成上述停服与恢复测试。

## 边界

这一门槛证明本地 HTTP 与数据库业务路由可用，不检验 SMTP 实际投递、LiveKit 媒体、WebSocket 长连接、外部 TLS/TURN 或真实设备网络。Docker 健康检查发现故障后只标记状态，Compose 不会自动修复已运行的下游。0.3.8 发布包仍是变更前的配置；需要重新打包与验证后，才能将本次门槛交付给试玩者。
