# C0 依赖升级预检 + axonhub 探针

## Goal

把工程升级到 Boot 4.x + Spring AI 2.0 并保证编译与现有 510 用例全绿；实测 axonhub
对 `json_schema` strict / tool calling / reasoning 的透传能力，为 C2/C3 实现深度定档。
本子任务是父任务 `10-02-spring-ai-adoption` 的**前置硬门**，未通过不得启动其余子任务。

## Depends On

无（父任务第一个子任务）。

## Requirements

- 父 R1。升级 `pom.xml`：Boot parent → 4.x；加 `spring-ai-bom` 2.0 + OpenAI 模型 starter +
  pgvector store starter（名称以 2.0 文档为准）；MyBatis-Plus 换 Boot 4 对应 starter。
- `application.yml` 配 `spring.ai.openai.base-url/api-key/chat.options.model/embedding.options.model`
  （走 `.env` 占位）；`.env.example` 同步；**绝不改真实 `.env`**。
- 探针：验证 `json_schema` strict、tool calling、reasoning 字段透传，报告落
  `research/axonhub-capability-probe.md`。

## Acceptance Criteria

- [ ] `mvn -q -DskipTests compile` 通过；`mvn test` 全绿（现 510 用例）。
- [ ] `./dev.sh start` 后 `/api/auth/me` 探活正常。
- [ ] 探针报告落档，明确三项能力「支持/退化/不支持」结论。
- [ ] 无 Boot 4 兼容版时，回退方案（Boot 3.5 + Spring AI 1.1）已评估并记录。

## Out of Scope

- 不改任何业务逻辑 / prompt / 契约。
