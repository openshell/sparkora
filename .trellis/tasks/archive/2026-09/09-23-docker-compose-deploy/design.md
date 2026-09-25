# 技术设计：Docker 容器化产线部署

## 1. 架构与边界

```
                 ┌─────────────────────────────────────────────┐
  浏览器 ──────► │ frontend 容器 (nginx:alpine)                 │
                 │  - 托管 Vite build 产物 (dist/)              │
                 │  - location /api/ → proxy_pass backend:5661 │
                 │  - SPA fallback: try_files ... /index.html  │
                 └───────────────┬─────────────────────────────┘
                                 │ docker 内部网络 (sparkora-net)
                                 ▼
                 ┌─────────────────────────────────────────────┐
                 │ backend 容器 (eclipse-temurin JRE21 + Node)  │
                 │  - sparkora.jar                              │
                 │  - @wenyan-md/cli@2.0.11 (WENYAN_CLI_PATH)   │
                 │  - 挂卷 /app/data ← ./data                   │
                 └───────────────┬─────────────────────────────┘
                                 │ 出网(host 网络可达)
        ┌────────────┬───────────┼───────────┬──────────────┐
        ▼            ▼           ▼           ▼              ▼
   外部 PostgreSQL  axonhub   wenyan-server  searxng    七牛图床
   (.env 配置)      (HTTPS)   (10.126..)   (192.168..)  (HTTPS)
```

**边界**：本仓库只负责 backend + frontend 两个镜像的构建与编排。所有 AI / 搜索 / 发布 / 存储 / 数据库依赖均为外部服务，通过 `.env` 中的地址访问，不进 compose。

## 2. 镜像设计

### 2.1 后端 `Dockerfile`（多阶段）

- **build 阶段**：`maven:3.9-eclipse-temurin-21`，复制 `pom.xml` 先 `dependency:go-offline`（利用层缓存），再复制 `src` 执行 `mvn -q -DskipTests package` → `target/sparkora-0.1.0-SNAPSHOT.jar`。
- **runtime 阶段**：`eclipse-temurin:21-jre-jammy`（Debian 系，便于 `apt-get install nodejs`）或 `node:22-bookworm-slim` 装 JRE。**决策见 §5 权衡**。
  - 复制 jar 与 `src/main/resources/db/schema.sql`（jar 内已含，无需单独复制）。
  - 安装 `@wenyan-md/cli@2.0.11`（与远程 wenyan-server 同核，版本对齐 `WenyanServerService` 注释实测的 2.0.11）。
  - `WORKDIR /app`；`ENV WENYAN_CLI_PATH=wenyan IMAGE_STORAGE_DIR=/app/data/images`。
  - `ENTRYPOINT ["java","-jar","/app/app.jar"]`（**不**用 shell 形式，保证信号正确转发、优雅停止）。
- 镜像内**不**含 `.env`。

### 2.2 前端 `Dockerfile`（多阶段）

- **build 阶段**：`node:22-alpine`，`npm ci`（有 `frontend/package-lock.json`）→ `npm run build` → `dist/`。
- **runtime 阶段**：`nginx:1.27-alpine`。
  - 拷贝 `dist/` 到 `/usr/share/nginx/html`。
  - 拷贝 `frontend/nginx.conf`（新增）覆盖默认站点。
  - SPA 路由为 `createWebHistory()`（`frontend/src/router/index.js:32`），**必须**配 `try_files $uri $uri/ /index.html;`，否则刷新子路由 404。

### 2.3 `frontend/nginx.conf`（新增）

```nginx
server {
  listen 80;
  root /usr/share/nginx/html;
  index index.html;

  location /api/ {
    proxy_pass http://backend:5661;          # 容器名解析，端口与 SERVER_PORT 对齐
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_read_timeout 180s;                 # AI/渲染接口可能长耗时
    client_max_body_size 15m;                # 对齐后端 multipart 上限
  }

  location / {
    try_files $uri $uri/ /index.html;
  }
}
```

> 关键取舍：`vite.config.js` 的 proxy 仅在 dev server 生效，**不改动它**（R8）；产线反代由 nginx 承担。`/api` 走相对路径，前端 `http.js` 的 `baseURL: '/api'` 无需修改。

## 3. 编排设计 `docker-compose.yml`

```yaml
services:
  backend:
    build: { context: ., dockerfile: Dockerfile }
    image: sparkora-backend:local
    env_file: [.env]
    environment:
      - IMAGE_STORAGE_DIR=/app/data/images
      - WENYAN_CLI_PATH=wenyan
    volumes:
      - ./data:/app/data
    ports:
      - "${BACKEND_PORT:-5661}:${SERVER_PORT:-5661}"
    restart: unless-stopped
    healthcheck:
      test: ["CMD-SHELL", "curl -sf -o /dev/null http://localhost:$$SERVER_PORT/api/auth/me || exit 1"]
      interval: 15s
      timeout: 5s
      retries: 10
      start_period: 90s

  frontend:
    build: { context: ./frontend, dockerfile: Dockerfile }
    image: sparkora-frontend:local
    ports:
      - "${FRONTEND_PORT:-8088}:80"
    depends_on:
      backend:
        condition: service_healthy
    restart: unless-stopped

networks:
  default:
    name: sparkora-net
```

> 说明：前端 `depends_on: service_healthy` 只是启动顺序护栏，运行时 nginx 每请求动态解析 `backend`，不缓存上游 IP，后端重启后无需重启前端。

**端口变量**：`SERVER_PORT` 已在 `.env` 中；新增 `FRONTEND_PORT`（宿主机访问端口，默认 8088）、可选 `BACKEND_PORT`（宿主机映射端口，默认等于 SERVER_PORT）。三者写入 `.env.example`。

**数据卷**：`./data:/app/data`。后端只需 `data/images`（临时 md 根）与 `data/tmp/wenyan-themes`（主题物化）。日志现落 `logging` 到 stdout（容器标准），不再写 `data/logs`。

## 4. 安全与兼容

- **密钥**：`.env` 经 `env_file` 注入为容器环境变量，不进镜像层；新增 `.dockerignore`（§5）。`.gitignore` 已忽略 `.env`（确认于 `.gitignore:2-4`）。
- **健康检查依赖 curl**：JRE 基础镜像默认无 curl，需在 runtime 阶段 `apt-get install -y curl`（或改用 `wget -q -O-`/bash `/dev/tcp`）。实现时优先避免额外依赖，用 `wget`/`bash` 探活。
- **`SERVER_PORT` 双关**：容器内监听端口与宿主机映射端口同源。若用户改 `.env` 的 `SERVER_PORT`，`nginx.conf` 的 `proxy_pass` 端口需同步——为降低耦合，nginx 也可改为只连服务名 + 固定内部端口。**决策**：保持 `SERVER_PORT` 单一来源，docker-compose 的 `environment` 显式把 `SERVER_PORT` 传给后端，nginx.conf 内部端口写死为 `SERVER_PORT` 的默认部署值并加注释。
- **时区/编码**：容器默认 UTC；如需本地时区可加 `TZ=Asia/Shanghai`（可选，非阻塞）。
- **不破坏既有**：`dev.sh`、`application.yml`、`vite.config.js`、所有业务代码零改动（R8）。

## 5. 权衡记录

| 决策点 | 选择 | 备选与理由 |
|---|---|---|
| 后端 runtime 基础镜像 | `eclipse-temurin:21-jre-jammy` + **NodeSource/nodesource 或 apt nodejs** 装 Node | 备选 `node:22-bookworm-slim` 装 JRE：更省事但需手动装 JDK 运行时。**实现时以「CLI 能跑」为验收**，两者择一以实测为准（AC3）。 |
| wenyan CLI 安装方式 | 全局 `npm i -g @wenyan-md/cli@2.0.11` | 备选：COPY 宿主 node_modules——不可复现，弃。版本锁定 2.0.11 对齐 server。 |
| 前端服务方式 | nginx 静态 + 反代 | 备选：`vite preview`——非生产级、需改 vite 配置，弃。 |
| 后端对外端口 | compose 端口映射，容器内监听同 `SERVER_PORT` | 避免容器内固定 8080 与 `.env` 不一致。 |
| DB | 连外部 | 已与用户确认，见 PRD Out of Scope。 |
| 健康检查 | `/api/auth/me`（401/200 均算活） | 与 `dev.sh:75` 口径一致。注意：401 也是「端口可达」，健康检查用「HTTP 有响应且非连接失败」判定，勿用 `-f`（`-f` 会在 401 时判失败）。**修正：用 `wget -S` 或 curl 不带 `-f`，接受 401。** |

## 6. 回滚形态

- 纯新增文件（`Dockerfile`、`frontend/Dockerfile`、`frontend/nginx.conf`、`docker-compose.yml`、`.dockerignore`、部署文档），对现有代码零侵入。
- 回滚 = `docker compose down` + 删除上述新增文件；`dev.sh` 部署路径始终可用。
- 无数据库迁移、无 schema 变更（`schema.sql` 幂等，容器启动执行与现在等价）。

## 7. 待实现文件清单

| 文件 | 动作 |
|---|---|
| `Dockerfile` | 新增（后端多阶段） |
| `frontend/Dockerfile` | 新增 |
| `frontend/nginx.conf` | 新增 |
| `docker-compose.yml` | 新增 |
| `.dockerignore` | 新增 |
| `.env.example` | 修改（补 `FRONTEND_PORT` / `BACKEND_PORT` 与部署说明） |
| `docs/deploy.md` | 新增（启动/停止/日志/更新/排障） |
| `README.md` / `AGENTS.md` | 修改（Commands 段补 compose 用法） |
