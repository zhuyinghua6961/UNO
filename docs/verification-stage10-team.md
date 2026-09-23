# stage10 四人 2v2 增量验收

日期：2026-09-24。规则按 [暂定团队规则 v1](rules-team-v1.md) 实现；最终产品确认待答复。

- game-core 的 `TeamUnoTest` 验证四人约束、队友出完即整队终局、只计对手余牌、跳过与反转收尾、末张 +4 等质疑后裁决，以及队友仅见牌数。`MatchServiceIT` 验证四人准备开局、V7 队伍快照、开局后不能换队、终局返回等待室、四人历史胜负和团队模式拒绝 `NEXT_ROUND`。定向 `mvn -pl game-service -am -Pdatabase-it verify -Dtest=TeamUnoTest -Dit.test=MatchServiceIT -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false` 通过。
- Web 75 项测试和生产构建通过。Flutter 18 项测试、`dart analyze lib test` 通过。`flutter analyze` 的分析服务曾两次报 `FormatException: Unexpected end of input`，所以使用直接的 Dart 分析命令复核；未发现源码诊断。
- 原隔离 Compose 测试卷从 V6 原地升级到 V7；Game/Web 新镜像构建并健康启动。`SMOKE_TEAM_MATCH=true node tools/smoke-auth-chat.mjs` 经 Gateway 注册、邮箱验证并登录四个独立 App 身份，组成 A/B/A/B 房间、全员准备、通过公开 API 从真实发牌打至团队终局。四人战绩为同一对局，胜负按队伍一致；房间回到等待状态且准备清空。本次随机对局由 B 队获胜。脚本不输出令牌和密码。
- 完整 Maven 全套有两个与本改动无关的时钟敏感失败：一次身份限流用例预期 429 收到 401；一次旧版 WebSocket 自动长局测试运行 654 秒后某动作收到 `TURN_EXPIRED`。定向团队测试通过，完整套件不能据此宣称全绿。需在稳定时钟环境复验并改进长局测试的计时控制。

尚未完成真实 Web/Android/iOS 混合设备四人操作、UI 视觉验收及用户对规则的最终确认。房间文字已可用；队伍文字与队友语音另属后续阶段。
