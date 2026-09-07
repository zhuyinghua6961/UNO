# 发布包目录

本目录用于存放构建产物，不提交二进制包到 Git 主历史。未来 CI 将相同产物上传到大仓库的 Release / 制品库；这里仍保留本地归档。若必须跟踪二进制，先确认 Git LFS 策略。

完成构建后，在根目录执行：

```sh
node tools/package-release.mjs 0.1.0-scaffold
```

脚本拒绝覆盖已有同名目录，生成 Web 静态文件、三个后端 JAR、部署配置参考、manifest、SHA-256 和 tar.gz。

当前包是开发骨架，**不是完成的游戏或生产安装包**。不包括 APK/IPA、Docker 镜像、账号密钥、用户数据。Docker 配置需要在原源码仓库构建，不能仅凭这个包离线重建镜像。没有 Git 提交时 manifest 的 sourceCommit 为 null，不会虚构版本关联。

移动端后续归档约定：`<version>/flutter/android/` 存 APK/AAB，`<version>/flutter/ios/` 存经用户签名的 IPA。Android debug 包和 release 包必须区分；签名密钥永远不放本目录。发布版本号以 manifest 为准，现阶段 JAR 内部仍为 0.1.0-SNAPSHOT。
