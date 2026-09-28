# 0.3.13 同版语音栈与跨端补验

日期：2026-09-28。使用 [0.3.13 包与离线镜像](verification-stage17-local-playtest-0.3.13.md) 的 `0.3.13-local-playtest-65820f647ba1` 标签，在独立空库 Compose 项目 `uno-package-0313-voice` 启用账号与 LiveKit。未修改已有 `uno-package-0313` 试玩栈及 `uno-stage17-check` 数据。

## 本机环境

- Docker Compose v5.2.0；Web `127.0.0.1:63088`、Gateway `63080`、Mailpit `63025`，LiveKit 信令 `7890`、媒体 TCP `7891`、媒体 UDP `7892`，全部只绑定宿主机 loopback。新项目使用独立随机密钥和数据库卷。
- 合并包内 `compose.yaml`、`compose.auth-local.yaml`、`compose.voice-local.yaml`、`compose.images-local.yaml`。临时覆盖文件用 Compose `!override` 将 LiveKit 的 `ports` 和配置挂载改到上述端口，Game 的 `LIVEKIT_SERVER_URL` 改为 `http://livekit:7890`、`LIVEKIT_PUBLIC_URL` 改为 `ws://127.0.0.1:7890`。对应 LiveKit 配置使用 `port: 7890`、`rtc.tcp_port: 7891`、`rtc.udp_port: 7892`、`rtc.node_ip: 127.0.0.1`。本机配置与密钥在 `/tmp/uno-0313-voice-config/`，未进入 Git 或发布包。
- `docker compose --profile voice config --quiet` 通过；同版镜像 `up --no-build -d --wait` 后六个有健康检查的服务均健康，LiveKit 在运行，网关 `features.teamVoice=true`。常规本机重现可使用[部署说明](../deploy/README.md)的默认 7880–7882 端口；本次改端口是为与已运行的另一套本地栈并存。

本次使用的覆盖配置内容如下；随机 `.env` 不记录在文档中。将 `override.yaml` 中的挂载路径指向同目录的 `livekit.yaml`，以新解包目录的 `tools/init-local-env.mjs` 生成独立 `.env`，并设置 Web/Gateway/Mailpit 端口及镜像标签后即可复现。

```yaml
# override.yaml
services:
  livekit:
    ports: !override
      - "127.0.0.1:7890:7890"
      - "127.0.0.1:7891:7891/tcp"
      - "127.0.0.1:7892:7892/udp"
    volumes: !override
      - "/tmp/uno-0313-voice-config/livekit.yaml:/etc/livekit.yaml:ro"
  game-service:
    environment:
      LIVEKIT_SERVER_URL: http://livekit:7890
      LIVEKIT_PUBLIC_URL: ws://127.0.0.1:7890
```

```yaml
# livekit.yaml
port: 7890
bind_addresses: ["0.0.0.0"]
rtc:
  tcp_port: 7891
  udp_port: 7892
  use_external_ip: false
  node_ip: 127.0.0.1
```

实际启动命令合并了包内四个 Compose 文件及上述覆盖文件。可从干净解包目录重建随机密钥，再执行同样的检查与启动：

```sh
mkdir -p /tmp/uno-0313-voice-repro
tar -xzf release/0.3.13-local-playtest.tar.gz -C /tmp/uno-0313-voice-repro
VOICE_ROOT=/tmp/uno-0313-voice-repro/0.3.13-local-playtest
node "$VOICE_ROOT/tools/init-local-env.mjs"
export WEB_PORT=63088 GATEWAY_PORT=63080 MAILPIT_WEB_PORT=63025 MAILPIT_SMTP_PORT=63026
export UNO_LOCAL_IMAGE_TAG=0.3.13-local-playtest-65820f647ba1
docker compose -p uno-package-0313-voice --env-file "$VOICE_ROOT/deploy/.env" \
  -f "$VOICE_ROOT/deploy/compose.yaml" -f "$VOICE_ROOT/deploy/compose.auth-local.yaml" \
  -f "$VOICE_ROOT/deploy/compose.voice-local.yaml" -f "$VOICE_ROOT/deploy/compose.images-local.yaml" \
  -f /tmp/uno-0313-voice-config/override.yaml --profile voice config --quiet
docker compose -p uno-package-0313-voice --env-file "$VOICE_ROOT/deploy/.env" \
  -f "$VOICE_ROOT/deploy/compose.yaml" -f "$VOICE_ROOT/deploy/compose.auth-local.yaml" \
  -f "$VOICE_ROOT/deploy/compose.voice-local.yaml" -f "$VOICE_ROOT/deploy/compose.images-local.yaml" \
  -f /tmp/uno-0313-voice-config/override.yaml --profile voice up --no-build -d --wait --wait-timeout 180
```

在执行前需把上面的两段 YAML 保存为 `/tmp/uno-0313-voice-config/override.yaml` 和 `livekit.yaml`；镜像需先按[离线镜像说明](verification-stage17-local-playtest-0.3.13.md)导入。端口若被占用，须同时调整覆盖配置、LiveKit RTC 端口和 Game 公共地址。临时文件中的随机密钥不要加入 Git。

## 实测

- `API_BASE_URL=http://127.0.0.1:63080 MAILPIT_BASE_URL=http://127.0.0.1:63025 SMOKE_TEAM_MATCH=true SMOKE_TEAM_VOICE=true node tools/smoke-auth-chat.mjs` 通过：四名真实账号的 A/B 语音房间隔离、仅麦克风授权、伪造队伍参数不改变授权、2v2 终局与双方历史一致。
- 设置 `UNO_E2E_ORIGIN=http://127.0.0.1:63088`、`UNO_E2E_API_ORIGIN=http://127.0.0.1:63080`、`UNO_E2E_MAILPIT=http://127.0.0.1:63025` 后，`npm --prefix web run test:e2e:voice` 和 `UNO_E2E_VOICE_REVOKE=1 npm --prefix web run test:e2e:voice-revoke` 均通过。同队浏览器双向收到虚拟麦克风 PCM 样本，对手房间无音轨；静音/退出/终局释放音轨，终局旧令牌重建的房间再次清理。房外账号无法获取牌局或语音权限；会话撤销后有效队友迁移到新代次，旧 JWT 无法听到有效队友。
- Android API 36.1 模拟器与三名包内 Web 玩家完成四人 2v2，Web/App 房间及队伍文字双向互发、队伍隔离、双方 UI 出牌至终局、战绩一致；Web 与 Android 互相订阅到对方音轨，对局 `47ce6527-dde8-4b1a-aa1d-175d0276ffe4`。测试临时使用 `adb reverse` 映射 Gateway/Mailpit `63080/63025` 及媒体 TCP `7890/7891`，结束后已撤销。
- iPhone 17 Pro / iOS 26.5 模拟器同样完成四人 2v2、双向文字、双方 UI 出牌、终局与战绩；iOS 客户端仅收听模式加入并退出 LiveKit，未申请麦克风，对局 `c4596630-2426-40fd-8a6a-422bcc556eef`。测试脚本没有核对 iOS 收到的远端音频样本。

浏览器测试使用虚拟音频设备；移动端使用 Flutter 集成测试构建，产品代码与包一致。以上不证明真实扬声器听感、真机麦克风、跨网 ICE/TURN、生产 TLS/WSS 或容量。独立语音栈没有覆盖服务重启时的媒体恢复。

## 源码修复后的 iOS 远端音轨补验

上述 0.3.13 镜像栈保持原样运行；以下 Flutter App 使用当前源码重新构建，**修复未进入 0.3.13 发布包，已进入 [0.3.14 本地试玩包](verification-stage17-local-playtest-0.3.14.md)**。新增 Web 虚拟麦克风向 iOS 仅收听端持续发布音轨的测试。修复前，iOS 订阅后直接离会的完整流程和离会后空等 30 秒的最小流程多次发生 `SIGABRT`；系统崩溃报告的故障线程为 WebRTC worker，栈顶经过 `_ReportRPCTimeout`、`AURemoteIO::Initialize`、`AudioUnitInitialize`。先让 Web 停止发布并等待 iOS 收到退订事件，再让 iOS 离会的对照流程通过。该实验将风险缩到远端音频仍活跃时的清理顺序，不能单独证明系统音频超时的全部成因。

App 离会时现先调用 LiveKit 的远端音轨 `unsubscribe()`，等音轨释放后再 `disconnect()` / `dispose()`。语音面板显示“正在退出语音”，直到异步清理完成才开放再次加入。修复后原场景的离会后 30 秒回归通过一次；包含 Web/App 双向文字、四人 2v2 双方 UI 出牌、终局和战绩一致的 iOS 远端音轨流程连续通过两次，对局 `3460fbbf-ad4e-445a-9fa8-7240e6a096d2`、`d7ed9f2a-f29c-4df8-af05-8f1b2b93c041`。这验证了 iOS 收到 LiveKit 远端音轨订阅事件及离会清理，仍未测量 iOS 扬声器输出的 PCM 样本或真机听感。

同一源码的 Android API 36 模拟器回归亦通过：Web/Android 双向订阅音轨、双向房间及队伍文字、四人 2v2 双方出牌、终局与战绩一致，对局 `4958bf7f-c4de-4ed7-8805-e823bf201af1`。临时 `adb reverse` 映射已撤销。

复现时设置本文件上方三个 `UNO_E2E_*` 服务地址及 `UNO_E2E_DEVICE_ID` 为启动的模拟器 ID；iOS 分别运行 `npm --prefix web run test:e2e:ios-voice-remote`、`npm --prefix web run test:e2e:ios-voice-cleanup`，Android 映射宿主机 `63080/63025/7890/7891` 端口后运行 `npm --prefix web run test:e2e:android-team-media`。本机 `xcode-select -p` 指向 Command Line Tools，Xcode 构建子进程没有保留外层的 `DEVELOPER_DIR`；iOS 测试时用临时 `PATH` 中的 `xcrun` 包装脚本为未设置该变量的进程指向 `/Applications/Xcode.app/Contents/Developer`，没有更改全局 Xcode 选择。
