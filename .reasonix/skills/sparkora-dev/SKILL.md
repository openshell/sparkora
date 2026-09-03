---
name: sparkora-dev
description: Sparkora 项目专用：后台启动/重启前后端联调环境（mvn spring-boot:run + vite dev）、查看日志、验证服务存活。当用户要求启动联调、重启后端、看接口是否正常时使用。
---

# Sparkora 联调环境

Sparkora = Spring Boot 后端（根目录，Maven）+ Vue3/Vite 前端（frontend/）。后端读根目录 `.env`，需要 PostgreSQL 可达。

## 端口

- 后端端口读 `.env` 的 `SERVER_PORT`（当前 **5661**；`application.yml` 兜底 8080）。`frontend/vite.config.js` 把 `/api` 代理到该端口，两者必须一致。
- 前端 dev server 固定 `http://localhost:5173`。

## 一键控制脚本（推荐）

仓库根目录 `./dev.sh` 统一管理启停/状态/日志（日志在 `/tmp/sparkora-logs/`）：

```bash
./dev.sh start                # 启动前后端（默认 all，已运行则跳过；等就绪才返回）
./dev.sh stop [backend|frontend|all]
./dev.sh restart backend      # 只重启后端（改 Java 代码后常用）
./dev.sh status               # 进程 + HTTP 探活（后端探 /api/auth/me，401=存活且鉴权生效）
./dev.sh logs [backend|frontend|all] [行数|-f]
```

- bash 调 dev.sh 时（start/restart 会写 /tmp 日志），用 additional_write_dirs 声明 ["/tmp"]。
- 启动内部用 `setsid nohup bash -c 'exec …'` + 空白 stdin 脱离会话，无需手工加 nohup；重复 start 仅提示跳过，不会起双实例。

## 手动启动（dev.sh 不可用时）

```bash
# 后端（仓库根目录执行）
nohup mvn -q spring-boot:run > /tmp/sparkora-backend.log 2>&1 & echo "backend pid=$!"

# 前端（frontend/ 目录执行）
cd frontend && nohup npm run dev > /tmp/sparkora-frontend.log 2>&1 & echo "frontend pid=$!"
```

bash 需要写 /tmp 日志时，用 additional_write_dirs 声明 ["/tmp"]。

## 重启后端（改了 Java 代码后）

```bash
./dev.sh restart backend
# 或手动：
pkill -f 'spring-boot:run' ; pkill -f 'SparkoraApplication' ; sleep 1
mvn -q -DskipTests compile   # 先确认编译通过再启动（默认 maven 仓库只读时加 -Dmaven.repo.local=/tmp/m2repo）
nohup mvn -q spring-boot:run > /tmp/sparkora-backend.log 2>&1 & echo "backend pid=$!"
```

## 验证存活

```bash
./dev.sh status                        # 一条命令看进程 + HTTP 探活
tail -20 /tmp/sparkora-logs/backend.log    # 看到 Started SparkoraApplication 即就绪
tail -20 /tmp/sparkora-logs/frontend.log   # vite Local: http://localhost:5173/
curl -s -o /dev/null -w '%{http_code}' http://localhost:5661/api/auth/me   # 401 = 服务存活且鉴权生效
```

## 注意

- `/api/**` 全部受 Spring Security 保护，未带 JWT 的 curl 返回 401 属预期；先用 `POST /api/auth/login` 拿 token。
- 后端起不来优先查：PostgreSQL 连接（`.env` 的 `SPARKORA_DB_*`）、`SERVER_PORT` 端口占用（pkill 残留）。
- 数据库 schema 由 `src/main/resources/db/schema.sql` 启动时幂等执行，无需手工建表。
