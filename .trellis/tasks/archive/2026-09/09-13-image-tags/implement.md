# 执行计划：图库图片标签功能与交互优化

## 实施顺序

### M1 后端标签域（schema + entity/mapper/service）

- [ ] 1. `schema.sql` 追加 S-tags 段：`sparkora_image_tag` 建表（含 UNIQUE(image_id, tag_name)、`idx_image_tag_name`），幂等写法
- [ ] 2. 新建 `ImageTagEntity`（`sparkora_image_tag`，无 @TableLogic）+ `ImageTagMapper`
- [ ] 3. 新建 `ImageTagService`：
  - `normalize(List<String>)`：trim / 去空 / 去重 / 长度 1~50 校验（>50 抛 IllegalArgumentException）
  - `saveTags(imageId, tags, operator)`：批量 insert，DuplicateKeyException 吞（幂等）
  - `mergeTags(imageId, tags, operator)`：读已有 ∪ 传入，只插差集
  - `copyTags(fromId, toId, operator)`：regenerate 继承
  - `replaceTags(imageId, tags, operator)`：全量覆盖（先 delete 后 insert，事务内）
  - `batchApply(ids, tags, action, operator)`：add=逐图 merge / remove=逐图删
  - `listAll()`：`GROUP BY tag_name` 出 `{name, count}`，count 降序
  - `imageIdsByTag(name)`：tag → id 列表
  - `fillTags(List<ImageAssetEntity>)`：页内 id 批查回填，避免 N+1
  - `deleteByImageId(imageId)`：删图联动清理
- [ ] 4. `ImageAssetEntity` 加 `@TableField(exist=false) List<String> tags` 非持久化字段

### M2 后端接口接入

- [ ] 5. `ImageGenDTO` 加 `List<String> tags`（可空）
- [ ] 6. `ImageController`：
  - `GET /api/images` 加 `tag` 参数；`GET /api/images/tags`（三角色）
  - `PUT /api/images/{id}/tags`（ADMIN/EDITOR，body `{tags:[]}`）
  - `POST /api/images/tags/batch`（ADMIN/EDITOR，body `{ids, tags, action}`）
  - upload 加 `tags` 多值参数收齐（`getParameterValues` + 逗号拆分）
  - generateText/generateFromImage 透传 body.tags
- [ ] 7. `ImageService`：
  - `list(...)` 加 tag 参数两段查询；rows 回填 tags（fillTags）
  - `upload`/`generateText2Image`/`generateImage2Image` 加 tags 形参：persistOrReuse 后新图 saveTags / 命中 mergeTags
  - `regenerate`：copyTags(源图, 新图)
  - `projectImages`：引用图回填 tags
  - `delete`：先 `deleteByImageId` 再删图行
- [ ] 8. BYD 图片分类接入（R2b）：
  - `CarModelService.persistIntroImages`：preset 传 tags=`[车型-<车型名>]`（persistOrReuse 自动落标）
  - `NewsService.upsertOne`：下载 `imageUrl` 走 `persistOrReuse`（source=byd-news、tags=`[新闻]`），失败仅告警
  - `ImageService.SOURCES` 白名单加 `byd-news`
  - 存量追溯：启动任务（幂等）遍历 `car_model.intro_images` asset id 列表逐车型 mergeTags(`车型-<名>`)
- [ ] 9. 编译验证：`mvn -q -DskipTests compile`

### M3 前端（api 封装 + ImageLibrary.vue）

- [ ] 10. `src/api/index.js` imageApi：`listTags` / `updateTags` / `batchTags`；upload/generateText/generateFromImage 透传 tags
- [ ] 11. `ImageLibrary.vue`：
  - 工具条「上传标签」预选控件（multiple allow-create，共 upload/AI 生图用）
  - 标签筛选下拉 + tag chip；`load()` 带 tag 参数
  - `SOURCE_LABELS` 加 `byd-news: '比亚迪新闻'` + 色点配色
  - 卡片 hover 层/移动端常显区展示标签；点标签触发筛选
  - hover 操作区 + 移动端 ··· 菜单加「编辑标签」→ 对话框全量覆盖（updateTags）
  - bulk-bar 加「打标签」按钮 → 对话框（标签多选 + add/remove 单选）→ batchTags
  - AI 抽屉双 tab prompt 拆独立 ref（修共用污染缺陷）
  - 参考图弹窗加页内搜索/翻页（修只看第一页缺陷）
- [ ] 12. 构建验证：`npm run build`

### M4 规格同步 + 联调验证

- [ ] 13. `docs/s0-spec.md` §10：数据模型加 `sparkora_image_tag` 表；接口表加 4 个新接口 + 现有接口 tags 字段 + source 白名单 `byd-news`；页面职责段补标签能力描述；§7 新闻表注释补「封面图转存图库」
- [ ] 14. 联调验证（`./dev.sh start` 或已有环境）：上传带标签 → 列表见标签；按标签筛选；单图改标；批量补打/移除；AI 生图带标签；重生成继承；BYD 车型同步图带 `车型-<名>` 标签；新闻同步封面入库带「新闻」标签、来源筛 byd-news 可筛；VIEWER 可筛选不可写
- [ ] 15. 质量门：最终 `mvn -q -DskipTests compile` + `npm run build` + trellis-check 全量核对

## 验证命令

```bash
mvn -q -DskipTests compile        # 后端（仓库根）
npm run build                      # 前端（frontend/）
./dev.sh start                     # 联调环境（需要时）
./dev.sh logs backend -f           # 看启动与接口日志
```

## 风险点与回滚

- 改动集中：`schema.sql`、`ImageService`（入库管线签名）、`ImageController`、`ImageLibrary.vue`、`api/index.js`、`CarModelService`（传 tags）、`NewsService`（封面下载入库）、spec §10/§7。
- `persistOrReuse` 是四来源共用管线：只加钩子不改变去重/转存语义；改后必回归 BYD 路径编译。
- 新闻同步新增下载封面步骤：失败仅告警不阻断新闻入库（容错先例同车型图）；下载超时 30s 同 `TRANSFER_TIMEOUT` 量级，不影响同步任务轮询语义。
- 存量追溯是启动任务：merge 只插差集，重复启动幂等；若车型量大（56）逐车型 merge 是轻量本地操作。
- schema 启动自动执行：新表幂等，老库无感；回滚 DROP TABLE IF EXISTS 即可。
- `ImageService` 签名变更波及 `CarModelService.persistIntroImages`：保持向后兼容重载（tags 可选），避免波及调用方改动。

## Review gate

- task.py start 前需用户确认本计划与 prd/design。