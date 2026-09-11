# 修复全栈蓝主题失效外链导致发布失败

## Goal

修复社区主题「全栈蓝」（`custom:quanzhanlan`）发布失败的问题：该主题 CSS 含一个已失效的外链装饰图标，wenyan-server 发布时下载失败导致整次发布中断。移除该失效外链后，全栈蓝主题可正常预览与发布。

## Background（已确认事实）

- **报错**：发布到草稿箱返回「最近一次发布失败 / 下载图片失败 URL: https://imgkr.cn-bj.ufileos.com/15fdfb3c-b350-4da9-928e-5f8c506ec325.png」。
- **根因**：该 URL 来自 `quanzhanlan.css` 第 58 行 `#wenyan h2::before` 的 `background-image`（h2 标题前的 20×20 装饰小图标）。
- **失效验证**：该 URL 实测返回 **HTTP 400**（图壳图床 `imgkr.cn-bj.ufileos.com` 已失效）；官方源 CSS（`file.yuzhi.tech`）也含同一失效 URL，属上游遗留。
- **发布链路**：发布 = 本机 `wenyan render --custom-theme <CSS>` 渲染成 HTML → 组 `gzhContent` → wenyan-server 下载 HTML 中引用的图片 → 写入草稿箱。外链图片下载失败即整次发布失败。
- **渲染验证**：`wenyan render --custom-theme quanzhanlan.css` 的输出 HTML 中确实包含该失效 URL。
- **影响范围**：仅 `quanzhanlan` 一个主题；其余 6 个社区主题无外链图片（`hongfei` 的 `background-image` 是 `linear-gradient`，非外链）。
- **双份存在**：CSS 同时存在于后端 `src/main/resources/wenyan-themes/quanzhanlan.css`（CLI 发布用）与前端 `frontend/src/assets/wenyan-themes/quanzhanlan.css`（浏览器预览用），两份必须同步修改。

## Requirements

- **R1**：移除 `quanzhanlan.css` 中失效的外链 `background-image`（`#wenyan h2::before` 规则内的该属性），前后端两份同步。
- **R2**：不改变全栈蓝主题其余任何样式与观感（仅去掉这个已失效、本就显示不出的装饰图标）。
- **R3**：修复后全栈蓝主题渲染输出中不再包含任何 `imgkr.cn-bj.ufileos.com` 外链 URL。

## Acceptance Criteria

- [ ] AC1 `src/main/resources/wenyan-themes/quanzhanlan.css` 与 `frontend/src/assets/wenyan-themes/quanzhanlan.css` 均不再含 `imgkr`/`ufileos` 外链。
- [ ] AC2 两份 CSS 内容一致（`diff` 为空）。
- [ ] AC3 `wenyan render --custom-theme <quanzhanlan.css>` 输出 HTML 中不再出现该失效 URL，且渲染 EXIT=0。
- [ ] AC4 全栈蓝主题其余样式规则保持不变（仅移除 1 行 `background-image`）。
- [ ] AC5 后端 `mvn -q -DskipTests compile` 与前端 `npm run build` 均通过。
- [ ] AC6 发布链路复测：对全栈蓝主题执行发布不再因该 URL 失败（发布成功进 `PUBLISHED_DRAFT`；若受外部微信/server 条件限制，则至少验证发布前置的渲染产物无失效外链）。

## Out of Scope

- 为 h2 标题重新设计/替换装饰图标（用户已决定直接移除）。
- 修复其他主题或 wenyan-server 本身的图片下载逻辑。
- 用户自上传主题能力。
