# 0.3.11 本地试玩候选增量矩阵

日期：2026-09-28。延续 [0.3.10 候选矩阵](verification-stage18-0.3.10-candidate.md)。本机 Web 与后端可完成经典和四人 2v2；包内 iOS 模拟器 App 可实际登录、组房和出牌。**完整真机发布版本尚未完成。**

| 项目 | 本版证据 | 仍缺 |
| --- | --- | --- |
| 可复现交付 | 干净提交 `afa7c53`；234 个包内文件、四镜像的校验和与导入；独立空库解包栈全 healthy | 目标架构、镜像仓库 digest、正式签名、生产密钥与 TLS/TURN |
| 本机 Web/服务 | 0.3.11 独立栈真实账号、双向文字、经典与四人 2v2 整局、双方战绩及队伍文字隔离通过 | 生产容量、跨网媒体、完整安全回归和环境回滚 |
| iOS 模拟器 | 包内普通 App 登录、开局、服务器确认出牌；音效静音在牌桌间保留，无 Keychain 告警 | 包内普通 App 整局、签名真机、Keychain 持久会话、真机音轨与弱网 |
| Android | 同版 debug APK 构建通过；[0.3.9 包内普通 APK](verification-stage17-local-playtest-0.3.9.md)已有界面操作及战绩证据 | 本版普通 APK 全局界面验收、真机与签名包 |

见 [0.3.11 包、镜像和 iOS 普通 App 验收](verification-stage17-local-playtest-0.3.11.md)。目前没有 Apple Developer 团队和目标 iPhone；真实设备门槛保持未通过，stage18 不标完成。
