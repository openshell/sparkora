# Design — C4 多轮对话式知识问答

> 父任务 `09-11-knowledge-base-data-foundation`。依赖 C2（新闻域）/C3（前端框架）。

## 1. 范围与边界

**做**：后端多轮消息能力 + 问答会话/消息持久化 + QA REST + 跨三域检索合成；前端独立问答页 `/qa` + 引用展示。
**不做**：语音/多模态；会话分享导出；流式输出（沿用同步一次性返回，AI 超时放宽）；改动 `retrieveForGeneration` 的 CAR/KB 语义。

## 2. 组件与数据流

```
前端 /qa (QaChat.vue)
  │ POST /api/qa/sessions           建会话
  │ GET  /api/qa/sessions           会话列表
  │ GET  /api/qa/sessions/{id}      历史(含消息+citations)
  │ POST /api/qa/sessions/{id}/messages {question}
  ▼
QaController ──▶ QaService.ask(sessionId, question, user)
                    │ 1) 载入最近 N 轮历史(上下文窗口)
                    │ 2) 构造检索 query(见 §4)
                    │ 3) CarRagService.retrieveForGeneration(query, topK, anchors=null)
                    │ 4) 组装多轮 messages(system + history + 本轮含知识上下文)
                    │ 5) AiClient.chatMessages(messages, maxTokens)
                    │ 6) 落 user 消息 + assistant 消息(citations JSON)
                    ▼
              QaMessageEntity(citations = RagResult.citations JSON)
```

## 3. 数据模型（`schema.sql` 幂等追加，S12 段）

```sql
CREATE TABLE IF NOT EXISTS sparkora_qa_session (
    id          BIGSERIAL PRIMARY KEY,
    title       VARCHAR(200),                -- 首问摘要,可空
    created_by  VARCHAR(64) NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS sparkora_qa_message (
    id          BIGSERIAL PRIMARY KEY,
    session_id  BIGINT NOT NULL REFERENCES sparkora_qa_session(id),
    role        VARCHAR(20) NOT NULL,        -- user / assistant
    content     TEXT NOT NULL,
    citations   TEXT,                        -- JSON 数组(Citation: source/modelName/chunkType/score/chunkText);user 消息为空
    rag_status  VARCHAR(20),                 -- OK/LOW_CONFIDENCE/FAILED/NO_KNOWLEDGE(KB 降级可见)
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_qa_message_session ON sparkora_qa_message(session_id);
```
无逻辑删除（消息保留）；会话逻辑删除。无向量表。

## 4. 多轮上下文策略（明确）

- **检索 query 构造**（解决追问指代）：`query = 当前问题`；若当前问题 ≤ 12 字（疑似指代）或最近 2 轮用户问题存在，则拼接 `最近2轮用户问题 + 当前问题`（截断 ≤ 300 字）作为检索文本。
- **送入 LLM 的历史窗口**：最近 `HISTORY_MAX_TURNS=6` 轮（12 条消息），单条 `content` 截断 `HISTORY_MSG_MAX=2000` 字，总历史 ≤ 12000 字，超出丢最旧。无摘要压缩（个人项目规模，保持简单，文档记录）。
- **知识上下文**：`RagResult.context` 作为 system 附加段，仅 OK 时注入；非 OK 时在 system 中标注降级（LOW_CONFIDENCE/FAILED/NO_KNOWLEDGE），让模型回答「知识库未覆盖」。

## 5. AiClient 扩展（非破坏）

```java
/** 多轮消息 chat(不强制 JSON)。messages: 每项 {role, content}。 */
public ChatResult chatMessages(List<Map<String, String>> messages, int maxTokens)
```
复用既有 `rest`/`parseChat`；`temperature`/`model` 同 `chat`。现有 `chat`/`chatJson` 签名不动（可内部委托）。

## 6. REST 契约（`/api/qa`，`@PreAuthorize` + `R<T>`）

| 方法 | 路径 | 权限 | 入参 | 返回 |
|---|---|---|---|---|
| POST | `/sessions` | ADMIN/EDITOR/VIEWER | `{title?}` | `R<QaSessionEntity>` |
| GET | `/sessions` | 三角色 | — | `R<List<QaSessionEntity>>`（仅本人） |
| GET | `/sessions/{id}` | 三角色 | — | `R<Map>`（session + messages[]，校验归属） |
| POST | `/sessions/{id}/messages` | 三角色 | `{question}` | `R<Map>`（user 消息 + assistant 消息含 citations） |
| DELETE | `/sessions/{id}` | 三角色 | — | `R<Void>`（逻辑删，本人） |

- 会话归属：`created_by = 当前用户名`；越权 404（不泄露存在性）。
- 写操作入参用 `@Valid` DTO（`web/dto/QaAskDto`）。

## 7. 开关契约（R7）

- `QaService` **不读** `SettingService.kbEnabled` / `sparkora_setting`；问答链路完全独立于「生成注入」开关。
- KB 域在检索层由 `AiProperties.ragKbEnabled` 决定（既有生成语义，不在本任务改）；新闻域由 `ragNewsTopk` 决定。AC4 验证 `kb_enabled=false` 时问答仍可用。

## 8. 前端

- 新增 `frontend/src/api/index.js` → `qaApi`（createSession/listSessions/getSession/ask/removeSession）。
- 新增 `frontend/src/views/QaChat.vue`：左侧会话列表 + 右侧对话流；输入框发送；展示 assistant 消息 + `CitationList`（复用，扩展 NEWS 标签）；新会话/删除；三态；移动端单列 ≥44px。
- `CitationList.vue` 扩展 `NEWS` 源（`sourceLabel='官方新闻'`，`tagType='danger'`）——纯增量，不影响既有 CAR/KB/WEB/MULTI。
- 路由 `/qa`（auth）；TopBar 增「知识问答」入口。
- AI 耗时长，`ask` 前端超时 120000ms。

## 9. 验证

- `mvn -q -DskipTests compile`；`mvn test`（新增 `QaServiceTest` 覆盖检索降级/上下文窗口/citations 映射）。
- `npm --prefix frontend run build`。
- 真机：建会话 → 首问（车型）→ 追问（指代）→ 引用展示；`kb_enabled=false` 下问答仍可用；越权会话 404。
- 回归：既有 `POST /car/rag`、创作生成链路不受影响（未改检索服务）。

## 10. 回滚

- 新表可 `DROP TABLE sparkora_qa_message, sparkora_qa_session`。
- `/qa` 路由与 TopBar 入口可下线；`AiClient.chatMessages` 为纯新增方法，删除即回退。
- 未改任何既有代码路径语义（仅 CitationList 纯增量分支）。

## 11. 与父契约衔接

- §3.5 多轮能力 ✅；§5 接口契约 ✅；§6 兼容（不改 `/car/rag`、不读 `kb_enabled`）✅；§7 风险（上下文超长→历史窗口截断）✅。
