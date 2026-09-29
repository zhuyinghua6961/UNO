# 0.3.18 本地试玩候选增量矩阵

日期：2026-09-29。延续 [0.3.17 候选矩阵](verification-stage18-0.3.17-candidate.md)，逐项证据见 [0.3.18 包验收](verification-stage17-local-playtest-0.3.18.md)。本机 Web 与模拟器可试玩；正式可分发版本尚未完成。

| 项目 | 本版证据 | 仍缺 |
| --- | --- | --- |
| 来源与交付 | 干净提交 `1ee82bb`；Web/JAR/Android debug APK/iOS Simulator App、247 个文件与四个同版离线镜像校验；独立解包空库栈全部健康 | 正式签名移动包、目标环境、镜像仓库 digest |
| 账号与玩法 | 同版栈真实账号经典与 2v2 整局、文字隔离及双方战绩通过；Android 同源码集成测试 App 在同版栈完成经典整局结算和再开一局；包内普通 APK 完成注册/验证/登录、建房与开局，并显示 API 客户端推进的同局结算战绩；包内普通 iOS 模拟器 App 用同一账号登录并显示相同战绩与当前房间 | 0.3.18 包内普通 Android/iOS App 双方界面全程操作整局、真机安装与升级 |
| 语音与重连 | 同版栈四人队伍语音授权隔离、浏览器断网恢复与 Android 集成测试 App 前后台恢复通过 | 真机听感、跨网 ICE/TURN、媒体重连及容量 |
| CI 与发布 | 功能分支及 `master` 的自动 push CI 均有 Web、后端、整栈、Android 四项通过记录；远端 iOS Simulator 手动运行通过；本机可复现容量基线；见[远端 CI 记录](verification-stage17-ci.md) | 正式并发/延迟目标、生产网络、发布授权；用户暂无 Apple Developer 团队和目标 iPhone |

Android 集成测试使用与包相同的源码，但不是包内普通启动 APK；普通 APK 中间的出牌由 API 客户端推进，不能把它记为普通安装包双方界面全程完成整局。普通 iOS 模拟器包的同账号战绩读取也不能替代该包的完整牌局。iOS Debug 模拟器包使用临时进程会话，不能替代真机签名和 Keychain 验收。stage18 继续进行。
