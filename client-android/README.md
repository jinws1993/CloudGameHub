# NASGame 安卓客户端

基于 Kotlin + Jetpack Compose + Retrofit + Hilt + DataStore。

## 主要功能

1. **连接服务器** - 配置 NAS 地址, 用户名密码登录
2. **浏览游戏库** - 按平台分类, 卡片网格展示, 带封面
3. **搜索/筛选** - 按平台/状态/关键词
4. **下载 ROM** - 流式下载, 进度条, 保存到 App 私有目录
5. **本地运行** - 调用第三方模拟器 (RetroArch/PPSSPP/Drastic 等) 启动
6. **串流游玩** - WebSocket + H.264 视频流 (实验性)
7. **收藏** - 标记喜欢的游戏

## 项目结构

```
app/src/main/java/com/nasgame/
├── App.kt                    # Application + Hilt
├── MainActivity.kt           # Compose 入口
├── ui/
│   ├── LoginScreen.kt
│   ├── LibraryScreen.kt
│   ├── GameDetailScreen.kt
│   ├── SettingsScreen.kt
│   └── theme/
├── data/
│   ├── api/NasGameApi.kt    # Retrofit interface
│   ├── api/Models.kt        # data classes
│   ├── repo/NasGameRepo.kt
│   └── prefs/PrefsStore.kt  # DataStore
├── emulator/EmulatorLauncher.kt
└── stream/StreamClient.kt   # WebSocket + media decoder
```

## 构建

```bash
# 在项目根目录 (含 settings.gradle.kts) 运行:
./gradlew assembleDebug

# APK 输出: app/build/outputs/apk/debug/app-debug.apk
```

## 推荐的安卓模拟器 (按平台)

- **FC/SFC/N64/MD/PSP/NDS/GBA/PS1**: RetroArch
- **PSP**: PPSSPP
- **PS1**: Duckstation
- **NDS**: DraStic (付费) 或 melonDS
- **3DS**: Citra / Azahar
- **PS2**: AetherSX2 (付费)
- **N64**: Mupen64Plus FZ
- **街机**: MAME4droid / FBNeo

在 App 的「模拟器设置」中填入对应包名, App 会用 Intent 启动它们。

## 工作流程

1. **下载模式**: 
   - 用户点击运行 → 后台下载 ROM (OkHttp + Progress) → 下载到 `getExternalFilesDir()/roms/<platform>/<filename>` → 用 `Intent.ACTION_VIEW` + `EXTRA_STREAM` 唤起第三方模拟器
   - 大部分模拟器支持 file:// URI 启动

2. **串流模式**: 
   - App 通过 WebSocket 接收视频流
   - 使用 MediaCodec 硬解码 H.264
   - 通过 SurfaceView 渲染
   - 控制信号通过另一个 WebSocket 发送 (key/joy 事件)

## 兼容性

- minSdk: 26 (Android 8.0)
- targetSdk: 34
- ABI: arm64-v8a (推荐), armeabi-v7a, x86_64
