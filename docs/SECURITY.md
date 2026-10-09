# 安全注意事项 / Security Notes

> [简体中文](#简体中文) · [English](#english)

---

## 简体中文

### 🔒 必须做的

1. **首次部署立即改默认密码**：`admin / admin123` 是公开默认，必须改。
2. **不要把 CloudGameHub 直接暴露公网**：Web UI 默认监听 `0.0.0.0:14322`。如果必须公网访问：
   - 用 **Nginx + Let's Encrypt + Basic Auth** 双层认证
   - 或 **Cloudflare Tunnel**（免费，无需开防火墙端口）
3. **保管好 `data/config/config.json`**：里面以明文存储 AI key / SS 凭据 / 115 cookie。**不要 commit 到 Git**。已在 `.gitignore`。
4. **JWT Token 泄漏检测**：浏览器开发者工具 → Application → Local Storage → `cloudgamehub_token`。泄漏立即**改密码**。

### ⚠️ 已知风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| **AI key 明文存储** | `config.json` 里 plaintext | 已 gitignored；不要备份 config.json 到云 |
| **SQL 注入** | SQLAlchemy ORM 自动转义 | ✅ 安全 |
| **路径穿越** | ROM 扫描/上传有限制 | 仅允许 `data/roms/` 下 |
| **大文件上传** | `client_max_body_size` 默认 100MB | Nginx 反代可调 |
| **JWT 无 revoke** | 改密码不撤销旧 token（有效期 7 天） | 短期内改密码会强制所有设备重登 |
| **SS devid 限流** | 公共 devid 会被 SS 限流 | 注册自己的 devid |

### 🛡️ 凭据管理建议

- **115 Cookie**：7-30 天有效；过期重新扫码
- **AI API Key**：定期轮换（OpenAI 在 Dashboard → API Keys → Revoke）
- **ScreenScraper devid**：在 SS 论坛申请一次性

### 📁 文件权限

```bash
# 推荐权限 (仅 owner 可读写)
chmod 700 data/
chmod 600 data/config/config.json
chmod 700 data/db/
chmod -R 700 data/media/
```

### 🔍 出问题排查

```bash
# 看最近登录
docker compose exec cloudgamehub python -c "
import sqlite3
con = sqlite3.connect('/data/db/cloudgamehub.db')
for row in con.execute('SELECT id, username, created_at FROM users'):
    print(row)
"

# 看错误日志
docker compose logs --tail=200 cloudgamehub | grep -i 'error\|warning'
```

---

## English

### 🔒 Must-do

1. **Change the default password** (`admin / admin123`) on first deploy.
2. **Don't expose CloudGameHub directly to the public internet.** If you must, use Nginx + Let's Encrypt + Basic Auth, or Cloudflare Tunnel.
3. **Protect `data/config/config.json`** — it stores AI keys / SS credentials / 115 cookies in plaintext. Gitignored, but don't back it up to cloud storage.
4. **Monitor the JWT token** — `localStorage.cloudgamehub_token`. If leaked, change password immediately.

### ⚠️ Known risks

See Chinese section.

### 🛡️ Credential hygiene

Rotate AI keys; re-login 115 every 7-30 days; apply for your own SS devid.

### 📁 File permissions

```bash
chmod 700 data/
chmod 600 data/config/config.json
chmod 700 data/db/
chmod -R 700 data/media/
```