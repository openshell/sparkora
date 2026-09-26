# P0 修复 实施计划

> 三个子问题独立，按 R1（1 文件）→ R3（1 文件）→ R2（6 文件，最大风险面）顺序实施；每步后可独立编译验证。

## 前置检查

- [ ] `git status` 干净基线（当前 dirty 1 path 需确认与本任务无关）
- [ ] `mvn -q -DskipTests compile` 通过（改动前基线）

## Step 1: R1 超时对齐（frontend/nginx.conf.template）

- [ ] `proxy_read_timeout` / `proxy_send_timeout` 180s → 300s
- [ ] 注释补交叉引用（值须 ≥ frontend/src/api/index.js 最长 axios 超时 300000ms）
- 验证：`docker compose config` 语法无误即可（不强制重建容器，产线部署时生效）；文件为模板，无本地构建验证手段

## Step 2: R3 JWT 查库校验（security/JwtAuthenticationFilter.java）

- [ ] 注入 UserMapper；新增 ConcurrentHashMap 缓存（userId→enabled/role/fetchedAt，TTL 60s，含负缓存）
- [ ] parse 成功后：查缓存/查库 → 用户不存在或 enabled!=true → 不注入 SecurityContext
- [ ] 查库异常 → fail-closed + log.warn
- [ ] role/username 以库为准注入；token username 与库不一致 log.warn（不阻断）
- 验证：`mvn -q -DskipTests compile`

## Step 3: R2 修复（6 文件，按 design.md 三类模式）

- [ ] 3a `ArticleProjectController.update`（L146-161）→ 模式 A 白名单编辑
- [ ] 3b `ArticleProjectController.setSelectedTitle`（L308-315）→ 模式 C 单列
- [ ] 3c `VersionService.setCurrent`（L332）→ 模式 C 单列
- [ ] 3d `VersionService.generate` 成功（L152）/失败（L162）→ 模式 B 条件更新
- [ ] 3e `ImitationService.analyze` 成功（L147）/失败（L159）→ 模式 B
- [ ] 3f `BriefService.generateFromFactSheet` 成功（L134）/失败（L144）→ 模式 B
- [ ] 3g `DeepController.generate`（L156-162）→ 模式 B（current_version_id 两条拆分语义）
- [ ] 3h `ClarifyService` 清错（L117）/写错（L144）→ 模式 C 单列
- 验证：`mvn -q -DskipTests compile` + `grep -rn "projectMapper.updateById\|mapper.updateById" src/main/java` 核对清单位置清零

## Step 4: 全量验证

- [ ] `mvn test`（38 个既有测试类不回归）
- [ ] `cd frontend && npm run build`
- [ ] 人工核对：design.md 中每处「保持不变」清单未被误改（preview-style/publish-meta/PublishService/抢占分支）

## 风险与回滚点

| 步骤 | 风险 | 回滚 |
|---|---|---|
| Step 1 | 无（模板值） | 改回 180s + 重建 frontend 容器 |
| Step 2 | 查库开销/缓存穿透；fail-closed 在 DB 抖动时造成短暂不可用 | git revert 单文件 |
| Step 3 | 状态推进条件更新 claimed=0 时的语义分支遗漏（如 PUBLISHED_DRAFT 上深度追加） | 逐文件独立提交，可单独 revert |

## 提交约定

- 分两次提交：`fix(security): JWT 查库校验+超时对齐`（Step1+2）、`fix(s2): 项目状态推进改条件更新消除并发回写`（Step3）
- commit message 中文，scope 按仓库惯例

## start 前检查

- [ ] implement.jsonl / check.jsonl 已填真实条目
- [ ] 用户已确认最终规划摘要