#!/usr/bin/env bash
# Sparkora 产线一键部署:拉取代码 → 预拉基础镜像 → 构建 → 启动 → 健康检查
# 用法: ./deploy.sh [--no-pull]
#   ./deploy.sh              # 拉取 main 最新代码并重建启动
#   ./deploy.sh --no-pull    # 跳过 git pull,仅重建启动
#
# 环境变量:
#   DOCKER_MIRROR   基础镜像镜像源(默认 docker.m.daocloud.io;失败后回退 docker.1ms.run 与 Docker Hub)
#   PULL_TIMEOUT    单个镜像源拉取超时秒数(默认 900)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

MIRRORS=("${DOCKER_MIRROR:-docker.m.daocloud.io}" "docker.1ms.run")
PULL_TIMEOUT="${PULL_TIMEOUT:-900}"
BASE_IMAGES=(
  "maven:3.9-eclipse-temurin-21"
  "eclipse-temurin:21-jre-jammy"
  "node:22-alpine"
  "nginx:1.27-alpine"
)
PULL_CODE=1

GREEN='\033[0;32m'; YELLOW='\033[0;33m'; RED='\033[0;31m'; NC='\033[0m'
ok()   { echo -e "${GREEN}[ok]${NC} $*"; }
warn() { echo -e "${YELLOW}[..]${NC} $*"; }
err()  { echo -e "${RED}[err]${NC} $*"; }

usage() { sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'; }

for arg in "$@"; do
  case "$arg" in
    --no-pull) PULL_CODE=0 ;;
    -h|--help) usage; exit 0 ;;
    *) err "未知参数: $arg"; usage; exit 2 ;;
  esac
done

env_value() { # $1=key $2=default -> 读根目录 .env
  local v
  v="$(awk -F= -v k="$1" '$1==k{v=$2} END{gsub(/[[:space:]]/,"",v); print v}' "$SCRIPT_DIR/.env" 2>/dev/null)"
  echo "${v:-$2}"
}

# ---------- 前置检查 ----------
command -v docker >/dev/null 2>&1 || { err "未找到 docker"; exit 1; }
docker compose version >/dev/null 2>&1 || { err "未找到 docker compose v2"; exit 1; }
[ -f "$SCRIPT_DIR/.env" ] || { err "缺少根目录 .env(模板见 .env.example)"; exit 1; }

# ---------- 1. 拉取代码 ----------
if [ "$PULL_CODE" -eq 1 ]; then
  branch="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)"
  if [ "$branch" != "main" ]; then
    warn "当前分支为 $branch,跳过自动拉取(仅 main 自动拉取)"
  elif ! git diff --quiet || ! git diff --cached --quiet; then
    warn "存在未提交改动,跳过自动拉取(避免冲突);如需更新请先提交或暂存"
  else
    ok "拉取 origin/main"
    if ! git pull --no-rebase --no-edit origin main; then
      err "git pull 失败(可能有冲突),请手工处理后重试"
      exit 1
    fi
  fi
else
  warn "--no-pull:跳过代码拉取"
fi

# ---------- 2. 预拉基础镜像(绕开 Docker Hub 直连超时) ----------
ensure_image() {
  local img="$1" m
  if docker image inspect "$img" >/dev/null 2>&1; then
    ok "基础镜像已存在: $img"
    return 0
  fi
  for m in "${MIRRORS[@]}"; do
    warn "拉取 $img (via $m)"
    if timeout "$PULL_TIMEOUT" docker pull "$m/library/$img"; then
      docker tag "$m/library/$img" "$img"
      ok "基础镜像就绪: $img"
      return 0
    fi
    warn "镜像源 $m 失败,回退下一个"
  done
  warn "镜像源均失败,尝试 Docker Hub 直连: $img"
  docker pull "$img"
}

for img in "${BASE_IMAGES[@]}"; do
  ensure_image "$img"
done

# ---------- 3. Flyway 版本冲突早失败 ----------
dups="$(ls src/main/resources/db/migration/V*__*.sql 2>/dev/null \
  | sed -E 's#.*/V([0-9]+)__.*#\1#' | sort -n | uniq -d | tr '\n' ' ')"
if [ -n "$dups" ]; then
  err "检测到重复的 Flyway 版本号: ${dups}"
  ls src/main/resources/db/migration/V*__*.sql | sed -E 's#.*/##' | sort -V | grep -E "^V($(echo "$dups" | tr ' ' '|'))__" || true
  err "请把后合入的迁移改成未占用的版本号后重试(已应用版本不要改名)"
  exit 1
fi
ok "Flyway 版本号无冲突"

# ---------- 4. 构建并启动 ----------
ok "构建镜像并启动容器"
if ! docker compose up -d --build; then
  err "docker compose up 失败,后端最近日志:"
  docker compose logs --tail=80 backend || true
  exit 1
fi

# ---------- 5. 健康检查 ----------
BACKEND_PORT="$(env_value BACKEND_PORT "$(env_value SERVER_PORT 5661)")"
FRONTEND_PORT="$(env_value FRONTEND_PORT 8088)"
warn "等待 backend healthy"
healthy=0
for _ in $(seq 1 120); do
  status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' sparkora-backend 2>/dev/null || echo missing)"
  if [ "$status" = "healthy" ]; then healthy=1; break; fi
  if [ "$status" = "exited" ] || [ "$status" = "missing" ]; then
    err "backend 状态异常: $status"
    docker compose logs --tail=80 backend || true
    exit 1
  fi
  sleep 2
done
if [ "$healthy" -ne 1 ]; then
  err "backend 120s 内未 healthy,最近日志:"
  docker compose logs --tail=80 backend || true
  exit 1
fi
ok "backend healthy"

front_code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "http://127.0.0.1:${FRONTEND_PORT}/" 2>/dev/null || echo 000)"
if [ "$front_code" = "000" ]; then
  err "frontend 不可达(http://127.0.0.1:${FRONTEND_PORT})"
  docker compose logs --tail=40 frontend || true
  exit 1
fi

echo
docker compose ps
echo
ok "部署完成:"
echo "  前端 http://127.0.0.1:${FRONTEND_PORT}"
echo "  后端 http://127.0.0.1:${BACKEND_PORT} (健康检查通过)"
