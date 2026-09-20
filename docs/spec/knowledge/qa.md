# 多轮对话式知识问答（C4）

> 回链：[系统说明总览](../../README.md) ｜ 上级：[知识库检索](../retrieval.md)

职责：独立入口 `/qa`（**非知识中心 Tab**）：多轮对话 + 来源引用。答案基于统一检索（CAR 车型 / NEWS 官方新闻 / KB 通用知识）三域结果由 LLM 合成，同一会话可追问、上下文连贯。答案随带相关图库缩略图（只读附加展示）。

> C4 正式规格（2026-09-12）；答案配图由 09-15 qa-auto-illustrate 子D（2026-09-17）增量补入。
> 父任务：`09-11-knowledge-base-data-foundation`。
> 开关契约：浏览/问答**不受** `kb_enabled` 控制，仅生成注入可开关（AC4）。

---

## 1. 数据模型（schema.sql S12 区块，幂等 `CREATE TABLE IF NOT EXISTS`）

| 表 | 字段 | 说明 |
|---|---|---|
| `sparkora_qa_session` | id / title(≤200，首问摘要，可空) / created_by(归属用户) / created_at / updated_at / deleted | 会话；逻辑删除，仅本人可见 |
| `sparkora_qa_message` | id / session_id FK→sparkora_qa_session(id) / role(`user`/`assistant`) / content / citations(JSON) / rag_status(`OK`/`LOW_CONFIDENCE`/`FAILED`/`NO_KNOWLEDGE`) / created_at / image_refs(JSON，可空) | 消息保留（无逻辑删除）；`citations` 为 `Citation` 数组 `[{source,modelName,chunkType,score,chunkText,docId}]`，user 消息为空；`image_refs` 为 `QaImageRef` 数组，**仅 assistant 消息非空**，历史行为 NULL |

- 索引 `idx_qa_message_session(session_id)`。无向量表。
- `image_refs TEXT` 由「09-15 qa-auto-illustrate」段幂等补列（`ALTER TABLE ... ADD COLUMN IF NOT EXISTS`），纯增量、存量行 NULL。

---

## 2. 多轮上下文策略（明确，不做摘要压缩）

- **检索 query 构造**：`query = 当前问题`；若当前问题 ≤12 字（疑似指代）或会话已有历史，则拼接最近 2 轮 user 问题 + 当前问题（截断 ≤300 字）作为检索文本。
- **送入 LLM 的历史窗口**：最近 `HISTORY_MAX_TURNS=6` 轮（12 条消息），单条 `content` 截断 2000 字，总历史 ≤12000 字，超出丢最旧。
- **知识上下文**：`RagResult.context` 作 system 附加段，仅 `OK` 时注入；非 `OK` 时 system 标注降级原因（`LOW_CONFIDENCE`/`FAILED`/`NO_KNOWLEDGE`），让模型回答「知识库未覆盖」。
- **AI 扩展**：`AiClient.chatMessages(List<Map<String,String>> messages, int maxTokens)`（C4 新增，不破坏既有 `chat`/`chatJson` 签名）；`temperature`/`model` 同 `chat`。
- 常量（`QaService`）：`ANSWER_MAX_TOKENS=2048`、`HISTORY_MAX_TURNS=6`、`HISTORY_MAX_MESSAGES=12`、`HISTORY_MSG_MAX=2000`、`HISTORY_TOTAL_MAX=12000`、`SEARCH_QUERY_MAX=300`、`PRONOUN_QUESTION_MAX=12`。
- **无摘要压缩**：历史超限直接丢最旧，不引入摘要（明确取舍）。

---

## 3. 请求链路

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
   6) 解析答案配图(QaImageRefService,失败仅 warn 不阻断)
   7) 落 user 消息 + assistant 消息(citations JSON / rag_status / image_refs)
   8) 首问回填会话 title,刷新 updated_at
```

---

## 4. 接口契约（`/api/qa`，全部 `R<T>` + 方法级 `@PreAuthorize`）

| 方法 | 路径 | 权限 | 入参 | 返回 |
|---|---|---|---|---|
| POST | `/api/qa/sessions` | ADMIN/EDITOR/VIEWER | `{title?}` | `R<QaSessionEntity>`（title 可空，首问回填） |
| GET | `/api/qa/sessions` | 三角色 | — | `R<List<QaSessionEntity>>`（仅本人，`updated_at` 倒序） |
| GET | `/api/qa/sessions/{id}` | 三角色 | — | `R<Map>`：`{session, messages[]}`（messages 按 id 升序；越权/不存在 `R.fail(404)`） |
| POST | `/api/qa/sessions/{id}/messages` | 三角色 | `{question}`（`@Valid`，≤2000 字） | `R<Map>`：`{userMessage, assistantMessage}`（assistant 含 `citations`/`ragStatus`/**`imageRefs`**）；越权 404 / AI 失败 500 |
| DELETE | `/api/qa/sessions/{id}` | 三角色 | — | `R<Void>`（逻辑删，仅本人；越权 404） |

- 会话归属：`created_by = 当前用户名`（无登录态回退 `system`）；越权/不存在统一 404（不泄露存在性）。
- 检索调用 `CarRagService.retrieveForGeneration(searchQuery, 8, null)`（锚点 null）；**不改** CAR/KB/NEWS 检索语义，**不读** `SettingService.kbEnabled`。

---

## 5. 答案配图（09-15 qa-auto-illustrate，子D，2026-09-17）

**语义**：答案随带相关图库缩略图（**只读附加展示**）。两条来源路径，合并去重后随 assistant 消息返回：

| 路径 | 触发条件 | 链路 |
|---|---|---|
| 新闻关联图（便宜路径，始终执行） | 答案引用含 `source=NEWS` 且该引用 `docId` 非空 | `Citation.docId`(= `sparkora_news_doc.id`) → `news_id`(内部 BIGINT) → `sparkora_news.id` → `cover_image_id` → `sparkora_image_asset` |
| 语义检索图（语义路径，仅图片意图） | 问题命中图片意图关键词 | `ImageEmbeddingService.searchImages(cleanQuery, limit, AI_IMAGE_MIN_SCORE, null)` |

- **`Citation` / `UnifiedHit` 加可空 `docId`**（09-15 补读）：`searchTopKUnified` SQL 本已 `SELECT docId`（CAR=`car_doc.id` / KB=`kb_chunk.id` / NEWS=`news_doc.id`），此前读行时丢弃，现补读并透传（**含锚点 boost 重排分支**，漏传会让 id 静默丢失）。`Citation` **保留 5 参兼容构造器**（`docId=null`），`BriefService.citationsJson`（简报）与 `KnowledgeSearchTool`（深度检索）行为不变。
- **图片意图判定**（`QaImageIntent`，纯静态关键词，不用 LLM）：命中 `看图/看图片/看张图/看照片/图片/海报/照片/配图/给我看/我想看/看一下` 任一即触发语义检索；**非图片意图不调 `searchImages`**（无 embedding 浪费）。
- **合并去重**：按 `imageId` 去重，**新闻关联图优先**（与答案引用强相关），上限 `QaService.IMAGE_REF_MAX = 3`（常量，不配置化）。
- **降级（绝不阻断答案）**：`QaImageRefService` 各路径与 `QaService.ask` 调用处均包 try/catch，异常仅 warn；配图解析失败/为空 → `image_refs` 落 **null**，答案照常落库。
- **`image_refs` 结构**：`[{imageId, url, thumbUrl, title, newsId, source}]`（`QaImageRef` record）；新闻关联图 `title`=新闻标题、`newsId`=官方 `news_id`；语义图 `newsId`=null、`title`=嵌入原文首段或文件名。
- **只读契约（用户 09-17 决策）**：配图**直接随答案展示**，无批准流程、无候选态；本任务**不提供任何写入用户内容的路径**（不插入文章、不改答案文本）——与配图建议「写入正文必须用户批准」的风险模型不同（前者改用户内容，此处只多显示几张图）。
- **`ImageService.loadDerived(List<Long>)`**：新增**只读**批量方法（`selectBatchIds` + 复用既有 `fillDerived`），避免问答侧二次实现「storageKey→url / 七牛 thumbUrl」派生规则导致漂移。
- **错误矩阵**：无 NEWS 引用/`docId` 空 → 新闻关联图为空（不报错）；news 无 `cover_image_id` → 跳过；图库记录已删/无 `url` → 跳过；语义检索失败 → 该路空 + warn；配图整体异常 → `image_refs=null` + warn；历史消息 `image_refs` NULL → 前端不展示图片区（零回归）。
- **权限**：无新接口，沿用 `/api/qa` 三角色矩阵（问答读写三角色均可）。

---

## 6. 前端

| 文件 | 职责 |
|---|---|
| `views/QaChat.vue` | 左侧会话列表（新建/删除/切换）+ 右侧对话流 + 底部输入（Enter 发送 / Shift+Enter 换行）；assistant 气泡下 `CitationList`；**其下图片缩略图行**（横向滚动，`el-image` 预览大图，标注新闻标题/来源）；三态；移动端单列、触控 ≥44px |
| `views/project/deep/CitationList.vue` | 新增 `NEWS` 分支（`官方新闻` / `danger`），纯增量，不影响既有 CAR/KB/WEB/MULTI |
| `api/index.js` | `qaApi`（createSession/listSessions/getSession/ask/removeSession；`ask` 超时 120s） |
| `router/index.js` | `/qa`（`meta.auth`），紧随 `/knowledge` |
| `layouts/TopBar.vue` | 新增「知识问答」导航（登录可见） |

- 配图展示契约（09-15）：`imgRefsOf(m)` **兼容 `imageRefs` 为 JSON 字符串或数组**两种形态（后端存字符串，同 `citations` 惯例）；字段名以 `QaImageRef` record 为准（`imageId`，非实体 `id`）；`el-image` 缩略用 `thumbUrl || url`，**`preview-src-list` 必须用 `url`（原图）**——`thumbUrl` 是七牛 webp 派生，既有教训；`preview-teleported` + 移动端横向滚动、触控目标 ≥44px；历史消息无 `imageRefs` → `v-if` 不渲染。

---

## 7. 开关契约（AC4）

- `QaService` **不读** `SettingService.kbEnabled` / `sparkora_setting`：问答链路完全独立于「生成注入」开关。
- KB 域是否参与检索由检索层既有 `AiProperties.ragKbEnabled` 决定；NEWS 域由 `ragNewsTopk` 决定。
- 因此 `kb_enabled=false` 时问答仍可用（KB 块按既有生成语义在配额层排除，CAR/NEWS 正常）。

---

## 8. 关键实现路径

- 后端：`com.sparkora.qa.service.{QaService,QaImageRefService,QaImageIntent}`、`web.controller.QaController`、`web.dto.QaAskDto`、`domain.entity.QaSessionEntity`/`QaMessageEntity`、`mapper.QaSessionMapper`/`QaMessageMapper`。
- 前端：`views/QaChat.vue`、`views/project/deep/CitationList.vue`、`api/index.js` 的 `qaApi`。
- 表：`sparkora_qa_session` / `sparkora_qa_message`。

---

## 9. 验收清单

- [x] AC1 可创建会话、提问、得到带来源引用的答案（2026-09-12 check 真机：`POST /qa/sessions` → `POST /qa/sessions/1/messages` code=0 / ragStatus=OK / assistant.citations 含 CAR+NEWS；`sparkora_qa_message` 落库）
- [x] AC2 同一会话多轮追问上下文连贯（2026-09-12 check 真机：首问「海狮08续航配置」→ 追问「那它的价格呢？」正确解析为海狮08价格并答出 DM-i/EV 价格区间，未串到「海豹08」）
- [x] AC3 答案可引用车型/新闻/通用 KB 三类来源并正确标注（2026-09-12 check 真机：KB 问「家用充电桩怎么选」citations 含 KB(`通用知识`)/CAR/NEWS；CitationList NEWS 分支=官方新闻/danger）
- [x] AC4 `kb_enabled=false` 时问答仍可用（2026-09-12 check 真机：setting `kbEnabled=false` 下提问仍 code=0/ragStatus=OK；`grep` 确认 QaService/QaController 无 SettingService/kb_enabled 引用）
- [x] AC5 `mvn -q -DskipTests compile`、`mvn test`（71 全绿）、`npm run build` 通过（2026-09-12 implement 复验）
- 09-15 qa-auto-illustrate（子D，2026-09-17）AC 见该任务 `prd.md`；实现/验证结论见 `.trellis/tasks/09-15-qa-auto-illustrate/`（归档后位于 `archive/2026-09/`）。

---

## 10. 已知限制

- 问答为**同步一次性返回**，无流式输出（AI 超时见 `.env AI_TIMEOUT_MS`，前端 `ask` 超时 120s）。
- 历史无摘要压缩，超长会话丢最旧（见 §2）。
- 会话不可分享/导出（Out of Scope，后续可扩展）。
- `sparkora_*_embedding` 为物理表（无 `deleted`），删除带逻辑删除的实体时需按外键一条 SQL 兜底物理清（既有实现已处理）。
