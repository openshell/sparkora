# 执行计划:kb-generalize

> 顺序执行;每步验证后打钩。验证命令在仓库根目录执行。回滚点:每步独立提交,任一步失败回退该步。

## 前置

- [ ] P0 阅读上下文:`implement.jsonl` 列出的 spec;`research/current-chain.md`(现状链路)。
- 验证:无(阅读步)。

## Step 1 数据层(schema + entity + mapper)

- [ ] `schema.sql` 幂等追加 `sparkora_kb_doc` / `sparkora_kb_chunk` / `sparkora_kb_chunk_embedding`(含 ivfflat 索引,与 car_doc_embedding 参数一致)。
- [ ] 实体:`KbDocEntity`/`KbChunkEntity`/`KbChunkEmbeddingEntity`(`domain.entity`);Mapper:`KbDocMapper`/`KbChunkMapper`/`KbChunkEmbeddingMapper`(含 `searchTopK` 全库检索 SQL,`@Select` 注解,参照 `CarDocEmbeddingMapper`)。
- 验证:`mvn -q -DskipTests compile` 绿;本地起库后启动日志无 SQL 错(联调环境可用 `./dev.sh restart backend` 探活 `/api/auth/me`)。

## Step 2 KbDocService(切块/重建/CRUD)

- [ ] `com.sparkora.kb.service.KbDocService`:create/update/delete/list/get/rebuild;切块算法按 design §3.1;重建幂等;embedding 失败计数与日志。
- [ ] 单测 `KbDocServiceTest`(Fake embedding/mapper,不连真实服务)。
- 验证:`mvn -q -DskipTests compile` + `mvn test -Dtest=KbDocServiceTest` 绿。

## Step 3 API 层

- [ ] `KbDocController`(design §4 六接口,`@PreAuthorize` + `R<T>` + `@Valid` DTO);`KbDocSaveDto`/`KbDocVo`。
- [ ] 更新 `.env.example`(AI_RAG_KB_TOPK / AI_RAG_KB_ENABLED)与 `AiProperties`。
- 验证:编译绿;`./dev.sh restart backend` 后用 httpie/curl 走一遍 POST→GET→PUT→rebuild→DELETE(带 admin token),返回 `code=0`。

## Step 4 CarRagService 双源统一检索

- [ ] `retrieveKb(query, topK)` + `KbChunkEmbeddingMapper.searchTopK`。
- [ ] `retrieveForGeneration` 合并通用域:空 modelIds 不再短路;KB 配额/来源前缀/知识来源行/多源失败判定按 design §3.2;`AI_RAG_KB_ENABLED` 开关。
- [ ] 扩展 `CarRagServiceTest`:双源合并/来源标注/开关关闭/单侧失败/空 modelIds 检索 KB。
- 验证:`mvn test -Dtest=CarRagServiceTest` 全绿。

## Step 5 生成链路接入

- [ ] `BriefService`/`VersionService`:去掉 `modelIds.isEmpty() → EMPTY` 短路,改调 `retrieveForGeneration`(内部处理空车型);prompt 注入来源行(KB 块前缀已带)。
- [ ] 核对 S6.1 前端展示位复用:状态四态不变,无需前端展示改动。
- 验证:编译绿 + 测试全绿;联调创建未关联车型项目生成简报,日志可见 KB 检索与来源行(AC1 部分证据)。

## Step 6 前端知识库页

- [ ] `src/views/kb/KbLibrary.vue` + 路由 + 菜单;CRUD/重建向量/启停用交互(design §5)。
- [ ] `npm run build` 绿;移动端宽度(375px)自查单列布局。

## Step 7 集成验证(AC1~AC4 端到端)

- [ ] 未关联车型项目 → 简报/正文生成 → 前端展示「知识库 · 已引用」(AC1)。
- [ ] KB 文档 CRUD + 重建后检索反映变化(AC2)。
- [ ] 已关联车型项目:车型块 + KB 块同上下文且来源可区分(AC3)。
- [ ] 人为制造 embedding 异常(改错 BASE_URL)→ FAILED 降级不阻断(AC4)。
- 验证:各项截图/日志证据记入本文件勾选处或 journal。

## Step 8 收尾

- [ ] 全量验证:`mvn -q -DskipTests compile` + `mvn test` + `npm run build`。
- [ ] spec 同步:`docs/s0-spec.md` §6b 修订(检索对象不再限于关联车型)+ 新增 §6c KB 字段级表格 + 配置表。
- [ ] trellis-check 全量检查;commit(`feat(S7): 通用汽车知识库+双源统一检索`,scope 按阶段号惯例)。

## 回滚点

- Step 1-3 独立可回退(新文件为主)。
- Step 4-5 涉及既有链路:`AI_RAG_KB_ENABLED=false` 运行时回退;git revert 兜底。
- Step 6 前端:下架路由/菜单即可。