# 使用手册 / User Manual

> [简体中文](#简体中文) · [English](#english)

---

## 简体中文

### 1. 首次登录

1. 浏览器访问 `http://NAS_IP:14322/`
2. 默认账号：`admin` / `admin123`
3. **首次登录后会强制要求改密码** —— 改完后会自动登录
4. 进入"概览"页，应该看到 25 个平台已自动初始化（FC / SFC / N64 / ...）

### 2. 准备 ROM

把 ROM 文件放到 `data/roms/<平台>/` 目录下。**两种方式**：

#### 方式 A：手动拷贝（最简单）

```bash
# 通过 SCP / SFTP / SMB / FileBrowser 等上传到 NAS
# 例如:
scp -r "~/Downloads/roms/fc/"  admin@NAS_IP:/vol2/1000/docker/NASGame/data/roms/fc/
```

#### 方式 B：115 云盘导入（无需下载到 NAS）

1. 进入 **云盘 → 115 云盘** 页面
2. 点 **📂 添加云盘目录**
3. 弹窗里浏览 115 目录，选中目录（双击行进入子目录）
4. 选平台 → 点 **📥 导入** → 后台开始导入 + 刮削

### 3. 扫描库

进入 **库浏览** → **🔍 扫描**：

- 选是否覆盖已有元数据 (`force_rescrape`)
- 点 **开始扫描**
- 后台扫描 + 刮削；进度可在 **任务队列** 查看

> 内置元数据库覆盖约 100 款热门老游戏（无 AI/SS 凭据也能识别）；LibRetro 元数据无需凭据，但只覆盖主机平台 + 部分游戏。

### 4. 自定义名字搜索（AI 辅助）

对于不规范的 ROM 文件名（比如 `rom123.gba`、`クロノ・トリガー (Japan).smc`），可以手动告诉 AI 这是什么游戏：

1. 在 **库浏览** 点游戏卡片 → 进入详情
2. 在 **🔍 自定义名字刮削** 输入框输入游戏真实名字（如 `超时空之轮`、`Final Fantasy VII`）
3. 点 **搜索**
4. AI 识别后弹出预览窗口，显示：
   - 中文译名
   - 英文名
   - 中文简介（AI 翻译）
   - 英文简介（原文）
   - 封面预览（如有）
5. 满意后点 **✅ 应用此结果** → 立即更新数据库 + 库列表

### 5. 手动上传封面

AI / SS 都找不到封面时：

1. 在自定义搜索预览窗口的 **封面右下角** 点 **📷 上传封面**
2. 选择本地图片（jpg / png / webp，≤ 5MB）
3. 上传后立即预览 + 写入数据库

### 6. 115 云盘集成

#### 6.1 启用

1. 进入 **设置 → 115 云盘**
2. **Cookie 获取**：点 **🟢 启动扫码登录** → 用 115 App 扫码 → 自动填充到表单
3. 点 **保存配置** + 勾选 **启用**

#### 6.2 导入游戏

1. 进入 **云盘 → 115 云盘**
2. 点 **📂 添加云盘目录**
3. 弹窗里：
   - 双击行进入子目录
   - 点行右侧 **✓ 选中** 单选目录
   - 顶部面包屑可快速跳转父目录
4. 选择平台 → 点 **📥 导入当前目录**
5. 后台开始扫描 + 刮削该目录下所有 ROM

#### 6.3 注意事项

- 115 API 对 IP 风控敏感；批量扫多个目录可能被限速
- Cookie 有效期约 7-30 天，过期后重新扫码登录

### 7. 客户端

#### 7.1 Android 客户端

`client-android/` 目录下是 Android Studio 项目，编译出 APK 后安装：

- 设置里填 NasGameHub 服务器地址（`http://NAS_IP:14322`）
- 登录账号
- 主界面显示库列表 + 远程启动按钮

#### 7.2 Windows 客户端

`client-windows/` 下是 PySide6 + pyinstaller 项目：

```bash
cd client-windows
pip install -r requirements.txt
python main.py   # 开发模式
# 打包:
pyinstaller --name=NasGameHub --windowed --onefile main.py
```

### 8. 远程游玩

#### 8.1 唤起模拟器

- 在 Android 客户端点游戏 → **🖥 远程启动**
- 服务端推送 → Windows 客户端通过 OS 唤起关联的模拟器进程

#### 8.2 Moonlight 串流

- NAS 上需装 NVIDIA/AMD GPU + Sunshine 服务器
- 客户端用 Moonlight / Artemis 连接
- Web UI 也有"在浏览器串流"入口

### 9. 数据备份

定期备份 `data/db/nasgame.db`（SQLite）+ `data/media/`（封面/截图）：

```bash
# 停容器
docker compose stop nasgame

# 备份
tar -czf backup-$(date +%F).tar.gz data/db data/media data/config

# 启动
docker compose start nasgame
```

或用 SQLite 在线备份（不需停服务）：

```bash
sqlite3 data/db/nasgame.db ".backup '/path/to/backup.db'"
```

### 10. 用户管理

管理员可在 **设置 → 用户** 创建/删除用户：

- `is_admin=true` —— 管理员（可扫描、刮削、删游戏、改设置）
- `is_admin=false` —— 普通用户（只能浏览 + 启动游戏）

---

## English

### 1. First login

Visit `http://NAS_IP:14322/`, log in with `admin` / `admin123`, and change the password immediately.

### 2. Add ROMs

Drop ROM files under `data/roms/<platform>/`. The 25 platform codes are: `fc, sfc, n64, gb, gbc, gba, nds, 3ds, md, dc, ps1, ps2, psp, wii, gc, pce, neogeo, mame, arcade, j2me, dos, win, flash, html`. Or import from a 115 cloud disk (UI → Cloud → 115).

### 3. Scan & scrape

**Library → Scan**. Progress is shown in **Tasks** page.

### 4. Custom name search (AI helper)

For irregular filenames (e.g. `rom123.gba`), open a game detail → type the real name in "Custom name scrape" → Search → Preview → Apply.

### 5. Upload cover manually

If both AI and ScreenScraper miss a cover, the preview modal has an "📷 Upload cover" button (jpg/png/webp, ≤ 5MB).

### 6. 115 cloud disk

Enable in **Settings → 115 cloud disk** via QR-code login. Then browse and import directories in **Cloud 115**.

### 7. Clients

- **Android**: `client-android/` — Android Studio project; build APK and configure server URL.
- **Windows**: `client-windows/` — PySide6 + pyinstaller project.

### 8. Remote play

- **Emulator launch** via Android/Windows client
- **Streaming** via Sunshine + Moonlight (Web UI has a stream button)

### 9. Backup

```bash
docker compose stop nasgame
tar -czf backup-$(date +%F).tar.gz data/db data/media data/config
docker compose start nasgame
```

### 10. User roles

Admin creates users in **Settings → Users**. Admins can do everything; regular users can only view and launch games.