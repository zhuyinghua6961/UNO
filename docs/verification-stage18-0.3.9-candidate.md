# 0.3.9 本地试玩候选增量矩阵

日期：2026-09-28。0.3.9 延续 [0.3.8 本地玩法和回退证据](verification-stage18-0.3.8-candidate.md)，新增部署就绪门槛并交付到独立归档。**本地 Web 可完整试玩；目标真机可分发版本仍未完成。**

| 项目 | 本版证据 | 仍缺 |
| --- | --- | --- |
| 包与镜像 | 源码提交 `02c0d0f`；247 个包内文件和四镜像归档 SHA-256 通过；四镜像可重新导入，架构为 `linux/arm64` | 目标架构、镜像仓库 digest、正式发布签名 |
| 部署就绪 | 解包目录的账号栈 `up --no-build -d --wait` 等到实际 Game/Identity/Gateway/Web 路由；停 Game 后 Gateway readiness 503、liveness 200，Gateway/Web 标为 `unhealthy`，重启后恢复 | SMTP 投递、LiveKit、WebSocket、TLS/TURN 的独立生产健康门槛；容量与延迟目标 |
| 真实本地业务 | 包内镜像的注册、验证、登录、房间和双向文字烟测通过；前版已有 Web 经典/2v2 整局与同 schema 回退证据 | 包内普通启动 Android/iOS App 整局、真机互听、iOS 实际音轨、弱网与跨网恢复 |
| 移动构建 | Android debug 与 iOS Simulator 构建通过；可执行文件与 0.3.8 相同 | iPhone/Android 真机包、签名、真实设备验收 |

详见 [0.3.9 包验收](verification-stage17-local-playtest-0.3.9.md)及 [就绪门槛故障注入](verification-stage17-compose-readiness.md)。stage18 正式发布门槛仍未通过。
