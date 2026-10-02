# implement.md — 执行计划

> 复杂任务。前置：`prd.md` + `design.md` 已评审；本文件用于 `task.py start` 后的执行。
> 验证命令：后端 `mvn -q -DskipTests compile` / `mvn test`（仓库根）；前端 `npm run build`（`frontend/`）。

## 阶段 A：基础设施 — AiClient 透出 reasoning

1. `src/main/java/com/sparkora/ai/AiClient.java`
   - `ChatResult` 增第 5 分量 `reasoning`，保留 3/4 参构造器。
   - `parseChat` 读 `reasoning`（缺省回退 `reasoning_content`），按 `REASONING_MAX_CHARS` 截断后传入。
   - 新增常量 `REASONING_MAX_CHARS = 20000`。
2. 跑 `mvn -q -DskipTests compile`。

**验证**：编译通过；既有 chat 调用方无改动。

## 阶段 B：数据层 — Flyway V4 + entity

3. 新增 `src/main/resources/db/migration/V4__content_description_and_brief_reasoning.sql`（见 design §2.4，五条单语句）。
4. `ArticleProjectEntity`：删 `keywords`/`remark`，`extraInfo`→`contentDescription`（含注释更新）。
5. `ArticleBriefEntity`：增 `researchReasoning`。
6. `ProjectRequest`：删 `keywords`/`remark`，`extraInfo`→`contentDescription`（`@Size(max=5000)`）。

**验证**：`mvn -q -DskipTests compile`（控制器尚未改，会短暂报错，进入阶段 C 修复）。

## 阶段 C：后端业务与控制器

7. `ClarifyService`：`start(Long)` / `runAsync(Long,Long)` 收敛签名；`generatePlan(ArticleProjectEntity)`；prompt 加 内容描述/目标读者/目标字数；`ensureBackgroundQuestion` probe 改 contentDescription；R1 8192→16384 提额重试；成功后 `setResearchReasoning`。
8. `DeepController`：`clarify` 忽略 body；`status` 增 `planReasoning`。
9. `ArticleProjectController`：create/update 字段替换；`match(topic, contentDescription)`。
10. `CarModelMatcherService`：`match` 第 2 参改名 + prompt 文案。
11. `DeepResearchService`：`resolveContentDescription` + 传入 `subAgent.research`。
12. `SubAgentRunner`：`research` 增 `contentDescription` 参，ctx 注入（不改检索 query）。
13. `BriefService`：`buildDeepBriefUserPrompt` 注入三项。
14. `DeepWriterService`：user prompt 注入 目标读者/内容描述。
15. `VersionService`：头部换内容描述；删独立「用户补充信息」块。

**验证**：`mvn -q -DskipTests compile`。

## 阶段 D：测试同步

16. 机械修复编译引用：
    - `DeepResearchServiceProgressTest` stub `subAgent.research(...)` 补第 8 个 `any()`。
    - `SubAgentRunnerTest` 全部 `r.research(...)` 调用补 `contentDescription` 参。
    - `ClarifyServiceTest` 注释/用例文案 extraInfo→内容描述。
17. 新增用例：
    - `AiClient`：`parseChat` 透出 `reasoning`（含回退 `reasoning_content`）。
    - `ClarifyService`：prompt 含内容描述/目标读者/目标字数；截断首败→提额 16384 重试；成功落 `researchReasoning`。
    - `SubAgentRunner`：ctx 含内容描述，且 `webQuery`/`compositeQuery` 不含内容描述（Q4=A 断言）。
    - `BriefService`/`DeepWriterService`/`VersionService`：prompt 捕获断言含三项。
18. `mvn test` 全绿。

**验证**：`mvn test`（约 510+ 用例）。

## 阶段 E：前端

19. `ProjectEdit.vue`：删关键词/备注；补充信息→内容描述；form 字段；`startDeep` 无参。
20. `ProjectList.vue`：删关键词列。
21. `api/index.js`：`startDeep` 无参。
22. `StepBrief.vue`：startDeep 简化；`deepReasoning`；传 `DeepPlanCard`。
23. `DeepPlanCard.vue`：可选 `reasoning` prop + 「AI 思考过程」折叠项。
24. `tests/fixtures/data.js`：去 `keywords`。

**验证**：`npm run build`（frontend/）。

## 阶段 F：文档同步

25. `docs/spec/project-lifecycle.md`：字段表删 keywords/remark，增 content_description；列表列描述更新；接口请求体。
26. `docs/spec/brief-generation.md`：§2 增 `research_reasoning` 列；§3 `/deep/clarify` 请求体、`/deep/status` `planReasoning`；§7 思考过程面板；§1 user prompt 字段。

## 验证门（全部通过才算完成）

- [ ] `mvn -q -DskipTests compile` 绿
- [ ] `mvn test` 全绿
- [ ] `npm run build` 绿
- [ ] 真机：reasoning 模型触发 `/deep/clarify` 成功（AC1/AC2）
- [ ] `/deep/status` 返回 `planReasoning`；澄清页可见折叠面板（AC3/AC4）
- [ ] prompt 断言覆盖三项注入（AC5/AC9）
- [ ] 表单/列表字段变更、存量内容描述不丢（AC6/AC7）
- [ ] `docker compose up -d --build` 后真机走查（可选，部署验证）

## 风险文件与回滚点

- **高风险**：`V4` 迁移（DROP 列不可逆）——上线前确认已备份；建议先在本地/联调库跑通迁移。
- `SubAgentRunner.research` 签名变更——机械但对测试面广；按编译错误逐个修。
- `ClarifyService` 异步签名收敛——注意 `self.runAsync` 代理调用仍生效。

## 依赖顺序

A → B → C → D 必须串行（编译依赖）；E 可在 C 后并行；F 最后。
