# stage8 Web 经典对局验收

日期：2026-09-23。范围：真实账号从创建到经典对局结算、再次组局，以及 Web 牌桌的私有视图、实时动作与窄屏显示。

## 自动测试与构建

- `npm test -- --run`：Web 对局 API、WebSocket、牌桌状态及既有模块测试通过。对局测试覆盖同源 CSRF、响应校验、先订阅后发送、断线后不自动重发、限流关闭重连、重复点击屏蔽、旧版本快照、命令拒绝后补同步及页面卸载关闭连接。
- `npm run build`：Vue 类型检查与 Vite 生产构建通过。
- `mvn -f backend/pom.xml -pl game-service -am test -Dtest=GameWebSocketIT -Dsurefire.failIfNoSpecifiedTests=false -Duno.postgres.image=postgres:16-alpine -q`：WebSocket 集成测试通过，包含每连接速率超限时 1008 / `RATE_LIMITED` 关闭原因。

## 真实浏览器闭环

在隔离的 PostgreSQL 16 + Mailpit 容器、三个本机 Java 服务和 Vite 代理上，运行 `web/e2e/classic-match.cjs`。脚本使用两个独立浏览器上下文，通过页面注册两个新账号，从 Mailpit 取得验证邮件，再登录、建房、加入、准备、启动对局。牌桌动作均由浏览器点击提交；脚本只读取 `/api/matches/{id}/state` 以核对权威版本并决定下一次合法操作。

验收场次中，房主通过出牌、万能选色和喊 UNO 推进牌局；来客按测试策略主要摸牌并在可出时选择放弃，从而让终局在可控时间内出现。两人从 0 分发牌开始，137 次界面动作后由服务端以 859 分判定 `MATCH_OVER`、数据库写入 `ENDED`。记录到 84 次摸牌、14 次摸后放弃、2 次 +4 接受、1 次出牌时喊 UNO 和 2 次选色。两人随后从结算页返回等待室，重新准备并启动了不同 `matchId` 的第二局；刷新后恢复本人手牌与实时连接。将临时脚本整理进仓库后，再次运行 `npm run test:e2e:classic`，以 149 次界面动作完成另一场从零分开始的终局和第二局启动，结果通过。

移动浏览器使用 390×844 视口；验证页面无横向溢出，手牌行可横向滚动。桌面结算、窄屏结算及新局截图见 [桌面结算](evidence/stage8-settlement-desktop.png)、[窄屏结算](evidence/stage8-settlement-mobile.png)、[窄屏新局](evidence/stage8-next-match-mobile.png)。截图仅含临时测试昵称。

复跑需启动真实服务、Mailpit 与 Web，并为 `web/e2e/classic-match.cjs` 设置 `UNO_E2E_ORIGIN`、`UNO_E2E_MAILPIT`（默认 `http://localhost:5173` 与 `http://127.0.0.1:28025`）；先在 `web` 执行 `npm ci`、`npx playwright install chromium`，再执行 `npm run test:e2e:classic`。每次运行创建新账号，截图写入系统临时目录，脚本最后打印路径。测试账号与牌局留在所连接的环境，因此只能对隔离测试环境运行。

## 范围与限制

本次浏览器整局实际经过 UNO 随出牌宣告、万能选色、摸牌后放弃及 +4 接受。UNO 抓漏喊和 +4 质疑的界面动作由组件测试覆盖，后端规则及 WebSocket 集成测试覆盖相应服务分支；这两条分支未在真实浏览器整局中碰到。只验证 Chromium 的桌面与窄屏视口，未进行其他浏览器、真机、跨实例广播和完整弱网验收。Docker 完整镜像构建曾因拉取 Eclipse Temurin 17 镜像超时中断；本次三个 Java 服务由本机 Maven JAR 启动，因此不记为完整 Docker 部署验收。
