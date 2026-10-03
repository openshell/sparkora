# C KB 批量导入

## Goal

支持批量新建 KB 知识文档，解决单条录入的低效（E3 已具备数据模型，仅缺导入）。

## Depends On

无（可与 A/B 并行）。复用 E3 的 `KbDomain`/tags/生效期 + 切块 + 嵌入链路。

## Requirements

- 父 R4、D4。
- `POST /api/kb/docs/batch`（ADMIN/EDITOR）：支持 CSV / JSON / Markdown 三种输入。
- CSV 列：title/domain/content/source/tags/effectiveFrom/effectiveTo；JSON：对象数组；
  Markdown：frontmatter 或按标题分段。
- 逐条复用 `KbDomain.normalize` + tags normalize + 切块 + 嵌入；返回
  `[{index,title,success,error}]`；**单条失败不阻断其余**。
- 非法/重复处置：非法 domain → 该条失败回报；重复（同 title+domain）默认跳过 + 回报。
- 前端 `KbLibrary.vue` 增「批量导入」入口 + 结果面板。

## Acceptance Criteria

- [ ] AC-C1 CSV/JSON/Markdown 批量导入可用；逐条成功/失败结果可读。
- [ ] AC-C2 复用 E3 受控 domain/来源/标签/生效期 + 切块 + 嵌入；导入后向量入 `vector_store`。
- [ ] AC-C3 非法/重复条目有明确处置（可测），不产生孤儿文档。
- [ ] AC-C4 `mvn test` 全绿 + `npm run build` 通过。

## Out of Scope

- 审核流 / 版本管理（D4 仅批量导入）。
