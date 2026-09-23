# stage4 Flutter 账号增量验收

日期：2026-09-23。范围：本地 Flutter 账号实现与自动测试；不替代 Android/iOS 设备和真实跨端联调。

## 已验证

- `flutter pub add http:^1.6.0 flutter_secure_storage:^11.2.0`：依赖解析成功，版本记录在 `flutter/pubspec.lock`。
- `dart analyze --format=machine`：无诊断。
- `flutter test`：11项通过，含原生请求头与登录/退出协议、重启恢复、凭证轮换、并发401只刷新一次、撤销/服务失败、错误密码、安全存储删除失败和账号页交互。
- `git diff --check`：通过。
- `flutter build apk --debug --dart-define=API_BASE_URL=http://10.0.2.2:29080`：构建成功；APK在`flutter/build/app/outputs/flutter-apk/app-debug.apk`，未归档为发布包。
- `flutter test integration_test/secure_storage_test.dart -d emulator-5554`：Android API 36.1模拟器上1项通过；原生插件实际写入测试专用Key，使用新实例读取并删除。此项未验证杀进程/重启后的持久化。
- iOS模拟器构建：`flutter build ios --simulator --dart-define=API_BASE_URL=https://example.invalid`通过，产物为`flutter/build/ios/iphonesimulator/Runner.app`。本机全局`xcode-select`指向CommandLineTools；构建时在临时PATH中放置仅设置`DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`的`xcrun`包装脚本，未更改全局选择。直接构建设备包仍要求开发团队/Provisioning Profile，未执行签名或设备安装。

本机iOS模拟器构建的临时工具选择（仅当前命令，不修改全局Xcode设置）：

```sh
mkdir -p /tmp/uno-xcode-tools
cat > /tmp/uno-xcode-tools/xcrun <<'SH'
#!/bin/sh
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
exec /usr/bin/xcrun "$@"
SH
chmod +x /tmp/uno-xcode-tools/xcrun
PATH=/tmp/uno-xcode-tools:$PATH DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer flutter build ios --simulator --dart-define=API_BASE_URL=https://example.invalid
```

## 行为与边界

App请求带`X-UNO-Client: APP`，不发送Cookie或Origin；需要身份时发送Bearer。访问/刷新凭证以一条记录写入系统安全存储，不写入日志、普通首选项或Web Storage。访问凭证将过期时串行刷新；并发401复用轮换结果。刷新401清除本地会话，网络/服务失败保留凭证并显示不可用。退出收到服务端成功或401后才清除本地凭证。

Android主manifest关闭应用备份；HTTP只在debug manifest许可。iOS仅放行本地网络；实机应使用可达的HTTPS网关。账号功能仍由后端`AUTH_ENABLED`控制，默认关闭。

## 待验收

- 本机`127.0.0.1:29080`无网关服务；Docker启动后所需PostgreSQL镜像拉取未完成：无法进行Flutter→Gateway→identity→PostgreSQL/Mailpit的真实注册和邮件流程，也无法比较Web/App同一账号的userId。
- 没有iOS设备或真机；Android杀进程/重启持久化、真实账号退出清除与iOS Keychain运行时行为仍待实际验证。iOS设备签名和安装未执行。
- 需在设备和真实服务可用时执行：同一测试账号分别在Web/App登录并比对`/api/users/me.id`；重启App、令牌过期轮换、密码重置撤销、退出后拒绝访问；验证注册/验证/找回邮件、错误密码、断网和重复提交。

版本或提交：本轮未提交。
