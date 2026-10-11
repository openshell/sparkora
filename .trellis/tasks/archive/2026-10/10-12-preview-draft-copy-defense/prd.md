# 预览页保存与复制防御

## Goal

修复 2026-10-12 预览页评审确认的行为级缺陷(丢稿防御 ×2、富文本复制 http 源必失败),并入四个小项打磨(闪烁/字数/宽度持久化/§4 规格漂移)。

## 背景(评审结论,全部有代码证据)

| # | 问题 | 证据 |
|---|---|---|
| P1 | 富文本复制只走 `navigator.clipboard.write + ClipboardItem`;Chrome **非安全源**(http://IP:8088 产线形态)下 `navigator.clipboard` 为 undefined → 恒报「浏览器不支持富文本复制」 | StepPreview.vue:408-416 |
| P2a | 草稿只在 `watch(dirty)` false→true 翻转瞬间写一次,持续编辑**不再更新** → 崩溃恢复只得「首次改动版」,之后的编辑全丢 | StepPreview.vue:465-468 |
| P2b | 草稿**无放弃路径**:「刷新放弃」提示不成立(draft≠originalMd 每次进页都命中恢复分支);toast 6s 内没有放弃动作 | StepPreview.vue:346-353 |
| P2d | 每次重渲染 `.wenyan-preview` 的 `fadein .18s` 重放 → 连续打字时预览每 400ms 闪一次 | PreviewPane.vue:90 |
| P3c | 字数把 markdown 语法与图片 URL 全算进「字数」,插图多时严重虚高 | StepPreview.vue:157 |
| P3d | 宽度档位(phone/tablet/full)不持久化,分栏比例却持久化,体验不一致 | StepPreview.vue:146 |
| P3e | 规格漂移:docs/spec/preview.md §4 仍写「iframe `srcdoc` + 顶部标注排版引擎:文颜」,实现已是 `v-html` + sanitize、pane-head「公众号预览」 | docs/spec/preview.md §4 vs PreviewPane.vue |

## Requirements

### R1 复制降级链(P1)

「复制排版」按序降级,**不得改变既有富文本优先语义**:

1. 安全源:现状不变(`navigator.clipboard.write` 富文本,成功文案不重复)。
2. 富文本降级:`execCommand('copy')` 经隐藏 contenteditable 载入内联 HTML(桌面 Chrome/Edge 可在用户手势中剪裁富文本)——成功文案与原生一致。
3. 再降级:纯文本兜底,复制 **markdown 源文**(`contentMd`),提示**明确告知非排版**(如「浏览器不支持富文本复制,已复制 Markdown 源文」)。
4. 全部失败:保留现报错。
- 失败/降级不得静默;不引依赖。

### R2 草稿节流写(P2a)

- dirty 期间对 `contentMd` **节流写草稿**(~1s debounce,沿用防抖清理惯例),保证崩溃恢复贴近最新编辑;保存成功清除草稿的既有语义不动。
- 非编辑场景(采纳落库/插图插入)同样受益;不得在 dirty=false 时写。

### R3 草稿放弃路径(P2b)

- 进页恢复草稿时,除 toast 外加**常驻警示条**(`el-alert`,风格对齐 lost-image-alert):「已恢复未保存草稿」+「放弃草稿(回退已保存版本)」动作。
- 放弃 = `contentMd = originalMd` + 清 draftKey + 警示条消失 + 轻提示;保存成功同样使警示条消失。
- 警示条**只在「曾恢复草稿」会话内出现**,普通编辑产生的 dirty 不展示此条(避免编辑全程误报)。
- 修复 toast 文案:去掉不成立的「刷新放弃」。

### R4 渲染不闪烁(P2d)

- 内容更新(400ms 防抖重渲染)不再重放入场动画;过渡只保留在首帧/主题切换场景。不新增依赖。

### R5 字数修正(P3c)

- 「字数」剥离图片语法(整体)与链接目标(保留链接文字)后按非空白字符计数;语法字符(`#`、`!` 等)是否保留由实现自行取舍,须与真机直觉一致并在代码注释说明口径。

### R6 宽度档位持久化(P3d)

- `previewWidth` 记 localStorage(键命名对齐 `sparkora.previewSplit` 惯例),进页恢复,失败 try/catch 兜底默认 `phone`。

### R7 规格同步(P3e)

- docs/spec/preview.md §4:iframe srcdoc/v-html 现实现、复制降级链(R1)、宽度持久化等与实现一致的最小同步;不新增无关段落。

## Acceptance Criteria

- [ ] 真机(http://IP:8088):「复制排版」成功,公众号粘贴为排版富文本;或按降级得到源文 + 明确提示
- [ ] 真机:编辑后等 ≥2s 再强制刷新 → 恢复草稿为**最近一次编辑内容**(非首次改动版);点保存后草稿清除
- [ ] 真机:恢复草稿进页 → 常驻警示条可放弃;放弃后正文回服务器版本;普通编辑不出现该警示条
- [ ] 连续输入 10s 预览无闪烁;切换主题仍有过渡
- [ ] 字数:插图多/链接多的文章不再虚高(真机对比)
- [ ] 宽度档位刷新后保持
- [ ] `npm run build` 通过
- [ ] docs/spec/preview.md 同步(R7)

## Non-goals

- 不做滚动同步锚点化(P2c,中期方向,只记 journal 留痕)
- 不做自动保存(维持手动保存 + 草稿兜底模型)
- 不动保存通道契约、采纳落库语义、登记/双写逻辑
- 不处理 P3a(采纳双成功 toast 降噪,未在本批批准范围)
- 无后端改动、无新依赖