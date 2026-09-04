# 执行计划:unified-retrieval

## Step 1 统一检索 SQL + mapper

- [ ] `CarDocEmbeddingMapper.searchTopKUnified(queryVec, limit)`(design §2 UNION ALL,含 modelName 列)。
- [ ] 编译绿。

## Step 2 CarRagService 重构

- [ ] 新主签名 `retrieveForGeneration(query, topK, anchorModelIds)`:统一检索+子查询+锚点加权+统一配额+行内来源标注;旧签名委托保留。
- [ ] `AiProperties.ragAnchorBoost`(默认 1.15)+ application.yml + .env.example。
- [ ] `CarRagServiceTest` 扩展:统一合并/锚点加权/来源标注/旧签名委托/四态回归(FakeMapper 加 searchTopKUnified)。
- 验证:compile + 全量测试绿。

## Step 3 调用方与前端文案

- [ ] BriefService/VersionService 改传 anchor(一行);/api/car/rag 问答不动。
- [ ] ProjectEdit 车型关联文案改「写作锚点(可选)」。
- 验证:npm run build 绿。

## Step 4 端到端回归

- [ ] 重启后端;项目 18(未关联)重生成简报 → ragStatus=OK 且上下文含海狮08 价格块(AC1)。
- [ ] 已关联项目锚点优先验证(AC2,日志对比)。
- [ ] vector-stats/clean-stats 无回归。

## Step 5 收尾

- [ ] spec §6b/§6c 更新统一检索契约;commit `feat(S8): 统一检索去车型门禁+锚点加权`。
- [ ] trellis-check;归档任务。

## 回滚点

- 单提交,git revert 即回 S7 行为;无表结构/数据迁移。