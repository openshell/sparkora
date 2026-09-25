# 实施计划：Docker 容器化产线部署

## 前置检查

- [ ] 确认 Docker Engine ≥ 20.10 与 `docker compose` v2 可用（已核实：Engine 26.1.5 / Compose v2.32.4）。
- [ ] 确认本地 `wenyan --version` = 2.0.11（已核实），镜像锁定同版本。
- [ ] 确认 `.env` 中外部依赖地址在容器内可达（DB / axonhub / wenyan-server / searxng / 七牛）。

## 实施步骤（有序）

1. **`.dockerignore`（根目录）** — 先建，避免后续构建把 `.env`/`data`/`target` 打进上下文。
   排除：`.git`、`.env`、`.env.*`（保留 `!.env.example` 无需）、`target`、`data`、`node_modules`、`docs`、`prototypes`、`.trellis`、`.opencode`、日志。

2. **`Dockerfile`（后端，根目录多阶段）**
   - build 阶段 `maven:3.9-eclipse-temurin-21`：先 COPY `pom.xml` + `mvn -q dependency:go-offline`，再 COPY `src` + `mvn -q -DskipTests package`。
   - runtime 阶段：JRE21 基础镜像 + Node + `npm i -g @wenyan-md/cli@2.0.11`；COPY jar 为 `/app/app.jar`；`ENV WENYAN_CLI_PATH=wenyan IMAGE_STORAGE_DIR=/app/data/images`；`WORKDIR /app`；exec 形式 ENTRYPOINT。

3. **`frontend/nginx.conf`** — 新建，按 design §2.3；`proxy_pass http://backend:<内部端口>`，SPA fallback。

4. **`frontend/Dockerfile`** — 新建，node 阶段 `npm ci && npm run build` → nginx 阶段 COPY `dist` 与 `nginx.conf`。

5. **`docker-compose.yml`（根目录）** — 按 design §3；backend/frontend 两服务、`env_file: .env`、数据卷 `./data:/app/data`、healthcheck、`depends_on: service_healthy`、命名网络。

6. **`.env.example`** — 补 `FRONTEND_PORT`（默认 8088）、`BACKEND_PORT`（默认同 SERVER_PORT），附注释；说明 `SERVER_PORT` 与 nginx 内部端口需一致。

7. **`docs/deploy.md`** — 新增：前置要求、一键启动/停止/重启/日志/重建、端口与 `.env` 关系、排障（wenyan CLI 缺失、数据目录权限、DB 不可达、401 探活判定说明）。

8. **`README.md` / `AGENTS.md`** — Commands 段补 `docker compose up -d --build` 等常用命令，并注明「本地联调仍用 dev.sh」。

## 验证命令

```bash
# 构建
docker compose build
# 起服务
docker compose up -d
docker compose ps                       # AC1：两服务 running/healthy

# 后端容器内 CLI 就绪（AC3）
docker compose exec backend wenyan --version
docker compose exec backend curl -s -o /dev/null -w '%{http_code}' \
  http://localhost:${SERVER_PORT}/api/auth/me     # 期望 401/200（非 000）

# 前端反代（AC2）
curl -s -o /dev/null -w '%{http_code}' http://localhost:${FRONTEND_PORT}/          # 200
curl -s -o /dev/null -w '%{http_code}' http://localhost:${FRONTEND_PORT}/api/auth/me  # 401/200

# 持久化（AC4）
docker compose down && docker compose up -d
ls data/tmp/wenyan-themes           # 主题 CSS 仍在

# 密钥不进镜像（AC5）
docker history sparkora-backend:local | grep -i env || true
docker run --rm sparkora-backend:local sh -c 'test ! -f /app/.env && echo no-env-ok'

# 既有构建不破（AC7）
mvn -q -DskipTests compile
cd frontend && npm run build

# 本地联调不破（AC6）
./dev.sh status
```

## 风险点与回滚

- **风险：runtime 镜像装 Node 的可靠性**（apt 源/NodeSource）→ 若 `apt nodejs` 版本过旧跑不动 CLI，退回 `node:22-bookworm-slim` 基础镜像并装 JRE。此点以 AC3 实测为准。
- **风险：健康检查工具缺失** → 优先用基础镜像自带 `wget`/bash；确需 curl 才安装。
- **风险：401 被 `curl -f` 判失败** → 健康检查不带 `-f`，接受 401。
- **风险：端口冲突**（宿主机 5661/8088 被占）→ 经 `.env` 改 `BACKEND_PORT`/`FRONTEND_PORT`，无需改代码。
- **回滚**：`docker compose down`；删除新增文件即完全恢复原状（纯新增，零侵入，见 design §6）。

## 完成后（Phase 3）

- [ ] 跑完上述验证命令并留证。
- [ ] 更新 `docs/spec/overview.md` 或 README 的部署章节（若存在）、`.env.example`。
- [ ] 提交信息建议：`feat(deploy): Docker 容器化产线部署(backend+frontend compose)`。
