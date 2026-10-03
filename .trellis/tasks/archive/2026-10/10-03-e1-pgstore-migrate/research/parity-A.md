# E1 阶段 A 对拍报告(parity-A)

- 固定 query 集:7 条;候选窗 topK=32
- 模型:Qwen3-Embedding-8B;score 容差 0.0001;比较 (source,refId) 序列 + score
- 方法:同一 query 向量分别跑旧 SQL(逐字复刻 searchTopKUnified)与新 store SQL(复刻 PgVectorStore COSINE 查询)

## query: 海狮08EV 续航  (anchors=[55])
- **CAR+KB**: OK  old=32 new=32 order_equal=True
- **NEWS**: OK  old=32 new=32 order_equal=True
- **IMAGE**: OK  old=10 new=10 order_equal=True

## query: 大唐EV 价格  (anchors=[39])
- **CAR+KB**: OK  old=32 new=32 order_equal=True
- **NEWS**: OK  old=32 new=32 order_equal=True
- **IMAGE**: OK  old=10 new=10 order_equal=True

## query: 海狮06EV 动力与充电  (anchors=[33])
- **CAR+KB**: OK  old=32 new=32 order_equal=True
- **NEWS**: OK  old=32 new=32 order_equal=True
- **IMAGE**: OK  old=10 new=10 order_equal=True

## query: 比亚迪 新车型 发布  (anchors=[])
- **CAR+KB**: OK  old=32 new=32 order_equal=True
- **NEWS**: OK  old=32 new=32 order_equal=True
- **IMAGE**: OK  old=10 new=10 order_equal=True

## query: 充电桩怎么选  (anchors=[])
- **CAR+KB**: OK  old=32 new=32 order_equal=True
- **NEWS**: OK  old=32 new=32 order_equal=True
- **IMAGE**: OK  old=10 new=10 order_equal=True

## query: 比亚迪海外销量  (anchors=[])
- **CAR+KB**: OK  old=32 new=32 order_equal=True
- **NEWS**: OK  old=32 new=32 order_equal=True
- **IMAGE**: OK  old=10 new=10 order_equal=True

## query: 深度分析海狮08定价逻辑，这个价格到底贵不贵？  (anchors=[])
- **CAR+KB**: OK  old=32 new=32 order_equal=True
- **NEWS**: OK  old=32 new=32 order_equal=True
- **IMAGE**: OK  old=10 new=10 order_equal=True

## 结论: 全部一致

## 对拍口径与差异说明

- **query 向量唯一**:两条路径共用同一次远程 embedding 结果,消除「两侧各自嵌入」的抖动。
- **精确扫描**:对拍期间 `SET enable_indexscan=off; SET enable_bitmapscan=off`,让排序走 seq scan + sort,
  以验证「向量/过滤/距离公式」等价,不掺入 HNSW 近似噪声。生产默认仍用 HNSW(两套索引图不同,
  边界近似结果天然有差异,但不影响语义/四态/配额)。
- **候选窗口隔离复现**:旧 `searchTopKUnified` 的 ① 段 = (CAR UNION ALL KB) 共用一个 top-k 窗;
  新路径 = CAR+KB 合并一次 `similaritySearch(topK=k)` + NEWS 独立一次,Java 合并并全局按 score 排序。
  两者候选集逐条一致(见上)。
- CAR 过滤 `deleted=0`、KB `deleted=0 && enabled`、NEWS `deleted=0 && news.deleted=0` 分别对齐
  回填时 `active=true` / `active` 由活表同步层维护(AC-A3 已验证)。
- 旧 `JOIN sparkora_car_model m` 取车型名 → 新 metadata `name`(回填自旧表 JOIN)。
- 旧 `docId` = 域内块 id → 新 metadata `refId`。

## 端到端验证(实际 Spring AI `similaritySearch` 读路径,running app)

- `POST /api/car/rag {modelId:55,"海狮08EV 续航"}` → 命中 CAR 参数块,score 0.79+。
- QA 问答 `海狮08EV 续航多少？` → citations `source=CAR`,docId=1635/1636…(域内块 id 正确回填)。
- QA 问答 `比亚迪海外销量最近的新闻` → citations `source ∈ {CAR, NEWS}`,证明 CAR+KB 合并窗与 NEWS 独立窗
  经 `in(domain)` 过滤表达式在真实 store 上工作。
- `POST /api/images/search {query:"销量海报"}` → 命中 IMAGE 域(score 0.60),证明 IMAGE 域检索路径。

## 活表同步三路径验证(AC-A3 / AC-A4)

| 场景 | 操作 | 效果 |
|---|---|---|
| 软删/停用 → active=false | 直更 store metadata active=false(modelId=55 的 24 行) | `/api/car/rag` topK=3 立即返回 0 命中 |
| 恢复 active=true | 直更回 true | 立即恢复 3 命中 |
| 换模型 → embeddingModel=OTHER | 直更 modelId=55 为 OTHER | `/api/car/rag` 立即返回 0 命中 |
| 恢复当前模型 | 直更回 Qwen3-Embedding-8B | 立即恢复 3 命中 |
| KB 重建写路径 | `POST /api/kb/docs/2/rebuild` success=3 | store KB 3 行 embeddingModel/active 正确同步 |

> 注:上表「直更 metadata」模拟「活表同步层」的最终态;同步层本身由
> `CarDocService`/`KbDocService`/`NewsDocService`/`ImageEmbeddingService` 的
> persist/delete/setActive 调用实现(单测 `*TransactionTest`/`VectorStoreServiceTest` 覆盖 SQL 形制)。
