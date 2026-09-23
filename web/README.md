# Vue Web

Vue 3 + TypeScript + Vite，Pinia 管理模式与账号状态，Vue Router 提供大厅、账号、等待室和经典对局牌桌；`/preview` 保留为静态交互预览。

先在根目录运行 `node tools/sync-assets.mjs`，再在此目录运行：

```sh
npm ci
npm run dev
npm run build
npm test
```

建议 Node 24 LTS（`.nvmrc`）。原本的本机 Node 23 不在测试工具支持范围内。

默认开发代理到 localhost:8080；Docker 后端使用 `GATEWAY_URL=http://localhost:28080 npm run dev`。账号开发按 [认证指南](../docs/authentication.md) 启用后端与本地邮件，将当前网页的完整Origin同时加入identity/game的白名单。仅检查bootstrap不代表对战可用。

账号已接入真实注册、验证、登录、找回/重置、退出和会话恢复；后端开关关闭或状态检查失败时禁用表单。邮件中Token字段复制到相应表单，不接受URL携带密码/凭证。

`/account`展示真实邮箱/昵称/ID，并可核对游戏服务身份。身份通过HttpOnly Cookie管理；每次写请求重新取得CSRF，不用localStorage/sessionStorage持久化凭证。页面启动、受保护路由、重新聚焦和可见时每60秒恢复会话；断网隐藏无法确认的用户信息，退出失败不假装成功。站内返回目标使用明确白名单。

已接入好友房大厅、邀请链接和等待室；可创建/加入、准备、2v2选队、调整经典局人数与离开。经典房在全员准备后由房主开始对局；牌桌通过 `/ws/game` 接收本人私有视图，支持出牌、摸牌、万能选色、UNO、+4 质疑、轮次推进、结算和返回等待室。未确认的命令不自动重发；断线重连后重新订阅并同步权威状态。2v2 对局、文字聊天和语音仍未实现；未安装 LiveKit SDK，不请求录音。

`public/game-assets` 是从根 assets 同步的生成目录；只使用原素材清单中已记录来源的内容。源码测试覆盖账号、房间、对局传输和牌桌状态；真实浏览器联调见 [账号 Web 验收](../docs/verification-stage4-web.md) 和 [经典对局 Web 验收](../docs/verification-stage8-web.md)。

完整经典局浏览器测试需先启动隔离的账号、游戏、网关、Web 与 Mailpit 服务，并安装 Chromium：`npx playwright install chromium`。在 `web` 目录运行 `UNO_E2E_ORIGIN=http://localhost:5173 UNO_E2E_MAILPIT=http://127.0.0.1:28025 npm run test:e2e:classic`；按实际本机端口调整变量。该测试会注册临时账号、发送真实邮件、打完整局并开始第二局，只应连接隔离的测试环境。
