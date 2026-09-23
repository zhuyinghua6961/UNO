# stage16 经典战绩增量验收

日期：2026-09-23。范围：已正常完赛的经典局；不把进行中或中断局计入胜率，也不声称团队战绩已实现。

- V6 迁移为 `match_players` 增加开局昵称快照；新局保存原始昵称，旧局有房间成员记录时回填，缺失时返回 `null`。历史结果从 `game.matches` 的终局快照和结束时间读取，不由前端上传胜负。按 `ended_at,id` 进行稳定游标分页，仅查询当前认证用户参与的局。
- `MatchServiceIT` 用两场已结束记录验证胜负、昵称快照、分页次序、空历史、无权用户查不到他人记录、无私有手牌字段；完整 `mvn -f backend/pom.xml -Pdatabase-it verify -Duno.postgres.image=postgres:16-alpine -q` 通过。
- Web `npm test -- --run` 73 项通过，`npm run build` 通过；Flutter `flutter test` 17 项通过，`dart analyze` 无问题。两端账号页均接入读取、空状态、刷新、失败重试和继续加载。
- 已有 V5 数据的隔离 Compose 栈重建 Game/Web 后，Flyway 仅增量执行 V6，所有服务健康。`EXPECT_AUTH_AVAILABLE=true EXPECT_GAME_AUTH_AVAILABLE=true node tools/smoke-api.mjs` 通过；`SMOKE_FULL_MATCH=true node tools/smoke-auth-chat.mjs` 使用两个真实测试账号完成邮件验证、组房、房间文字和一场通过公开 API 操作到终局的经典局，随后两人的私有历史均含同一对局 ID，胜负相反，响应无手牌。该脚本只输出断言结果，不输出凭证。

未验证 Web/Flutter 账号页真实设备视觉、旧库中缺失房间成员的昵称展示、团队或中断记录、统计和偏好同步。iOS 设备仍未能进入对局实测。

## 昵称修改增量

- 同日新增 `POST /api/users/me/profile`：仅认证账号可修改自己的昵称，沿用 Web Cookie/CSRF 与 App Bearer 安全边界。昵称去首尾空白后限 1–40 个 Unicode 码点、拒绝控制字符。其他现有会话下次读取资料可见；房间和历史已保存的昵称快照保持不变。
- `AuthIT` 验证双 App 会话同步、跨账号隔离、无凭证拒绝、Web 缺 CSRF 拒绝与非法昵称；全量 Maven 数据库集成构建通过。Web 74 项测试与生产构建通过，Flutter 17 项测试和 `dart analyze` 通过。
- 隔离 Compose 环境更新 Identity/Web 镜像后，`SMOKE_FULL_MATCH=true node tools/smoke-auth-chat.mjs` 再次通过：App 修改昵称、原会话重读、以新昵称入房、双向文字、经典终局及双方战绩均完成。
