# 安装指南 / Installation Guide

> [简体中文](#简体中文) · [English](#english)

---

## 简体中文

### 1. 硬件需求

- **架构**：x86_64 / ARM64（Docker 都支持；树莓派 4B+ 可跑但性能一般）
- **内存**：≥ 1 GB（刮削任务时 +512 MB）
- **磁盘**：≥ 2 GB（不含 ROM 库本身）
- **网络**：能访问 [ScreenScraper.fr](https://www.screenscraper.fr) 和你的 AI API endpoint（推荐；离线模式仅依赖内置元数据库）

### 2. 系统要求

| 操作系统 | Docker 版本 | 测试情况 |
|---|---|---|
| **飞牛 OS / fnOS** | 20.10+ | ✅ 推荐 (作者开发环境) |
| Debian 12 / 11 | 20.10+ | ✅ |
| Ubuntu 22.04 / 24.04 | 20.10+ | ✅ |
| CentOS Stream 9 / RHEL 9 | 20.10+ | ✅ |
| Windows 11 + WSL2 (Ubuntu) | Docker Desktop 4.10+ | ✅（仅开发测试） |
| macOS 13+ (Apple Silicon) | Docker Desktop 4.10+ | ✅（仅开发测试） |

### 3. 准备工作

#### 3.1 安装 Docker（如果还没装）

```bash
# Debian / Ubuntu / fnOS (apt)
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER
newgrp docker

# 验证
docker --version
docker compose version
```

#### 3.2 准备 AI API Key（可选但强烈推荐）

- **OpenAI**：https://platform.openai.com/api-keys
- **国内替代**（任意 OpenAI 兼容端点）：
  - DeepSeek: https://platform.deepseek.com/
  - 通义千问 / 智谱 GLM / MiniMax / Moonshot 等均提供 OpenAI 兼容模式
- 在 Settings → AI 智能识别 里填 base_url + api_key + model 即可

#### 3.3 准备 ScreenScraper.fr 账号（可选）

- 注册: https://www.screenscraper.fr/membre.php
- 申请 **devid / devpass** （论坛置顶帖 https://www.screenscraper.fr/topic/1274）
- 在 Settings → 刮削源 填入

> 不配也没问题——会退回到 AI 识别 + 内置元数据库（覆盖约 100 款热门老游戏）。

### 4. 部署步骤

```bash
# 1. 克隆项目
git clone https://github.com/<你的用户名>/CloudGameHub.git
cd CloudGameHub

# 2. 复制环境配置
cp .env.example .env
nano .env   # 或 vim / VS Code Remote SSH

# 3. 修改必填项 (其余默认即可)
NASGAME_PORT=14322                  # Web UI 端口 (宿主机侧)
NASGAME_ADMIN_USERNAME=admin        # 默认管理员
NASGAME_ADMIN_PASSWORD=请改强密码    # ← 一定要改!
NASGAME_AI_ENABLED=true
NASGAME_AI_API_KEY=sk-xxxxxxxxxxxx  # ← 填你的真实 key

# 4. 创建数据目录 (首次部署)
mkdir -p data/roms data/media data/db data/logs data/config
mkdir -p data/roms/{fc,sfc,n64,gb,gbc,gba,nds,3ds,md,dc,ps1,ps2,psp,wii,gc,pce,neogeo,mame,arcade,j2me,dos,win,flash,html}

# 5. 启动
docker compose up -d

# 6. 查看日志
docker compose logs -f cloudgamehub

# 7. 浏览器访问
#    http://NAS_IP:14322/
#    默认账号: admin / admin123 (如果 .env 改了密码就用新的)
```

### 5. 离线 / 内网部署

如果 NAS 完全无法访问 PyPI / Docker Hub：

#### 5.1 在有网机器上预构建 venv

```bash
# 有网机器 (与 NAS 同架构)
python3.11 -m venv build_venv
source build_venv/bin/activate
pip install -r server/requirements.txt
deactivate
tar -cf venv.tar -C build_venv .   # 注意是 build_venv 内的内容
```

把 `venv.tar` 复制到 NAS 上 `CloudGameHub/` 根目录即可。Dockerfile 检测到 `venv.tar` 会优先使用，跳过 `pip install`。

#### 5.2 镜像导出/导入

```bash
# 有网机器 build + 导出
docker build -t cloudgamehub:latest .
docker save cloudgamehub:latest | gzip > cloudgamehub.tar.gz

# NAS 上
gunzip -c cloudgamehub.tar.gz | docker load
docker compose up -d
```

### 6. 反向代理 (可选)

#### 6.1 Nginx + Let's Encrypt

```nginx
server {
    listen 443 ssl http2;
    server_name cloudgamehub.example.com;

    ssl_certificate     /etc/letsencrypt/live/cloudgamehub.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/cloudgamehub.example.com/privkey.pem;

    client_max_body_size 500M;   # ROM 上传 / 截图上传

    location / {
        proxy_pass http://127.0.0.1:14322;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        # WebSocket (扫描进度推送)
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }
}
```

#### 6.2 Caddy

```caddy
cloudgamehub.example.com {
    reverse_proxy 127.0.0.1:14322 {
        header_up Host {host}
        header_up X-Real-IP {remote_host}
    }
}
```

### 7. 升级

```bash
cd CloudGameHub
git pull
docker compose build
docker compose up -d
# 数据在 ./data 下, 不会被覆盖
```

### 8. 卸载

```bash
cd CloudGameHub
docker compose down        # 停容器
# 删数据 (会丢失所有游戏元数据):
rm -rf data/
# 删镜像:
docker rmi cloudgamehub:latest
```

### 9. 故障排查

| 问题 | 排查 |
|---|---|
| **端口被占** | `lsof -i:14322` 或 `netstat -tlnp \| grep 14322` 看谁占；改 `.env` 的 `NASGAME_PORT` |
| **容器一直重启** | `docker compose logs --tail=50 cloudgamehub` 看启动日志；多半是 `data/` 权限问题 |
| **AI 识别返回 404** | 检查 `.env` 的 `NASGAME_AI_API_KEY` 是否有效；浏览器开发者工具 → Network 看具体错误 |
| **SS 403** | devid/devpass 没填，去 https://www.screenscraper.fr 申请 |
| **115 Cookie 失效** | 重新扫码登录；浏览器开发者工具复制最新 cookie 粘贴 |
| **Web UI 502 / 连不上** | 反向代理配错了；先 `curl http://127.0.0.1:14322/` 看容器本身能不能访问 |

### 10. 性能调优

- **大 ROM 库（>1 万个）**：把 `data/roms/` 拆到独立 SSD 挂载；`- /mnt/games:/data/roms`
- **多用户并发**：改 `docker-compose.yml` 的 `command`，加 `--workers 4`
- **SQLite 优化**：`PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL;` （默认已是 WAL）

---

## English

### 1. Hardware

- **Architecture**: x86_64 / ARM64 (Docker supports both)
- **RAM**: ≥ 1 GB (+512 MB during scrape jobs)
- **Disk**: ≥ 2 GB (excluding the ROM library itself)
- **Network**: access to [ScreenScraper.fr](https://www.screenscraper.fr) and your AI endpoint (recommended)

### 2. Supported systems

| OS | Docker | Notes |
|---|---|---|
| **fnOS (飞牛)** | 20.10+ | ✅ Tested |
| Debian 12 / 11 | 20.10+ | ✅ |
| Ubuntu 22.04 / 24.04 | 20.10+ | ✅ |
| CentOS Stream 9 | 20.10+ | ✅ |
| Windows 11 + WSL2 | Docker Desktop 4.10+ | ✅ (dev only) |
| macOS 13+ | Docker Desktop 4.10+ | ✅ (dev only) |

### 3. Prerequisites

- Docker 20.10+ with Compose v2
- AI API key (any OpenAI-compatible endpoint) — recommended but optional
- ScreenScraper.fr account with devid — optional

### 4. Install

```bash
git clone https://github.com/<your-username>/CloudGameHub.git
cd CloudGameHub
cp .env.example .env
# Edit .env: change NASGAME_ADMIN_PASSWORD and NASGAME_AI_API_KEY
docker compose up -d
# Open http://NAS_IP:14322/
```

### 5. Offline / air-gapped

Pre-build `venv.tar` on a machine with network access (see INSTALL section 5 above).

### 6. Reverse proxy

Nginx / Caddy examples in Chinese section above.

### 7. Upgrade / Uninstall

Same commands as Chinese section. Data in `./data/` is preserved across upgrades.