# stage18 本机身份链路容量基线

日期：2026-09-28。用户选择先建立可复现基线，暂不指定同时在线人数或延迟目标。此记录只覆盖一个已登录 App 账号反复读取会话的 HTTP 路径；不代表整局、文字或语音承载能力。

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

这次测试超过旧共享额度 10,000 次且没有身份链路 503，支持该额度故障已修复的结论。它没有跑到饱和点，也没有记录持续 CPU/内存峰值、数据库连接数、并发玩家数或媒体负载；速率和延迟不能当作生产容量承诺。后续基线需覆盖真实出牌、WebSocket、房间文字和 LiveKit，并在单独压测环境采集资源曲线。
