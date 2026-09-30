#!/usr/bin/env bash
# 在 Linux/Mac 主机上构建 APK。需要 Android SDK + JDK 17。
set -euo pipefail

cd "$(dirname "$0")"

if ! command -v ./gradlew >/dev/null 2>&1; then
  echo "未找到 gradlew, 请在 Android Studio 中打开本项目生成 wrapper, 或手动执行 gradle wrapper"
  exit 1
fi

./gradlew assembleDebug "$@"
echo ""
echo "✅ APK 已生成: app/build/outputs/apk/debug/app-debug.apk"
ls -la app/build/outputs/apk/debug/
