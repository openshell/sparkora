# Docker 容器化产线部署

> 回链：[系统说明总览](README.md)

本文件说明 Sparkora 前后端的**产线部署方式**：用 `docker compose` 一键起容器。本地热重载联调仍走 `./dev.sh`（`mvn spring-boot:run` + `vite dev`），两者并存、互不干扰。

涉及文件（均为新增，对业务代码零侵入）：

| 文件 | 作用 |
|---|---|
| `Dockerfile` | 后端多阶段镜像（Maven 构建 → JRE21 + Node + `@wenyan-md/cli`） |
| `frontend/Dockerfile` | 前端多阶段镜像（Node 构建 → nginx 托管） |
| `frontend/nginx.conf.template` | nginx 站点模板：静态托管 + `/api` 反代 + SPA fallback（容器启动时 envsubst 渲染） |
| `docker-compose.yml` | backend + frontend 两服务编排 |
| `.dockerignore`（根 + `frontend/`） | 构建上下文排除（`.env` / `target` / `node_modules` / `data` / `.git`） |

---

## 1. 架构与依赖边界

```
浏览器 ──► frontend 容器 (nginx:1.27-alpine)  :80 → 宿主机 ${FRONTEND_PORT}
            ├─ 托管 Vite build 产物（SPA fallback）
            └─ location /api/ → proxy_pass http://backend:${SERVER_PORT}
                                   │ docker 内网 sparkora-net
                                   ▼
           backend 容器 (eclipse-temurin:21-jre + Node + wenyan CLI)
            ├─ sparkora.jar（listen ${SERVER_PORT}）
            └─ 挂卷 ./data:/app/data（渲染临时文件 + 主题 CSS）
                                   │ 出网
        ┌────────────┬─────────────┼────────────┬──────────────┐
        ▼            ▼             ▼            ▼              ▼
   外部 PostgreSQL  axonhub   wenyan-server  searxng      七牛图床
   （.env 配置）    (HTTPS)   (10.126..)     (192.168..)   (HTTPS)
```

**边界**：本仓库只构建 backend + frontend 两个镜像。数据库、AI 入口、wenyan-server、搜索/抓取、图床均为**外部服务**，通过 `.env` 中的地址访问，不随 compose 部署。

---

## 2. 前置要求

- Docker Engine ≥ 20.10 + `docker compose` v2（本机实测 Engine 26.1.5 / Compose v2.32.4）。
- 根目录存在 `.env`（模板 `.env.example`），且其中的外部依赖地址在容器内可达：
  - `SPARKORA_DB_*`（PostgreSQL）
  - `AI_BASE_URL`（axonhub）
  - `WENYAN_MCP_SERVER_URL`（发布通道）
  - `SEARXNG_BASE_URL` / `CRAWL4AI_BASE_URL`（深度研究，可选）
  - `QINIU_*`（图床）

> 镜像内**不含** `.env` 与任何真实凭据；`.env` 由 compose 的 `env_file` 在运行时注入。

---

## 3. 一键启动 / 停止

```bash
# 构建并后台启动（代码变动后必须加 --build，见 §6）
docker compose up -d --build

# 查看状态（backend 应 healthy，frontend running）
docker compose ps

# 查看日志
docker compose logs -f backend
docker compose logs -f frontend

# 停止并移除容器（数据卷 ./data 保留）
docker compose down

# 重启（不重新构建）
docker compose restart backend
```

访问：浏览器打开 `http://<宿主机IP>:${FRONTEND_PORT}`（默认 8088），登录请求经 nginx `/api` 反代到后端容器。

---

## 4. 端口与 `.env` 关系

| 变量 | 含义 | 默认 |
|---|---|---|
| `SERVER_PORT` | 后端容器内监听端口，同时用于 compose 内网与 nginx `proxy_pass` | `5661`（`application.yml` 默认 8080） |
| `BACKEND_PORT` | 后端映射到宿主机的端口 | 等于 `SERVER_PORT` |
| `FRONTEND_PORT` | 前端 nginx 映射到宿主机的访问端口 | `8088` |

- `compose` 端口映射写法：`"${BACKEND_PORT:-5661}:${SERVER_PORT:-5661}"`、`"${FRONTEND_PORT:-8088}:80"`。
- **改 `SERVER_PORT` 无需改 nginx**：`frontend/nginx.conf.template` 用 `${SERVER_PORT}` 占位符，compose 经 `environment` 把该值传入前端容器，nginx 官方镜像的 envsubst 在容器启动时渲染出 `proxy_pass http://backend:<SERVER_PORT>`；改端口后 `docker compose up -d` 重启即生效。
- 前端请求走相对路径 `/api`（`frontend/src/api/http.js`），产线由 nginx 反代，前端代码无需改动。
- **反代超时须 ≥ 前端最长 axios 超时**：`frontend/nginx.conf.template` 的 `proxy_read_timeout`/`proxy_send_timeout` 当前为 `300s`，对齐 `frontend/src/api/index.js` 中最长超时（300000ms，AI 生图/深度写作/多版本生成等）。调任一侧须联动检查，否则长耗时接口会被 nginx 先掐断返回 504，而前端仍在等待。

---

## 5. 数据持久化

宿主机 `./data` 挂载到后端容器 `/app/data`：

| 容器内路径 | 用途 |
|---|---|
| `/app/data/images` | `IMAGE_STORAGE_DIR`；S6 起图片不落本地，仅作根目录 |
| `/app/data/tmp/preview` | wenyan 渲染临时 md（`PreviewService.createTempMd`） |
| `/app/data/tmp/wenyan-themes` | 社区主题 CSS 启动物化（`WenyanThemeCatalog`） |

`docker compose down && docker compose up -d` 后上述目录内容仍在，无需重新 `mvn`/`npm` 构建。

> 后端日志输出到 stdout，用 `docker compose logs` 查看（`dev.sh` 的 `/tmp/sparkora-logs/` 仅用于本地联调）。

---

## 6. 更新镜像

**代码变动后的默认动作**：本地改完后端/前端代码（或拉取最新代码）后，主动执行下面命令重建并滚动替换。compose 只挂载 `./data`，代码**烘焙进镜像**，不重建就不会应用新代码。

```bash
# 代码变动后重建并滚动替换(默认动作,--build 不可省)
docker compose up -d --build

# 仅重建某一服务
docker compose build backend && docker compose up -d backend
```

- `docker compose restart backend` 与裸 `docker compose up -d` **不会重新构建镜像**，仍跑旧代码——改代码后必须带 `--build`。
- `schema.sql` 幂等（`spring.sql.init.mode: always`），容器每次启动都会执行建表/回填，无需手工迁移。

---

## 7. 排障

| 现象 | 排查 |
|---|---|
| backend 一直 `starting`/`unhealthy` | `docker compose logs backend`；确认 `.env` 的 `SERVER_PORT` 与健康检查端口一致；DB 不可达会启动失败 |
| 预览返回降级 HTML（`degraded=true`） | 容器内 `docker compose exec backend wenyan --version`（应输出 `2.0.11`）；检查 `/app/data` 挂卷可写 |
| 前端能开但接口 502 | `docker compose ps` 看 backend 是否 healthy；`docker compose exec frontend sh -c 'curl -s -o /dev/null -w "%{http_code}\n" http://backend:$SERVER_PORT/api/auth/me'`（`$SERVER_PORT` 须在容器内展开，宿主 shell 无此变量；期望 `401`） |
| 健康检查判定 | 探活 `/api/auth/me`，**401 也算存活**（鉴权生效），故不得用 `curl -f`；状态码非 `000` 即通过 |
| 端口冲突（5661/8088 被占） | 改 `.env` 的 `BACKEND_PORT` / `FRONTEND_PORT`，`docker compose up -d` 生效，无需改代码 |
| 容器内时区不对 | compose 已设 `TZ=Asia/Shanghai`（backend） |

---

## 8. 与本地联调的关系

- **产线**：`docker compose up -d --build`（本文件；代码变动后必须重建）。
- **本地热重载联调**：`./dev.sh start|stop|restart|status|logs`（见 [AGENTS.md「Commands」](../AGENTS.md)），行为与容器化改造前完全一致。
- 两者端口独立、互不干扰；本任务未改动 `dev.sh` / `application.yml` / `frontend/vite.config.js` / 任何 Java、Vue 源码。

---

## 9. 回滚

纯新增文件，对现有代码零侵入。回滚 = `docker compose down` + 删除 `Dockerfile`、`frontend/Dockerfile`、`frontend/nginx.conf.template`、`frontend/.dockerignore`、`docker-compose.yml`、`.dockerignore`、`docs/deploy.md`，并还原 `.env.example` / `README.md` / `AGENTS.md` / `docs/README.md` / `docs/spec/overview.md` 的相应段落。`dev.sh` 部署路径始终可用。
