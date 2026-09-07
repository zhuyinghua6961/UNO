from collections import Counter
from hashlib import sha256
from html import escape
import json
from pathlib import Path
import shutil

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1] / "assets"
VENDOR = ROOT / "vendor"
CARD_ROOT = VENDOR / "4colour-cards/4ColourCardsByVerzatileDev/Individual"
READY = ROOT / "ready"
COLORS = ("red", "yellow", "blue", "green")
sources = [
    {"id": "4colour-cards", "author": "VerzatileDev", "url": "https://verzatiledev.itch.io/4colour", "license": "CC0-1.0 (publisher page)", "archive": "4ColourCardsByVerzatileDev.zip"},
    *[{"id": slug, "author": "Kenney", "url": f"https://kenney.nl/assets/{slug}", "license": "CC0-1.0 (included License.txt)", "archive": f"kenney_{slug}.zip"} for slug in ("casino-audio", "ui-pack", "board-game-icons", "playing-cards-pack")],
]
entries = []


def add_asset(source, destination, source_id, **metadata):
    target = READY / destination
    target.parent.mkdir(parents=True, exist_ok=True)
    if source.suffix == ".png" and destination.startswith("cards/"):
        with Image.open(source) as original:
            image = original.convert("RGBA")
            image.thumbnail((320, 500), Image.Resampling.LANCZOS)
            image.save(target, optimize=True)
    else:
        shutil.copy2(source, target)
    entry = {"id": Path(destination).stem, "path": str(target.relative_to(ROOT)), "original": str(source.relative_to(ROOT)), "source": source_id, **metadata}
    if target.suffix == ".png":
        with Image.open(target) as image:
            entry.update(width=image.width, height=image.height)
    entries.append(entry)
    return entry


for color in COLORS:
    for number in range(10):
        if color == "yellow":
            source_number = 1068 if number == 0 else 1058 + number
        elif color == "blue":
            source_number = 1088 if number == 0 else 1078 + number
        elif color == "red":
            source_number = 1069 if number == 0 else 1079 - number
        else:
            source_number = 1089 if number == 0 else 1099 - number
        add_asset(CARD_ROOT / f"Numbered/4ColourCardNumByVerzatileDev_g{source_number}.png", f"cards/{color}-{number}.png", "4colour-cards", kind="card", color=color, face=str(number), copies=1 if number == 0 else 2)
    special = {"yellow": (1, 2, 3), "red": (14, 13, 12), "blue": (15, 16, 17), "green": (28, 27, 26)}[color]
    for face, source_number in zip(("draw-two", "reverse", "skip"), special):
        add_asset(CARD_ROOT / f"Special/4ColourCardSpecialByVerzatileDev_g{source_number}.png", f"cards/{color}-{face}.png", "4colour-cards", kind="card", color=color, face=face, copies=2)

for face, source_number in (("wild", 33), ("wild-draw-four", 32), ("back", 36)):
    add_asset(CARD_ROOT / f"Special/4ColourCardSpecialByVerzatileDev_g{source_number}.png", f"cards/{face}.png", "4colour-cards", kind="back" if face == "back" else "card", color=None, face=face, copies=0 if face == "back" else 4)

for source in sorted((VENDOR / "casino-audio/Audio").glob("card*.ogg")):
    add_asset(source, f"audio/{source.name}", "casino-audio", kind="audio")

for color in ("Blue", "Red", "Green", "Yellow"):
    source = VENDOR / f"ui-pack/PNG/{color}/Default/button_rectangle_depth_gloss.png"
    add_asset(source, f"ui/button-{color.lower()}.png", "ui-pack", kind="ui")

for name in ("arrow_clockwise", "arrow_counterclockwise", "card_add", "card_place", "card_flip", "award"):
    add_asset(VENDOR / f"board-game-icons/Vector/Icons/{name}.svg", f"icons/{name}.svg", "board-game-icons", kind="icon")

for source in sources:
    archive = ROOT / "downloads" / source["archive"]
    source["bytes"] = archive.stat().st_size
    source["sha256"] = sha256(archive.read_bytes()).hexdigest()

cards = [entry for entry in entries if entry["kind"] in ("card", "back")]
assert len(cards) == 55
assert sum(entry.get("copies", 0) for entry in cards) == 108
assert len({entry["path"] for entry in entries}) == len(entries)
counts = dict(Counter(entry["kind"] for entry in entries))
manifest = {"retrieved": "2026-09-06", "path_base": "assets/", "sources": sources, "counts": counts, "deck_size": 108, "assets": entries}
(ROOT / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")

canvas = Image.new("RGB", (1320, 1180), "#101827")
draw = ImageDraw.Draw(canvas)
draw.text((30, 18), "FOUR COLOUR CARDS / DEVELOPMENT ASSET KIT", fill="#ffffff")
for index, entry in enumerate(cards):
    with Image.open(ROOT / entry["path"]) as source:
        image = source.convert("RGBA")
        image.thumbnail((101, 162), Image.Resampling.LANCZOS)
        left, top = 29 + index % 11 * 117, 58 + index // 11 * 219
        canvas.paste(image, (left, top), image)
        draw.text((left, top + 170), entry["id"].replace("-", "\n", 1), fill="#b8c8e0")
(ROOT / "previews").mkdir(exist_ok=True)
canvas.save(ROOT / "previews/cards-overview.png")

card_markup = "".join(f'<figure><img src="{escape(entry["path"])}" alt="{escape(entry["id"])}"><figcaption>{escape(entry["id"])}</figcaption></figure>' for entry in cards)
audio_markup = "".join(f'<div class="sound"><span>{escape(entry["id"])}</span><audio controls preload="none" src="{escape(entry["path"])}"></audio></div>' for entry in entries if entry["kind"] == "audio")
ui_markup = "".join(f'<figure class="ui"><img src="{escape(entry["path"])}" alt="{escape(entry["id"])}"><figcaption>{escape(entry["id"])}</figcaption></figure>' for entry in entries if entry["kind"] in ("ui", "icon"))
document = f'''<!doctype html>
<html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>UNO 开发素材库</title>
<style>
*{{box-sizing:border-box}}body{{margin:0;background:#101827;color:#edf3fd;font:16px/1.6 system-ui,sans-serif}}main{{max-width:1280px;margin:auto;padding:40px 24px}}h1{{font-size:36px;margin:8px 0}}h2{{margin-top:44px}}p{{color:#a9bbd3}}.badge{{color:#a5e7ce;font-size:13px;letter-spacing:2px}}.grid{{display:grid;grid-template-columns:repeat(auto-fill,minmax(110px,1fr));gap:18px}}figure{{margin:0}}figure img{{display:block;width:100%;height:178px;object-fit:contain}}figcaption{{text-align:center;color:#9fb2cd;font-size:12px;margin-top:8px;overflow-wrap:anywhere}}.sounds{{display:grid;grid-template-columns:repeat(auto-fit,minmax(275px,1fr));gap:14px}}.sound{{padding:16px;border:1px solid #2c3b51;border-radius:12px}}.sound span{{display:block;margin-bottom:8px}}audio{{width:100%}}.ui img{{height:72px}}a{{color:#a5e7ce}}footer{{margin-top:48px;border-top:1px solid #2c3b51;padding-top:20px}}
</style><main><div class="badge">LOCAL ASSET LIBRARY · 2026.09.06</div><h1>UNO 开发素材库</h1>
<p>54 种牌面 + 1 种牌背 · 23 段卡牌音效 · 按钮与矢量图标。这里只展示素材，不是可玩的游戏。</p>
<p>素材由 VerzatileDev / Kenney 发布，来源标注 CC0。不是美泰官方授权素材；来源、许可说明和原文件见 <a href="README.md">素材说明</a>。</p>
<h2>卡牌 · 320 × 500 PNG</h2><div class="grid">{card_markup}</div>
<h2>界面与图标</h2><div class="grid">{ui_markup}</div>
<h2>音效试听</h2><p>点击播放器试听。目标浏览器的 OGG 支持需要在游戏接入时验证。</p><div class="sounds">{audio_markup}</div>
<footer><a href="manifest.json">开发用素材索引</a> · 完整下载包保留在 downloads，原始内容保留在 vendor。</footer></main></html>'''
(ROOT / "preview.html").write_text(document)
print(json.dumps({"counts": counts, "deck_size": 108, "ready_bytes": sum(path.stat().st_size for path in READY.rglob("*") if path.is_file())}, indent=2))
