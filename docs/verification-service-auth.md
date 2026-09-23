# stage3：跨服务身份增量验收

日期：2026-09-07。衔接 [首次账号后端验收](verification-stage3.md)。此记录覆盖identity内部核验、game消费身份、网关隔离与撤销，不代表房间、对局、双端UI或生产上线完成。

## 已交付

- identity增加受专用服务凭证保护的 `/internal/auth/introspect`；网关没有该路由，浏览器Cookie/Origin不能调用内部入口。
- game的 `/api/system/session`提取Web Cookie或App Bearer，逐请求向identity核验；不信任外部userId头、不共享账号数据库、不缓存有效会话。
- 检查协议版本、受众、客户端类型、ID与过期时间；响应限4 KiB，完整响应等待有界，拒绝重定向。凭证无效401，依赖异常503，不泄露内部响应。
- 默认要求HTTPS；本地HTTP必须显式启用且限制目标主机。专用服务认证不等于用户Basic登录，也不是mTLS。
- 本地环境初始化补充随机服务密钥，保留已有配置；基础部署保持账号关闭，auth-local才启用身份链路。

详细接口、配置和限制见 [服务认证说明](service-authentication.md)。

## 自动验证

从仓库根运行：

```sh
mvn -f backend/pom.xml -Pdatabase-it clean verify
```

本机额外使用临时Maven镜像设置，不提交个人代理配置。2026-09-07 12:27完整聚合构建成功，后端共76项，失败/错误/跳过均为0：

Web接入并更新能力状态文案后，2026-09-07 13:48再次完整clean verify成功，仍为76项；下表为两次相同的测试组成。

| 测试组 | 数量 |
| --- | ---: |
| game-core通信策略 | 10 |
| identity密码/加密基础 | 4 |
| identity内部入口安全 | 5 |
| identity认证HTTP/数据库/SMTP | 14 |
| identity数据库迁移与隔离 | 13 |
| game内部HTTP核验客户端 | 20 |
| game独立数据库 | 1 |
| 真实跨服务集成 | 9 |

九项跨服务测试启动真实identity、game、gateway进程与独立PostgreSQL17/Mailpit容器，覆盖直连/网关身份一致、伪造头与路径、刷新轮换、刷新重放、退出、密码重置、禁用/过期、Web来源和凭证模式隔离、内部认证、identity中断与恢复。测试JAR复制到独立目录，结束清理所属进程/容器，不使用共享数据库。聚合报告保存在各模块target的surefire/failsafe目录（不提交Git）。

本机预览额外通过：

```sh
EXPECT_AUTH_AVAILABLE=true EXPECT_GAME_AUTH_AVAILABLE=true API_BASE_URL=http://127.0.0.1:29080 node tools/smoke-api.mjs
```

验证功能开关、匿名拒绝和内部路由不经网关暴露。后续真实Web浏览器也验证了同一userId及退出后两服务401，见 [Web验收](verification-stage4-web.md)。

## 尚未覆盖

- 登录渠道与公开注册策略仍待确认，因此stage3不标为产品决策全部完成。
- 未验收公网TLS/mTLS、真实SMTP供应商、分布式容量与可信代理限流链。
- 实时长连接撤销属于后续阶段；当前只保证下一次HTTP身份核验反映撤销。
- 全栈Docker镜像、APK/IPA、生产发布未验收；没有自动提交、推送或创建分支。
