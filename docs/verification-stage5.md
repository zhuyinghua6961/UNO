# stage5 房间与组队验证记录

日期：2026-09-23。状态：部分完成，未提交。

## 已实现

- game-service 新增 `V2__waiting_rooms.sql`、房间服务及 HTTP API。房间码 10 位随机字符、24 小时有效；同一账号只占一个房间席位。每个进程对单账号限制 10 分钟内 12 次加入尝试；集中式边缘限流仍属于后续部署工作。加入与更新在数据库事务中锁定房间行；`expectedVersion` 拒绝过期准备/选队/设置请求。
- 经典局支持 2–6 人；2v2 固定四个交替座位，A/B 各两席。成员或设置变化重置准备状态，`canStart` 仅表示房间人数与准备条件满足。尚无启动对局端点。
- Web 大厅可创建/凭码加入、恢复当前房间；邀请 URL `/join/:code` 登录后返回该路径并加入；等待室轮询、准备、选队、改人数、离开。
- Flutter 大厅可创建/凭码加入并恢复当前房间；登录前输入房间码可在登录后自动加入；等待室轮询、准备、选队、改人数、离开。原生系统级邀请深链尚未配置。

## 实测

| 验证 | 结果 |
| --- | --- |
| `npm run build && npm test`（`web`） | 通过，7 个文件、58 个测试 |
| `dart analyze lib test && flutter test`（`flutter`） | 分析无问题；13 个测试通过 |
| `mvn -f backend/pom.xml -Pdatabase-it verify -Duno.postgres.image=postgres:16-alpine` | 通过；PostgreSQL 16 容器验证迁移、房间并发/权限、真实 identity + game + gateway 联调（跨服务 10 项） |
| `flutter build apk --debug` | 通过；`flutter/build/app/outputs/flutter-apk/app-debug.apk` |
| `flutter build ios --simulator --dart-define=API_BASE_URL=https://example.invalid` | 通过；`flutter/build/ios/iphonesimulator/Runner.app`，使用临时 `DEVELOPER_DIR` 指向本机 Xcode |

跨服务集成用两个真实注册/验证账号：App Bearer 创建 2v2 房间，Web Cookie/CSRF 加入，双方通过 gateway 看到同一成员列表；验证无 CSRF 写入被拒、非成员不能读取、旧版本操作冲突、非房主不能改设置、房主离开后移交。房间服务数据库测试另覆盖最后席位并发争抢、重复加入、队伍满员、邀请码失效和空房删除。

## 待验收

- 在 Android/iOS 真机及 Web 浏览器实际操作两端同房、登录回邀请、前后台恢复和弱网；当前自动测试与 HTTP 联调不能代替设备界面验收。
- 使用目标 PostgreSQL 17 重跑数据库与跨服务集成；本次本地使用缓存的 PostgreSQL 16 镜像。
- App 的系统级邀请 URL 深链需要确定发布域名与关联文件；当前可以通过房间码跨端加入。
- 2v2 的产品含义目前按四位真人组成两队实现，仍待确认。
