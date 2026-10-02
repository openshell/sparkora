package com.sparkora.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.sparkora.ai.AiClient;
import com.sparkora.ai.AiException;
import com.sparkora.car.service.CarRagService;
import com.sparkora.domain.entity.ArticleBriefEntity;
import com.sparkora.domain.entity.ArticleProjectEntity;
import com.sparkora.domain.entity.ArticleVersionEntity;
import com.sparkora.domain.entity.StyleProfileEntity;
import com.sparkora.mapper.ArticleBriefMapper;
import com.sparkora.mapper.ArticleProjectMapper;
import com.sparkora.mapper.ArticleVersionMapper;
import com.sparkora.mapper.StyleProfileMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 文章多版本生成服务。基于 brief + 用户从风格库选择的若干风格，每选一个风格生成一版正文。
 *
 * 风格来源：sparkora_style_profile.tone_guidance（由用户样文提炼入库），供用户选择，
 *  不再硬编码预设风格。
 *
 * 状态机：READY(brief就绪) → GENERATING_VERSIONS → VERSIONS_READY
 *  失败回 READY 并写 lastVersionError（任一版都没生成成功才算整体失败）。
 *
 * 09-27-gen-async 异步化(对齐 ClarifyService 范式):同步 generate() 毫秒级校验 + 原子抢占后返回占位,
 *  @Async runGenerate() 执行 RAG + 逐风格生成 + advanceVersionsReady/failVersionsToReady;
 *  失败在异步体内 catch 落 last_version_error,不再向控制器抛异常。
 * 事务边界同 BriefService：置状态短事务先提交，AI 调用无事务，最后写版本+置状态再提交。
 */
@Slf4j
@Service
public class VersionService {

    private final ArticleProjectMapper projectMapper;
    private final ArticleBriefMapper briefMapper;
    private final ArticleVersionMapper versionMapper;
    private final StyleProfileMapper styleMapper;
    private final AiClient aiClient;
    private final CarRagService ragService;
    private final ArticleProjectCarService carService;
    private final ObjectMapper json;
    /** 文章仿写(09-09-article-imitation):仿写 prompt 与相似度自检。 */
    private final ImitationService imitationService;
    /** 项目状态机唯一写权持有者(抢占/推进/回退/错误列,09-27-state-machine-service)。 */
    private final ProjectStatusService statusService;

    private static final String LABELS = "ABCDEFGHIJ";

    // 自注入代理,确保 @Async 生效(generate 内 this.runGenerate 不会走代理)
    @Autowired
    @Lazy
    private VersionService self;

    public VersionService(ArticleProjectMapper projectMapper, ArticleBriefMapper briefMapper,
                           ArticleVersionMapper versionMapper, StyleProfileMapper styleMapper,
                           AiClient aiClient, CarRagService ragService,
                           ArticleProjectCarService carService, ObjectMapper json,
                           ImitationService imitationService, ProjectStatusService statusService) {
        this.projectMapper = projectMapper;
        this.briefMapper = briefMapper;
        this.versionMapper = versionMapper;
        this.styleMapper = styleMapper;
        this.aiClient = aiClient;
        this.ragService = ragService;
        this.carService = carService;
        this.json = json;
        this.imitationService = imitationService;
        this.statusService = statusService;
    }

    /**
     * 启动多版本生成(同步毫秒级,占位语义):校验 + 原子抢占 GENERATING_VERSIONS 后立即返回,
     * 后台 self.runGenerate 执行 AI;前端靠项目状态轮询(GENERATING_VERSIONS→VERSIONS_READY)翻转刷新。
     * @param styleIds 用户从风格库选中的风格 id 列表（至少 1 个，最多 10 个）
     * @return 占位标记 {status:"GENERATING_VERSIONS", styleCount:N}
     */
    public Map<String, Object> generate(Long projectId, List<Long> styleIds) {
        if (styleIds == null || styleIds.isEmpty())
            throw new IllegalArgumentException("至少选择一个风格");
        if (styleIds.size() > LABELS.length())
            throw new IllegalArgumentException("一次最多生成 " + LABELS.length() + " 版");

        ArticleProjectEntity p = projectMapper.selectById(projectId);
        if (p == null) throw new IllegalArgumentException("项目不存在");
        // 并发防护:正在生成中（未过期）时拒绝重复触发;陈旧状态(超 10 分钟,进程已死)放行自愈
        if (statusService.stuckGenerating(p)) {
            throw new IllegalStateException("该项目正在生成中，请稍候（刷新页面可查看进度）");
        }
        if (p.getCurrentBriefId() == null) throw new NotReadyException("尚未生成 brief，无法生成版本");
        ArticleBriefEntity brief = briefMapper.selectById(p.getCurrentBriefId());
        if (brief == null) throw new NotReadyException("brief 不存在");

        // 校验（含 styles 存在性）保留在同步阶段(400 语义在触发时即时反馈);异步体重取
        List<StyleProfileEntity> styles = styleMapper.selectBatchIds(styleIds);
        if (styles.isEmpty()) throw new IllegalArgumentException("所选风格不存在");

        // 1) 原子抢占置进行中(消除 check-then-set 竞态):仅当「READY/VERSIONS_READY(首生成或追加)」
        //    或「生成中且已陈旧(超阈值,进程已死,自愈)」才生效,claimed==0 抛 409 守卫提示(含状态机回退拒绝)。
        statusService.claimVersionsGenerating(projectId, p, "生成版本");

        // 后台异步生成(经自注入代理确保 @Async 生效)
        (self == null ? this : self).runGenerate(projectId, styleIds);
        return Map.of("status", "GENERATING_VERSIONS", "styleCount", styleIds.size());
    }

    /**
     * 异步执行多版本生成(由 self 代理调用)。
     * 成功:advanceVersionsReady(projectId, firstId, partialErrors);全失败/异常:failVersionsToReady(落 last_version_error)。
     * 异步线程无调用方,异常只落状态不外抛。
     */
    @Async
    public void runGenerate(Long projectId, List<Long> styleIds) {
        // 重取实体:同步阶段快照可能已变(异步体不复用旧快照)
        ArticleProjectEntity p = projectMapper.selectById(projectId);
        if (p == null) {
            log.warn("版本生成异步体:项目已不存在 project={}", projectId);
            return;
        }
        try {
            if (p.getCurrentBriefId() == null) throw new NotReadyException("尚未生成 brief，无法生成版本");
            ArticleBriefEntity brief = briefMapper.selectById(p.getCurrentBriefId());
            if (brief == null) throw new NotReadyException("brief 不存在");
            List<StyleProfileEntity> styles = styleMapper.selectBatchIds(styleIds);

            List<ArticleVersionEntity> created = new ArrayList<>();
            List<String> perVersionErrors = new ArrayList<>();
            boolean imitation = "IMITATION".equals(p.getGenSource());
            // S8 统一检索:modelIds 降为写作锚点(加权),未关联也全库检索。
            // 仿写模式跳过 RAG(任意题材原文与车型库强行匹配会注入无关数据约束,污染仿写;ragStatus 记 NO_KNOWLEDGE)
            List<Long> modelIds = imitation ? List.of() : carService.listModelIds(projectId);
            CarRagService.RagResult rag = imitation ? CarRagService.RagResult.EMPTY
                    : ragService.retrieveForGeneration(p.getTopic(), 8, modelIds);

            // 2) 每个选中风格生成一版
            int i = 0;
            for (StyleProfileEntity style : styles) {
                String label = String.valueOf(LABELS.charAt(i++));
                try {
                    ArticleVersionEntity v = generateOne(p, brief, style, label, rag);
                    versionMapper.insert(v);
                    created.add(v);
                } catch (Exception e) {
                    log.warn("版本 {}({}) 生成失败 project={}: {}", label, style.getName(), projectId, e.getMessage());
                    perVersionErrors.add("[" + label + ":" + style.getName() + "] " + e.getMessage());
                }
            }

            if (created.isEmpty()) {
                throw new AiException("全部版本生成失败: " + String.join("; ", perVersionErrors), null);
            }

            // 3) 默认选第一版为当前 + 推进状态机(委托状态服务,条件更新防回退 + 首版两拆分)。
            ArticleVersionEntity first = created.get(0);
            String lastVersionError = perVersionErrors.isEmpty() ? null : "部分版本失败: " + String.join("; ", perVersionErrors);
            statusService.advanceVersionsReady(projectId, first.getId(), lastVersionError);
            log.info("版本生成完成 project={} created={} failed={}", projectId, created.size(), perVersionErrors.size());
        } catch (Exception e) {
            // 异步体无调用方,异常只落状态。失败回退(仅生成中状态,防覆盖并发推进;截断收在状态服务)
            log.warn("版本生成失败 project={}: {}", projectId, e.getMessage(), e);
            statusService.failVersionsToReady(projectId, e.getMessage());
        }
    }

    private ArticleVersionEntity generateOne(ArticleProjectEntity p, ArticleBriefEntity brief,
                                              StyleProfileEntity style, String label,
                                              CarRagService.RagResult rag) throws Exception {
        boolean imitation = "IMITATION".equals(p.getGenSource());
        // 09-10-style-library-enhance:风格强化句(仿写/主题两分支统一,在 toneGuidance 拼接点后一次插入)
        String styleEnforce = "\n\n以上语气、句式、结构与用词特征必须在正文中充分体现,不得只在部分段落贴合。";
        // 2026-09-10:排版铁律(三处正文生成点统一)——此前仅「用 Markdown」靠模型自觉,
        // 部分模型/风格组合会输出整段长文无小标题无加粗,公众号可读性差
        // 09-27-shared-layout-rules R3:分节档位随项目目标字数自适应(与深度写作同档,共享 LayoutRules);
        // 文案格式仍为三段 bullet 列表,其余两行逐字保留。
        LayoutRules.SectionSpec sec = LayoutRules.sectionSpec(p.getWordCountTarget());
        String layoutRules = "\n\n排版铁律(公众号正文可读性,必须遵守):"
                + "\n- 全文用 " + sec.headings() + " 个「## 小标题」分节,每节 " + sec.parasPerSection() + " 段,禁止整篇无分节;"
                + "\n- 关键数据、核心结论用 **加粗** 突出,每节至少一处;"
                + "\n- 单段不超过 5 行,长段拆分。";
        String sys;
        String user;
        if (imitation) {
            // 文章仿写(09-09-article-imitation):风格指令 + 仿写铁律(保留观点组织/禁照搬/去图)
            sys = (style.getToneGuidance() == null ? "" : style.getToneGuidance())
                    + styleEnforce
                    + "\n\n你是文章仿写专家。基于【参考原文】以指定风格重新表达,铁律:"
                    + "\n1. 保留原文的观点组织与信息脉络,但必须用全新的语言重新表达;"
                    + "\n2. 严禁连续 10 字以上照搬原句;"
                    + "\n3. 不得保留原文任何图片链接、图注、配图说明,正文不得出现任何图片占位或「配图」字样。"
                    + "\n4. 保留原文的分节层次:原文有小标题则仿写文对应位置也用「## 小标题」重新拟写,原文加粗处同样用加粗表达。"
                    + layoutRules
                    + "\n\n只输出 JSON 对象：{\"title\":\"本版标题\",\"contentMd\":\"完整 Markdown 正文\"}。"
                    + "contentMd 内直接写 Markdown，不要包代码块围栏，不要额外说明。所有内容中文。";
            user = buildImitationPrompt(p, brief);
        } else {
            sys = (style.getToneGuidance() == null ? "" : style.getToneGuidance())
                    + styleEnforce
                    + layoutRules
                    + "\n\n只输出 JSON 对象：{\"title\":\"本版标题\",\"contentMd\":\"完整 Markdown 正文\"}。"
                    + "contentMd 内直接写 Markdown，不要包代码块围栏，不要额外说明。所有内容中文。";
            user = buildUserPrompt(p, brief, rag);
        }
        AiClient.ChatResult cr = aiClient.chatJson(sys, user, 4096);
        // AI 输出 JSON 容错:剥围栏+转义字符串内裸控制字符(模型偶发违反 json_object 约束,见 AiClient.sanitizeAiJson)
        var node = json.readTree(AiClient.sanitizeAiJson(cr.content()));
        String title = node.path("title").asText("");
        String contentMd = node.path("contentMd").asText("");
        if (contentMd.isBlank()) throw new AiException("contentMd 为空（可能 max_tokens 不足被截断）", null);

        // 仿写双保险去图(09-09-article-imitation R3):prompt 约束之外,生成后正则二次清洗
        if (imitation) contentMd = stripImages(contentMd);

        ArticleVersionEntity v = new ArticleVersionEntity();
        v.setProjectId(p.getId());
        v.setBriefId(brief.getId());
        v.setTitle(resolveTitle(p, title));
        v.setContentMd(contentMd);
        v.setVersionLabel(label);
        // 09-10-style-library-enhance:风格名手填最长 64 字,版本表 style_tag 列宽仅 VARCHAR(20),超长截断防写库报错阻断生成
        v.setStyleTag(style.getName() != null && style.getName().length() > 20 ? style.getName().substring(0, 20) : style.getName());
        v.setAiModel(cr.model());
        v.setTokenUsage(cr.totalTokens());
        v.setRagStatus(rag.status().name());
        v.setRagCitations(BriefService.citationsJson(rag));
        v.setWordCount(contentMd.length());
        // 仿写:生成后本地相似度自检,结果随版本落库(仅警示不阻断)
        if (imitation && p.getImitationText() != null && !p.getImitationText().isBlank()) {
            try {
                var sim = imitationService.similarityCheck(p.getImitationText(), contentMd);
                v.setSimilarityScore((Double) sim.get("score"));
                v.setSimilarityReport((String) sim.get("report"));
            } catch (Exception ex) {
                log.warn("相似度自检失败 project={} label={}: {}", p.getId(), label, ex.getMessage());
            }
        }
        v.setCreatedAt(LocalDateTime.now());
        return v;
    }

    /**
     * 仿写 user prompt(09-09-article-imitation R3):原文全文 + 结构大纲 + 字数目标。
     * 不注入车型知识库(仿写跳过 RAG)。
     */
    private String buildImitationPrompt(ArticleProjectEntity p, ArticleBriefEntity b) {
        String base = """
                目标字数：%s

                原文分析（结构骨架/核心观点,仿写时保留其组织）：
                - 结构大纲：%s
                - 核心观点：%s

                请基于下方参考原文完整仿写公众号文章正文（Markdown），严格遵循指定风格。
                保留原文的观点组织与信息脉络，但用全新语言表达；不得照搬原句；不得出现任何图片。

                【参考原文】
                %s
                """.formatted(
                p.getWordCountTarget() == null ? "1500" : p.getWordCountTarget(),
                nv(b.getOutline()), nv(b.getCoreViewpoints()),
                p.getImitationText() == null ? "" : p.getImitationText());
        return base;
    }

    /** 剔除 Markdown 图片 ![..](..) 与 HTML <img>(含可能残留的图注/占位行)。 */
    static String stripImages(String md) {
        if (md == null) return "";
        String s = md.replaceAll("(?s)!\\[[^\\]]*\\]\\([^)]*\\)", "");
        s = s.replaceAll("(?s)<img[^>]*/?>", "");
        // 残留的「配图」「图注」占位行整行剔除(仅限以这些词开头的独立行,避免误伤正文)
        s = s.replaceAll("(?m)^\\s*(>\\s*)*(配图|图片|图注|示意图|图片来源)[::：].*$\\n?", "");
        return s;
    }

    private String buildUserPrompt(ArticleProjectEntity p, ArticleBriefEntity b, CarRagService.RagResult rag) {
        String base = """
                主题：%s
                内容描述：%s
                目标读者：%s
                目标字数：%s

                创作简报（基于此展开，标题可从中候选调整）：
                - 标题候选：%s
                - 核心观点：%s
                - 大纲：%s
                - 事实风险点（写作时注意表述，按建议弱化或标注）：%s

                请按大纲完整展开成公众号文章正文（Markdown），严格遵循指定风格。
                """.formatted(
                nv(p.getTopic()), nv(p.getContentDescription()), nv(p.getAudience()),
                p.getWordCountTarget() == null ? "1500" : p.getWordCountTarget(),
                nv(b.getTitleCandidates()), nv(b.getCoreViewpoints()),
                nv(b.getOutline()), nv(b.getFactRisks()));
        // S6:简报阶段用户点选的标题,作为本版标题偏好(优先采用,可微调)
        if (p.getSelectedTitle() != null && !p.getSelectedTitle().isBlank()) {
            base += "\n\n【用户已选定标题,请优先采用该标题作为本版标题(可微调措辞,勿偏离原意)】\n" + p.getSelectedTitle();
        }
        // 10-02:原独立「用户补充信息」块删除(其内容即内容描述,已并入头部「内容描述：」)
        // S6.1 RAG 必查:检索成功且过整体门槛才注入权威数据;失败/低置信降级可见(要求 AI 标注数据风险)
        if (rag.ok()) {
            base += "\n\n【车型知识库权威数据,请严格依据这些数据撰写,不得编造;数据缺失时不要臆造】\n" + rag.context();
            if (rag.coveredText() != null && !rag.coveredText().isBlank()) {
                base += "\n\n【知识库已覆盖参数(仅可引用这些数值,严禁改写/换算/脑补其他数)】" + rag.coveredText();
                base += "\n【覆盖度约束】上述清单之外的具体参数数值知识库未覆盖,正文中禁止出现具体数值——用定性表述,并在文末提示「详细参数以官方发布为准」。";
            }
        } else if (rag.status() == CarRagService.RagStatus.FAILED) {
            base += "\n\n【知识库检索提示】车型知识库本次检索失败,你未能获得权威数据。涉及车型参数/权益的表述不得给出具体数值,应以定性表述为主并在文末附「参数请以官方发布为准」提示。";
        } else if (rag.status() == CarRagService.RagStatus.LOW_CONFIDENCE) {
            base += "\n\n【知识库检索提示】车型知识库有数据但与主题相关性过低(最高相似度 " + String.format("%.2f", rag.maxScore())
                    + ",低于可信门槛),已全部抛弃,不要参考。涉及车型参数/权益的表述不得给出具体数值,应以定性表述为主并在文末附「参数请以官方发布为准」提示。";
        }
        // NO_KNOWLEDGE:无车型对象或无命中,与现状一致,不注入不提示
        return base;
    }

    /** 列出项目全部版本（按创建序）。 */
    public List<ArticleVersionEntity> list(Long projectId) {
        return versionMapper.selectList(new QueryWrapper<ArticleVersionEntity>()
                .eq("project_id", projectId).orderByAsc("id"));
    }

    /** 设定当前版本。 */
    public void setCurrent(Long projectId, Long versionId) {
        ArticleProjectEntity p = projectMapper.selectById(projectId);
        if (p == null) throw new IllegalArgumentException("项目不存在");
        ArticleVersionEntity v = versionMapper.selectById(versionId);
        if (v == null || !v.getProjectId().equals(projectId))
            throw new IllegalArgumentException("版本不存在或不属于该项目");
        // 单列显式 set:避免 updateById 全字段覆盖并发写入的状态列
        projectMapper.update(null, new UpdateWrapper<ArticleProjectEntity>()
                .eq("id", projectId)
                .set("current_version_id", versionId)
                .set("updated_at", LocalDateTime.now()));
    }

    /**
     * 保存版本正文(S4 预览页左栏编辑)。仅更新 contentMd;字数上限随 brief 生成口径,这里只防御超长。
     */
    public void updateContent(Long projectId, Long versionId, String contentMd) {
        if (contentMd == null || contentMd.isBlank()) throw new IllegalArgumentException("正文不能为空");
        if (contentMd.length() > 200_000) throw new IllegalArgumentException("正文过长(上限 20 万字符)");
        ArticleVersionEntity v = versionMapper.selectById(versionId);
        if (v == null || !v.getProjectId().equals(projectId))
            throw new IllegalArgumentException("版本不存在或不属于该项目");
        v.setContentMd(contentMd);
        versionMapper.updateById(v);
    }

    /**
     * 编辑版本标题(S6)。仅更新 title;空串清除。
     */
    public void updateTitle(Long projectId, Long versionId, String title) {
        if (title != null && title.length() > 200) throw new IllegalArgumentException("标题不能超过 200 字");
        ArticleVersionEntity v = versionMapper.selectById(versionId);
        if (v == null || !v.getProjectId().equals(projectId))
            throw new IllegalArgumentException("版本不存在或不属于该项目");
        v.setTitle(title == null || title.isBlank() ? null : title);
        versionMapper.updateById(v);
    }

    /**
     * 版本标题优先级(与深度链路 DeepWriterService.resolveTitle 同口径):
     * 用户选定标题(裁剪 200) > AI 产出标题 > 项目主题。
     * 仅仿写链路可达(主题创作多版本接口已封死走深度生成)。
     */
    private static String resolveTitle(ArticleProjectEntity p, String aiTitle) {
        String selected = p.getSelectedTitle();
        if (selected != null && !selected.isBlank()) {
            String s = selected.trim();
            return s.length() > 200 ? s.substring(0, 200) : s;
        }
        return aiTitle == null || aiTitle.isBlank() ? p.getTopic() : aiTitle;
    }

    private static String nv(String s) { return s == null || s.isBlank() ? "未指定" : s; }
}
