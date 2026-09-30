#!/usr/bin/env bash
# 用 NAS 的 git config 中已有的 https proxy，把 NASGitHubHub v1.2.0 push 上去
# 用法: GH_TOKEN=ghp_xxx ./push-with-token.sh
set -euo pipefail
cd "$(dirname "$0")"

if [[ -z "${GH_TOKEN:-}" ]]; then
  echo "请先设置 GitHub token:  export GH_TOKEN=ghp_xxx" >&2
  exit 1
fi

# 用 url-embedded credential (仅本次 push 临时)
git push "https://x-access-token:${GH_TOKEN}@github.com/jinws1993/NasGameHub.git" main 2>&1

echo ""
echo "✅ Push 完成."
echo "   GitHub Actions 会在 ~5 分钟内构建 APK 并上传到 v1.2.0 release."
echo "   监控: https://github.com/jinws1993/NasGameHub/actions"