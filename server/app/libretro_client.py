"""LibRetro Thumbnails 客户端 - 无需 API key 的游戏封面/截图源。

数据源: https://thumbnails.libretro.com/
URL 模式:
    https://thumbnails.libretro.com/<system>/Named_Boxarts/<rom_name>.png
    https://thumbnails.libretro.com/<system>/Named_Snapshots/<rom_name>.png

文件名格式: 与 No-Intro DAT 文件一致, 如 "Super Mario Bros. (World).png"
系统目录名映射见 SYSTEM_FOLDERS。
"""
from __future__ import annotations
import re
from pathlib import Path
from typing import Optional
from urllib.parse import quote
import httpx
from loguru import logger

BASE_URL = "https://thumbnails.libretro.com"

# 我们的平台代号 -> LibRetro 系统目录名
SYSTEM_FOLDERS = {
    "FC":     "Nintendo - Nintendo Entertainment System",
    "NES":    "Nintendo - Nintendo Entertainment System",
    "SFC":    "Nintendo - Super Nintendo Entertainment System",
    "SNES":   "Nintendo - Super Nintendo Entertainment System",
    "N64":    "Nintendo - Nintendo 64",
    "GB":     "Nintendo - Game Boy",
    "GBC":    "Nintendo - Game Boy Color",
    "GBA":    "Nintendo - Game Boy Advance",
    "NDS":    "Nintendo - Nintendo DS",
    "3DS":    "Nintendo - 3DS",
    "MD":     "Sega - Mega Drive - Genesis",
    "GENESIS": "Sega - Mega Drive - Genesis",
    "SATURN": "Sega - Saturn",
    "DC":     "Sega - Dreamcast",
    "PS1":    "Sony - PlayStation",
    "PS2":    "Sony - PlayStation 2",
    "PSP":    "Sony - PlayStation Portable",
    "WII":    "Nintendo - Wii",
    "GC":     "Nintendo - GameCube",
    "PCE":    "NEC - PC Engine - TurboGrafx 16",
    "NEOGEO": "SNK - Neo Geo",
    "MAME":   "MAME",
    "ARCADE": "MAME",
}

# 文件名清理: 去掉 (USA) (Rev 1) [!] 之类标记
TAG_RE = re.compile(
    r"\s*[\(\[]("
    r"USA|Europe|Japan|World|Asia|China|Korea|HK|TW"
    r"|En|Ja|Zh|Fr|De|Es|It|Pt|Ru|Ko"
    r"|Rev\s*\d+|v\d+\.\d+|Beta|Proto|Sample|Demo|Unl|Hack"
    r"|Disc\s*\d+|Disk\s*\d+|Side\s*[AB]|Part\s*\d+"
    r"|[!]"
    r")[\)\]]",
    re.IGNORECASE,
)

# No-Intro 风格的额外后缀: (1985-09)(Nintendo)(US)
NOINTRO_SUFFIX = re.compile(
    r"\s*\(\d{4}-\d{2}\)\([^)]+\)\([^)]+\)\s*"
)


def get_system_folder(platform_code: str) -> Optional[str]:
    """平台代号 -> LibRetro 系统目录名"""
    return SYSTEM_FOLDERS.get(platform_code.upper())


def parse_rom_to_libretro(filename: str) -> list[str]:
    """把我们的 ROM 文件名转成 LibRetro 可能用的多种文件名格式。

    返回多个候选名 (从最精确到最宽松), 用于依次尝试。
    LibRetro No-Intro DAT 里的文件名带句点, 如 "Super Mario Bros. (World)"
    我们也需要尝试不加句点的版本, 如 "Super Mario Bros (World)"
    """
    if not filename:
        return []

    # 1. 去扩展名
    stem = Path(filename).stem
    candidates = [stem]

    # 2. 加句点版本: "Super Mario Bros" -> "Super Mario Bros."
    # (No-Intro DAT 用的格式)
    dotted = stem
    # 在游戏名后加点, 但不重复加
    for prefix in ["Super Mario Bros", "Super Mario", "Zelda", "Contra"]:
        if stem == prefix or (stem.startswith(prefix) and len(stem) > len(prefix)
                              and not stem[len(prefix)].startswith(".")):
            dotted = prefix + "." + stem[len(prefix):]
            break
    if dotted != stem and dotted not in candidates:
        candidates.append(dotted)

    # 3. 去掉 (USA) (Rev 1) 等标记
    cleaned = TAG_RE.sub("", stem).strip()
    if cleaned and cleaned not in candidates:
        candidates.append(cleaned)
    cleaned_dotted = TAG_RE.sub("", dotted).strip()
    if cleaned_dotted and cleaned_dotted not in candidates:
        candidates.append(cleaned_dotted)

    # 4. 去掉句点但保留清理结果
    cleaned_no_dot = cleaned.rstrip(".").strip()
    if cleaned_no_dot and cleaned_no_dot not in candidates:
        candidates.append(cleaned_no_dot)

    return candidates


async def _try_url(client: httpx.AsyncClient, url: str) -> Optional[bytes]:
    """尝试下载 URL, 失败返回 None"""
    try:
        r = await client.get(url, timeout=15.0)
        if r.status_code == 200 and r.headers.get("content-type", "").startswith("image/"):
            return r.content
        return None
    except Exception as e:
        logger.debug(f"LibRetro URL fail {url}: {e}")
        return None


async def fetch_cover(client: httpx.AsyncClient, platform_code: str,
                       rom_filename: str) -> Optional[bytes]:
    """获取封面 PNG, 返回字节或 None"""
    folder = get_system_folder(platform_code)
    if not folder:
        return None
    candidates = parse_rom_to_libretro(rom_filename)
    for name in candidates:
        url = f"{BASE_URL}/{quote(folder)}/Named_Boxarts/{quote(name)}.png"
        data = await _try_url(client, url)
        if data:
            logger.debug(f"LibRetro cover hit: {name}")
            return data
    return None


async def fetch_screenshot(client: httpx.AsyncClient, platform_code: str,
                            rom_filename: str) -> Optional[bytes]:
    """获取截图 PNG, 返回字节或 None"""
    folder = get_system_folder(platform_code)
    if not folder:
        return None
    candidates = parse_rom_to_libretro(rom_filename)
    for name in candidates:
        url = f"{BASE_URL}/{quote(folder)}/Named_Snapshots/{quote(name)}.png"
        data = await _try_url(client, url)
        if data:
            logger.debug(f"LibRetro screenshot hit: {name}")
            return data
    return None


async def fetch_title_screen(client: httpx.AsyncClient, platform_code: str,
                              rom_filename: str) -> Optional[bytes]:
    """获取标题画面 PNG, 返回字节或 None"""
    folder = get_system_folder(platform_code)
    if not folder:
        return None
    candidates = parse_rom_to_libretro(rom_filename)
    for name in candidates:
        url = f"{BASE_URL}/{quote(folder)}/Named_Titlescreens/{quote(name)}.png"
        data = await _try_url(client, url)
        if data:
            logger.debug(f"LibRetro title hit: {name}")
            return data
    return None
