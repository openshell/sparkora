# Implement — C4 多轮对话式知识问答

> 父任务 `09-11-knowledge-base-data-foundation`。依赖 C2（新闻域）/C3（前端框架）。

## 有序清单

### 后端
- [ ] `AiClient`：新增 `chatMessages(List<Map<String,String>> messages, int maxTokens)`（复用 rest/parseChat，不破坏 `chat`/`chatJson`）。
- [ ] `schema.sql`：追加 S12 段 `sparkora_qa_session` / `sparkora_qa_message`（幂等，无 DO $$）。
- [ ] `domain/entity/QaSessionEntity.java` / `QaMessageEntity.java`（逻辑删除仅 session）。
- [ ] `mapper/QaSessionMapper.java` / `QaMessageMapper.java`（BaseMapper）。
- [ ] `web/dto/QaAskDto.java`（`@NotBlank question`）。
- [ ] `qa/service/QaService.java`：建/列/取/删会话 + `ask()`（历史窗口、检索 query 构造、`retrieveForGeneration`、多轮 messages、落库、citations 映射）。
- [ ] `web/controller/QaController.java`：`/api/qa/sessions*` 全部 `@PreAuthorize` + `R<T>` + 归属校验 + 404。
- [ ] `src/test/java/com/sparkora/qa/service/QaServiceTest.java`：检索降级、上下文窗口截断、citations 映射、越权。

### 前端
- [ ] `frontend/src/api/index.js`：`qaApi`。
- [ ] `frontend/src/views/project/deep/CitationList.vue`：新增 `NEWS` 分支（官方新闻/danger），纯增量。
- [ ] `frontend/src/views/QaChat.vue`：会话列表 + 对话流 + 输入 + 引用；三态；移动端 ≥44px。
- [ ] `frontend/src/router/index.js`：`/qa`（auth）。
- [ ] `frontend/src/layouts/TopBar.vue`：+「知识问答」入口。
- [ ] `docs/s0-spec.md`：新增 §17 知识问答（路由/接口/多轮策略/开关/验收）。
- [ ] 文档：`docs/knowledge-base.md`（父任务 AC7 交付物，R4 评估结论 + 三域架构 + 问答说明）。

## 验证命令

```bash
mvn -q -DskipTests compile
mvn test
npm --prefix frontend run build
./dev.sh restart backend
```

## 关键约束（勿越界）

- 不改 `CarRagService.retrieveForGeneration` 的 CAR/KB 语义（仅调用）。
- 问答链路不读 `kb_enabled`（AC4）。
- 会话仅本人可见（`created_by` 过滤 + 越权 404）。
- 不新增重型依赖；不引入流式（本期）。

## 回滚点

- 新表 `DROP TABLE`；`/qa` 路由与 TopBar 入口下线；`chatMessages` 删除即回退。
- CitationList 改动为纯增量分支，可单独回退。

## 前置依赖确认

- C2：`CarRagService` 已支持 NEWS（`source='NEWS'`，`【官方新闻：…】`，`ragNewsTopk`）。
- C3：`CitationList` 路径 `frontend/src/views/project/deep/CitationList.vue`、`TopBar.vue`、`api/index.js` 命名导出范式。
