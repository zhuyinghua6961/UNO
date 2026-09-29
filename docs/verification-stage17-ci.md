# stage17 GitHub Actions 验证流水线

日期：2026-09-28，2026-09-29 补记。仓库 `origin` 指向私有仓库 `zhuyinghua6961/UNO`。用户确认采用 `master` 加功能分支，CI 覆盖所有分支。[工作流](../.github/workflows/ci.yml) 因此对任意分支的 push/PR 执行验证，另提供手动触发入口。已创建并首次推送 `master` 与 `codex/match-context-at-controls`，将远端默认分支设为 `master`。

| Job | 触发 | 检查 |
| --- | --- | --- |
| Web | push、PR、手动 | 从受控素材生成资源，锁文件 `npm ci`，94 项单元测试与生产构建 |
| Backend | push、PR、手动 | Java 17、Maven `database-it clean verify`，包括 Testcontainers 的真实服务跨库集成 |
| Stack smoke | push、PR、手动 | 随机本地密钥启动账号版 Compose；真实注册、经典与 2v2 整局、文字和战绩；Playwright 浏览器断网恢复 |
| Android | push、PR、手动 | Flutter 3.47.0、Dart 静态分析、widget 测试、未签名 Android debug APK 构建 |
| iOS Simulator | 仅手动 | macOS 26、Xcode、Flutter 3.47.0，未签名模拟器 App 构建 |

工作流仅有 `contents: read` 权限，不读取发布/签名秘密，不上传制品，也不执行发布。Stack smoke 的 `.env` 由 Runner 上的脚本随机生成，Compose 对外仅绑定 loopback，结束后停止本 Job 自己的栈。外部 Actions 使用完整提交 SHA 固定版本；版本选择参考 [GitHub Actions 官方安全建议](https://docs.github.com/en/code-security/tutorials/secure-your-organization/protect-against-threats)、[setup-node](https://github.com/actions/setup-node)、[setup-java](https://github.com/actions/setup-java)、[macOS 26 Runner 清单](https://github.com/actions/runner-images/blob/main/images/macos/macos-26-Readme.md)与 [Flutter Action](https://github.com/subosito/flutter-action)。Runner 缓存只加快下载，不取代锁文件或构建检查。

本地使用 `go run github.com/rhysd/actionlint/cmd/actionlint@v1.7.11 .github/workflows/ci.yml` 完成工作流语法和表达式检查，退出码 0。[0.3.17 包](verification-stage17-local-playtest-0.3.17.md)已在本机运行相同的 Web、Maven、Flutter/Android 构建命令，并完成 iOS 模拟器构建；包内独立栈完成经典/2v2 整局。新增 Stack smoke 的全新 Runner Docker 构建尚未实跑。首次推送后，GitHub API 可从 `master` 读取工作流文件，但 Actions 工作流列表和运行列表暂时均为 0；将再由默认分支的新提交触发并核对。**GitHub 托管 Runner 的依赖下载、Docker/Testcontainers、Playwright、Android SDK 和 iOS 构建尚未实跑**。首次远端 CI 通过前，不将该配置视为已验收的发布门槛；远程历史核对和 CI 实跑后再设置分支保护与制品发布策略。
