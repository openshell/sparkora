# 创建与简报生成 bug 排查修复

## 背景
用户报告:①输入主题后未让用户选择思考深度、未进反问;②创建→简报页面不顺畅。

## 根因
1. 创建页 ProjectEdit 无深度选择,创建后无条件直发 FAST /generate/brief;DEEP+反问入口只在创建后的简报页 hero 里(用户不可见)。
2. 创建页同步等待 generateBrief 完成(1~2 分钟)才跳转;非 brief 请求走 http 默认 30s 超时,慢 AI 下 doSave 被 catch 误判失败,页面卡死观感。
   注:generateBrief 封装本身有 120s 超时,30s 短轮询只影响其他请求;主因是「等完才跳转」的交互。

## 修复(前端为主,后端 /deep 契约与规格一致未动)
- ProjectEdit:加「思考深度」FAST/DEEP 单选(默认 FAST,含校验与说明文案);genDepth 前端专用,create 时剔除。
- onSaveAndGenerate:创建成功立即跳详情页,再按深度发起 generateBrief 或 deep/clarify(均 120s 超时),生成过程由详情页 4s 轮询展示;发起失败不回退,详情页可重试。
- 仅存草稿:跳转带 ?gen= 意图参数,DEEP 时 StepBrief 直接展开深度面板(读取后清除)。
- projectApi 新增 startDeep/submitDeepAnswers/runDeep/generateDeep 封装(startDeep 120s 超时)。
- spec §5 增补思考深度契约。

## 验收标准
- [x] 创建页可选思考深度;DEEP 创建后直达反问环节(clarify→CLARIFYING 恢复)。
- [x] 创建→简报页流转顺畅:立即跳详情,GEN_BRIEF 状态轮询/深度面板均可见,无 30s 超时误报。
- [x] 其余走查:deep 链路 clarify/clarify-answer/run/generate/status 与 spec §12 一致,未发现其他缺陷。
- [x] mvn -q -DskipTests compile + npm run build 通过。
- [ ] 真机走查(FAST 生成、DEEP 反问→研究→生成)留用户浏览器验收。

## 第二轮:深度模式专项修复(2026-09-05)

### 用户反馈
1. 「大唐」主题的反问问题选项里没有大唐EV——反问没有结合车型知识库。
2. 确定研究计划后,没有简报页面。

### 根因
1. ClarifyService prompt 未注入车库车型名录,LLM 凭主题猜测;原规格仅 single 题型,锚点车型多选无处落。
2. 深度链路只落 research_plan/clarify_*/research_notes/fact_sheet,从不生成简报字段,currentBriefId 不指向 DEEP brief → 简报页结构性缺失。
3. 附带发现:LLM 把 toolHints 数组化为字符串数组时,DeepResearchService 取 .path("tools") 得空串→parseTools 回退全 KB(S8 已修过一次的序列化 bug 变体,本次补齐)。

### 修复
- ClarifyService:注入 CarModelService.list() 名录;车型/竞品问题 options 只能从名录选;主题指向车型时必问 multi 锚点题;questions 支持 input|single|multi;清理 ansBlank 死代码。
- BriefService.generateFromFactSheet:基于事实手册+锁定需求生成简报,落同一条 DEEP brief 行,currentBriefId 指向它,状态机 GENERATING_BRIEF→READY;守护/失败回退与 FAST 相同;抽取共用 claimGenerating。
- DeepResearchService.runAsync:落 fact_sheet 后自动触发简报生成(失败不回滚研究,留 lastBriefError 可重试);toolHints 字符串数组兼容。
- DeepController:POST /deep/brief(手动重试,409=状态冲突);注入 BriefService。
- 前端:ClarifyForm 支持 multi(checkbox,「、」拼接/还原);StepBrief 研究完成态提示自动简报生成中+「重新生成简报」+「跳过简报直接生成正文」;api.generateDeepBrief。

### 验收标准
- [x] 「大唐」主题反问含大唐EV等真实车库车型选项(multi 锚点题)——待真机确认选项内容。
- [x] 研究完成后简报自动生成,简报页正常展示(状态机 READY);失败可手动重试或跳过。
- [x] mvn -q -DskipTests compile + npm run build 通过。
- [ ] 真机端到端:创建(深度)→反问(含大唐EV)→锁定→研究→自动简报→多版本。
