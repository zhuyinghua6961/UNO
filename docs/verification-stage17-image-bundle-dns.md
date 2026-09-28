# 本地镜像包与容器地址变更验收

日期：2026-09-28。范围是本机 Docker Desktop 的 `linux/arm64` 隔离 Compose 栈；不是生产环境、跨网媒体或真机验收。

## 已复现的问题

`0.3.6-local-playtest` 的 Web/JAR 归档来自 `61c3d2e3cd64f27fd220c93ca6d2906d43bb3898`。先对其全部包内文件和归档重新校验，再从包内 Web 文件与三个 JAR 构建四个本地镜像。`release/0.3.6-local-playtest-images/images.tar.gz` 的 SHA-256 是 `c4827749bd53a0cbc1962c341bef0a198f96d6c1fd3ce5c4efd5437cf575634e`；`docker load -i` 成功，隔离栈 `uno-bundle-036` 的四个容器均显示该包的源码标签，Game 的 V17 迁移成功。原版镜像的账号、Mailpit 验证、经典整局、四人 2v2 整局、队伍文字、WebSocket 与双方战绩检查通过。

给隔离栈启用 LiveKit 时，Game 容器被重建并换了 IP。原 Gateway 持续向旧地址发请求，房间创建与战绩接口返回 500；Gateway 自身健康接口仍返回 200。Gateway 日志确认连接旧 `game-service` IP 被拒绝。之后另一次强制换 Gateway IP，原 Web Nginx 仍连旧地址，Web 的 `/api/system/bootstrap` 返回 502；直接访问新 Gateway 返回 200。这两项是实际故障，不是模拟测试断言。

## 修复与候选复测

Gateway 的 Reactor Netty HTTP 客户端通过 Spring Cloud Gateway `HttpClientCustomizer` 将 DNS 正向缓存上限设为 5 秒、负向缓存设为 1 秒。Web Nginx 对 `/api/` 和 `/ws/` 在请求时使用 Docker 内部 DNS 解析 `gateway`，缓存有效期 5 秒。该配置只适用于使用 Docker 内部 DNS `127.0.0.11` 的 Compose Web 容器。

候选 Gateway JAR 和 Web 配置制成临时镜像并替换隔离栈的对应容器后，执行以下实测：

- Game 原地址 `172.18.0.8` 被占位容器占用，新 Game 为 `172.18.0.9`；没有重启 Gateway，直连 Gateway 与经 Web 的 bootstrap 均返回 200。随后真实账号、队伍文字 WebSocket、四人 2v2 整局、语音令牌准入和战绩检查通过。
- Gateway 原地址 `172.18.0.6` 被占位，新地址为 `172.18.0.10`；原 Web 返回 502。替换 Web 配置后返回 200。再次占用 `172.18.0.10` 并使 Gateway 换到 `172.18.0.11`，没有重启 Web，Web 的 Game bootstrap 和 Identity auth 状态接口均返回 200。
- 修复栈上的浏览器四人对局与双方虚拟麦克风 PCM 到达通过；房外账号无法读取房间/牌局/文字或获取语音令牌，对手手牌 ID 未泄露。语音撤销用例还验证旧 JWT 无法重新进入，新队友继续使用保留的麦克风选择。

这些测试通过复用 `0.3.6` Game/Identity 镜像加候选 Gateway/Web 镜像完成，**还不是同一提交的最终交付包**。下一步要从包含两项修复的干净提交重新构建 Web、JAR、移动模拟器包及镜像，并用新归档独立启动。占位容器和旧测试栈需要在验收后恢复/清理。

剩余：真实手机麦克风听感、iOS 实际音轨、弱网切换、跨网 ICE/TURN、HTTPS/WSS、签名安装包、容量、生产部署及兼容回滚。镜像是本机 `linux/arm64` 保存文件，没有镜像仓库 digest；其他架构需在目标架构重新构建和验证。
