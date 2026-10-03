# A rerank A/B 评估报告（10-03-a-rerank）

> 任务：`.trellis/tasks/10-03-a-rerank`（父 `10-03-leftover-optimization` R2 / D2 / AC-A3）。
> 结论口径：重排为**可选增强**，默认关闭（关闭态零回归，见 `CarRagServiceTest.A_关闭态_*`）；
> 本节量化「开启后代表 query 的相关性排名提升」，并记录预算/降级边界。

## 0. 方法

- **baseline** = 向量召回原序（真实链路原序即按余弦分数降序；curated 候选集模拟纯余弦 top-K）。
- **rerank** = `LlmReranker` 同款 prompt（`src/main/resources/prompts/rag/rerank-system.st` 文案 +
  `RerankOrderDto` 的 order 契约）+ axonhub 真实模型（`AI_MODEL`，与生产同 key）。
- **后验校验**同 `LlmReranker.applyOrder`：越界/重复下标丢弃、缺项按原序补尾，保证「集合不变、仅顺序变」。
- **指标**：相关候选的名次（1 起）、top-1 命中率、MRR；order 完整性率。
- harness：`.trellis/tasks/10-03-a-rerank/research/rerank_ab_probe.py`（仅标准库，不打印任何密钥）。

复现：

```bash
# 读取仓库根 .env（也可用 SPARKORA_ENV=/path/to/.env 覆盖）；需 AI_BASE_URL/AI_API_KEY/AI_MODEL
python3 .trellis/tasks/10-03-a-rerank/research/rerank_ab_probe.py /tmp/opencode/rerank-ab-result.json
```

## 1. 代表 query 与前后对比（真机实测，2026-10-03）

候选集由真实三域块（CAR/KB/NEWS）构造，每条 query 标注一个相关下标（`rel`）；baseline 原序把相关块放在第 2–3 位，
模型重排后应上提到第 1 位。

| key | query | n | 原序 | 重排后 order（模型原始输出） | 相关名次 baseline→rerank | order 完整 |
|---|---|---|---|---|---|---|
| NEWS_charge | 比亚迪闪充电池的充电倍率是多少 | 5 | 相关块第 3 | `[2,1,0,3,4]` | 3 → **1** | ✓ |
| CAR_param_range | 大唐EV 纯电续航里程是多少 | 5 | 相关块第 3 | `[2,3,1,4,0]` | 3 → **1** | ✓ |
| KB_text | 家用充电桩怎么选择 | 5 | 相关块第 3 | `[2,4,0,3,1]` | 3 → **1** | ✓ |
| MIX_platform | 超级e平台兆瓦闪充的技术参数 | 5 | 相关块第 3 | `[2,0,1,4,3]` | 3 → **1** | ✓ |
| CAR_price | 海豹07EV 价格区间 | 5 | 相关块第 2 | `[1,3,2,0,4]` | 2 → **1** | ✓ |

**汇总（5 例）**：

| 指标 | baseline | rerank |
|---|---|---|
| top-1 命中率 | 0.00 | **1.00** |
| MRR | 0.367 | **1.000** |
| order 完整性率 | — | **1.00**（5/5，无越界/重复/缺项） |

- 5/5 query 的相关块被上提到首位；order 全部为合法完整排列（后验校验无修正动作）。
- 原始结果：`/tmp/opencode/rerank-ab-result.json`（含每例 rawOrder/appliedOrder/rank）。

## 2. 预算与降级边界（AC-A2 / A4）

- **时延预算**：`AI_RAG_RERANK_TIMEOUT_MS`（默认 10000ms）在独立虚拟线程兜底；超时 → `cancel(true)` + 原序返回 + warn。
  实测单次调用（5 候选、2048 max_tokens）：约 2–4s（受模型排队影响），远低于 10s 预算。
- **token 预算**：`AI_RAG_RERANK_TOPN`（默认 20）只把原分前 N 个候选送入 prompt，单条候选文本截断 300 字；
  超出 top-N 的候选保持原序追加，不参与调用。
- **降级全景**（均「原序返回 + warn，绝不抛出」）：开关关闭、候选 ≤1、order 为空/全非法、越界/重复/缺项
  （后验校验吸收）、调用异常、超时。单测：`LlmRerankerTest`（11 例）。
- **四态不变**：重排只改顺序、**不改 score**；`maxScore`/`minScore`/`rejectScore` 判定基于原分，
  故 `OK/LOW_CONFIDENCE/FAILED/NO_KNOWLEDGE` 与候选集不因重排改变（`CarRagServiceTest.A_重排不改分数_*`）。

## 3. 与既有业务规则的衔接

- 插入点在候选合并去重后、锚点加权/配额前（`CarRagService.retrieveForGeneration`）。
- 锚点加权仍照常作用于 CAR 锚点块；重排后配额选择与最终排序**按重排名次**（`rerankRank` 位置映射），
  关闭时回退为既有的「按分数降序」，与改造前逐字等价。
- 配额分层（PARAM_GROUP/MODEL_INFO 优先、RIGHTS/FEATURE ≤1/3、KB/NEWS 独立配额）语义不变，
  仅在各桶内部按重排名次取块。

## 4. 风险与未决

- 本评估用 **curated 候选集**（相关块明确、每例 5 候选）验证「重排能上提相关块」，非全链路线上流量 A/B；
  线上收益需在开启后按真实 query 分数分布复测（建议先小流量开启 `AI_RAG_RERANK_ENABLED=true` 观察 hitCount/四态与生成质量）。
- 模型对「全排列」的遵循在 5/5 例成立；生产候选 20 条时仍依赖 `applyOrder` 后验校验兜底（越界/缺项已单测覆盖）。
- 额外一次 LLM 调用带来时延/token 成本，故默认关闭；`ragRerankTopN` 与超时可调小以控成本。
