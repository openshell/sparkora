# 实施计划：事实手册近似条目合并与交叉验证

## 前置

- 任务：`.trellis/tasks/09-25-fact-claim-merge`；上游 `09-25-brief-web-search` 已提交。
- 验证命令：`mvn -q -DskipTests compile`、`mvn test`；如触及前端契约再跑 `cd frontend && npm run build`。

## 有序清单

1. **新增 `ClaimSimilarity`**（`src/main/java/com/sparkora/deep/service/ClaimSimilarity.java`）
   - `normalize(String)`：去 Markdown/HTML、标点空白、小写，仅留中英文数字。
   - `numberValues(String... texts)`：正则抽数值 → 归一（去千分位、`万`/`亿` 换算、`BigDecimal` 比较）→ 稳定去重集合；解析失败回退原 token。
   - `similarity(String a, String b)`：字符 3-gram 重合（Jaccard）+ 最长公共连续片段比，取分数；过短返回 0。
   - `sameClaim(String c1, String v1, String c2, String v2)`：数值签名硬前提 + 阈值（有数值 0.45 / 无数值 0.70）。
   - 阈值/常量集中定义并加中文注释说明依据。

2. **改造 `FactSheetService.merge`**（`src/main/java/com/sparkora/deep/service/FactSheetService.java`）
   - 保留 fact 展开顺序，改精确 `byClaim` 为**贪心簇**（代表 fact = 簇首条）。
   - 来源去重沿用 `sameSource`（url+modelName）；`crossCount` 改为去重来源数。
   - 保持 KB+WEB 冲突裁决 / 纯 KB 0.9 / 单 WEB 0.4 / 多源 MULTI 0.85 的既有优先级。
   - 增量字段 `sourcesList`、`sourceCount`。
   - 更新类注释（`:13-16` 旧置信注释与实际 0.85 不一致，一并校正）。

3. **补单测**（`src/test/java/com/sparkora/deep/service/ClaimSimilarityTest.java` + 扩充 `FactSheetServiceTest.java`）
   - AC-01 同 URL 近义 claim 合并。
   - AC-02 异 URL 近义 claim 合并 → `MULTI` 0.85、`crossCount`=2。
   - AC-03 「第2000座」vs「第1500座」不合并。
   - AC-04 语义不同共享文字不合并。
   - AC-05 同 URL 不产生交叉（不标 0.85）。
   - AC-06 KB+WEB 仍 KB 胜出、WEB 进 `alternatives`、不标待核实、`sourcesList` 保留 URL。
   - 数值签名：`200000` vs `20万` 视为一致；一侧有数字一侧无 → 不合并。
   - 回归：既有 6 个 `FactSheetServiceTest` 用例保持通过；`verifyNumbers` 用例不回归。

4. **前端 provider 展示（R10/AC-12）**
   - `frontend/src/views/project/deep/CitationList.vue:55`：WEB 条目名改为「provider · 域名」（缺 provider 回退域名）。
   - `frontend/src/views/project/deep/FactSheetSummary.vue:42-43`：WEB 的 `srcLabel` 追加 provider（缺省回退现值）。
   - 纯展示层；不改请求/响应结构。

5. **文档同步**
   - `docs/spec/brief-generation.md:87,96-97`：claim 近似合并规则、数值签名硬前提、`crossCount` 语义、增量字段；`docs/spec/brief-generation.md` 前端章节补充 WEB 来源展示 provider。
   - `.trellis/spec/backend/ai-rag-guidelines.md`：新增/补充事实手册合并的 code-spec（签名/契约/校验矩阵/测试点/Wrong-vs-Correct）。

6. **验证**
   - `mvn -q -DskipTests compile` → 通过。
   - `mvn test` → 全绿（含新增用例）。
   - `cd frontend && npm run build` → 通过（R10 触及前端，必跑）。

7. **提交**
   - 单 commit：`fix(deep): 事实手册近似 claim 归并恢复来源交叉验证`。
   - 不提交真实 `.env`；不新增数据库迁移。

## 风险文件 / 回滚点

- `FactSheetService.java`：核心改动点；测试必须覆盖正向合并与反向不合并。
- `ClaimSimilarity.java`：纯函数，独立可回滚。
- `CitationList.vue` / `FactSheetSummary.vue`：纯展示层，provider 缺省必须回退旧文案。
- 回滚：`git revert` 该 commit；无 schema 变更，历史 `fact_sheet` 不追溯。

## 完成前检查

- [ ] 数值签名硬门槛在实现中确实短路 `sameClaim`。
- [ ] `crossCount` 已改为去重来源数，文档同步。
- [ ] KB 冲突裁决路径未被近似合并逻辑绕过。
- [ ] 前端 provider 缺省回退旧展示；`npm run build` 通过。
- [ ] `FactSheetSummary.vue`/`CitationList.vue` 不读新字段也不报错。
