# stage18 安全回归与 V17 语音撤销补验

日期：2026-09-28。测试对象为本地隔离 `uno-stage17-check` Compose 栈与当前源码的 Web/Flutter 自动化；该栈在本轮重建 Game 服务并将既有游戏库实际迁移到 V17。它尚不是正式签名、同版不可变镜像的发布环境。

## 修复与验证

原语音复核只检查 **仍在媒体房间内** 的会话。账号撤销时若旧客户端先断开，服务端可能看不到该失效会话而保留旧代次，尚未过期的旧 JWT 可重建旧房间。V17 增加按对局、媒体代次、账号会话记录令牌签发的 `game.voice_issued_sessions`；复核同时检查已签发会话与在场参与者。任何当前代次会话失效或无法确认时切换代次并删除、持续清理旧房间。升级时对已有签发记录的进行中 2v2 先安排旧房间清理并换代，避免旧版无法追溯的令牌继续用于新房间。

- `mvn -q -f backend/pom.xml -Pdatabase-it clean verify` 通过：单元 59 项、数据库集成 88 项，失败/错误 0，媒体集成测试跳过 1 项。新增集成测试验证已发令牌的用户离开媒体房间后，撤销其会话仍能换代并清理 A/B 旧房间；原有连接中撤销、限流、终局清理继续通过。
- `uno-stage17-check` 原有数据库随 Game 镜像重建完成 V17 Flyway 升级，Game 日志显示迁移成功，Gateway 健康检查为 `UP`。本地数据库确有旧版进行中对局，升级流程没有清空账号、房间或牌局数据。
- `UNO_E2E_VOICE_REVOKE=1 npm --prefix web run test:e2e:voice-revoke` 通过：五个真实登录身份中的房外账号不能读取房间、牌局、房间/队伍文字、执行出牌或领取语音令牌；对手视图只包含允许字段，双方手牌物理 ID 不重叠。A1 会话撤销后，旧麦克风轨道结束、静音的 A2 进入新代次且未重新采集；A1 旧 JWT 无法订阅新房间。`npm --prefix web run test:e2e:voice` 也通过双向浏览器虚拟麦克风样本、终局释放与旧令牌房间重复清理。
- Android API 36.1 模拟器执行 `UNO_E2E_DEVICE_ID=emulator-5554 UNO_E2E_MOBILE_CHAT=1 UNO_E2E_MOBILE_VOICE=1 UNO_E2E_ANDROID_MIC_TRACK=1 npm --prefix web run test:e2e:mixed-android-team` 通过：三名 Web 玩家与一名 Android UI 玩家完成双向音轨订阅、房间/队伍文字、双方界面出牌、2v2 结算与战绩；对局 ID `bd7f29bb-6d8e-437b-9b4a-ec7256d6c1b5`。
- iPhone 17 Pro / iOS 26.5 模拟器执行 `UNO_E2E_DEVICE_ID=00AF3E75-A77A-4F3F-BCBF-FF3E12CD6BBC UNO_E2E_MOBILE_CHAT=1 UNO_E2E_MOBILE_VOICE=1 npm --prefix web run test:e2e:mixed-ios-team` 通过：三名 Web 玩家与一名 iOS UI 玩家完成文字、仅收听加入/退出、双方界面出牌、2v2 结算与战绩；对局 ID `1e55a964-9b50-417c-8070-ad06900fa403`。构建命令仍需 [临时 Xcode 工具选择](verification-stage4-flutter.md)。连续测试碰到账号 15 分钟限流时，只将隔离测试库近期 73 条限流窗口置为过期，未改产品逻辑。
- `npm --prefix web test`：93 项通过；`dart analyze lib test integration_test` 无问题；`flutter test`：33 项通过。混合端脚本现通过 Web/App 各自界面操作，符合 V16 WebSocket 出牌所有权；房外伪造 HTTP 命令依旧被服务端拒绝。

## 未覆盖

撤销到房间换代依赖约 5 秒的轮询，旧代次清理保留到短令牌到期；此处只验证本地隔离栈。尚未在目标 TLS/TURN 网络、跨实例高并发和真实设备上测定撤销延迟、音频实际可听、iOS 音轨、弱网恢复或容量。更广的伪造身份、日志脱敏和生产部署安全矩阵仍需 stage18 继续验收。此前 `0.3.5-local-playtest` 包来自 V17 之前的提交，**不包含本修复**。
