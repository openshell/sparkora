# 技术设计：生图尺寸比例选项 + 生图交互优化

> 权威需求见 `prd.md`。本文只讲技术边界、契约、取舍与兼容/回滚形状。

## 1. 改动边界

| 层 | 文件 | 改动性质 |
|---|---|---|
| 后端 | `src/main/java/com/sparkora/service/ImageService.java` | **仅注释/文案**：说明白名单 3 值与前端比例档的映射关系。**白名单值不变** |
| 后端 | `src/main/java/com/sparkora/domain/dto/ImageGenDTO.java` | 仅 `size` 字段注释（白名单说明与实际一致） |
| 前端 | `frontend/src/components/AiImageDrawer.vue` | **主战场**：比例选择器、生成中取消、错误分层、参数回显、缩略图真实比例 |
| 前端 | `frontend/src/api/index.js` | 取消能力需 `signal` 透传（axios 支持），仅 `imageApi.generateText` / `generateFromImageUpload` 增加可选 `signal` 形参 |
| DB | —— | **无迁移**（见 prd.md Constraints） |
| 文档 | `docs/spec/image.md` | §6 size 契约、§8 抽屉职责 |

**不动的**：`AiImageClient`（`size` 透传逻辑不变）、`ImageController`（绑定无须改）、
`ImageCard.vue`（已展示 `genSize`）、`ImageService.normalizeSize`（校验逻辑不变）。

## 2. 比例映射的唯一真源

映射是**产品语义**，必须单一真源，否则前后端各写一份必然漂移。放在前端一个导出常量里：

```js
// frontend/src/components/AiImageDrawer.vue（或抽到 utils/）
// 比例档 → 后端白名单内的实际像素。刻意只列这 4 档：
// 16:9 与 4:3 同为 1536x1024（同像素），按决策去掉重复档，避免两个选项给同一张图。
export const GEN_RATIOS = [
  { key: '1:1',  size: '1024x1024', exact: true  },
  { key: '4:3',  size: '1536x1024', exact: false },  // 实际 3:2
  { key: '3:4',  size: '1024x1536', exact: false },  // 实际 2:3
  { key: '9:16', size: '1024x1536', exact: false },  // 实际 2:3
]
```

- `genSize` 仍存**实际像素**（`'1024x1024'` 等），因为后端契约就是像素、且 `gen_size` 落库为像素
  → **不引入「比例」新参数，零后端改动、零迁移**。
- **不能用 `genSize` 反查比例**：`3:4` 与 `9:16` 同为 `1024x1536`，反查必然歧义 →
  用户在 3:4 与 9:16 之间切换时选中态会乱跳。
  决策 = **用单独的 `genRatio` ref 记用户选的档位**，`genSize` 改为 `computed` 派生自它。
  注意现状 `genSize` 是 `ref`（`AiImageDrawer.vue:196`），改派生后两 tab 共用同一份，
  与现状「两 tab 共用同一 `genSize`」的行为一致。
- 存量会话缓存（`imageRefCache` 存的 `cached.size` 是像素）**无需迁移**：由像素反查档位失败时
  回落按 `1024x1024` 处理，不报错、不阻断重生成（`onRegenerate:487` 传的是 `cached.size` 像素，
  该路径天然不依赖比例档）。

## 3. 取消语义（本设计的关键决策点）

`prd.md` R2 要求「真正中断请求」。取舍：

| 方案 | 效果 | 代价 |
|---|---|---|
| A. axios `AbortController` | 前端真正断连 | 后端**不会**感知取消，Spring 请求线程仍在跑，**AI 侧已消耗的算力与图床写入照旧发生**，新图可能仍入图库 |
| B. 仅前端忽略响应 | 改动最小 | 与 A 同样有「静默入库」，且用户以为取消了其实还在跑，更危险 |

**选 A**，因为它是唯一能做到「用户侧立即停止等待」且不谎报的手段。但必须**如实告知后端可能仍在处理**：

> 取消提示文案：「已取消本次请求（已提交给服务端的任务可能仍会完成并入库）」

这条文案是**功能性要求，不是文案偏好**——它与本项目刚归档的 `09-27-wenyan-stale-conn`
是同一类事故（客户端放弃 ≠ 服务端停止），验收时会按此口径检查。

实现要点：
- `imageApi.generateText` / `generateFromImageUpload` 增加末尾可选 `signal` 形参，
  透传给 axios config。**现有调用方不传 = 行为不变**（向后兼容）。
- 取消后**不得**弹「已进图库」成功提示（AC6）：`doGenerate` 内以 `controller.signal.aborted` 判定，
  走取消分支而非成功分支。
- 取消入口**始终可见**（不只 `n>1`），因为单张也可能卡满 300s 超时；
  `n=1` 时用次要样式，避免诱导取消本会很快完成的请求。

## 4. 错误分层

现状是 `ElMessage.error('生成失败：' + (e.response?.data?.msg || e.message || '网络异常或超时'))`。
改为三类判定（前端，无后端改动）：

| 情形 | 判据 | 文案方向 |
|---|---|---|
| 输入校验 | 调用前置校验分支（`!p` / `!refReady`） | 已是 `ElMessage.warning`，保留 |
| 网络/超时 | `e.code === 'ECONNABORTED'` 或 `e.code === 'ETIMEDOUT'`，或 message 含 timeout | 提示「请求超时」+ 建议「减少张数或稍后重试」 |
| 取消 | `axios.isCancel(e)` / `signal.aborted` | 走 §3 取消分支，**不**进错误提示 |
| 网关/模型错误 | `e.response?.data?.msg` 存在 | 直接展示后端中文 msg（后端已是中文可读） |

**注意判据顺序**：取消必须**先于**超时判定。axios 取消请求时抛的 `CanceledError`
不带 `code`，但若把「message 含 timeout」放得太宽，取消可能被误判成超时而显示「请重试」——
那等于诱导用户重复提交，正是本任务要避免的行为。

**不新增自造错误码**：后端 `R.fail` 的 `msg` 已是中文可读（符合 `.trellis/spec/backend/error-handling.md`），
前端只做「传输层原因」的分流，不改写业务错误文案。

## 5. 缩略图真实比例

改 `aspect-ratio: 4/3; object-fit: cover` → 按真实比例。两种实现：

- **A. 纯 CSS**：按当前生成比例给候选区设 `aspect-ratio: <ratio>`。
  优点：零 JS、零额外字段。缺点：**图库选择弹窗与参考图区是混合比例的存量图**，单一 CSS 值无法逐图适配。
- **B. 按 `width`/`height` 内联**：`style="aspect-ratio: {{ img.width }} / {{ img.height }}"`。
  优点：逐图正确，存量图也对。缺点：`width`/`height` **可能为空**（`fillSize` 探测失败时留空，PRD 背景已述），
  需回落 `4/3`。

**选 B + 回落**：因为 AC10 明确要求「图库选择弹窗中的历史竖图同样按真实比例显示」——存量图的 `width`/`height`
是入库时探测填的，可靠；而 CSS 方案对存量图无能为力。

- 容器用 `object-fit: contain` + 背景 `var(--paper)`，避免比例不同时图片变形或出现空白缝隙。
- **网格稳定性**：候选网格是 `repeat(2, 1fr)`，竖图（2:3）比横图（3:2）高得多，按行对齐会产生大空隙。
  决策：`.cand-grid` 用 `align-items: start` + 单元格内限高 `max-height`，
  移动端维持单列（现有 `@media max-width:768px`）。**不引 masonry**。
  精确视觉效果实现后按截图核对；若仍不可接受，作为 UI 缺陷单独记录而非在本任务扩依赖。

## 6. 权衡记录

- **为什么后端白名单不扩**：扩了要撞 axonhub 未验证的支持矩阵（PRD 背景），
  且 `3:4→768x1024` 这类非官方值一旦被网关 4xx，`AiImageClient` 的模型轮询会把所有模型试一遍
  （`:25` 注释、`:119-123`），报错变得难以归因。方案 A 零风险落地。
- **为什么比例映射放前端**：它是 UI 呈现语义；后端只认像素，映射不参与任何业务判断。
  放后端反而要在 DTO/Service/文档三处表达同一个 4 元素数组。
- **为什么 `genSize` 保持像素**：保持与 `gen_size` 落库、`imageRefCache`、`regenerate`
  （`ImageService.java:294` 用源图 `gen_size` 复现）三处既有链路**零改动**，无兼容负担。

## 7. 兼容与回滚

- **向后兼容**：`imageApi.*` 新增的 `signal` 为可选末位参数；`genSize` 仍是像素字符串。
  存量 `imageRefCache` 条目存的是像素，重生成走像素路径，不依赖比例档。
- **回滚形状**：改动集中在 1 个 Vue 组件 + 1 个 api 文件的形参扩展 + 若干注释，
  **可整体 revert 单个 commit**，无数据迁移、无 schema 变更、无配置项 ⇒ 回滚无副作用。
