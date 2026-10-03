# Implement — E5 覆盖度三域统一 + 去重缓存

## 1. 范围与依赖

- 依赖：E1（store 迁移）、E2（切块稳定）。均已满足。
- 本任务只碰「检索覆盖度声明」与「写入侧嵌入去重」两处；**不改**检索排序/配额/四态/锚点，不改对外契约。

## 2. #5 覆盖度三域统一（coveredText）

### 现状
`CarRagService.retrieveForGeneration` 组装 `coveredText` 时**仅对 CAR `PARAM_GROUP` 块**调用
`extractParamSummary`（抽取「参数名：值」行）。KB/NEWS 不产出覆盖度；`coveredText` 经
`version/rag-covered.st` 注入 version prompt（"仅可引用这些数值"）。

### 目标
`coveredText` 覆盖 CAR/KB/NEWS（IMAGE 不参与生成注入）：

1. **CAR（保持）**：`PARAM_GROUP` 仍用 `extractParamSummary`（`key→value`）。
2. **KB / NEWS（新增）**：抽取块内**数值事实**（复用与 C7 相同口径的数值归一，避免 1200/12000 类误配），
   格式 `〔通用知识：<标题>〕<数值,...>；〔官方新闻：<标题>〕<数值,...>`。
3. **去重 + 截断**：整体去重、长度上限（沿用现有 ~400 字口径，防灌爆 prompt）。
4. **语义不破坏防编造契约**：`rag-covered.st` 文案不变（"仅可引用这些数值，清单外禁止具体数值"）；
   只是清单内容从"仅 CAR"扩为"CAR+KB+NEWS"。

### 契约
- `RagResult.coveredText` 字段类型/位置不变，仍 `String`。
- **CAR-only 的历史行为不被回归**：当命中只有 CAR 块时，`coveredText` 与改造前逐字等价（测试锁定）。
- 数值抽取口径：与 `DeepWriterService`/`ClaimSimilarity` 的数值签名一致（复用其思路，勿另造一套）。

## 3. #6 内容去重 / 嵌入缓存（写入侧）

### 现状
写路径统一经 `EmbeddingBatchRunner.run(...)` → `EmbeddingClient.embed(text)`（每次网络调用）。
重建（rebuild / rebuild-all）对未变内容也重新嵌入。

### 目标
新增**内容寻址嵌入缓存**：相同 `sha256(text)` + 相同 `embedding_model` 的块复用已有向量，不再网络调用。

- 新表 Flyway `V8__embedding_cache.sql`：
  ```sql
  CREATE TABLE IF NOT EXISTS sparkora_embedding_cache (
      content_hash    CHAR(64)     NOT NULL,
      embedding_model VARCHAR(100) NOT NULL,
      embedding       TEXT         NOT NULL,   -- pgvector 字面量 "[...]"，缓存无需 ANN 类型
      created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
      PRIMARY KEY (content_hash, embedding_model)
  );
  ```
  （TEXT 存字面量：缓存只做键查回读，不做 ANN，规避 vector 类型映射。）
- `EmbeddingCacheMapper`（`selectByHashModel` / `insertIgnore`）。
- `EmbeddingClient` 新增 `embedForIndex(String): String`（缓存感知，返回 pgvector 字面量）：
  1. 计算 `hash = sha256(text)`；
  2. 命中 `(hash, model)` → 直接返回缓存字面量（**不网络调用**）；
  3. 未命中 → 走 `embed(text)` 计算；**best-effort** 写缓存（`INSERT ... ON CONFLICT DO NOTHING`，
     失败仅 warn，绝不影响主流程/不回滚）；
  4. `embed(String)`（查询用）**保持无缓存**（查询文本每次不同，缓存会污染+膨胀）。
- `EmbeddingBatchRunner` 改调 `embedForIndex`（写路径）；查询路径（`CarRagService` 向量化 query）不变。
- **模型切换安全**：键含 `embedding_model`，换模型天然 miss、绝不复用旧模型向量（AC3）。

## 4. 验证命令

```bash
mvn -q -DskipTests compile
mvn test
./dev.sh restart backend && ./dev.sh logs backend 20
cd frontend && npm run build
```

## 5. 验收对照

- [ ] `coveredText`三域生效 + 单测；CAR-only 场景逐字等价旧行为（回归锁）。
- [ ] 相同文本重复嵌入只 1 次网络调用（单测用计数 stub 断言；可观测）。
- [ ] 换模型缓存 miss（单测：不同 model 不复用）。
- [ ] `mvn test` 全绿。

## 6. 风险 / 回退

- R-E5：缓存键漏 model → 复用错模型向量（已用复合主键规避）；缓存写入失败污染主流程（已 best-effort + 全吞）。
- 回退：删缓存表 + revert；`embedForIndex` 可退回直调 `embed`。
- 不新增跨域重嵌编排（Out of Scope 未变）。
