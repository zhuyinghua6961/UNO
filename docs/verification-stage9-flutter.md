# stage9 Flutter 经典对局增量验收

日期：2026-09-23。范围：Flutter 经典牌桌、原生实时连接与 Android/Web 本机混合对局。iOS 已完成模拟器启动检查，尚未完成真实对局验收。

## 已实现

- 等待室中房主可启动经典局，其他成员通过当前对局接口发现牌局；返回等待室后可继续牌局，终局后可再次准备开局。
- 原生 App 使用 Bearer 与 `X-UNO-Client: APP` 请求个人牌面、建立 `/ws/game` 连接、先订阅再发送命令。重连前重新取得私有状态，不自动重发未确认动作；页面销毁与前后台切换关闭旧订阅。
- 牌桌按服务器版本展示手牌、玩家牌数/得分、牌堆、颜色、方向和截止时间；提供出牌、摸牌、放弃、选色、UNO、抓漏喊、+4 接受/质疑、下一轮和终局返回。命令等待确认时禁止重复提交，拒绝后补同步。
- 卡牌素材来自 `assets/cards`，来源见仓库 `assets` 说明。点击音效可静音，偏好写入设备安全存储。

## 本地验证

- `flutter test --no-pub`：17 项通过。新增 API 测试核对原生请求头与个人视图；本地 WebSocket 服务器测试核对订阅顺序、限流重连、不自动重发；组件测试核对手牌点击面积、UNO、万能选色、+4 质疑、等待确认、旧快照与销毁关闭。
- `dart analyze --format=machine lib test`：无诊断；本机 `flutter analyze` 的 LSP 异常见既有工程记录。
- `flutter build apk --debug --no-pub`：成功。Android API 36.1 `Medium_Phone_API_36.1` 模拟器安装并运行 debug APK；在实际牌桌通过触控执行出牌、摸牌、UNO、万能牌选色、+4 应对、结算返回和第二局启动。
- 使用本机 Xcode 临时工具选择（见 [stage4 记录](verification-stage4-flutter.md)）执行 `flutter build ios --simulator --no-pub --dart-define=API_BASE_URL=http://localhost:29080`：成功。在 iOS 26.5 `iPhone 17 Pro` 模拟器安装、启动并目视确认大厅；尚未在 iOS 上完成登录和对局。
- 隔离启动 PostgreSQL 16、Mailpit、identity/game/gateway 三个 Java 进程和 Vite。注册并验证两个新账号，Android App 房主与 Web 玩家加入同一房间；房主在 App 启动对局，双方通过各自 UI 完成经典局。终局 `0c9f76e7-a724-497d-b767-6e4ddd7d477c` 的权威状态为 `MATCH_OVER`、版本 436，房主 0 张牌、得分 1220；[Android 结算画面](evidence/stage9-android-settlement.png)与 [Web 结算画面](evidence/stage9-web-settlement.png)一致。双方返回等待室后，通过 UI 准备并由 App 启动另一场 `b868badb-495f-48eb-8a62-259911953adb`，[Android 第二局画面](evidence/stage9-android-second-match.png)已核对。
- 联调时 Gateway 的 WebSocket 上游连接为原生 App 请求补充了上游同源 `Origin`。game-service 已限缩允许该同源值，仍拒绝任意来源及 Cookie/Fetch Metadata 混用；原生 WebSocket 回归测试覆盖此场景。
- `mvn -f backend/pom.xml -Pdatabase-it verify -Duno.postgres.image=postgres:16-alpine -q`：全模块数据库集成构建通过，包含跨服务认证及 WebSocket 握手回归。`npm test -- --run`：Web 69 项通过。最终代码再次完成 Android debug APK 与 iOS 模拟器构建。

## 待验收

- 在 iOS 模拟器或真机使用真实账号完成同房对局，验证结算与第二局。
- 在 Android/iOS 牌桌上系统检查长手牌滚动、系统返回、前后台和网络切换；Android 已完成整局触控，但这些恢复边界尚未逐项验收。
- 设备上验证音效偏好跨重启保存。完整弱网与连续超时策略归 stage12。
- 当前超时会自动摸牌。联调中 Web 玩家持续摸牌曾耗尽摸牌堆，双方短暂陷入无可出牌状态；通过 Web 玩家改色后继续至终局。stage12 应明确牌堆耗尽、无人可出及持续超时的收敛策略。

版本或提交：见本阶段 Git 提交。

2026-09-24 后续增量：iOS 模拟器已进一步通过原生安全存储与真实本地服务的账号、组房、开局及一次回合动作验证；仍未完成 iOS 整局。见 [iOS 模拟器增量验收](verification-stage9-ios.md)。
