# 固化容器化约定:代码变动后主动重建

## Goal

让「每次完成后端/前端代码变动后，主动重新构建并重启容器」成为 AI 会话的稳定约定，而不是依赖当次口头指令——写入 agent 指令文件（`AGENTS.md`）与部署文档（`docs/deploy.md`），使后续会话自动遵守。

用户价值：产线容器不会因忘记 `--build` 而长期跑旧代码（镜像内代码是烘焙的，`docker compose restart` 不会拉取新代码），减少「明明改了却没生效」的排查成本。

## Background

- 本仓库产线为容器化部署；compose 仅挂载 `./data`，**代码烘焙进镜像**（`Dockerfile` / `frontend/Dockerfile`），未挂源码卷。
- 因此代码变动后必须 `docker compose up -d --build` 重新构建；`restart` 或裸 `up -d` 不会应用新代码。
- 当前 `AGENTS.md` Commands 段仅在注释里写「首次或改 Dockerfile 后加 `--build`」，未把「代码变动」明确为触发条件；`docs/deploy.md` §6「更新镜像」有 `--build`，但未强调这是**每次代码变动后的默认动作**。
- `AGENTS.md` 被 `.gitignore` 忽略（本地文件，每会话注入 agent 上下文）；`docs/deploy.md` 受版本控制（跨机器/跨开发者权威来源）。两处都要写，职责不同。

## Requirements

- R1 在 `AGENTS.md` 的产线部署命令块中补充明确约定：**后端或前端代码变动后，主动执行 `docker compose up -d --build`**，并点明「代码烘焙进镜像，`restart`/裸 `up -d` 不生效」的原因。
- R2 在 `docs/deploy.md` §6「更新镜像」中把 `docker compose up -d --build` 明确为**代码变动后的默认动作**（而不仅是「拉取最新代码后」），并说明与 `restart` 的区别。
- R3 措辞与现有文档风格一致（中文、简洁、命令块内注释），不改动任何业务代码、Dockerfile、compose 配置。
- R4 不引入新的自动化钩子/脚本（纯文档约定；自动化属未来可选项，不在本任务）。

## Acceptance Criteria

- [ ] AC1 `AGENTS.md` 产线部署命令块含明确说明：代码变动后跑 `docker compose up -d --build`，且说明 `restart` 不重新构建。
- [ ] AC2 `docs/deploy.md` §6 把 `docker compose up -d --build` 列为代码变动后的默认推荐动作，并对比 `restart`/裸 `up -d`。
- [ ] AC3 `git diff` 仅涉及 `AGENTS.md` 与 `docs/deploy.md`；无 Java/Vue/Dockerfile/compose 改动。
- [ ] AC4 两处描述一致，无相互矛盾（触发条件、命令、原因）。

## Out of Scope

- 任何自动重建机制（git hook、watch 脚本、CI）。
- 改动 `docker-compose.yml`（如改为源码挂载 + 容器内热重载）——本仓库产线保持镜像烘焙形态。
- 改动 `dev.sh` 本地联调路径。
