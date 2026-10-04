# Design — C KB 批量导入

> 配套 `prd.md`。父技术设计见 `../10-03-leftover-optimization/design.md` §3.3。

## 1. 目标与边界

```
上传文件(CSV/JSON/MD) ──► KbBatchImportService
                              │  1) 解析为 List<KbImportRow>  (格式差异封装)
                              │  2) 逐条:去重检查 → KbDocService.create(...)
                              │  3) 汇总 {total,success,failed,results[]}
                              ▼
                        KbDocService.create(既有)
                        normalize → 切块 → embedding → vector_store
```

- **只新增** service + controller 端点 + DTO;`KbDocService`(单条链路)不动。
- 无数据库迁移;无新配置键。
- 同步执行(单批 ≤200 条);失败逐条隔离,不做整批回滚。

## 2. 契约

### 2.1 端点

```
POST /api/kb/docs/batch   (multipart/form-data, ADMIN/EDITOR)
  part: file    (必填, 文本文件)
  part: format  (可选: csv|json|markdown; 缺省按文件名后缀→内容嗅探)
→ R<{ total:int, success:int, failed:int,
      results:[ {index:int, title:string, success:boolean, error:string|null} ] }>
```

- 文件解析失败(整体非法,如 CSV 无表头/JSON 非数组/编码错)→ 400 + 中文 `msg`,**不落任何文档**。
- 单条非法 → 进入 `results` 的失败项,其余继续。

### 2.2 行模型 `KbImportRow`

| 字段 | 类型 | 说明 |
|---|---|---|
| title | String | 必填,≤200 |
| domain | String | 受控词表,空→通用,非法→该条失败 |
| content | String | 必填,≤50000 |
| source | String | 可空,≤200 |
| tags | List\<String\> | 可空;逐项 ≤50,去重保序 |
| effectiveFrom / effectiveTo | LocalDate | 可空(ISO `yyyy-MM-dd`) |

字段名与 `KbDocSaveDto` 对齐;`tags` 在 CSV/MD 为分隔字符串(`;`),在 JSON 允许数组或字符串。

### 2.3 三种格式解析

- **CSV**:自实现最小 RFC4180 解析(引号包裹、`""` 转义、内嵌逗号/换行)。表头认列名(大小写不敏感、trim)。缺 `title` 或 `content` 列 → 整体 400。
- **JSON**:Jackson 反序列化为 `List<Map<String,Object>>`(或 DTO 列表);非数组 → 400。字段名对齐,`tags` 兼容 `List`/字符串。
- **Markdown**:按行扫描 `^#\s+`(一级标题)切分;每段 title=H1 文本,content=段内其余;无 H1 → 整文件一篇,title=文件名去后缀。

### 2.4 解析器落位

- 新增 `com.sparkora.kb.KbBatchParser`(纯静态/可单测):`parseCsv(String)`, `parseJson(String, ObjectMapper)`, `parseMarkdown(String, String fallbackTitle)` → `List<KbImportRow>`。
- 格式判定 `detectFormat(filename, content)` 纯静态。
- 新增 `com.sparkora.kb.service.KbBatchImportService`:`importBatch(List<KbImportRow>, String operator)` → 结果 Map;内部逐条 catch,委托 `KbDocService.create`。
- DTO 返回用 `LinkedHashMap`(与 KB 现有 VO 同风格),不新增响应实体。

## 3. 去重策略

- **批内去重**:同一批解析结果中 `title+domain` 重复 → 仅首条处理,后续标记跳过。
- **库内去重**:导入前按 `(title, domain)` 预取现有文档(一次 `selectList` 建 `Set`),命中 → 跳过 + 回报。
- 默认**跳过**(非覆盖);符合"不产生孤儿/不误伤既有"。覆盖策略 out of scope。

## 4. 失败与事务

- **整体解析失败** → 400,**先于任何写库**(quality-guidelines「多步写入失败顺序」)。
- **单条失败**:`KbDocService.create` 内部已保证失败先于落库(校验/normalize 在 INSERT 前);若仍抛,该条 `success=false`,已落库部分不受影响(逐条独立,`KbDocService` 每次 create 走既有事务边界)。
- **计数**:`total`=解析出的行数;`success`=`KbDocService.create` 返回成功数;`failed`=`total-success`。

## 5. 前端

- `KbLibraryPanel.vue`:toolbar 增「批量导入」按钮(editorOrAbove)。
- 对话框:文件选择(`el-upload` `:auto-upload=false` 或原生 input)+ 格式说明 + 「下载样例」提示(可选,文案内联)。
- 提交:`kbApi.batchImport(file, format)`(FormData `file` + 可选 `format`)。
- 结果面板:成功/失败计数 + `results[]` 表格(序号/标题/状态/错误);失败项红色。
- 完成后 `load()` 刷新。

## 6. 兼容 / 回退

- 纯新增端点与 UI;既有单条 CRUD/`/domains`/`/docs` 契约不变。
- 回退 = revert 提交(无迁移、无配置、无持久化结构变化)。

## 7. 权衡

- **同步 vs 异步**:单批 ≤200,同步返回结果最直观;超限报错引导分批。异步留待未来。
- **自实现 CSV vs 引依赖**:项目无 CSV 依赖(不新增重型库);RFC4180 子集自实现 + 单测覆盖边界。
- **重复跳过 vs 覆盖**:跳过零风险、可预测;覆盖易误伤,out of scope。
