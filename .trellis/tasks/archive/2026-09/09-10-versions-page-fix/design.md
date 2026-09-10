# 技术设计：版本页显示与状态推进修复

> 最小侵入原则：后端只动 `DeepWriterService` + `DeepController` + `schema.sql`（回填段）；前端只动 `StepVersions.vue` + `api/index.js`（styleName 透传）。状态机常量/仿写链路零改动。

## 1. 后端

### 1.1 DeepWriterService.write 扩展（签名向后兼容）
```java
// 现: write(Long projectId, Long briefId, String stylePrompt)
// 改: write(Long projectId, Long briefId, String stylePrompt, String styleName)
```
- 落版本前补齐 4 字段：
  - `title`：AI 正文首个 H1（`^#\s+(.+)$` 多行首匹配）；缺失回退 `project.topic`。
  - `version_label`：`selectCount(project_id)` → LABELS[count]（A/B/C…，与 VersionService.LABELS 同表）；取 project 判空防御。
  - `style_tag`：styleName 参数；空回退「深度」。
  - `word_count`：`contentMd.length()`（与 VersionService.generate 口径一致）。
- 需注入 `ArticleProjectMapper`（现只注入 brief/version mapper）取 project（title 回退用）。

### 1.2 DeepController.generate 状态推进（对齐 VersionService.generate 成功分支语义）
```java
Long versionId = writerService.write(...);
ArticleProjectEntity p = projectMapper.selectById(projectId);
if (p != null) {
    // 首版(此前无 current)才设默认当前;追加不覆盖用户已选
    if (p.getCurrentVersionId() == null) p.setCurrentVersionId(versionId);
    if ("READY".equals(p.getStatus()) || "DRAFT".equals(p.getStatus())) p.setStatus("VERSIONS_READY");
    p.setUpdatedAt(LocalDateTime.now());
    projectMapper.updateById(p);
}
```
- 注入 `ArticleProjectMapper`。状态白名单只从 READY/DRAFT 推进（PUBLISHED_DRAFT 追加不回退，同 §4「配图为增量编辑，不回退」语义）。
- 失败分支不动（write 抛异常时控制器映射 500，项目状态不变）。

### 1.3 存量回填（schema.sql 幂等段，启动自动执行）
```sql
-- 09-10-versions-page-fix:深度链路历史版本补 version_label/style_tag/word_count/title
UPDATE sparkora_article_version v SET style_tag='深度'
 WHERE v.style_tag IS NULL;
UPDATE sparkora_article_version v SET word_count=length(v.content_md)
 WHERE v.word_count IS NULL AND v.content_md IS NOT NULL;
UPDATE sparkora_article_version v SET title=
  coalesce(nullif(split_part(v.content_md, E'\n', 1), ''), p.topic)
 FROM sparkora_article_project p
 WHERE v.project_id=p.id AND (v.title IS NULL OR v.title='');
-- version_label 按项目内创建序补(仅补 NULL 行,用窗口序映射 LABELS)
UPDATE sparkora_article_version v SET version_label = sub.lbl
 FROM (
   SELECT id, CASE rn WHEN 1 THEN 'A' WHEN 2 THEN 'B' WHEN 3 THEN 'C' WHEN 4 THEN 'D'
            WHEN 5 THEN 'E' WHEN 6 THEN 'F' WHEN 7 THEN 'G' WHEN 8 THEN 'H'
            ELSE 'A' END AS lbl
   FROM (SELECT id, row_number() OVER (PARTITION BY project_id ORDER BY id) AS rn
         FROM sparkora_article_version WHERE version_label IS NULL) t
 ) sub WHERE v.id=sub.id;
```
- 注意：仅对 NULL 行回填，重复执行无副作用（幂等）。**不能用 `DO $$`**（ScriptUtils 不支持 dollar-quote），全部单语句 CASE 写法。

### 1.4 接口契约增量（spec §13 表）
- `POST /deep/generate` body 增可选 `styleName`；响应不变 `{versionId}`；行为增量：落版本后推 READY→VERSIONS_READY + 首版设 current。

## 2. 前端

### 2.1 api/index.js
```js
generateDeep: (id, briefId, stylePrompt = '', styleName = '') =>
  http.post(`/projects/${id}/deep/generate`, { briefId, stylePrompt, styleName }, { timeout: 300000 }),
```

### 2.2 StepVersions.vue
- 深度分支调用处传 `style?.name || ''`。
- 显示兜底：chip/头部/对比选项/版本卡 meta 处 `v.versionLabel || '—'`、`v.styleTag || '深度'`、`v.wordCount || '—'`（模板层 `||` 兜底，避免 null/undefined 渲染）。
- `currentVersionLabel`：`cur.versionLabel || '—'` + `cur.styleTag || '深度'`。
- next-row：`v-if="project?.status === 'VERSIONS_READY'"` 的「再生成其他风格」外，补「进入预览 →」按钮：`v-if="project?.status === 'VERSIONS_READY' || (versions.length && project?.status === 'READY')"` → `router.push({ name: 'project-preview' })`。修复后状态会被后端推进，此按钮主要服务存量 READY-with-versions 项目与防御。

## 3. 权衡

| 决策 | 取舍 |
|---|---|
| styleName 由前端显式传，而非后端按 stylePrompt 反查风格库 | toneGuidance 是画像文本非唯一键，反查不可靠；契约加一个可选字段最直接 |
| 状态推进放 DeepController（成功分支）而非 DeepWriterService | writer 保持「纯写作+落版本」职责；控制器已有 projectMapper 类依赖模式可循 |
| 回填写 schema.sql 而非一次性脚本 | 项目惯例（schema.sql 幂等+启动自动执行）；幂等段保证重跑安全 |
| label 用 row_number 全量映射（非仅 NULL 计数） | 一次回填后不再有 NULL；对已 NULL 行按项目内创建序给 A/B/C… 与 VersionService 编号规则一致（该行此前编号缺失，重编号可接受） |

## 4. 回滚

- 后端改动独立可回退；schema 回填段是幂等 UPDATE，代码回退不产生新脏数据（回填后的正确值保留无害）。
- 前端两文件独立回退。