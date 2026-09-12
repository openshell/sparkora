# Sparkora 知识基座与多轮问答

> 父任务：`09-11-knowledge-base-data-foundation`。本文汇总 R4 评估结论、三域知识基座架构、多轮问答实现与数据同步。
> 相关规格：`docs/s0-spec.md` §15（新闻知识域 C2）、§16（知识中心 C3）、§17（多轮问答 C4）。

---

## 1. R4 评估结论：保留并扩展现有 RAG，不引入外部向量库

**结论：现有自建 RAG 已具备三域统一检索能力，边际成本低，应保留并在此之上扩展（而非替换）。**

支撑理由（基于现有代码，非假设）：

- **pgvector 已在库内**：`sparkora_car_doc_embedding` / `sparkora_kb_chunk_embedding` / `sparkora_news_doc_embedding` 三张向量表共用 1024 维空间与 HNSW `vector_cosine_ops` 索引，无需额外部署向量数据库。
- **EmbeddingClient 已就绪**：统一向量化入口（Qwen3-Embedding-8B，1024 维），三域块共用同一模型，相似度可直接跨域比较。
- **统一检索已就绪**：`CarDocEmbeddingMapper.searchTopKUnified` 一次查询跨三域取候选（按域隔离候选窗口），`CarRagService.retrieveForGeneration` 完成锚点加权、分层配额、来源行内标注。
- **四态降级语义完整**：`RagStatus{OK, LOW_CONFIDENCE, FAILED, NO_KNOWLEDGE}` 保证「必查 + 降级可见」，检索失败不阻断主链路。
- **citations 已就绪**：`RagResult.citations`（`Citation{source,modelName,chunkType,score,chunkText}`）直接复用于问答引用展示。
- **不引入外部向量库的收益**：无新运维组件、无数据双写一致性负担；个人项目规模（车型 + KB + 约 167 篇新闻）下 pgvector 性能足够。

**现有入库方式合理之处**

- 块首行带来源锚点（车型「车型：X」/ 知识「知识：标题（领域）」/ 新闻「新闻：标题（日期）」），检索时主题可对齐。
- 先清后插的幂等重建（`rebuildForModel` / `KbDocService.rebuild` / `NewsDocService.rebuildForNews`），重复同步不产生重复块。
- 单块向量化失败不阻断整体（warn + 计数），可用重建补齐。

**已修缺陷（保留决策的配套）**

- NEWS 候选窗口必须按域隔离（C2 check 修复）：三域共用一个全局 `LIMIT` 时，新闻块数量远大于车型/KB，语义邻近会占满窗口把 CAR/KB 挤出候选。现为 CAR+KB 合并窗口 + NEWS 独立窗口（详见 `database-guidelines.md`「多域统一检索：候选窗口必须按域隔离」）。
- 统一检索锚点加权仅对 CAR 生效（NEWS/KB 无 modelId，不得被放大）。

---

## 2. 三域知识基座架构

```
                 ┌─────────────────────────────────────────────┐
                 │           统一检索(同向量空间 1024d)         │
                 │  CarDocEmbeddingMapper.searchTopKUnified     │
                 │  ┌────────┬────────┬────────┐               │
                 │  │  CAR   │   KB   │  NEWS  │  按域隔离窗口  │
                 │  └────┬───┴───┬────┴───┬────┘               │
                 └───────┼────────┼────────┼────────────────────┘
                         ▼        ▼        ▼
              sparkora_car_doc_  sparkora_kb_  sparkora_news_
              embedding          chunk_embedding doc_embedding
                         │        │        │
              ┌──────────┴────────┴────────┴───────────┐
              │      CarRagService.retrieveForGeneration │
              │  锚点加权(CAR) · 分层配额 · 来源行内标注    │
              │  → RagResult{status, context, citations} │
              └───────────────┬──────────────────────────┘
                              ▼
            ┌─────────────────┴───────────────────┐
            │ 消费方:文章生成(Brief/Version/Deep) │
            │        + 多轮问答(QaService)          │
            └─────────────────────────────────────┘
```

| 域 | 来源表 | 切块首行锚点 | 注入标注 | 配额开关 |
|---|---|---|---|---|
| CAR 车型 | `sparkora_car_doc_embedding` | 车型：<名称> | `【车型数据：<名称>】` | 锚点加权 `AI_RAG_ANCHOR_BOOST` |
| KB 通用知识 | `sparkora_kb_chunk_embedding` | 知识：<标题>（<领域>） | `【通用知识：<标题>】` | `AI_RAG_KB_ENABLED` + `AI_RAG_KB_TOPK` |
| NEWS 官方新闻 | `sparkora_news_doc_embedding` | 新闻：<标题>（<日期>） | `【官方新闻：<标题>】` | `AI_RAG_NEWS_TOPK`（不受 KB 开关控制） |

- 首行 `知识来源：车型数据 + 通用知识库 + 官方新闻` 按命中构成动态拼接。
- 检索状态：无命中 → `NO_KNOWLEDGE`；有命中但最高分 < `ragRejectScore` → `LOW_CONFIDENCE`（全抛弃）；检索异常 → `FAILED`；其余 → `OK`。
- `citations` 仅 OK 时非空，与注入 context 同源。

---

## 3. 多轮问答实现（C4，`/qa`）

### 3.1 请求链路

```
前端 QaChat.vue
  │ POST /api/qa/sessions/{id}/messages {question}
  ▼
QaController ─▶ QaService.ask(sessionId, question, user)
   1) 归属校验(created_by=本人;越权/不存在 → IllegalArgumentException → 404)
   2) 载入本会话历史,构造检索 query(短问题/追问拼接最近 2 轮 user 问题,≤300 字)
   3) CarRagService.retrieveForGeneration(query, 8, null)  ← 跨三域统一检索
   4) 组装多轮 messages:system(含知识上下文/降级说明) + 历史窗口 + 本轮 user
   5) AiClient.chatMessages(messages, 2048)  ← 非 JSON 文本合成
   6) 落 user 消息 + assistant 消息(citations JSON / rag_status)
   7) 首问回填会话 title,刷新 updated_at
```

### 3.2 多轮上下文策略

| 参数 | 值 | 说明 |
|---|---|---|
| `HISTORY_MAX_TURNS` | 6 | 送入 LLM 的最近轮数（12 条消息） |
| `HISTORY_MSG_MAX` | 2000 | 单条历史消息截断长度 |
| `HISTORY_TOTAL_MAX` | 12000 | 历史总字符上限，超出丢最旧 |
| `SEARCH_QUERY_MAX` | 300 | 检索 query 截断长度 |
| `PRONOUN_QUESTION_MAX` | 12 | 短问题（疑似指代）阈值 |

- **指代消解（轻量）**：短问题或已有历史时，检索 query 拼「最近 2 轮 user 问题 + 当前问题」，让「那续航呢」这类追问带上车型/主题上下文；不做 LLM 改写（个人项目规模，保持简单）。
- **无摘要压缩**：历史超限直接丢最旧，不引入摘要（已在本节记录，作为明确取舍）。

### 3.3 降级可见

- 检索 `OK` → system 注入 `RagResult.context`（带三域来源标注），模型据此作答。
- 非 `OK` → system 标注降级原因，要求模型明说「知识库未覆盖 / 暂时不可用」，不臆造。
- assistant 消息落 `rag_status`；前端 `CitationList` 无引用时按状态展示对应文案（复用既有降级提示）。

### 3.4 开关契约（AC4）

- `QaService` **不读** `SettingService.kbEnabled` / `sparkora_setting`：问答链路完全独立于「生成注入」开关。
- KB 域是否参与检索由检索层既有 `AiProperties.ragKbEnabled` 决定；NEWS 域由 `ragNewsTopk` 决定。
- 因此 `kb_enabled=false` 时问答仍可用（KB 块按既有生成语义在配额层排除，CAR/NEWS 正常）。

---

## 4. 数据同步（车型 / 新闻）

| 域 | 手动入口 | 定时任务 | 幂等键 |
|---|---|---|---|
| 车型 | `/car/sync` 创建同步任务（`POST /api/car/sync/jobs`） | 既有车型定时同步 | 车型业务 id |
| 新闻 | 知识中心「同步新闻」FULL/INCREMENT（`POST /api/news/sync/jobs`） | `NewsSyncScheduler`（`NEWS_SYNC_ENABLED`，默认关闭；cron `NEWS_SYNC_CRON`） | 官方字符串 `news_id` |
| 通用知识 | `/kb` 新建/编辑（保存即切块向量化） | — | 文档 id |

- 新闻单条失败记 `failed_items` 不阻断；可 `POST /api/news/sync/jobs/{id}/retry` 重试失败项。
- 定时任务防重叠用 `hasRunning()`；`@Async` 执行，进程死亡残留 RUNNING 的已知限制见 `database-guidelines.md`。
- 三域块均支持幂等重建：先物理清 embedding + chunk，再重切重嵌。

---

## 5. 已知限制（登记）

- 问答为**同步一次性返回**，无流式输出（AI 超时见 `.env AI_TIMEOUT_MS`，前端 `ask` 超时 120s）。
- 历史无摘要压缩，超长会话丢最旧（见 §3.2）。
- 会话不可分享/导出（Out of Scope，后续可扩展）。
- `sparkora_*_embedding` 为物理表（无 `deleted`），删除带逻辑删除的实体时需按外键一条 SQL 兜底物理清（既有实现已处理，见 `database-guidelines.md`）。
