# Implement — C KB 批量导入

> 配套 `prd.md` / `design.md`。依赖:无。回退点:R-C = revert。

## 0. 顺序

后端解析/服务 → 后端端点 + 测试 → 前端 API + UI → 验证 → 自查。

## 1. 后端:解析器(纯静态,先行)

- [ ] `com.sparkora.kb.KbImportRow`(或 `design` 定的行模型):字段 title/domain/content/source/tags/effectiveFrom/effectiveTo。
- [ ] `com.sparkora.kb.KbBatchParser`:
  - [ ] `parseCsv(String)`:表头认列(大小写/trim);RFC4180 子集(引号/`""`/内嵌逗号换行);缺 title/content 列 → `IllegalArgumentException`。
  - [ ] `parseJson(String, ObjectMapper)`:对象数组;`tags` 兼容数组/字符串;非数组 → 抛。
  - [ ] `parseMarkdown(String, String fallbackTitle)`:H1 分段;无 H1 → 单篇。
  - [ ] `detectFormat(String filename, String content)`:后缀优先 → 内容嗅探(`[`→json,首个非空行含 `#`→md,含逗号表头→csv)。
  - [ ] `parseDate(String)`:ISO,失败→该条失败(不抛整批)。

## 2. 后端:批量服务

- [ ] `com.sparkora.kb.service.KbBatchImportService`:
  - [ ] 注入 `KbDocService` + `KbDocMapper` + `ObjectMapper`。
  - [ ] `importBatch(fileName, format, bytesOrString, operator)` → `Map<String,Object>`:
    - 判定格式 → 解析(整体失败 → 抛 `IllegalArgumentException` 让控制器 400)。
    - 上限校验(默认 200 行)超限 → 抛。
    - 预取库内 `(title,domain)` 集合;批内去重。
    - 逐条 `try { KbDocService.create(...) } catch (e) { 记为失败 }`;重复→跳过回报。
    - 汇总 `{total, success, failed, results[]}`。

## 3. 后端:端点

- [ ] `KbDocController` 增 `POST /docs/batch`:`@RequestParam("file") MultipartFile`, `@RequestParam(required=false) String format`;ADMIN/EDITOR;`R<Map>`;`IllegalArgumentException`→400 其余→500。
- [ ] 复用 `SecurityUtil.current()` 取 operator(同单条 create)。

## 4. 后端:测试

- [ ] `KbBatchParserTest`:CSV(标准/引号/内嵌逗号/缺列)、JSON(数组/字符串 tags/非数组抛)、MD(H1 分段/无 H1)、format 探测。
- [ ] `KbBatchImportServiceTest`(mock `KbDocService`+`KbDocMapper`):逐条成功、单条失败不阻断、库内重复跳过、批内重复跳过、超限报错、整体解析失败抛出。
- [ ] `KbDocControllerContractTest`(若无则新建/并入):batch 200 结构 + viewer 403 + 缺 file 400。

## 5. 前端

- [ ] `frontend/src/api/index.js` `kbApi` 增 `batchImport(file, format)`:FormData;可选 `format`;`timeout` 放宽(逐条 embedding,取 120000 或 300000)。
- [ ] `KbLibraryPanel.vue`:「批量导入」按钮 + 对话框(文件选择/格式提示/样例说明) + 结果面板(`results[]` 表格) + 完成后 `load()`。

## 6. 验证

- [ ] `mvn -q -DskipTests -Dmaven.repo.local=/tmp/m2repo compile`
- [ ] `mvn -Dmaven.repo.local=/tmp/m2repo test`(全绿;基线 750)
- [ ] `cd frontend && npm run build`
- [ ] 样例端到端(启动后端):三种格式各导入一次,核对 `results[]` 与列表新增;重复导入 → 跳过。

## 7. 风险 / 回退

| 点 | 风险 | 回退 |
|---|---|---|
| R-C | 批量解析/落库不一致、CSV 边界 | revert 提交 |

## 8. task start 前检查

- [ ] `prd.md`/`design.md`/`implement.md` 完成且一致。
- [ ] `implement.jsonl`/`check.jsonl` 含真实 spec 条目。
- [ ] 用户对规划摘要给出明确批准。
