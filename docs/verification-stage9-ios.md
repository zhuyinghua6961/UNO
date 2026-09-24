# stage9 iOS 模拟器真实服务增量验收

日期：2026-09-24。平台：iOS 26.5、iPhone 17 Pro 模拟器；服务：隔离的 `uno-stage17-check` Compose 栈，Gateway 绑定本机 `127.0.0.1:28080`，Mailpit 绑定本机 `127.0.0.1:28025`。测试过程不使用正式账号或公网服务。

## 操作与结果

- 模拟器上 `flutter test integration_test/secure_storage_test.dart -d <simulator-id>` 通过：原生安全存储跨实例读取与删除均成功。
- `integration_test/local_ios_play_test.dart` 由 `UNO_LOCAL_IOS_E2E=true` 显式开启，通过 `API_BASE_URL` 指定隔离 Gateway。测试从模拟器向真实 Identity 服务注册并验证两个随机账号，验证邮件只从本地 Mailpit 读取；iOS UI 登录房主账号、创建经典房间，第二账号经真实 App API 加入并准备。
- iOS UI 刷新成员、准备并启动对局；第二账号经真实对局 API 完成其回合，iOS 牌桌取得实时连接并从界面提交自己的回合操作。再次读取服务器私有状态，版本高于提交前。命令通过 iOS 原生 WebSocket 提交，没有直接改数据库来伪造出牌结果。
- 测试随后通过两个真实账号的 App API 按规则推进整场经典局。权威状态达到 `MATCH_OVER`；iOS 牌桌显示正常结算，两位成员的历史对局 ID 相同且分别为 `WIN` 与 `LOSS`。iOS UI 返回等待室，再次准备并启动第二局，新对局 ID 与首局不同。
- 本机命令：`PATH=/tmp/uno-xcode-tools:$PATH DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer flutter test integration_test/local_ios_play_test.dart -d 00AF3E75-A77A-4F3F-BCBF-FF3E12CD6BBC --dart-define=UNO_LOCAL_IOS_E2E=true --dart-define=API_BASE_URL=http://127.0.0.1:28080`，结果 1 项通过。测试会在隔离数据库中留下随机测试账号/房间；第二局闲置后按超时策略中断。`/tmp/uno-xcode-tools` 是本机 Xcode 工具选择路径，不是项目依赖。

## 边界

- 首局仅第一个 iOS 回合由界面提交；后续回合由 API 自动玩家推进，iOS UI 验证终局显示并启动第二局。这证明 iOS 的真实连接、动作提交和结算接收链路，但尚未逐张触控完成整局。iOS 2v2 队友语音、真实设备麦克风和网络切换仍未验证。
- 模拟器本机地址与正式 HTTPS/TURN 网络不同；此项不能替代真机安装或跨网验收。
