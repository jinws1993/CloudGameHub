"""High-level scrape orchestrator: combines ROM parsing, ScreenScraper, AI, translation."""
from __future__ import annotations
import asyncio
import json
from pathlib import Path
from datetime import datetime
from typing import Optional

import httpx
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from loguru import logger

from .config import settings
from .database import Game, ScanTask, SessionLocal
from .rom_scanner import parse_rom_filename, crc32_file, detect_platform_by_ext
from .scraper import ScreenScraperClient, download_to
from .translator import translate as do_translate, detect_language
from .ai_matcher import AIMatcher
from .libretro_client import fetch_cover as lr_fetch_cover, fetch_screenshot as lr_fetch_screenshot


class ScrapeEngine:
    def __init__(self):
        self.ai = AIMatcher(
            base_url=settings.ai_base_url,
            api_key=settings.ai_api_key,
            model=settings.ai_model,
        ) if settings.ai_enabled else AIMatcher("", "")

    # ---- ROM scanning ----
    async def scan_roms(self, db: AsyncSession, roms_dir: Path,
                        task: ScanTask) -> int:
        """Walk roms_dir, create Game entries, return count."""
        if not roms_dir.exists():
            return 0

        # index platforms by folder
        from .database import Platform
        res = await db.execute(select(Platform))
        platforms = {p.folder.lower(): p for p in res.scalars().all()}
        # also index by extension
        ext_to_platform = {}
        for p in platforms.values():
            for ext in p.extensions.split(","):
                ext = ext.strip().lower()
                if ext:
                    ext_to_platform.setdefault(ext, []).append(p)

        found = 0
        rom_files: list[Path] = []
        for ext in ext_to_platform:
            rom_files.extend(roms_dir.rglob(f"*.{ext}"))
            rom_files.extend(roms_dir.rglob(f"*.{ext.upper()}"))

        task.total = len(rom_files)
        await db.commit()

        # dedup by rom_path
        existing_res = await db.execute(select(Game.rom_path))
        existing_paths = {p[0] for p in existing_res.all()}

        for f in rom_files:
            rel = str(f.relative_to(roms_dir))
            if rel in existing_paths:
                task.processed += 1
                continue

            # determine platform
            ext = f.suffix.lstrip(".").lower()
            # First try: parent folder name
            parent_folder = f.parent.name.lower() if f.parent != roms_dir else ""
            platform = platforms.get(parent_folder)
            # Fallback: extension mapping
            if not platform:
                cands = ext_to_platform.get(ext, [])
                if len(cands) == 1:
                    platform = cands[0]
                elif len(cands) > 1:
                    # PS1/PS2/SATURN/DC ambiguous: prefer PS1 over PS2 for .iso
                    # (PS2 typically uses .iso too but is larger; without folder
                    #  hint we pick the first)
                    priority = ["PS1", "PS2", "PSP", "SATURN", "DC", "WII", "GC"]
                    platform = next(
                        (c for code in priority for c in cands if c.code == code),
                        cands[0],
                    )

            if not platform:
                task.processed += 1
                continue

            parsed = parse_rom_filename(f.name)
            try:
                size = f.stat().st_size
            except Exception:
                size = 0

            game = Game(
                platform_id=platform.id,
                rom_path=rel,
                rom_filename=f.name,
                rom_size=size,
                title_raw=parsed.raw_title,
                title=parsed.title,
                scrape_status="pending",
                extra={"region": parsed.region, "languages": parsed.languages,
                       "tags": parsed.tags, "disc": parsed.disc},
            )
            db.add(game)
            found += 1
            task.processed += 1

            if found % 20 == 0:
                await db.commit()

        await db.commit()
        return found

    # ---- Scrape one game ----
    async def scrape_game(self, db: AsyncSession, game: Game,
                          ss: Optional[ScreenScraperClient] = None,
                          force: bool = False,
                          progress_cb=None,
                          custom_name: str | None = None) -> bool:
        if game.scrape_status == "done" and not force:
            return True

        parsed = parse_rom_filename(game.rom_filename)
        # 自定义名字: 覆盖 parsed.title / rom_filename 让 ScreenScraper 按用户名字搜
        if custom_name:
            custom_name = custom_name.strip()
            logger.info(f"scrape_game 用用户自定义名字: {custom_name} (原: {game.rom_filename})")
            parsed.title = custom_name
            # rom_filename 用于 SS 搜索关键字, 后面 fallback 会用
        roms_full = settings.roms_dir / game.rom_path

        ss_client = ss
        own_ss = False
        if ss_client is None and settings.screenscraper_user:
            ss_client = ScreenScraperClient(
                settings.screenscraper_user, settings.screenscraper_pass,
                devid=settings.screenscraper_devid,
                devpassword=settings.screenscraper_devpass,
            )
            await ss_client.__aenter__()
            own_ss = True

        try:
            metadata, source = await self._collect_metadata(
                game=game,
                ss_client=ss_client,
                custom_name=custom_name,
                prefer_ai=False,
                progress_cb=progress_cb,
            )

            if not metadata:
                game.scrape_status = "failed"
                error_msg = (ss_client.last_error if ss_client else "") or "no_metadata"
                game.extra = {**(game.extra or {}), "scrape_error": error_msg}
                await db.commit()
                return False

            # Apply metadata
            game.title_en = metadata.get("title_en", "") or ""
            game.title_jp = metadata.get("title_jp", "") or ""
            # title_zh: 优先用 metadata, 否则翻译
            if metadata.get("title_zh"):
                game.title_zh = metadata["title_zh"]
            elif metadata.get("title_en"):
                game.title_zh = await do_translate(
                    metadata["title_en"],
                    ai_client=self.ai.client if self.ai.enabled else None,
                    ai_model=self.ai.model,
                )
            else:
                game.title_zh = ""
            if not game.title_en:
                game.title_en = parsed.title
            # 主字段 game.title: 总是使用最新中文译名 (跟随 title_zh)
            game.title = game.title_zh or game.title_en or parsed.title

            game.description_en = metadata.get("description_en", "") or ""
            if game.description_en:
                # Translate to Chinese
                game.description = await do_translate(
                    game.description_en, ai_client=self.ai.client if self.ai.enabled else None,
                    ai_model=self.ai.model,
                )
            game.release_date = metadata.get("year", "") or ""
            game.developer = metadata.get("developer", "") or ""
            game.publisher = metadata.get("publisher", "") or ""
            game.genre = metadata.get("genre", "") or ""
            game.players = metadata.get("players", "") or ""
            game.rating = float(metadata.get("rating", 0) or 0)
            game.scraper_id = metadata.get("scraper_id", "")
            # 优先使用 metadata 携带的来源标识 (内置/SS/AI)
            src = metadata.get("scraper_source", "")
            if src == "builtin":
                game.scrape_source = "builtin"
            elif src == "libretro":
                game.scrape_source = "libretro"
            elif src == "ai":
                game.scrape_source = "ai"
            elif ss_client:
                game.scrape_source = "screenscraper"
            else:
                game.scrape_source = "local"
            game.scraped_at = datetime.utcnow()

            # Download media
            # 使用全局代理设置
            proxies = None
            if settings.proxy_enabled and settings.proxy_url:
                proxies = {"http://": settings.proxy_url, "https://": settings.proxy_url}
            async with httpx.AsyncClient(timeout=60.0, proxies=proxies) as http:
                media_dir = settings.media_dir / str(game.id)
                media_dir.mkdir(parents=True, exist_ok=True)
                if metadata.get("cover_url"):
                    cover_dest = media_dir / "cover.jpg"
                    if await download_to(metadata["cover_url"], cover_dest, http):
                        game.cover_path = f"media/{game.id}/cover.jpg"
                if metadata.get("screenshot_url"):
                    ss_dest = media_dir / "screenshot.jpg"
                    if await download_to(metadata["screenshot_url"], ss_dest, http):
                        existing = json.loads(game.screenshot_paths or "[]")
                        existing.append(f"media/{game.id}/screenshot.jpg")
                        game.screenshot_paths = json.dumps(existing)
                if metadata.get("logo_url"):
                    logo_dest = media_dir / "logo.png"
                    if await download_to(metadata["logo_url"], logo_dest, http):
                        game.logo_path = f"media/{game.id}/logo.png"
                if metadata.get("video_url"):
                    video_dest = media_dir / "video.mp4"
                    if await download_to(metadata["video_url"], video_dest, http):
                        game.video_path = f"media/{game.id}/video.mp4"

                # Fallback: LibRetro Thumbnails (无需账号)
                # 仅当其他源未提供封面时使用
                platform_code = game.platform.code if game.platform else ""
                if not game.cover_path and platform_code:
                    cover_bytes = await lr_fetch_cover(
                        http, platform_code, game.rom_filename
                    )
                    if cover_bytes:
                        cover_dest = media_dir / "cover.png"
                        cover_dest.write_bytes(cover_bytes)
                        game.cover_path = f"media/{game.id}/cover.png"
                        logger.info(f"LibRetro cover: {game.rom_filename}")
                if not game.screenshot_paths and platform_code:
                    ss_bytes = await lr_fetch_screenshot(
                        http, platform_code, game.rom_filename
                    )
                    if ss_bytes:
                        ss_dest = media_dir / "screenshot.png"
                        ss_dest.write_bytes(ss_bytes)
                        existing = json.loads(game.screenshot_paths or "[]")
                        existing.append(f"media/{game.id}/screenshot.png")
                        game.screenshot_paths = json.dumps(existing)
                        logger.info(f"LibRetro screenshot: {game.rom_filename}")

            game.scrape_status = "done"
            extra = {**(game.extra or {}), "regions": metadata.get("regions", [])}
            if custom_name:
                extra["last_custom_name"] = custom_name
            game.extra = extra
            await db.commit()
            return True

        except Exception:
            pass

        finally:
            if own_ss:
                await ss_client.__aexit__(None, None, None)

    async def _collect_metadata(self, game: Game,
                                ss_client=None,
                                custom_name: str | None = None,
                                prefer_ai: bool = False,
                                progress_cb=None) -> tuple[dict | None, str | None]:
        """收集 game 元数据, 按 fallback chain 遍历. 返回 (metadata, source) 或 (None, None).
        供 scrape_game 和 scrape-search 共享.
        custom_name: 用户填的名字 (会跳过 builtin/LibRetro, 因为它们按 ROM 文件名查表).
        prefer_ai: True 时 AI 优先 (仅在 AI 启用时; scrape-search 用).
        """
        parsed = parse_rom_filename(game.rom_filename)
        roms_full = settings.roms_dir / game.rom_path
        metadata = None
        source = None

        # Fallback 1: 内置数据库 (不依赖外部凭据)
        # custom_name 时跳过 (内置按 ROM 文件名查, 与 custom_name 无关)
        if not metadata and not custom_name:
            try:
                from .builtin_db import lookup_builtin
                builtin = lookup_builtin(game.rom_filename)
                if builtin:
                    metadata = {
                        "title_en": builtin.get("title_en", ""),
                        "title_zh": builtin.get("title_zh", ""),
                        "description_en": builtin.get("description_en", ""),
                        "year": builtin.get("year", ""),
                        "developer": builtin.get("developer", ""),
                        "publisher": builtin.get("publisher", ""),
                        "genre": builtin.get("genre", ""),
                        "players": "",
                        "rating": 0,
                        "cover_url": "",
                        "screenshot_url": "",
                        "logo_url": "",
                        "video_url": "",
                        "scraper_id": "",
                    }
                    metadata["scraper_source"] = "builtin"
                    source = "builtin"
                    logger.info(f"使用内置元数据库: {game.rom_filename}")
            except Exception as e:
                logger.debug(f"builtin lookup fail: {e}")

        # Fallback 2: LibRetro (不需 API; 只按 ROM 文件名查)
        if not metadata and not custom_name and game.platform:
            try:
                from .libretro_meta import lookup_libretro_sync
                lr = lookup_libretro_sync(game.platform.code, game.rom_filename)
                if lr:
                    metadata = {
                        "title_en": lr.get("title", "") or parsed.title,
                        "title_zh": "",
                        "description_en": "",
                        "year": lr.get("year", ""),
                        "developer": "",
                        "publisher": lr.get("publisher", ""),
                        "genre": "",
                        "players": "",
                        "rating": 0,
                        "cover_url": "",
                        "screenshot_url": "",
                        "logo_url": "",
                        "video_url": "",
                        "scraper_id": "",
                        "regions": [lr.get("region", "")] if lr.get("region") else [],
                    }
                    metadata["scraper_source"] = "libretro"
                    source = "libretro"
                    logger.info(f"LibRetro meta: {game.rom_filename} -> {metadata['title_en']}")
            except Exception as e:
                logger.debug(f"LibRetro lookup fail: {e}")

        # 如果 prefer_ai 且 AI 启用 → AI 优先 (在 SS 之前)
        if not metadata and prefer_ai and self.ai.enabled:
            if progress_cb:
                progress_cb("AI识别游戏...")
            ai_filename = custom_name or game.rom_filename
            try:
                metadata = await self.ai.identify(
                    filename=ai_filename,
                    platform_hint=game.platform.name_en if game.platform else "",
                )
                if metadata:
                    metadata["scraper_id"] = ""
                    metadata["cover_url"] = ""
                    metadata["screenshot_url"] = ""
                    metadata["logo_url"] = ""
                    metadata["video_url"] = ""
                    metadata["scraper_source"] = "ai"
                    source = "ai"
                    logger.info(f"AI identify: {ai_filename} -> {metadata.get('title_en', '')}")
            except Exception as e:
                logger.warning(f"AI identify fail: {e}")

        # Fallback 3: ScreenScraper (需账号)
        if not metadata and ss_client:
            crc = ""
            if roms_full.exists():
                crc = crc32_file(roms_full)
            ss_rom_name = custom_name or game.rom_filename
            try:
                metadata = await ss_client.get_game_info(
                    platform_code=game.platform.code if game.platform else "",
                    rom_filename=ss_rom_name,
                    rom_size=game.rom_size,
                    crc=crc,
                )
                if metadata:
                    metadata["scraper_source"] = "screenscraper"
                    source = "screenscraper"
            except Exception as e:
                logger.warning(f"SS get_game_info fail: {e}")

        # Fallback 4: AI identification (默认顺序: 在 SS 之后)
        if not metadata and not prefer_ai and self.ai.enabled:
            if progress_cb:
                progress_cb("AI识别游戏...")
            ai_filename = custom_name or game.rom_filename
            try:
                metadata = await self.ai.identify(
                    filename=ai_filename,
                    platform_hint=game.platform.name_en if game.platform else "",
                )
                if metadata:
                    metadata["scraper_id"] = ""
                    metadata["cover_url"] = ""
                    metadata["screenshot_url"] = ""
                    metadata["logo_url"] = ""
                    metadata["video_url"] = ""
                    metadata["scraper_source"] = "ai"
                    source = "ai"
                    logger.info(f"AI identify: {ai_filename} -> {metadata.get('title_en', '')}")
            except Exception as e:
                logger.warning(f"AI identify fail: {e}")

        return metadata, source

    async def apply_metadata(self, db: AsyncSession, game: Game,
                              metadata: dict, source: str = "custom",
                              download_media: bool = True) -> bool:
        """把掳来的 metadata 写入 game (不重新掳).
        用于 "scrape-search 返回候选项 → 用户点选 → apply" 流程."""
        if not metadata:
            return False
        parsed = parse_rom_filename(game.rom_filename)
        try:
            game.title_en = metadata.get("title_en", "") or ""
            game.title_jp = metadata.get("title_jp", "") or ""
            # title_zh: 优先用 metadata 中的, 没有时从 metadata 自带的 description_en 或
            # 现有的 description_en 翻译
            if metadata.get("title_zh"):
                game.title_zh = metadata["title_zh"]
            elif metadata.get("title_en"):
                game.title_zh = await do_translate(
                    metadata["title_en"],
                    ai_client=self.ai.client if self.ai.enabled else None,
                    ai_model=self.ai.model,
                )
            else:
                game.title_zh = ""
            if not game.title_en:
                game.title_en = parsed.title
            # game.title 主字段: 总使用中文译名 (或回退英文) - 跟随 title_zh
            # 用户期望"名字被替换", 所以总是覆盖.
            game.title = game.title_zh or game.title_en or parsed.title

            game.description_en = metadata.get("description_en", "") or ""
            # 来自 AI/SS 的 description_en 总是覆盖现有, 不论中文描述是否已有
            # (避免之前误译/乱码的描述保留下来)
            if game.description_en:
                game.description = await do_translate(
                    game.description_en, ai_client=self.ai.client if self.ai.enabled else None,
                    ai_model=self.ai.model,
                )
            game.release_date = metadata.get("year", "") or ""
            game.developer = metadata.get("developer", "") or ""
            game.publisher = metadata.get("publisher", "") or ""
            game.genre = metadata.get("genre", "") or ""
            game.players = metadata.get("players", "") or ""
            try:
                game.rating = float(metadata.get("rating", 0) or 0)
            except Exception:
                game.rating = 0
            game.scraper_id = metadata.get("scraper_id", "") or ""
            game.scrape_source = source or "custom"
            game.scraped_at = datetime.utcnow()
            game.scrape_status = "done"

            extra = {**(game.extra or {}), "regions": metadata.get("regions", [])}
            extra["applied_from"] = source
            extra["applied_at"] = datetime.utcnow().isoformat()
            game.extra = extra

            # 下裁封面/截图 (仅 cover, 避免过度抓取)
            if download_media:
                try:
                    proxies = None
                    if settings.proxy_enabled and settings.proxy_url:
                        proxies = {"http://": settings.proxy_url, "https://": settings.proxy_url}
                    async with httpx.AsyncClient(timeout=60.0, proxies=proxies) as http:
                        media_dir = settings.media_dir / str(game.id)
                        media_dir.mkdir(parents=True, exist_ok=True)
                        if metadata.get("cover_url"):
                            cover_dest = media_dir / "cover.jpg"
                            if await download_to(metadata["cover_url"], cover_dest, http):
                                game.cover_path = f"media/{game.id}/cover.jpg"
                        if metadata.get("screenshot_url"):
                            ss_dest = media_dir / "screenshot.jpg"
                            if await download_to(metadata["screenshot_url"], ss_dest, http):
                                existing = json.loads(game.screenshot_paths or "[]")
                                existing.append(f"media/{game.id}/screenshot.jpg")
                                game.screenshot_paths = json.dumps(existing)
                except Exception as e:
                    logger.warning(f"apply_metadata 下裁媒体失败: {e}")

            await db.commit()
            return True
        except Exception as e:
            logger.warning(f"apply_metadata 失败: {e}")
            try:
                game.scrape_status = "failed"
                game.extra = {**(game.extra or {}), "scrape_error": str(e)[:200]}
                await db.commit()
            except Exception:
                pass
            return False


    # ---- Bulk scrape ----
    async def bulk_scrape(self, task_id: int,
                          platform_code: str | None = None,
                          force: bool = False) -> None:
        async with SessionLocal() as db:
            task = await db.get(ScanTask, task_id)
            if not task:
                return

            q = select(Game).where(Game.scrape_status.in_(["pending", "failed"]))
            if platform_code:
                from .database import Platform
                p = (await db.execute(select(Platform).where(Platform.code == platform_code))).scalar_one_or_none()
                if p:
                    q = q.where(Game.platform_id == p.id)
            games = (await db.execute(q)).scalars().all()
            if force:
                # include already-done games
                q2 = select(Game)
                if platform_code:
                    from .database import Platform
                    p = (await db.execute(select(Platform).where(Platform.code == platform_code))).scalar_one_or_none()
                    if p:
                        q2 = q2.where(Game.platform_id == p.id)
                games = (await db.execute(q2)).scalars().all()

            task.total = len(games)
            task.status = "running"
            await db.commit()

            ss = None
            if settings.screenscraper_user:
                ss = ScreenScraperClient(
                    settings.screenscraper_user, settings.screenscraper_pass,
                    devid=settings.screenscraper_devid,
                    devpassword=settings.screenscraper_devpass,
                )
                await ss.__aenter__()
            try:
                for g in games:
                    try:
                        ok = await self.scrape_game(db, g, ss=ss, force=force)
                        if ok:
                            task.success += 1
                        else:
                            task.failed += 1
                    except Exception as e:
                        logger.exception(f"Scrape error for {g.rom_filename}: {e}")
                        task.failed += 1
                    task.processed += 1
                    if task.processed % 5 == 0:
                        await db.commit()
                task.status = "done"
                task.finished_at = datetime.utcnow()
                await db.commit()
            finally:
                if ss:
                    await ss.__aexit__(None, None, None)


engine = ScrapeEngine()
