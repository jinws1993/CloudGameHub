# NASGame Windows 客户端

极简 Python 客户端,使用 PySide6 + 系统 WebView。

## 功能

- 连接 NASGame 服务器 (输入 IP/账号/密码)
- 浏览游戏库 (与 Web 管理界面一致的视图)
- 一键下载 ROM 到本地
- 配置每个平台的模拟器路径
- 点击游戏 → 调用本地模拟器启动
- 自动接管 ROM 文件关联

## 构建

```bash
pip install -r requirements.txt
python main.py            # 开发运行

pyinstaller --onefile --windowed --icon=app.ico main.py
```

## 文件说明

- `main.py` - 应用入口
- `api_client.py` - 服务器 API 客户端
- `downloader.py` - ROM 下载器 (带进度条)
- `settings.py` - 配置存储
- `requirements.txt` - 依赖

## 工作流程

1. 用户启动应用 → 设置服务器地址 → 登录
2. 主界面展示游戏库卡片
3. 点击运行:
   - 检查本地是否已有 ROM
   - 没有则从 NAS 下载 (带进度)
   - 调用配置的模拟器, 传入 ROM 路径
4. 游戏结束 → 自动上报游玩记录
