# 0.3.5 本地试玩包综合验收矩阵

日期：2026-09-28。归档来自提交 `dea3750b04494e7c0546f21b490daf8791759ad6`。当前状态：**可供本机模拟器试玩；尚未达到目标真机可分发的完整版本**。混合端整局与音轨检查使用相同源码的测试构建和本机 Compose 服务，包内普通启动 App 只验证了安装与首页。

| 功能或交付项 | Web | Android | iOS | 当前证据与缺口 |
| --- | --- | --- | --- | --- |
| 账号、建房、入房 | 本机通过 | API 36.1 模拟器测试构建通过 | iOS 26.5 模拟器测试构建通过 | [混合端复测](verification-stage15-voice.md)；0.3.5 普通启动包未逐项验收 |
| 经典局、结算、再开局 | 本机通过 | 较早模拟器联调通过 | 较早模拟器联调通过 | [Web 生命周期](verification-stage12-lifecycle.md)、[Android 经典局](verification-stage9-flutter.md)、[iOS 经典局](verification-stage9-ios.md)；0.3.5 包内 App 未整局 |
| 四人 2v2、结算、战绩 | 本机通过 | 当前源码模拟器通过 | 当前源码模拟器通过 | [混合端复测](verification-stage15-voice.md)；真机与同版部署待做 |
| 房间文字、队伍文字隔离 | 本机通过 | 当前源码模拟器通过 | 当前源码模拟器通过 | [混合端复测](verification-stage15-voice.md)；跨网弱网待做 |
| 队友语音 | 浏览器虚拟麦克风 PCM 样本双向到达 | 与 Web 双向音轨订阅通过 | 仅收听信令通过 | [Web 样本](verification-stage13-14-voice.md)、[Android/Web 音轨与 iOS 信令](verification-stage15-voice.md)；真机实际听感、iOS 音轨与非队友媒体隔离待验 |
| 重连与故障恢复 | 双 Game 容器接管通过 | 传输层自动测试 | 传输层自动测试 | [容器故障恢复](verification-stage17-multi-instance.md)；真机网络切换、生产网关和容量待做 |
| 包与部署 | Web 静态文件及三个 JAR 归档 | 本版 debug APK 安装启动 | 本版 Simulator App 安装启动；IPA 缺失 | [0.3.5 包验收](verification-stage17-local-playtest-0.3.5.md)；正式签名、同版镜像、HTTPS/WSS/TURN、回滚和发布仍缺 |

安全回归已有真实账号授权、跨队文字隔离、短期媒体令牌与会话撤销等分项测试；冒充用户、偷看手牌、令牌重放、敏感日志和跨端组合仍需按 stage18 在目标部署复验。并发与延迟目标尚未确认，不能声明容量。当前没有正式签名凭证、目标域名/网络和真机验收记录，因此本包不能作为已通过发布门槛的版本。
