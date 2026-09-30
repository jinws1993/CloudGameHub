"""Default platform catalog."""
from __future__ import annotations
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select
from .database import Platform

PLATFORMS = [
    # code, 中文, English, folder, extensions, sort
    ("FC",     "红白机",   "Nintendo Entertainment System",  "fc",     "nes,fds",                                                  10),
    ("SFC",    "超级任天堂","Super Nintendo",                "sfc",    "smc,fig,sfc,swc",                                         20),
    ("N64",    "任天堂64", "Nintendo 64",                    "n64",    "n64,z64,v64",                                             30),
    ("GB",     "Game Boy", "Game Boy",                       "gb",     "gb,gmb",                                                   40),
    ("GBC",    "Game Boy Color","Game Boy Color",            "gbc",    "gbc,cgb",                                                  50),
    ("GBA",    "GBA",     "Game Boy Advance",                "gba",    "gba,agb",                                                  60),
    ("NDS",    "NDS",     "Nintendo DS",                     "nds",    "nds,dsi",                                                  70),
    ("3DS",    "3DS",     "Nintendo 3DS",                    "3ds",    "3ds,cia",                                                  80),
    ("MD",     "Mega Drive","Sega Mega Drive",               "md",     "md,gen,bin,smd",                                           90),
    ("SATURN", "土星",     "Sega Saturn",                     "saturn", "cue,iso,ccd,mds,chd",                                     100),
    ("DC",     "Dreamcast","Sega Dreamcast",                 "dc",     "cdi,chd,gdi,iso",                                          110),
    ("PS1",    "PlayStation","PlayStation",                  "ps1",    "cue,iso,ccd,mds,pbp,chd,img",                             120),
    ("PS2",    "PlayStation 2","PlayStation 2",              "ps2",    "iso,cso,bin,mdf,nrg,chd",                                 130),
    ("PSP",    "PSP",     "PlayStation Portable",            "psp",    "iso,cso,pbp",                                              140),
    ("WII",    "Wii",     "Nintendo Wii",                    "wii",    "iso,wad,wbfs,rvz,nkit",                                   150),
    ("GC",     "GameCube","Nintendo GameCube",               "gc",     "iso,gcm,ciso,rvz,nkit",                                    160),
    ("PCE",    "PC Engine","PC Engine",                      "pce",    "pce,sgx,ccd,iso,cue",                                      170),
    ("NEOGEO", "Neo Geo", "Neo Geo",                         "neogeo", "neo,zip",                                                  180),
    ("MAME",   "街机",     "Arcade (MAME)",                   "mame",   "zip",                                                      190),
    ("ARCADE", "通用街机", "Arcade",                          "arcade", "zip",                                                      200),
    ("J2ME",   "Java手游", "J2ME Mobile",                     "j2me",   "jar",                                                      210),
    ("DOS",    "DOS",     "DOS",                             "dos",    "exe,bat,com,iso,img",                                      220),
    ("WIN",    "Windows", "Windows",                         "win",    "exe,msi",                                                  230),
    ("FLASH",  "Flash游戏","Flash",                          "flash",  "swf",                                                      240),
    ("HTML",   "网页游戏", "HTML5",                           "html",   "html,htm",                                                 250),
]


async def seed_platforms(session: AsyncSession):
    res = await session.execute(select(Platform))
    existing = {p.code for p in res.scalars().all()}
    changed = False
    for code, name, name_en, folder, exts, order in PLATFORMS:
        if code in existing:
            continue
        session.add(Platform(
            code=code, name=name, name_en=name_en, folder=folder,
            extensions=exts, sort_order=order,
        ))
        changed = True
    if changed:
        await session.commit()
