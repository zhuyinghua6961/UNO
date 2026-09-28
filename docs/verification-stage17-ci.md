# stage17 GitHub Actions 验证流水线

日期：2026-09-28。仓库现有 `origin` 指向 `zhuyinghua6961/UNO`，本地只有 `master`。用户确认采用 `master` 加功能分支，CI 覆盖所有分支。[工作流](../.github/workflows/ci.yml) 因此对任意分支的 push/PR 执行验证，另提供手动触发入口；没有创建或推送分支。

| Job | 触发 | 检查 |
| --- | --- | --- |
| Web | push、PR、手动 | 从受控素材生成资源，锁文件 `npm ci`，94 项单元测试与生产构建 |
| Backend | push、PR、手动 | Java 17、Maven `database-it clean verify`，包括 Testcontainers 的真实服务跨库集成 |
| Android | push、PR、手动 | Flutter 3.47.0、Dart 静态分析、widget 测试、未签名 Android debug APK 构建 |
| iOS Simulator | 仅手动 | macOS 26、Xcode、Flutter 3.47.0，未签名模拟器 App 构建 |

工作流仅有 `contents: read` 权限，不读取发布/签名秘密，不上传制品，也不执行发布。外部 Actions 使用完整提交 SHA 固定版本；版本选择参考 [GitHub Actions 官方安全建议](https://docs.github.com/en/code-security/tutorials/secure-your-organization/protect-against-threats)、[setup-node](https://github.com/actions/setup-node)、[setup-java](https://github.com/actions/setup-java)、[macOS 26 Runner 清单](https://github.com/actions/runner-images/blob/main/images/macos/macos-26-Readme.md)与 [Flutter Action](https://github.com/subosito/flutter-action)。Runner 缓存只加快下载，不取代锁文件或构建检查。

本地使用 `go run github.com/rhysd/actionlint/cmd/actionlint@v1.7.11 .github/workflows/ci.yml` 完成工作流语法和表达式检查，退出码 0。同日 [0.3.13 包](verification-stage17-local-playtest-0.3.13.md)已在本机运行相同的 Web、Maven、Flutter/Android 构建命令，并完成 iOS 模拟器构建。**尚未向远程推送，GitHub 托管 Runner 的依赖下载、Docker/Testcontainers、Android SDK 和 iOS 构建均未实跑**。首次远程 CI 通过前，不将该配置视为已验收的发布门槛；远程历史核对和 CI 实跑后再设置分支保护与制品发布策略。
