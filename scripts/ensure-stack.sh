#!/usr/bin/env bash
# 一键恢复本机 Aetheris 全栈：等待 Docker 引擎可用 → compose up → 等待 16 容器健康 → 学习者冒烟。
# Docker Desktop 未启动时先手动打开它（脚本不会启动桌面应用）。
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

echo "== waiting for docker engine =="
ready=0
for _ in $(seq 1 60); do
  if docker info >/dev/null 2>&1; then ready=1; break; fi
  sleep 5
done
if [ "$ready" -ne 1 ]; then
  echo "Docker engine unavailable. Start Docker Desktop first, then rerun this script." >&2
  exit 1
fi

echo "== compose up =="
docker compose up -d

echo "== waiting for healthy containers =="
healthy=0
for _ in $(seq 1 60); do
  healthy=$(docker compose ps --format '{{.Status}}' | grep -c 'healthy' || true)
  if [ "$healthy" -ge 16 ]; then break; fi
  sleep 5
done
echo "healthy containers: $healthy"
if [ "$healthy" -lt 16 ]; then
  docker compose ps --format '{{.Name}} {{.Status}}' >&2
  exit 1
fi

echo "== learner smoke =="
node scripts/learning-smoke.mjs