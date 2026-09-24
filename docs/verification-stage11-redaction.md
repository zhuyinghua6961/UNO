# stage11 消息撤回增量验收（2026-09-24）

V15 增加 `redacted_at`，受限运营脚本 `deploy/moderation/redact.sql` 以固定占位文字替换原正文并保留消息 ID、频道序号和过期时间。历史接口返回 `redacted=true`；同一 `clientMessageId` 的重试确认仍返回此记录。原举报证据快照继续按从举报起 30 天的期限保存。

Web 与 Flutter 对各自已展示的最近消息每约 10 秒按当前权限取最新 100 条，仅用服务器撤回状态更新已知 ID，不改变游标或新增消息未读数。消息到达顺序变化时，旧实时事件不能把撤回正文恢复。后台或断网时以重新连接后的补取与核对为准。

## 已执行

- `mvn -f backend/pom.xml -Pdatabase-it -pl game-service -am verify -q`：通过，迁移到 V15，PostgreSQL 测试验证举报快照、撤回历史和幂等重试。
- `npm --prefix web run build`、`npm --prefix web test`：通过，15 个文件、91 项测试，含已显示消息的撤回替换。
- `cd flutter && dart analyze --format=machine && flutter test`：通过，33 项测试，含 App 缓存更新。
- 隔离回环端口的 Compose 栈：`API_BASE_URL=http://127.0.0.1:18080 MAILPIT_BASE_URL=http://127.0.0.1:18025 SMOKE_CHAT_WS=true SMOKE_CHAT_MODERATION=true node tools/smoke-auth-chat.mjs` 通过，验证受限 SQL 撤回后 HTTP 历史和原消息重试均返回占位文本与撤回标记。

本次未在真机画面上测量撤回传播延迟。用户自助删除、申诉、跨实例消息推送以及正式隐私告知仍需另行验收。
