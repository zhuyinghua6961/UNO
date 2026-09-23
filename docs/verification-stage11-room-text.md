# stage11 房间文字增量验收

日期：2026-09-23。范围：房间公共文字频道；队伍频道、WebSocket 消息事件和公开试用所需的内容处理入口尚未完成。

## 实现

- `V5__room_text.sql` 增加独立频道序号和消息表。客户端只提交 UUID 消息 ID 与纯文本；身份、昵称、频道、序号和时间由服务器取得或分配。同一身份/房间/消息 ID 的重试不重复落库；换正文返回 409。
- 当前成员可发送和按游标读取房间文字。退出后立即失去查询与发送权限；重新加入后只可查看新成员期内的消息。内容限制为 1–500 个 Unicode 码点，拒绝控制字符；每人每秒最多两条新消息。默认保存 30 天并由定时任务清理；此保留期与举报/删除流程仍待产品确认。
- Web 与 Flutter 在等待室和牌桌展示消息，按两秒周期补取新消息。发送失败保留原消息 ID 供用户重试；界面以普通文字渲染 `<b>` 等内容，不执行 HTML。牌局动作与聊天请求互不阻塞。

## 验证

- `mvn -f backend/pom.xml -Pdatabase-it verify -Duno.postgres.image=postgres:16-alpine -q` 通过：PostgreSQL 迁移/服务测试和跨服务测试。跨服务用独立的 Web Cookie 与 App Bearer 账号经 Gateway 互发、读取相同序号与内容；检查伪造发送者字段、无 CSRF 写入、非成员读取、重复消息与换正文、退出后读取。
- `npm test -- --run` 71 项通过；`npm run build` 通过。Web API 测试检查 CSRF、客户端消息 ID、游标及畸形响应。
- `flutter test --no-pub`（17 项）与 `dart analyze --format=machine lib test` 通过；原生 API 测试检查 Bearer 与消息格式。`flutter build apk --debug --no-pub` 和 iOS 模拟器构建通过。Android/iOS 新文字界面尚未在设备上实测。

## 待做

- 在 Web 与 Android/iOS 设备界面实际互发并检查长消息、滚动、前后台、断线重试与未读提示。
- 2v2 队伍频道的发送与历史隔离、WebSocket 事件、跨实例传播、举报/禁言/删除流程和最终保存策略。未完成前不按公开聊天服务验收。
