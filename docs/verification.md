# 骨架验收记录

日期：2026-09-06。验证的是开发骨架，不是完整游戏。

本文件保留早期骨架验收快照；数据库后续变更与新验证见 [stage2 验收记录](verification-stage2.md)。下文“PostgreSQL 尚未接入”和原生无数据库启动方式仅描述当时版本，不适用于当前源码。

后续仓库操作记录：同日已创建并核实 GitHub 私有仓库 `zhuyinghua6961/UNO`，关联本地 origin，未提交/推送。以下骨架验收和发布包记录保留当时状态；原发布包的 sourceCommit=null 仍然准确，三个分支安排仍待确认。

## 已通过

| 检查 | 结果 |
| --- | --- |
| Maven verify | 4 个子模块通过构建，3 个可执行 JAR 生成 |
| 后端单元测试 | 10 个：文字受众、队友语音范围、模式/成员/代次、消息内容约束 |
| Vue 生产构建 | TypeScript 检查与 Vite build 通过 |
| Vue 交互测试 | 3 个通过：模式切换、经典模式不开放队伍、语音禁用占位 |
| Flutter widget tests | 3 个通过：大厅/牌桌入口、队友语音禁用、经典模式无队伍频道 |
| Dart 静态分析 | 在 flutter/ 内执行 `dart analyze --format=machine`，退出码 0 |
| Dart 格式 | `dart format lib test` 通过 |
| Java 实际启动 | 本机 Java 17 启动账号、对局、网关进程，三个进程均可运行 |
| 网关联通与拒绝策略 | `tools/smoke-api.mjs` 通过：bootstrap/auth 状态真实返回；受保护路由拒绝未登录访问 |
| Web 浏览器预览 | 大厅→2v2→牌桌，后端状态连接正常；图片无缺失，桌面没有水平溢出 |
| Compose 配置 | 主栈服务配置解析通过 |
| Nginx 配置 | 使用本机缓存的 nginx:1.27-alpine 做配置语法验证通过；不是目标 1.28 镜像完整启动验证 |
| 发布包 | release/0.1.0-scaffold.tar.gz，约 91 MiB，内部文件 SHA-256 校验通过 |

后端与 Web 的第一次依赖获取受本机网络代理设置影响；使用本机已有代理进行本次构建后成功。没有将个人代理设置写入项目配置，也没有改动全局网络设置。

## 未通过或未进行

- 完整 Docker 镜像构建已尝试，但 Docker daemon 获取 auth.docker.io 镜像认证令牌时 DNS 超时；尚未完成 Compose 容器联调。不是已验证可一键上线的容器制品。
- `flutter analyze` 包装命令在本机 SDK 的 LSP 初始化时报 FormatException；独立 `dart analyze --format=machine` 正常退出。没有修改 Flutter SDK；需要升级/排查 SDK 时另行处理。
- 没有构建 Android APK/AAB 或 iOS IPA，没有进行真机、签名和商店发布验证。
- 尚未实现/验证真实账号登录、对局规则与消息传输、语音 SDK、令牌签发和撤销、跨网 WebRTC、TLS/TURN。
- PostgreSQL/Redis/LiveKit profile 仅提供配置，没有宣称完成持久化或实时音频连通验证。
- 没有绑定 Git 大仓库或三个分支，等待远程地址与映射。

## 重复验证

在根目录执行 `node tools/sync-assets.mjs` 后：

```sh
mvn -f backend/pom.xml verify
npm --prefix web ci
npm --prefix web run build
npm --prefix web test
cd flutter
flutter pub get
dart analyze --format=machine
flutter test
```

启动后端后，在根目录执行 `API_BASE_URL=http://localhost:<网关端口> node tools/smoke-api.mjs`。默认 Docker 网关端口 28080，本次原生联调为了避开其他项目使用了 29080。

骨架测试总计 16 个，不应被解释为 UNO 完整规则、安全和联机体验的验收覆盖。
