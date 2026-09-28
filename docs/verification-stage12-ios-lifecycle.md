# stage12 iOS 模拟器牌桌前后台恢复补验

日期：2026-09-28。iPhone 17 Pro / iOS 26.5 模拟器；后端为 [0.3.12 本地包](verification-stage17-local-playtest-0.3.12.md)的独立 Compose 栈（Gateway `127.0.0.1:59080`、Mailpit `127.0.0.1:59025`）。测试使用当前源码的 `integration_test/local_ios_play_test.dart` 驱动模拟器 App；产品端 Flutter 代码与 0.3.12 包相同，新增的是集成测试流程，**不是在包内普通 App 上手动逐张完成整局**。

## 实测

- 随机注册并验证两个真实账号。iOS 界面登录房主、创建经典房、准备并开局；另一账号通过 App API 加入和准备。
- iOS 牌桌先从界面向真实 WebSocket 提交一项回合动作，服务端版本推进。测试按 Flutter 有效序列注入 `inactive→hidden→paused`；暂停后另一个权威动作通过已认证 API 提交，版本恰好增加 1。
- 按 `hidden→inactive→resumed` 回到前台。测试同时核对牌桌显示“实时连接”、App 持有的私有视图版本追上服务器、本人手牌张数一致。轮到房主后，iOS 界面再次提交动作，服务器版本恰好再增加 1。
- 再次暂停牌桌，两个真实账号的 API 按规则推进经典局至 `MATCH_OVER`；回前台看到结算，双方历史为同一对局的 `WIN`/`LOSS`。iOS 界面返回等待室并启动不同 ID 的第二局。
- 下列命令在同一栈连续两次通过（分别约 56 秒和 96 秒，均为 1 项通过）；`dart analyze lib test integration_test` 无问题，Flutter 33 项测试通过：

```sh
cd flutter
PATH=/tmp/uno-xcode-tools:$PATH DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
flutter test --reporter expanded --timeout 5m integration_test/local_ios_play_test.dart \
  -d 00AF3E75-A77A-4F3F-BCBF-FF3E12CD6BBC \
  --dart-define=UNO_LOCAL_IOS_E2E=true \
  --dart-define=API_BASE_URL=http://127.0.0.1:59080 \
  --dart-define=MAILPIT_BASE_URL=http://127.0.0.1:59025
```

`/tmp/uno-xcode-tools` 只为本机选取完整 Xcode，不属于交付包。测试使用随机本地账号，未发送互联网邮件。

## 限制与故障记录

这验证了 iOS 模拟器中真实服务、原生 WebSocket 和 Flutter 生命周期回调的组合。它没有让操作系统真正切后台、开启飞行模式、改变 Wi-Fi/蜂窝网络，也没有验证签名真机上的 Keychain 或音轨；两次 iOS 界面动作之后的整局由 API 自动玩家推进。

调试测试流程时曾直接注入 `resumed→paused`，触发 Flutter 框架的无效生命周期断言；已改为完整有效序列。一次在暂停后调用 `tester.pump()` 使 iOS 测试帧调度停滞；后台阶段现在只执行网络动作，回前台再检查 UI。未限速的数千次 API 自动动作曾遇到一次 503 `AUTH_UNAVAILABLE`；给自动补局加 25 毫秒间隔后，两次完整运行通过。另一次等待室加载超时、一次恢复订阅等待超时尚未证明根因，测试保留界面/连接诊断；不能以两次通过推断长期稳定性或容量达标。目标设备的系统级网络切换和这些偶发故障仍需进一步排查。
