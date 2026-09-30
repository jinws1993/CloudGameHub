"""115 网盘 客户端 (基于开源社区已知的 115 WebAPI 协议).

功能:
  * list_dir(cid)       - 列目录
  * get_info(pickcode)  - 取文件/目录的元数据
  * get_download_url(pickcode) - 取下载 URL (CDN 直链)

Cookie 格式: 用户从浏览器复制 "UID=...; CID=...; SEID=..." 后, 填进 NASGame 设置。

不存储密码/账号, 仅用 cookie。Cookie 有效期通常 1 周左右, 失效用户重填。
"""
from __future__ import annotations
import re
import json
from typing import Optional
from urllib.parse import quote
import httpx
from loguru import logger

API_BASE = "https://webapi.115.com"
FILES_BASE = "https://webapi.115.com/files"

# User-Agent 必须使用桌面浏览器, 不然会被风控拦截
UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/120.0.0.0 Safari/537.36"
)


class Pan115Error(Exception):
    """115 API 错误"""
    pass


class Pan115Client:
    """115 网盘 客户端。

    用法:
        async with Pan115Client(cookie_str) as client:
            items = await client.list_dir(cid=0)
            download_url = await client.get_download_url(pickcode)
    """

    def __init__(self, cookie: str = "", timeout: float = 30.0,
                 proxy_url: str = ""):
        self.cookie_str = cookie.strip()
        self.timeout = timeout
        self._client: Optional[httpx.AsyncClient] = None
        self._proxies = None
        if proxy_url:
            self._proxies = {"http://": proxy_url, "https://": proxy_url}

    def _headers(self) -> dict:
        return {
            "User-Agent": UA,
            "Cookie": self.cookie_str,
            "Referer": "https://115.com/",
            "Origin": "https://115.com",
        }

    async def __aenter__(self):
        self._client = httpx.AsyncClient(
            timeout=self.timeout,
            headers=self._headers(),
            follow_redirects=True,
            proxies=self._proxies,
        )
        return self

    async def __aexit__(self, exc_type, exc, tb):
        if self._client:
            await self._client.aclose()

    @staticmethod
    def parse_cookie(cookie: str) -> dict[str, str]:
        """把 'UID=xx; CID=yy; SEID=zz' 解析成 dict"""
        out = {}
        for part in re.split(r"[;\n]+", cookie):
            part = part.strip()
            if not part or "=" not in part:
                continue
            k, v = part.split("=", 1)
            out[k.strip()] = v.strip()
        return out

    async def _check_cookie(self) -> bool:
        """验证 cookie 是否有效 (用 /files cid=0)"""
        try:
            r = await self._client.get(f"{API_BASE}/files", params={
                "aid": 1, "cid": 0, "limit": 1, "show_dir": 1,
                "o": "user_ptime", "asc": 1, "fc_mix": 0, "natsort": 1, "format": "json",
            })
            j = r.json()
            if j.get("state"):
                return True
            logger.warning(f"115 cookie check fail: {j}")
            return False
        except Exception as e:
            logger.error(f"115 cookie check error: {e}")
            return False

    async def login_check(self) -> dict:
        """登录态检查, 返回用户信息."""
        try:
            r = await self._client.get(f"{API_BASE}/files", params={
                "aid": 1, "cid": 0, "limit": 1, "show_dir": 1,
                "o": "user_ptime", "asc": 1, "fc_mix": 0, "natsort": 1, "format": "json",
            })
            j = r.json()
            if not j.get("state"):
                return {"ok": False, "error": j.get("message") or j.get("error") or "Cookie 无效"}
            # 用户信息通常在 login 后能看到, 这里返回最基本的数据
            return {
                "ok": True,
                "count": j.get("data", {}).get("count", 0),
                "msg": f"登录成功, 根目录有 {j.get('data', {}).get('count', 0)} 项",
            }
        except Exception as e:
            return {"ok": False, "error": f"网络错误: {type(e).__name__}: {e}"}

    async def list_dir(self, cid: int = 0, limit: int = 100, offset: int = 0) -> list[dict]:
        """列目录.

        cid=0 表示根目录. 返回 [{id, name, sha1, size, is_dir, pickcode, ...}, ...]
        """
        if not self._client:
            raise Pan115Error("client not initialized")
        # 参数参考 py115 官方 SDK (https://github.com/deadblue/py115):
        #  URL 必须是 /files (不是 /files/list), 否则返回 "服务器开小差"
        #  需要 o/user_ptime/asc/fc_mix/natsort/format, 否则同样错误
        r = await self._client.get(f"{API_BASE}/files", params={
            "aid": 1,
            "cid": cid,
            "limit": limit,
            "offset": offset,
            "show_dir": 1,
            "o": "user_ptime",
            "asc": 1,
            "fc_mix": 0,
            "natsort": 1,
            "format": "json",
        })
        j = r.json()
        if not j.get("state"):
            raise Pan115Error(j.get("message") or j.get("error") or "list failed")
        return j.get("data") or []

    async def get_path(self, cid: int) -> list[dict]:
        """获取 cid 的完整路径 (根 → 当前), 用于面包屑.
        返回 [{cid: str, name: str}, ...] — cid 为字符串避免 JS 精度丢失."""
        if cid == 0:
            return [{"cid": "0", "name": "根目录"}]
        try:
            r = await self._client.get(f"{API_BASE}/files/category", params={"cid": cid})
            j = r.json()
            if j.get("state") and j.get("data"):
                # 路径数组
                paths = j["data"].get("paths", [])
                return [{"cid": str(p.get("cid", 0)), "name": p.get("name", "")}
                        for p in paths] + [{"cid": str(cid), "name": "当前"}]
        except Exception as e:
            logger.warning(f"get_path err: {e}")
        return []

    async def get_download_url(self, pickcode: str) -> str:
        """获取文件的下载直链 (302 后的 CDN URL)."""
        if not self._client:
            raise Pan115Error("client not initialized")
        # 1. 拿下载参数 (含 token)
        r = await self._client.get(f"{FILES_BASE}/download",
                                   params={"pickcode": pickcode, "_": "1"})
        j = r.json()
        if not j.get("state"):
            raise Pan115Error(j.get("message") or j.get("error") or "no download url")
        data = j.get("data") or {}
        # data.list[0].url 是带 token 的 CDN 链接
        try:
            url = data["list"][0]["url"]
        except (KeyError, IndexError):
            raise Pan115Error("download URL missing in response")
        # 2. 该 URL 本身就是 CDN 直链, 浏览器/客户端直接 GET 即可
        return url

    async def get_file_info(self, pickcode: str) -> Optional[dict]:
        """取文件的元数据 (sha1, size, name, ...)"""
        if not self._client:
            raise Pan115Error("client not initialized")
        r = await self._client.get(f"{API_BASE}/files/file",
                                   params={"pickcode": pickcode})
        j = r.json()
        if not j.get("state"):
            return None
        return (j.get("data") or [None])[0]

    async def list_recursive_files(self, cid: int, max_depth: int = 4,
                                    exts: Optional[set] = None) -> list[dict]:
        """递归列目录下所有文件. exts 限制扩展名 (如 {'.nes', '.smc'}).

        返回: [{cid, name, sha1, size, pickcode, path_display}, ...]
        """
        results: list[dict] = []

        async def walk(current_cid: int, current_path: str, depth: int):
            if depth > max_depth:
                return
            try:
                items = await self.list_dir(current_cid, limit=200)
            except Exception as e:
                logger.warning(f"115 walk skip cid={current_cid}: {e}")
                return
            for item in items:
                name = item.get("n", "")
                if not name:
                    continue
                is_dir = "fid" not in item  # py115 官方: 有 fid = 文件, 无 fid = 目录
                pickcode = item.get("pc", "")
                full_path = f"{current_path}/{name}" if current_path else name
                if is_dir:
                    await walk(int(item.get("cid", 0) or item.get("id", 0)),
                               full_path, depth + 1)
                else:
                    # 扩展名过滤
                    if exts:
                        dot = name.rfind(".")
                        ext = name[dot:].lower() if dot >= 0 else ""
                        if ext not in exts:
                            continue
                    results.append({
                        "cid": current_cid,
                        "name": name,
                        "sha1": (item.get("sha") or "").lower(),
                        "size": int(item.get("s") or item.get("size") or 0),
                        "pickcode": pickcode,
                        "path_display": full_path,
                    })

        await walk(cid, "", 0)
        return results