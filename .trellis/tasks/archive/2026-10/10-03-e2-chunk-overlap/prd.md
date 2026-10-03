# E2 切块滑动重叠 + 全库重嵌

## Goal

给 `TextChunker` 增加滑动重叠，减少跨块边界语义被切断；并对 4 域全库重嵌。

## Depends On

- **E1**（PgVectorStore 迁移阶段 A）必须先完成——本任务的阶段 B 对拍基于 E1 的 store 路径。

## Requirements

- 父 R5（阶段 B）、R6（#3）。
- `TextChunker.chunk` 增重叠参数（默认值保守；KB/NEWS 句读集合差异保持）。
- 重叠策略：相邻块尾部/头部按 N 字符（或句读）重叠，避免重复块。
- 全库重嵌：CAR/KB/NEWS/IMAGE 经既有 rebuild 入口 + store 回填。
- 阶段 B 验收：语义合理、改进可观测，**不要求**与旧逐条一致。

## Acceptance Criteria

- [ ] 重叠参数化并有单测（边界：空正文、超长段、单段、多段）。
- [ ] 4 域重嵌完成、无缺失（对账 `embeddedCount == chunkCount`）。
- [ ] 阶段 B：代表 query 的召回/相关性改善可观测（记录前后对比）。
- [ ] `mvn test` 全绿。

## Out of Scope

- rerank（D5）、KB 规范化（E3）。
