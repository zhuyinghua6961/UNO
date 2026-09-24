# stage11 Web/App 跨端文字验收

日期：2026-09-24。环境：隔离的 `uno-stage17-check` Compose 栈、无头 Chromium、iPhone 17 Pro iOS 26.5 模拟器、Android API 36.1 `Medium_Phone_API_36.1` 模拟器。测试只使用随机账号和隔离数据库。

## 结果

- 每次以三个 Web 账号和一个 App 账号加入同一四人 2v2 等待室，座位与队伍为 A/B/A/B。App 从界面发送房间消息与 B 队消息；三个 Web 账号均从认证历史读到房间消息，只有 Web B 队账号读到 B 队消息。App 消息的身份、昵称和时间由服务端返回，Web A 队两个账号均无法在团队历史取得 B 队正文。
- Web B 队玩家从界面发送队伍消息，Web A 队房主从界面发送房间消息；App 从当前队伍频道看到 B 队消息，房间频道出现 1 条未读，切换后看到 Web 房间消息。随后四人准备、开局，Web 与 App 各通过界面提交回合动作，正常完赛并核对四份团队战绩。
- iOS 对局 ID：`fc29b3b2-c4c8-4a6a-b21c-fc796ace8222`；Android 对局 ID：`3d8c4292-0b93-41c2-9bbb-0e5ce8a86b7d`。
- App 长列表滚动离屏后曾销毁聊天组件并丢失草稿、游标与未读；现保留该组件状态，Widget 测试验证离屏期间收到队伍消息后未读和未发送草稿仍在。频道切换后，iOS 集成测试显式聚焦输入框再输入，以覆盖系统键盘与焦点行为。

## 复现

启动隔离认证 Compose 栈及目标模拟器后，在 `web` 目录执行：

```sh
UNO_E2E_DEVICE_ID=<booted-ios-simulator-id> npm run test:e2e:ios-team-chat
```

Android 先执行 `adb -s emulator-5554 reverse tcp:28080 tcp:28080` 和 `adb -s emulator-5554 reverse tcp:28025 tcp:28025`，再在 `web` 目录执行：

```sh
UNO_E2E_DEVICE_ID=emulator-5554 npm run test:e2e:android-team-chat
```

脚本使用 Web `127.0.0.1:8088`、Gateway `127.0.0.1:28080` 和 Mailpit `127.0.0.1:28025` 默认地址。iOS 本机需选用完整 Xcode 工具链；本次使用 `PATH=/tmp/uno-xcode-tools:$PATH DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`。测试账号和消息留在隔离测试库。

## 边界

此项证明两种模拟器与 Web 经真实服务完成双向文字和当前队伍隔离。它不证明真机弱网体验、跨队实时推送事件、禁言/举报/删除流程或 30 天暂定保留期的产品确认。非成员、已退出成员和换队后的历史权限另有服务端集成测试与 [队伍文字验收](verification-stage11-team-text.md)；本次没有重复在设备 UI 上覆盖这些场景。
