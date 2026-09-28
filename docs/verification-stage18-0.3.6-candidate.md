# 0.3.6 本地试玩包综合验收矩阵

日期：2026-09-28。源码与本地包为 `61c3d2e3cd64f27fd220c93ca6d2906d43bb3898`。当前状态：**本机可试玩，尚未达到目标真机可分发的完整版本**。端到端联调用相同业务源码的测试构建和隔离 Compose 栈；包内普通启动移动 App 只验收到安装和首页。

| 功能或交付项 | Web | Android | iOS | 证据与缺口 |
| --- | --- | --- | --- | --- |
| 账号、组房 | 本机通过 | 模拟器测试构建通过 | 模拟器测试构建通过 | [混合端整局](verification-stage18-security-v17.md)；包内 App 未逐项验收 |
| 经典局、结算、再开局 | 本机通过 | 较早模拟器联调通过 | 较早模拟器联调通过 | [Android 经典局](verification-stage9-flutter.md)、[iOS 经典局](verification-stage9-ios.md)；0.3.6 包内 App 未整局 |
| 四人 2v2、结算、战绩 | 本机通过 | 当前源码模拟器通过 | 当前源码模拟器通过 | [V17 后混合端复测](verification-stage18-security-v17.md)；真机、同版部署待做 |
| 房间与队伍文字 | 本机通过 | 当前源码模拟器通过 | 当前源码模拟器通过 | [V17 后混合端复测](verification-stage18-security-v17.md)；跨网弱网待做 |
| 队友语音 | 浏览器虚拟麦克风 PCM 双向到达，旧令牌隔离 | 与 Web 双向音轨订阅通过 | 仅收听信令通过 | [stage15](verification-stage15-voice.md)、[V17 安全补验](verification-stage18-security-v17.md)；真机实际听感、iOS 音轨和跨网 ICE/TURN 待验 |
| 身份与隐私安全 | 房外账号读取/伪造出牌/语音准入均被拒绝，对手手牌 ID 隔离 | 后端共用准入；本机混合端文字隔离 | 后端共用准入；本机混合端文字隔离 | [V17 安全补验](verification-stage18-security-v17.md)；生产环境、敏感日志、跨实例高并发仍需复验 |
| 重连与撤销 | 双 Game 容器接管、语音撤销换代通过 | 传输层自动测试 | 传输层自动测试 | [双实例](verification-stage17-multi-instance.md)、[V17 语音撤销](verification-stage18-security-v17.md)；真机网络切换与容量待做 |
| 包与部署 | Web 静态文件、三个 JAR 归档 | 本版 debug APK 安装启动 | 本版 Simulator App 安装启动；IPA 缺失 | [0.3.6 包验收](verification-stage17-local-playtest-0.3.6.md)；正式签名、同版镜像、HTTPS/WSS/TURN、回滚待做 |

已知风险：语音会话撤销由轮询触发，有数秒窗口；LiveKit 旧 JWT 在过期前可能重建旧房间，清理任务会重复删除旧代次。Android 模拟器未证明麦克风实际音频，iOS 模拟器音轨试验仍有系统崩溃记录，不能宣称真机互听。并发与延迟目标、目标域名/网络、正式签名凭证和真机验收记录尚未确定。当前不满足 stage18 发布门槛。
