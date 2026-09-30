# 推广文案模板 / Promotion Templates

> 把下列文案复制到对应平台，**微调加入你的个人语气**效果更好。

---

## 🇨🇳 中文版

### 标题
**NasGameHub — 让游戏管理像电影一样简单**

### 短版（V2EX / 酷安 / 贴吧）

> 分享一下我自用的 NAS 复古游戏库管理器：**NasGameHub**
>
> 装好之后把 ROM 丢进去，自动归类 25 个平台，自动刮削封面/中文译名/简介。AI + ScreenScraper 双引擎，AI 优先识别不规则文件名（包括中文、日文）。
>
> 还有 115 云盘集成（直接云盘目录扫描导入）、Web 端串流、Android/Windows 客户端远程启动。
>
> Docker 一键部署，飞牛 OS / Debian / Ubuntu 都跑。我自己用了一段时间，关掉游戏。
>
> 🔗 https://github.com/jinws1993/NasGameHub
>
> Star 一下 + 提 issue / PR 都欢迎，目前还在积极开发。

### 长版（少数派 / 什么值得买）

> # NasGameHub：把散落的 ROM 整理成一个漂亮的游戏库
>
> 作为一个 NAS 玩家，最大的痛点就是——硬盘上堆了几百个 ROM 文件，命名乱七八糟，想玩的时候找不到。
>
> **NasGameHub** 是我写给自己用了一段时间后开源出来的，目标是让游戏管理变得像 Netflix 一样简单。
>
> ## 它能做什么
>
> 1. **自动扫描归类** —— 25 个平台（FC/SFC/N64/GBA/PS1/PS2/PSP/Wii/GC/DC/Saturn/MD/3DS/NDS/...），按扩展名和目录自动识别
> 2. **AI 智能刮削** —— 接入 OpenAI AI 对不规则的中文/英文文件名做识别，自动翻译成中文译名 + 中文简介
> 3. **ScreenScraper 双备份** —— 国外权威元数据库做 fallback
> 4. **手动上传封面** —— AI/SS 都找不到封面时，可以自己上传
> 5. **115 云盘集成** —— 直接在 Web UI 里浏览 115 目录，一键导入（不会触发额外下载）
> 6. **远程游玩** —— Android / Windows 客户端一键启动模拟器，或 Moonlight 串流
> 7. **多用户** —— 管理员/普通用户两级权限
>
> ## 装起来有多简单
>
> ```bash
> git clone https://github.com/jinws1993/NasGameHub.git
> cd NasGameHub
> cp .env.example .env   # 改 AI key / 管理员密码
> docker compose up -d
> ```
>
> 然后浏览器开 `http://NAS_IP:14322/` 就能用。
>
> ## 截图
>
> （待补：贴几张 Web UI 截图）
>
> ## 我也在不断完善
>
> - 仓库：[jinws1993/NasGameHub](https://github.com/jinws1993/NasGameHub)
> - 文档：仓库 docs/ 目录，中英双语
> - Issue / PR 都很欢迎！看 [CONTRIBUTING.md](https://github.com/jinws1993/NasGameHub/blob/main/CONTRIBUTING.md) 了解如何贡献。

---

## 🇬🇧 English version

### Short (Reddit r/selfhosted / r/emulation)

> **NasGameHub — Netflix for your retro game library**
>
> After getting tired of my messy ROM folders, I open-sourced the NAS game library manager I've been using:
>
> - **25 platforms** auto-classified (FC/SFC/N64/GBA/PS1/PS2/PSP/Wii/GC/DC/Saturn/MD/3DS/NDS/J2ME/...)
> - **Dual scraping**: ScreenScraper.fr + any OpenAI-compatible LLM (AI first for messy filenames)
> - **Auto Chinese translate** for game titles and summaries
> - **Manual cover upload** as fallback
> - **115 cloud disk** integration (browse and import without re-downloading)
> - **Remote play**: Android/Windows launchers + Web streaming
> - **Multi-user** roles
>
> Stack: FastAPI + SQLAlchemy + SQLite + Vue 3 SPA (single-file, no build). One-line Docker deploy, offline venv support for air-gapped NAS.
>
> 🔗 https://github.com/jinws1993/NasGameHub
>
> Star ⭐ and contributions welcome!

### Long (Hacker News / Show HN)

> **Show HN: NasGameHub – A Netflix-style retro game library for your NAS**
>
> I built this because I had hundreds of ROM files scattered across my NAS with terrible filenames. Now I drop them in a folder and NasGameHub gives me a beautiful, browsable, scrapable game collection.
>
> **What makes it different**
>
> 1. Dual-engine scraping — ScreenScraper.fr (high quality metadata) + any OpenAI-compatible LLM (best for non-standard names). When AI is enabled it runs first.
> 2. AI auto-translates titles/summaries into the user's language.
> 3. Manual cover upload when scraping sources fail.
> 4. 115 cloud disk integration (China's biggest cloud storage) — browse and import without re-downloading.
> 5. Single-file Vue 3 SPA (no build step, ~100KB app.js).
> 6. Native Android (Kotlin) and Windows (PySide6+pyinstaller) clients.
> 7. Air-gapped NAS friendly — Dockerfile supports pre-built `venv.tar` to avoid PyPI access.
>
> **Stack**: FastAPI + SQLAlchemy + SQLite + Vue 3 + httpx + loguru. ~580KB total source.
>
> 🔗 https://github.com/jinws1993/NasGameHub
>
> Looking for contributors, especially for the 14 missing platforms (Atari 2600/5200/7800, Lynx, 32X, Sega CD, Saturn, Neo Geo Pocket, WonderSwan, Virtual Boy, MSX, etc.)

---

## 📍 推广平台列表（按优先级）

| 平台 | URL | 受众 | 调性 | 时间 |
|---|---|---|---|---|
| **V2EX** | v2ex.com | 程序员/极客 | 技术流, 突出 AI + Docker | 1 天内 |
| **少数派** | sspai.com | 数字生活派 | 体验流, 突出 NAS 管理体验 | 1 周内 |
| **什么值得买** NAS 板块 | post.smzdm.com | NAS 玩家 | 实用流, 突出 115 集成 + 飞牛 | 1 周内 |
| **恩山论坛** | right.com.cn | NAS / 路由圈 | 极客流, 突出 Docker + 全平台 | 1 周内 |
| **酷安** | coolapk.com | Android + 玩机 | 年轻化, 突出远程游玩 + 模拟器 | 1 周内 |
| **Reddit r/selfhosted** | reddit.com/r/selfhosted | 国际 NAS 玩家 | 英文长版 | 1 周内 |
| **Reddit r/emulation** | reddit.com/r/emulation | 模拟器玩家 | 突出 AI 刮削 | 1 周内 |
| **Hacker News** | news.ycombinator.com | 国际程序员 | Show HN: ... | 2 周内 |
| **awesome-selfhosted PR** | github.com/awesome-selfhosted/awesome-selfhosted | 受众精准 | PR template + 截图 | 1 周内 |

---

## 💡 推广小技巧

1. **不要纯粘贴文案** —— 加入你的真实使用场景（比如"我有个 200 个 ROM 的 FC 库"）
2. **截图 + gif 效果最好** —— Web UI 截图、刮削过程 gif、串流截图
3. **回复每一个 issue / 评论** —— 早期社区氛围靠你亲自互动
4. **每周一个 release** —— 表明项目活着
6. **fist commit 1 个月内主动 cold PR** 到 awesome-xxx 列表

---

## 🚀 我（AI）能帮你做什么

- 写 README 改写（针对 Reddit / HN 优化）
- 生成中英双语 release notes
- 写 awesome-xxx 列表的 PR 描述
- 翻译 / 校对中文文案
- 生成 README 的 banner 图（用 SVG / matplotlib）

告诉我 **不要**做什么：
- 不能帮你"买 star" / "刷 commit" / 任何黑帽推广
- 不能帮你用 AI 生成虚假用户评论