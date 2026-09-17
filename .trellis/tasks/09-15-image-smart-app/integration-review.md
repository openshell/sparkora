# 集成核对报告（父任务收尾，2026-09-17）

全部四个子任务已实现、独立核对、提交并归档（A/B/C/D 均 `archive/2026-09/`）。本报告为父任务 Integration Review 输出。

## 1. 跨子任务验收标准逐条核对

| # | 跨子验收项 | 判定 | 独立证据 |
|---|---|---|---|
| 1 | 四子任务各自 AC 全绿，依赖顺序正确（B 在 A 后、C 在 B 后） | ✅ | A `mvn test` 111→（A 时点）+20；B 137 全绿；C 169 全绿；D 205 全绿。归档顺序 A→B→C→D 与依赖一致 |
| 2 | 全链路集成：新闻入库→自动标签+`source_ref`→图库主题+年份 AND 筛选→语义检索命中同批→配图建议/问答配图 | ✅ | 见下表全链路真机证据（本次核对实测） |
| 3 | 存量可回溯且幂等：157 张 byd-news 补标 + 建向量 | ✅ | `byd_news=157 / ref_filled=157 / embeddings=166 / news_covers=157`；回溯重跑日志「处理=157 跳过=0 失败=0」，重跑零新增 |
| 4 | 图片向量与三域同向量空间，无维度/模型混用 | ✅ | 四域全部 `vector(1024)`：car=380 / kb=3 / news=1339 / image=166；图片域 HNSW `vector_cosine_ops`（`idx_image_emb_vec_hnsw`） |
| 5 | 零回归：不传标签旧调用、BYD 车型图管线、三域检索行为不变 | ✅ | `CarDocEmbeddingMapper` 无 diff；四域行数与基线一致；`VersionService`/`PreviewService` 未改；既有图库接口结构不变 |
| 6 | 每子任务 `mvn -q -DskipTests compile` + `npm run build` 通过；spec 同步 | ✅ | 各子任务均记录 EXIT=0；`docs/s0-spec.md` §1/§7/§10/§11/问答章节均已同步 |
| 7 | 无 AI 生成图/上传图被新闻分类误标（分类仅作用 `source=byd-news`） | ✅ | `mislabeled=0`：`主题/%` 标签关联的图全部 `source='byd-news'` |

## 2. 全链路真机证据（本次核对实测）

| 环节 | 实测结果 |
|---|---|
| 多标签 AND 筛选 | `GET /images?tag=主题/销量&tag=年份/2026` → `total=9`，命中图 tags 同时含两标签 |
| 语义检索 | `POST /images/search {"query":"销量海报"}` → top3 score 0.4857/0.4717/0.4697 降序，均含 `主题/销量` |
| 配图建议（子C） | `POST /projects/45/illustration-suggestions` → 2 组锚点，各 3 候选；零副作用（未改正文/body_image_ids） |
| 来源追溯 | `GET /images/38/source` → `sourceRef=/page/byd-cn/news-2026/detail633`，新闻标题「比亚迪第10000座闪充站落成…」，日期 2026-08-28 |
| 新闻页封面接图库 | `GET /news` → `coverImageUrl` 为七牛图床 URL，`themes=['销量','出海','里程碑']` |
| 问答配图（子D） | 临时会话问闪充合作 → `ragStatus=OK`，NEWS citations `docId=[1347,1698,1692,1348]`，`imageRefs=[(38,闪充站落成…),(84,小桔充电/新电途共建兆瓦闪充…)]`，链路与 DB 反查一致 |

## 3. spec 跨章节一致性

- §1 接口清单：`/images/{id}/source`、`/images/search`、`/images/embeddings/rebuild`、`/projects/{id}/illustration-suggestions[/dismiss]` 均已登记。
- §7 新闻：`cover_image_id` 与封面接图库已记录。
- §10 图库：`source_ref`、主题词表、命名空间、多标签 AND、来源接口、语义检索、配图建议、已知债务（`body_image_ids` 与渲染脱节）齐备。
- 问答章节：`image_refs` 字段、两条配图来源路径、展示契约、`docId` 说明、错误矩阵齐备。
- §7b 配置表：`AI_IMAGE_MIN_SCORE`、`AI_ILLUSTRATION_*` 已登记。

## 4. .trellis/spec 跨层约定沉淀

| spec | 沉淀内容 |
|---|---|
| `backend/ai-rag-guidelines.md` | 图片向量域（第四域）场景契约；问答答案配图（只读派生展示）场景契约；`record` 加字段保留旧构造器 + 重排处全字段透传 |
| `backend/database-guidelines.md` | 启动补齐 `@Order` 依赖排序 + 网络任务异步化；向量写入 `REQUIRES_NEW` 事务隔离；多对多关系表；受控词表分类；派生数据不落库/只存用户决策；multipart 多值；**图片域同空间但独立检索（不并入 unified）** |
| `backend/quality-guidelines.md` | 验证禁改生产数据（09-15 事故）；不可逆副作用隔离与可回滚 |
| `frontend/index.md` | 建议→批准双写；按标题定位插入退回光标；TEXT 列存 JSON 的前端双态兼容；预览用原图 URL |

## 5. 差异项与已知债务（不阻断，已记录）

| 项 | 说明 | 处置 |
|---|---|---|
| `body_image_ids` 与渲染脱节 | 实际渲染只认 `content_md` 的 markdown；手动插图不登记该字段；`modifyBodyImage` 因 MyBatis-Plus `NOT_NULL` 无法清空 | 已作为**已知债务**写入 §10；子C 采用时双写规避；根因修复需另立任务 |
| 新闻存量封面 10 条缺失 | 09-13 起文件名含空格等导致未入库；走官网 `imageUrl` 回退 | 记录不改（历史缺口，回退链路已验证不报错） |
| 标签变更不触发图片重嵌 | `PUT /{id}/tags`/批量打标后 `source_text` 漂移 | 记为已知限制；`rebuild` 接口可修正 |
| 忽略建议不可撤销（无 UI） | 子C Non-goal | 记录不改（删表记录可恢复） |
| version 25 正文不可恢复 | 09-15 子C 实现期数据事故（已单独报告） | 已向用户报告；可用 `brief(id=41)` 重新生成 |

## 6. 提交与归档

各子任务按 Trellis 3.4 分批提交（工作提交 → spec 沉淀 → task 工件 → archive），均未 push（未要求）。

## 7. 结论

父任务 4/4 子任务全部交付并通过跨子验收；无阻断遗留。父任务可归档。

**核对期数据完整性声明**：本次集成核对仅新建 1 个临时问答会话（id=12）+ 2 条消息（id=35/36），核对后已按 id 精确物理删除；最终基线 `sessions=1 / messages=2 / images=166 / news=167 / projects=44 / versions=34 / dismiss=0` 与核对前完全一致，既有消息 11/12 的 `image_refs` 仍为 NULL、内容未变。
