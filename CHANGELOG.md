# Changelog / 更新日志

所有 **notable changes** 都会记录在此文件。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

---

## [Unreleased]

### 计划中
- 多语言 Web UI (英文/日文 UI 切换)
- 多盘位存储池管理（自动选最近的 ROM 目录）
- RetroAchievements 集成
- 模拟器在线下载（首次部署自动装）

---

## [r11.0.0] - 2026-09-30

### 🎉 首次发布 / Initial Release

NasGameHub 项目首次公开发布。71 files / 11215 lines。

#### ✨ Features
- **25 平台自动扫描归类**: FC / SFC / N64 / GB / GBC / GBA / NDS / 3DS / MD / DC / PS1 / PS2 / PSP / Wii / GC / PCE / NeoGeo / MAME / 街机 / J2ME / DOS / Windows / Flash / HTML
- **双引擎刮削**:
  - **ScreenScraper.fr** 优先（高质量官方元数据）
  - **OpenAI 兼容 AI** (base_url 可配) 用于不规则文件名 + 中文译名自动翻译
  - **内置元数据库** 离线兜底（约 100 款热门老游戏）
  - **LibRetro 数据库** 主机平台兜底
- **手动上传封面** (jpg/png/webp, ≤ 5MB)
- **115 云盘集成**:
  - 扫码登录
  - 全路径 CID 字符串导航 (避免 JS Number 精度丢失)
  - 后台批量导入 + 刮削
- **AI 翻译路径** reasoning model `<think>...` 块剥离
- **Web UI** Vue 3 SPA (单文件，无 build step)
- **远程游玩**:
  - Android 客户端唤起模拟器
  - Windows 客户端唤起模拟器
  - Web 端 Moonlight 串流入口
- **多用户权限**: admin / regular
- **JWT 认证** 7 天有效
- **数据库**: SQLAlchemy ORM + SQLite + WAL

#### 🐛 Fixes
- `database.py` `init_db` 连接生命周期 bug (ALTER TABLE 在关闭的 conn 上静默失败)

#### 📖 Docs
- 中英双语 README / INSTALL / USAGE / DEVELOPMENT / SECURITY

#### ⚠️ Known Limitations
- 客户端 Android 需要 Android Studio 重打 (后续提供 Release APK)
- J2ME 平台目前用模拟器下载站，缺 IME 模拟器原生路径
- 无 GitHub Actions CI (后续加上)
- 无英语 UI (Chinese only)

---

## [0.x] - 历史版本（私有测试期）

2025-09 ~ 2026-09: 私 NAS 测试期，针对以下场景做迭代：
- 单平台 50 个 ROM 批量刮削
- 115 云盘 1000+ 文件全量导入
- 离线环境 (内网无 PyPI) 部署

[Unreleased]: https://github.com/jinws1993/NasGameHub/compare/main...HEAD
[r11.0.0]: https://github.com/jinws1993/NasGameHub/releases/tag/r11.0.0
[0.x]: https://github.com/jinws1993/NasGameHub/commits/main