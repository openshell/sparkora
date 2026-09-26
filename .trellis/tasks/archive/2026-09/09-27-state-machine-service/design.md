# 状态机推进逻辑收敛 技术设计

> 单仓库内重构，无 schema 变更、无接口签名变更（对外 API 行为不变）。

## 1. 新组件

```java
package com.sparkora.service;

/** 项目状态机唯一写权持有者(docs/README.md 4.2)。
 *  P0-② 修复的口径为基线:抢占带状态白名单+陈旧自愈,推进防回退,失败仅生成中回退。 */
@Service
public class ProjectStatusService {

    public static final long STALE_GENERATING_MS = 10 * 60 * 1000L;   // 唯一定义处

    /** 生成中判定(前端禁重复提交同款口径)。 */
    public boolean stuckGenerating(ArticleProjectEntity p)

    /** 409 守卫提示语(原 BriefService.projectStatusGuardMsg,三服务共用)。 */
    public static String guardMsg(ArticleProjectEntity p, String action)

    // ---- 抢占(条件更新置生成中) ----
    /** 简报域抢占:仅 DRAFT/READY 或陈旧生成中置 GENERATING_BRIEF,清 last_brief_error。claimed==0 抛 IllegalStateException(409)。 */
    public void claimBriefGenerating(Long projectId) throws IllegalStateException

    /** 版本域抢占:仅 READY/VERSIONS_READY 或陈旧生成中置 GENERATING_VERSIONS,清 last_version_error。 */
    public void claimVersionsGenerating(Long projectId) throws IllegalStateException

    // ---- 成功推进(条件更新,防回退) ----
    /** 简报成功:仅 GENERATING_BRIEF → READY,set current_brief_id + 清 last_brief_error;extraCols 同条写入(如 imitation_analysis)。 */
    public void advanceReady(Long projectId, Long briefId, Map<String,Object> extraCols)

    /** 多版本成功:仅 GENERATING_VERSIONS → VERSIONS_READY;首版两拆分(仅 current_version_id IS NULL 时设 firstVersionId,未命中第二条只推状态);partialErrors 可空写入 last_version_error。 */
    public void advanceVersionsReady(Long projectId, Long firstVersionId, String partialErrors)

    /** 深度单版成功:仅 READY/DRAFT → VERSIONS_READY;首版两拆分同上。 */
    public void advanceVersionsReadyFromReady(Long projectId, Long versionId)

    /** 发布成功:任意态 → PUBLISHED_DRAFT(可重发覆盖),set media_id/theme/published_at,清 last_publish_error。 */
    public void markPublished(Long projectId, String mediaId, String theme, LocalDateTime publishedAt)

    // ---- 失败回退(仅生成中状态,防覆盖并发推进) ----
    /** 简报/仿写失败:仅 GENERATING_BRIEF/GENERATING_VERSIONS → DRAFT,写 last_brief_error(截断1000)。 */
    public void failBriefToDraft(Long projectId, String reason)

    /** 版本失败:仅 GENERATING_VERSIONS/GENERATING_BRIEF → READY,写 last_version_error(截断1000)。 */
    public void failVersionsToReady(Long projectId, String reason)

    /** 发布失败:不动状态,仅写 last_publish_error(压缩空白+截断990,原 markFailure 口径)。 */
    public void markPublishFailure(Long projectId, String message)

    /** 单列错误列写入/清空(ClarifyService 异步清错等场景,不触碰状态)。 */
    public void writeBriefError(Long projectId, String reasonOrNull)
}
```

要点：
- `advanceReady` 的 extraCols 用 `Map<String,Object>`（R5）；`imitation_analysis` 由 ImitationService 传入,服务不感知业务语义。
- 两拆分等价:`advanceVersionsReady` 内先 `isNull("current_version_id")` 命中(含 current+status)即返回,否则第二条只推状态——与 `VersionService:152-171`/`DeepController:158-172` 逐字等价。
- 截断逻辑(1000/990)收进服务,调用方不再各自截断。
- `guardMsg` 保留 static(三服务静态引用现况),内部不再有重复实现。

## 2. 调用方改造(纯委托,行为不变)

| 调用方 | 现状 | 改后 |
|---|---|---|
| `BriefService.generate` | 内联抢占 L99/推进 L134/回退 L147 + 私有 claimGenerating/stuckGenerating | `claimBriefGenerating` / `advanceReady(projectId, b.getId(), Map.of())` / `failBriefToDraft`;删私有方法与常量 |
| `ImitationService.analyze` | 内联三份拷贝 | 同上;`advanceReady(projectId, b.getId(), Map.of("imitation_analysis", json))` |
| `VersionService.generate` | 抢占 L108-117/成功两拆分 L152-171/失败 L177 | `claimVersionsGenerating` / `advanceVersionsReady(first.getId(), partialErrors)` / `failVersionsToReady`;删 stuckGenerating |
| `DeepController.generate` | 成功两拆分 L158-172 | `advanceVersionsReadyFromReady(projectId, versionId)` |
| `PublishService.publish` | 成功 L107-116 / markFailure L131 | `markPublished` / `markPublishFailure`;删私有 markFailure 逻辑体 |
| `ClarifyService` | 清错/写错单列 UpdateWrapper | `writeBriefError`(清 null/写 reason);删手写 wrapper |
| `ArticleProjectController` | INSERT setStatus("DRAFT") L124 | 不动(创建初始态,非状态机转换) |

保持不变：schema.sql 启动回填（⑥ 处置）；`PreviewService` 内状态校验（只读）；前端 constants/project.js。

## 3. 兼容性

- 对外 API（路径/参数/错误码/提示语/HTTP 语义）零变化;只有 UPDATE 语句组织方式变化,WHERE/SET 逐项等价（AC4 单测断言）。
- 单测层面:既有断言 wrapper 列的测试(BriefServiceTest 等)改为对 ProjectStatusService 的 verify/参数捕获;新增 ProjectStatusServiceTest 直测新服务。
- 回滚:单 commit revert 即恢复 P0 修复后行为,无数据影响。

## 4. 验证命令

```bash
mvn -q -DskipTests compile
mvn test                              # 325 + ProjectStatusServiceTest
grep -rn 'set("status"' src/main/java | grep -v "CarSyncJob\|NewsSyncJob\|ArticleProjectController"   # 仅 ProjectStatusService
grep -rn "STALE_GENERATING_MS" src/main/java   # 仅 ProjectStatusService 一处
cd frontend && npm run build          # 前端不动,回归确认
```