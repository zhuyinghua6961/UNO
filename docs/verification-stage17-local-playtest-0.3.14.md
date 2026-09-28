# stage17 本地试玩包 0.3.14 验收

日期：2026-09-28。试玩包与离线镜像均来自干净提交 `e498aa184ddc73042b3fe69d08885521066ea90a`。本版将 iOS 收到远端音轨后退出语音时的清理顺序修复装入移动 App；此前 0.3.13 包不包含该修复。这是本机预览制品，不是签名发布版。

## 制品与构建

- [试玩归档](../release/0.3.14-local-playtest.tar.gz) SHA-256：`a679eaf9a0b6d2a08b99b00f49df39f222ac93a4a79408f3746a6d48cf08a8a4`。归档与包内 247 个文件的 `SHA256SUMS` 校验通过；manifest 记录 `sourceDirty=false` 和完整提交号，包内没有 `.env`。
- [离线镜像归档](../release/0.3.14-local-playtest-images/images.tar.gz) SHA-256：`b076e4238bc61c420c68c11545beb9e6e59a2f24c41668640591053509e1ea12`。[镜像清单](../release/0.3.14-local-playtest-images/manifest.json)中的包哈希与上方一致，四个 `linux/arm64` 镜像的标签为 `0.3.14-local-playtest-e498aa184ddc`；归档和清单哈希校验、`docker load -i` 导入均通过。
- 打包脚本从锁文件安装 Web 依赖，94 项 Web 测试和生产构建、Maven `database-it` 全量验证（跨服务 12 项）、Dart 静态检查、34 项 Flutter 测试、Android debug APK 与未签名 iOS Simulator App 构建通过。Android APK SHA-256：`42236ad60390d07f37299a1410c63b629b6d137e39c3b081b037c793578caef1`；iOS `App.framework/App` SHA-256：`26bdad2eb2edc51a4b8bdc70941da212df0155e82812aa5c2012f7cbb2fc6812`。
- 包内普通 Android APK 在 API 36 模拟器安装、启动并显示[大厅](evidence/stage17-android-0.3.14-launch.png)；包内普通 iOS App 在 iPhone 17 Pro / iOS 26.5 模拟器安装、启动并显示[大厅](evidence/stage17-ios-0.3.14-launch.png)。Android 截图只验证启动；普通 iOS App 的整局证据见下文。

## 独立解包栈

从归档解出包内文件，用包内 `init-local-env.mjs` 为新 Compose 项目 `uno-package-0314-voice` 生成独立随机密钥与空数据库卷。使用包内四个 Compose 文件及临时端口覆盖配置，以 `--no-build -d --wait` 启动：Web `127.0.0.1:64088`、Gateway 最初为 `64080`、Mailpit `64025`、LiveKit 信令 `7900`、媒体 TCP `7901`、UDP `7902`。六个带健康检查的容器均健康，LiveKit 运行。为让包内普通移动 App 使用编译时默认地址，后来停止旧 `uno-stage17-check` 的 Gateway，并把本项目 Gateway 改绑到 `127.0.0.1:28080`；两个项目的数据库卷均未改动。

并行端口覆盖方式与[0.3.13 语音栈配置](verification-stage18-0.3.13-voice.md)相同：将其中的 Web/Gateway/Mailpit 端口改为 `64088/64080/64025`、LiveKit `port/tcp_port/udp_port` 和 Game 公共地址改为 `7900/7901/7902`，并设置镜像标签 `0.3.14-local-playtest-e498aa184ddc`。默认端口在空闲机器上可直接使用包内[部署说明](../deploy/README.md)；临时密钥和覆盖配置不在发布归档中。

- 真实账号烟测通过：注册、邮件验证、App 登录、资料、双向房间文字、经典整局与双方历史；四人 2v2 的队伍文字隔离、终局和双方战绩一致；四个授权语音令牌仅允许麦克风发布，并按队伍隔离。
- 包内 Web 的真实浏览器用例通过：同队双向接收虚拟麦克风 PCM 样本、对手隔离、麦克风生命周期和终局房间清理；房外账号不能读取房间、牌局、聊天或取得语音令牌，对手手牌 ID 不公开。
- 三名包内 Web 玩家与当前源码的 iOS **Flutter 集成测试 App** 完成四人 2v2：iOS 仅收听模式订阅 Web 队友音轨后退出，Web/App 房间与队伍文字双向传送，双方界面出牌到终局、战绩一致；对局 `bde194e4-f481-4b88-a733-a40f6ba69a25`。Android 集成测试 App 也在同栈完成 Web/Android 双向音轨订阅、文字、双方界面整局与战绩；对局 `fd056466-4e9a-4c1f-a5b8-9ac56ae857f0`。Android 临时 `adb reverse` 映射已撤销，两个模拟器现重新安装包内普通 App。

## 普通 iOS App 同版整局

把 `0.3.14` 包内**普通** `Runner.app` 安装到 iPhone 17 Pro / iOS 26.5 模拟器，连接上述同版 `28080` Gateway。经普通界面登录、创建双人经典房、准备并开局；另一名已验证的 App 测试账号经 API 加入并自动出牌。iOS 玩家在普通界面连续完成摸牌、出牌、万能牌选色、接受 +4、结束摸牌回合和开始下一轮；最后通过普通界面持续摸牌，让对手结束本轮。整场经过 8 轮和服务端超过 1100 个状态版本，约 21 分钟后正常进入[普通 App 结算页](evidence/stage18-ios-ordinary-0.3.14-settlement.png)，对局 `ea25c017-e479-4ccc-b4fe-e0683e2af80b`，双方累计 `55:1110`，末轮得分 `713`。期间 App 保持实时连接；自动玩家测试账号的 15 分钟访问令牌续期后继续参赛。

[普通 App 战绩页](evidence/stage18-ios-ordinary-0.3.14-history.png)显示经典 `0 胜 1 负 0 中断`、8 轮及 `55:1110`；后端 `/api/matches/history`、`/api/matches/stats` 对双方分别返回 `LOSS`/`WIN` 和一致的比分。此项验证普通 iOS 安装包在同版栈上从登录到结算、历史的完整界面流程，补齐先前仅由 Flutter 集成测试入口覆盖的缺口。

切换到同版栈前，旧 `uno-stage17-check` 在另一场长局的身份内省请求中返回 `429 RATE_LIMITED`，游戏服务转为 `503 AUTH_UNAVAILABLE`，普通 App 无法继续；这场旧栈对局未计作成功证据。同版 `0.3.14` 栈完成上述更长整局，身份限流桶最高仅 8 次，没有重现旧栈错误。

集成测试 App 与包内 App 使用同一 Flutter 产品源码，但前者由测试入口启动；**普通 Android 包的整局界面验收仍待完成**。iOS 语音只核对音轨订阅事件，未测扬声器 PCM 或真实听感。包内 Android debug APK 预设 `10.0.2.2:28080`，iOS Simulator App 预设 `127.0.0.1:28080` 并使用 Debug 进程内会话。真机签名、真实 Keychain、跨网 ICE/TURN、生产 TLS/WSS、服务重启媒体恢复和正式容量目标仍未验收。
