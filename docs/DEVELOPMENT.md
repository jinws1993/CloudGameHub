# 开发指南 / Developer Guide

> [简体中文](#简体中文) · [English](#english)

---

## 简体中文

### 1. 项目结构

```
NasGameHub/
├── server/                       # 后端 + Web 前端
│   ├── app/                      # Python 后端 (FastAPI)
│   │   ├── main.py               # FastAPI 应用 + 路由
│   │   ├── config.py             # Pydantic Settings
│   │   ├── models.py             # SQLAlchemy ORM
│   │   ├── auth.py               # JWT 认证
│   │   ├── scrape_engine.py      # 刮削主引擎 + _collect_metadata
│   │   ├── scraper.py            # ScreenScraper.fr 客户端
│   │   ├── ai_matcher.py         # OpenAI 兼容 AI 识别
│   │   ├── translator.py         # AI 翻译 (含 think 块剥离)
│   │   ├── cloud_115.py          # 115 网盘集成
│   │   ├── rom_scanner.py        # ROM 扫描归类
│   │   ├── builtin_db.py         # 内置元数据
│   │   ├── libretro_meta.py      # LibRetro 元数据
│   │   └── emulator_map.py       # 模拟器映射
│   ├── web/                      # 前端 (Vue 3 SPA)
│   │   ├── index.html
│   │   └── static/
│   │       ├── app.js            # 主应用 (render function 风格)
│   │       └── vue.global.prod.js
│   └── requirements.txt
├── client-android/               # Android 客户端 (Kotlin)
├── client-windows/               # Windows 客户端 (PySide6)
├── data/                         # 运行时数据 (gitignored)
│   ├── db/nasgame.db             # SQLite
│   ├── roms/                     # ROM 文件
│   ├── media/                    # 刮削封面/截图/视频
│   ├── config/config.json        # 用户配置 (AI key 等)
│   ├── logs/
│   └── libretro_db/              # LibRetro 元数据快照
├── docs/
│   ├── INSTALL.md
│   ├── USAGE.md
│   ├── DEVELOPMENT.md            # 本文件
│   └── SECURITY.md
├── docker-compose.yml
├── Dockerfile
├── .env.example
├── .gitignore
├── LICENSE
└── README.md
```

### 2. 本地开发

#### 2.1 后端

```bash
cd server
python3.11 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt

# 启动开发模式 (热重载)
uvicorn app.main:app --reload --host 0.0.0.0 --port 14322
```

Web UI: http://localhost:14322/
API Docs: http://localhost:14322/api/docs (Swagger)

#### 2.2 前端

`server/web/static/app.js` 是单文件 SPA，无需打包步骤。直接修改 → 浏览器 Ctrl+Shift+R 硬刷新即可。

### 3. 添加新平台

`server/app/models.py` 里有 25 个平台的默认数据 (`Platform` 表)。新增平台有 **两种方式**：

#### 3.1 启动时自动创建

修改 `server/app/main.py` 的 `init_default_platforms()` 函数（如果存在），或在迁移脚本里 INSERT。

#### 3.2 运行时 UI 创建

管理员**登录** → **平台管理** → 填写：
- `code`: 平台代号，用于 ROM 目录名（如 `FC`）
- `name`: 中文名（如 "红白机"）
- `name_en`: 英文名（用于 AI 提示）
- `folder`: ROM 子目录名
- `extensions`: 扩展名列表（逗号分隔，如 `.nes,.zip`）
- `cover`: 占位图 URL（可选）

### 4. 数据库 schema

主要表 (`server/app/models.py`)：

- `users`：管理员/普通用户
- `platforms`：25 个游戏平台
- `games`：ROM 元数据（主表）
  - `id, platform_id, rom_filename, rom_path, rom_size`
  - `title, title_en, title_zh, title_jp, title_raw`
  - `description, description_en, release_date, developer, publisher, genre, players, rating`
  - `cover_path, screenshot_paths, logo_path, video_path`
  - `scrape_status (pending|done|failed)`, `scrape_source (builtin|libretro|screenscraper|ai|custom)`
  - `cloud_source, cloud_path` （115 来源）
  - `extra`：JSON 通用字段
- `scan_tasks`, `scrape_tasks`：后台任务记录
- `play_sessions`：游玩历史

迁移用 SQLAlchemy `create_all()`（首次启动自动建表），无 Alembic。

### 5. REST API 速览

完整文档：启动后访问 `/api/docs`。

| Method | Path | 说明 |
|---|---|---|
| POST | `/api/auth/login` | 登录，返回 JWT |
| POST | `/api/auth/change-password` | 改密码（首次登录强制） |
| GET | `/api/auth/me` | 当前用户信息 |
| GET | `/api/config` | 公开配置（含 AI/SS enabled 状态，**不**含密钥） |
| POST | `/api/config` | 更新配置（管理员） |
| GET | `/api/platforms` | 平台列表 |
| POST | `/api/platforms` | 新增平台（管理员） |
| GET | `/api/games?page=1&page_size=60&platform=FC&status=done&search=xxx` | 游戏列表 |
| GET | `/api/games/{id}` | 单个游戏详情 |
| POST | `/api/games/{id}/favorite` | 切换收藏 |
| POST | `/api/games/{id}/rescrape` | 重新刮削（管理员） |
| POST | `/api/games/{id}/scrape-custom` | 用自定义名字重新刮削 |
| POST | `/api/games/{id}/scrape-search` | 搜索刮削源（同步，返回候选项） |
| POST | `/api/games/{id}/scrape-apply` | 应用候选项（同步） |
| POST | `/api/games/{id}/cover` | 上传封面（multipart） |
| DELETE | `/api/games/{id}/cover` | 删除封面 |
| POST | `/api/library/scan` | 启动全库扫描 |
| POST | `/api/library/scrape` | 启动全库刮削 |
| GET | `/api/library/tasks` | 任务列表 |
| GET | `/api/cloud/115/list?cid=xxx` | 列目录 |
| GET | `/api/cloud/115/qrcode` | 115 扫码登录（返回 QR PNG） |
| GET | `/api/cloud/115/qrcode/status` | 轮询扫码状态 |
| POST | `/api/cloud/115/scan` | 导入目录 |
| GET | `/api/stats` | 统计 |
| GET | `/api/media/{path}` | 静态媒体文件 |

### 6. 添加新刮削源

`server/app/scrape_engine.py` 里：

```python
async def _collect_metadata(self, game, ss_client, custom_name, prefer_ai):
    # 1. builtin
    # 2. libretro
    # 3. AI (prefer_ai=True 时优先)
    # 4. ScreenScraper
    # 5. AI (默认 fallback)
    # ... 新增步骤: 5.5 MyCustomGame
```

实现一个 `MyScraperClient` 类（参考 `ScreenScraperClient`），然后在 `_collect_metadata` 里插入调用。

### 7. 调试技巧

```python
# 启用 mock AI 模式 (无需真实 key)
NASGAME_MOCK_AI=1 docker compose up

# 查看 API 调用日志
docker compose logs -f nasgame | grep -i "scrape\|AI\|115"

# 浏览器开发者工具 → Network 看具体 HTTP 请求
# 浏览器开发者工具 → Console 看前端 [NasGame] 标签的 log
```

### 8. 提交 PR

1. Fork 仓库
2. 创建分支：`git checkout -b feature/your-feature`
4. 提交：`git commit -m "feat: your feature"`
5. Push：`git push origin feature/your-feature`
6. GitHub 上提 PR 到 `main` 分支

提交前请：
- [ ] 在 fnOS / Debian 上测试
- [ ] 不引入新依赖到 requirements.txt（如必要，注释理由）
- [ ] 不提交 ROM / 数据库 / 配置 / 凭据
- [ ] 更新 README / docs/ 如有 UI/API 变化

---

## English

### 1. Layout

See the directory tree above. The backend is FastAPI + SQLAlchemy + SQLite; the Web UI is a Vue 3 single-file SPA (no build step). Clients are native Android (Kotlin) and Windows (PySide6 + pyinstaller).

### 2. Local development

```bash
cd server
python3.11 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --reload --host 0.0.0.0 --port 14322
```

Web UI: http://localhost:14322/, Swagger: http://localhost:14322/api/docs.

Front-end: edit `server/web/static/app.js` and Ctrl+Shift+R.

### 3. Add a new platform

Either insert into `Platform` table or add it via the **Platforms** admin page.

### 4. Database

SQLAlchemy ORM, no Alembic — tables created via `Base.metadata.create_all()` on first launch. Main tables: `users`, `platforms`, `games`, `scan_tasks`, `scrape_tasks`, `play_sessions`.

### 5. REST API

Full schema at `/api/docs`. Key endpoints in the table above.

### 6. Add a new scraper source

Implement a client class (see `scraper.py`), then hook into `ScrapeEngine._collect_metadata` in `scrape_engine.py`.

### 7. Debugging

- `NASGAME_MOCK_AI=1 docker compose up` — Mock AI without real keys
- `docker compose logs -f nasgame` — Live server logs
- Browser DevTools → Network — Inspect HTTP

### 8. Contributing

1. Fork & branch (`feature/xxx`)
2. Test on fnOS / Debian
3. Don't add new deps without justification; don't commit ROMs/db/config
4. PR against `main`