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

本地使用 `go run github.com/rhysd/actionlint/cmd/actionlint@v1.7.11 .github/workflows/ci.yml` 完成工作流语法和表达式检查，退出码 0。最初在 push/PR 上写显式 `branches: ['**']` 时，GitHub 已接收 push 事件并注册工作流，但没有产生自动运行。改成不带过滤条件的 `push:` / `pull_request:` 后，功能分支与 `master` 的 push 均实际产生了运行；这个结论来自仓库运行记录，不把仅有的静态配置当作触发证据。

| 运行 | 源码 | 结果与发现 |
| --- | --- | --- |
| [首次 master 手动运行](https://github.com/zhuyinghua6961/UNO/actions/runs/36525295859) | `332e573` | Web、整栈、Android、iOS Simulator 通过；后端两处 `Instant` 纳秒与 PostgreSQL 微秒比较失败。修复在 `0428b24` 返回实际存储时间。 |
| [master 第二次手动运行](https://github.com/zhuyinghua6961/UNO/actions/runs/36526085843) | `0428b24` | 后端、Web、Android、iOS Simulator 通过；整栈浏览器测试在准备按钮等待超时。 |
| [功能分支手动运行](https://github.com/zhuyinghua6961/UNO/actions/runs/36526211136) | `0428b24` | 五项全通过。 |
| [房间入口功能分支自动 push](https://github.com/zhuyinghua6961/UNO/actions/runs/36527184825) | `1ee82bb` | Web、后端、整栈、Android 四项通过；iOS 按工作流设计跳过。 |
| [同提交功能分支手动运行](https://github.com/zhuyinghua6961/UNO/actions/runs/36527868812) | `1ee82bb` | 五项全通过，包含远端 macOS 26/iOS Simulator 构建。 |
| [master 自动 push](https://github.com/zhuyinghua6961/UNO/actions/runs/36527844503) | `1ee82bb` | Web、后端、Android 通过；整栈中的浏览器客方在房主已准备但尚未刷新到客方界面时点击准备，等待超时。`c1094af` 使测试等客方看到房主状态后再操作。 |
| [浏览器同步功能分支自动 push](https://github.com/zhuyinghua6961/UNO/actions/runs/36528793370) | `c1094af` | Web、后端、整栈、Android 四项通过；iOS 按 push 工作流设计跳过。 |
| [master 自动 push 复验](https://github.com/zhuyinghua6961/UNO/actions/runs/36529535222) | `c1094af` | Web、后端、整栈、Android 四项通过；iOS 按 push 工作流设计跳过。 |
| [0.3.18 验收文档 master 自动 push](https://github.com/zhuyinghua6961/UNO/actions/runs/36531009035) | `dc543b4` | Web、后端、整栈、Android 四项通过；iOS 按 push 工作流设计跳过。 |

远端 Runner 的依赖下载、Docker/Testcontainers、Playwright、Android SDK 与 iOS 构建已实际跑通。`master` 和功能分支在修正后均有自动 push 全绿记录；同一产品源码 `1ee82bb` 的功能分支手动运行也覆盖远端 iOS Simulator。此工作流尚不生成可发布制品，也未完成依赖/秘密扫描、分支保护、镜像仓库和正式签名。
