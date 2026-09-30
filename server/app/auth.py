"""JWT-based authentication."""
from __future__ import annotations
from datetime import datetime, timedelta
from fastapi import HTTPException, Depends, status
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from jose import jwt, JWTError
from passlib.context import CryptContext
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select

from .config import settings
from .database import User, SessionLocal

pwd_ctx = CryptContext(schemes=["bcrypt"], deprecated="auto")
bearer = HTTPBearer(auto_error=False)


def hash_pwd(p: str) -> str:
    return pwd_ctx.hash(p)


def verify_pwd(p: str, h: str) -> bool:
    try:
        return pwd_ctx.verify(p, h)
    except Exception:
        return False


def create_token(uid: int, username: str) -> str:
    exp = datetime.utcnow() + timedelta(hours=settings.jwt_expire_hours)
    return jwt.encode(
        {"sub": str(uid), "name": username, "exp": exp},
        settings.jwt_secret, algorithm="HS256",
    )


def decode_token(tok: str) -> dict:
    try:
        return jwt.decode(tok, settings.jwt_secret, algorithms=["HS256"])
    except JWTError as e:
        raise HTTPException(status_code=401, detail=f"invalid token: {e}")


async def get_db():
    async with SessionLocal() as s:
        yield s


async def current_user(
    cred: HTTPAuthorizationCredentials | None = Depends(bearer),
    db: AsyncSession = Depends(get_db),
) -> User:
    if not cred:
        raise HTTPException(status_code=401, detail="missing token")
    payload = decode_token(cred.credentials)
    uid = int(payload.get("sub", 0))
    res = await db.execute(select(User).where(User.id == uid))
    user = res.scalar_one_or_none()
    if not user:
        raise HTTPException(status_code=401, detail="user not found")
    return user


async def require_admin(user: User = Depends(current_user)) -> User:
    if not user.is_admin:
        raise HTTPException(status_code=403, detail="admin only")
    return user
