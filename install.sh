#!/usr/bin/env bash
# CloudGameHub Docker 一键安装脚本 - 在飞牛OS / Linux 主机上运行
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  CloudGameHub Docker 一键安装 / 启动${NC}"
echo -e "${BLUE}========================================${NC}"

# 必要镜像检查
echo -e "${YELLOW}>> 检查基础镜像 python:3.11-slim ...${NC}"
if ! docker images python:3.11-slim --format "{{.Repository}}:{{.Tag}}" 2>/dev/null | grep -q "python:3.11-slim"; then
  echo -e "${RED}未找到 python:3.11-slim 镜像${NC}"
  echo -e "${YELLOW}请在飞牛OS Docker 应用里搜索 'python' 拉取 'python:3.11-slim' 后重新运行此脚本${NC}"
  echo -e "${YELLOW}如果镜像加速器不通, 也可用 python:3.12-slim 或 python:3-slim, 改一下 Dockerfile 第一行即可${NC}"
  exit 1
fi

# 创建默认目录结构
mkdir -p data/roms data/media data/db data/config data/logs
for d in fc sfc n64 gb gbc gba nds 3ds md saturn dc ps1 ps2 psp wii gc pce neogeo mame j2me dos win flash html; do
  mkdir -p "data/roms/$d"
done

# 构建本地 venv 打包 (供 docker build 复用, 跳过 docker 内 apt/pip 联网)
if [ ! -f "venv.tar" ]; then
  echo -e "${YELLOW}>> 打包 Python 虚拟环境...${NC}"
  if [ ! -d ".venv" ]; then
    python3 -m venv .venv
    .venv/bin/pip install --quiet --upgrade pip
    .venv/bin/pip install --quiet -r server/requirements.txt
  fi
  tar -cf venv.tar -C .venv .
  echo -e "${GREEN}   venv.tar 已生成 ($(du -h venv.tar | cut -f1))${NC}"
fi

# Docker build (完全离线)
echo -e "${YELLOW}>> 构建 Docker 镜像...${NC}"
docker build -t cloudgamehub:latest . 2>&1 | tail -10

# 启动
if docker compose version >/dev/null 2>&1; then
  COMPOSE_CMD="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE_CMD="docker-compose"
else
  echo -e "${RED}未找到 docker compose${NC}"
  exit 1
fi

# 停掉旧容器 (如果存在)
docker rm -f cloudgamehub 2>/dev/null || true

echo -e "${YELLOW}>> 启动容器...${NC}"
$COMPOSE_CMD up -d

# 等服务就绪
PORT=${NASGAME_PORT:-14322}
echo -e "${GREEN}>> 等待服务就绪...${NC}"
for i in {1..30}; do
  if curl -fsS http://localhost:$PORT/ >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

HOST_IP=$(hostname -I 2>/dev/null | awk '{print $1}')
[ -z "$HOST_IP" ] && HOST_IP="localhost"

echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  ✅ CloudGameHub Docker 部署成功${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo -e "  管理界面:  ${BLUE}http://${HOST_IP}:${PORT}/${NC}"
echo -e "  API 文档:  ${BLUE}http://${HOST_IP}:${PORT}/api/docs${NC}"
echo -e "  默认账号:  ${YELLOW}admin / admin123${NC} (登录后请修改)"
echo ""
echo -e "  📁 ROM 存放: ${YELLOW}${DIR}/data/roms/${NC}"
echo "     推荐按平台分子目录: fc/ sfc/ ps1/ ps2/... 系统自动识别"
echo ""
echo -e "  📋 常用命令:"
echo -e "     ${BLUE}${COMPOSE_CMD} logs -f${NC}       # 查看日志"
echo -e "     ${BLUE}${COMPOSE_CMD} restart${NC}       # 重启"
echo -e "     ${BLUE}${COMPOSE_CMD} down${NC}          # 停止并删除容器"
echo -e "     ${BLUE}docker build -t cloudgamehub:latest . && ${COMPOSE_CMD} up -d${NC}  # 升级"
echo ""