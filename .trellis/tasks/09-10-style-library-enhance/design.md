# Design — 完善风格库

## 0. 设计总览

三个改动面,相互独立可分步回滚:

1. 后端提炼拆两段:新增 `POST /api/styles/extract/preview`(AI 提炼**不入库**),入库复用既有 `POST /api/styles`;现 `POST /extract` 契约与行为保持不变。
2. 风格注入统一强化:深度链路改为后端按 `styleId` 回查风格表 + 风格指令迁入 system prompt;仿写多版本 system prompt 追加「特征必须体现」强化句。
3. 前端:风格库页统一「新增/提炼」两步式对话框;StepVersions 深度生成改传 `styleId`。

## 1. 后端设计

### 1.1 提炼不入库(draft)

- `StyleService` 重构(现 extract L64-96):
  - 新增 `StyleProfileEntity draft(String sourceText, String name)`:承载现有 AI 提炼逻辑(prompt text block、样文截 4000 字、`chatJson`、JSON 解析、命名优先 AI>用户>兜底「新风格」、source_excerpt 截 2000 字),**不 insert、不设 createdAt**;
  - `extract(sourceText, name)` 改为:`draft(...)` 结果补 `createdAt` + `insert`,对外行为与契约不变(接口保留)。
- `StyleController` 新增接口:
  ```
  POST /api/styles/extract/preview   @PreAuthorize ADMIN/EDITOR
  body: {name?, sourceText}          响应: R<StyleProfileEntity>(id=null,未入库)
  ```
  错误口径:样文空 → 400「样文不能为空」;AI 失败 → 500「风格提炼失败: …」(沿用 AiException 文案)。
- spec §3.3 同步新增该契约行;现有 6 接口契约/权限矩阵不变(Out of Scope 承诺)。

### 1.2 深度链路 styleId 回查(DeepController.generate L120-146)

- 入参兼容(同一 body):
  - 新:`{briefId, styleId}`(前端改造后唯一使用);
  - 旧:`{briefId, stylePrompt?, styleName?}` 保留解析,注释标 deprecated;
  - 优先级:`styleId` 非空 → 忽略旧参数;`styleMapper.selectById(styleId)` 查不到 → `R.fail(400, "风格不存在或已删除")`(用户显式选了风格,不静默降级);
  - 回查得 `toneGuidance`/`name` 后以字符串传给 `writerService.write(...)`——`DeepWriterService` 签名不变,`DeepController` 注入 `StyleProfileMapper`(controller 编排,与其现有 projectMapper 注入风格一致)。
- `DeepWriterService.write`:
  - 风格注入从 user prompt(L89-91「风格要求:\n」)**迁至 system prompt**:在既有铁律后追加「文风要求」段;
  - `style_tag` 落库防御:超 20 字符截断(列宽 VARCHAR(20),PG 超长直接 insert 报错会阻断整次生成;截断为防御底线)。

### 1.3 统一强化句(两处文案一致,内嵌字符串,沿项目 prompt 全内嵌惯例)

> 「以上语气、句式、结构与用词特征必须在正文中充分体现,不得只在部分段落贴合。」

- `VersionService.generateOne`(L175-181):`toneGuidance` 之后、输出契约之前插入;
- `DeepWriterService`:system「文风要求」段尾附同款。

## 2. 前端设计

### 2.1 styleApi(frontend/src/api/index.js)

- 新增 `extractPreview(name, sourceText)` → `POST /styles/extract/preview`,timeout 120s(与 extract 同);
- `generateDeep` 签名改 `(id, briefId, styleId)`,body `{briefId, styleId}`;
- `extract` 保留(契约不变;UI 不再一键入库)。

### 2.2 StyleLibrary.vue:统一「新增/提炼」对话框(R1+R2 一个实现)

- 顶栏两按钮:「新增风格」「从样文提炼」→ 打开**同一个对话框**,后者自动展开提炼区;
- 对话框结构:
  - 表单区:name(必填,≤64)/ description(≤500)/ toneGuidance(textarea)/ enabled 开关(默认 true);
  - 提炼区(可折叠):风格名(可选,AI 拟名提示)+ 样文 textarea(12 行)+「AI 提炼预填」按钮;
  - 预填流:调 `extractPreview` → 回填 name/description/toneGuidance(临时记录 sourceExcerpt,不展示输入项)→ 提示「已提炼,请检查修改后保存」;
  - 保存:`POST /api/styles` create(body 含 sourceExcerpt,来自提炼时非空;纯手工新增为空);
  - el-form rules:name 必填;触控目标与移动端单列沿用现有布局;
- 移除旧一键提炼对话框逻辑;编辑对话框不动。

### 2.3 StepVersions.vue

- L290 `generateDeep(id, briefId, style?.toneGuidance || '', style?.name || '')` → `generateDeep(id, briefId, styleId)`;
- 逐风格循环、成功/失败统计、失败预选重试逻辑不变(styleId 即循环变量)。

## 3. 数据流(提炼两步式)

```
粘贴样文 → POST /styles/extract/preview(AI,不入库)
        → 前端表单回填、人工修改
        → POST /api/styles(create 入库,携带 sourceExcerpt)
```

## 4. 兼容与迁移

- `/deep/generate`:旧参数保留解析(deprecated),新前端只发 styleId,无破坏性切换;
- `/styles/extract`:契约行为不变,保留;前端 UI 改走 preview + create;
- spec 同步点(实现完成后 Phase 3.3 执行):§3.3 加 preview 契约行、§13 `/deep/generate` 请求体改 `{briefId, styleId?}`(注明旧参数兼容)、§14 L681 仿写 system 强化句说明。

## 5. Trade-offs

- 强化句内嵌两处而非抽公共常量:项目 prompt 全内嵌惯例,两处复制成本低于新抽象;
- 风格回查放 DeepController 而非 DeepWriterService:controller 已承担状态机推进编排,service 保持字符串注入签名,diff 最小;
- style_tag 截断而非改列宽:表结构变更 out of scope;PG varchar 超长报错会阻断生成,截断是防御底线;
- 提炼预填不落草稿:无新表/新列;未保存提炼结果随刷新丢弃(用户显式决策「人工修改后再入库」);
- POST/PUT 继续以 Entity 收参(@Valid DTO 化为已知项目偏差,本次不改,对外契约不变)。

## 6. 风险与回滚

- AI 提炼 preview 超时:timeout 120s 与现 extract 一致,失败中文提示可重试;
- styleId 查无:400 明确提示,不静默降级;
- system prompt 变长影响生成质量:toneGuidance 仅 2-4 句,影响有限;AC3/AC4 人工抽检;
- 回滚点:后端两改动(提炼拆分 / 深度链路)与前端两文件相互独立,分步 commit 可单独 revert;旧参数兼容保证前端未更新时后端仍可用。