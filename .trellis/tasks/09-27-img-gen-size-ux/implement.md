# 执行计划：生图尺寸比例选项 + 生图交互优化

> 权威需求 `prd.md`，技术设计 `design.md`。按序执行，每步末尾附验证方式。
> 全部改动集中在 `frontend/src/components/AiImageDrawer.vue` + `api/index.js` 形参扩展 + 注释/文档。

> **实现时的范围微调（已按 design §2 授权执行）**：比例映射常量**必须**能被 import 成单一真源，
> 而 `<script setup>` 不允许 `export`（编译期报「cannot contain ES module exports」），
> 故按 design §2「或抽到 utils/」新增 `frontend/src/utils/imageGenRatio.js`（纯常量 + 两个纯函数，无新依赖）。

## 0. 前置确认

- [x] 复核 `GEN_RATIOS` 四档映射与后端白名单一致性：`ImageService.java:59` 仍为
      `{1024x1024, 1536x1024, 1024x1536}`，`normalizeSize`（`:791-796`）未改。
      → 复核结果：三值未变、校验逻辑未动；前端 4 档映射的 3 个像素值全部落在白名单内
      （`grep ALLOWED_SIZES` + node 跑映射表逐档打印核对）。
- [x] 确认 `AiImageDrawer.vue` 的比例档**无 16:9**（与 4:3 同像素，决策去掉）。
      → 原实现只有 3 个像素下拉（方/横/竖），本就没有 16:9；新增映射表经断言确认不含该档。

## 1. 比例选择器（R1 + R2 的可视化部分）

- [x] 声明 `GEN_RATIOS` 常量（design §2）：4 档，`{key, size, exact}`。
      → 落在 `frontend/src/utils/imageGenRatio.js`（单一真源，附「勿回改」理由注释）。
- [x] 模板：把文生图（`:12-16`）与图生图（`:67-71`）**两处** `el-select` 换成同一个比例选择组件
      （抽 `AiImageDrawer.vue` 内子组件或 slot 复用，两 tab 共用 `genRatio`/`genSize`）。
      → 实现方式：**把「比例 + 张数 + 生成/取消」整块上移到 `</el-tabs>` 之外**作为共用区，
        两个 tab 不再各写一份（比抽子组件更少重复，且天然共用同一 `genRatio`/`genSize`/`AbortController`）。
      - 每档展示比例名 + 实际像素（如 `9:16 · 实际 1024×1536`）；`1:1` 档不显示「近似」类字样。
        → `exact` 档只显示 `1024×1024`，其余三档显示 `实际 1536×1024` / `实际 1024×1536`。
      - 图形化轮廓（方/横/竖）用纯 CSS 实现，**不引依赖**。
        → `.ratio-shape` 用 `aspect-ratio`（由 `cssRatioOf(key)` 给 `9 / 16` 这样的值）+ 定高 30px；
        轮廓画的是**用户所选比例**（3:4 与 9:16 剪影不同），实际像素由文字行如实披露。
- [x] `genSize` 改为**由 `genRatio` 派生**（`computed`），消除 3:4/9:16 同像素导致的选中态乱跳。
- [x] 移动端：比例项触控目标 ≥44px（现有 `@media max-width:768px` 段内补）。
      → `.ratio-item{min-height:44px}` 全端生效；移动端改 2×2 网格（4 列在窄屏会挤掉「实际 1024×1536」文字）。

## 2. 生成中取消（R2）

- [x] `frontend/src/api/index.js`：`imageApi.generateText` 与 `generateFromImageUpload` 增加末尾可选
      `signal`，透传 axios config。**确认现有调用方不传时行为不变。**
      → 唯一其他调用方 `composables/useImageLibraryOps.js:102`（重生成）传 7 个实参，不受影响。
- [x] `doGenerate` 引入 `AbortController`；生成中渲染「取消」入口，**含 `n=1`**（`n=1` 用次要样式）。
      → `doGenerate` 签名由「已发起的 promise」改为「工厂 `reqFn(signal)`」：signal 必须在**发请求之前**拿到。
      → 取消按钮 `v-if="generating"`，`:type="genCount > 1 ? 'warning' : 'default'"`。
- [x] 取消分支：**不弹**「已进图库」成功提示（AC6），改为取消文案并**如实告知**
      「已提交给服务端的任务可能仍会完成并入库」（design §3）。
      → 双重保障：① 成功分支开头 `if (ctl.signal.aborted) return`（即使拿到响应也不当成功）；
        ② catch 判据 `isCanceled(e, ctl)` 命中后只弹 `CANCEL_TIP`。
- [x] `finally` 复位 `generating`，避免取消后按钮永久 loading（并把 `abortCtl` 置 null）。
      → 另补：`onBeforeUnmount` 里 `abortCtl?.abort()`，避免卸载后回调打在已销毁组件上。

## 3. 错误分层（R2）

- [x] `doGenerate` catch 分流（design §4）：**取消判据必须先于超时判据**。
      → `reportGenError(e, canceled)` 顺序固定为 取消 → 后端 `R.fail` 中文 msg → 超时（含建议）→ 传输层断网；
      取消以自持 `signal.aborted` 为主判据（另兜 `ERR_CANCELED` / `CanceledError`），不 import axios。
      → 不新增自造错误码、不改写后端中文 msg。

## 4. 参数回显（R2）

- [x] 候选区顶部展示「本次按 4:3（实际 1536×1024）· 2 张生成」，取本次请求的**提交时快照**。
      → `lastRun` ref 在 `doGenerate` 入口写入 `{kind, ratioKey, size, n}`；两个 handler 内的
        `ratioKey/size/n` 先取局部 const 再进请求与 ctx（提交时快照，不读 await 后的值）。
      → 重生成路径另设 `kind:'regenerate'`，回显「本次重生成 N 张（像素）」（无档位语义，不硬套比例）。
      → 顺带：生成中骨架提示原本写死「约 10~30 秒」，与后端 300s 上限/实际耗时无关且属编造数字，
        改为「正在生成 N 张，请稍候…（可随时取消本次请求）」（PRD R2「不加预估耗时」+ 前端 spec 等待提示条款）。

## 5. 缩略图真实比例（R3）

- [x] `.cand-thumb` / `.ref-thumb` / `.ref-cell-thumb` 改按真实比例：内联 `aspect-ratio: w / h`，
      `width/height` 为空时回落 `4/3`（design §5）。
      → `ratioStyleOf(img)`：`Number.isFinite && > 0` 才用真实值，否则 `{aspectRatio:'4/3'}`
        （node 实测：空/0/null 三种缺失态均回落，无 `0/0`、无 `NaN`）。
- [x] 容器 `object-fit` 语义 + 背景色复核，避免变形/白缝。
      → `el-image` 由 `fit="cover"` 改 `fit="contain"`（候选区 + 图库弹窗），`.ref-thumb` 改 `object-fit: contain`；
        纸色底沿用 `.cand-thumb{background:var(--paper)}`，另给 `.ref-thumb` 补纸色底。
- [x] `.cand-grid` 横竖混排稳定性：`align-items: start` + 单元格限高；移动端维持单列。**不引 masonry 依赖。**
      → `.cand-thumb{max-height:220px}` / `.ref-cell-thumb{max-height:180px}` / `.ref-thumb{max-height:96px}`；
        `.cand-grid`、`.ref-grid`、`.ref-thumbs` 均加 `align-items: start`。
      → 追加：本地粘贴的参考图未入库、没有 `width/height`，新增 `probeRefSize(uid)` 用条目**自己那条**
        `blob:` 预览 URL 探测宽高（不另建 URL，避免多一条待 revoke 的泄漏面），失败静默回落 4/3。

## 6. 后端注释同步

- [x] `ImageService.java` `ALLOWED_SIZES` 注释补「前端 4 档比例 → 本白名单」的映射说明。**值未动。**
- [x] `ImageGenDTO.java` `size` 字段注释与实际白名单一致（3 个合法值 + 非法 400 + 「不接收比例值」）。

## 7. 验证

- [x] `cd frontend && npm run build`（须通过）→ ✅ `✓ built in 39.98s`（改动后又跑一次：`✓ built in 40.67s`）。
- [x] `mvn -q -DskipTests -Dmaven.repo.local=/tmp/m2repo compile` → ✅ 退出码 0，无输出（仅注释改动）。
- [ ] AC1–AC4 走查：四档比例可见、无 16:9、像素标注（**已由代码 + node 断言静态核对**），
      「选 9:16 生成，产出图竖向且 `gen_size=1024x1536`、不触发 400」**需真实调 AI 服务 → 本次未执行**。
- [ ] AC5–AC6 走查：生成中可取消 + 取消后无「已进图库」提示 → **需浏览器真实交互（点取消）→ 本次未执行**。
- [ ] AC7 走查：空 prompt 校验（代码在）/ 断网超时提示可区分（代码在）→ **断网场景需真实断网 → 本次未执行**。
- [ ] AC8 走查：结果区回显本次比例 + 像素 + 张数 → **需真实生成一次出候选 → 本次未执行**。
- [ ] AC9–AC10 走查：竖图缩略图竖向完整 + 图库弹窗存量竖图真实比例 → **需图库里有竖图 → 本次未执行**。
- [ ] AC11 回归：文生图 / 图生图 / 重生成三链路 → **需真实 AI 调用 → 本次未执行**（静态面：三条链路的
      参数与调用点 diff 已逐个核对，接口签名向后兼容）。

## 8. 规格同步

- [x] `docs/spec/image.md`：§6（size 契约 + 比例档映射 + 「比例不落库」+ 「取消是纯客户端行为」）、
      §8（新增「出图比例 + 生成交互」子条目）、§9（关键实现路径补 `utils/imageGenRatio.js`）、
      §10（已知限制：4 档近似 / 比例与像素未分离持久化）、§8 标题日期串。
- [x] `.trellis/spec/frontend/index.md`：补 3 条可复用约定（与 `external-cli-integration.md` 交叉引用）：
      「客户端取消 ≠ 服务端停止」（含判据顺序与「取消后不弹成功提示」）、
      「语义档位与底层取值不同值时档位独立记忆、不可反查」、「缩略图按真实宽高展示，缺失回落而非破版」。

## Review Gates

- **Gate A（步骤 1 后）**：✅ 通过。比例档数 = 4（`GEN_RATIOS.length === 4`，无 16:9）；
  `genSize` 已是 `computed(() => sizeOfRatio(genRatio.value))`（全文件无 `genSize.value =` 赋值点）；
  `1:1` 档 `exact: true` → 模板只输出 `1024×1024`，无「近似/实际」字样。
- **Gate B（步骤 2 后）**：✅ 通过。取消路径不发成功提示：成功分支 `if (ctl.signal.aborted) return` +
  catch 取消分支只弹 `CANCEL_TIP`；文案常量含「已提交给服务端的任务可能仍会完成并入库」。
- **Gate C（步骤 3 后）**：✅ 通过。`reportGenError` 第一条判据就是 `canceled`，超时判据（含宽松的
  `/timeout|超时/` 消息匹配）排在其后，取消不会被显示成「请重试」。
- **Gate D（步骤 5 后）**：✅ 通过（代码层）。三处缩略图都改成内联真实比例 + `contain`；缺失/0/null 三种
  情况经 node 复刻函数验证均回落 `4/3`；网格 `align-items: start` + 限高已加。**视觉最终效果待浏览器核对**。
- **Gate E（步骤 7 后）**：⚠️ 部分通过。build + compile 绿；AC1/AC2 及全部取消/回显/缩略图的**代码路径**已具备，
  但 AC3/AC5/AC6/AC7/AC8/AC9/AC10/AC11 需要真实 AI 调用与浏览器交互，**本次未执行实测**（见下）。

## Rollback

- 单 commit 可整体 revert：改动仅 1 个 Vue 组件 + 1 个新 util + `api/index.js` 可选形参 + 注释/文档。
- **无 DB 迁移、无 schema 变更、无新增配置项** ⇒ 回滚无数据/配置副作用。

---

## 实测记录（2026-09-27，implement 子代理）

### 改动文件清单

| 文件 | 性质 | 内容 |
|---|---|---|
| `frontend/src/utils/imageGenRatio.js` | **新增** | `GEN_RATIOS`（4 档映射，单一真源）+ `GEN_RATIO_DEFAULT` / `sizeOfRatio()` / `cssRatioOf()` |
| `frontend/src/components/AiImageDrawer.vue` | 改 | 比例选择器（替代 2 处像素下拉，两 tab 共用区）、生成中取消、错误分层、参数回显、缩略图真实比例、`probeRefSize` |
| `frontend/src/api/index.js` | 改 | `imageApi.generateText` / `generateFromImageUpload` 末尾**可选** `signal` 透传 axios |
| `src/main/java/com/sparkora/service/ImageService.java` | 改（**仅注释**） | `ALLOWED_SIZES` 处补 4 档比例映射说明；**白名单值未动** |
| `src/main/java/com/sparkora/domain/dto/ImageGenDTO.java` | 改（**仅注释**） | `size` 字段补合法值/400 语义/「不接收比例值」 |
| `docs/spec/image.md` | 改 | §6 size 契约、§8 抽屉职责、§9 实现路径、§10 已知限制 |
| `.trellis/spec/frontend/index.md` | 改 | 新增 3 条前端可复用约定 |

零 DB 迁移、零新配置项、零新依赖（未引 masonry / Vant / 任何包）。

### 验证命令真实结果

1. `cd frontend && npm run build` → **成功**，`✓ built in 39.98s`；改动后再跑一次 → `✓ built in 40.67s`
   （仅剩既有的 chunk >500kB 提示，与本任务无关）。
2. `mvn -q -DskipTests -Dmaven.repo.local=/tmp/m2repo compile` → **成功**，退出码 0，无警告输出。
3. 映射表 node 断言：档位 4 个、无 `16:9`；`1:1→1024x1024(精确)`、`4:3→1536x1024`、
   `3:4→1024x1536`、`9:16→1024x1536`；未知档回落 `1024x1024`（不抛错）。
4. `ratioStyleOf` 回落逻辑 node 复刻：`{1024,1536}`→`1024 / 1536`；`{}`/`{0,0}`/`{null,null}` → 均 `4 / 3`。
5. `grep` 交叉核对：全仓（除本任务文档）已无「方图/横图 1536/竖图 1024」旧文案；无 16:9 字样。

### AC 逐条状态

| AC | 状态 | 依据 / 未验证原因 |
|---|---|---|
| AC1 两 tab 均有 4 档比例、无 16:9 | ✅ 代码层 | 参数行上移为两 tab 共用区，渲染 `GEN_RATIOS`（4 项，node 断言无 16:9）。**渲染观感待浏览器** |
| AC2 每档标注实际像素、1:1 无「近似」 | ✅ 代码层 | 模板 `r.exact ? pxTextOf(r.size) : '实际 ' + pxTextOf(r.size)` |
| AC3 选 9:16 产出竖图、`gen_size=1024x1536`、不 400 | ⏸ **未执行** | 需真实调 axonhub 生成一张竖图并查库；代码侧映射与白名单已静态核对 |
| AC4 `ALLOWED_SIZES` 仍 3 值、非法 size 仍 400 | ✅ 代码层 | 后端仅注释改动（`git diff` 确认无逻辑变更）；`normalizeSize` 原样 |
| AC5 生成中可见取消（含 n=1）、请求真中断、文案如实 | ⏸ **未执行**（代码层 ✅） | 取消按钮 `v-if="generating"` 不区分 n；`AbortController.abort()` + `CANCEL_TIP`。**需真实点一次取消** |
| AC6 取消后不出现「已进图库」 | ⏸ **未执行**（代码层 ✅） | 成功分支首行 `if (ctl.signal.aborted) return`；取消分支只弹 `CANCEL_TIP` |
| AC7 空 prompt 校验 / 断网超时可区分 | ⏸ **未执行**（代码层 ✅） | 校验在 handler 前置；分层见 `reportGenError`（超时→「减少张数后重试」，断网→「检查网络后重试」）。**断网需真断网** |
| AC8 结果区回显比例+像素+张数 | ⏸ **未执行**（代码层 ✅） | `runEcho` 由 `lastRun` 提交时快照派生；需真实生成一次出候选才可见 |
| AC9 竖图候选缩略图竖向完整、网格不乱 | ⏸ **未执行**（代码层 ✅） | `fit="contain"` + 内联 `aspect-ratio` + 限高 + `align-items: start`。**需真实竖图目视** |
| AC10 图库弹窗存量竖图真实比例 | ⏸ **未执行**（代码层 ✅） | 同上（`.ref-cell-thumb`）。需图库里有存量竖图 |
| AC11 三链路回归 + build/compile | ⚠️ 一半 | build ✅ compile ✅；三条链路需真实 AI 调用，**未执行**。静态面：`imageApi` 新增形参为末尾可选，存量调用方（`useImageLibraryOps.js:102`）不受影响 |
| AC12 规格同步 | ✅ | `docs/spec/image.md` §6/§8/§9/§10 + `.trellis/spec/frontend/index.md` 3 条约定 |

### 与 design.md 预期不符之处及处理

1. **`<script setup>` 不能 `export`**（design §2 说「放在前端一个导出常量里」）：编译期禁止 ES 导出，
   故按 design §2 的备选「或抽到 utils/」新建 `frontend/src/utils/imageGenRatio.js`。
   这是本次唯一的**新增文件**（不在原始「改动只限」清单内），依据 design 明确授权。
2. **「抽子组件或 slot 复用」未采用子组件**：改为把「比例 + 张数 + 生成/取消」整块**上移到 `</el-tabs>` 之外**
   作为两 tab 共用区。比子组件更少重复（子组件仍需在两处各写一次标签 + v-model 传参），
   且天然共用同一 `genRatio`/`genSize`/`AbortController`；同时受「只改 `AiImageDrawer.vue`」的硬约束。
3. **生成中骨架提示原文「约 10~30 秒」被删**：该数字与实际耗时无关（后端超时上限 300s）且属 PRD R2 明令
   不许的「预估耗时」，改为指向取消入口的无数字文案。

### 剩余风险

- **视觉观感未实测**（AC1/AC9/AC10）：比例卡片在 420px 抽屉内 4 列、窄屏 2×2；竖图 `contain` 会在纸色底上留边
  （设计如此，避免裁切/变形）。若 4 列在某个宽度下挤掉「实际 1024×1536」文字，需调列数或字号。
- **取消的真实语义**（AC5/AC6）：只能保证「客户端停止等待 + 如实告知」，**无法保证服务端停止处理**——
  被取消的生成仍可能把图写入图库（用户在图库页可能看到「幽灵图」）。这是设计决策（design §3 方案 A），
  后端当前**没有**取消接口，若日后要真取消需另开任务（服务端协作式中断）。
- **`el-image` + 内联 `aspect-ratio`**：Element Plus 的 `el-image` 根节点是 `overflow:hidden` 的相对定位块，
  限高被 clamp 时内层 `object-fit: contain` 会留上下留白（预期行为），但极端长图（如 `768x2048`）单元格会偏高，
  已用 `max-height` 兜底。
- **本地参考图宽高探测**（`probeRefSize`）依赖 `Image` 解码 blob：极快失败（如条目被立刻移除）时回落 4/3，
  不影响功能；但同一条 `blob:` URL 被 `<img>` 与探测 `Image` 共用，若将来在别处 revoke 该 URL 需一并考虑。
- **后端零测试覆盖新增逻辑**：本次后端仅注释改动，无新增逻辑，故未跑 `mvn test`（跑 510 个用例只为验证注释
  性价比低）；如需全量回归请执行 `mvn test`。

---

## check 阶段记录（2026-09-28，check 子代理）

### 修复项

| # | 严重度 | 位置 | 问题 | 修复 |
|---|---|---|---|---|
| 1 | **P0** | `frontend/src/api/http.js:34` + `api/index.js` 3 个方法 | **全局错误拦截器把新加的错误分层与取消提示打回原形**：axios 的 `CanceledError` 也会走响应拦截器 reject 分支，弹一条**红色英文** `canceled`；超时/断网同理弹 `timeout of 300000ms exceeded` / `Network Error`。结果 = 两个 toast，且**「已取消」被显示成错误**——直接违反 AC5/AC6 与 `error-handling.md`「禁止透出框架内部串」 | 拦截器加 `err.config?.skipGlobalErrorToast` 开关（401 分支不受影响，仍无条件跳登录）；`imageApi.generateText` / `generateFromImageUpload` / `regenerate` 传 `skipGlobalErrorToast: true`（三个方法的两个调用方都自带错误提示，无覆盖缺口） |
| 2 | P1 | `AiImageDrawer.vue:503-543`（`doGenerate`） | **`lastRun` 与 `candidates` 不同步**：入口就写新快照但不换候选，于是「上批候选还在 → 发新批 → 取消/失败」后，`generating` 落回 false 时结果区渲染**旧候选**、回显却是**新那次的比例/像素/张数**（回显与图不匹配）。`res.code!==0`（如白名单外尺寸 400）路径同病 | 增 `settled` 标记：仅在 `candidates.value = list` 后置真；`finally` 中 `if (!settled) lastRun.value = prevRun` 回滚（选回滚而非清空候选，是为了不丢用户上一批结果） |
| 3 | P2 | `AiImageDrawer.vue` `.ratio-picker` | 固定 `repeat(4, 1fr)`：容器变窄时（preview 内联模式宿主更窄）`.ratio-px`（`nowrap` 的「实际 1024×1536」约 77px）会被挤出按钮边框（`.ratio-item` 无 `overflow:hidden`，文字直接溢出边框） | 改 `repeat(auto-fit, minmax(84px, 1fr))`：**容器自适应**降为 3/2 列，84px 下限保证 420px 抽屉（内容区 380px）仍 4 列（(380−18)/4≈90px）；移动端 `@media ≤768px` 的 2×2 规则仍在后覆盖 |

### 复核为「非缺陷」的疑点（附依据）

- **`.cand-tip` 内嵌 `<div>`**：普通 div 嵌套 div，合法；Element Plus 不参与该容器，无约束冲突。文本节点在块级子元素后另起一行，正是「回显在上、说明在下」的预期排版。
- **`el-image` + 内联 `aspect-ratio` + `max-height` 几何**：查 `element-plus@2.8` 源码，`.el-image` = `position:relative; display:inline-block; **overflow:hidden**`，`.el-image__inner` = `width:100%; height:100%`，`fit` 只额外加 inline `object-fit`。⇒ 根块被 `max-height` clamp 时内层图**按比例缩放贴合并留纸色边**（letterbox），**不会被裁切也不会变形**，`.cand-thumb{background:var(--paper)}` 已兜底。`.ref-thumb` 是原生 `<img>` + `object-fit:contain`，同理。
- **`ratioStyleOf` 回落**：`Number(undefined/''/null/0)` → `NaN`/`0` → `Number.isFinite && >0` 双条件拦下，一律回落 `4/3`，**永不输出非法值**（不会产生 `aspect-ratio: NaN`）。
- **`probeRefSize`**：复用条目自己那条 `blob:` URL（不新建 → 不多一条待 revoke）；`onerror` 静默；条目已被移除时 `find` 返 undefined 直接 return。`Image` 走 DOM 事件而非 Promise，**无 unhandled rejection 风险**。
- **`isTransport` 判 `!e?.response`**：axios 的 4xx/5xx（`ERR_BAD_REQUEST`/`ERR_BAD_RESPONSE`）都带 `response`，且业务 `R.fail` 走 200 成功分支；真正无 response 的只有断网/DNS/连接被拒 → 报「网络连接失败」正确，不会误报。
- **`onRegenerate` 传 `null` ctl**：`isCanceled(e, null)` 退化为只看 `ERR_CANCELED`/`CanceledError`——该路径本就无 `AbortController`，恒 false 即恒走正常分层；将来若补取消也能正确降级。合理。
- **`useImageLibraryOps.js:102` 重生成无 signal**：图库页卡片重生成是既有独立交互（非本次范围），传 7 个实参不受新增第 8 位可选形参影响；其自带 catch 提示不受影响。可接受。
- **`isCanceled`/`isTimeout`/`isTransport` 互吞**：node 复刻 7 个用例实测分层顺序（取消·含 timeout 消息的最坏情况 → 取消·仅 signal → 后端 400 msg → 网关 5xx msg → 超时 → 断网 → 非 Error 抛出），**取消恒最先命中**，不会被显示成「请重试」。

### 复跑验证（check 阶段真实输出）

1. `cd frontend && npm run build` → **成功**，`✓ built in 39.14s`（改动后；implement 阶段为 39.98s/40.67s）。
2. `mvn -q -DskipTests -Dmaven.repo.local=/tmp/m2repo compile` → **退出码 0**（后端仍为**纯注释**改动，`git diff -U0 -- src/main/java` 过滤掉注释行后**输出为空** = 无逻辑变更）。
3. 映射表 node 断言：档位 4 个、**无 16:9**（全仓 `grep 16:9` 仅命中 `imageGenRatio.js:10` 的「刻意不加」注释）、3 个像素值全在 `ALLOWED_SIZES` 内、`cssRatioOf` 输出 `1 / 1`…`9 / 16`、未知档回落不抛错。
4. `doGenerate` 状态机 node 复刻（4 场景）：取消 / 后端 400 / 断网 三条失败路径的「候选 id + 回显快照」与上一批**完全一致**（一致 ? true），首次成功路径正确保留新快照，`generating` 均复位 false。**修复前三者会显示新快照配旧图。**
5. **真实 axios 实测拦截器**（node 起本地慢端点 + 真实 `axios@1.19.0` + 复刻 `http.js` 拦截器）：
   - 不传 flag：`["GLOBAL:canceled", "INFO:已取消本次请求（…）"]` ⇒ **两个 toast、取消被显示成红色英文错误**（= 缺陷 1 的实证）。
   - 传 flag：`["INFO:已取消本次请求（…）"]` ⇒ 单一如实告知提示。
   - 超时同构：不传 `["GLOBAL:timeout of 300ms exceeded", "ERR:生成超时…"]`；传后仅剩中文分层提示。
   - 附带确认 `AxiosError` 有 `this.config`（`axios/lib/core/AxiosError.js:163`），`err.config?.skipGlobalErrorToast` 在 CanceledError 上可读。
6. 硬约束复核：`genSize` 全文件仍为 `computed`（`:207`）、无 `genSize.value =` 写点；`ALLOWED_SIZES` 仍 3 值（`ImageService.java:72`）；`git diff --check` 干净；无新增依赖/配置/迁移；`size-select` 旧类无残留；宿主无 `:deep()` 覆盖抽屉内部（不与新共用区冲突）。

### AC 状态变化（check 阶段）

**无变化：仍无任何 AC 从「⏸ 未执行」升级为已完成。** 本次全部为静态/构建/真实 axios 层面的验证，**不构成**真实浏览器交互或真实 AI 生图验证。AC3/AC5/AC6/AC7/AC8/AC9/AC10/AC11 **仍需人工在真实环境点一遍**（清单见下）。

修复项对 AC 的影响（**均为「代码层缺陷已消除」，不是「AC 已验证」**）：

| AC | 修复前代码层状态 | check 后代码层状态 | 是否需重测 |
|---|---|---|---|
| AC5 取消入口 + 如实文案 | ⚠️ **实为缺陷**：除取消提示外还会弹红色英文 `canceled` | ✅ 单一中文如实告知提示 | **是**（需真点一次取消确认只弹一条） |
| AC6 取消后无「已进图库」 | ✅（成功分支首行 `if (ctl.signal.aborted) return`） | ✅ 不变 | 否（同属 AC5 那次点击可一并验） |
| AC7 断网/超时可区分 | ⚠️ **实为缺陷**：与全局英文框架串 toast 并列出现 | ✅ 仅剩分层中文提示 | **是**（需真断网/真超时各验一次） |
| AC8 参数回显 | ⚠️ **实为缺陷**：失败/取消后回显与旧候选图不匹配 | ✅ 一致（失败回滚） | **是**（需「上批成功 → 新批失败/取消」走一遍） |
| AC1/AC2/AC9/AC10 比例与缩略图 | ✅ 代码层 | ✅ + 比例选择器改为容器自适应（AC1 观感仍待目视） | AC1 观感建议顺带看一眼窄宿主 |
| AC4 后端白名单/400 | ✅ 代码层 | ✅ 不变 | 否 |
| AC12 规格同步 | ✅ | ✅ 已补「跳过全局 toast」「回显不得与图不同源」两条口径 | 否 |

### 仍必须人工实测的 AC（**请勿据此勾选**）

- **AC3** 选 9:16 真跑一次生图 → 产出竖图、`gen_size=1024x1536`、`width/height` 与之一致、不 400（需真实 axonhub）。
- **AC5 / AC6 / AC7** 生成中点「取消」（含 `n=1`）；再断网 + 等超时各验一次，确认提示**只有一条**且可区分。
- **AC8** 「上批成功 → 改比例 → 新批失败/取消」走一遍，确认回显与候选图仍匹配。
- **AC9 / AC10** 竖图在候选区与图库弹窗目视完整（`contain` 会在纸色底上留边，属设计预期）；横竖混排网格不乱。
- **AC11** 文生图 / 图生图 / 重生成三链路各真跑一次（`npm run build` 与 `mvn compile` 已过，但三链路未实测）。
- **AC1 观感补充** 在窄宿主（preview 内联模式）下看比例卡片是否 4 列、文字是否被挤。
