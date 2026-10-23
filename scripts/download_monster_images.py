#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从灰机 wiki 的敌人图鉴下载怪物原图，生成本地 TinyCat 图包。

页面：https://sts.huijiwiki.com/wiki/敌人图鉴
授权：站点内容按 CC BY-NC-SA 4.0 分发；游戏素材版权归 Mega Crit。
      下载结果仅供本地、非商业使用，不要提交到 TinyCat 仓库或随 JAR 分发。

页面 HTML 对脚本请求返回 403，但上传文件所在的 huiji-public CDN 可公开访问。
MediaWiki 的上传路径由文件名的 MD5 决定：
    uploads/<md5[0]>/<md5[:2]>/<文件名>
因此脚本不需要抓取 HTML，只要维护文件名清单即可。

用法：
    python scripts/download_monster_images.py --dry-run
    python scripts/download_monster_images.py --replace
    python scripts/download_monster_images.py --out "D:/game/mods/TinyCat/monster-images"

默认输出到常见 Steam 安装目录下的 mods/TinyCat/monster-images；
--replace 会把旧目录改名备份，不会删除。
默认会把 300x300 图鉴卡裁成中央立绘（去掉顶部名称和底部稀有度徽章）；
--no-crop 保留完整图鉴卡。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import struct
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zlib
from datetime import datetime
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Tuple

PAGE_URL = "https://sts.huijiwiki.com/wiki/%E6%95%8C%E4%BA%BA%E5%9B%BE%E9%89%B4"
LICENSE_NAME = "CC BY-NC-SA 4.0"
LICENSE_URL = "https://creativecommons.org/licenses/by-nc-sa/4.0/deed.zh-hans"
CDN_ROOT = "https://huiji-public.huijistatic.com/sts/uploads"
USER_AGENT = "TinyCat-local-image-fetcher/1.0 (+https://github.com/haotiangod/tinycattrack)"
PNG_MAGIC = b"\x89PNG\r\n\x1a\n"

# 图鉴卡 300x300 的中央立绘区域：去掉顶部标题和底部稀有度徽章。
CROP_RATIO = (40 / 300, 55 / 300, 260 / 300, 255 / 300)
CROP_CORNER_RATIO = 0.09

# TinyCat 的遭遇名 -> wiki 图鉴文件名（每个遭遇取一张代表图）。
# 名字必须与 .run 的 damage_taken[].enemies 完全一致。
ENCOUNTERS: Dict[str, str] = {
    # 第一幕
    "Cultist": "邪教徒图鉴.png",
    "Jaw Worm": "大颚虫图鉴.png",
    "2 Louse": "虱虫图鉴.png",
    "3 Louse": "虱虫图鉴.png",
    "Small Slimes": "酸液史莱姆图鉴.png",
    "Large Slime": "酸液史莱姆图鉴.png",
    "Lots of Slimes": "尖刺史莱姆图鉴.png",
    "Blue Slaver": "奴隶主图鉴.png",
    "Red Slaver": "奴隶主图鉴.png",
    "Looter": "抢劫的图鉴.png",
    "Exordium Thugs": "抢劫的图鉴.png",
    "Exordium Wildlife": "真菌兽图鉴.png",
    "2 Fungi Beasts": "真菌兽图鉴.png",
    "Gremlin Gang": "小地精图鉴.png",
    "Gremlin Nob": "地精大块头图鉴.png",
    "Lagavulin": "乐加维林图鉴.png",
    "3 Sentries": "哨卫图鉴.png",
    "Hexaghost": "六火亡魂图鉴.png",
    "The Guardian": "守护者图鉴.png",
    "Slime Boss": "史莱姆老大图鉴.png",
    # 第二幕
    "Chosen": "被拣选者图鉴.png",
    "Cultist and Chosen": "邪教徒图鉴.png",
    "Spheric Guardian": "圆球守护者图鉴.png",
    "Sentry and Sphere": "哨卫图鉴.png",
    "Centurion and Healer": "百夫长图鉴.png",
    "Shell Parasite": "带壳寄生怪图鉴.png",
    "Shelled Parasite and Fungi": "带壳寄生怪图鉴.png",
    "Snake Plant": "蛇花图鉴.png",
    "Snecko": "异蛇图鉴（修）.png",
    "3 Byrds": "异鸟图鉴.png",
    "Chosen and Byrds": "被拣选者图鉴.png",
    "2 Thieves": "打劫的图鉴.png",
    "Slavers": "奴隶头子图鉴.png",
    "Gremlin Leader": "地精首领图鉴.png",
    "Book of Stabbing": "扎人的书图鉴.png",
    "3 Cultists": "邪教徒图鉴.png",
    "Champ": "第一勇士图鉴.png",
    "Automaton": "铜制机械人偶图鉴.png",
    "Collector": "收藏家图鉴.png",
    # 第三幕
    "3 Shapes": "三机械图鉴（修）.png",
    "4 Shapes": "三机械图鉴（修）.png",
    "Sphere and 2 Shapes": "圆球守护者图鉴.png",
    "Orb Walker": "圆球行者图鉴.png",
    "Maw": "巨口图鉴（修）.png",
    "3 Darklings": "小黑图鉴（修）.png",
    "Transient": "倏忽魔图鉴.png",
    "Writhing Mass": "扭曲团块图鉴.png",
    "Spire Growth": "塔内增生组织图鉴.png",
    "Giant Head": "大脑袋图鉴.png",
    "Reptomancer": "拜蛇术士图鉴.png",
    "Nemesis": "天罚图鉴.png",
    "Jaw Worm Horde": "大颚虫图鉴.png",
    "Donu and Deca": "八体＆甜圈图鉴.png",
    "Awakened One": "觉醒者图鉴.png",
    "Time Eater": "时间吞噬者图鉴.png",
    # 第四幕与事件战：梯度表不统计，但保留图片供导出引用
    "Shield and Spear": "高塔之盾图鉴.png",
    "The Heart": "腐化之心图鉴.png",
    "2 Orb Walkers": "圆球行者图鉴.png",
    "Lagavulin Event": "乐加维林图鉴.png",
    "Colosseum Nobs": "地精大块头图鉴.png",
    "Colosseum Slavers": "奴隶头子图鉴.png",
    "Masked Bandits": "抢劫的图鉴.png",
    "The Mushroom Lair": "真菌兽图鉴.png",
    "Mind Bloom Boss Battle": "六火亡魂图鉴.png",
}

# 图鉴里存在、但当前遭遇映射没有直接用到的图；一并保存，方便以后补组合图。
EXTRA_WIKI_FILES = [
    "神秘术士图鉴.png",
    "高塔之矛图鉴.png",
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="下载灰机 wiki 敌人图鉴到本地 TinyCat 图包目录")
    parser.add_argument("--out", type=Path, help="输出目录，默认自动找 Steam 游戏目录")
    parser.add_argument("--replace", action="store_true",
                        help="把已有输出目录改名备份后重新下载（不会删除旧图）")
    parser.add_argument("--force", action="store_true", help="重新下载已存在的图片")
    parser.add_argument("--no-crop", action="store_true",
                        help="保留完整图鉴卡（不裁掉标题和稀有度徽章）")
    parser.add_argument("--dry-run", action="store_true", help="只打印将要下载的地址")
    parser.add_argument("--only", default="", help="只处理指定遭遇，逗号分隔")
    parser.add_argument("--list", action="store_true", help="列出遭遇与图鉴文件映射")
    parser.add_argument("--delay", type=float, default=0.5, help="每次网络请求后的等待秒数")
    parser.add_argument("--timeout", type=float, default=30.0, help="单次请求超时秒数")
    parser.add_argument("--retries", type=int, default=3, help="单张图片重试次数")
    return parser.parse_args()


def default_out_dir() -> Optional[Path]:
    env = os.environ.get("SLAY_THE_SPIRE", "").strip()
    candidates = []
    if env:
        candidates.append(Path(env))
    candidates.extend([
        Path(r"C:\Program Files (x86)\Steam\steamapps\common\SlayTheSpire"),
        Path(r"C:\Program Files\Steam\steamapps\common\SlayTheSpire"),
        Path.home() / "Library/Application Support/Steam/steamapps/common/SlayTheSpire",
        Path.home() / ".steam/steam/steamapps/common/SlayTheSpire",
    ])
    for game in candidates:
        if (game / "desktop-1.0.jar").is_file() or (game / "mods").is_dir():
            return game / "mods" / "TinyCat" / "monster-images"
    return None


def wiki_url(file_name: str) -> str:
    digest = hashlib.md5(file_name.encode("utf-8")).hexdigest()
    return f"{CDN_ROOT}/{digest[0]}/{digest[:2]}/{urllib.parse.quote(file_name)}"


def is_png(path: Path) -> bool:
    try:
        with path.open("rb") as handle:
            return handle.read(8) == PNG_MAGIC
    except OSError:
        return False


def _paeth(a: int, b: int, c: int) -> int:
    value = a + b - c
    pa, pb, pc = abs(value - a), abs(value - b), abs(value - c)
    if pa <= pb and pa <= pc:
        return a
    if pb <= pc:
        return b
    return c


def read_png_rgba(path: Path) -> Tuple[int, int, bytearray]:
    """读取 8 位、非隔行的 RGB/RGBA PNG，返回 RGBA 像素。"""
    data = path.read_bytes()
    if not data.startswith(PNG_MAGIC):
        raise ValueError("不是 PNG")
    pos = 8
    idat = bytearray()
    width = height = color = None
    while pos + 12 <= len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        ctype = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        if ctype == b"IHDR":
            width, height, depth, color, _, _, interlace = struct.unpack(">IIBBBBB", chunk)
            if depth != 8 or interlace != 0 or color not in (2, 6):
                raise ValueError(
                    f"不支持的 PNG 格式（depth={depth}, color={color}, interlace={interlace}）")
        elif ctype == b"IDAT":
            idat.extend(chunk)
        elif ctype == b"IEND":
            break
        pos += 12 + length
    if width is None or height is None:
        raise ValueError("缺少 IHDR")
    channels = 4 if color == 6 else 3
    stride = width * channels
    raw = zlib.decompress(bytes(idat))
    if len(raw) != height * (stride + 1):
        raise ValueError("PNG 数据长度不符")
    rows = []
    prev = bytearray(stride)
    pos = 0
    for _ in range(height):
        filter_type = raw[pos]
        pos += 1
        row = bytearray(raw[pos:pos + stride])
        pos += stride
        if filter_type == 1:
            for i in range(channels, stride):
                row[i] = (row[i] + row[i - channels]) & 255
        elif filter_type == 2:
            for i in range(stride):
                row[i] = (row[i] + prev[i]) & 255
        elif filter_type == 3:
            for i in range(stride):
                left = row[i - channels] if i >= channels else 0
                row[i] = (row[i] + ((left + prev[i]) >> 1)) & 255
        elif filter_type == 4:
            for i in range(stride):
                a = row[i - channels] if i >= channels else 0
                b = prev[i]
                c = prev[i - channels] if i >= channels else 0
                row[i] = (row[i] + _paeth(a, b, c)) & 255
        elif filter_type != 0:
            raise ValueError(f"未知 PNG 过滤器 {filter_type}")
        rows.append(row)
        prev = row

    pixels = bytearray(width * height * 4)
    for y, row in enumerate(rows):
        dest = y * width * 4
        if channels == 4:
            pixels[dest:dest + width * 4] = row
        else:
            for x in range(width):
                src = x * 3
                dst = dest + x * 4
                pixels[dst:dst + 3] = row[src:src + 3]
                pixels[dst + 3] = 255
    return width, height, pixels


def write_png_rgba(path: Path, width: int, height: int, pixels: bytearray) -> None:
    def chunk(ctype: bytes, payload: bytes) -> bytes:
        return (struct.pack(">I", len(payload)) + ctype + payload
                + struct.pack(">I", zlib.crc32(ctype + payload) & 0xFFFFFFFF))

    stride = width * 4
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        raw.extend(pixels[y * stride:(y + 1) * stride])
    data = (PNG_MAGIC
            + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
            + chunk(b"IEND", b""))
    temp = path.with_name(path.name + ".part")
    temp.write_bytes(data)
    os.replace(temp, path)


def crop_alias(source: Path, target: Path, enabled: bool) -> Optional[List[int]]:
    """把图鉴卡裁成中央立绘；失败时退回整图复制。返回裁切后的尺寸。"""
    if not enabled:
        shutil.copyfile(source, target)
        return None
    try:
        width, height, pixels = read_png_rgba(source)
        x1 = int(round(width * CROP_RATIO[0]))
        y1 = int(round(height * CROP_RATIO[1]))
        x2 = int(round(width * CROP_RATIO[2]))
        y2 = int(round(height * CROP_RATIO[3]))
        crop_w, crop_h = x2 - x1, y2 - y1
        if crop_w <= 0 or crop_h <= 0:
            raise ValueError("裁切区域为空")
        cropped = bytearray(crop_w * crop_h * 4)
        for y in range(crop_h):
            src = ((y1 + y) * width + x1) * 4
            dest = y * crop_w * 4
            cropped[dest:dest + crop_w * 4] = pixels[src:src + crop_w * 4]

        radius = max(4, int(round(min(crop_w, crop_h) * CROP_CORNER_RATIO)))
        for y in range(crop_h):
            for x in range(crop_w):
                dx = radius - x if x < radius else (x - (crop_w - 1 - radius) if x >= crop_w - radius else 0)
                dy = radius - y if y < radius else (y - (crop_h - 1 - radius) if y >= crop_h - radius else 0)
                if dx > 0 and dy > 0 and dx * dx + dy * dy > radius * radius:
                    cropped[(y * crop_w + x) * 4 + 3] = 0
        write_png_rgba(target, crop_w, crop_h, cropped)
        return [crop_w, crop_h]
    except Exception as error:  # noqa: BLE001 - 裁切失败不应阻断整包生成
        print(f"  警告: 裁切失败，保留整图 {source.name}: {error}")
        shutil.copyfile(source, target)
        return None


def select_encounters(only: str) -> List[str]:
    if not only.strip():
        return list(ENCOUNTERS)
    names = [part.strip() for part in only.split(",") if part.strip()]
    unknown = [name for name in names if name not in ENCOUNTERS]
    if unknown:
        raise SystemExit("未知遭遇名: " + ", ".join(unknown)
                         + "\n用 --list 查看可用名称。")
    return names


def needed_wiki_files(encounters: Iterable[str]) -> List[str]:
    files = {ENCOUNTERS[name] for name in encounters}
    # 默认全量下载时顺带保存图鉴中的其余图片；--only 时只取需要的。
    if len(list(encounters)) == len(ENCOUNTERS):
        files.update(EXTRA_WIKI_FILES)
    return sorted(files)


def fetch_png(url: str, dest: Path, timeout: float, retries: int, delay: float) -> int:
    last_error: Optional[Exception] = None
    for attempt in range(1, retries + 1):
        try:
            request = urllib.request.Request(url, headers={
                "User-Agent": USER_AGENT,
                "Referer": "https://sts.huijiwiki.com/",
                "Accept": "image/png,image/*;q=0.8,*/*;q=0.5",
            })
            with urllib.request.urlopen(request, timeout=timeout) as response:
                data = response.read()
            if not data.startswith(PNG_MAGIC):
                raise ValueError("返回内容不是 PNG")
            dest.parent.mkdir(parents=True, exist_ok=True)
            temp = dest.with_name(dest.name + ".part")
            temp.write_bytes(data)
            os.replace(temp, dest)
            return len(data)
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError,
                OSError, ValueError) as error:
            last_error = error
            if attempt < retries:
                wait = max(0.5, delay) * attempt
                print(f"  重试 {attempt}/{retries - 1}: {error}，{wait:.1f}s 后继续")
                time.sleep(wait)
    raise RuntimeError(str(last_error))


def backup_existing(out_dir: Path) -> None:
    if not out_dir.exists():
        return
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    backup = out_dir.with_name(out_dir.name + "-backup-" + stamp)
    suffix = 1
    while backup.exists():
        backup = out_dir.with_name(out_dir.name + "-backup-" + stamp + f"-{suffix}")
        suffix += 1
    shutil.move(str(out_dir), str(backup))
    print(f"旧图包已备份到: {backup}")


def safe_encounter_name(name: str) -> str:
    return re.sub(r'[<>:"/\\|?*]', "_", name)


def write_notice(out_dir: Path) -> None:
    text = f"""TinyCat local monster image pack
================================

Source page : {PAGE_URL}
License     : {LICENSE_NAME}
License URL : {LICENSE_URL}
Downloaded  : {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}

The wiki distributes its content under CC BY-NC-SA 4.0.
Slay the Spire and its assets are copyright Mega Crit Games.
These files are for local, non-commercial use with TinyCat only.
Do not commit this directory to the TinyCat repository or bundle it in a release.

The encounter-named files at the pack root are local aliases used by
MonsterImageLibrary; the original wiki files are kept under wiki/.
By default the aliases are cropped derivatives: the title banner and
rarity badge are removed and the corners are rounded. Use --no-crop to
keep the full compendium cards.
"""
    (out_dir / "NOTICE.txt").write_text(text, encoding="utf-8")


def main() -> int:
    args = parse_args()

    if args.list:
        for encounter, wiki_file in ENCOUNTERS.items():
            print(f"{encounter:30s} -> {wiki_file}")
        print(f"\n{len(ENCOUNTERS)} encounters, {len(needed_wiki_files(ENCOUNTERS))} wiki files")
        return 0

    selected = select_encounters(args.only)
    wiki_files = needed_wiki_files(selected)

    out_dir = args.out or default_out_dir()
    if out_dir is None:
        print("找不到游戏目录，请用 --out 指定 mods/TinyCat/monster-images 的路径。", file=sys.stderr)
        return 2

    if args.dry_run:
        print(f"输出目录: {out_dir}")
        for name in wiki_files:
            print(f"GET {wiki_url(name)}")
            print(f" -> {out_dir / 'wiki' / name}")
        for name in selected:
            action = "copy" if args.no_crop else "crop"
            print(f"{action} {ENCOUNTERS[name]} -> {out_dir / (safe_encounter_name(name) + '.png')}")
        return 0

    if args.replace:
        backup_existing(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    wiki_dir = out_dir / "wiki"
    wiki_dir.mkdir(parents=True, exist_ok=True)

    downloaded = 0
    skipped = 0
    failed: List[Tuple[str, str]] = []
    wiki_paths: Dict[str, Path] = {}

    print(f"输出目录: {out_dir}")
    print(f"图鉴文件: {len(wiki_files)} 张")
    for index, wiki_file in enumerate(wiki_files, 1):
        dest = wiki_dir / wiki_file
        wiki_paths[wiki_file] = dest
        if dest.is_file() and is_png(dest) and not args.force:
            skipped += 1
            print(f"[{index}/{len(wiki_files)}] 已存在 {wiki_file}")
            continue
        url = wiki_url(wiki_file)
        try:
            size = fetch_png(url, dest, args.timeout, args.retries, args.delay)
            downloaded += 1
            print(f"[{index}/{len(wiki_files)}] 下载 {wiki_file} ({size} bytes)")
        except Exception as error:  # noqa: BLE001 - 单张失败不中断其余图片
            failed.append((wiki_file, str(error)))
            print(f"[{index}/{len(wiki_files)}] 失败 {wiki_file}: {error}")
        time.sleep(max(0.0, args.delay))

    aliases = 0
    alias_sizes: Dict[str, Optional[List[int]]] = {}
    for name in selected:
        wiki_file = ENCOUNTERS[name]
        source = wiki_paths.get(wiki_file)
        if source is None or not source.is_file():
            failed.append((name, "缺少对应 wiki 图片 " + wiki_file))
            continue
        target = out_dir / (safe_encounter_name(name) + ".png")
        alias_sizes[name] = crop_alias(source, target, not args.no_crop)
        aliases += 1

    write_notice(out_dir)

    manifest = {
        "source_page": PAGE_URL,
        "license": LICENSE_NAME,
        "license_url": LICENSE_URL,
        "generated_at": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "encounter_count": len(selected),
        "wiki_file_count": len(wiki_files),
        "crop": None if args.no_crop else {
            "ratios": [round(value, 4) for value in CROP_RATIO],
            "corner_ratio": CROP_CORNER_RATIO,
        },
        "images": [],
    }
    for name in selected:
        wiki_file = ENCOUNTERS[name]
        source = out_dir / "wiki" / wiki_file
        if source.is_file():
            data = source.read_bytes()
            manifest["images"].append({
                "encounter": name,
                "file": safe_encounter_name(name) + ".png",
                "wiki_file": wiki_file,
                "url": wiki_url(wiki_file),
                "bytes": len(data),
                "sha256": hashlib.sha256(data).hexdigest(),
                "alias_size": alias_sizes.get(name),
            })
    (out_dir / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print("\n完成:")
    print(f"  下载 {downloaded}，跳过 {skipped}，遭遇别名 {aliases}"
          + ("（保留整图）" if args.no_crop else "（已裁边）"))
    print(f"  图鉴目录 {wiki_dir}")
    if failed:
        print(f"  失败 {len(failed)} 项:")
        for name, error in failed:
            print(f"    - {name}: {error}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
