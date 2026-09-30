"""115 网盘 二维码扫码登录.

协议 (从 115 网站 reverse-engineered, 公开文档化):
  1. GET  https://qrcode.115.com/api/1.0/web/1.0/token
       -> {"state":1, "data":{uid, time, sign, qrcode}}
  2. 用户用 115 App 扫描 https://115.com/scan/dg-{uid}
  3. 轮询 GET https://qrcode.115.com/api/1.0/web/1.0/status?uid=...&time=...&sign=...
       返回:
         code 90038 -> 未扫码 (waiting)
         code 90039 -> 已扫码未确认 (scanned) [可选状态]
         code 0 / 其它 -> 已确认 (login_success), data.cookies 是真正的 cookie 串
"""
from __future__ import annotations
import asyncio
import io
import json
import re
import time
from dataclasses import dataclass, field
from typing import Optional
import httpx
import qrcode
from qrcode.image.pil import PilImage
from loguru import logger

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/120.0.0.0 Safari/537.36"
)

QR_TOKEN_URL = "https://qrcodeapi.115.com/api/1.0/web/1.0/token"
QR_STATUS_URL = "https://qrcodeapi.115.com/get/status/"
QR_LOGIN_URL = "https://passportapi.115.com/app/1.0/web/1.0/login/qrcode"
QRCODE_EXPIRED_CODE = 10009  # qrcode expired


def _is_status_expired(j: dict) -> bool:
    """115 status 接口: code 10009 表示过期"""
    return j.get("code") == QRCODE_EXPIRED or j.get("state") == 0 and j.get("code") in (10009,)


def _is_status_confirmed(j: dict) -> bool:
    """115 status 接口: data.status == 2 表示已扫码确认"""
    data = j.get("data") or {}
    if not data:
        return False
    return int(data.get("status", 0)) == 2


def _parse_cookie_str(s: str) -> dict[str, str]:
    """把 'UID=xx; CID=yy; SEID=zz' 解析成 dict"""
    out = {}
    for part in re.split(r"[;\n]+", s or ""):
        part = part.strip()
        if not part or "=" not in part:
            continue
        k, v = part.split("=", 1)
        out[k.strip()] = v.strip()
    return out


async def _fetch_cookies_via_login(client: httpx.AsyncClient, sess: "QRSession") -> str:
    """如果 /status 返回的 data 里没 cookies, 试调 /login/qrcode 取。

    不同版本 115 接口可能位置不同: 这是一个 fallback。
    返回 cookie 串 (UID=xx; CID=yy; SEID=zz) 或空字符串。
    """
    try:
        # 1) 试 passportapi 的 /login/qrcode
        r = await client.post(QR_LOGIN_URL, data={
            "uid": sess.uid,
            "time": str(sess.time),
            "sign": sess.sign,
        })
        j = r.json()
        logger.info(f"115 login/qrcode resp: {json.dumps(j, ensure_ascii=False)[:300]}")
        # 可能直接返回 cookie 在 data 中
        data = j.get("data") or {}
        for k in ("cookie", "cookies", "cookie_string"):
            v = data.get(k)
            if v:
                if isinstance(v, dict):
                    return "; ".join(f"{a}={b}" for a, b in v.items())
                elif isinstance(v, list):
                    return "; ".join(f"{c.get('name','')}={c.get('value','')}" for c in v if c.get("name"))
                return str(v)
        # 2) 试直接调 /status 接口取 Set-Cookie
        # Set-Cookie 已经在 client 内部，理论上本次状态 cookie 是 115 返回的
        # 在 httpx 中 Set-Cookie 会被 client.cookies 收集
        # 试提取整个 client.cookies
        if client.cookies:
            cookies_dict = {n: v for n, v in client.cookies.items()}
            logger.info(f"115 client.cookies after status=0: {list(cookies_dict.keys())[:10]}")
            return "; ".join(f"{n}={v}" for n, v in cookies_dict.items())
    except Exception as e:
        logger.warning(f"115 login/qrcode fallback fail: {e}")
    return ""


@dataclass
class QRSession:
    uid: str
    time: int
    sign: str
    qrcode_url: str        # https://115.com/scan/dg-{uid}
    created_at: float = field(default_factory=time.time)
    last_status: int = 0   # 0=waiting, 1=scanned, 2=confirmed, -1=expired
    cookies: Optional[dict] = None
    user_id: Optional[str] = None
    user_name: Optional[str] = None


# 全局会话存储 (内存, 进程重启会丢)
_SESSIONS: dict[str, QRSession] = {}


def _make_qr_png(content: str) -> bytes:
    """生成二维码 PNG bytes."""
    qr = qrcode.QRCode(version=1, box_size=8, border=2,
                       error_correction=qrcode.constants.ERROR_CORRECT_M)
    qr.add_data(content)
    qr.make(fit=True)
    img = qr.make_image(fill_color="black", back_color="white", image_factory=PilImage)
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return buf.getvalue()


async def create_session(client: httpx.AsyncClient) -> QRSession:
    """创建一个新的扫码登录会话."""
    r = await client.get(QR_TOKEN_URL)
    r.raise_for_status()
    j = r.json()
    if not j.get("state"):
        raise RuntimeError(f"115 返回失败: {j}")
    d = j["data"]
    sess = QRSession(
        uid=d["uid"],
        time=int(d["time"]),
        sign=d["sign"],
        qrcode_url=d["qrcode"],
    )
    _SESSIONS[sess.uid] = sess
    return sess


async def poll_status(client: httpx.AsyncClient, sess: QRSession) -> QRSession:
    """轮询状态, 更新 sess.last_status / sess.cookies.

    实际 115 协议 (参考 py115 官方 SDK):
    1. GET https://qrcodeapi.115.com/get/status/?uid=...&time=...&sign=...&_=t
         返回 {"state":1, "data":{"status": N}}  N=0未扫/1已扫/2确认
         过期: {"state":0, "code":10009}
    2. 当 status==2 后, POST https://passportapi.115.com/app/1.0/web/1.0/login/qrcode
         form: account=uid&app=web
         返回 {"state":1, "data":{"user_id":..., "cookie":"UID=...; CID=...; SEID=..."}}
    """
    # 1. GET status
    r = await client.get(QR_STATUS_URL, params={
        "uid": sess.uid,
        "time": str(sess.time),
        "sign": sess.sign,
        "_": str(int(time.time() * 1000)),
    })
    j = r.json()
    logger.debug(f"115 QR status resp: {json.dumps(j, ensure_ascii=False)[:300]}")

    # 过期检测
    if j.get("state") == 0 and j.get("code") == 10009:
        sess.last_status = -1  # expired
        return sess

    if not j.get("state"):
        return sess  # 未就绪

    data = j.get("data") or {}
    status = int(data.get("status", 0))

    if status == 2:
        # 2. 已确认, 调 /login 拿 cookie
        sess.last_status = 2
        try:
            r2 = await client.post(QR_LOGIN_URL, data={"account": sess.uid, "app": "web"})
            j2 = r2.json()
            logger.info(f"115 QR login/qrcode resp: {json.dumps(j2, ensure_ascii=False)[:500]}")
            if j2.get("state") and j2.get("data"):
                ck = j2["data"].get("cookie")
                if isinstance(ck, dict) and ck:
                    # 115 实际返回 cookie 就是一个 dict: {"UID": "...", "CID": "...", "SEID": "...", ...}
                    sess.cookies = {str(k): str(v) for k, v in ck.items() if v}
                elif isinstance(ck, str) and ck:
                    sess.cookies = _parse_cookie_str(ck)
                else:
                    sess.cookies = {}
                sess.user_id = j2["data"].get("user_id")
                sess.user_name = j2["data"].get("user_name")
                if sess.cookies:
                    logger.info(f"115 QR via login, cookies keys: {list(sess.cookies.keys())[:10]}")
                else:
                    logger.warning(f"115 QR login: no cookies in response, data cookie type={type(ck).__name__}")
            else:
                sess.cookies = {}
        except Exception as e:
            logger.exception(f"115 login/qrcode error: {e}")
            sess.cookies = {}
    elif status == 1:
        sess.last_status = 1
    elif status == 0:
        sess.last_status = 0
    else:
        logger.warning(f"115 QR unknown status: {status}")
    return sess


def cookies_to_string(cookies: dict) -> str:
    """dict cookies -> 'UID=...; CID=...; SEID=...' 字符串."""
    return "; ".join(f"{k}={v}" for k, v in cookies.items())


def get_session(uid: str) -> Optional[QRSession]:
    return _SESSIONS.get(uid)


def cleanup_old_sessions(ttl_seconds: int = 600) -> None:
    """清理超过 ttl 秒的会话."""
    cutoff = time.time() - ttl_seconds
    for uid in list(_SESSIONS.keys()):
        if _SESSIONS[uid].created_at < cutoff:
            _SESSIONS.pop(uid, None)


# 长连接客户端 (单例, 所有会话共用)
class QRSessionManager:
    def __init__(self):
        self._client: Optional[httpx.AsyncClient] = None

    async def get_client(self) -> httpx.AsyncClient:
        if not self._client:
            self._client = httpx.AsyncClient(
                timeout=15.0,
                headers={"User-Agent": UA, "Referer": "https://115.com/"},
            )
        return self._client

    async def close(self):
        if self._client:
            await self._client.aclose()
            self._client = None


qr_mgr = QRSessionManager()