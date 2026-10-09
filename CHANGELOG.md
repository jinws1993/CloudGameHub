# Changelog / 更新日志

所有 **notable changes** 都会记录在此文件。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

---

## [Unreleased]

### 计划中
- 后台下载 (下完通知, 支持息屏下载)
- 自动清理本地 ROM (按 LRU / 容量上限)
- 多 115 账号切换

---

## [r12.0.0] - 2026-10-09

### 🔥 架构大改: 单 App, 不再需要 NAS

**安卓客户端重写成完全独立的单 APK。** 之前是 `115 → NAS(Docker) → 手机`,
现在直接 `115 → 手机`, 中间没有任何服务器。

```
旧:  115 → NAS (Docker 服务端) → Android 客户端
新:  115 → Android 客户端 (装完就能用)
```

#### 💥 移除了什么

- `server/` 提供的全部运行时能力 —— 扫描、刮削、115 代理、库管理, 全部移植进 App
- Retrofit / kotlinx.serialization / 认证 token / 服务器地址
  (依赖也从 `build.gradle.kts` 里删干净了)
- 登录注册流程 —— 现在只有 115 自己的 cookie, 且**只存在本机**
- `data/api/` · `data/repo/NasGameRepo.kt` · `stream/` 整个包

`server/` 和 `client-windows/` 的代码暂时保留在仓库里作为历史实现参考, 但已停更。

#### ✨ App 里新增了什么

**115 客户端 (`data/pan115/`)**
- cookie 登录 + 扫码登录 (ZXing core 生成二维码)
- 目录浏览 / 面包屑 / 递归 walk (显式栈, 带深度上限和安全阀)
- 拿 115 CDN 直链 —— 这就是「302 直连」, 手机自己跟, 流量不经任何中间人
- 踩坑记录写进注释: cid 必须全程用字符串 (64 位, 转浮点就丢精度);
  CDN 域与 webapi 不同所以 cookie jar 不会自动带上, 必须显式拼 Cookie 头;
  Chrome UA 反而会被 403

**本地库 (`data/db/NasDb.kt`)**
- `SQLiteOpenHelper` 手写 DAO, 没引 Room (少一个 KSP processor, 少一份编译风险)
- 游戏表存的是 **115 pickcode**, ROM 本体永远不入库
- `local_path` / `local_size` 标记"已经下到手机了"
- 25 个平台定义内置在 `data/model/Models.kt`, 不再依赖服务端 seed

**扫描归类 (`data/scan/RomScanner.kt`)**
- 文件名清洗: `(USA) (Rev 1) [!]` 标签 / 地区 / 语言 / 光盘号 / 年份
- 平台判定**目录名优先**: `iso`/`cue`/`bin` 十几个平台都在用, 光看扩展名
  没法区分, 而多数人 115 上的目录结构是 `/roms/PS1/...` 这种
- 支持压缩包内层扩展名 (`xxx.nes.zip`)

**刮削 (`data/scrape/`)**
- AI 走 OpenAI 兼容 `/chat/completions` —— DeepSeek / Kimi / 通义 / Ollama 都能用
- **AI 是可选的**: 没配 key 也能跑, 只是少了简介和中文译名
- 兜底: libretro 官方缩略图 (免费无 key) + 文件名清洗
- 处理了推理模型的 `<think>` 块和 markdown 代码块包裹的 JSON
- 手动上传封面 (SAF 选图片)

**下载 (`data/download/DownloadManager.kt`)**
- 115 直链 + `Range` 断点续传 (下到 `.part`, 断了接着下)
- 115 直链有时效, 每次重试重新取
- 三种存储: SAF (默认, 推荐) / 公共绝对路径 / App 私有

**游玩 (`data/play/PlayCoordinator.kt`)**
- 本地有 → 直接开; 没有 → 下载 → 自动开
- RA 缺核心 → 自动装 (libretro buildbot, 按 ABI 自适应)
- 失败有具体可读原因, 不再是"点了没反应"

#### 🕹️ RetroArch 集成 (这轮的硬菜)

- **自动探测 5 种包名变体**, 含 RetroArch 2 (`org.libretro.ra2`)
- **修掉 Android 11+ 检测不到 RA 的根因** —— 缺 `<queries>` 声明,
  `getPackageInfo()` 一律返回 null, 这是之前必现的 bug
- **25 平台 → libretro core 映射**, 名单对着 buildbot 实际目录核过
- **修掉 `LIBRETRO` 只给文件名导致加载失败** (2025-01-17 nightly 改动,
  见 libretro/RetroArch#17433) —— 现在给完整路径
- **逐级降级**: component → LOAD_CONTENT action → 主界面 → 系统 chooser
- **修掉 SAF 模式打开一片空白** —— 之前把一个**根本不存在**的
  `File.absolutePath` 喂给了模拟器。现在引入 `RomHandle` 显式区分
  `content://` 和真实路径, SAF 目录会把 tree URI 反推回 `/storage/emulated/0/...`
- **App 私有目录 RetroArch 读不到** —— 这点在设置页会主动飘红字提醒,
  因为它就是"点了游玩没反应"的头号原因

#### 📱 UI

连接(115) → 选目录 → 扫描刮削 → 库 → 详情游玩 → 下载管理 → 设置

详情页只留一个 **🎮 游玩** 按钮: 本地有就开, 没有就下完再开。封面也能直接点。

#### 🔧 顺手修的

- `proguard-rules.pro` 之前根本不存在, 但 release build 引用了它,
  `assembleRelease` 必挂。已补齐。
- 115 `list_dir` 的 `int()` 精度隐患 → Kotlin 侧全程 String
- 几个文件里的 U+FFFD 编码损坏注释

#### 📖

- `client-android/README.md` 完全重写
- `README.md` 顶部加了架构调整公告

#### ⚠️ 已知限制

- libretro buildbot 在国内经常连不上, 核心装不上时会明确提示用户去
  RetroArch 里手动装 (App 会自动发现 RA 已装的核心)
- PS2 / J2ME / DOS 没有 Android 核心, 需要独立 App
- **没有在真机上跑过完整流程** —— 开发环境没有 Android SDK 和真机,
  代码经过逐行审查和静态检查 (括号平衡 / 符号解析 / 悬空引用扫描), 但没有实机验证

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