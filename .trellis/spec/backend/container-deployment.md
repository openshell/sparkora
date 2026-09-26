# Container Deployment (Docker Compose)

> 产线部署为容器化（`docker compose`）；本地热重载联调仍用 `./dev.sh`，两者并存。

---

## Overview

本文件沉淀**容器化产线部署**的可执行契约：端口单一来源、`env_file` 注入、健康检查口径、数据卷、镜像内 CLI 依赖。改动 `Dockerfile` / `docker-compose.yml` / `frontend/nginx.conf.template` / `.env.example` 部署段前必读。用户文档见 `docs/deploy.md`。

---

## Scenario: 产线部署端口单一来源 `SERVER_PORT`

### 1. Scope / Trigger

新增或修改容器端口、nginx 反代端口、`.env` 部署变量时触发——跨层（`.env` → compose → backend 容器 / frontend 容器 → nginx envsubst → `proxy_pass`）契约，易漏一处即 502。

### 2. Signatures

- `docker-compose.yml` 端口映射：`"${BACKEND_PORT:-5661}:${SERVER_PORT:-5661}"`（backend）、`"${FRONTEND_PORT:-8088}:80"`（frontend）。
- `frontend/nginx.conf.template`：`proxy_pass http://backend:${SERVER_PORT};`

### 3. Contracts

| 环境变量 | 含义 | 默认 | 单一定义处 |
|---|---|---|---|
| `SERVER_PORT` | 后端容器内监听端口，同时是 compose 内网端口与 nginx 反代端口 | `5661`（`application.yml` 默认 8080） | `.env` |
| `BACKEND_PORT` | 后端映射到宿主机的端口 | 等于 `SERVER_PORT` | `.env` |
| `FRONTEND_PORT` | 前端 nginx 映射到宿主机的访问端口 | `8088` | `.env` |

- `SERVER_PORT` 必须同时注入 backend 与 frontend 两个容器的 `environment`；nginx 模板由官方镜像的 `20-envsubst-on-templates.sh` 渲染，只替换**已定义**的变量，`$host`/`$remote_addr` 等运行时变量原样保留。

### 4. Validation & Error Matrix

| 条件 | 结果 |
|---|---|
| 改 `SERVER_PORT` 未重启容器 | 仍用旧端口（envsubst 只在容器启动时渲染） |
| 仅给 backend 注入 `SERVER_PORT`、漏给 frontend | nginx `proxy_pass` 端口为空/错 → 502 |
| `frontend/nginx.conf.template` 写成 `nginx.conf`（无 `.template` 后缀） | nginx 官方 entrypoint 不渲染 → `${SERVER_PORT}` 字面量进配置 |

### 5. Good/Base/Bad Cases

- Good：`.env` 只改 `SERVER_PORT=7777` → `docker compose up -d` → 后端监听 7777、nginx 渲染 `proxy_pass http://backend:7777`（实测通过，无需改 nginx）。
- Base：全部默认（5661/8088）直接 `docker compose up -d --build` 可用。
- Bad：在 nginx 模板里把端口写死 `backend:5661` 并声称「端口可配置」——改 `.env` 后前端 502，违反 R5。

### 6. Tests Required

- `docker compose config` 解析无错（含端口插值）。
- 起服务后：`curl -s -o /dev/null -w '%{http_code}' http://localhost:${FRONTEND_PORT}/api/auth/me` → `401`（非 000/502）。
- 改 `SERVER_PORT` 后 `docker compose exec frontend cat /etc/nginx/conf.d/default.conf` 确认 `proxy_pass` 端口已更新。

### 7. Wrong vs Correct

#### Wrong
```bash
docker compose exec frontend curl ... http://backend:${SERVER_PORT}/api/auth/me   # ${SERVER_PORT} 在宿主 shell 展开（未设→空）→ 000 假象
```

#### Correct
```bash
docker compose exec frontend sh -c 'curl -s -o /dev/null -w "%{http_code}\n" http://backend:$SERVER_PORT/api/auth/me'  # 容器内展开 → 401
```

---

## Scenario: 健康检查与「401 也算存活」口径

### 1. Scope / Trigger

配置/修改 backend `healthcheck` 时触发；`/api/**` 受 Spring Security 保护，未带 token 返回 401。

### 2. Signatures

```yaml
healthcheck:
  test: ["CMD-SHELL", "curl -s -o /dev/null --max-time 5 http://localhost:$$SERVER_PORT/api/auth/me"]
  interval: 15s
  timeout: 8s
  retries: 10
  start_period: 90s
```

### 3. Contracts

- `$$SERVER_PORT`（compose 中双 `$`）→ 容器内运行时 `$SERVER_PORT`。
- **不得用 `curl -f`**：`-f` 在 4xx/5xx 返回非 0，401 会被误判为不健康。以 curl 退出码判定：任何 HTTP 响应（含 401/403）为 0，连接失败/超时非 0。
- 口径与 `dev.sh:75` 一致（探活 `/api/auth/me`，非 000 即存活）。

### 4. Validation & Error Matrix

| 条件 | curl 退出码 | 健康判定 |
|---|---|---|
| 200 / 401 / 403 | 0 | healthy ✅ |
| 连接被拒 / 超时 | 7 / 28 | unhealthy |
| 加了 `-f` 且返回 401 | 22 | unhealthy ❌（错误配置） |

### 5. Good/Base/Bad Cases

- Good：上面写法，实测 401 → exit 0，`docker inspect` 显示 `ExitCode: 0`。
- Base：DB 不可达 → 应用启动失败 → 探活连接失败 → unhealthy（符合预期）。
- Bad：`curl -sf .../api/auth/me` ——永远 unhealthy。

### 6. Tests Required

- `docker compose up -d` 后 `docker compose ps` 显示 backend `(healthy)`。
- 容器内手工验证：`docker compose exec backend sh -c 'curl -s -o /dev/null http://localhost:$SERVER_PORT/api/auth/me; echo $?'` → `0`。

### 7. Wrong vs Correct

#### Wrong
`test: ["CMD-SHELL", "curl -sf ... /api/auth/me || exit 1"]`

#### Correct
`test: ["CMD-SHELL", "curl -s -o /dev/null --max-time 5 http://localhost:$$SERVER_PORT/api/auth/me"]`

---

## Scenario: 密钥注入与后端镜像内置 wenyan CLI

### 1. Scope / Trigger

改动镜像构建、`.dockerignore`、`env_file`、后端起容器方式时触发（infra integration + secrets）。

### 2. Signatures

- 后端运行镜像：`eclipse-temurin:21-jre-jammy` + Node 22（NodeSource）+ `@wenyan-md/cli@2.0.11`。
- `ENV WENYAN_CLI_PATH=wenyan IMAGE_STORAGE_DIR=/app/data/images`；`ENTRYPOINT ["java","-jar","/app/app.jar"]`（exec 形式）。
- `env_file: [.env]` 注入所有配置；`.dockerignore` 排除 `.env`（根与 `frontend/` 各一份）。

### 3. Contracts

- **为什么必须内置 Node**：`PreviewService.renderByCli` 用 `ProcessBuilder` 执行 `WENYAN_CLI_PATH`（默认 `wenyan`）做同核渲染，只装 JRE 会走降级 HTML（见 `external-cli-integration.md`）。
- **可写数据卷**：`./data:/app/data`；渲染临时 md 落 `{IMAGE_STORAGE_DIR}/../tmp/preview`，社区主题 CSS 物化到 `data/tmp/wenyan-themes`。
- `.env` **绝不** `COPY` 进镜像，只经 `env_file` 运行时注入。

### 4. Validation & Error Matrix

| 条件 | 结果 |
|---|---|
| 镜像内缺 Node/CLI | 预览/发布降级（`degraded=true`），不崩但排版失真 |
| `/app/data` 未挂卷或不可写 | 临时 md 创建失败 → CLI 渲染失败 → 降级 |
| `.env` 被 COPY 进镜像 | 凭据泄露（AC5 失败） |

### 5. Good/Base/Bad Cases

- Good：`docker compose exec backend wenyan --version` → `2.0.11`；预览端到端 `degraded=false`（内置与 `custom:*` 社区主题均实测）。
- Base：外部服务（DB/axonhub/wenyan-server）不可达 → 应用启动失败或对应接口报错，镜像本身正常。
- Bad：用 `COPY . .` 且无 `.dockerignore` → `.env` 进镜像。

### 6. Tests Required

- `docker compose exec backend wenyan --version`（断言 `2.0.11`）。
- `docker run --rm sparkora-backend:local sh -c 'test ! -f /app/.env && echo ok'`（断言无 `.env`）。
- `docker history` grep 密钥串为空；镜像内不含 `JWT_SECRET`/`QINIA/DB` 等真实值。
- 预览接口返回非降级 HTML（`degraded=false`）。

### 7. Wrong vs Correct

#### Wrong
```dockerfile
FROM eclipse-temurin:21-jre-jammy
COPY . .          # 把 .env、target、data 全带进去，且无 Node → CLI 缺失
```

#### Correct
```dockerfile
FROM eclipse-temurin:21-jre-jammy
RUN ... nodejs + npm i -g @wenyan-md/cli@2.0.11
COPY --from=build /build/target/*.jar /app/app.jar   # 仅产物
# .env 由 compose env_file 运行时注入
```

---

## Common Mistakes

### Common Mistake: 前端构建上下文覆盖镜像内 `npm ci`

**Symptom**：前端镜像产物异常或含宿主旧依赖。

**Cause**：`frontend/Dockerfile` 用 `COPY . .`，而根 `.dockerignore` **不作用于** `./frontend` 构建上下文，宿主 `node_modules` 被打进上下文覆盖安装结果。

**Fix**：`frontend/.dockerignore` 显式排除 `node_modules/`、`dist/`（实测上下文由 130MB+ 降到 3.4kB）。

**Prevention**：多构建上下文项目，每个 context 目录各自维护 `.dockerignore`。

### Common Mistake: `docker compose down` 未保留数据

**Symptom**：重启后主题 CSS / 临时文件丢失。

**Cause**：数据未挂卷（落在容器可写层）。

**Fix**：`./data:/app/data` 命名挂载；`down` 只删容器不删宿主 `./data`。

**Prevention**：验证 `down && up -d` 后 `data/tmp/wenyan-themes` 仍在。

### Common Mistake: 代码变动后未重建容器，仍跑旧代码

**Symptom**：改了后端/前端代码（或已提交），但产线容器行为不变，像是改动没生效。

**Cause**：compose 只挂载 `./data`，**源码烘焙进镜像**（`Dockerfile` / `frontend/Dockerfile`），无源码卷。`docker compose restart` 或裸 `docker compose up -d` **不重新构建**，容器继续跑镜像里的旧代码。

**Fix**：代码变动后 `docker compose up -d --build` 重建并滚动替换（`--build` 不可省）。

**Prevention**：把「代码变动后主动 `--build`」作为默认动作——已固化于仓库根 `AGENTS.md`（Commands 段，每会话注入 agent 上下文）与 `docs/deploy.md`（§6 更新镜像、§3 启动、§8 产线）。AI/开发者完成代码改动后应主动执行，无需等用户提醒。
