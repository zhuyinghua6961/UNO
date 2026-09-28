# stage17 本地试玩包 0.3.11 验收

日期：2026-09-28。源码提交 `afa7c53147a14ddf8937c243ad6d1b74408e4313`，打包和镜像构建时工作区干净。相对 [0.3.10](verification-stage17-local-playtest-0.3.10.md)，本版仅让未签名 Debug iOS Simulator 试玩模式的音效偏好也使用进程内存储；Android 与正式 iOS 构建仍使用安全存储。

- [试玩归档](../release/0.3.11-local-playtest.tar.gz) SHA-256：`093a03e9791546a42e7657e21606f959493c2b07d8b493a561e2f92537ed409e`。外层归档与包内 234 项 `SHA256SUMS` 均校验通过。Android debug APK SHA-256：`22bcc6543f43694f5a10081b3c547a974a0eded3340bacba255de0716be4e0ee`；iOS `App.framework/App` SHA-256：`23fe9bd5f56a3889e42879076061ce2f6376d623ab4ffe903c16a8515eda0085`。
- [离线镜像归档](../release/0.3.11-local-playtest-images/images.tar.gz) SHA-256：`242897e5eeb0b3b4db5a9c00158cf72c52bbea1216faf43e5f9dff9e06395781`。[镜像清单](../release/0.3.11-local-playtest-images/manifest.json)绑定包哈希和源码提交；四个 `linux/arm64` 镜像经 `SHA256SUMS` 校验并通过 `docker load -i`。标签 `0.3.11-local-playtest-afa7c53147a1`。
- 打包中的 Web 93 项测试/构建、Maven 数据库集成 profile、Dart 静态分析、Flutter 33 项测试、Android debug APK 与 iOS Simulator App 构建全部通过。
- 用包内 Compose 文件、新生成的随机本地密钥、同版镜像在空数据库卷启动 `uno-package-0311`，`up --no-build -d --wait` 等到 PostgreSQL、Identity、Game、Gateway、Web、Mailpit 全 healthy。对独立解包栈运行 `SMOKE_FULL_MATCH=true SMOKE_TEAM_MATCH=true node tools/smoke-auth-chat.mjs`：真实账号注册/验证、App 登录、双向房间文字、经典和四人 2v2 整局、双方战绩及队伍文字隔离均通过。该栈未开启 LiveKit，因此不是语音验收。

## 包内普通 iOS App

在 iPhone 17 Pro / iOS 26.5 模拟器安装**0.3.11 归档内** `Runner.app`，连接原本机测试 Gateway `127.0.0.1:28080`。普通界面登录、建立双人经典房 `Q9SGLDZX7E`、另一测试客户端入房准备、房主开局；牌桌不再显示 0.3.10 的音效偏好 Keychain 告警。点击静音后按钮改为“开启音效”，返回等待室再进入仍保持静音。在房间 `0ba7842b-6ae8-4d29-bcfe-efbf95a192d0` 的对局中，普通界面打出“蓝色反转”后显示“操作已由服务器确认”，方向变为逆时针、手牌 7→6。没有让这场普通 iOS 界面对局打到终局，不能据此声称包内 iOS 整局通过。

此包继续使用未签名 Debug 模拟器临时会话，进程退出会丢失登录及音效设置。先前 [0.3.10 包内 App](verification-stage17-local-playtest-0.3.10.md)已实际验证重启后退出登录。本版没有 iPhone IPA、正式签名或真实 Keychain 授权；当前也没有 Apple Developer 团队和目标 iPhone，真机登录、升级、音轨与跨网恢复仍未验证。Android 普通界面的数百次操作及终局战绩证据属于 [0.3.9](verification-stage17-local-playtest-0.3.9.md)，本版 Android APK 只完成构建。
