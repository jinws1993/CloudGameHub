"""Async SQLAlchemy database models."""
from __future__ import annotations
from datetime import datetime
from pathlib import Path
from sqlalchemy import (
    String, Integer, BigInteger, Float, Text, Boolean, DateTime, ForeignKey, JSON
)
from sqlalchemy.ext.asyncio import create_async_engine, async_sessionmaker, AsyncSession
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship

from .config import settings


class Base(DeclarativeBase):
    pass


class User(Base):
    __tablename__ = "users"
    id: Mapped[int] = mapped_column(primary_key=True)
    username: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    password_hash: Mapped[str] = mapped_column(String(255))
    is_admin: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)
    last_login: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)


class Platform(Base):
    __tablename__ = "platforms"
    id: Mapped[int] = mapped_column(primary_key=True)
    code: Mapped[str] = mapped_column(String(32), unique=True, index=True)  # FC, SFC, PS...
    name: Mapped[str] = mapped_column(String(128))  # 红白机, 超任, PlayStation
    name_en: Mapped[str] = mapped_column(String(128))
    folder: Mapped[str] = mapped_column(String(64))  # 物理目录名
    extensions: Mapped[str] = mapped_column(String(512))  # comma-separated
    cover: Mapped[str] = mapped_column(String(512), default="")
    description: Mapped[str] = mapped_column(Text, default="")
    sort_order: Mapped[int] = mapped_column(Integer, default=0)
    enabled: Mapped[bool] = mapped_column(Boolean, default=True)


class Game(Base):
    __tablename__ = "games"
    id: Mapped[int] = mapped_column(primary_key=True)
    platform_id: Mapped[int] = mapped_column(ForeignKey("platforms.id"), index=True)
    # File info
    rom_path: Mapped[str] = mapped_column(String(1024))
    rom_filename: Mapped[str] = mapped_column(String(512))
    rom_size: Mapped[int] = mapped_column(BigInteger, default=0)
    rom_crc: Mapped[str] = mapped_column(String(32), default="")
    # Metadata
    title_raw: Mapped[str] = mapped_column(String(512))  # 从文件名解析的原始名
    title: Mapped[str] = mapped_column(String(512), default="")  # 刮削后的中文名
    title_en: Mapped[str] = mapped_column(String(512), default="")
    title_zh: Mapped[str] = mapped_column(String(512), default="")
    title_jp: Mapped[str] = mapped_column(String(512), default="")
    description: Mapped[str] = mapped_column(Text, default="")
    description_en: Mapped[str] = mapped_column(Text, default="")
    release_date: Mapped[str] = mapped_column(String(32), default="")
    developer: Mapped[str] = mapped_column(String(256), default="")
    publisher: Mapped[str] = mapped_column(String(256), default="")
    genre: Mapped[str] = mapped_column(String(256), default="")
    players: Mapped[str] = mapped_column(String(32), default="")
    rating: Mapped[float] = mapped_column(Float, default=0)
    # Media
    cover_path: Mapped[str] = mapped_column(String(512), default="")
    screenshot_paths: Mapped[str] = mapped_column(Text, default="")  # JSON array
    logo_path: Mapped[str] = mapped_column(String(512), default="")
    video_path: Mapped[str] = mapped_column(String(512), default="")
    # Scraping
    scraper_id: Mapped[str] = mapped_column(String(128), default="")
    scrape_status: Mapped[str] = mapped_column(String(32), default="pending")  # pending|done|failed|skipped
    scrape_source: Mapped[str] = mapped_column(String(32), default="")  # screenscraper|ai|manual
    scraped_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    # 云盘源 (115 网盘 / ...)
    cloud_source: Mapped[str] = mapped_column(String(32), default="")  # '115' | ''
    cloud_pickcode: Mapped[str] = mapped_column(String(64), default="")  # 115 pickcode
    cloud_sha1: Mapped[str] = mapped_column(String(64), default="")
    cloud_path: Mapped[str] = mapped_column(String(1024), default="")  # 网盘里的显示路径
    # Misc
    favorite: Mapped[bool] = mapped_column(Boolean, default=False)
    play_count: Mapped[int] = mapped_column(Integer, default=0)
    last_played: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    extra: Mapped[dict] = mapped_column(JSON, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)

    platform: Mapped[Platform] = relationship(lazy="joined")


class ScanTask(Base):
    """Scraping / scan job records."""
    __tablename__ = "scan_tasks"
    id: Mapped[int] = mapped_column(primary_key=True)
    task_type: Mapped[str] = mapped_column(String(32))  # scan|scrape|translate|identify
    target: Mapped[str] = mapped_column(String(512), default="all")  # platform code or path
    total: Mapped[int] = mapped_column(Integer, default=0)
    processed: Mapped[int] = mapped_column(Integer, default=0)
    success: Mapped[int] = mapped_column(Integer, default=0)
    failed: Mapped[int] = mapped_column(Integer, default=0)
    status: Mapped[str] = mapped_column(String(32), default="pending")  # pending|running|done|failed
    message: Mapped[str] = mapped_column(Text, default="")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)
    finished_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)


class PlaySession(Base):
    __tablename__ = "play_sessions"
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"))
    game_id: Mapped[int] = mapped_column(ForeignKey("games.id"))
    mode: Mapped[str] = mapped_column(String(16))  # local|stream
    started_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)
    ended_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    duration: Mapped[int] = mapped_column(Integer, default=0)


# --- engine / session ---
engine = create_async_engine(
    f"sqlite+aiosqlite:///{settings.db_path}",
    echo=False,
    pool_pre_ping=True,
)
SessionLocal = async_sessionmaker(engine, expire_on_commit=False, class_=AsyncSession)


async def init_db():
    # Step 1: create tables (uses its own connection)
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    # Step 2: ALTER TABLE for legacy migration columns (uses its own connection)
    from sqlalchemy import text
    new_cols = [
        ("games", "cloud_source", "VARCHAR(32) DEFAULT ''"),
        ("games", "cloud_pickcode", "VARCHAR(64) DEFAULT ''"),
        ("games", "cloud_sha1", "VARCHAR(64) DEFAULT ''"),
        ("games", "cloud_path", "VARCHAR(1024) DEFAULT ''"),
    ]
    for table, col, decl in new_cols:
        try:
            async with engine.begin() as conn:
                await conn.execute(text(f"ALTER TABLE {table} ADD COLUMN {col} {decl}"))
        except Exception:
            # 列已存在 (老库已迁移过)
            pass

    # Step 3: seed default platforms if empty
    from .seed import seed_platforms
    async with SessionLocal() as s:
        await seed_platforms(s)
