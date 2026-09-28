# 0.3.3 本地试玩包综合验收矩阵

日期：2026-09-28。当前状态：**未达到可分发的完整版本**。归档来自提交 `096a32ec8a4ab88f64047f171668666451e07afb`；业务联调和媒体检查还使用本机 Compose 服务，不能代替同一归档版本在目标环境的端到端验收。

| 功能或交付项 | Web | Android | iOS | 当前证据与缺口 |
| --- | --- | --- | --- | --- |
| 账号、建房、入房 | 本机通过 | API 36.1 模拟器通过 | iOS 26.5 模拟器当前源码通过 | [Android/iOS 混合端复测](verification-stage15-voice.md)、[iOS 账号/经典局](verification-stage9-ios.md)；归档 APK 未逐项验收 |
| 经典局、结算、再开局 | 本机通过 | 较早模拟器联调通过 | 较早模拟器联调通过 | [Web 生命周期](verification-stage12-lifecycle.md)、[Android 经典局](verification-stage9-flutter.md)、[iOS 经典局](verification-stage9-ios.md)；0.3.3 归档包未完成整局 |
| 四人 2v2、结算、战绩 | 本机通过 | 当前源码模拟器通过 | 当前源码模拟器通过 | [Android/iOS 混合端复测](verification-stage15-voice.md)；真机与同版部署待做 |
| 房间文字、队伍文字隔离 | 本机通过 | 当前源码模拟器通过 | 当前源码模拟器通过 | [Android/iOS 混合端复测](verification-stage15-voice.md)；跨网弱网待做 |
| 队友语音 | 虚拟麦克风样本双向到达 | 仅收听信令通过 | 仅收听信令通过 | [Web 双向样本](verification-stage13-14-voice.md)、[Flutter 信令](verification-stage15-voice.md)；真实设备开麦/播放/互听未验收 |
| 重连与故障恢复 | 双 Game 容器接管通过 | 传输层自动测试 | 传输层自动测试 | [容器故障恢复](verification-stage17-multi-instance.md)；真机网络切换、生产网关和容量待做 |
| 包与部署 | Web 静态文件、三个 JAR 已归档 | 模拟器 debug APK 已归档、安装启动 | IPA 缺失 | [0.3.3 包验收](verification-stage17-local-playtest-0.3.3.md)；正式签名、同版镜像、HTTPS/WSS/TURN、回滚和发布仍缺 |

安全回归已有真实账号授权、跨队文字隔离、短期媒体令牌与会话撤销等分项测试；冒充用户、偷看手牌、令牌重放、敏感日志和跨端组合仍需按 stage18 完整矩阵在目标部署重新执行。并发与延迟目标尚未确认，不能声明容量。当前没有批准的正式签名凭证、目标域名/网络和真机验收记录，因此不发布或推送此归档。
