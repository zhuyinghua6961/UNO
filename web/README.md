# Vue Web

Vue 3 + TypeScript + Vite，Pinia 管理模式与账号状态，Vue Router 提供大厅 / login / account / preview。

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

已接入好友房大厅、邀请链接和等待室；可创建/加入、准备、2v2选队、调整经典局人数与离开。对局、发送消息、开麦仍未实现；未安装LiveKit SDK，不请求录音。多设备账号会话不代表对局连接已实现。

`public/game-assets` 是从根 assets 同步的生成目录；只使用原素材清单中已记录来源的内容。源码测试覆盖骨架、账号传输/状态竞争/表单/路由，真实浏览器联调记录见 [Web验收](../docs/verification-stage4-web.md)。
