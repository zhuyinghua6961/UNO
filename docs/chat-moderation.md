# 文字举报与运营处理

房间和队伍消息保存 30 天；到期由 game-service 定时清除。玩家仅能举报目前有权阅读的消息，一位玩家对一条消息最多一份举报，每 24 小时最多 20 份。举报保留当时的消息文字快照，供房间或原消息先到期时复核；举报从提交起保存 30 天并自动删除。限时禁言记录在到期 30 天后删除。聊天内容只在受控数据库中读取，常规日志不记录正文。

## 受限运营流程

下列 SQL 文件只给有数据库权限的运营者在可信终端使用；不要把数据库密码、举报正文或用户令牌贴进工单或日志。连接目标分别为 `uno_game` 和 `uno_identity`，在执行前确认备份、环境与目标 UUID，并把示例中的 `UUID` 换成实际值。示例使用本机 Compose 数据库容器的 `uno` 管理角色；其他环境应使用单独受限角色。`-v` 值经 psql 的 `:'name'` 安全引用，并由 PostgreSQL 强制转换为 UUID。

```sh
docker compose --env-file deploy/.env -f deploy/compose.yaml exec -T postgres psql -U uno -d uno_game < deploy/moderation/review.sql
docker compose --env-file deploy/.env -f deploy/compose.yaml exec -T postgres psql -U uno -d uno_game -v user_id=UUID < deploy/moderation/mute-24h.sql
docker compose --env-file deploy/.env -f deploy/compose.yaml exec -T postgres psql -U uno -d uno_game -v report_id=UUID < deploy/moderation/resolve.sql
docker compose --env-file deploy/.env -f deploy/compose.yaml exec -T postgres psql -U uno -d uno_game -v message_id=UUID < deploy/moderation/redact.sql
docker compose --env-file deploy/.env -f deploy/compose.yaml exec -T postgres psql -U uno -d uno_game -v user_id=UUID < deploy/moderation/unmute.sql
docker compose --env-file deploy/.env -f deploy/compose.yaml exec -T postgres psql -U uno -d uno_identity -v user_id=UUID < deploy/moderation/disable-account.sql
```

复核报告的消息、房间、被举报用户 ID 后，按实际需要移除原消息、禁言 24 小时并将报告标记为已处理；证据不足时可只标记为已处理。移除操作把正文替换成“[消息已移除]”并保留频道序号，已打开的聊天面板在最近消息核对时更新；举报中原有证据快照仍按 30 天期限保存。误禁言可执行 `deploy/moderation/unmute.sql`，传入相同的 `user_id`。禁言立即影响 HTTP 和 WebSocket 新消息发送；原消息的同 ID 重试仍返回原结果，避免把成功发送误报为失败。

严重滥用可在 `uno_identity` 库执行 `deploy/moderation/disable-account.sql` 并传入 `user_id`；它禁用账号并撤销其全部会话与未消费账号令牌。聊天 WebSocket 会周期重查会话。完成后还须检查该用户所在的进行中对局与媒体连接；已建立的 LiveKit 音频连接仍需单独撤销，本流程不能替代 stage15/18 的语音封禁验收。

目前没有公开运营后台或自动内容判定。运营值班、申诉处理、正式隐私告知及最终公开发布策略仍需在发布验收中确认。
