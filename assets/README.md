# UNO 开发素材库

获取与核对日期：2026-09-06。此目录只提供素材，不包含游戏实现。

## 优先使用

- `preview.html`：本地素材预览，包含卡牌、按钮、图标和音效播放器。
- `ready/cards/`：54 种牌面与 1 种牌背，PNG，最大 320 × 500；透明背景。
- `ready/audio/`：23 段卡牌相关 OGG 音效。
- `ready/ui/`：红、黄、蓝、绿按钮 PNG。
- `ready/icons/`：6 个 SVG，包括顺逆时针、摸牌、出牌、翻牌与奖章。
- `manifest.json`：相对于本目录的路径、原文件映射、来源、图片尺寸、牌面数量、下载包 SHA-256。
- `previews/cards-overview.png`：全部已选牌面的总览。

`ready` 是精选与适配目录；`vendor` 保留供应方原始文件；`downloads` 保留原始 ZIP。不要将整个 vendor 目录默认打包进游戏。

Git 只保存精选素材、预览、索引以及 vendor 中的许可记录；约 1.2GB 的原始下载包与未精选原图保留在本地，不纳入提交。克隆仓库后可直接运行素材同步和构建；若需要运行 `tools/prepare-assets.py` 重新整理素材，须先按下列来源和 manifest.json 中的文件清单恢复 downloads/vendor 原始内容，并核对下载包校验值。

## 来源与许可记录

| 素材包 | 作者与来源 | 获取内容 | 许可依据 |
| --- | --- | --- | --- |
| 4 Colour Cards | VerzatileDev，https://verzatiledev.itch.io/4colour | 50 张数字牌、36 张特殊牌/牌背及 2 张合图；原始单张约 965 × 1507 | 作者产品页标注 CC0；2026-03-03 发布日志宣布改为 CC0。原 ZIP 没有独立许可文本；本目录额外保留核对记录 |
| Casino Audio | Kenney，https://kenney.nl/assets/casino-audio | 发牌、出牌、洗牌、滑牌、筹码、骰子等 OGG | 包内 `vendor/casino-audio/License.txt`，CC0 |
| UI Pack | Kenney，https://kenney.nl/assets/ui-pack | 多色按钮、面板、控件、SVG、字体及音效 | 包内 `vendor/ui-pack/License.txt`，CC0 |
| Board Game Icons | Kenney，https://kenney.nl/assets/board-game-icons | 桌游图标 PNG 与 SVG | 包内 `vendor/board-game-icons/License.txt`，CC0 |
| Playing Cards Pack | Kenney，https://kenney.nl/assets/playing-cards-pack | 像素风标准扑克牌、空白牌、牌背和合图 | 包内 `vendor/playing-cards-pack/License.txt`，CC0；备用素材，不是 UNO 牌面 |

这些是第三方发布的素材，而非美泰官方素材。供应方对其素材的许可不等于获得 UNO 名称、标志或官方美术的品牌授权。本次精选牌背不含 UNO 标志。公开发布前请单独核对命名与品牌使用。

## 卡牌接入约定

- 数字牌：`red-0.png` 至 `red-9.png`，其他颜色对应 `yellow`、`blue`、`green`。
- 功能牌：`{color}-draw-two.png`、`{color}-reverse.png`、`{color}-skip.png`。
- 万能牌：`wild.png`、`wild-draw-four.png`，两者在数据层均无固定颜色。
- 牌背：`back.png`。同局对所有牌使用同一种背面。
- 清单中的 `copies` 定义用于经典 108 张牌原型的重复数量；美术文件不是完整牌堆，一张美术可以由多张逻辑牌共用。
- 原包额外提供黑色数字牌、彩色万能牌等变体，这些没有选入经典牌堆。

牌面原始文件名无法直接表明含义。本次通过带原编号的联系表目视核对后映射；红色与绿色数字的原始排列是反向的，不能直接按编号递增当作数字递增。

## 音效建议

- 出牌：`card-place-1.ogg` 至 `card-place-4.ogg`，可随机切换。
- 摸牌：`card-slide-1.ogg` 至 `card-slide-8.ogg`。
- 洗牌：`card-shuffle.ogg`。
- 手牌展开：`card-fan-1.ogg` / `card-fan-2.ogg`。
- 还未准备背景音乐、中文语音、玩家头像与独立胜利提示音。

音频已检查文件签名并提供试听入口；并未逐段人工试听或做响度归一化。接入时需要验证目标浏览器/引擎的 OGG 支持，并在用户首次操作后启用声音。

## 重新整理

脚本 `../tools/prepare-assets.py` 从已下载的 vendor 文件重新生成精选素材、索引与预览；需要 Python 和 Pillow，不发起网络请求。原始下载包的校验值在 `manifest.json`。

建议署名：Card artwork by VerzatileDev; UI, icons and audio by Kenney. CC0. 该署名用于记录来源，不代表官方背书。
