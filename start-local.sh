#!/usr/bin/env bash
cd "$(dirname "$0")"
export NASGAME_DATA_DIR="$(pwd)/data"
# 启用内置 mock AI 以便在没有 OpenAI key 时也能演示刮削流程
# 真实使用时请去掉这一行, 并在 Web 设置里填入 AI key
export NASGAME_MOCK_AI=${NASGAME_MOCK_AI:-1}
mkdir -p "$NASGAME_DATA_DIR"/{roms,media,db,config,logs}
exec .venv/bin/uvicorn --app-dir server app.main:app --host 0.0.0.0 --port "${NASGAME_PORT:-14322}"
