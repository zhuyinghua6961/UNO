# 发布包目录

本目录用于存放构建产物，不提交二进制包到 Git 主历史。未来 CI 将相同产物上传到大仓库的 Release / 制品库；这里仍保留本地归档。若必须跟踪二进制，先确认 Git LFS 策略。

在干净的已提交工作区，从根目录执行：

```sh
node tools/package-release.mjs 0.2.0-local-preview
```

脚本先运行 Web 测试与构建、Maven `verify`，再从这次构建生成 Web 静态文件、三个后端 JAR、部署配置参考、manifest、SHA-256 和 tar.gz。工作区脏、构建中提交变化、同名目录或归档已存在时拒绝打包，避免把旧制品标为当前提交。数据库集成测试、浏览器/设备端到端验收仍需另行执行。

`0.1.0-scaffold` 是历史骨架包。新脚本产物是**本地预览包，不是完成的游戏或生产安装包**。不包括 APK/IPA、Docker 镜像、账号密钥、用户数据。Docker 配置需要在原源码仓库构建，不能仅凭这个包离线重建镜像。manifest 记录实际提交号及此次脚本执行的检查。

移动端后续归档约定：`<version>/flutter/android/` 存 APK/AAB，`<version>/flutter/ios/` 存经用户签名的 IPA。Android debug 包和 release 包必须区分；签名密钥永远不放本目录。发布版本号以 manifest 为准，现阶段 JAR 内部仍为 0.1.0-SNAPSHOT。
