# stage17 本地试玩包 0.3.13 验收

日期：2026-09-28。包和镜像来自干净提交 `65820f647ba1459bd64d220f28e556c6b96c6e50`。本版修复正常游戏流量耗尽 Identity 内部鉴权共享额度后，Game 将有效会话误报为 `AUTH_UNAVAILABLE` 的机制。无效服务凭证改为按来源 IP 单独限流。

- [试玩归档](../release/0.3.13-local-playtest.tar.gz) SHA-256：`c5e4d35c54f6b470e9f8ade3bec677dc20d6d573fe4eb499ee503b7905fe2ac5`。外层归档和包内 247 个文件的 `SHA256SUMS` 校验通过；包内不包含 `.env`。
- [离线镜像归档](../release/0.3.13-local-playtest-images/images.tar.gz) SHA-256：`a46efcdb9e4b67bfc588bde1ab8b621c5bfa486e6fd8e3318ed1da3b84b5fbeb`。镜像归档和清单校验通过，四个 `linux/arm64` 镜像实际 `docker load` 通过；标签 `0.3.13-local-playtest-65820f647ba1`，[镜像清单](../release/0.3.13-local-playtest-images/manifest.json)记录包哈希和源码提交。
- 打包从锁文件安装 Web 依赖，94 项 Web 测试及生产构建、Maven `database-it` 全量构建、Dart 静态分析、33 项 Flutter 测试、Android debug APK 和未签名 iOS Simulator App 构建通过。Maven 跨服务 12 项测试通过；LiveKit 媒体集成测试跳过 1 项。移动端产品代码未改，APK 与 iOS `App.framework/App` 哈希仍分别为 `22bcc6543f43694f5a10081b3c547a974a0eded3340bacba255de0716be4e0ee`、`23fe9bd5f56a3889e42879076061ce2f6376d623ab4ffe903c16a8515eda0085`。
- 从包内生成随机本地密钥，使用同版镜像在空数据库卷启动独立 Compose 栈 `uno-package-0313`。Web 为 `127.0.0.1:61088`，Gateway 为 `127.0.0.1:61080`，Mailpit 为 `127.0.0.1:61025`；六个服务经 `up --no-build -d --wait` 全部健康。网关路由和未登录拒绝检查通过。
- 对该栈运行 `SMOKE_FULL_MATCH=true SMOKE_TEAM_MATCH=true` 的真实账号烟测：注册、邮件验证、登录、房间双向文字、经典整局、四人 2v2 整局、队伍文字隔离及双方战绩一致性均通过。iPhone 17 Pro 模拟器另对同一栈运行 `integration_test/local_ios_play_test.dart`，界面登录开局、前后台恢复、恢复后再次出牌、结算并开启第二局，1 项通过，约 86 秒；此项是测试构建的 App，不能等同于包内普通 App 完整 UI 整局。
- 对同一栈以一个真实 App 会话执行 8 路并发的 1200 次 `GET /api/system/session`，全部返回 200；本机测得中位数 5 毫秒、P95 10 毫秒、最大 20 毫秒。仅为本机短时检查，不代表长期容量或跨网延迟。

修复前，在独立 0.3.12 栈将原内部共享计数置为 9999 后，第二次有效会话返回 503 `AUTH_UNAVAILABLE`。修复后同一脚本的两次有效会话均返回 200，旧计数保持 9999；跨服务测试还验证无效服务凭证超额返回 429 时，有效 Game 会话继续返回 200。这证明共享额度这一故障机制已经消除；此前偶发 503 是否还存在超时或其他原因，仍需更长时间的负载观测。

包生成后，测试代码另扩展了 iOS 界面选牌、提交和服务端确认路径；同一 0.3.13 栈上的增强用例约 50 秒通过，产品端 Flutter 代码与包一致。此项仍由 API 自动玩家完成后续回合，不能写成包内普通 App 已逐张完成整局。[身份链路可复现基线](verification-stage18-capacity-baseline.md)另记录 15 秒持续请求与环境边界。

同版包内 Web `127.0.0.1:61088` 另运行真实 Playwright 浏览器：两个账号经界面注册、验证、登录并加入经典局；双方实时同步与私有手牌检查通过，18 轮、1242 次 UI 动作达到终局状态，随后第二局开局、刷新恢复和窄屏布局检查通过，页面脚本错误为空。双方战绩由前述 API 整局烟测核对，浏览器用例未进入战绩页。独立断网恢复用例也通过：一端离线时版本 1→2，恢复后同步并提交到版本 4。该栈仍未启用 LiveKit；本版普通 Web 2v2 完整界面整局未复测。

本版没有 Apple Developer 团队和目标 iPhone，未完成真机签名、真实 Keychain 会话、设备音轨或跨网弱网验收；本次 Compose 未启用 LiveKit。镜像仅供本机试玩，没有生产 TLS/TURN、目标环境、容量或兼容迁移回退证据。0.3.12 仍是历史包，不包含此修复。
