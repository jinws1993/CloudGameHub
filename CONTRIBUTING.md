# 贡献指南 / Contributing to CloudGameHub

> [简体中文](#简体中文) · [English](#english)

感谢考虑为 CloudGameHub 添砖加瓦！🎮

---

## 简体中文

### 报告 Bug / 提功能请求

1. **搜 [已有 issues](https://github.com/jinws1993/CloudGameHub/issues)** —— 避免重复
2. **用 issue 模板** —— GitHub 会自动给选择器
4. **附上关键信息**：
   - 操作系统（fnOS / Debian / Ubuntu / 其他）
   - Docker 版本（`docker --version`）
   - 日志（`docker logs cloudgamehub --tail=200`）
   - 复现步骤

### 提 Pull Request

#### 本地准备

```bash
# 1. Fork + clone
git clone https://github.com/<你的用户名>/CloudGameHub.git
cd CloudGameHub

# 2. 创建分支 (语义化命名)
git checkout -b feature/my-awesome-feature     # 新功能
git checkout -b fix/issue-123                  # 修 bug
git checkout -b docs/improve-install-guide     # 文档改进

# 3. 设置本地开发 (不依赖 Docker)
cd server
python3.11 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --reload --port 14322

# 4. 改完后验证
python3 -m py_compile app/*.py    # 后端语法
node -e "new Function(require('fs').readFileSync('web/static/app.js'))"  # 前端语法
curl http://localhost:14322/         # 基础 smoke test
```

#### 提交规范

我们用 [Conventional Commits](https://www.conventionalcommits.org/zh-hans/)：

```
feat: 新增 XX 平台
fix(scrape): 修复 SS 403 时未 fallback 到 AI
docs: 更新 INSTALL.md 离线部署章节
refactor(translator): 抽出 _strip_think_block 通用函数
perf(scrape-search): 缓存 builtin_db 查询结果
test: 添加 scrape_engine 单测
chore: 升级 fastapi 到 0.116
```

#### PR 检查清单

- [ ] 分支名：`feature/*` / `fix/*` / `docs/*` / `refactor/*`
- [ ] 提交信息用 Conventional Commits
- [ ] 单个 PR 只做一件事（避免 +3000 行的"大杂烩"）
- [ ] 后端代码无新增依赖（除非注释理由）
- [ ] 不提交 ROM / 数据库 / 配置 / 凭据
- [ ] UI/API 变化时同步更新 docs/
- [ ] 在本地测试过基础功能（启动 + 登录 + 扫描）

#### 添加新平台

最受欢迎的贡献 —— 我们有些平台缺：

| 平台 | code | folder | extensions |
|---|---|---|---|
| Atari 2600 | A2600 | a2600 | `.bin,.rom,.a26` |
| Atari 5200 | A5200 | a5200 | `.bin,.rom,.a52` |
| Atari 7800 | A7800 | a7800 | `.bin,.rom,.a78` |
| Atari Lynx | LYNX | lynx | `.lnx,.lyx` |
| Sega 32X | 32X | 32x | `.32x` |
| Sega CD | SCD | scd | `.cue,.iso,.bin` |
| Sega Saturn | SS | saturn | `.cue,.iso` |
| TurboGrafx-16 / PC Engine | 已支持 (PCE) | | |
| Neo Geo Pocket | NGP | ngp | `.ngp,.ngc` |
| WonderSwan | WS | ws | `.ws,.wsc` |
| Virtual Boy | VB | vb | `.vb` |
| MSX | MSX | msx | `.rom,.mx1,.mx2` |

PR 步骤：
1. 在 `server/app/seed.py` 的 `DEFAULT_PLATFORMS` 加条目
2. 在 `data/roms/` 下创建对应平台子目录 + 更新 `README.md`
3. 在 `server/app/libretro_client.py` 加 LibRetro 索引文件名
4. 测试扫描 + 刮削

### 开发架构要点

- `ScrapeEngine._collect_metadata()` 是刮削核心，**所有新增源都加在这里**
- AI 提示词在 `server/app/ai_matcher.py` —— 修改前先看 mock 模式 (`server/app/mock_ai.py`)
- 翻译剥离 `<think>...` 在 `server/app/translator.py` —— **不要删**，reasoning model 都靠它
- 前端是单文件 Vue 3 (`server/web/static/app.js`)，**没有构建步骤**

### Code Style

- Python: PEP 8 + type hints (项目用 SQLAlchemy 2.0 `Mapped[]` 风格)
- JavaScript: ES2020+, render function 风格 (不用 `<template>`)
- 注释用中文，技术名词保留英文
- 行宽 120 字符

### 联系方式

- Issue: 提 issue
- Discussion: 提 discussion (架构讨论)
- Telegram 群: (待建)

---

## English

### Reporting Bugs

Search [existing issues](https://github.com/jinws1993/CloudGameHub/issues) first, then use the issue template. Include:
- OS (fnOS / Debian / Ubuntu)
- Docker version (`docker --version`)
- Logs (`docker logs cloudgamehub --tail=200`)
- Reproduction steps

### Submitting PRs

1. Fork + create a branch (`feature/*`, `fix/*`, `docs/*`, `refactor/*`)
2. Follow [Conventional Commits](https://www.conventionalcommits.org/) for commit messages
3. Keep PRs small and focused
5. Don't add deps without justification; never commit ROMs / DB / config / credentials
6. Update `docs/` when API / UI changes

### Adding new platforms

See the table above for the 14 currently-missing platforms. Add entries to `seed.py`, `libretro_client.py`, and `data/roms/`. PR welcome! 🚀

### Architecture notes

- `ScrapeEngine._collect_metadata()` is the scraping core — **add new sources here**
- AI prompts in `server/app/ai_matcher.py` — test with `server/app/mock_ai.py` first
- `translator.py` `<think>...` stripping is **load-bearing** for reasoning models
- Frontend is single-file Vue 3 (`app.js`), no build step

### Code style

- Python: PEP 8 + type hints (SQLAlchemy 2.0 `Mapped[]` style)
- JavaScript: ES2020+, render function style (no `<template>`)
- Chinese comments welcome
- 120-char line width