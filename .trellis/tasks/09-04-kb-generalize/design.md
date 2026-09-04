# 设计:通用汽车知识库(kb-generalize)

## 1. 总体架构

延续「车型库保留 + 新增通用域」的既定路线(decision_id: dec-87296017859b7d49):

```
                    ┌─ 车型域: BYD 同步 → car_param_clean → car_doc(+embedding)   (既有,不动)
知识来源(双源) ──┤
                    └─ 通用域: 手工知识 → kb_doc → kb_chunk → kb_chunk_embedding  (新增)
                                        │
                    CarRagService(统一检索入口)
                        ├─ 车型域检索: modelIds 非空 → 既有分层配额+子查询(S6.2 策略不变)
                        └─ 通用域检索: 始终执行(全库 topK,跨项目)
                                        │
                    双源合并 → 来源标注上下文 → 注入 BriefService/VersionService prompt
```

## 2. 数据层(schema.sql 幂等追加)

```sql
-- 通用知识文档(手工录入的知识条目)
CREATE TABLE IF NOT EXISTS sparkora_kb_doc (
    id          BIGSERIAL PRIMARY KEY,
    title       VARCHAR(200) NOT NULL,             -- 知识标题(如「家用充电桩选择要点」)
    domain      VARCHAR(50)  NOT NULL DEFAULT '通用',-- 领域标签: 通用/充电/保养/政策/技术科普…
    content     TEXT         NOT NULL,             -- 原始正文(Markdown 纯文本)
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE, -- 停用后不参与切块重建与检索
    created_by  VARCHAR(50)  NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT now(),
    deleted     SMALLINT     NOT NULL DEFAULT 0
);

-- 知识切块(检索粒度;与 car_doc 对齐 chunk_type 概念,KB 统一 KB_CHUNK)
CREATE TABLE IF NOT EXISTS sparkora_kb_chunk (
    id          BIGSERIAL PRIMARY KEY,
    doc_id      BIGINT       NOT NULL REFERENCES sparkora_kb_doc(id),
    seq         INT          NOT NULL,             -- 块序号
    chunk_text  TEXT         NOT NULL,             -- 首行固定「知识:<title>(<domain>)」
    created_at  TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_kb_chunk_doc ON sparkora_kb_chunk(doc_id);

-- 知识块向量
CREATE TABLE IF NOT EXISTS sparkora_kb_chunk_embedding (
    id          BIGSERIAL PRIMARY KEY,
    chunk_id    BIGINT       NOT NULL REFERENCES sparkora_kb_chunk(id),
    embedding   vector(1024) NOT NULL               -- Qwen3-Embedding-8B,与 car_doc_embedding 一致
);
CREATE INDEX IF NOT EXISTS idx_kb_chunk_emb_vec ON sparkora_kb_chunk_embedding
    USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
```

设计取舍:

- **doc/chunk 分表**而非沿用 `car_doc` 单表:车型块生命周期绑定车型重建,KB 块绑定知识文档编辑;分开避免互扰,且 `car_*` 表不加列(边界约束)。
- **domain 为自由标签而非枚举表**:本期手工录入量小,枚举表过早;检索时 domain 仅作为展示/过滤辅助,不参与向量过滤。
- **enabled 停用开关**:知识过期可停用不删除,重建向量时跳过,检索层不需要特判(停用即重建后无块)。
- 删除策略:与项目其他表一致,KB doc 用逻辑删除 `deleted`,chunk/embedding 物理删除(重建幂等的先清后插)。

## 3. 服务层

### 3.1 KbDocService(新,`com.sparkora.kb.service`)

- `create(title, domain, content)` / `update(id, …)` / `delete(id)` / `list()` / `get(id)` / `rebuild(id)`。
- 切块算法(`chunkContent`):
  1. 首行固定 `知识:<title>(<domain>)`——跨域检索时块自带主题锚点(对齐 S6.2 P1 的「块首行车型全名」经验)。
  2. 正文按空行分段;单段 ≤500 字符直接成块,`知识:标题` + 段落拼接。
  3. 超长段按句读(。;;!?)切分合并至 ≤500 字符;单段切多块时块间保留 seq 连续。
  4. 空内容拒绝(校验层已拦,服务层兜底抛 IllegalArgumentException)。
- 重建幂等:delete chunks+embeddings by doc_id → 重新切块 → 逐块 embedding 入库;与 `CarDocService.rebuildForModel` 同款「先清后插」。update 后自动触发 rebuild。
- embedding 失败处理:单块失败 warn 日志跳过(与 CarDocService 一致);**失败块数与总数记录进返回值/日志**,供前端提示「部分块未向量化」——对齐 kb-clean-audit 的可观测要求,不留静默。

### 3.2 CarRagService 双源检索(扩展,不重写)

新增:

```java
// 通用域检索:无 modelId 约束,全库 topK
public List<TypedHit> retrieveKb(String query, int topK);        // 走 KbChunkEmbeddingMapper.searchTopK
public RagResult retrieveForGeneration(List<Long> modelIds, String query, int topK)
// 内部:车型域沿用现有逻辑;追加通用域 retrieveKb(query, kbTopK),独立配额合并
```

- **配额**:车型域块与 KB 块分别配额(车型域沿用 S6.2 配额;KB 块上限 `AI_RAG_KB_TOPK`,默认 4),互不挤占——避免重复 S6.2 诊断过的「权益块挤占参数块」问题。
- **来源标注**:注入上下文时车型块保持原样,KB 块统一加前缀 `【通用知识】`;已关联车型的上下文头部加一行 `知识来源:车型数据 + 通用知识库`,未关联为 `知识来源:通用知识库`。
- **状态判定(多源)**:
  - 两域都异常 → FAILED(语义不变:任一域异常即 FAILED,但**单域异常不否定另一域结果**——车型域异常时 KB 块仍注入,状态仍标 FAILED 以维持「降级可见」警示,日志记录来源)。
  - 双源合并后 rawHit==0 → NO_KNOWLEDGE;maxScore < rejectScore → LOW_CONFIDENCE;其余 OK(与现状判定完全一致,只是样本池扩大)。
  - 覆盖度声明(coveredText)仅统计车型参数块,KB 块不参与「参数覆盖」语义。
- **minScore 门槛**:KB 块与车型块共用 `AI_RAG_MIN_SCORE`(0.3)起步;如检索质量分化再拆 `AI_RAG_KB_MIN_SCORE`,本期不预拆(YAGNI)。

### 3.3 生成链路接入(BriefService/VersionService)

现状 `modelIds.isEmpty() → RagResult.EMPTY` 改为:

```java
CarRagService.RagResult rag = ragService.retrieveForGeneration(modelIds, query, topK);
// retrieveForGeneration 内部处理空 modelIds:车型域跳过,通用域照常检索
```

- prompt 组装:既有 rag 段落逻辑不变;`rag.context()` 为空但 status==OK 不可能(OK 必有 context),无需新分支;新增来源行已由 CarRagService 生成。
- 前端展示:版本/简报卡片既有「知识库 · 已引用」状态位直接复用(四态语义未变),**前端无需改动展示逻辑**;仅知识来源文案由 CarRagService 注入。

## 4. API 层(`com.sparkora.web.controller.KbDocController`)

| Method | Path | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/kb/docs` | 登录 | 列表(含 domain/块数/启用状态) |
| GET | `/api/kb/docs/{id}` | 登录 | 详情(含 content) |
| POST | `/api/kb/docs` | ADMIN,EDITOR | 新建(自动切块+向量化) |
| PUT | `/api/kb/docs/{id}` | ADMIN,EDITOR | 编辑(自动重建向量) |
| DELETE | `/api/kb/docs/{id}` | ADMIN,EDITOR | 逻辑删除 + 物理清块 |
| POST | `/api/kb/docs/{id}/rebuild` | ADMIN,EDITOR | 手动重建向量 |

DTO:`KbDocSaveDto { title @NotBlank @Size(max=200), domain @NotBlank, content @NotBlank @Size(max=50000) }`;`KbDocVo { id, title, domain, enabled, chunkCount, updatedAt }`。

## 5. 前端

- 新页 `src/views/kb/KbLibrary.vue`:表格列(标题/领域/块数/更新时间/操作),工具栏「新建知识」;编辑抽屉(el-drawer)内 title/domain/enabled/content 表单 + rules 校验;操作含「重建向量」「删除」(ElMessageBox 确认)。
- 路由 `/kb`,菜单加入口(与车型库同级);移动端单列、触控目标 ≥44px;复用 `src/api/http.js`。
- 生成页展示位不动(S6.1 已有)。

## 6. 配置(`AiProperties` + .env.example)

| 变量 | 默认 | 说明 |
|---|---|---|
| `AI_RAG_KB_TOPK` | `4` | 生成检索时通用域注入块数上限 |
| `AI_RAG_KB_ENABLED` | `true` | 通用域总开关(异常时一键回退纯车型域,回滚点) |

## 7. 兼容与回滚

- 旧数据零迁移:车型域行为完全不变;`AI_RAG_KB_ENABLED=false` 可整体关闭通用域(回退到 S6.2 行为)。
- 新表独立,回滚 = 停开关 + 前端下架入口;不触碰 car_* 数据。
- 风险点:KB 全库检索无 model_id 过滤,知识量增大后需要 domain 过滤/分区——本期知识量小(<100 doc),留待下期。

## 8. 测试设计

- `KbDocServiceTest`:切块(短段/长段切分/空内容拒绝)、重建幂等(重建后块数一致、旧块清除)、embedding 失败块计数。
- `CarRagServiceTest` 扩展:双源合并与来源标注、KB 开关关闭、双源单侧失败状态判定、未关联车型(空 modelIds)仍检索 KB。
- 不连真实 PG/embedding:沿用 FakeMapper/FakeEmbeddingClient 手写假件模式。