# Implement — 向量层 PgVectorStore 迁移 + 知识库规范化

> 父任务执行计划（父任务拥有任务映射与跨子验收；实际实现由子任务 E1–E5 完成）。
> 子任务各自另有 `implement.md`。

## 0. 总门与顺序

- 顺序：**E1（硬门）→ E2 → {E3, E4, E5}**。E2 依赖 E1；E3/E4/E5 依赖 E1（E5 另依赖 E2）。
- **G-A 阶段 A 对拍门**：E1 通过前不得切读路径、不得删旧表。
- **G-B 阶段 B 门**：E2 验收前不进入 E5。

## 1. E1 — PgVectorStore 迁移阶段 A（前置，阻塞其余）

- [ ] Flyway 迁移：建单张 `vector_store` 表（维度 1024、HNSW cosine、metadata 相关索引），
      不依赖 Spring `initialize-schema=true`。
- [ ] Store 适配：封装「每域 `similaritySearch(domain, topK)` + Java 合并」；`content`=chunk_text，
      metadata 按 design §3.1。
- [ ] 4 域回填 runner（异步、`REQUIRES_NEW`、异常全吞、幂等差集）。
- [ ] 活表同步层：软删/停用/重建三路径同步 `active`（定案 API：native SQL 更新 metadata 或 delete+add）。
- [ ] `embeddingModel` metadata 过滤。
- [ ] Harness（阶段 A）：固定 query 集逐条对拍，落 `research/parity-A.md`。
- 验证：`mvn test` 全绿；`./dev.sh restart backend` + 冒烟；对拍报告达标。
- 回退点：R-A = E1 切读提交；旧 4 表未删。

## 2. E2 — 切块滑动重叠 + 全库重嵌

- [ ] `TextChunker` 增重叠（参数化，默认保守；KB/NEWS 句读差异保持）。
- [ ] 4 域 rebuild + store 重嵌；对账 `embeddedCount == chunkCount`。
- [ ] 阶段 B 验收（语义合理 + 改进观测），落 `research/parity-B.md`。
- 验证：`mvn test`；对账查询；代表 query 前后对比。
- 回退点：R-B。

## 3. E3 — KB 数据模型规范化

- [ ] 受控 `domain` 词表（代码常量）+ 校验/归并策略。
- [ ] Flyway 加列 `source`/生效期；entity/mapper/store metadata；docs 三处同步。
- 验证：`mvn test`；检索按新维度过滤用例。
- 回退点：R-E3。

## 4. E4 — 命名规范化 car_doc→块语义

- [ ] Flyway `RENAME` 表 + 索引；entity/mapper/引用全量更新；docs/spec 同步。
- 验证：`mvn test`；`flyway_schema_history` 正常；无 API 变化。
- 回退点：R-E4。

## 5. E5 — 覆盖度三域统一 + 去重缓存

- [ ] `coveredText` 扩 CAR/KB/NEWS；防编造契约不回归。
- [ ] `content_hash` 去重/嵌入缓存（同模型复用）。
- 验证：`mvn test`；嵌入调用数下降可观测。
- 回退点：R-E5。

## 6. 父任务集成（全部子任务达标后）

- [ ] 跨域端到端回归：主题→简报→多版本正文→深度→编辑→预览（发布只做不破坏验证）。
- [ ] 检索 API/`rag_status`/`rag_citations`/前端交互契约不变；`npm run build`。
- [ ] **旧 4 表删除**（最后一步，需显式确认；此前一直保留）。
- 验证：`mvn test` 全绿 + 前端 build + 一键联调冒烟。

## 7. 全局验证命令

```bash
mvn -q -DskipTests compile
mvn test
./dev.sh restart backend
./dev.sh logs backend -f
npm run build   # frontend/
```

## 8. 风险清单 / 回退点

| 点 | 风险 | 回退 |
|---|---|---|
| R-A | store 迁移/对拍不达标 | revert 切读，旧表保留 |
| R-B | 切块重叠效果差 | revert E2（需再重嵌回旧切块） |
| R-E3 | KB 新列破坏兼容 | revert（列为可空/默认，兼容） |
| R-E4 | 重命名漏引用 | revert 迁移 |
| R-E5 | 缓存复用错模型向量 | revert E5 |

## 9. task start 前检查

- [ ] `prd.md`/`design.md`/`implement.md` 完成且内部一致。
- [ ] `implement.jsonl`/`check.jsonl` 含真实 spec/research 条目。
- [ ] 子任务 E1–E5 已建并链接父任务，各自 `prd.md` 写明依赖。
- [ ] 用户对最终规划摘要给出**新的**明确批准。
