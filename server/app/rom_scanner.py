"""ROM file scanner - parses messy filenames, detects platform by extension."""
from __future__ import annotations
import re
import zlib
from pathlib import Path
from dataclasses import dataclass

# Patterns like (USA), [!], (Rev 1), (En,Ja)...
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

# Region priority for picking preferred title
REGION_RANK = {"China": 0, "TW": 1, "HK": 1, "Japan": 2, "Asia": 3, "Korea": 4,
               "USA": 5, "Europe": 6, "World": 7}


@dataclass
class ParsedRom:
    raw_title: str           # 原始文件名(无扩展)
    title: str               # 清洗后标题
    region: str
    languages: list[str]
    tags: list[str]          # 标签: rev/beta/demo/disc...
    disc: int | None
    crc32: str


def parse_rom_filename(filename: str) -> ParsedRom:
    """Parse a messy ROM filename into structured info."""
    stem = Path(filename).stem

    tags: list[str] = []
    region = ""
    languages: list[str] = []
    disc = None

    # extract all tags
    for m in TAG_RE.finditer(stem):
        val = m.group(1)
        if val.startswith("Disc "):
            try:
                disc = int(val.split()[1])
            except Exception:
                pass
            tags.append(val)
        elif val.startswith("Disk ") or val.startswith("Side ") or val.startswith("Part "):
            tags.append(val)
        elif val in ("USA", "Europe", "Japan", "World", "Asia", "China", "Korea", "HK", "TW"):
            if not region:
                region = val
        elif val in ("En", "Ja", "Zh", "Fr", "De", "Es", "It", "Pt", "Ru", "Ko"):
            languages.append(val)
        elif val.startswith("Rev "):
            tags.append(val)
        else:
            tags.append(val)

    # strip tags from stem to get title
    title = TAG_RE.sub("", stem)
    # also strip " - " prefixed region tags sometimes seen
    title = re.sub(r"\s+-\s*$", "", title)
    title = re.sub(r"\s{2,}", " ", title).strip(" -_.")

    return ParsedRom(
        raw_title=stem,
        title=title or stem,
        region=region,
        languages=languages,
        tags=tags,
        disc=disc,
        crc32="",
    )


def detect_platform_by_ext(ext: str, custom_map: dict | None = None) -> str | None:
    """Map file extension to platform code."""
    ext = ext.lower().lstrip(".")
    mapping = {
        "nes": "FC", "fds": "FC",
        "smc": "SFC", "fig": "SFC", "sfc": "SFC", "swc": "SFC",
        "n64": "N64", "z64": "N64", "v64": "N64",
        "gb": "GB", "gmb": "GB",
        "gbc": "GBC", "cgb": "GBC",
        "gba": "GBA", "agb": "GBA",
        "nds": "NDS", "dsi": "NDS",
        "3ds": "3DS", "cia": "3DS",
        "md": "MD", "gen": "MD", "smd": "MD", "bin": "MD",
        "cue": "SATURN", "ccd": "SATURN", "mds": "SATURN", "iso": "DC",
        "chd": "DC",
        "gdi": "DC", "cdi": "DC",
        "pbp": "PSP",
        "cso": "PSP",
        "wad": "WII", "wbfs": "WII", "rvz": "WII", "nkit": "WII",
        "gcm": "GC", "ciso": "GC",
        "pce": "PCE", "sgx": "PCE",
        "neo": "NEOGEO",
        "zip": "MAME",
        "jar": "J2ME",
        "exe": "WIN", "msi": "WIN",
        "bat": "DOS", "com": "DOS",
        "swf": "FLASH",
        "html": "HTML", "htm": "HTML",
        "img": "SATURN",
    }
    # PS1 vs PS2 disambiguation: same extensions; use folder hint if provided
    if ext in ("iso", "bin", "mdf", "nrg") and custom_map:
        return None
    return mapping.get(ext)


def crc32_file(path: Path, max_bytes: int = 1024 * 1024) -> str:
    """Compute CRC32 of first MB - fast fingerprint for dedup."""
    try:
        h = 0
        with open(path, "rb") as f:
            data = f.read(max_bytes)
            h = zlib.crc32(data) & 0xFFFFFFFF
            # also include file size in hash
            h = zlib.crc32(data + str(path.stat().st_size).encode()) & 0xFFFFFFFF
        return f"{h:08X}"
    except Exception:
        return ""


def extract_year(text: str) -> str | None:
    m = re.search(r"\b(19[89]\d|20[0-3]\d)\b", text)
    return m.group(1) if m else None
