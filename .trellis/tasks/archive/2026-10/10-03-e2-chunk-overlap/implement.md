# Implement — E2 切块滑动重叠 + 全库重嵌

> 依赖 E1（PgVectorStore 单表 + store 读路径）已完成。本任务只改切块 + 重嵌，不改检索业务规则。

## 0. 背景与范围界定（先读再动手）

- `TextChunker` 服务 **KB 与 NEWS 两个文本域**；**CAR 域块（参数分组）与 IMAGE 域（ImageEmbeddingTextBuilder）
  不走 TextChunker**，其切块形态不应被本任务改变。
- 因此「滑动重叠」的实际作用域 = **KB/NEWS 文本块**；「全库重嵌」= 4 域都重建 store 向量，
  但只有 KB/NEWS 的块内容会变（CAR/IMAGE 重建产出应与现状等价）。
- **兼容约束**：`TextChunker.chunk(header,content,titlePresent,keepTitleWhenEmpty,separators)` 是公共签名，
  既有调用方（KB/NEWS）与测试依赖；新增重叠必须**向后兼容**（默认无重叠 = 旧行为），
  或新增重载/参数且默认值保持旧语义，避免破坏既有 649 用例。

## 1. 切块重叠实现

- [ ] `TextChunker` 增重叠能力：相邻块在**句读边界**上重叠 N 字符（保守默认，如 50–80 字符；
      由常量/参数控制）。保持「KB 句读 `。；!?`、NEWS 句读 `。；;！!？?`」差异。
- [ ] 默认参数 = 旧无重叠行为，保证既有调用方/测试不回归；KB/NEWS 服务层显式启用重叠。
- [ ] 避免重复块：重叠仅追加尾部上下文，不做整块重复。
- [ ] 单测：空正文、单段、多段、超长段、句读边界重叠、KB/NEWS 句读差异、默认无重叠等价旧实现。

## 2. 全库重嵌

- [ ] 经既有 rebuild 入口重建 4 域：CAR `POST /api/car/models/rebuild-all`、KB
      `POST /api/kb/docs/{id}/rebuild`、NEWS `POST /api/news/{id}/rebuild`、IMAGE
      `POST /api/images/embeddings/rebuild`。**不新增破坏性脚本**。
- [ ] 重嵌后 store 同步（E1 已把 rebuild 路径接 store）；对账 `embeddedCount == chunkCount`（4 域）。
- [ ] 旧 4 表与 store 均刷新到新切块；旧表**不删**。

## 3. 阶段 B 验收

- [ ] 代表 query 集（覆盖 KB/NEWS 文本召回 + CAR 参数 + 锚点）跑迁移前后对比：
      命中集合理包含旧命中、分数分布不崩、四态不恶化；记录改进（如边界语义召回）。
- [ ] 报告落 `research/parity-B.md`（含 query、前后命中/分数、结论）。
- [ ] **不要求**逐条一致（切块有意变更）。

## 4. 验证命令

```bash
mvn -q -DskipTests compile
mvn test
./dev.sh restart backend
# 触发 4 域 rebuild + 对账查询
```

## 5. 风险 / 回退

- 切块变更→全库重嵌不可逆（回退需再重嵌回旧切块）：先小范围（KB 3 块）验证重叠效果，
  再全量；每步留旧表可回退。
- 重叠过大导致块数膨胀/检索噪声：以阶段 B 指标调参，默认保守。
- 回退点：R-B（revert E2 提交 + 重嵌回旧切块）。
