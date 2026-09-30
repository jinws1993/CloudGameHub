"""Application configuration loaded from environment / config file."""
from __future__ import annotations
import json
import os
from pathlib import Path
from pydantic_settings import BaseSettings
from pydantic import Field

DATA_DIR = Path(os.environ.get("NASGAME_DATA_DIR", "/data"))
CONFIG_FILE = DATA_DIR / "config" / "config.json"


class Settings(BaseSettings):
    # Server
    host: str = "0.0.0.0"
    port: int = 14322
    debug: bool = False

    # Auth
    admin_username: str = "admin"
    admin_password: str = "admin123"
    jwt_secret: str = Field(default_factory=lambda: os.urandom(32).hex())
    jwt_expire_hours: int = 24 * 7

    # Paths (resolved relative to DATA_DIR at startup)
    roms_dir: Path = DATA_DIR / "roms"
    media_dir: Path = DATA_DIR / "media"
    db_path: Path = DATA_DIR / "db" / "nasgame.db"
    logs_dir: Path = DATA_DIR / "logs"

    # Scraper
    screenscraper_user: str = ""
    screenscraper_pass: str = ""
    screenscraper_devid: str = ""
    screenscraper_devpass: str = ""
    scraper_lang: str = "zh"
    scraper_region: str = "cn"
    # 代理设置 (用于所有外部 API 调用)
    proxy_url: str = ""
    # 默认代理 (宿主代理) - 可在设置中覆盖
    proxy_enabled: bool = False

    # AI
    ai_enabled: bool = False
    ai_base_url: str = "https://api.openai.com/v1"
    ai_api_key: str = ""
    ai_model: str = "gpt-4o-mini"
    ai_temperature: float = 0.2

    # Streaming
    stream_enabled: bool = True
    stream_max_bitrate: int = 8000
    stream_fps: int = 60

    # 115 网盘集成
    cloud_115_cookie: str = ""       # 用户手动粘贴的 Cookie 串
    cloud_115_enabled: bool = False  # 是否启用 (由用户在设置页勾选)

    # Emulator mapping per platform
    emulator_map: dict = Field(default_factory=lambda: {
        "FC":     {"type": "nestopia",    "win": "nestopia.exe",     "android": "com.frodo.nes"},
        "SFC":    {"type": "snes9x",      "win": "snes9x.exe",       "android": "com.explusalpha.Snes9xPlus"},
        "N64":    {"type": "mupen64plus", "win": "mupen64plus.exe",  "android": "org.mupen64plusae"},
        "GBA":    {"type": "mgba",        "win": "mgba.exe",         "android": "mgba"},
        "GB":     {"type": "sameboy",     "win": "sameboy.exe",      "android": "com.explusalpha.GbcEmulator"},
        "GBC":    {"type": "sameboy",     "win": "sameboy.exe",      "android": "com.explusalpha.GbcEmulator"},
        "NDS":    {"type": "melonds",     "win": "melonDS.exe",      "android": "com.explusalpha.MelonDs"},
        "PSP":    {"type": "ppsspp",      "win": "PPSSPP.exe",       "android": "org.ppsspp.ppsspp"},
        "PS1":    {"type": "duckstation", "win": "duckstation.exe",  "android": "com.github.stenzek.duckstation"},
        "PS2":    {"type": "pcsx2",       "win": "pcsx2.exe",        "android": "com.alyx.arp"},
        "DC":     {"type": "flycast",     "win": "flycast.exe",      "android": "com.flycastEmu.flycast"},
        "SATURN": {"type": "mednafen",    "win": "mednafen.exe",     "android": "com.explusalpha.MdEmu"},
        "MD":     {"type": "genesisplusgx","win": "gens.exe",        "android": "com.explusalpha.MdEmu"},
        "ARCADE": {"type": "mame",        "win": "mame.exe",         "android": "com.explusalpha.MameEmu"},
        "MAME":   {"type": "mame",        "win": "mame.exe",         "android": "com.explusalpha.MameEmu"},
        "NEOGEO": {"type": "mame",        "win": "mame.exe",         "android": "com.flycastEmu.flycast"},
        "PCE":    {"type": "mednafen",    "win": "mednafen.exe",     "android": "com.explusalpha.MdEmu"},
        "WII":    {"type": "dolphin",     "win": "dolphin.exe",      "android": "org.dolphinemu.dolphinemu"},
        "GC":     {"type": "dolphin",     "win": "dolphin.exe",      "android": "org.dolphinemu.dolphinemu"},
        "3DS":    {"type": "citra",       "win": "citra.exe",        "android": "org.citra.citra_emu"},
        "J2ME":   {"type": "freej2me",    "win": "freej2me.jar",     "android": "com.freedroid.j2me"},
    })

    class Config:
        env_prefix = "NASGAME_"


def load_user_config() -> dict:
    """Load user-overrides from JSON config file if present."""
    if CONFIG_FILE.exists():
        try:
            return json.loads(CONFIG_FILE.read_text(encoding="utf-8"))
        except Exception:
            return {}
    return {}


def save_user_config(cfg: dict) -> None:
    CONFIG_FILE.parent.mkdir(parents=True, exist_ok=True)
    CONFIG_FILE.write_text(json.dumps(cfg, indent=2, ensure_ascii=False), encoding="utf-8")


settings = Settings()
for k, v in load_user_config().items():
    if hasattr(settings, k):
        # Path 字段从 JSON 加载后会丢失类型,需要重新包装
        if isinstance(getattr(settings, k), Path) and not isinstance(v, Path):
            v = Path(v)
        setattr(settings, k, v)

# Ensure dirs exist
for d in [settings.roms_dir, settings.media_dir, settings.db_path.parent,
          settings.logs_dir, CONFIG_FILE.parent]:
    Path(d).mkdir(parents=True, exist_ok=True)
