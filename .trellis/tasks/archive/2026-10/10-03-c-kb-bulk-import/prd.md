# C KB 批量导入

> 父任务:`../10-03-leftover-optimization`(R4 / D4)。
> **依赖:无**(可与 A/B 并行)。复用 E3 的 `KbDomain`/tags/生效期 + 切块 + 嵌入链路。

## Goal

支持批量新建 KB 知识文档,解决单条录入低效(E3 已具备数据模型,仅缺导入)。
纯新增端点 + 前端入口,**不改既有单条 CRUD 契约**。

## Confirmed Facts(仓库证据)

- `KbDocController`(`/api/kb`,S7 + 10-03 E3)已有单条 `POST/PUT/GET/DELETE /docs`、`POST /docs/{id}/rebuild`、`GET /domains`;**无批量导入**。
- `KbDocService.create(title, domain, source, tags, effectiveFrom, effectiveTo, content, createdBy)`(`KbDocService.java:86`)已封装:校验 → `KbDomain.normalize`(非法词表抛 `IllegalArgumentException`)→ tags normalize → 入库 → 切块(`TextChunker` + `DEFAULT_OVERLAP_CHARS`)→ 逐块 embedding 入单表 `vector_store`(经 `REQUIRES_NEW` 独立事务)。**批量导入只需逐条委托它**。
- 受控 domain 词表:`com.sparkora.kb.KbDomain`(通用/充电/保养/政策/技术科普/安全/驾驶,`normalize` 非法即抛)。
- 前端实际文件为 `frontend/src/views/knowledge/KbLibraryPanel.vue`(知识中心「知识库」tab 内);API 封装 `kbApi`(`frontend/src/api/index.js`)。
- 现有测试:`KbDocServiceTest`/`KbDocE3Test`/`KbDocTransactionTest`/`KbDomainTest`(单测直 `new` 服务 + mock mapper 范式)。

## Requirements

- **R1 端点**:`POST /api/kb/docs/batch`(ADMIN/EDITOR),multipart `file` + 可选 `format`(缺省按文件名后缀自动判定 `.csv`/`.json`/`.md`/`.markdown`,后缀缺失再按内容嗅探)。
- **R2 格式**:
  - CSV:表头行必填,列 `title,domain,content,source,tags,effectiveFrom,effectiveTo`;`tags` 以 `;` 分隔;支持引号包裹(内嵌逗号/换行/`""` 转义)。
  - JSON:对象数组,每对象字段同 CSV(字段名一致;`tags` 为数组或分隔字符串,兼容两者)。
  - Markdown:按一级标题 `# ` 分段为多篇(标题=H1 文本、正文=该段);无 H1 则整文件一篇(标题=文件名去后缀)。
- **R3 逐条语义**:逐条复用 `KbDocService.create`(含 normalize/切块/嵌入);返回
  `{total, success, failed, results:[{index,title,success,error}]}`;**单条失败不阻断其余**。
- **R4 非法/重复处置**:非法 domain / 空标题 / 空正文 / 超长 → 该条 `success=false` + 中文 `error`;
  重复(同 `title`+`domain`,库内已存在或本批内重复)→ **默认跳过** + 回报(`error="已存在同标题同领域文档，已跳过"`)。
- **R5 前端**:`KbLibraryPanel.vue` 增「批量导入」入口(editorOrAbove)+ 上传对话框(格式提示/样例模板)
  + 结果面板(逐条成功/失败);完成后刷新列表。
- **R6 边界**:单批条目上限(默认 200)与单条正文 ≤50000 字(对齐 `KbDocSaveDto`);超限明确报错。

## Acceptance Criteria

- [ ] **AC-C1** CSV/JSON/Markdown 三种批量导入可用;逐条成功/失败结果可读(`results[]`)。
- [ ] **AC-C2** 复用 E3 受控 domain/来源/标签/生效期 + 切块 + 嵌入;导入后向量入 `vector_store`。
- [ ] **AC-C3** 非法/重复条目有明确处置且可测;不产生孤儿文档(失败先于写库)。
- [ ] **AC-C4** `mvn test` 全绿 + `npm run build` 通过;后端 API 契约(既有单条 CRUD)零回归。

## Out of Scope

- 审核流 / 版本管理(D4 仅批量导入)。
- 新增数据库迁移(复用现有表)。
- 异步化批量(单批同步,超上限即报错)。
