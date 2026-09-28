# stage17 本地试玩包 0.3.12 验收

日期：2026-09-28。包和镜像来自干净源码提交 `7ba0bbdcd533700b7c17b52831d2b37bc2aefb8d`。本版修复自定义 `WEB_PORT` 下浏览器 Origin 未被允许的问题，并让 Web 牌桌响应浏览器离线/在线事件。

- [试玩归档](../release/0.3.12-local-playtest.tar.gz) SHA-256：`a101c924bd5d3825bf9642460c18388750dfe53637fbcdaa3969b7b402570b3b`；外层归档和包内 234 个文件的 `SHA256SUMS` 均校验通过。包内没有 `.env`。
- [离线镜像归档](../release/0.3.12-local-playtest-images/images.tar.gz) SHA-256：`86dbb3b53d047a3b7adff6fbb2ddece3069055aeb0a7155627b3f563320278be`；镜像归档及清单的 `SHA256SUMS` 校验通过，四个 `linux/arm64` 镜像实际 `docker load` 通过。标签 `0.3.12-local-playtest-7ba0bbdcd533`，[镜像清单](../release/0.3.12-local-playtest-images/manifest.json)绑定包哈希和源码提交。
- 打包从锁文件安装 Web 依赖，Web 94 项测试及生产构建、Maven `database-it`、Dart 静态分析、Flutter 33 项测试、Android debug APK 和未签名 iOS Simulator App 构建通过。Android APK SHA-256 为 `22bcc6543f43694f5a10081b3c547a974a0eded3340bacba255de0716be4e0ee`；iOS `App.framework/App` 为 `23fe9bd5f56a3889e42879076061ce2f6376d623ab4ffe903c16a8515eda0085`，均与 0.3.11 相同，本版移动端未改代码。
- 包内生成独立随机本地密钥，用包内 Compose 和同版镜像在空数据库卷启动 `uno-package-0312`；Web `127.0.0.1:59088`、Gateway `59080`、Mailpit `59025`，`up --no-build -d --wait` 等待全部服务健康。**未设置 `AUTH_ALLOWED_ORIGINS`** 时，包内 Identity/Game 的默认值均为 `localhost:59088,127.0.0.1:59088`。真实 Playwright 浏览器在包内 Web 上完成两个账号注册、邮件验证、登录、双人经典局开局；一端离线后显示重连，另一端推进版本 1→2，恢复端收到版本 2 并提交到版本 3，过程无页面脚本错误。源码开发服务器同一用例另连续通过两次。
- 对同一包内隔离栈运行 `SMOKE_FULL_MATCH=true SMOKE_TEAM_MATCH=true`：真实账号、房间双向文字、经典整局、四人 2v2 整局、双方战绩和队伍文字隔离通过。该栈未启用 LiveKit，不能作为语音验收。

本版未对包内 Android/iOS 普通界面重新完成整局；iOS 未签名模拟器构建仍使用仅供试玩的进程内登录，会在进程退出后失去会话。此前 [0.3.11 包内 iOS 普通界面](verification-stage17-local-playtest-0.3.11.md)及 [0.3.9 Android 普通界面](verification-stage17-local-playtest-0.3.9.md)的范围维持原记录。目前没有 Apple Developer 团队和目标 iPhone，真机签名、真实 Keychain、设备音轨、跨网弱网、正式 TLS/TURN 与发布容量均未通过验收。
