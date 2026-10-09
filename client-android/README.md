# NASGame 安卓客户端

> **单 App 私有游戏库** —— 装一个 APK 就完事, 不需要 NAS, 不需要服务器。
> ROM 全部放在 115 网盘上, 玩的时候才下到手机, 玩完删掉也不心疼。

基于 Kotlin + Jetpack Compose + Hilt + DataStore + 裸 OkHttp。

---

## 一句话说清它怎么工作

```
装 APK
  → 连 115 (扫码 / Cookie)
  → 在 115 里挑一个 ROM 目录
  → 扫描建库 + AI 刮削 (封面/中文译名/简介)
  → 库里点游戏 → 🎮 游玩
       ├─ 手机已有 ROM? ── 是 ──→ 直接唤起 RetroArch
       └─ 没有 → 115 直链下载到本地 (断点续传) → 唤起 RetroArch
```

**没有中间服务器。** 115 的 302 就是手机自己跟: 拿直链 → 手机 GET 115 CDN → 落盘。

---

## 第一次用必做的两件事

### 1. 装 RetroArch

RetroArch 是唯一推荐的模拟器 —— 25 个平台全覆盖, 而且 App 会自动帮你把对应的
libretro 核心下好。

App 设置页有跳转按钮, 也可以直接去
[retroarch.com](https://www.retroarch.com/?page=platforms) 下 APK。

自动识别的包名:

| 包名 | 是什么 |
|------|--------|
| `com.retroarch.aarch64` | 官网 64 位版 (最常见) |
| `com.retroarch` | 官网 32 位 / Play 商店版 |
| `com.retroarch.ra32` | 官网 32 位新版 |
| `org.libretro.ra2` | RetroArch 2 (nightly 主线) |
| `org.fdroid.retroarch` | F-Droid 版 |

### 2. 把 ROM 目录设成公共位置 ⚠️

**这一步不做, 点了「游玩」也开不了游戏。**

默认的 App 私有目录 (`/sdcard/Android/data/com.nasgame/files/roms`) **RetroArch
读不到** —— Android 11+ 上别的 app 无权访问别的 app 的私有目录。

设置 →「ROM 存放位置」→ 选一个公共目录, 比如 `/sdcard/RetroArch/roms`。
App 会自动按平台建子目录 (`FC/`、`SFC/`…)。

检测到还在私有目录时, 设置页会飘红字提醒。

---

## 目录结构

```
app/src/main/java/com/nasgame/
├── App.kt                          # Application + Hilt
├── MainActivity.kt                 # 入口, 启动时检查 115 登录态
├── data/
│   ├── model/Models.kt             # Platform(25 个内置) + Game + ScanState
│   ├── db/NasDb.kt                 # SQLiteOpenHelper, 手写 DAO
│   ├── prefs/PrefsStore.kt         # 全部本地设置 (无服务器地址/无 token)
│   ├── pan115/
│   │   ├── Pan115Client.kt         # 115 webapi: 列目录 / 直链 / 递归 walk
│   │   └── QrLogin.kt              # 扫码登录 + 二维码渲染 (ZXing core)
│   ├── scan/RomScanner.kt          # 文件名解析 + 平台归类
│   ├── scrape/
│   │   ├── AiClient.kt             # OpenAI 兼容接口 + libretro 缩略图
│   │   └── ScrapeEngine.kt         # 刮削编排 (多源兜底)
│   ├── media/MediaStore.kt         # 封面本地存储
│   ├── download/DownloadManager.kt # 115 直连下载 + 断点续传
│   ├── play/PlayCoordinator.kt     # 游玩总编排
│   └── repo/LibraryRepo.kt         # 库读写 + 扫描编排
├── emulator/
│   ├── EmulatorLauncher.kt         # 唤起模拟器 (含 RA intent 契约)
│   ├── RetroArchManager.kt         # RA 探测 / 核心目录 / 核心下载
│   └── RomHandle.kt                # 本地 ROM 的可启动句柄
└── ui/
    ├── NavGraph.kt                 # 导航
    └── screen/                     # 连接 / 选目录 / 扫描 / 库 / 详情 / 下载 / 设置
```

---

## 115 相关

### 登录

- **扫码** (推荐): 生成二维码 → 用 115 App 扫 → 轮询确认
- **Cookie**: 浏览器登录 115.com → F12 → Network → 任一请求 → 复制 Cookie
  (只要 `UID` / `CID` / `SEID` / `KID` 那几段)

Cookie 只存在这台手机上, 不上传到任何地方。约一周过期, 过期后重新登录即可。

### 两个必须记住的坑 (代码里都处理了)

1. **cid 全程用字符串**。115 的 cid 是 64 位的, 任何一步转成浮点都会丢精度,
   然后列目录就变成"服务器开小差"。

2. **CDN 直链要自己带 cookie**。`files/download` 返回的 `file_url` 指向另一个
   域名的 CDN, cookie jar 因为域名不匹配**不会**自动带上 session cookie,
   裸 GET 会 403 (`ua not match cookie`)。所以下载时显式把 jar 拼成 Cookie 头。
   另外 115 对 Chrome UA 特别敏感反而会 403, 所以下载用的是另一个 UA。

### 用的接口

| 用途 | 接口 |
|------|------|
| 验 cookie | `GET /files?aid=1&cid=0&limit=1&show_dir=1&format=json` |
| 列目录 | `GET /files?aid=1&cid=..&show_dir=1&o=user_ptime&asc=1&fc_mix=0&natsort=1&format=json` |
| 取路径 | `GET /files/category?cid=..` |
| 下载直链 | `GET /files/download?pickcode=..&_=1` |
| 扫码 token | `GET https://qrcodeapi.115.com/api/1.0/web/1.0/token` |
| 扫码轮询 | `GET https://qrcodeapi.115.com/get/status/?uid=&time=&sign=` |

> 列表接口必须是 `/files` 而不是 `/files/list`, 参数也不能少, 否则报"服务器开小差"。

---

## 刮削

策略是**多源兜底**, 任何一环挂了都不会让整个流程停摆:

```
1. AI (OpenAI 兼容)  → 标题/年份/厂商/类型/简介/中文译名
2. libretro 缩略图    → 封面 (免费无 key)
3. 都没有             → 至少文件名清洗后的标题能用
```

**AI 是可选的。** 没配 key 也能跑, 只是少了简介和中文译名 —— 很多用户没有可用的
AI 接口, 不能因此卡住。

支持的 AI: 任何 OpenAI 兼容的 `/chat/completions` 就行, 比如
OpenAI / DeepSeek / Kimi / 通义 / MiniMax, 本地 Ollama 也行
(base_url 填 `http://localhost:11434/v1`)。

> 处理了两个模型怪癖: 推理模型 (DeepSeek-R1 / o1 / MiniMax-M2) 会先吐一整段
> `<think>...</think>`, 里面常有没闭合的 JSON —— 剥掉才 parse; 以及有些模型
> 会给 JSON 套 markdown 代码块。

---

## 平台 → 核心对照

| 平台 | 首选核心 | 备选 |
|------|----------|------|
| FC | `fceumm` | `nestopia`, `quicknes` |
| SFC | `snes9x` | `snes9x2010`, `snes9x2005`, `snes9x2002`, `mednafen_snes` |
| N64 | `mupen64plus_next_gles3` | `mupen64plus_next_gles2` |
| GB / GBC | `gambatte` | `sameboy` |
| GBA | `mgba` | `mednafen_gba` |
| NDS | `melonds` | `desmume`, `desmume2015` |
| 3DS | `citra` | — |
| MD | `genesis_plus_gx` | `genesis_plus_gx_wide`, `picodrive` |
| SATURN | `yabasanshiro` | `mednafen_saturn` |
| DC | `flycast` | — |
| PS1 | `swanstation` | `pcsx_rearmed`, `mednafen_psx` |
| PSP | `ppsspp` | — |
| WII / GC | `dolphin` | — |
| PCE | `mednafen_pce_fast` | `mednafen_pce`, `neocd` |
| NEOGEO | `fbneo` | `mame2003_plus` |
| MAME / ARCADE | `mame2003_plus` | `mame2016`, `mamearcade`, `fbneo` |
| DOS | `dosbox_pure` | `dosbox_svn`, `dosbox` |

核心来源: `https://buildbot.libretro.com/nightly/android/latest/<abi>/<core>_libretro_android.so.zip`,
按设备 ABI 自适应 (`arm64-v8a` / `armeabi-v7a` / `x86_64` / `x86`)。

**buildbot 在国内经常连不上**, 这时候设置页会提示你去 RetroArch 的
「在线更新 → 核心下载」里手动装 —— App 的 [RetroArchManager](
app/src/main/java/com/nasgame/emulator/RetroArchManager.kt) 会自动发现
RetroArch 已经装好的核心。

**RA 没有核心的平台** (buildbot 上确实不存在, 需要独立 App):

- `PS2` → AetherSX2 (`xyz.aethersx2`)
- `J2ME` → FreeJ2ME / J2ME Loader
- `WIN` → Wine / BoxedWine
- `FLASH` → Ruffle
- `HTML` → 内置浏览器

---

## RetroArch 启动契约

RA 不认 `ACTION_VIEW`, 认自己那套 extra。代码里按官方契约拼, 并且**逐级降级**:

```
1. component = <pkg>/com.retroarch.browser.retroactivity.RetroActivityFuture
     + ROM / LIBRETRO / CONFIGFILE / IME
2. action = org.libretro.android.action.LOAD_CONTENT  (部分构建只认这个)
3. 主界面 + ROM extra
4. 系统 chooser
```

踩过的坑 (都在 [EmulatorLauncher](app/src/main/java/com/nasgame/emulator/EmulatorLauncher.kt)
里处理了):

1. **`LIBRETRO` 必须是完整路径**, 不能只给 core 文件名 —— 2025-01-17 之后的
   nightly 改过 (libretro/RetroArch#17433), 只给名字会 core 加载失败。
2. **`ROM` 最好是真实文件路径**。content:// 只有部分构建能吃。
3. **RA 读不到 app 私有目录**, 所以 ROM 要放公共目录 (见上文)。
4. 不同构建能认的东西不一样, 所以要逐级降级, 不能一发 intent 就放弃。

---

## 构建

```bash
cd client-android
./gradlew assembleDebug      # 需要 JDK 17 + Android SDK 34
```

产物: `app/build/outputs/apk/debug/app-debug.apk`

> 没有 `gradlew` 的话用 Android Studio 打开本目录生成 wrapper。

release 包用仓库自带的 keystore (`client-android/keys/`), debug 和 release
共用同一把钥匙, 所以可以覆盖安装不用先卸载。

---

## 调不通时看这里

| 现象 | 大概率原因 |
|------|-----------|
| "手机上还没装 RetroArch" | 没装。点详情页的"装 RetroArch"按钮 |
| "无法被外部唤起" | 该 RA 版本没开放外部启动接口。换官网版, 或先手动开一次 RA 授权存储 |
| RA 起来了但黑屏 | 核心没装上。到设置 → RetroArch 集成 → 点"装核心"看具体报错 |
| "ROM 放在 App 私有目录里" | 改 ROM 存放路径到公共目录 |
| 115 登录说 cookie 失效 | 115 的 cookie 大概一周过期了, 重新登录一次 |
| 列目录报"服务器开小差" | 检查 115 cookie, 以及目录 cid 是否正确 |
| 核心下载失败 | buildbot 国内不稳。去 RetroArch 里手动装 |
| 扫描没找到游戏 | 目录选深了 (默认 5 层), 或者文件扩展名不在 25 平台列表里 |
| PS2 / J2ME / DOS 开不了 | buildbot 没这些核心, 需要装独立 App |

---

## 许可

RetroArch 及 libretro 核心均为 GPLv3 / 各自独立许可, 从官方渠道获取。
本仓库不分发任何 ROM, 也不分发任何核心二进制。
