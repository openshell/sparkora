# 执行规划：简报外部搜索 Tavily 优先与全局可配置

依赖：`prd.md`（需求与验收）、`design.md`（技术设计）。实现前必须获得用户对最终规划摘要的明确批准并执行 `task.py start`。

## 1. 实施顺序

- [ ] 1. 配置层：`DeepProperties` 增加 `webProviderOrder`；`application.yml` 增加 `sparkora.deep.web-provider-order: ${DEEP_WEB_PROVIDER_ORDER:TAVILY,SEARXNG}`；`.env.example` 同步并注明优先级语义。
- [ ] 2. 策略模型：新增 `com.sparkora.deep.search.WebProvider` 与 `WebProviderOrder.parse`（去重、未知值拒绝、空值回退默认）。
- [ ] 3. 结果治理：新增 `WebResultNormalizer`（协议校验、URL 规范化、去重、截断、`sourceId` 分配）与对应单测。
- [ ] 4. 路由组件：新增 `WebSearchRouter` 与 `WebSearchSnapshot`/`WebSearchOutcome`，封装 provider 顺序、跳过与降级、attempts 元数据。
- [ ] 5. SearchHit 扩展：增量增加 `sourceId`/`provider` 字段并保留旧构造器/工厂，确认既有调用方编译通过。
- [ ] 6. SubAgentRunner 改造：改调 router；WEB query 注入 `topic + question + 已锁定答案`；提示词与后验校验使用 `sourceId`；修正 `kbHit` 与 `rawFallback` 转义。
- [ ] 7. 编排与快照：`DeepResearchService.run` 校验 brief/project/DEEP/计划/答案；构建一次策略与开关快照传入各 agent；重复 `/run` 互斥 409；超时 `cancel(true)`。
- [ ] 8. 设置持久化：`schema.sql` 幂等加 `web_provider_order`；`SettingEntity`、`SettingUpdateDto`、`SettingService`、`SettingController` 同步；写仅 ADMIN，字段做合法性校验。
- [ ] 9. 可观测性：`research_notes` 增加 search 元数据；修正 `webCount` 语义与 `webCalls` 日志命名；`/deep/status` 增量暴露策略信息。
- [ ] 10. 前端：`SettingsView.vue` 增加策略选择；`ResearchProgress.vue` 展示策略与降级原因；保持未知态 `--`。
- [ ] 11. 文档：同步 `docs/spec/brief-generation.md`、`docs/spec/settings.md`、`.trellis/spec/backend/ai-rag-guidelines.md`、`.env.example`；清理 `docs/spec/overview.md`/`docs/README.md` 中 Crawl4AI 自相矛盾描述。
- [ ] 12. 测试：策略解析、首源命中不调后备、失败降级、开关门控、URL 处理、sourceId 校验、密钥脱敏、并发互斥、快照隔离。

## 2. 关键改动文件

后端：

- `src/main/java/com/sparkora/config/DeepProperties.java`
- `src/main/java/com/sparkora/deep/service/SubAgentRunner.java`
- `src/main/java/com/sparkora/deep/service/DeepResearchService.java`
- `src/main/java/com/sparkora/deep/tool/SearchTool.java`、`SearxngSearchTool.java`、`TavilySearchTool.java`
- `src/main/java/com/sparkora/deep/search/*`（新增）
- `src/main/java/com/sparkora/service/SettingService.java`
- `src/main/java/com/sparkora/web/controller/DeepController.java`、`SettingController.java`
- `src/main/java/com/sparkora/domain/entity/SettingEntity.java`、`domain/dto/SettingUpdateDto.java`
- `src/main/resources/application.yml`、`src/main/resources/db/schema.sql`

前端：

- `frontend/src/views/SettingsView.vue`
- `frontend/src/views/project/deep/ResearchProgress.vue`
- 如新增 API 方法：`frontend/src/api/index.js`

## 3. 验证命令

```bash
mvn -q -DskipTests compile
mvn test
```

前端有改动时：

```bash
npm run build
```

## 4. 风险文件与回滚点

- `SubAgentRunner.java`：路由替换是行为翻转核心，改前保持可单独回退。
- `schema.sql`：仅幂等加列，回滚保留列即可。
- `DeepResearchService.java`：并发与快照逻辑，需覆盖重复 `/run` 与超时用例。
- `SettingService.java`：缓存刷新一致性，写后必须刷新。

## 5. 完成前检查

- [ ] 所有验收标准 `prd.md` AC-01..AC-16 有对应证据。
- [ ] `mvn -q -DskipTests compile` 与 `mvn test` 通过。
- [ ] 前端改动时 `npm run build` 通过。
- [ ] 文档、`schema.sql`、实体/DTO 三处同步完成。
- [ ] 未记录或提交任何真实密钥。
