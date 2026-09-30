"""Build client-side emulator launch commands/URLs.

For Android: uses `am start` with the configured package (intent extra = rom path)
For Windows: returns the command line to run on the client side
"""
from __future__ import annotations
from urllib.parse import quote
from .config import settings


def windows_launch_cmd(platform_code: str, rom_local_path: str,
                       emulator_override: str = "") -> dict:
    """Build Windows-side command to launch a game locally."""
    cfg = settings.emulator_map.get(platform_code, {})
    emu = emulator_override or cfg.get("win") or cfg.get("type", "")
    if not emu:
        return {"ok": False, "error": f"no emulator configured for {platform_code}"}
    return {
        "ok": True,
        "command": [emu, rom_local_path],
        "platform": platform_code,
    }


def android_launch_intent(platform_code: str, rom_local_path: str,
                          package_override: str = "") -> dict:
    """Build Android intent (action + data) to launch a game."""
    cfg = settings.emulator_map.get(platform_code, {})
    pkg = package_override or cfg.get("android") or cfg.get("type", "")
    if not pkg:
        return {"ok": False, "error": f"no android emulator package for {platform_code}"}

    # Generic intent: most retro emulators accept ACTION_VIEW + content:// or file://
    return {
        "ok": True,
        "intent": {
            "action": "android.intent.action.VIEW",
            "data": f"file://{quote(rom_local_path)}",
            "package": pkg,
        },
        "package": pkg,
        "platform": platform_code,
    }
