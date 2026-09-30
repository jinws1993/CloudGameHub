"""LibRetro metadata source - 无需 API, 无需注册.

数据源: https://thumbnails.libretro.com/<system>/Named_Boxarts/
- 这个目录列出了所有 LibRetro 收录的 ROM 缩略图文件名
- 文件名格式: <title>(<region>)[<tags>].png 来自 No-Intro/Redump DAT
- 我们爬一次目录, 解析文件名, 得到 canonical title/year/region/publisher

存储: /data/libretro_db/<system>.json
- 首次访问时拉取并缓存
- 后续直接读本地 JSON, 离线可用
"""
from __future__ import annotations
import asyncio
import json
import re
import httpx
from pathlib import Path
from typing import Optional
from loguru import logger

from .config import settings, DATA_DIR
from .libretro_client import SYSTEM_FOLDERS

BASE_URL = "https://thumbnails.libretro.com"
# 数据缓存目录
DB_DIR = DATA_DIR / "libretro_db"
DB_DIR.mkdir(parents=True, exist_ok=True)

# 解析文件名:
# 例:
#   '89 Dennou Kyuusei Uranai (Japan).png
#   Super Mario Bros. (World).png
#   2-in-1 Super Mario Bros. + Duck Hunt (1988-11)(Nintendo)(US).png
#   Bomberman (1990-12-19)(Hudson)(JP).png
TAG_RE = re.compile(r"\s*\(([^()]*)\)\s*")
YEAR_RE = re.compile(r"^(?:-\d+)?(\d{4})(?:-\d+)?$")


def _parse_libretro_name(filename: str) -> dict | None:
    """解析 LibRetro 文件名为 {title, year, region, publisher, tags}.

    返回 None 表示无法解析.
    """
    name = filename.replace(".png", "").strip()
    if not name:
        return None

    # 抽离尾部 () 块: (1988-12-10)(Induction Produce)(JP)
    # 但要先识别哪个是 year 哪个是 region
    matches = TAG_RE.findall(name)
    base = TAG_RE.sub('', name).strip()

    year = ""
    region = ""
    publisher = ""
    tags = []

    for m in matches:
        m = m.strip()
        # 年月日 (1988-12-10)
        if re.match(r"^\d{4}-\d{2}-\d{2}$", m):
            year = m[:4]
            continue
        # 年 (1988)
        if YEAR_RE.match(m):
            year = YEAR_RE.match(m).group(1)
            continue
        # 地区 (Japan, USA, World, Europe, JP, US, EU, etc)
        if m in ("Japan", "USA", "World", "Europe", "Asia", "Korea", "China",
                 "JP", "US", "EU", "UK", "Cn", "Kr", "Asia", "Aus", "Can",
                 "France", "Germany", "Spain", "Italy", "Netherlands", "Sweden",
                 "Japan, Asia", "USA, Europe", "World, Japan"):
            region = m
            continue
        # 制作方
        if len(m) > 2 and not m.startswith('[') and not m.endswith(']'):
            # 可能是制作方 (Hudson, Nintendo, etc)
            # 但要排除一些常见词
            if m not in ("En", "Ja", "Fr", "De", "Es"):
                publisher = m
                continue
        # 其它当 tags
        tags.append(m)

    return {
        "title": base,
        "year": year,
        "region": region,
        "publisher": publisher,
        "tags": tags,
    }


async def _fetch_dir_listing(client: httpx.AsyncClient, system_dir: str) -> list[str]:
    """抓取 LibRetro Named_Boxarts 目录列表, 返回所有 PNG 文件名."""
    url = f"{BASE_URL}/{system_dir}/Named_Boxarts/"
    try:
        r = await client.get(url, timeout=60.0)
        if r.status_code != 200:
            logger.warning(f"LibRetro list {url}: HTTP {r.status_code}")
            return []
        # 解析 HTML 目录列表, 提取 href="xxx.png", 并 URL 解码
        from urllib.parse import unquote
        return [unquote(m) for m in re.findall(r'href="([^"]+\.png)"', r.text)]
    except Exception as e:
        logger.warning(f"LibRetro list fail {url}: {e}")
        return []


async def build_db_for_platform(platform_code: str, force: bool = False) -> dict:
    """构建/加载某平台的 LibRetro 元数据库.

    返回 {filename_lower: {title, year, region, publisher, tags}}
    """
    system_dir = SYSTEM_FOLDERS.get(platform_code.upper())
    if not system_dir:
        return {}

    cache_file = DB_DIR / f"{platform_code.upper()}.json"
    if cache_file.exists() and not force:
        try:
            return json.loads(cache_file.read_text(encoding="utf-8"))
        except Exception:
            pass

    proxies = None
    if settings.proxy_enabled and settings.proxy_url:
        proxies = {"http://": settings.proxy_url, "https://": settings.proxy_url}

    db = {}
    async with httpx.AsyncClient(timeout=120.0, proxies=proxies) as client:
        files = await _fetch_dir_listing(client, system_dir)
        for f in files:
            parsed = _parse_libretro_name(f)
            if parsed:
                key = f.lower().replace(".png", "")
                db[key] = parsed

    cache_file.write_text(
        json.dumps(db, ensure_ascii=False, indent=0),
        encoding="utf-8",
    )
    logger.info(f"LibRetro DB built: {platform_code} -> {len(db)} entries (cached at {cache_file})")
    return db


async def lookup_libretro(platform_code: str, rom_filename: str) -> dict | None:
    """查 LibRetro 元数据.

    返回 {title, year, region, publisher, tags} 或 None.
    """
    if not platform_code:
        return None
    db = await build_db_for_platform(platform_code)
    # 文件名可能带扩展名 (game.nes), 也可能不带 (game)
    key = rom_filename.lower()
    # 去掉扩展名再试
    if "." in key:
        stem = key.rsplit(".", 1)[0]
        if stem in db:
            return db[stem]
    if key in db:
        return db[key]
    return None


# ===== 同步便捷接口 (供 ScrapeEngine 使用) =====
def lookup_libretro_sync(platform_code: str, rom_filename: str) -> dict | None:
    """同步版本 - 从缓存读, 不阻塞."""
    cache_file = DB_DIR / f"{platform_code.upper()}.json"
    if not cache_file.exists():
        return None
    try:
        db = json.loads(cache_file.read_text(encoding="utf-8"))
        key = rom_filename.lower()
        if "." in key:
            stem = key.rsplit(".", 1)[0]
            if stem in db:
                return db[stem]
        if key in db:
            return db[key]
    except Exception:
        pass
    return None