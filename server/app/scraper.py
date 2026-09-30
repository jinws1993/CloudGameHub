"""ScreenScraper.fr API client - main metadata source."""
from __future__ import annotations
import asyncio
import hashlib
import re
from pathlib import Path
from typing import Optional
from urllib.parse import urlencode
import httpx
from loguru import logger
from .config import settings

BASE_URL = "https://www.screenscraper.fr/api2"

# Map our platform code -> ScreenScraper platform id
SS_PLATFORM = {
    "FC": 3, "SFC": 4, "N64": 14,
    "GB": 9, "GBC": 10, "GBA": 12, "NDS": 15, "3DS": 17,
    "MD": 1, "SATURN": 22, "DC": 23,
    "PS1": 57, "PS2": 58, "PSP": 61,
    "WII": 16, "GC": 13,
    "PCE": 31,
    "NEOGEO": 24, "MAME": 75, "ARCADE": 75,
    "J2ME": 142,
}


def _md5(text: str) -> str:
    return hashlib.md5(text.encode()).hexdigest()


class ScreenScraperClient:
    # 类级标记: 检测到 403 后全进程内不再调 SS (避免每条都走 15s 超时)
    _disabled_globally = False

    def __init__(self, user: str, password: str,
                 devid: str = "", devpassword: str = ""):
        self.user = user
        self.password = password
        # devid/devpassword 是 ScreenScraper 的开发者凭据 (需注册开发者账号获得)
        # 默认使用公开的 dev 账号 (会限流, 仅供测试)
        self.devid = devid or "nasgame"
        self.devpassword = devpassword or "nasgame123"
        self._client: httpx.AsyncClient | None = None
        self.last_error: str = ""  # 上一次错误信息
        self.last_status: int = 0  # 上一次 HTTP 状态

    async def __aenter__(self):
        # 代理配置
        proxies = None
        if settings.proxy_enabled and settings.proxy_url:
            proxies = {"http://": settings.proxy_url, "https://": settings.proxy_url}
        self._client = httpx.AsyncClient(
            timeout=60.0,
            headers={"User-Agent": "NASGame/1.0"},
            proxies=proxies,
        )
        return self

    async def __aexit__(self, *args):
        if self._client:
            await self._client.aclose()

    async def get_game_info(
        self,
        platform_code: str,
        rom_filename: str = "",
        rom_size: int = 0,
        crc: str = "",
        md5_hash: str = "",
        sha1_hash: str = "",
    ) -> dict | None:
        """Query ScreenScraper for game metadata."""
        if not self._client:
            return None
        ss_id = SS_PLATFORM.get(platform_code)
        if not ss_id:
            return None

        params = {
            "devid": self.devid,
            "devpassword": self.devpassword,
            "softname": "nasgame",
            "output": "json",
            "ssid": self.user,
            "sspassword": self.password,
            "systemeid": ss_id,
            "romtype": "rom",
            "romnom": rom_filename,
            "romsize": str(rom_size or 0),
            "romcrc": (crc or "").lower(),
            "rommd5": (md5_hash or "").lower(),
        }
        if sha1_hash:
            params["romsha1"] = sha1_hash.lower()

        try:
            # 全局禁用: 检测到 403 后跳过, 避免每条游戏都走 15s 超时
            if ScreenScraperClient._disabled_globally:
                self.last_error = "ScreenScraper 已全局禁用 (凭据被拒)"
                self.last_status = 403
                return None
            # ScreenScraper 官方文档推荐用 GET 以查询字符串方式发送参数
            # POST 会被拒绝 (“缺少 URL 字段”)
            resp = await self._client.get(
                BASE_URL + "/jeuInfos.php", params=params
            )
            self.last_status = resp.status_code
            if resp.status_code != 200:
                # 提取错误信息供前端展示
                try:
                    err_data = resp.json()
                    err = (err_data.get("response", {}).get("erreur")
                           or err_data.get("erreur")
                           or resp.text[:200])
                except Exception:
                    err = resp.text[:200]
                # 添加人类可读的提示
                friendly = ""
                if resp.status_code == 403:
                    friendly = "请检查 developer 凭据 (需在 screenscraper.fr 论坛申请)"
                    # 403 = 凭据被拒, 全局禁用避免每条都 15s 超时
                    ScreenScraperClient._disabled_globally = True
                    logger.warning(f"ScreenScraper 全局禁用 (检测到 403): {err}")
                elif resp.status_code == 401:
                    friendly = "API 暂时对非活跃用户关闭"
                self.last_error = f"HTTP {resp.status_code}: {err}" + (f" ({friendly})" if friendly else "")
                logger.debug(f"ScreenScraper {self.last_error}")
                return None
            try:
                data = resp.json()
            except Exception as e:
                self.last_error = f"非 JSON 返回: {e}"
                logger.warning(f"ScreenScraper {self.last_error}")
                return None
            if "response" not in data:
                return None
            jeu = data["response"].get("jeu") or {}
            if not jeu:
                return None
            return self._parse(jeu)
        except Exception as e:
            logger.warning(f"ScreenScraper error: {e}")
            return None

    def _parse(self, jeu: dict) -> dict:
        """Parse ScreenScraper JSON into normalized dict."""
        noms = jeu.get("noms", {}) or {}
        textes = jeu.get("textes", {}) or {}
        medias = jeu.get("medias", []) or []
        classifications = jeu.get("classifications", []) or {}
        dates = jeu.get("dates", []) or []
        genres_raw = jeu.get("genres", []) or []
        regions_raw = jeu.get("regions", []) or []
        dev = jeu.get("developpeur", "") or ""
        pub = jeu.get("editeur", "") or ""
        players = jeu.get("joueurs", {}) or {}
        rating = jeu.get("note", {}) or {}

        def find_media(media_type: str, region: str = "") -> dict | None:
            for m in medias:
                if m.get("type") == media_type:
                    if region and m.get("region") != region:
                        continue
                    return m
            return None

        cover = find_media("png", "cn") or find_media("png") or find_media("jpg")
        ss_img = find_media("ss", "cn") or find_media("ss")
        logo = find_media("logo", "cn") or find_media("logo")
        video = find_media("video") or {}

        return {
            "scraper_id": str(jeu.get("id", "")),
            "title_en": noms.get("nom_eu", noms.get("nom_us", "")) or "",
            "title_jp": noms.get("nom_jp", "") or "",
            "title_zh": noms.get("nom_cn", noms.get("nom_tw", noms.get("nom_hk", ""))) or "",
            "description_en": textes.get("text", "") or "",
            "year": (dates[0].get("date") if dates else "")[:4] if dates else "",
            "developer": dev.strip(),
            "publisher": pub.strip(),
            "genre": ", ".join(g.get("name", "") for g in (genres_raw or []) if isinstance(g, dict) and g.get("name")),
            "players": str(players.get("joueursmax", "") if isinstance(players, dict) else ""),
            "rating": float(rating.get("note", 0) or 0) if isinstance(rating, dict) else 0,
            "cover_url": cover.get("url", "") if cover else "",
            "screenshot_url": ss_img.get("url", "") if ss_img else "",
            "logo_url": logo.get("url", "") if logo else "",
            "video_url": video.get("url", "") if video else "",
            "regions": [r.get("nom") for r in (regions_raw or []) if isinstance(r, dict)],
        }


async def download_to(url: str, dest: Path, client: httpx.AsyncClient) -> bool:
    if not url:
        return False
    try:
        dest.parent.mkdir(parents=True, exist_ok=True)
        r = await client.get(url, timeout=120)
        if r.status_code == 200:
            dest.write_bytes(r.content)
            return True
        logger.debug(f"Download failed {r.status_code}: {url}")
        return False
    except Exception as e:
        logger.warning(f"Download error {url}: {e}")
        return False
