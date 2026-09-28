# iOS 模拟器试玩包的安全存储与临时会话

日期：2026-09-28。设备为本机 iPhone 17 Pro / iOS 26.5 模拟器，服务为隔离的 `uno-package-039` 栈。本记录只涉及本地测试账号和未签名模拟器 App。

## 发现

从 0.3.9 包安装普通 `Runner.app` 后，首页可打开，但账号页提示无法读取安全存储，登录提交也报安全存储失败。模拟器 `securityd` 对 `SecItemCopyMatching` 记录错误 `-34018`：`Client has neither application-identifier nor keychain-access-groups entitlements`。0.3.9 打包使用 `flutter build ios --simulator --no-codesign`；`codesign --verify --deep --strict` 显示 App 未签名。此前的 iOS 整局 `integration_test` 注入内存 `TokenStore`，因此没有覆盖普通 App 的 Keychain 路径。

在包外复制品上尝试临时签名：无授权的 ad hoc 签名仍无法读 Keychain；追加自写访问组授权的 ad hoc/本机开发证书签名虽通过 `codesign --verify`，但模拟器拒绝启动。没有把这些试验复制品放入 release。根据 [Apple 的 Keychain 访问组说明](https://developer.apple.com/documentation/security/sharing-access-to-keychain-items-among-a-collection-of-apps)与[签名配置说明](https://developer.apple.com/documentation/technotes/tn3125-inside-code-signing-provisioning-profiles)，正式持久会话需要由有效签名及相应授权支撑；单纯在未签名包上补一个自写 entitlement 不是可交付的修复。

## 本地试玩处理

新增编译期开关 `UNO_LOCAL_EPHEMERAL_SESSION=true`。只有该开关显式传入、处于 Debug 模式且运行于 iOS 时，默认 App 使用进程内 `EphemeralTokenStore`；其余构建继续使用 `SecureTokenStore`。`tools/package-release.mjs` 仅向可选的未签名 iOS Simulator 试玩构建传入此开关。它让模拟器能登录和玩牌，但 App 进程退出后需重新登录；不把令牌写进不受保护的本地文件或偏好设置。

从工作区构建候选执行 `dart analyze lib test integration_test` 无问题，`flutter test test/auth_test.dart` 7 项通过，`flutter build ios --simulator --no-codesign --no-pub --dart-define=API_BASE_URL=http://127.0.0.1:28080 --dart-define=UNO_LOCAL_EPHEMERAL_SESSION=true` 成功。普通安装后用随机邮箱经本地 Mailpit 验证，在 App 账号页登录、显示昵称/战绩，创建经典双人房 `KNGFS25CFP`、调整人数、等待另一测试账号准备、在 App 开局并接收实时牌面。第二次测试局 `b9b5bd19-931f-4782-a5de-9073538dabdc` 中，普通 App 点击摸牌后界面显示“操作已由服务器确认”，手牌从 8 张变为 9 张。重新启动 App 后回到未登录状态，符合临时会话设计。第一局因测试暂停和连续超时中断；第二局在确认摸牌后结束客户端，均未计为 iOS 普通 App 整局通过。对局界面的音效偏好仍会显示安全存储不可用提示，本次使用默认设置。

## 包内复验

已从干净提交生成 [0.3.10 本地试玩包](verification-stage17-local-playtest-0.3.10.md)，安装**包内** iOS App 并经普通界面完成注册、验证、登录、开房、开局和服务端确认的摸牌；进程重启后回到未登录状态。这把先前的工作区修复候选提升为本机模拟器包的实际验收。

0.3.10 包内牌桌仍提示音效偏好 Keychain 不可用。随后将同一 Debug 模拟器开关应用到音效偏好：只在此模式使用进程内设置，其余构建仍使用安全存储。工作区重新构建的普通 iOS App 在独立 0.3.10 容器栈上登录、开房和开局后不再显示该告警；点击静音、返回等待室、重新进入牌桌后仍为静音。[0.3.11 包内普通 App](verification-stage17-local-playtest-0.3.11.md)已复验无告警、静音跨牌桌保留和服务端确认出牌；0.3.10 归档本身不包含此音效修复。

## 仍需完成

- 确认正式包名、Apple 开发团队、签名及授权配置，用真正的 Keychain 会话在目标 iPhone 上验证登录、重启恢复、升级与退出。
- 在包内普通 iOS App 上完成整局、结算、文字、音轨和网络切换测试。临时会话仅覆盖本机模拟器试玩，不代表这些门槛已通过。
