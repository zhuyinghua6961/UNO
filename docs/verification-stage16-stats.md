# stage16 个人战绩统计增量验收

日期：2026-09-24。统计按当前已认证用户从 `game.matches` 的终局快照和 `game.match_players` 的开局席位/队伍快照计算，不接受客户端传入 userId。经典与 2v2 各返回胜、负、中断三个计数；进行中局不计，胜率仅按胜场除以正常完赛场次计算。空账号两个模式均为零。V13 为本人历史/统计查询增加索引。

## 验证

- `mvn -f backend/pom.xml -Pdatabase-it -pl game-service -am verify -q`：通过。PostgreSQL 集成测试核对两场经典胜负的汇总、团队同队胜与对手负、两种模式中断另计、非参与者零统计；迁移和重启测试通过。测试时修正了旧断言：普通回合超时裁决有时依规则连续执行摸牌和放弃，版本可前进两次，重复裁决仍须保持最终版本不变。
- `npm --prefix web test -- --run`：14 个文件、85 项通过；`npm --prefix web run build`：类型检查和构建通过。`flutter test`：30 项通过；`dart analyze lib test integration_test`：无问题。Web/App 接口解析检查拒绝负数统计，Web 组件测试核对分模式展示及中断不计胜率的说明。
- 在隔离的 `uno-stage17-check` Compose 栈重建 Game 服务后，`SMOKE_FULL_MATCH=true SMOKE_TEAM_MATCH=true node tools/smoke-auth-chat.mjs` 通过：新账号统计为零、未认证查询返回 401；两个账号完成经典局后，胜者与负者各自的统计和私人历史一致；四账号完成 2v2 后，四份统计按队伍胜负与私人历史一致。此测试还经过真实 Gateway、Identity、Game、PostgreSQL 和 Mailpit。

## 边界

这证明数据来源、认证查询、两个模式的结果语义和双端构建；未在真机账号页进行人工视觉验收，也未覆盖大量历史数据下的查询延迟。统计不代表技能排名或官方 UNO 胜率。`release/0.2.0-local-preview` 是此前提交 `3547f89` 的归档，不包含本次统计功能，后续完整版本须重新构建归档。
