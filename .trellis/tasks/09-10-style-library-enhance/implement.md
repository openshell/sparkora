# Implement — 完善风格库

## 前置

- 依赖认知:prd.md(R1-R3/AC)、design.md(§1 后端 / §2 前端)。
- 无表结构变更、无权限矩阵变更。

## 执行清单(按序)

### Step 1 后端:提炼拆两段

- [ ] `StyleService`:新增 `draft(sourceText, name)`(现 extract L64-96 的 AI 逻辑,不 insert、不设 createdAt);`extract` 改为 draft+补 createdAt+insert。
- [ ] `StyleController`:新增 `POST /api/styles/extract/preview`(ADMIN/EDITOR,body `{name?, sourceText}`,响应 R<StyleProfileEntity> id=null);异常口径 400 样文空 / 500 提炼失败。

### Step 2 后端:深度链路 styleId 回查 + 统一强化

- [ ] `DeepController`:注入 `StyleProfileMapper`;`generate` 解析 `styleId`(非空优先,回查 `selectById`,查无 → `R.fail(400,"风格不存在或已删除")`);旧 `stylePrompt/styleName` 保留解析标 deprecated。
- [ ] `DeepWriterService`:风格注入迁 system prompt(「文风要求」段,铁律后);强化句附加;`style_tag` >20 字符截断。
- [ ] `VersionService.generateOne`(L175-181):toneGuidance 后插入同款强化句。

### Step 3 前端

- [ ] `api/index.js`:`styleApi.extractPreview`(timeout 120s);`projectApi.generateDeep(id, briefId, styleId)`。
- [ ] `StyleLibrary.vue`:统一新增/提炼两步式对话框(新增风格按钮、提炼区折叠、「AI 提炼预填」→ 回填 → 保存 create;移除旧一键提炼)。
- [ ] `StepVersions.vue` L290:改传 `generateDeep(id, briefId, styleId)`。

### Step 4 文档同步(Phase 3.3 一并)

- [ ] spec §3.3 加 `/styles/extract/preview` 契约行;§13 `/deep/generate` 请求体 `{briefId, styleId?}` + 旧参数兼容注;§14 L681 强化句说明。

## 验证命令

```bash
mvn -q -DskipTests compile        # 后端每步后
npm run build                     # 前端(frontend/ 目录)
```

## 手工验收(对照 prd AC)

- AC1/AC1b/AC2:风格库页新增(纯手填入库)、提炼预填(不入库回填)、两步式提炼——需后端启动 + 移动端视口检查。
- AC3/AC4:仿写/深度各生成一版,验证版本 style_tag 与正文风格贴合(需 AI 环境)。
- AC5:深度无风格(body 不带 styleId)仍可生成;空库推荐空数组不阻断(现状)。

## 风险文件与回滚

- 风险:DeepController/DeepWriterService(深度链路核心)、StyleService(extract 复用)。
- 回滚:Step1/Step2 独立 commit,可单独 revert;旧参数兼容保证前端/后端版本错配可用。

## task.py start 前检查

- [ ] implement.jsonl / check.jsonl 已填真实条目
- [ ] 用户批准 final planning summary