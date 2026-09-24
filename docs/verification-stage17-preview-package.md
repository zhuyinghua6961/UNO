# stage17 本地预览包验收

日期：2026-09-24。源码提交：`3547f896c514e719b0e6c8d602264370e7f0a6c6`。

在干净工作区执行 `node tools/package-release.mjs 0.2.0-local-preview`，脚本从该提交运行 Web 测试（13 个文件、83 项通过）、Web 类型检查和 Vite 构建、Maven `clean verify`（全部模块成功），随后归档本次生成的 Web 静态文件、Gateway/Identity/Game JAR 和部署配置参考。构建时也清理了 Maven 旧编译目录。脚本在工作区未提交时按预期拒绝打包。

产物位于 `release/0.2.0-local-preview.tar.gz`（约 129 MiB；该目录按 Git 忽略规则留在本机），对应 `release/0.2.0-local-preview/manifest.json`。在 `release` 目录执行 `shasum -a 256 -c 0.2.0-local-preview.tar.gz.sha256`，归档校验通过；在解包目录执行 `shasum -a 256 -c SHA256SUMS`，包内文件校验通过。manifest 的 `sourceCommit` 与当时 HEAD 相同，`sourceDirty=false`，列明实际执行的验证命令。

这是本地预览包，不含 APK/IPA、Docker 镜像或秘密，不能作为完整可玩版本的发布验收。Maven 默认 `verify` 不运行 PostgreSQL 集成测试；此前跨端模拟器和容器端到端验收分别记录在对应阶段文档，不能由本包的测试结果替代。正式交付仍需移动端签名与真机、真实音频互听、弱网、跨网 TLS/TURN、数据库备份恢复及发布配置验收。
