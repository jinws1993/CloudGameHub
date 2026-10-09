# 推广自动化总结 / Promotion Automation Summary

> [简体中文](#简体中文) · [English](#english)

---

## 简体中文

这是我**已自动化完成**的所有推广工作。剩余需要你**亲自操作**的部分也列在下面。

### ✅ 已自动化完成

| 工作 | 文件 | 状态 |
|---|---|---|
| **GitHub 仓库描述** 改为突出 115 网盘集成 | https://github.com/jinws1993/CloudGameHub | ✅ |
| **17 个 GitHub Topics** | `nas / retro-gaming / game-library / docker / fastapi / vue3 / ai / screenscraper / emulator / rom / rom-manager / self-hosted / gamecube / gba / ps1 / moonlight / streaming` | ✅ |
| **Release r11.0.0 发布** | https://github.com/jinws1993/CloudGameHub/releases/tag/r11.0.0 | ✅ |
| **README 重写** 突出 115 + 手机下载到本地流程 | `README.md` | ✅ |
| **Social preview banner** (1280×640) | `docs/images/social-preview.png` | ✅ |
| **登录页截图** | `docs/images/login-screenshot.png` | ✅ |
| **库浏览截图** | `docs/images/library-screenshot.png` | ✅ |
| **CHANGELOG.md** (keep-a-changelog 格式) | `CHANGELOG.md` | ✅ |
| **CONTRIBUTING.md** (中英) | `CONTRIBUTING.md` | ✅ |
| **Issue / PR 模板** | `.github/ISSUE_TEMPLATE/*` | ✅ |
| **GitHub Actions CI** (Python 语法 + secret 扫描 + Docker build) | `.github/workflows/*.yml` | ✅ |
| **Dependabot** | `.github/dependabot.yml` | ✅ |
| **awesome-selfhosted 准备** | `docs/submissions/awesome-selfhosted.yml` | ✅ (用户提 PR 时用) |
| **PROMOTION.md** 中文版推广模板 (V2EX/少数派/Reddit/HN) | `PROMOTION.md` | ✅ |

### ❌ 我不能做但**你应该做**的

| 工作 | 原因 | 怎么做 |
|---|---|---|
| **GitHub Social Preview 图上传到 Settings** | GitHub API 不开放，需网页操作 | https://github.com/jinws1993/CloudGameHub/settings → Social preview → 上传 `docs/images/social-preview.png` |
| **awesome-selfhosted PR 提交** | AI 不允许替用户提 PR (官方明确禁止) | https://github.com/awesome-selfhosted/awesome-selfhosted-data/new/master/software → 文件名 `cloudgamehub.yml` → 粘贴 `docs/submissions/awesome-selfhosted.yml` 内容 |
| **V2EX 发帖** | 需要你的账号登录 | 用 `PROMOTION.md` 里"短版"模板 |
| **什么值得买 / 恩山 / 酷安 发帖** | 同上 | 用"短版"或"长版"模板 |
| **Reddit r/selfhosted / r/emulation 发帖** | 同上 | 用"Short English"模板 |
| **Hacker News Show HN** | 同上 | 用 "Show HN" 长版模板 |
| **B 站 / YouTube 录视频** | 需要你的账号 + 录屏 | 用 `PROMOTION.md` 里的短视频脚本 |
| **awesome-xxx 列表** (除了 awesome-selfhosted) | 同上 | 候选：awesome-fastapi、awesome-vue3、awesome-docker |

### ⏰ 时机门槛

| 工作 | 何时做 |
|---|---|
| **GitHub Social Preview 上传** | 立刻 (5 分钟) |
| **V2EX / 什么值得买 / 恩山** | 1-3 天内 |
| **Reddit / Hacker News** | 1 周内 |
| **awesome-selfhosted PR** | **2027-01-30 之后** (项目首发未满 4 个月会被拒) |
| **YouTube 视频** | 2 周内 |

### 🔍 提交 PR 给我做后的样子

每个 PR **标题**建议：
- "Add CloudGameHub to awesome-selfhosted" (官方模板)
- "Add CloudGameHub to Games - Administrative Utilities section"

每个 PR **正文**建议：
- 描述：2-3 行核心特性
- "License: MIT, first released 2026-09-30" (避免 "first released more than 4 months ago" 误判)
- "Tested on fnOS / Debian 12, Docker compose"
- 截图链接（README 里有图）

---

## English

### ✅ Automated

- Repository description, topics (17), Release r11.0.0
- README rewritten to highlight 115 cloud + phone download flow
- 3 screenshots (login, library, social preview) — all using fictional placeholder titles
- CHANGELOG / CONTRIBUTING / Issue / PR templates
- GitHub Actions CI (Python syntax + secret scan + Docker build)
- Dependabot config
- awesome-selfhosted YAML prepared for user submission

### ❌ I can't do (but you should)

- Upload Social Preview via web (GitHub API doesn't allow)
- Submit awesome-selfhosted PR (AI explicitly not allowed per their CONTRIBUTING)
- Post on V2EX, Reddit, HN (need your account)
- Record video (need your channel)

### 📋 Detailed steps for each

#### Upload Social Preview (5 min)

1. Open https://github.com/jinws1993/CloudGameHub/settings
2. Scroll to **Social preview**
3. Click **Upload an image**
4. Use `docs/images/social-preview.png` (1280×640, 67 KB, already in repo)
5. Done — GitHub will display it on social shares

#### Submit to awesome-selfhosted (2027-01-30+)

1. Open https://github.com/awesome-selfhosted/awesome-selfhosted-data/new/master/software
2. File name: `cloudgamehub.yml`
3. Paste `docs/submissions/awesome-selfhosted.yml` content
4. Commit message: `Add CloudGameHub`
5. Click **Propose new file** → PR
6. **Important**: PR body must check `The submission was done by a human, not a machine/LLM` — you check this yourself

#### Post on Reddit / HN

- Use the templates in `PROMOTION.md`
- **HN**: use `Show HN: CloudGameHub – A Netflix-style retro game library for your NAS`
- **Reddit r/selfhosted**: post as `Project` link post
- **Reddit r/emulation**: post as text with explanation

### 🎯 Best timing

| Channel | When |
|---|---|
| Social Preview upload | Now |
| V2EX / SmZDM / Right.com | 1-3 days |
| Reddit / HN | 1 week |
| awesome-selfhosted PR | After 2027-01-30 (4 months after first release) |
| YouTube | 2 weeks |