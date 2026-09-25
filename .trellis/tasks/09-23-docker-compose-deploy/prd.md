# Docker 容器化产线部署(compose)

## Goal

把 Sparkora 前后端的**产线部署方式**从「手工脚本 + 宿主机进程」改造为 `docker compose up -d` 一键起容器的标准形态；本地联调继续沿用 `dev.sh`（热重载），两者并存、互不干扰。

用户价值：产线部署可复现、可搬移、重启策略与依赖顺序由 compose 统一管理，不再依赖宿主机装好的 Java/Node/wenyan CLI。

## Background

当前部署完全依赖 `dev.sh`：`mvn spring-boot:run` 起后端、`npm run dev` 起 Vite 前端，进程用 `setsid nohup` 托管，日志落 `/tmp/sparkora-logs/`。宿主机需预装 JDK 21、Maven、Node、`@wenyan-md/cli` 全局包。

容器化已知约束（已从代码核实）：

- **后端预览链路需要 `wenyan` CLI**：`PreviewService.java:228` 直接 `ProcessBuilder` 执行 `WENYAN_CLI_PATH`（默认 `wenyan`），因此后端运行镜像必须内置 Node + `@wenyan-md/cli`，不能只装 JRE。
- **后端需要可写数据目录**：`IMAGE_STORAGE_DIR`（默认 `./data/images`）用于 wenyan 渲染临时 md（`PreviewService.java:285-293` 取 `{storage}/../tmp/preview`）与社区主题 CSS 物化（`data/tmp/wenyan-themes/`）；容器内须挂卷且可写。
- **配置已全部外部化**：`application.yml` 全为 `${XXX:default}`；真实值在根目录 `.env`（已被 `.gitignore` 忽略）。容器化只能经 `env_file` 注入，**绝不 COPY 进镜像**。
- **前端生产态不能再依赖 vite proxy**：`frontend/vite.config.js` 的 `server.proxy.target` 硬编码 `http://localhost:5661`，容器内 localhost 不是后端。
- **外部依赖均为外部地址，不随本仓库部署**：PostgreSQL（`.env` → `10.126.126.1:5201`）、axonhub（`https://axo.caiqz.cn`）、wenyan-server（`http://10.126.126.1:3000`）、searxng/crawl4ai（`192.168.3.108`）、七牛图床。

## Requirements

- R1 后端镜像：多阶段构建（Maven 构建 → JRE 21 运行），运行镜像内置 Node 与 `@wenyan-md/cli`，`WENYAN_CLI_PATH` 指向镜像内 CLI；启动即执行 `schema.sql` 幂等建表（沿用现有 `spring.sql.init.mode: always`）。
- R2 前端镜像：多阶段构建（Node 构建静态产物 → nginx 托管），并**反代 `/api` 到后端容器**，替代生产态不可用的 vite dev proxy。
- R3 compose 编排：根目录 `docker-compose.yml` 定义 backend + frontend 两个服务，共享同一网络；`env_file: .env` 注入配置；挂载宿主机数据目录（`./data`）到后端，保证重启后临时/主题文件与日志可留存。
- R4 运行健壮性：服务设置 `restart` 策略；后端带健康检查（探活 `/api/auth/me`，与 `dev.sh` 口径一致）；前端 `depends_on` 后端就绪。
- R5 端口可配置：对外暴露端口经 `.env` 变量控制（含前端访问端口），不写死。
- R6 密钥安全：`.env` 与任何真实凭据不进镜像、不进 git；`.dockerignore` 排除 `.env`、`target/`、`node_modules/`、`data/`、`.git/`。
- R7 文档与模板同步：`.env.example` 增补新增的部署相关变量（含注释与默认值）；新增产线部署说明文档（启动/停止/查看日志/更新镜像）。
- R8 不改动业务代码语义：现有 `dev.sh`、本地联调流程与所有接口契约保持不变。

## Acceptance Criteria

- [ ] AC1 在干净环境执行 `docker compose up -d` 后，`docker compose ps` 显示 backend/frontend 均 healthy/running。
- [ ] AC2 浏览器访问前端映射端口可打开 Sparkora 页面，登录请求经 nginx `/api` 反代成功（拿到真实响应，而非 502）。
- [ ] AC3 后端容器内 `wenyan --version` 可执行，预览接口返回真实渲染 HTML（非降级 fallback），证明 CLI 与可写数据目录就绪。
- [ ] AC4 `docker compose down && docker compose up -d` 后，挂载的数据目录内容仍在；重启期间无需重新 `mvn`/`npm` 构建。
- [ ] AC5 镜像内不含 `.env` 与真实密钥（`docker history` / 镜像文件系统核查无凭据泄露）。
- [ ] AC6 `dev.sh start|stop|restart|status|logs` 行为与改造前一致，本地联调不受影响。
- [ ] AC7 `mvn -q -DskipTests compile` 与 `frontend/npm run build` 仍通过（未破坏既有构建）。

## Technical Notes

- 后端运行镜像须内置 Node + `@wenyan-md/cli@2.0.11`（与 `WenyanServerService` 实测的 server 版本对齐）。
- 前端 SPA 用 `createWebHistory()`（`frontend/src/router/index.js:32`），nginx 必须配 `try_files ... /index.html` 兜底。
- 前端请求走相对路径 `/api`（`frontend/src/api/http.js:6`），产线由 nginx 反代，无需改前端代码。
- 健康检查探活 `/api/auth/me`，与 `dev.sh:75` 口径一致；401 亦算「服务存活」，探活判定不得使用 `curl -f`。

## Out of Scope

- 用容器替换 `dev.sh` 的本地热重载联调（明确保留）。
- **打包 PostgreSQL**：已确认 compose 不自带 DB 容器，继续连 `.env` 配置的外部数据库（`10.126.126.1:5201`）。
- 打包 searxng / crawl4ai / wenyan-server / axonhub / 七牛等外部依赖。
- HTTPS/TLS 证书终止与域名接入、CI/CD、镜像仓库推送。
- 多环境（dev/prod compose 双套）、K8s/编排平台适配。
