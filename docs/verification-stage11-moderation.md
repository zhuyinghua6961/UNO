# stage11 内容治理增量验收（2026-09-24）

## 本次交付

- V14 增加举报与限时禁言表。举报仅对当前成员有权读取的消息开放，服务端保存举报原因和受限文字快照；重复请求返回原记录，每人每天上限 20 份。
- HTTP 与 WebSocket 新消息共用禁言校验。运营 SQL 可查看、处理举报、禁言 24 小时、解禁，并在身份库禁用账号与撤销会话。玩家端 Web/Flutter 均可选原因并提交举报。
- 消息和举报保留 30 天；禁言记录在到期 30 天后清理。完整人工操作和限制见 [运营流程](chat-moderation.md)。

## 已执行检查

- `mvn -f backend/pom.xml -Pdatabase-it -pl game-service -am verify -q` 通过；`ChatServiceIT` 覆盖跨队越权举报、重复举报、禁言、原消息幂等确认及清理；`ChatWebSocketIT` 覆盖实际 HTTP 举报授权；迁移重启测试核对 V14。
- `npm --prefix web run build` 与 `npm --prefix web test` 通过：15 个文件、90 项测试，包含举报表单、CSRF 与返回值检查。
- `cd flutter && dart analyze --format=machine && flutter test` 通过：32 项测试，包含 App 举报原因选择与提交状态。
- 本机 Compose 使用独立回环端口 `18088/18080/18025/11025`，执行 `API_BASE_URL=http://127.0.0.1:18080 MAILPIT_BASE_URL=http://127.0.0.1:18025 SMOKE_CHAT_WS=true SMOKE_CHAT_MODERATION=true node tools/smoke-auth-chat.mjs` 通过。随机测试账号完成举报、重复去重、运营禁言/处理/解禁、禁用账号后身份与游戏接口均拒绝旧会话。

## 剩余范围

运营者尚不能立即撤回所有客户端已经显示的违规消息；当前只有到期清理、禁言和账号停用。正式申诉、值班和隐私告知需在发布前确定。身份禁用后的既有 LiveKit 音频连接不能仅凭本测试认定已撤销，须另做媒体验收。
