# Vue Web

Vue 3 + TypeScript + Vite，Pinia 管理模式选择，Vue Router 提供大厅 / login / preview。

先在根目录运行 `node tools/sync-assets.mjs`，再在此目录运行：

```sh
npm ci
npm run dev
npm run build
npm test
```

建议 Node 24 LTS（`.nvmrc`）。原本的本机 Node 23 不在测试工具支持范围内。

默认开发代理到 localhost:8080；Docker 后端使用 `GATEWAY_URL=http://localhost:28080 npm run dev`。顶栏只检查真实后端 bootstrap；连接成功也只显示“后端骨架已连接”，不表示登录/对战可用。

当前能切换模式、看牌桌布局、切换房间/队伍聊天标签；登录、发送消息、开麦都禁用，未安装 LiveKit SDK，不收集凭证或请求录音。

`public/game-assets` 是从根 assets 同步的生成目录；只使用原素材清单中已记录来源的内容。源码测试用于骨架交互，后续增加真实房间与账号流程测试。
