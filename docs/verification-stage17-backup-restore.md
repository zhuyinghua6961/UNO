# stage17 隔离数据库备份恢复演练

日期：2026-09-24。范围：隔离的 `uno-stage17-check` Compose 栈，PostgreSQL 17，测试注册账号与对局数据；未连接生产数据库。

按 [数据库指南](persistence.md) 的最小流程，使用容器内 `pg_dump -Fc` 分别备份 `uno_identity` 与 `uno_game` 到 `deploy/backups/stage17-aP349XUy`。目录权限为 `0700`，两份 dump 和 `SHA256SUMS` 为 `0600`，目录按 Git 忽略规则不提交。两份 dump 非空，`shasum -a 256 -c SHA256SUMS` 均通过。

在同一 PostgreSQL 实例创建两个唯一命名的新演练库，分别指定原服务角色为 owner，撤销 `PUBLIC` 的数据库权限，以 `pg_restore --exit-on-error --single-transaction --no-owner --role=<service-role>` 恢复。只读核对结果如下：

| 数据库 | 源表行数 | 恢复表行数 | 源成功迁移数 | 恢复成功迁移数 |
| --- | ---: | ---: | ---: | ---: |
| identity (`accounts`) | 294 | 294 | 3 | 3 |
| game (`game.matches`) | 66 | 66 | 13 | 13 |

演练库核对后已删除；查询确认 `uno_%_restore_%` 命名的演练库剩余 0 个。源库、运行服务及原数据卷未被删除。备份目录仍在本机供进一步检查；其中包含测试账号数据，不应上传或纳入 release。

这证明当前本机两库各自的导出、校验与隔离恢复路径可执行。两库 dump 不是跨库原子快照；尚未演练应用连接切换、加密异地存储、自动调度、保留期、恢复时间目标、故障回滚或生产数据恢复。stage17 的生产备份/回滚任务仍未完成。
