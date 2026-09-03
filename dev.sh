#!/usr/bin/env bash
# Sparkora 联调环境控制脚本:后端(Spring Boot)+ 前端(Vite)的启停 / 状态 / 日志
# 用法: ./dev.sh {start|stop|restart|status|logs} [backend|frontend|all]
# 示例:
#   ./dev.sh start              # 启动前后端(默认 all)
#   ./dev.sh restart backend    # 只重启后端
#   ./dev.sh logs backend -f    # 跟随后端日志(-f 跟踪,数字参数指定行数)
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FRONTEND_DIR="$SCRIPT_DIR/frontend"
LOG_DIR="/tmp/sparkora-logs"
BACKEND_LOG="$LOG_DIR/backend.log"
FRONTEND_LOG="$LOG_DIR/frontend.log"

# 后端端口:读根目录 .env 的 SERVER_PORT(须与 vite 代理一致),缺省 8080
SERVER_PORT="$(awk -F= '/^SERVER_PORT=/{v=$2} END{gsub(/[[:space:]]/,"",v); print (v==""?"8080":v)}' "$SCRIPT_DIR/.env" 2>/dev/null)"
SERVER_PORT="${SERVER_PORT:-8080}"
FRONTEND_PORT=5173
START_TIMEOUT="${START_TIMEOUT:-90}"   # 等待服务就绪的秒数

GREEN='\033[0;32m'; YELLOW='\033[0;33m'; RED='\033[0;31m'; NC='\033[0m'
mkdir -p "$LOG_DIR"
ok()   { echo -e "${GREEN}[ok]${NC} $*"; }
warn() { echo -e "${YELLOW}[..]${NC} $*"; }
err()  { echo -e "${RED}[err]${NC} $*"; }

usage() {
  sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//'
}

# ---------- 探测 ----------
http_code() { # $1=url -> 输出 HTTP 状态码(失败为 000)
  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "$1" 2>/dev/null)"
  echo "${code:-000}"
}

pids_of() { # $1=backend|frontend -> 匹配到的进程 pid(可多个)
  case "$1" in
    # mvn 包装进程 + fork 出来的 java 子进程都算 backend
    backend)  pgrep -f 'spring-boot:run|com\.sparkora\.SparkoraApplication' 2>/dev/null ;;
    # 仓库可能经 symlink 访问(如 /data -> /dockerData),用 realpath 归一化后限定匹配
    frontend) local dir; dir="$(realpath -m "$FRONTEND_DIR" 2>/dev/null || echo "$FRONTEND_DIR")"
              pgrep -f "$dir.*vite|sh -c vite" 2>/dev/null ;;
  esac
}

wait_http() { # $1=url $2=name -> HTTP 可达(非 000)即视为就绪
  local i=0 code
  while [ "$i" -lt "$START_TIMEOUT" ]; do
    code="$(http_code "$1")"
    if [ "$code" != "000" ]; then return 0; fi
    echo -n "."
    sleep 1
    i=$((i + 1))
  done
  echo
  return 1
}

# ---------- 动作 ----------
start_one() { # $1=backend|frontend
  local name="$1" pids
  pids="$(pids_of "$name")"
  if [ -n "$pids" ]; then
    ok "$name 已在运行(pid:$(echo "$pids" | tr '\n' ' ')),跳过"
    return 0
  fi
  case "$name" in
    backend)
      echo "启动 backend: mvn spring-boot:run(端口 $SERVER_PORT,日志 $BACKEND_LOG)"
      # bash -c 'exec ...':确保 mvn 成为会话首进程;三重空白 stdin 规避宿主环境的管道悬挂问题
      (cd "$SCRIPT_DIR" && setsid nohup bash -c "exec mvn -q spring-boot:run </dev/null </dev/null </dev/null >>\"$BACKEND_LOG\" 2>&1" </dev/null </dev/null >/dev/null 2>&1 &)
      if wait_http "http://localhost:$SERVER_PORT/api/auth/me" "$name"; then
        ok "backend 就绪: http://localhost:$SERVER_PORT"
      else
        err "backend 未在 ${START_TIMEOUT}s 内就绪,常见原因:PostgreSQL 未启动 / SERVER_PORT 被占用;查看: $0 logs backend | tail -50"
        return 1
      fi
      ;;
    frontend)
      echo "启动 frontend: npm run dev(端口 $FRONTEND_PORT,日志 $FRONTEND_LOG)"
      if [ "$(http_code "http://localhost:$FRONTEND_PORT/")" != "000" ]; then
        warn "端口 $FRONTEND_PORT 已被占用且非本脚本管理的 vite 进程;vite 可能会改用 5174 端口,请检查"
      fi
      # 同 backend:exec 成会话首进程 + 三重空白 stdin 规避宿主管道悬挂(不影响日志输出)
      (cd "$FRONTEND_DIR" && setsid nohup bash -c "exec npm run dev </dev/null </dev/null </dev/null >>\"$FRONTEND_LOG\" 2>&1" </dev/null </dev/null >/dev/null 2>&1 &)
      if wait_http "http://localhost:$FRONTEND_PORT/" "$name"; then
        ok "frontend 就绪: http://localhost:$FRONTEND_PORT"
      else
        err "frontend 未在 ${START_TIMEOUT}s 内就绪,查看: $0 logs frontend | tail -50"
        return 1
      fi
      ;;
  esac
}

stop_one() { # $1=backend|frontend
  local name="$1" pids i=0
  pids="$(pids_of "$name")"
  if [ -z "$pids" ]; then
    ok "$name 未运行"
    return 0
  fi
  case "$name" in
    backend)
      pkill -TERM -f 'spring-boot:run' 2>/dev/null
      pkill -TERM -f 'com\.sparkora\.SparkoraApplication' 2>/dev/null
      ;;
    frontend)
      local dir; dir="$(realpath -m "$FRONTEND_DIR" 2>/dev/null || echo "$FRONTEND_DIR")"
      pkill -TERM -f "$dir.*vite" 2>/dev/null
      pkill -TERM -f 'sh -c vite' 2>/dev/null
      ;;
  esac
  # 等待退出,最多 10s,残留则强杀;npm/sh 父进程会在 vite 退出后自行结束
  while [ "$i" -lt 20 ] && [ -n "$(pids_of "$name")" ]; do
    sleep 0.5
    i=$((i + 1))
  done
  if [ -n "$(pids_of "$name")" ]; then
    local dir; dir="$(realpath -m "$FRONTEND_DIR" 2>/dev/null || echo "$FRONTEND_DIR")"
    pkill -KILL -f 'spring-boot:run|com\.sparkora\.SparkoraApplication' 2>/dev/null
    pkill -KILL -f "$dir.*vite|sh -c vite" 2>/dev/null
    warn "$name 未能优雅退出,已强制杀死"
  else
    ok "$name 已停止"
  fi
}

status_one() { # $1=backend|frontend
  local pids code url probe
  pids="$(pids_of "$1")"
  case "$1" in
    backend)  url="http://localhost:$SERVER_PORT"; probe="$url/api/auth/me" ;;
    frontend) url="http://localhost:$FRONTEND_PORT"; probe="$url/" ;;
  esac
  if [ -z "$pids" ]; then
    warn "$1: 未运行"
  else
    # 401 = 服务存活且鉴权生效;其他非 000 状态码同样说明端口可达
    code="$(http_code "$probe")"
    ok "$1: 运行中(pid:$(echo "$pids" | tr '\n' ' '))  $url  探活 $probe → HTTP $code"
  fi
}

logs_cmd() { # 参数顺序无关: [backend|frontend|all] [-f|--follow] [行数],如 logs -f backend
  local follow=0 n=100 target="all" arg
  for arg in "$@"; do
    case "$arg" in
      -f|--follow) follow=1 ;;
      backend|frontend|all) target="$arg" ;;
      ''|*[!0-9]*) err "不支持的参数: $arg(只支持 -f、目标 backend|frontend|all 和行数)"; exit 1 ;;
      *) n="$arg" ;;
    esac
  done
  local files=()
  case "$target" in
    backend)  files=("$BACKEND_LOG") ;;
    frontend) files=("$FRONTEND_LOG") ;;
    all)      files=("$BACKEND_LOG" "$FRONTEND_LOG") ;;
    *) err "未知目标: $target(backend|frontend|all)"; exit 1 ;;
  esac
  local f
  for f in "${files[@]}"; do
    if [ ! -f "$f" ]; then
      warn "日志不存在: $f($0 start 后生成)"
      continue
    fi
    echo "==> $f(最近 $n 行)<=="
    if [ "$follow" = "1" ]; then tail -F -n "$n" "${files[@]}"; return; fi
    tail -n "$n" "$f"
    echo
  done
}

# ---------- 分发 ----------
cmd="${1:-help}"
[ $# -gt 0 ] && shift
case "$cmd" in
  start|stop|restart|status)
    target="${1:-all}"
    case "$target" in
      backend|frontend|all) ;;
      *) err "未知目标: $target(可选 backend|frontend|all)"; usage; exit 1 ;;
    esac
    targets=("$target")
    [ "$target" = "all" ] && targets=(backend frontend)
    for t in "${targets[@]}"; do
      case "$cmd" in
        start)   start_one "$t" || exit 1 ;;
        stop)    stop_one  "$t" ;;
        restart) stop_one "$t"; start_one "$t" || exit 1 ;;
        status)  status_one "$t" ;;
      esac
    done
    ;;
  logs)
    logs_cmd "$@"
    ;;
  help|-h|--help)
    usage
    ;;
  *)
    err "未知命令: $cmd"
    usage
    exit 1
    ;;
esac