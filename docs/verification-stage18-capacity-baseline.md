# stage18 本机容量基线：身份链路与整局

日期：2026-09-28。用户选择先建立可复现基线，暂不指定同时在线人数或延迟目标。以下两项分别覆盖已登录 App 账号反复读取会话，以及低并发真实账号整局；不代表生产承载能力。

## 环境与方法

- 目标为 [0.3.13 试玩包](verification-stage17-local-playtest-0.3.13.md)的独立 `uno-package-0313` 栈，Identity/Game/Gateway 均来自源码提交 `65820f647ba1459bd64d220f28e556c6b96c6e50` 的 `linux/arm64` 镜像，Gateway 只绑定本机 `127.0.0.1:61080`。六个服务经 Compose 健康检查通过；另有旧版本地栈同时运行。
- 宿主机 macOS arm64，10 个逻辑 CPU、24 GiB 内存；Docker Desktop VM 配置 10 CPU、约 7.75 GiB 内存。压测机与服务在同一台机器上，未使用 TLS、外网或负载均衡。
- [脚本](../tools/bench-session.mjs)通过本机 Mailpit 注册并验证一个随机账号，使用该账号的 App 访问凭证，以 8 路并发、无思考时间持续 15 秒请求 `GET /api/system/session`。每次都解析 JSON 并核对 `userId`；脚本仅允许 loopback 目标，不输出账号、密码或凭证。复现命令：

```sh
API_BASE_URL=http://127.0.0.1:61080 \
MAILPIT_BASE_URL=http://127.0.0.1:61025 \
BENCH_DURATION_SECONDS=15 BENCH_CONCURRENCY=8 \
node tools/bench-session.mjs
```

## 实测

| 指标 | 本次结果 |
| --- | ---: |
| 请求数 | 48,681 |
| HTTP 200 且身份一致 | 48,681 |
| 其他 HTTP/网络错误 | 0 |
| 实际时长 | 15.01 秒 |
| 平均完成速率 | 3,243 次/秒 |
| 延迟 P50 / P95 / P99 / 最大 | 2 / 4 / 7 / 44 毫秒 |

这次测试超过旧共享额度 10,000 次且没有身份链路 503，支持该额度故障已修复的结论。它没有跑到饱和点，也没有记录持续 CPU/内存峰值、数据库连接数、并发玩家数或媒体负载；速率和延迟不能当作生产容量承诺。

## 真实玩法与资源采样

使用同一个 0.3.13 独立栈运行 [玩法脚本](../tools/bench-match.mjs)。脚本并行启动两组现有整局烟测，每组注册并验证六个真实账号，完成一局经典、一局四人 2v2、房间和队伍文字、结算及个人战绩核对。它只访问本机网关和 Mailpit，使用 Docker Compose 项目标签选取要采样的六个容器；不启动媒体或 WebSocket 客户端。

```sh
API_BASE_URL=http://127.0.0.1:61080 \
MAILPIT_BASE_URL=http://127.0.0.1:61025 \
BENCH_COMPOSE_PROJECT=uno-package-0313 BENCH_SUITES=2 \
node tools/bench-match.mjs
```

两组均通过，共完成两局经典和两局 2v2。单组耗时 20.15 秒、20.75 秒；脚本总耗时 25.53 秒，包含 Docker 采样开销。`docker stats --no-stream` 共取到 12 组快照，观察到的各容器最大值如下；CPU 百分比按 Docker 显示值记录，各行峰值不一定发生在同一时刻。

| 容器 | 采样最大 CPU | 采样最大内存 |
| --- | ---: | ---: |
| Gateway | 30.34% | 292.0 MiB |
| Game | 59.25% | 332.7 MiB |
| Identity | 66.03% | 389.1 MiB |
| PostgreSQL | 35.61% | 83.2 MiB |
| Web | 0.84% | 9.3 MiB |
| Mailpit | 6.70% | 34.1 MiB |

该栈已有前几轮验收数据，宿主机还运行两个旧版本地栈；这次是低并发功能负载记录，采样可能错过瞬时峰值。没有测到吞吐极限、网络延迟、WebSocket、语音、数据库连接峰值或持续运行下的资源曲线，不据此推算可承载玩家数。下一轮应在隔离环境扩大并发，并覆盖实时通道和媒体。
