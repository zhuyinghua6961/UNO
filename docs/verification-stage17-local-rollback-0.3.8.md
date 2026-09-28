# 本地镜像升级与回退演练

日期：2026-09-28。范围是同一台 macOS/Docker Desktop 主机上的隔离 Compose 项目，使用已校验的 `linux/arm64` 0.3.7 和 0.3.8 镜像，账号、Mailpit、Gateway、Game/Identity 和 PostgreSQL 均开启。本演练**没有修改原 stage17 测试库**，也没有删除演练数据库卷。

## 可复现入口

先按各自 `SHA256SUMS` 校验并用 `docker load -i` 导入两个镜像归档，在仓库根目录运行：

```sh
node tools/smoke-local-rollback.mjs 0.3.7-local-playtest 0.3.8-local-playtest
```

脚本要求本机 `deploy/.env`，自动创建随机命名的隔离 Compose 项目；默认用 Gateway `58080`、Web `58088`、Mailpit `58025/51025`，可通过 `ROLLBACK_GATEWAY_PORT`、`ROLLBACK_WEB_PORT`、`ROLLBACK_MAILPIT_PORT`、`ROLLBACK_SMTP_PORT` 改变。它核对实际容器镜像 ID/源码标签，成功时 `down` 但保留数据库卷；失败时保留容器和卷供检查。它仅用于可写的本地测试环境。

## 实测结果

在隔离项目 `uno-rollback-98c20a67`，真实随机账号经 Mailpit 验证并创建一场进行中的经典局 `80e3018c-154e-4cb5-8e86-6e1fa5c8bb74`。四个服务镜像依次从 `0.3.7 → 0.3.8 → 0.3.7` 切换，PostgreSQL 卷没有切换。每步均核对四个容器的镜像 ID 和源码标签、Game/Identity 路由、同一牌局版本与双方私有手牌。回退后继续出牌到结算，双方私有战绩的同局 ID 与相反胜负一致。脚本退出码 0，结果为 `PASS`。

随后停止整栈，再用保留的卷启动 0.3.7：数据库中同一牌局仍为 `ENDED|CLASSIC|1029`，Game Flyway 当前版本为 V17。再次停止整栈，保留卷。首次脚本试跑曾在任何账号写入前遇到新栈路由连接被关闭；增加 Game/Identity 业务路由就绪检查并避免沿用旧 HTTP 连接后，完整演练通过。这说明单靠容器进程健康不足以证明第一条业务路由已就绪，仍需完善发布健康门槛。

## 范围与剩余风险

0.3.7 与 0.3.8 的游戏逻辑和数据库迁移版本相同；这次证明**同 schema 的镜像切换、牌局状态保留和回退后继续服务**，没有证明引入新迁移后的向后兼容。没有演练生产流量切换、失败候选、数据库备份还原、跨主机、签名移动包升级或语音连接的无缝保持。旧版一旦不认识新 schema，不能仅替换镜像就宣称已回滚；正式发布需逐版检查兼容性，必要时采用经验证的备份恢复/前向修复方案。
