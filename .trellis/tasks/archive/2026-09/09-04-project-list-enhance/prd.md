# 项目列表完善:搜索筛选 / 排序 / 批量操作

## Goal

工作台项目列表(`frontend/src/views/ProjectList.vue`)当前只有分页,多项目场景下无法定位与管理。本轮补齐三块:主题搜索 + 状态筛选、排序切换、ADMIN 批量删除。后端大部分能力已存在,前端未暴露是「简陋」的主因。

## Confirmed Facts(代码现状,已核实)

- 后端 `GET /api/projects`(`ArticleProjectController.java:57-73`)已支持 `page/size/topic(like 模糊)/status(eq 精确)`,固定 `orderByDesc("updated_at")`;spec §3.3 契约请求参数已含 `page,size,topic,status`——**搜索与筛选后端零改动**。
- 前端 `ProjectList.vue`(138 行)只传 `page/size`:无主题搜索框、无状态下拉、无排序控件;桌面 `el-table` + 移动端卡片双布局;已有加载失败/空态分支与分页器。
- `DELETE /api/projects/{ids}` 已支持逗号分隔多 id 批量逻辑删除(`@PreAuthorize hasRole('ADMIN')`);前端 `projectApi.remove(ids)` 已存在——**批量删除后端零改动**;当前列表页(含操作列)没有任何删除入口。
- 状态机 6 态(DRAFT / GENERATING_BRIEF / READY / GENERATING_VERSIONS / VERSIONS_READY / PUBLISHED_DRAFT),`frontend/src/constants/project.js` 为展示文案/标签色唯一事实源,筛选下拉直接复用。
- MyBatis-Plus QueryWrapper 排序为字符串拼接列名,新增排序参数必须**白名单校验**防 SQL 注入。

## Requirements

- **R1 搜索与筛选**:工具栏新增主题关键字搜索框(回车/防抖触发)+ 状态下拉(「全部」+ 6 态);任一筛选变化重置回第 1 页;全部走服务端参数(`topic`/`status`),前端不做客户端过滤。
- **R2 排序切换**:后端 list 新增 `orderBy`(`updatedAt`/`createdAt`,默认 `updatedAt`)与 `orderDir`(`desc`/`asc`,默认 `desc`)两个参数,白名单映射到列名;前端工具栏提供排序切换;spec §3.3 请求参数契约同步。
- **R3 批量删除**:桌面表格加多选列 + 「批量删除」按钮(仅 ADMIN 可见);点击后 `ElMessageBox.confirm` 确认(提示所选数量);成功后刷新列表;移动端卡片布局 MVP 不提供批量操作(无多选载体)。
- **R4 契约同步**:`docs/s0-spec.md` §3.3 GET /api/projects 行更新请求参数(`orderBy`/`orderDir`);权限矩阵与响应结构不变。

## Acceptance Criteria

- [ ] AC1:主题关键字搜索与状态筛选生效(服务端过滤),触发筛选后回到第 1 页;清空条件恢复全量。
- [ ] AC2:排序支持 updated_at/created_at × 升/降切换,默认 updatedAt desc 与现状一致;非法 `orderBy`/`orderDir` 值安全回退默认,不报错不注入。
- [ ] AC3:ADMIN 桌面端可多选并批量删除,有确认弹层(含数量),删除后列表刷新、total 减少;EDITOR/VIEWER 看不到多选列与删除按钮(仅后端拦截不够,前端也要隐藏)。
- [ ] AC4:搜索/筛选/排序与分页组合正常——翻页保留全部条件,条件变化不丢页码语义(重置为 1)。
- [ ] AC5:移动端卡片列表正常展示,无多选列残留;`mvn -q -DskipTests compile` 与 `npm run build` 通过;spec §3.3 与实现一致。

## Key Decisions(用户已确认)

- 方向组合:搜索与筛选 + 排序切换 + 批量操作三项都做(dec-1c43347d8f53cdc6)。
- 创建 Trellis 任务并规划(dec-1c43347d8f53cdc6)。

## Technical Notes

- 轻量任务,PRD-only;无 schema 变更、无新接口、无状态机变更。
- 排序白名单:`orderBy` ∈ {updatedAt→updated_at, createdAt→created_at},`orderDir` ∈ {asc, desc};不匹配即用默认,不透传原始字符串到 QueryWrapper。
- 前端筛选状态用 reactive 对象集中管理,`watch` 变化 → 重置 page=1 → `load()`;分页器/表格保留现有结构,多选列仅桌面渲染。

## Out of Scope

- 进度可视化(每行展示简报/版本/发布步骤指示)——本轮未选,后端需补聚合字段,另立任务。
- keywords 字段搜索、创建人(created_by)筛选。
- 移动端批量删除交互。
- 回收站/逻辑删除恢复 UI。

## Open Questions

- 无(剩余交互细节均有常规默认:搜索回车+防抖、删除二次确认、6 态全列下拉)。