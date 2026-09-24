# stage10 四人 2v2 增量验收

日期：2026-09-24。规则按 [暂定团队规则 v1](rules-team-v1.md) 实现；最终产品确认待答复。

- game-core 的 `TeamUnoTest` 验证四人约束、队友出完即整队终局、只计对手余牌、跳过与反转收尾、末张 +4 等质疑后裁决，以及队友仅见牌数。`MatchServiceIT` 验证四人准备开局、V7 队伍快照、开局后不能换队、终局返回等待室、四人历史胜负和团队模式拒绝 `NEXT_ROUND`。定向 `mvn -pl game-service -am -Pdatabase-it verify -Dtest=TeamUnoTest -Dit.test=MatchServiceIT -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false` 通过。
- Web 75 项测试和生产构建通过。Flutter 18 项测试、`dart analyze lib test` 通过。`flutter analyze` 的分析服务曾两次报 `FormatException: Unexpected end of input`，所以使用直接的 Dart 分析命令复核；未发现源码诊断。
- 原隔离 Compose 测试卷从 V6 原地升级到 V7；Game/Web 新镜像构建并健康启动。`SMOKE_TEAM_MATCH=true node tools/smoke-auth-chat.mjs` 经 Gateway 注册、邮箱验证并登录四个独立 App 身份，组成 A/B/A/B 房间、全员准备、通过公开 API 从真实发牌打至团队终局。四人战绩为同一对局，胜负按队伍一致；房间回到等待状态且准备清空。本次随机对局由 B 队获胜。脚本不输出令牌和密码。
- 完整 Maven 全套有两个与本改动无关的时钟敏感失败：一次身份限流用例预期 429 收到 401；一次旧版 WebSocket 自动长局测试运行 654 秒后某动作收到 `TURN_EXPIRED`。定向团队测试通过，完整套件不能据此宣称全绿。需在稳定时钟环境复验并改进长局测试的计时控制。

## Web 与 iOS 同局验收

2026-09-24：新增混合端测试，现位于 `web/e2e/mixed-mobile-team.cjs` 与 `flutter/integration_test/local_mobile_team_play_test.dart`。在隔离的 `uno-stage17-check` Compose 栈、Chromium 和 iPhone 17 Pro iOS 26.5 模拟器上，四次通过四个随机账号的同局 2v2 验收。三名 Web 玩家从真实浏览器注册、邮箱验证、登录、加入/创建房间并准备；第四名 App 玩家从 iOS UI 登录、加入并准备。四人座位为 A/B/A/B；Web UI 与 iOS UI 各提交至少一次真实回合动作，其余回合由各自已认证的 API 自动玩家完成。后续运行的 Web 自动回合也使用浏览器 Cookie 与 CSRF 会话。服务端达到 `MATCH_OVER`，三个 Web 浏览器均显示团队结算，iOS UI 也显示团队结算；四人的历史均指向同一场对局，A 队或 B 队各两人的胜负一致。后两次成功运行还按获胜队伍精确核对 iOS 战绩和结算文字。

运行入口：`cd web && UNO_E2E_DEVICE_ID=<booted-simulator-id> npm run test:e2e:mixed-ios-team`；本机使用 `PATH=/tmp/uno-xcode-tools:$PATH DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer` 选择 Xcode 工具。旧变量 `UNO_E2E_SIMULATOR_ID` 仍可用。默认地址为 Web `127.0.0.1:8088`、Gateway `127.0.0.1:28080`、Mailpit `127.0.0.1:28025`，可用脚本顶部的 `UNO_E2E_*` 环境变量覆盖。运行前需启动本地认证 Compose 覆盖层和已启动的 iOS 模拟器；测试账号和对局留在隔离测试数据库。四次通过的对局 ID 分别为 `9c00a639-67ee-40ed-b793-3984929655fe`、`d51c4684-9f31-45e8-90c4-bee5d5dc9062`、`93bac118-6fce-449c-a036-2031aced00b7` 和 `d399eba2-1b84-414a-86d7-97436ca0f919`。

前三次成功运行在 iOS 回合前点击过“同步最新状态”；排查发现测试未滚动到长列表下方的动作控件，误把未渲染的屏幕外按钮当成状态未更新。第四次改为滚动到控件后直接操作，未点击同步按钮，成功完成整局。后续回合经 API 自动推进，未逐张由人手操作；此项仍不覆盖 Android 真机、iOS 真机麦克风、跨网或完整视觉验收。

测试改为移动端通用入口并更新隔离身份服务后，同一 iPhone 17 Pro 模拟器再次通过 Web/iOS 2v2 全局，结算与四份战绩一致，对局 ID `96d89ba4-be2c-4b40-b915-0b33c9918d25`。

用户对团队规则的最终确认仍待答复。房间文字已可用；队伍文字与队友语音另属后续阶段。

## Web 与 Android 同局验收

2026-09-24：隔离的 `uno-stage17-check` Compose 栈、Chromium 与 Android API 36.1 `Medium_Phone_API_36.1` 模拟器完成三名 Web 玩家加一名 Android App 玩家的四人 2v2。首次成功对局 `dfc655ba-9575-4e1c-8670-ba10180792ac`；通用入口、身份服务修复后再次完成对局 `709234eb-dc77-4be3-82a7-0e8e738804e2`。Android App 从界面登录、入房、准备、提交至少一次回合动作并看到团队结算；Web 玩家也从界面提交回合，剩余动作由各自认证的 API 自动推进。四份历史指向同局且团队胜负一致。此项不覆盖 Android 真机、跨网媒体和整局逐张触控。

运行入口：先启动隔离认证 Compose 栈及 Android 模拟器，执行 `adb -s emulator-5554 reverse tcp:28080 tcp:28080` 和 `adb -s emulator-5554 reverse tcp:28025 tcp:28025`，再执行 `cd web && UNO_E2E_DEVICE_ID=emulator-5554 npm run test:e2e:mixed-android-team`。反向转发让 App 的本地 Gateway 与测试邮件地址可达；Web 仍由宿主机 Chromium 访问 `127.0.0.1:8088`。首次 Android 构建与安装超过原三分钟入房等待，测试等待已延长到八分钟。所有运行均使用随机测试账号及隔离数据库。

通用入口首次复跑中，Web 自动回合取得 `/api/auth/csrf` 时返回 429，导致脚本在结算前失败。原因是身份服务把每次 CSRF 获取和账号资料请求也计入所有用户共享的 IP 认证额度，四人长局累计超过 15 分钟窗口的 120 次限制。现仅对认证路径计入该额度；`AuthIT` 同时验证额度满时 CSRF/已认证资料仍可访问，登录依旧返回 429。修复后的身份服务镜像已在隔离栈重建，完整 Android 同局测试通过。正式部署仍需按实际代理拓扑校准认证限流容量。
