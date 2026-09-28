# stage17 本地试玩包 0.3.9 验收

日期：2026-09-28。源码与镜像构建提交均为 `02c0d0f30dc7f5f1ba478f1a974eb86dc6756171`，打包时工作区干净。本版新增 Compose 业务路由就绪门槛与 Gateway 下游 readiness，并在 iOS 打包前检查当前 Xcode 选择。

- [试玩归档](../release/0.3.9-local-playtest.tar.gz) SHA-256：`4c51d02f8f766de1e72631a35f65751cd313afb1f751de94d9cb9976e1971a0b`。归档校验通过，包内 `SHA256SUMS` 的 247 个文件逐一校验通过。
- [离线镜像归档](../release/0.3.9-local-playtest-images/images.tar.gz) SHA-256：`145e5fd59f364c1ceb8e3efe4f0ea0f3e93d88626d051002f7b64e4e50309b9a`。[镜像清单](../release/0.3.9-local-playtest-images/manifest.json) 记录四个 `linux/arm64` 镜像 ID、基础镜像 digest、源码提交和原包哈希；镜像归档校验并通过 `docker load -i` 导入。标签为 `0.3.9-local-playtest-02c0d0f30dc7`。
- 打包执行 Web 15 个文件/93 项测试、Maven 单元 59 项和集成 88 项测试（LiveKit 媒体集成跳过 1 项）、Flutter 静态分析与 33 项 widget 测试；Android debug APK 与 iOS Simulator App 构建成功。Android APK SHA-256 `0bf2c26e8ada7cdb8a6d4e319b96b12a831f999fd6f74e6a619db1c053645bde`，iOS `App.framework/App` SHA-256 `2158e2ae62b9a8f60382200df23160a4fb4e959d792d91d9ec74e91befc028db`，均与 0.3.8 逐字节相同。
- 从解包目录使用包内 Compose 文件、四个包内镜像和隔离 PostgreSQL 卷运行账号启用栈，`up --no-build -d --wait` 等到所有容器 `healthy`。API 烟测，以及随机账号注册、Mailpit 验证、App 凭证登录、资料更新、房间加入、双向文字、幂等和离房访问控制均通过。停用包内 Game 镜像时 Gateway readiness 为 503、liveness 为 200，Gateway/Web 容器转 `unhealthy`；重启 Game 后再次 `up --wait` 全部恢复 `healthy`。隔离项目为 `uno-package-039`，测试后执行 `down` 并保留数据卷，没有改动原 stage17 测试库。

首次打包尝试因本机 `xcode-select` 指向 CommandLineTools 而在 iOS 步骤失败；已加入提前执行 `xcodebuild -version` 的检查。成功打包使用当前命令的临时 `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer` 和 [既有 xcrun 包装](verification-stage4-flutter.md)，未更改全局 Xcode 选择。

## 包内普通 App 增量验收

之后从同一 0.3.9 归档安装 Android debug APK 到 API 36.1 `emulator-5554`，连接本包的独立 `uno-package-039` 容器栈。包内 APK 固定访问 `10.0.2.2:28080`；本机模拟器无法直接访问 Docker Desktop 发布的回环端口，因此测试时在宿主机用临时 TCP 桥接 `127.0.0.1:28080 → 127.0.0.1:48080`，而包内 Gateway 实际发布在 `48080`。这是本机网络绕行，**不是包内自带的设备接入能力**。

普通 App 完成测试账号登录、加入 Web 创建的房间、准备、接收实时牌面，并在同一经典对局 `caa4f612-e58a-4f65-afbe-94ec4125730d` 中通过 ADB 触控真实界面提交 **266 次**出牌、摸牌、UNO 与 +4 应对等操作。随后停止 App，让同一测试账号经 App API 自动补完剩余回合，以便验证终局和战绩；故不能声称这 26 轮全部由普通 App 界面操作。服务端在版本 2104 结算：APK 玩家 503 分胜、Web 玩家 447 分负，双方历史分别为 `WIN`/`LOSS`。重新启动未重装的普通 APK 后，账号页显示经典 1 胜 0 负、26 轮和两人成绩；[Android 战绩截图](evidence/stage18-android-apk-0.3.9-history.png)。调试期间的第一场对局因连续超时中断，历史按规则显示为中断、不计胜负。

包内 iOS Simulator App 在 iPhone 17 Pro / iOS 26.5 模拟器上安装、启动，但账号页显示无法读取安全存储。模拟器 `securityd` 记录 `-34018`，指出应用缺少 `application-identifier` 或 `keychain-access-groups` 授权；该包使用 `--no-codesign`，**iOS 0.3.9 普通 App 登录与玩法未通过**。单独给包外复制品追加签名未能得到可启动且可读 Keychain 的制品。后续 [iOS 本地预览修复记录](verification-stage18-ios-ephemeral-preview.md)区分模拟器临时会话与正式签名要求。

先前的 [Web 和模拟器综合矩阵](verification-stage18-0.3.7-candidate.md)、[同 schema 回退演练](verification-stage17-local-rollback-0.3.8.md)仍是相应证据；本版没有新增生产 TLS/TURN、签名真机包、跨网媒体或新迁移回退证明。
