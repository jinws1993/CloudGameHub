"""NASGame - Main FastAPI application."""
from __future__ import annotations
import asyncio
import json
import os
import shutil
from contextlib import asynccontextmanager
from datetime import datetime
from pathlib import Path
from typing import Optional

from fastapi import (
    FastAPI, HTTPException, Depends, UploadFile, File, Form, Query, BackgroundTasks, Request
)
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import (
    FileResponse, JSONResponse, HTMLResponse, StreamingResponse, RedirectResponse,
)
from fastapi.staticfiles import StaticFiles
from fastapi.security import HTTPBearer
from pydantic import BaseModel
from sqlalchemy import select, func, or_, and_
from sqlalchemy.ext.asyncio import AsyncSession
from loguru import logger
import aiofiles
import base64
import httpx

from .config import settings, save_user_config, load_user_config
from .database import (
    init_db, User, Platform, Game, ScanTask, PlaySession,
    SessionLocal,
)
from .auth import (
    hash_pwd, verify_pwd, create_token, get_db, current_user, require_admin,
    bearer,
)
from .scrape_engine import engine as scrape_engine
from .streamer import stream_mgr
from .emulator_launcher import windows_launch_cmd, android_launch_intent


# ------------------- lifespan -------------------
@asynccontextmanager
async def lifespan(app: FastAPI):
    await init_db()
    # create admin user if not exists
    async with SessionLocal() as db:
        res = await db.execute(select(User).where(User.username == settings.admin_username))
        if not res.scalar_one_or_none():
            admin = User(
                username=settings.admin_username,
                password_hash=hash_pwd(settings.admin_password),
                is_admin=True,
            )
            db.add(admin)
            await db.commit()
            logger.info(f"Created default admin user: {settings.admin_username}")

    logger.info(f"NASGame started on http://{settings.host}:{settings.port}")
    yield
    await stream_mgr.cleanup_all()


app = FastAPI(
    title="NASGame",
    version="1.0.0",
    lifespan=lifespan,
    docs_url="/api/docs",
    redoc_url=None,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
    allow_credentials=False,
)


# ------------------- Static / Web UI -------------------
WEB_DIR = Path(__file__).parent.parent / "web"
if WEB_DIR.exists():
    # 自定义静态文件 (加 no-cache 响应头, 防止升级后浏览器缓存老 JS)
    from starlette.responses import Response as StarletteResponse
    
    class NoCacheStaticFiles(StaticFiles):
        async def get_response(self, path, scope):
            resp = await super().get_response(path, scope)
            logger.debug(f"static serve {path} -> {type(resp).__name__}")
            if resp is not None and hasattr(resp, "headers"):
                resp.headers["Cache-Control"] = "no-cache, must-revalidate"
                logger.debug(f"  added Cache-Control header")
            return resp
    
    app.mount("/static", NoCacheStaticFiles(directory=str(WEB_DIR / "static")), name="static")


@app.get("/", response_class=HTMLResponse)
async def index():
    idx = WEB_DIR / "index.html"
    if idx.exists():
        return HTMLResponse(idx.read_text(encoding="utf-8"))
    return HTMLResponse("<h1>NASGame</h1><p>Web UI not yet installed.</p>")


@app.get("/favicon.ico")
async def favicon():
    f = WEB_DIR / "static" / "favicon.ico"
    if f.exists():
        return FileResponse(f)
    return JSONResponse({}, status_code=204)


# ------------------- Auth -------------------
class LoginIn(BaseModel):
    username: str
    password: str


class LoginOut(BaseModel):
    access_token: str
    token_type: str = "bearer"
    username: str
    is_admin: bool


@app.post("/api/auth/login", response_model=LoginOut)
async def login(data: LoginIn, db: AsyncSession = Depends(get_db)):
    res = await db.execute(select(User).where(User.username == data.username))
    user = res.scalar_one_or_none()
    if not user or not verify_pwd(data.password, user.password_hash):
        raise HTTPException(401, "用户名或密码错误")
    user.last_login = datetime.utcnow()
    await db.commit()
    return LoginOut(
        access_token=create_token(user.id, user.username),
        username=user.username,
        is_admin=user.is_admin,
    )


@app.post("/api/auth/change-password")
async def change_pwd(
    old: str = Form(...), new: str = Form(...),
    user: User = Depends(current_user), db: AsyncSession = Depends(get_db),
):
    if not verify_pwd(old, user.password_hash):
        raise HTTPException(400, "旧密码错误")
    user.password_hash = hash_pwd(new)
    await db.commit()
    return {"ok": True}


@app.get("/api/auth/me")
async def me(user: User = Depends(current_user)):
    return {"id": user.id, "username": user.username, "is_admin": user.is_admin}


# ------------------- Config -------------------
@app.get("/api/config")
async def get_config(user: User = Depends(current_user)):
    """Public-safe config view (mask secrets).

    敏感字段以 ****** 返回; 只有已设置的字段才返回占位符,
    避免前端误以为是已配置状态而忽略保存。
    """
    cfg = {
        "admin_username": settings.admin_username,
        "scraper_lang": settings.scraper_lang,
        "scraper_region": settings.scraper_region,
        "ai_enabled": settings.ai_enabled,
        "ai_model": settings.ai_model,
        "ai_base_url": settings.ai_base_url,
        # 用一个特殊占位符区分"未设置"和"已设置"
        "ai_api_key": "***AI***" if settings.ai_api_key else "",
        "ai_api_key_set": bool(settings.ai_api_key),
        "screenscraper_user": settings.screenscraper_user,
        "screenscraper_pass": "***SS***" if settings.screenscraper_pass else "",
        "screenscraper_pass_set": bool(settings.screenscraper_pass),
        "screenscraper_devid": settings.screenscraper_devid,
        "screenscraper_devpass": "***DEV***" if settings.screenscraper_devpass else "",
        "screenscraper_devpass_set": bool(settings.screenscraper_devpass),
        # 代理设置 (不敏感, 明文返回)
        "proxy_enabled": settings.proxy_enabled,
        "proxy_url": settings.proxy_url,
        # 115 网盘
        "cloud_115_enabled": settings.cloud_115_enabled,
        "cloud_115_cookie": "***115***" if settings.cloud_115_cookie else "",
        "cloud_115_cookie_set": bool(settings.cloud_115_cookie),
        "stream_enabled": settings.stream_enabled,
        "emulator_map": settings.emulator_map,
        "roms_dir": str(settings.roms_dir),
        "port": settings.port,
    }
    return cfg


@app.post("/api/config")
async def update_config(payload: dict, user: User = Depends(require_admin)):
    """Update config and persist to JSON."""
    # 处理 ****** 占位符: 如果前端送过来是占位符, 表示"不要修改"
    placeholder_map = {
        "ai_api_key": "***AI***",
        "screenscraper_pass": "***SS***",
        "screenscraper_devpass": "***DEV***",
        "cloud_115_cookie": "***115***",
    }
    for field, placeholder in placeholder_map.items():
        if field in payload and payload[field] == placeholder:
            payload.pop(field)
    current = load_user_config()
    current.update(payload)
    save_user_config(current)
    # apply to running settings (恢复 Path 类型)
    for k, v in payload.items():
        if hasattr(settings, k):
            if isinstance(getattr(settings, k), Path) and not isinstance(v, Path):
                v = Path(v)
            setattr(settings, k, v)
    return {"ok": True}


@app.post("/api/config/test-scraper")
async def test_scraper_endpoint(payload: dict, user: User = Depends(require_admin)):
    """Test ScreenScraper credentials (does not save them)."""
    from .scraper import ScreenScraperClient
    user_s = payload.get("user", "").strip()
    pass_s = payload.get("pass", "").strip()
    devid = payload.get("devid", "").strip()
    devpass = payload.get("devpass", "").strip()
    if not user_s:
        return {"ok": False, "error": "需要填写用户名"}
    try:
        async with ScreenScraperClient(user_s, pass_s, devid, devpass) as ss:
            r = await ss.get_game_info(
                platform_code="FC",
                rom_filename="Super Mario Bros",
                rom_size=40976,
            )
        if r:
            return {"ok": True, "test_title": r.get("title_en") or r.get("title_zh")}
        # 提取 last_error 提示用户
        return {"ok": False, "error": ss.last_error or "未获取到元数据。请检查账号密码是否正确,并确认 devid 是开发者账号。"}
    except Exception as e:
        return {"ok": False, "error": str(e)}


@app.post("/api/config/test-proxy")
async def test_proxy_endpoint(payload: dict, user: User = Depends(require_admin)):
    """Test proxy + LibRetro connectivity (does not save)."""
    proxy_url = payload.get("proxy_url", "").strip()
    proxies = {"http://": proxy_url, "https://": proxy_url} if proxy_url else None
    try:
        import httpx
        async with httpx.AsyncClient(timeout=20.0, proxies=proxies) as c:
            # 测试访问 LibRetro 缩略图 (无需账号, 直接可下)
            r = await c.get(
                "https://thumbnails.libretro.com/"
                "Nintendo%20-%20Super%20Nintendo%20Entertainment%20System/"
                "Named_Boxarts/Super%20Mario%20World%20(USA).png"
            )
        if r.status_code == 200 and len(r.content) > 1000:
            return {"ok": True, "size_kb": round(len(r.content)/1024, 1),
                    "msg": f"代理正常, LibRetro 缩略图下载成功 ({round(len(r.content)/1024, 1)} KB)"}
        elif r.status_code == 200:
            return {"ok": False, "error": f"代理正常但 LibRetro 返回异常 (status={r.status_code})"}
        else:
            return {"ok": False, "error": f"LibRetro 返回 {r.status_code}, 代理可能不可用"}
    except Exception as e:
        return {"ok": False, "error": f"代理测试失败: {type(e).__name__}: {e}"}


# ------------------- 115 网盘 -------------------
@app.post("/api/cloud/115/qr/start")
async def qr_115_start(user: User = Depends(require_admin)):
    """创建 115 扫码登录会话. 返回 {uid, qr_url, qr_png_data_uri}."""
    from . import cloud_115_qr
    try:
        client = await cloud_115_qr.qr_mgr.get_client()
        sess = await cloud_115_qr.create_session(client)
        # 同步生成二维码 (CPU-bound, 但很快)
        png = cloud_115_qr._make_qr_png(sess.qrcode_url)
        import base64
        b64 = base64.b64encode(png).decode()
        return {
            "ok": True,
            "uid": sess.uid,
            "qr_url": sess.qrcode_url,
            "qr_png_data_uri": f"data:image/png;base64,{b64}",
        }
    except Exception as e:
        return {"ok": False, "error": f"创建会话失败: {type(e).__name__}: {e}"}


@app.get("/api/cloud/115/qr/status/{uid}")
async def qr_115_status(uid: str, user: User = Depends(require_admin)):
    """轮询 115 扫码状态. 返回 {status, message, cookies?, cookie_string?}"""
    from . import cloud_115_qr
    sess = cloud_115_qr.get_session(uid)
    if not sess:
        return {"ok": False, "error": "会话不存在或已过期, 请刷新二维码"}
    try:
        client = await cloud_115_qr.qr_mgr.get_client()
        await cloud_115_qr.poll_status(client, sess)
    except Exception as e:
        return {"ok": False, "error": f"轮询失败: {type(e).__name__}: {e}"}

    if sess.last_status == 2 and sess.cookies:
        cookie_str = cloud_115_qr.cookies_to_string(sess.cookies)
        # 检查关键字段
        parsed = cloud_115_qr.Pan115Client if False else None
        from .cloud_115 import Pan115Client
        p = Pan115Client.parse_cookie(cookie_str)
        missing = [k for k in ("UID", "CID", "SEID") if k not in p]
        return {
            "ok": True,
            "status": 2,
            "message": "扫码成功",
            "cookies": sess.cookies,
            "cookie_string": cookie_str,
            "missing_fields": missing,
        }
    msg = {
        0: "等待扫码",
        1: "已扫码, 请在手机上点确认",
        2: "已确认",
    }.get(sess.last_status, "未知状态")
    return {"ok": True, "status": sess.last_status, "message": msg}


@app.post("/api/cloud/115/test")
async def test_115_cookie(payload: dict, user: User = Depends(require_admin)):
    """测试 115 Cookie 是否有效. payload = {"cookie": "UID=..; CID=..; SEID=.."}"""
    cookie = (payload.get("cookie") or "").strip()
    if not cookie:
        return {"ok": False, "error": "Cookie 不能为空"}
    # 115 走直连 (国内访问, 代理反而可能被拦)
    proxy = ""  # 115 强制直连, 不用全局代理
    from .cloud_115 import Pan115Client
    # 简单验证 Cookie 格式 (必须含 UID + CID + SEID)
    parsed = Pan115Client.parse_cookie(cookie)
    missing = [k for k in ("UID", "CID", "SEID") if k not in parsed]
    if missing:
        return {"ok": False, "error": f"Cookie 缺字段: {', '.join(missing)}。需要 UID、CID、SEID 三项。"}
    try:
        async with Pan115Client(cookie, proxy_url=proxy) as cli:
            r = await cli.login_check()
            return r
    except Exception as e:
        return {"ok": False, "error": f"连接错误: {type(e).__name__}: {e}"}


@app.get("/api/cloud/115/list")
async def cloud_115_list(cid: str = "0", user: User = Depends(require_admin),
                         db: AsyncSession = Depends(get_db)):
    """列 115 网盘目录. cid=0 是根目录. cid 为字符串以避免 JS Number 精度丢失."""
    if not settings.cloud_115_enabled or not settings.cloud_115_cookie:
        raise HTTPException(400, "请先在设置中启用并填写 115 Cookie")
    from .cloud_115 import Pan115Client
    # 115 网盘走直连, 不走全局代理 (用户设置页也是这么提示的)
    proxy = ""  # 115 强制直连, 不用全局代理
    # 转换为 int 给 115 SDK
    try:
        cid_int = int(cid)
    except (ValueError, TypeError):
        cid_int = 0
    try:
        async with Pan115Client(settings.cloud_115_cookie, proxy_url=proxy) as cli:
            items = await cli.list_dir(cid=cid_int, limit=200)
            # 面包屑
            paths = await cli.get_path(cid_int) if cid_int else [{"cid": 0, "name": "根目录"}]
        # 格式化输出
        return {
            "ok": True,
            "cid": cid,
            "path": paths,
            "items": [
                {
                    "cid": str(it.get("cid") or it.get("id") or 0),
                    "name": it.get("n") or it.get("name", ""),
                    # py115 官方判断: 有 'fid' = 文件, 无 'fid' = 目录
                    # 参考: https://github.com/deadblue/py115/blob/master/py115/lowlevel/types/file.py
                    "is_dir": "fid" not in it,
                    "size": int(it.get("s") or it.get("size") or 0),
                    "pickcode": it.get("pc", ""),
                    "sha1": (it.get("sha") or "").lower(),
                    "ico": it.get("ico", ""),
                    "updated_at": it.get("t") or it.get("up_time", ""),
                }
                for it in items
            ],
        }
    except Exception as e:
        logger.exception("115 list fail")
        return {"ok": False, "error": f"{type(e).__name__}: {e}"}


@app.post("/api/cloud/115/scan")
async def cloud_115_scan(payload: dict, bg: BackgroundTasks,
                         user: User = Depends(require_admin)):
    """从 115 目录扫描 ROM 并入库 (仅入库, 不下 ROM 文件本身)。

    payload = {"cid": 12345, "platform_code": "FC"}
    递归列目录 + 扩展名识别平台 + 入库 (cloud_source="115")
    """
    if not settings.cloud_115_enabled or not settings.cloud_115_cookie:
        raise HTTPException(400, "请先启用 115")
    cid = int(payload.get("cid", 0))
    platform_code = (payload.get("platform_code") or "").strip().upper()
    auto_scrape = bool(payload.get("auto_scrape", False))
    if not cid:
        raise HTTPException(400, "需要 cid")
    # 创建任务
    async with SessionLocal() as db:
        task = ScanTask(task_type="cloud_scan", target=f"115:{cid}", total=0)
        db.add(task); await db.commit(); await db.refresh(task)
        task_id = task.id

    async def _scan():
        from .cloud_115 import Pan115Client
        from .rom_scanner import detect_platform_by_ext, parse_rom_filename
        proxy = ""  # 115 强制直连, 不用全局代理
        added = 0; skipped = 0; failed = 0
        try:
            async with Pan115Client(settings.cloud_115_cookie, proxy_url=proxy) as cli:
                # 递归列文件
                files = await cli.list_recursive_files(cid, max_depth=4)
                async with SessionLocal() as db:
                    for f in files:
                        try:
                            stem = Path(f["name"]).stem
                            ext = Path(f["name"]).suffix.lower()
                            # 平台识别
                            plat_code = detect_platform_by_ext(ext)
                            if not plat_code:
                                skipped += 1; continue
                            if platform_code and plat_code != platform_code:
                                continue
                            # 按 sha1 去重 (跳过空 sha1 的, 避免批量空 sha1 互相覆盖)
                            if f.get("sha1"):
                                res = await db.execute(
                                    select(Game).where(Game.cloud_source == "115",
                                                        Game.cloud_sha1 == f["sha1"])
                                )
                                if res.scalar_one_or_none():
                                    skipped += 1; continue
                            # 入库
                            res = await db.execute(
                                select(Platform).where(Platform.code == plat_code)
                            )
                            plat = res.scalar_one_or_none()
                            if not plat:
                                skipped += 1; continue
                            parsed = parse_rom_filename(f["name"])
                            g = Game(
                                platform_id=plat.id,
                                rom_path="",  # 本地为空
                                rom_filename=f["name"],
                                rom_size=f["size"],
                                rom_crc="",
                                title_raw=parsed.title,
                                title=parsed.title,
                                cloud_source="115",
                                cloud_pickcode=f["pickcode"],
                                cloud_sha1=f["sha1"],
                                cloud_path=f["path_display"],
                                scrape_status="pending",
                            )
                            db.add(g)
                            added += 1
                        except Exception as e:
                            logger.warning(f"import {f.get('name')} fail: {e}")
                            failed += 1
                    await db.commit()
                    # 更新任务状态
                    task = await db.get(ScanTask, task_id)
                    if task:
                        task.total = len(files)
                        task.processed = len(files)
                        task.success = added
                        task.failed = failed
                        task.status = "done"
                        task.message = f"导入 {added} 个, 跳过 {skipped} 个, 失败 {failed} 个"
                        task.finished_at = datetime.utcnow()
                        await db.commit()
        except Exception as e:
            logger.exception("115 scan fail")
            async with SessionLocal() as db:
                task = await db.get(ScanTask, task_id)
                if task:
                    task.status = "failed"
                    task.message = str(e)
                    task.finished_at = datetime.utcnow()
                    await db.commit()

    async def _after_scan_then_scrape():
        """扫完后, 如果用户选了 auto_scrape, 自动启动刮削任务."""
        if not auto_scrape:
            return
        from .database import SessionLocal as SL, ScanTask as ST
        import asyncio as _aio
        # 等扫描任务完成 (最长 60 秒)
        for _ in range(60):
            async with SL() as db:
                t = await db.get(ST, task_id)
                if not t or t.status in ("done", "failed"):
                    break
            await _aio.sleep(1)
        # 创建一个新的刮削任务
        async with SL() as db2:
            scrape_task = ScanTask(task_type="scrape", target="all")
            db2.add(scrape_task)
            await db2.commit()
            await db2.refresh(scrape_task)
            scrape_task_id = scrape_task.id
        try:
            await scrape_engine.bulk_scrape(scrape_task_id, None, force=False)
        except Exception as e:
            logger.warning(f"auto scrape fail: {e}")

    if auto_scrape:
        bg.add_task(_after_scan_then_scrape)

    bg.add_task(_scan)

    return {"task_id": task_id, "msg": "扫描任务已启动" + (" + 自动刮削" if auto_scrape else "")}


# ------------------- Platforms -------------------
@app.get("/api/platforms")
async def list_platforms(db: AsyncSession = Depends(get_db)):
    res = await db.execute(select(Platform).order_by(Platform.sort_order))
    plats = res.scalars().all()
    # counts per platform
    counts = {}
    cres = await db.execute(
        select(Game.platform_id, func.count(Game.id)).group_by(Game.platform_id)
    )
    for pid, cnt in cres.all():
        counts[pid] = cnt
    out = []
    for p in plats:
        d = {
            "id": p.id, "code": p.code, "name": p.name, "name_en": p.name_en,
            "folder": p.folder, "extensions": p.extensions, "cover": p.cover,
            "description": p.description, "sort_order": p.sort_order,
            "enabled": p.enabled, "game_count": counts.get(p.id, 0),
        }
        out.append(d)
    return out


@app.post("/api/platforms")
async def upsert_platform(payload: dict, user: User = Depends(require_admin),
                          db: AsyncSession = Depends(get_db)):
    code = payload.get("code")
    if not code:
        raise HTTPException(400, "code required")
    res = await db.execute(select(Platform).where(Platform.code == code))
    p = res.scalar_one_or_none()
    if not p:
        p = Platform(code=code)
        db.add(p)
    for k in ["name", "name_en", "folder", "extensions", "cover",
              "description", "sort_order", "enabled"]:
        if k in payload:
            setattr(p, k, payload[k])
    await db.commit()
    return {"ok": True, "id": p.id}


@app.delete("/api/platforms/{pid}")
async def delete_platform(pid: int, user: User = Depends(require_admin),
                          db: AsyncSession = Depends(get_db)):
    p = await db.get(Platform, pid)
    if not p:
        raise HTTPException(404, "not found")
    # also delete its games
    await db.execute(Game.__table__.delete().where(Game.platform_id == pid))
    await db.delete(p)
    await db.commit()
    return {"ok": True}


# ------------------- ROM Library: scan / scrape -------------------
class ScanIn(BaseModel):
    directory: Optional[str] = None


@app.post("/api/library/scan")
async def library_scan(payload: ScanIn = ScanIn(), bg: BackgroundTasks = None,
                       user: User = Depends(require_admin),
                       db: AsyncSession = Depends(get_db)):
    """Scan ROMs directory and create Game entries."""
    async with SessionLocal() as db2:
        task = ScanTask(task_type="scan")
        db2.add(task)
        await db2.commit()
        await db2.refresh(task)
        task_id = task.id

    async def _do():
        async with SessionLocal() as db2:
            task = await db2.get(ScanTask, task_id)
            task.status = "running"
            await db2.commit()
            count = await scrape_engine.scan_roms(
                db2, settings.roms_dir, task)
            task.message = f"新增 {count} 个游戏"
            task.status = "done"
            task.finished_at = datetime.utcnow()
            await db2.commit()
            logger.info(f"Scan done: +{count} games")

    bg.add_task(_do)
    return {"ok": True, "task_id": task_id}


@app.post("/api/library/scrape")
async def library_scrape(payload: dict = {},
                         bg: BackgroundTasks = None,
                         user: User = Depends(require_admin)):
    """Bulk scrape metadata."""
    platform = payload.get("platform")  # None = all
    force = bool(payload.get("force", False))

    async with SessionLocal() as db2:
        task = ScanTask(task_type="scrape", target=platform or "all")
        db2.add(task)
        await db2.commit()
        task_id = task.id

    async def _do():
        await scrape_engine.bulk_scrape(task_id, platform, force=force)

    bg.add_task(_do)
    return {"ok": True, "task_id": task_id}


@app.get("/api/library/tasks")
async def list_tasks(limit: int = 50, db: AsyncSession = Depends(get_db),
                     user: User = Depends(current_user)):
    res = await db.execute(
        select(ScanTask).order_by(ScanTask.created_at.desc()).limit(limit)
    )
    return [
        {
            "id": t.id, "type": t.task_type, "target": t.target,
            "total": t.total, "processed": t.processed,
            "success": t.success, "failed": t.failed,
            "status": t.status, "message": t.message,
            "created_at": t.created_at.isoformat() if t.created_at else None,
            "finished_at": t.finished_at.isoformat() if t.finished_at else None,
        }
        for t in res.scalars().all()
    ]


@app.get("/api/library/tasks/{tid}")
async def get_task(tid: int, db: AsyncSession = Depends(get_db),
                   user: User = Depends(current_user)):
    t = await db.get(ScanTask, tid)
    if not t:
        raise HTTPException(404, "not found")
    return {
        "id": t.id, "type": t.task_type, "target": t.target,
        "total": t.total, "processed": t.processed,
        "success": t.success, "failed": t.failed,
        "status": t.status, "message": t.message,
        "created_at": t.created_at.isoformat() if t.created_at else None,
        "finished_at": t.finished_at.isoformat() if t.finished_at else None,
    }


@app.delete("/api/library/tasks/{tid}")
async def delete_task(tid: int, user: User = Depends(require_admin),
                       db: AsyncSession = Depends(get_db)):
    """单个删除任务记录 (可删已完成的/失败的)。不会中断正在运行的任务。"""
    t = await db.get(ScanTask, tid)
    if not t:
        raise HTTPException(404, "任务不存在")
    if t.status == "running":
        raise HTTPException(400, "正在运行的任务不能删, 请先取消")
    await db.delete(t)
    await db.commit()
    return {"ok": True, "deleted": tid}


@app.post("/api/library/tasks/delete-batch")
async def delete_tasks_batch(payload: dict, user: User = Depends(require_admin),
                              db: AsyncSession = Depends(get_db)):
    """批量删除任务。payload = {"ids": [1,2,3], "all_finished": false}"""
    ids = payload.get("ids") or []
    all_finished = payload.get("all_finished", False)

    q = select(ScanTask)
    if ids:
        q = q.where(ScanTask.id.in_(ids))
    elif all_finished:
        q = q.where(ScanTask.status != "running")
    else:
        raise HTTPException(400, "需要 ids 或 all_finished=true")

    res = await db.execute(q)
    tasks = res.scalars().all()
    deleted = []
    skipped = []
    for t in tasks:
        if t.status == "running":
            skipped.append(t.id)
            continue
        await db.delete(t)
        deleted.append(t.id)
    await db.commit()
    return {"ok": True, "deleted": deleted, "skipped": skipped}


@app.post("/api/library/tasks/clear-finished")
async def clear_finished_tasks(user: User = Depends(require_admin),
                                db: AsyncSession = Depends(get_db)):
    """一键清理所有已完成的扫描/刮掋任务记录。"""
    from sqlalchemy import delete as sql_delete
    res = await db.execute(
        select(ScanTask).where(ScanTask.status != "running")
    )
    tasks = res.scalars().all()
    count = len(tasks)
    for t in tasks:
        await db.delete(t)
    await db.commit()
    return {"ok": True, "deleted": count}


# ------------------- Games -------------------
@app.get("/api/games")
async def list_games(
    platform: Optional[str] = None,
    search: Optional[str] = None,
    status: Optional[str] = None,
    favorite: Optional[bool] = None,
    cloud_source: Optional[str] = None,
    page: int = 1,
    page_size: int = 60,
    sort: str = "title",
    db: AsyncSession = Depends(get_db),
    user: User = Depends(current_user),
):
    q = select(Game)
    if platform:
        res = await db.execute(select(Platform).where(Platform.code == platform))
        p = res.scalar_one_or_none()
        if p:
            q = q.where(Game.platform_id == p.id)
    if search:
        like = f"%{search}%"
        q = q.where(or_(
            Game.title.like(like), Game.title_en.like(like),
            Game.title_raw.like(like), Game.title_zh.like(like),
        ))
    if status:
        q = q.where(Game.scrape_status == status)
    if favorite is not None:
        q = q.where(Game.favorite == favorite)
    if cloud_source is not None:
        if cloud_source == "":
            q = q.where(or_(Game.cloud_source == "", Game.cloud_source.is_(None)))
        else:
            q = q.where(Game.cloud_source == cloud_source)

    sort_col = {
        "title": Game.title, "title_en": Game.title_en, "title_zh": Game.title_zh,
        "created": Game.created_at, "played": Game.last_played,
        "rating": Game.rating, "play_count": Game.play_count,
    }.get(sort, Game.title)
    q = q.order_by(sort_col.desc() if sort == "created" else sort_col.asc())

    total_q = q.with_only_columns(func.count(Game.id))
    total = (await db.execute(total_q)).scalar() or 0

    q = q.offset((page - 1) * page_size).limit(page_size)
    games = (await db.execute(q)).scalars().all()

    return {
        "total": total, "page": page, "page_size": page_size,
        "items": [_game_to_dict(g) for g in games],
    }


@app.get("/api/games/{gid}")
async def get_game(gid: int, db: AsyncSession = Depends(get_db),
                   user: User = Depends(current_user)):
    g = await db.get(Game, gid)
    if not g:
        raise HTTPException(404, "not found")
    return _game_to_dict(g, full=True)


@app.post("/api/games/{gid}/favorite")
async def toggle_fav(gid: int, db: AsyncSession = Depends(get_db),
                     user: User = Depends(current_user)):
    g = await db.get(Game, gid)
    if not g:
        raise HTTPException(404, "not found")
    g.favorite = not g.favorite
    await db.commit()
    return {"favorite": g.favorite}


@app.post("/api/games/{gid}/cover")
async def upload_cover(gid: int, file: UploadFile = File(...),
                       db: AsyncSession = Depends(get_db),
                       user: User = Depends(require_admin)):
    """上传/替换游戏封面. 支持 jpg/png/webp. 自动覆盖现有封面."""
    g = await db.get(Game, gid)
    if not g:
        raise HTTPException(404, "not found")
    if not file.content_type or not file.content_type.startswith("image/"):
        raise HTTPException(400, "只支持图片文件 (jpg/png/webp)")
    # 决定扩展名
    ext_map = {"image/jpeg": "jpg", "image/jpg": "jpg", "image/png": "png",
               "image/webp": "webp"}
    ext = ext_map.get(file.content_type)
    if not ext:
        # 从文件名猜
        fn = file.filename or ""
        if fn.lower().endswith(".png"):
            ext = "png"
        elif fn.lower().endswith(".webp"):
            ext = "webp"
        else:
            ext = "jpg"
    # 限制大小 5MB
    raw = await file.read()
    if len(raw) > 5 * 1024 * 1024:
        raise HTTPException(400, "封面太大 (max 5MB)")
    media_dir = settings.media_dir / str(g.id)
    media_dir.mkdir(parents=True, exist_ok=True)
    # 覆盖现有 cover (jpg/png/webp 都删)
    for old_ext in ("jpg", "png", "webp"):
        old_path = media_dir / f"cover.{old_ext}"
        if old_path.exists():
            old_path.unlink()
    cover_path = media_dir / f"cover.{ext}"
    async with aiofiles.open(cover_path, "wb") as f:
        await f.write(raw)
    g.cover_path = f"media/{g.id}/cover.{ext}"
    # 记录上传来源
    extra = {**(g.extra or {}), "cover_source": "user_upload",
             "cover_uploaded_at": datetime.utcnow().isoformat()}
    g.extra = extra
    await db.commit()
    return {"ok": True, "cover_path": g.cover_path, "url": f"/api/media/{g.cover_path}"}


@app.delete("/api/games/{gid}/cover")
async def delete_cover(gid: int, db: AsyncSession = Depends(get_db),
                       user: User = Depends(require_admin)):
    """删除封面, 恢复空白状态."""
    g = await db.get(Game, gid)
    if not g:
        raise HTTPException(404, "not found")
    media_dir = settings.media_dir / str(g.id)
    for ext in ("jpg", "png", "webp"):
        p = media_dir / f"cover.{ext}"
        if p.exists():
            p.unlink()
    g.cover_path = ""
    await db.commit()
    return {"ok": True}


@app.patch("/api/games/{gid}")
async def update_game(gid: int, payload: dict, db: AsyncSession = Depends(get_db),
                      user: User = Depends(require_admin)):
    g = await db.get(Game, gid)
    if not g:
        raise HTTPException(404, "not found")
    for k in ["title", "title_en", "title_zh", "title_jp", "description",
              "release_date", "developer", "publisher", "genre", "rating"]:
        if k in payload:
            setattr(g, k, payload[k])
    await db.commit()
    return {"ok": True}


@app.delete("/api/games/{gid}")
async def delete_game(gid: int, db: AsyncSession = Depends(get_db),
                      user: User = Depends(require_admin)):
    g = await db.get(Game, gid)
    if not g:
        raise HTTPException(404, "not found")
    # remove media files
    if g.cover_path:
        try:
            (DATA_DIR_FULL / g.cover_path).unlink(missing_ok=True)
        except Exception:
            pass
    await db.delete(g)
    await db.commit()
    return {"ok": True}


@app.post("/api/games/{gid}/rescrape")
async def rescrape_one(gid: int, bg: BackgroundTasks,
                       db: AsyncSession = Depends(get_db),
                       user: User = Depends(require_admin)):
    g = await db.get(Game, gid)
    if not g:
        raise HTTPException(404, "not found")
    g.scrape_status = "pending"
    await db.commit()

    async def _do():
        async with SessionLocal() as db2:
            g2 = await db2.get(Game, gid)
            await scrape_engine.scrape_game(db2, g2, force=True)
    bg.add_task(_do)
    return {"ok": True}


@app.post("/api/games/{gid}/scrape-custom")
async def scrape_custom(gid: int, payload: dict, bg: BackgroundTasks,
                        db: AsyncSession = Depends(get_db),
                        user: User = Depends(require_admin)):
    """用用户输入的自定义名字重新刮削. 用户输什么名字可选, 用作 ScreenScraper romnom/AI filename."""
    custom_name = (payload.get("custom_name") or "").strip()
    if not custom_name:
        raise HTTPException(400, "custom_name 不能为空")
    if len(custom_name) > 200:
        raise HTTPException(400, "custom_name 太长 (max 200)")
    g = await db.get(Game, gid)
    if not g:
        raise HTTPException(404, "not found")
    g.scrape_status = "pending"
    g.extra = {**(g.extra or {}), "pending_custom_name": custom_name}
    await db.commit()

    async def _do():
        async with SessionLocal() as db2:
            g2 = await db2.get(Game, gid)
            await scrape_engine.scrape_game(db2, g2, force=True, custom_name=custom_name)
    bg.add_task(_do)
    return {"ok": True, "msg": f"已用名字 “{custom_name}” 启动刮削"}


@app.post("/api/games/{gid}/scrape-search")
async def scrape_search(gid: int, payload: dict,
                        db: AsyncSession = Depends(get_db),
                        user: User = Depends(require_admin)):
    """按用户输入的名字去掳源搜一次, 返回命中候选的 metadata (不写入数据库).
    前端弹出子画面让用户点选后, 再调 /scrape-apply 应用."""
    custom_name = (payload.get("custom_name") or "").strip()
    if not custom_name:
        raise HTTPException(400, "custom_name 不能为空")
    if len(custom_name) > 200:
        raise HTTPException(400, "custom_name 太长 (max 200)")
    g = await db.get(Game, gid)
    if not g:
        raise HTTPException(404, "not found")

    # 复用 scrape_game 的 fallback chain: builtin → libretro → SS ↔ AI
    # 与默认顺序一致 (SS → AI), 但因为给了 custom_name 会跳过 builtin/libretro.
    # prefer_ai=True: 如果用户开了 AI, AI 优先于 SS (用户对 AI 更信任 / 适合中文名字).
    ss_client = None
    if settings.screenscraper_user:
        from .scraper import ScreenScraperClient
        ss_client = ScreenScraperClient(
            settings.screenscraper_user, settings.screenscraper_pass,
            devid=settings.screenscraper_devid,
            devpassword=settings.screenscraper_devpass,
        )

    metadata = None
    source = None
    try:
        if ss_client:
            await ss_client.__aenter__()
        metadata, source = await scrape_engine._collect_metadata(
            game=g,
            ss_client=ss_client,
            custom_name=custom_name,
            prefer_ai=True,  # AI 启用时 AI 优先
        )
    finally:
        if ss_client:
            await ss_client.__aexit__(None, None, None)

    if not metadata:
        # 描述实际尝试过的源, 便于用户调试
        tried = []
        if scrape_engine.ai.enabled and source is None:
            tried.append("AI")
        if settings.screenscraper_user and source is None:
            tried.append("ScreenScraper")
        tried_str = " → ".join(tried) if tried else "无可用刮削源"
        raise HTTPException(404, f"未找到匹配 ({tried_str} 里都没有 “{custom_name}”)")

    # AI 识别后可能不带封面, 额外去 ScreenScraper 找封面
    # (只在 SS 未参与本次搜索或 SS 未返回封面时执行)
    if not metadata.get("cover_url") and ss_client:
        try:
            cover_query = metadata.get("title_en") or custom_name
            ss_cover = await ss_client.get_game_info(
                platform_code=g.platform.code if g.platform else "",
                rom_filename=cover_query,
                rom_size=g.rom_size or 0,
            )
            if ss_cover and ss_cover.get("cover_url"):
                metadata["cover_url"] = ss_cover["cover_url"]
                logger.info(f"补搜封面: {custom_name} → {ss_cover['cover_url']}")
        except Exception as e:
            logger.warning(f"封面补搜失败: {e}")

    # 下裁封面给前端预览
    cover_url = metadata.get("cover_url") or ""
    if cover_url:
        try:
            proxies = None
            if settings.proxy_enabled and settings.proxy_url:
                proxies = {"http://": settings.proxy_url, "https://": settings.proxy_url}
            async with httpx.AsyncClient(timeout=30.0, proxies=proxies) as http:
                r = await http.get(cover_url)
                if r.status_code == 200:
                    metadata["_cover_b64"] = base64.b64encode(r.content).decode()[:200000]
                    metadata["_cover_ext"] = "jpg"
        except Exception:
            pass

    # 补全中文译名/描述: 创库或 AI 没给中文时, 用 AI 或 google 翻译补上
    # 目的是让前端预览 modal 立刻看到中文, 不是空着.
    if scrape_engine.ai.enabled or True:  # 总试译, 即使 AI 未启用也用 google
        try:
            from .translator import translate as _tr
            if not metadata.get("title_zh") and metadata.get("title_en"):
                metadata["title_zh"] = await _tr(
                    metadata["title_en"],
                    ai_client=scrape_engine.ai.client if scrape_engine.ai.enabled else None,
                    ai_model=scrape_engine.ai.model,
                )
            if not metadata.get("description_zh") and metadata.get("description_en"):
                metadata["description_zh"] = await _tr(
                    metadata["description_en"],
                    ai_client=scrape_engine.ai.client if scrape_engine.ai.enabled else None,
                    ai_model=scrape_engine.ai.model,
                )
        except Exception as e:
            logger.warning(f"补全中文译名/描述失败: {e}")

    return {
        "ok": True,
        "source": source,
        "custom_name": custom_name,
        "metadata": metadata,
    }


@app.post("/api/games/{gid}/scrape-apply")
async def scrape_apply(gid: int, payload: dict,
                       db: AsyncSession = Depends(get_db),
                       user: User = Depends(require_admin)):
    """把 scrape-search 返回的 metadata 直接同步应用到 game, 覆盖现有元数据.
    返回 200 时数据库已提交, 前端可立刻 get /api/games/{gid} 拿新字段."""
    metadata = payload.get("metadata")
    if not metadata or not isinstance(metadata, dict):
        raise HTTPException(400, "metadata 缺失")
    source = payload.get("source", "custom")
    g = await db.get(Game, gid)
    if not g:
        raise HTTPException(404, "not found")
    # 同步应用, 不再用 background task (避免前端 load() 拿不到最新数据)
    ok = await scrape_engine.apply_metadata(db, g, metadata, source=source)
    if not ok:
        raise HTTPException(500, "应用失败")
    return {"ok": True, "msg": "已应用该刮削结果"}


@app.post("/api/games/upload")
async def upload_roms(
    platform: str,
    files: list[UploadFile] = File(...),
    user: User = Depends(require_admin),
    db: AsyncSession = Depends(get_db),
):
    """Upload one or more ROM files. They will be saved under
    /data/roms/<platform_folder>/<original_filename>.

    Query param `platform` is the platform code (e.g. FC, PS1).
    The upload records progress to the latest scan task."""
    res = await db.execute(select(Platform).where(Platform.code == platform))
    plat = res.scalar_one_or_none()
    if not plat:
        raise HTTPException(400, f"未知平台: {platform}")

    target_dir = settings.roms_dir / plat.folder
    target_dir.mkdir(parents=True, exist_ok=True)

    # 跨平台压缩包: 把同一压缩包不同入口的扩展名补充到识别白名单
    archive_exts = {".zip", ".7z", ".rar"}
    # 光盘类格式: 多个平台都可能用 (cue/bin/img/chd)
    disc_exts = {".bin", ".img", ".cue", ".iso", ".chd"}

    saved = []
    failed = []
    for f in files:
        fname = Path(f.filename).name  # 去掉目录部分
        # 安全文件名: 移除路径分隔符
        safe = fname.replace("/", "_").replace("\\", "_")
        if not safe or safe.startswith("."):
            failed.append({"name": fname, "error": "非法文件名"})
            continue
        ext = Path(safe).suffix.lower()
        plat_exts = {e.strip().lower() for e in plat.extensions.split(",") if e.strip()}
        # 允许: 平台自身扩展名 + 压缩包 + 光盘镜像通用扩展
        if ext not in archive_exts and ext not in disc_exts and ext.lstrip(".") not in plat_exts:
            failed.append({"name": safe, "error": f"扩展名 {ext} 不属于 {plat.code}"})
            continue
        dest = target_dir / safe
        # 同名冲突: 加 _N 后缀
        if dest.exists():
            i = 1
            stem = dest.stem
            while True:
                cand = target_dir / f"{stem}_{i}{ext}"
                if not cand.exists():
                    dest = cand
                    break
                i += 1
        try:
            async with aiofiles.open(dest, "wb") as out:
                while True:
                    chunk = await f.read(1024 * 256)
                    if not chunk:
                        break
                    await out.write(chunk)
            size = dest.stat().st_size
            saved.append({"name": dest.name, "size": size, "platform": plat.code})
        except Exception as e:
            logger.exception(f"Upload failed for {safe}")
            failed.append({"name": safe, "error": str(e)})

    # 上传完后自动触发扫描入库 (后台)
    if saved:
        async with SessionLocal() as db2:
            task = ScanTask(task_type="scan", target=plat.code,
                            total=len(saved), processed=0,
                            status="running", message=f"已上传 {len(saved)} 个文件")
            db2.add(task)
            await db2.commit()
            await db2.refresh(task)
            task_id = task.id

        async def _do():
            async with SessionLocal() as db2:
                task = await db2.get(ScanTask, task_id)
                count = await scrape_engine.scan_roms(db2, settings.roms_dir, task)
                task.message = f"上传 {len(saved)} + 扫描新增 {count} 个游戏"
                task.status = "done"
                task.finished_at = datetime.utcnow()
                await db2.commit()

        # 直接用 asyncio task 避免 BackgroundTasks 作用域问题
        asyncio.create_task(_do())

    return {
        "ok": True,
        "saved": saved,
        "failed": failed,
        "platform": plat.code,
        "saved_count": len(saved),
        "failed_count": len(failed),
    }


def _game_to_dict(g: Game, full: bool = False) -> dict:
    plat = g.platform
    return {
        "id": g.id,
        "platform": {"id": plat.id, "code": plat.code, "name": plat.name} if plat else None,
        "rom_filename": g.rom_filename,
        "rom_size": g.rom_size,
        "title": g.title, "title_en": g.title_en,
        "title_zh": g.title_zh, "title_jp": g.title_jp, "title_raw": g.title_raw,
        "description": g.description if full else (g.description[:200] + "..." if len(g.description) > 200 else g.description),
        "release_date": g.release_date,
        "developer": g.developer, "publisher": g.publisher,
        "genre": g.genre, "players": g.players, "rating": g.rating,
        "cover": f"/api/media/{g.cover_path}" if g.cover_path else "",
        "screenshots": [f"/api/media/{p}" for p in json.loads(g.screenshot_paths or "[]")],
        "logo": f"/api/media/{g.logo_path}" if g.logo_path else "",
        "video": f"/api/media/{g.video_path}" if g.video_path else "",
        # 云盘源
        "cloud_source": g.cloud_source or "",
        "cloud_path": g.cloud_path or "",
        "local_available": bool(g.rom_path and (settings.roms_dir / g.rom_path).exists()),
        "scrape_status": g.scrape_status,
        "scrape_source": g.scrape_source,
        "favorite": g.favorite, "play_count": g.play_count,
        "last_played": g.last_played.isoformat() if g.last_played else None,
        "extra": g.extra or {},
    }


# ------------------- Media -------------------
@app.get("/api/media/{path:path}")
async def serve_media(path: str, request: Request, db: AsyncSession = Depends(get_db)):
    """Serve local media files (cover / screenshots / logo / video).

    任何人都可以访问 (就像游戏封面 CDN 一样), 这样 <img> 标签可以直接使用。
    路径是 DATA_DIR 相对的 (避免越权访问)。
    """
    full = DATA_DIR_FULL / path
    full = full.resolve()
    # 防越权: 必须落在 DATA_DIR 下面
    if not str(full).startswith(str(DATA_DIR_FULL.resolve())):
        raise HTTPException(403, "forbidden")
    if not full.exists() or not full.is_file():
        raise HTTPException(404, "not found")
    return FileResponse(full)


# ------------------- Play / Stream / Download -------------------
@app.post("/api/games/{gid}/play/local")
async def play_local(gid: int, client: str = "windows",
                     emulator_override: str = "",
                     user: User = Depends(current_user),
                     db: AsyncSession = Depends(get_db)):
    """Return a download URL + launch command for client-side play."""
    g = await db.get(Game, gid)
    if not g:
        raise HTTPException(404, "not found")
    plat = g.platform
    if client == "android":
        launch = android_launch_intent(plat.code, str(Path(g.rom_filename)),
                                       emulator_override)
    else:
        launch = windows_launch_cmd(plat.code, str(Path(g.rom_filename)),
                                    emulator_override)
    if not launch.get("ok"):
        raise HTTPException(400, launch.get("error"))
    # record session
    sess = PlaySession(user_id=user.id, game_id=g.id, mode="local")
    db.add(sess)
    g.play_count += 1
    g.last_played = datetime.utcnow()
    await db.commit()
    return {
        "session_id": sess.id,
        "download_url": f"/api/games/{gid}/rom",
        **launch,
    }


@app.post("/api/games/{gid}/play/stream")
async def play_stream(gid: int, bg: BackgroundTasks,
                      user: User = Depends(current_user),
                      db: AsyncSession = Depends(get_db)):
    """Allocate a server-side streaming session."""
    g = await db.get(Game, gid)
    if not g:
        raise HTTPException(404, "not found")
    sid = f"{user.id}-{g.id}-{int(datetime.utcnow().timestamp())}"
    rom = settings.roms_dir / g.rom_path
    sess = stream_mgr.create(sid, user.id, g.id, rom, g.platform.code, mode="stream")
    pdb = PlaySession(user_id=user.id, game_id=g.id, mode="stream")
    db.add(pdb)
    g.play_count += 1
    g.last_played = datetime.utcnow()
    await db.commit()
    return {
        "session_id": sid,
        "ws_control": f"/api/stream/{sid}/control",
        "ws_video": f"/api/stream/{sid}/video",
    }


@app.post("/api/stream/{sid}/stop")
async def stream_stop(sid: str, user: User = Depends(current_user)):
    await stream_mgr.stop(sid)
    return {"ok": True}


@app.get("/api/games/{gid}/rom")
async def download_rom(gid: int,
                       db: AsyncSession = Depends(get_db),
                       user: User = Depends(current_user),
                       request: Request = None):
    """获取 ROM 数据。

    1. 本地有 ROM -> 直接流式返回
    2. 本地无 + 115 集成 -> 302 重定向到 CDN (legacy 兼容)
    3. 都无 -> 404
    """
    g = await db.get(Game, gid)
    if not g:
        raise HTTPException(404, "game not found")
    # 关键: rom_path 为空或路径不存在都不能当本地 — 避免 115 入库的 game 被误判为 local
    full = settings.roms_dir / g.rom_path if g.rom_path else None

    # 本地存在 (必须是文件, 不是目录) -> 直接下载
    if full is not None and full.is_file():
        fname = g.rom_filename

        async def iterfile():
            async with aiofiles.open(full, "rb") as f:
                while True:
                    chunk = await f.read(64 * 1024)
                    if not chunk:
                        break
                    yield chunk

        return StreamingResponse(
            iterfile(),
            media_type="application/octet-stream",
            headers={"Content-Disposition": f'attachment; filename="{fname}"',
                     "Content-Length": str(g.rom_size)},
        )

    # 本地无 -> 查云盘
    if g.cloud_source == "115" and g.cloud_pickcode:
        if not settings.cloud_115_enabled or not settings.cloud_115_cookie:
            raise HTTPException(404, "本地无 ROM, 且 115 云盘未启用")
        from .cloud_115 import Pan115Client
        proxy = ""  # 115 强制直连, 不用全局代理
        try:
            async with Pan115Client(settings.cloud_115_cookie, proxy_url=proxy) as cli:
                url = await cli.get_download_url(g.cloud_pickcode)
            # 返回 302 重定向 (兼容旧版 client)
            return RedirectResponse(url, status_code=302,
                                    headers={"Content-Disposition":
                                             f'attachment; filename="{g.rom_filename}"'})
        except Exception as e:
            logger.error(f"115 download fail: {e}")
            raise HTTPException(502, f"云盘下载失败: {e}")

    raise HTTPException(404, "rom not found (本地和 115 都不可用)")


@app.get("/api/games/{gid}/rom-info")
async def get_rom_info(gid: int,
                       db: AsyncSession = Depends(get_db),
                       user: User = Depends(current_user)):
    """
    获取 ROM 下载元信息 (JSON, 不返回 binary).
    - 本地有 ROM → source="local", 客户端走 /api/games/{id}/rom 拿 stream
    - 本地无 + 115 集成 → source="remote_115" + url, 客户端用裸 OkHttp 下载 (避免 115 CDN 拒签)

    为什么不用 /rom 直接返回 binary:
    - 服务端 302 → 客户端 OkHttp 跟 redirect 时会保留 Authorization 头,
      115 CDN 不认我们 NAS token, 返回 401
    - 客户端拿到 JSON url 后用裸 client 下载, 不带任何 NAS auth header
    """
    g = await db.get(Game, gid)
    if not g:
        raise HTTPException(404, "game not found")
    # 关键: rom_path 为空 (115 入库不下载) 不能当成本地路径, 避免误判成目录
    if g.rom_path and not (settings.roms_dir / g.rom_path).is_file():
        # rom_path 写了但文件不存在 — 当作本地不存在, 走云盘
        pass
    has_local = bool(g.rom_path) and (settings.roms_dir / g.rom_path).is_file()

    if has_local:
        return {
            "source": "local",
            "url": f"/api/games/{gid}/rom",
            "filename": g.rom_filename,
            "size": g.rom_size,
            "pickcode": g.cloud_pickcode or "",
        }

    if g.cloud_source == "115" and g.cloud_pickcode:
        if not settings.cloud_115_enabled or not settings.cloud_115_cookie:
            raise HTTPException(404, "本地无 ROM, 且 115 云盘未启用")
        from .cloud_115 import Pan115Client
        proxy = ""
        try:
            async with Pan115Client(settings.cloud_115_cookie, proxy_url=proxy) as cli:
                url = await cli.get_download_url(g.cloud_pickcode)
            return {
                "source": "remote_115",
                "url": url,
                "filename": g.rom_filename,
                "size": g.rom_size,
                "pickcode": g.cloud_pickcode,
            }
        except Exception as e:
            logger.error(f"115 info fail: {e}")
            raise HTTPException(502, f"云盘查询失败: {e}")

    raise HTTPException(404, "rom not found (本地和 115 都不可用)")


# ------------------- Stream websocket -------------------
from fastapi import WebSocket, WebSocketDisconnect

@app.websocket("/api/stream/{sid}/control")
async def ws_control(ws: WebSocket, sid: str):
    await ws.accept()
    s = stream_mgr.sessions.get(sid)
    if not s:
        await ws.close(code=4404)
        return
    s.control_ws = ws
    try:
        while True:
            msg = await ws.receive_json()
            await s.handle_control(msg)
    except WebSocketDisconnect:
        pass


@app.websocket("/api/stream/{sid}/video")
async def ws_video(ws: WebSocket, sid: str):
    await ws.accept()
    s = stream_mgr.sessions.get(sid)
    if not s:
        await ws.close(code=4404)
        return
    s.video_ws = ws
    try:
        # stream runs in background via s.ffmpeg; just wait until disconnect
        while True:
            await ws.receive_text()
    except WebSocketDisconnect:
        pass
    finally:
        await stream_mgr.stop(sid)


# ------------------- Stats -------------------
@app.get("/api/stats")
async def stats(db: AsyncSession = Depends(get_db),
                user: User = Depends(current_user)):
    res = await db.execute(func.count(Game.id))
    total_games = res.scalar() or 0
    res = await db.execute(func.count(Platform.id))
    total_platforms = res.scalar() or 0
    res = await db.execute(
        select(Game.scrape_status, func.count(Game.id)).group_by(Game.scrape_status)
    )
    by_status = {s: c for s, c in res.all()}
    res = await db.execute(
        select(Platform.code, func.count(Game.id))
        .join(Game, Game.platform_id == Platform.id)
        .group_by(Platform.code, Platform.sort_order)
        .order_by(Platform.sort_order)
    )
    by_platform = [{"platform": c, "count": n} for c, n in res.all()]
    return {
        "total_games": total_games,
        "total_platforms": total_platforms,
        "by_status": by_status,
        "by_platform": by_platform,
    }


# expose data_dir_full for use elsewhere via module-level constant
import os as _os
from pathlib import Path as _Path
DATA_DIR_FULL = _Path(_os.environ.get("NASGAME_DATA_DIR", "/data"))


# ------------------- ROM Directory Browser & Import -------------------
@app.get("/api/library/browse")
async def browse_roms(
    path: str = "",
    user: User = Depends(current_user),
    db: AsyncSession = Depends(get_db),
):
    """Browse the ROMs directory tree. Returns one level of subdirectories/files.

    Each item: {name, is_dir, size?, path (relative to roms_dir),
                 platform_code?, recognized?}

    `recognized` = True if folder name matches a platform folder.
    """
    roms_root = settings.roms_dir
    # 安全: 路径不能越界
    rel = path.strip().strip("/")
    if rel:
        target = (roms_root / rel).resolve()
    else:
        target = roms_root.resolve()
    if not str(target).startswith(str(roms_root.resolve())):
        raise HTTPException(400, "非法路径")
    if not target.exists() or not target.is_dir():
        raise HTTPException(404, "目录不存在")

    # 平台目录对照
    res = await db.execute(select(Platform))
    platforms = res.scalars().all()
    plat_by_folder = {p.folder.lower(): p for p in platforms}
    plat_by_ext = {}
    for p in platforms:
        for ext in p.extensions.split(","):
            ext = ext.strip().lower()
            if ext:
                plat_by_ext.setdefault(ext, []).append(p)

    items = []
    try:
        for child in sorted(target.iterdir(), key=lambda x: (not x.is_dir(), x.name.lower())):
            if child.name.startswith("."):
                continue
            rel_path = str(child.relative_to(roms_root))
            if child.is_dir():
                plat = plat_by_folder.get(child.name.lower())
                items.append({
                    "name": child.name,
                    "path": rel_path,
                    "is_dir": True,
                    "platform_code": plat.code if plat else None,
                    "recognized": plat is not None,
                })
            else:
                items.append({
                    "name": child.name,
                    "path": rel_path,
                    "is_dir": False,
                    "size": child.stat().st_size,
                })
    except PermissionError:
        raise HTTPException(403, "无权限访问此目录")

    return {
        "path": rel,
        "items": items,
    }


@app.post("/api/games/import-existing")
async def import_existing_roms(
    payload: dict,
    bg: BackgroundTasks = None,
    user: User = Depends(require_admin),
    db: AsyncSession = Depends(get_db),
):
    """把已存在的 ROM 目录/文件导入游戏库。

    payload = {
        "path": "fc/mario.nes"   # 相对 roms_dir 的路径
        "type": "dir" | "file"   # dir = 整个目录(递归), file = 单个文件
    }
    """
    rel_path = payload.get("path", "").strip().strip("/")
    ptype = payload.get("type", "dir")
    if not rel_path:
        raise HTTPException(400, "path required")
    target = (settings.roms_dir / rel_path).resolve()
    if not str(target).startswith(str(settings.roms_dir.resolve())):
        raise HTTPException(400, "非法路径")
    if not target.exists():
        raise HTTPException(404, "路径不存在")

    # 创建任务, 后台跑扫描
    async with SessionLocal() as db2:
        task = ScanTask(task_type="scan", target=rel_path,
                        status="running",
                        message=f"导入 {rel_path} ({ptype})")
        db2.add(task)
        await db2.commit()
        await db2.refresh(task)
        task_id = task.id

    async def _do():
        async with SessionLocal() as db2:
            task = await db2.get(ScanTask, task_id)
            count = await scrape_engine.scan_roms(db2, settings.roms_dir, task)
            task.message = f"导入 {rel_path}: 新增 {count} 个游戏"
            task.status = "done"
            task.finished_at = datetime.utcnow()
            await db2.commit()

    asyncio.create_task(_do())
    return {"ok": True, "task_id": task_id, "path": rel_path, "type": ptype}
