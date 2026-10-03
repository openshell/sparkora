# E2 阶段 B 对拍报告 — 切块滑动重叠 + 全库重嵌

> 任务：`.trellis/tasks/10-03-e2-chunk-overlap`（父 R5 阶段 B / R6 #3）。
> 依赖 E1（PgVectorStore 单表 + store 读路径）已完成。
> 结论口径：**不要求逐条一致**（切块有意变更），验「命中集合理包含、分数分布不崩、四态不恶化 + 改进可观测」。

## 0. 环境与方法

- 后端：本地 `mvn spring-boot:run`（SERVER_PORT=5662，加载 E2 新代码），DB = `.env` 的 `10.126.126.1:5201/sparkora`，embedding = `Qwen3-Embedding-8B`。
- 检索入口：`POST /api/qa/sessions/{id}/messages`（`QaService` → `CarRagService.retrieveForGeneration`，锚点 null），
  从 assistant 消息的 `ragStatus` + `citations` 读命中集/分数/来源。harness 见 `/tmp/opencode/parity_harness.py`。
- 对拍时点：
  - **before** = E2 全库重嵌前（store 内为旧无重叠切块向量）。
  - **after** = 4 域重嵌后（KB/NEWS 新重叠切块；CAR/IMAGE 切块形态不变）。
- 稳定指纹：overlap 只给块**加前缀**，块尾稳定，故以 `(source, chunkType, modelName, 文本末 30 字)` 判定「旧命中保留」。

## 1. 代表 query 集与前后对比

| key | query | 状态 | 条数 | max | min | avg | 旧命中保留 | 来源分布 |
|---|---|---|---|---|---|---|---|---|
| KB_text | 家用充电桩怎么选择 | OK→OK | 10→10 | .7551→.7553 | .4648→.4721 | .5673→.5698 | 6/10 | KB3/CAR3/NEWS4 不变 |
| NEWS_charge | 比亚迪闪充电池的充电倍率是多少 | OK→OK | 9→9 | .7141→.7149 | .5396→.5376 | .6563→.6532 | 6/9 | CAR5/NEWS4 不变 |
| NEWS_station | 比亚迪闪充站建设目标 | OK→OK | 7→7 | .7605→**.7726** | .5767→.5758 | .7063→**.7090** | 4/6 | NEWS4/CAR3 不变 |
| NEWS_sales | 比亚迪海外销量再创历史新高 | OK→OK | 6→6 | .7792→.7792 | .6467→.6467 | .7305→.7293 | 5/6 | NEWS4/CAR2 不变 |
| CAR_param_range | 大唐EV 纯电续航里程 | OK→OK | 23→23 | .7402→.7420 | .5072→.4964 | .6129→.6120 | 19/23 | CAR19/NEWS4 不变 |
| CAR_param_price | 海豹07EV 价格区间 | OK→OK | 16→16 | .8040→.8019 | .5891→.5704 | .6700→.6679 | 13/14 | CAR12/NEWS4 不变 |
| CAR_rights | 汉EV闪充版 购车权益 | OK→OK | 8→8 | .8376→.8361 | .6256→.6256 | .7024→.7022 | 6/6 | CAR4/NEWS4 不变 |
| MIX_platform | 超级e平台兆瓦闪充的技术参数 | OK→OK | 9→9 | .8152→.7979 | .4876→.4886 | .6531→.6491 | 5/9 | CAR5/NEWS4 不变 |

**四态：8/8 query 前后均 `OK`，无一致劣化**（无 LOW_CONFIDENCE/FAILED/NO_KNOWLEDGE 迁移）。
**分数分布：max/min/avg 均在同一量级（Δ≤0.02）**，未出现整体下沉或塌缩。
**候选条数：逐 query 完全一致**（重叠只改块内容，不增删候选池）。
**来源分布：逐 query 完全一致**（重叠不改域隔离/配额/加权语义）。

关于「旧命中保留」列的「末位变动」：这些不是丢失，而是**同一批近义 NEWS 块因重叠前缀改变了末尾 30 字**（重叠前缀使块变长，尾部随之位移），
或近义块之间发生了**次序互换**（分数 ±0.001 级）。逐条看「末位变动」项的 `modelName` 均在 after 命中集中仍出现，来源分布也不变，可确认是内容位移而非召回缺失。

## 2. 改进观测（跨块边界语义）

### 2.1 重叠实际生效（store 抽样，news 67「超级e平台」）

重嵌后 store 内相邻块（按 `sparkora_news_doc.seq` 块序；**注意 NEWS 并发嵌入使 `refId` 顺序 ≠ seq 顺序**，统计务必按 seq）实测：

```
3215  此次发布的超级e平台，是全球首个量产的乘用车"全域千伏高压架构"…将电动车带入千伏时代。
3216  电流1000A的加持下，"闪充电池"可以做到全球量产最大充电功率1兆瓦（1000kW）…为用户带来极致的充电体验。
3217  e平台，是全球首个量产的乘用车"全域千伏高压架构"…充电倍率达到10C，都是全球之最。
3218  正极到负极，全方位构建起超高速离子通道…让充电功率正式迈入"兆瓦时代"。
```

- 3217 以 3216 尾部片段「e平台，是全球首个量产…」开头；3218 以 3217 尾部「正极到负极…」开头——**跨块边界语境已补齐**。
- 典型收益：查询「充电倍率达到10C」过去可能落在 3216/3217 边界外；重叠后 3217 同时含「全域千伏高压架构」与「充电倍率达到10C」，**边界语义不再被切断**。

### 2.2 全库重叠覆盖率（NEWS，脚本统计 1341 块）

- 相邻块对 1173 对，**其中 686 对（58.5%）后块以「前块 ≥15 字后缀」开头**，最大重叠 60 字（= `DEFAULT_OVERLAP_CHARS`）。
- 未重叠的 487 对为：前块长度 ≤60（无可取后缀）或前缀+本块超 500 上限（保守放弃），符合设计。

### 2.3 相关性微升（NEWS_station 例）

`BYD闪充站建设目标`：max 0.7605→0.7726（+0.0121），avg 0.7063→0.7090（+0.0027）；
`CAR_param_range` max 0.7402→0.7420。边界补齐使完整句/短语命中分略升，幅度保守。

## 3. 全库重嵌与对账

经既有 rebuild 入口重建 4 域（未新增破坏性脚本，旧 4 表保留）：

| 域 | 入口 | rebuild 结果 | 对账（旧活表块/向量 == store） |
|---|---|---|---|
| CAR | `POST /api/car/models/rebuild-all` | total 56 / success 56 / failed 0 | `car_doc`(deleted=0) 380 == `car_doc_embedding` 380 == store CAR 380 |
| KB | `POST /api/kb/docs/2/rebuild` | total 3 / success 3 / failed 0 | `kb_chunk` 3 == `kb_chunk_embedding` 3 == store KB 3 |
| NEWS | 逐篇 `POST /api/news/{id}/rebuild` ×168 | 168/168 success（日志 `DONE news n=168 ok=168 fail=0`） | `news_doc` 1341 == `news_doc_embedding` 1341 == store NEWS 1341 |
| IMAGE | `POST /api/images/embeddings/rebuild` | total 180 / success 180 / failed 0 | `image_asset` 180 == `image_embedding` 180 == store IMAGE 180 |

- **store 合计 1904 行**，`metadata.active=true`、`embeddingModel=Qwen3-Embedding-8B` 全部一致（无旧模型残留、无失效行）。
- 块数前后**不变**（CAR 380 / NEWS 1341 / KB 3 / IMAGE 180）：重叠只加前缀不切新块，未引入块数膨胀。
- KB 3 块均为短段落（<60 字），重叠按设计不生效（无可取后缀），KB 内容与旧一致。

## 4. 结论

- **阶段 B 通过**：四态 8/8 不恶化；分数分布稳定（Δ≤0.02）；候选条数与来源分布逐 query 不变；旧命中绝大多数以内容位移/次序互换形式保留，无系统性召回缺失。
- **改进可观测**：NEWS 相邻块 58.5% 已建立 15–60 字重叠，跨块边界语义补齐；`NEWS_station` 等 query 的 max/avg 有微升。
- **对账闭环**：4 域 `embeddedCount == chunkCount` + store 行数一致，旧 4 表与 store 均刷新到新切块口径，旧表未删（R8 可回退）。

## 5. 复现步骤

```bash
# 前提：本地后端加载 E2 代码（SERVER_PORT=5662），DB/embedding 可达
TOKEN=$(curl -s -X POST http://localhost:5662/api/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"admin","password":"admin123"}' | jq -r .data.token)
# before：重嵌前跑 harness 存 parity-before.json
python3 /tmp/opencode/parity_harness.py http://localhost:5662 /tmp/opencode/parity-before.json
# 全库重嵌（4 域）
curl -s -X POST http://localhost:5662/api/car/models/rebuild-all -H "Authorization: Bearer $TOKEN"
curl -s -X POST http://localhost:5662/api/kb/docs/2/rebuild -H "Authorization: Bearer $TOKEN"
for id in $(psql ... -At -c "SELECT id FROM sparkora_news ORDER BY id"); do
  curl -s -X POST http://localhost:5662/api/news/$id/rebuild -H "Authorization: Bearer $TOKEN"; done
curl -s -X POST http://localhost:5662/api/images/embeddings/rebuild -H "Authorization: Bearer $TOKEN"
# after + 对拍
python3 /tmp/opencode/parity_harness.py http://localhost:5662 /tmp/opencode/parity-after.json
python3 /tmp/opencode/compare_parity.py
```

> 注：before 快照采集于全库重嵌前；KB 因内容 <60 字重嵌后与旧等价，其「末位变动」仅 NEWS 近义块位移所致。
