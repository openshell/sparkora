# 扩展 wenyan 主题库并支持社区主题发布

## Goal

让创作时可选的排版主题从当前的 4 个扩展到 **15 个**（8 个 wenyan 内置主题 + 7 个 mdnice 社区主题），且这 15 个主题都能在预览页即时预览、在发布页原样发布到公众号草稿箱。用户价值：更多样的文章排版风格，且「选得到 = 发得出」，不再出现选完主题无法发布的情况。

## Background（已确认事实）

- **发布链路与主题的关系**：发布 = 本机 `wenyan render` 渲染成 HTML → 后端组 `gzhContent` → wenyan-server `/upload` → `/publish` → 微信草稿箱。**主题只在本机渲染阶段应用，wenyan-server 不感知主题**，因此扩展主题不需要改动 server 协议，也不需要往 server 注册主题。
- **CLI 2.0.11 内置 8 主题**：`default / orangeheart / rainbow / lapis / pie / maize / purple / phycat`。当前 `.env WENYAN_THEME_NAMES=default,lapis,orangeheart,phycat` 仅放行 4 个，`rainbow/pie/maize/purple` 选不到。
- **7 个 mdnice 社区主题**（`chazi 姹紫 / mohei 墨黑 / nenqin 嫩青 / hongfei 红绯 / lanqing 兰青 / shanchui 山吹 / quanzhanlan 全栈蓝`）CSS 已在前端 `frontend/src/assets/wenyan-themes/*.css` 打包，但后端白名单拒绝、wenyan CLI 也不识别（`主题不存在`），因此**当前完全无法预览/发布**。
- **CLI 自定义主题机制（实测验证）**：
  - `wenyan render --custom-theme <本地CSS路径>` 可渲染社区主题；`--custom-theme` **不支持网络 URL**（传 URL 退出码 1），故社区 CSS 必须随后端包内置。
  - `--custom-theme` 与 `--theme` **同时传时 `--theme` 覆盖**，故社区主题必须**只传 `--custom-theme`、不传 `--theme`**。
  - 非法 CSS 路径退出码 1，会走现有渲染降级链（degraded=true + 中文原因）。
  - `wenyan theme --add/--rm` 可注册到 `~/.config/wenyan-md`，但会污染宿主环境且重名退出码 1；本任务改用 `--custom-theme` 免注册方式，不写宿主配置。
- **前端渲染已具备社区主题能力**：`frontend/src/utils/wenyanRender.js` 对 `custom:*` id 直传 themeCss（`applyStylesWithResolvedCss`），`buildWechatHtml` 亦支持；此前仅因后端白名单拒绝才被移出下拉。
- **项目文档**：`docs/s0-spec.md` §11/§12 是主题与发布契约的权威来源；用户要求把 wenyan 关键文档沉淀进项目文档，便于后续开发理解。

## Requirements

- **R1 主题目录**：后端提供权威主题目录（8 内置 + 7 社区），每项含 `id / name(显示名) / group(内置|社区) / color / bright`，供前端下拉与后端白名单共用同一份清单。
- **R2 预览一致**：15 个主题全部可在预览页选择并即时预览，社区主题与内置主题体验一致。
- **R3 发布一致**：15 个主题全部可发布；发布链路的渲染结果与预览所选主题一致（同源渲染）。
- **R4 持久化**：所选主题（含社区主题 id）沿用现有项目级持久化（`preview_theme`），刷新/跨会话保持。
- **R5 校验**：未知主题仍被拒绝并给出中文提示；不得因放行社区主题而放宽参数注入防护。
- **R6 文档沉淀**：把 wenyan 主题/发布关键机制（主题来源、`--custom-theme` 用法与限制、发布链路主题落点）写入项目文档（新增 `docs/wenyan.md` 并同步 `docs/s0-spec.md`）。
- **R7 分组下拉**：预览页主题下拉按「内置主题 / 社区主题」两组呈现（`el-option-group`），社区主题显示中文名与色点。

## Acceptance Criteria

- [ ] AC1 预览页主题下拉分组列出 15 个主题（内置 8 + 社区 7），社区主题显示中文名。
- [ ] AC2 选择任一社区主题，预览即时生效，刷新页面后仍保持该主题（落库回显）。
- [ ] AC3 对社区主题执行发布，成功进入 `PUBLISHED_DRAFT`，且渲染 HTML 带该社区主题样式（非默认主题观感）。
- [ ] AC4 内置 8 主题全部可选可发布（此前 `rainbow/pie/maize/purple` 不可选）。
- [ ] AC5 未知主题 id 仍返回 `R.fail(400)` 中文提示；`preview-style` 保存未知主题同样被拒。
- [ ] AC6 `mvn -q -DskipTests compile` 与 `cd frontend && npm run build` 均通过。
- [ ] AC7 `docs/wenyan.md` 存在且覆盖主题来源、`--custom-theme` 限制、发布链路主题落点；`docs/s0-spec.md` §11/§12 同步。

## Out of Scope

- 用户自上传任意 CSS 主题（本次仅固定内置 7 个社区主题）。
- 高亮主题（代码块配色）扩展，仍维持现有 4 个。
- 改动 wenyan-server 协议或往 server 注册主题。
- 深度研究/仿写等其他链路。
- 为 15 个主题提供缩略图预览（仅色点 + 名称）。
