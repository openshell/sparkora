package com.sparkora.deep.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.sparkora.service.LayoutRules;
import com.sparkora.service.MetaLeakCleaner;
import com.sparkora.service.ReaderViewRules;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 深度写作与数值校验(S9 ⑤⑥):
 * ⑤ 写作:风格画像 + 事实手册 + 锁定需求 → 正文;约束「所有数值必须出自事实手册」。
 * ⑥ 数值回查:正则抽取正文数值,与手册比对;未收录 → factRisks(high) 随版本落库。
 * 产物复用 version 表(gen_mode=DEEP 标记在 brief 侧)。
 *
 * 09-27-gen-async 批量异步化(对齐 ClarifyService 范式):
 *  原「单风格单版」同步调用改为 {@link #startBatch} 一次触发多风格:
 *  同步毫秒级:校验 brief/风格 + claim GENERATING_VERSIONS + self.runBatch + 返回占位;
 *  @Async runBatch 循环 {@link #write} → 汇总 advanceVersionsReady / 失败 failVersionsToReady。
 * 批量改造根因:前端串行多次调用会撞第 2 次 claim 的 409;批量也顺带简化前端编排。
 *
 * 10-02-fix-meta-leak-in-article-body(内部元话语泄漏三层防线,prompt 侧共用契约见 {@link ReaderViewRules}):
 *  R1 简报 fact_risks 只抽 claim 注入「禁止写入正文的断言」块({@link ReaderViewRules#forbiddenClaimsBlock}),
 *     写给作者的 suggestion 祈使句不再进素材区(线上 version 44 实测泄漏源);
 *  R2 system 追加 {@link ReaderViewRules#READER_RULES} 读者视角铁律(与 VersionService 主题分支共用文本);
 *  R3 落库前经 {@link MetaLeakCleaner} 句级清洗兜底,位置在 ⑥ 数值回查之前。
 */
@Slf4j
@Service
public class DeepWriterService {

    private final AiClient aiClient;
    private final ObjectMapper json;
    private final ArticleBriefMapper briefMapper;
    private final ArticleVersionMapper versionMapper;
    /** 项目 mapper(09-10-versions-page-fix:title 回退 project.topic 需取项目) */
    private final ArticleProjectMapper projectMapper;
    /** 系统检索设置(09-09-brief-gen-redesign R3):知识库停用时 rag_status=DISABLED */
    private final com.sparkora.service.SettingService settingService;
    /** 风格表回查(09-27-gen-async 批量:由 service 按 styleIds 解析 toneGuidance/name) */
    private final StyleProfileMapper styleMapper;
    /** 项目状态机唯一写权持有者(批量链路的抢占/推进/回退)。 */
    private final com.sparkora.service.ProjectStatusService statusService;

    // 自注入代理,确保 @Async 生效(startBatch 内 this.runBatch 不会走代理)
    @Autowired
    @Lazy
    private DeepWriterService self;

    public DeepWriterService(AiClient aiClient, ObjectMapper json,
                             ArticleBriefMapper briefMapper, ArticleVersionMapper versionMapper,
                             ArticleProjectMapper projectMapper,
                             com.sparkora.service.SettingService settingService,
                             StyleProfileMapper styleMapper,
                             com.sparkora.service.ProjectStatusService statusService) {
        this.aiClient = aiClient;
        this.json = json;
        this.briefMapper = briefMapper;
        this.versionMapper = versionMapper;
        this.projectMapper = projectMapper;
        this.settingService = settingService;
        this.styleMapper = styleMapper;
        this.statusService = statusService;
    }

    /** 版本标签序列(与 VersionService.LABELS 同口径:A/B/C…按项目内已有版本数续编) */
    private static final String LABELS = "ABCDEFGHIJ";

    /** 09-10-style-library-enhance:风格强化句(与 VersionService.generateOne 同款文案,要求特征充分体现)。
     *  C1:文案外置模板 prompts/shared/style-enforce.st,两链路同源读取。 */
    private static final String STYLE_ENFORCE =
            com.sparkora.ai.PromptTemplateLoader.render("shared/style-enforce.st", java.util.Map.of());

    /** 批量生成单条风格选择(已解析:prompt/name 直接喂 {@link #write};legacy 路径 name/prompt 可为空)。 */
    public record StyleSpec(String prompt, String name) {}

    /**
     * 09-27-deep-writing-adaptive-sections R2:排版铁律「节数行」(随目标字数自适应);
     * 其余排版铁律(加粗/单段行数/禁止整篇无分节)保留不变,由调用方拼接。
     *
     * <p>09-27-shared-layout-rules R2:分档分类委托共享 {@link com.sparkora.service.LayoutRules},
     * 输出文案(单行分号串)逐字不变,行为零回归。
     */
    static String layoutRule(Integer targetWords) {
        com.sparkora.service.LayoutRules.SectionSpec s =
                com.sparkora.service.LayoutRules.sectionSpec(targetWords);
        return "排版铁律(公众号正文可读性,必须遵守):全文用 " + s.headings()
                + " 个「## 小标题」分节,每节 " + s.parasPerSection() + " 段,禁止整篇无分节;\n";
    }

    /**
     * 启动批量深度写作(同步毫秒级,占位语义):校验 brief/风格 + 原子抢占 GENERATING_VERSIONS 后立即返回,
     * 后台 self.runBatch 逐风格生成;前端靠项目状态轮询(GENERATING_VERSIONS→VERSIONS_READY)翻转刷新。
     *
     * @param styleIds 用户选中的风格 id 列表;空/缺省时按「无风格」生成一版(默认 tag「深度」)
     * @return 占位标记 {status:"GENERATING_VERSIONS", styleCount:N}
     */
    public Map<String, Object> startBatch(Long projectId, Long briefId, List<Long> styleIds) {
        ArticleBriefEntity b = requireBrief(projectId, briefId);
        List<StyleSpec> specs = new ArrayList<>();
        if (styleIds != null && !styleIds.isEmpty()) {
            if (styleIds.size() > LABELS.length())
                throw new IllegalArgumentException("一次最多生成 " + LABELS.length() + " 版");
            // 逐 id 回查并保序:用户显式选了风格,查无不静默降级(沿用原 /deep/generate 单风格口径)
            List<StyleProfileEntity> styles = styleMapper.selectBatchIds(styleIds);
            Map<Long, StyleProfileEntity> byId = new LinkedHashMap<>();
            for (StyleProfileEntity s : styles) byId.put(s.getId(), s);
            for (Long id : styleIds) {
                StyleProfileEntity s = byId.get(id);
                if (s == null) throw new IllegalArgumentException("风格不存在或已删除");
                specs.add(new StyleSpec(s.getToneGuidance() == null ? "" : s.getToneGuidance(), s.getName()));
            }
        } else {
            specs.add(new StyleSpec("", ""));   // 无风格:单版默认(style_tag 回退「深度」)
        }
        return startWithSpecs(projectId, b, specs);
    }

    /**
     * deprecated 兼容入口:旧前端直接传风格画像字符串(stylePrompt/styleName),已无前端调用;
     * 与 {@link #startBatch} 共用同一 claim/异步编排。
     */
    public Map<String, Object> startBatchLegacy(Long projectId, Long briefId, String stylePrompt, String styleName) {
        ArticleBriefEntity b = requireBrief(projectId, briefId);
        StyleSpec spec = new StyleSpec(stylePrompt == null ? "" : stylePrompt, styleName == null ? "" : styleName);
        return startWithSpecs(projectId, b, List.of(spec));
    }

    /** 公共编排:项目校验 + 陈旧判定 + 原子抢占 + 触发异步 + 返回占位。 */
    private Map<String, Object> startWithSpecs(Long projectId, ArticleBriefEntity b, List<StyleSpec> specs) {
        ArticleProjectEntity p = projectMapper.selectById(projectId);
        if (p == null) throw new IllegalArgumentException("项目不存在");
        if (statusService.stuckGenerating(p)) {
            throw new IllegalStateException("该项目正在生成中，请稍候（刷新页面可查看进度）");
        }
        // 原子抢占:深度源态 READY/DRAFT/VERSIONS_READY 或陈旧生成中;claimed==0 抛 409
        statusService.claimDeepVersionsGenerating(projectId, p, "生成版本");
        // 后台异步生成(经自注入代理确保 @Async 生效)
        (self == null ? this : self).runBatch(projectId, b.getId(), specs);
        return Map.of("status", "GENERATING_VERSIONS", "styleCount", specs.size());
    }

    private ArticleBriefEntity requireBrief(Long projectId, Long briefId) {
        ArticleBriefEntity b = briefMapper.selectById(briefId);
        if (b == null) throw new IllegalArgumentException("brief 不存在");
        if (projectId != null && !projectId.equals(b.getProjectId()))
            throw new IllegalArgumentException("brief 不属于该项目");
        return b;
    }

    /**
     * 异步批量写作(由 self 代理调用):逐风格 {@link #write} 落版本,汇总部分失败;
     * 全部失败 → failVersionsToReady 落 last_version_error;成功 → advanceVersionsReady(firstId, partialErrors)。
     * 异步线程无调用方,顶层 catch 必调 fail* 回写(避免卡 GENERATING_VERSIONS 到 10min 自愈)。
     */
    @Async
    public void runBatch(Long projectId, Long briefId, List<StyleSpec> specs) {
        List<Long> created = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        try {
            int i = 0;
            for (StyleSpec spec : specs) {
                String label = i < LABELS.length() ? String.valueOf(LABELS.charAt(i)) : "A";
                i++;
                String name = spec.name() == null || spec.name().isBlank() ? "深度" : spec.name();
                try {
                    created.add(write(projectId, briefId, spec.prompt(), spec.name()));
                } catch (Exception e) {
                    log.warn("深度版本 {}({}) 生成失败 project={}: {}", label, name, projectId, e.getMessage());
                    errors.add("[" + label + ":" + name + "] " + e.getMessage());
                }
            }
            if (created.isEmpty()) {
                throw new AiException("全部版本生成失败: " + String.join("; ", errors), null);
            }
            String partial = errors.isEmpty() ? null : "部分版本失败: " + String.join("; ", errors);
            statusService.advanceVersionsReady(projectId, created.get(0), partial);
            log.info("深度批量生成完成 project={} created={} failed={}", projectId, created.size(), errors.size());
        } catch (Exception e) {
            // 异步体无调用方,异常只落状态。失败回退(仅生成中状态,防覆盖并发推进;截断收在状态服务)
            log.warn("深度批量生成失败 project={}: {}", projectId, e.getMessage(), e);
            statusService.failVersionsToReady(projectId, e.getMessage());
        }
    }

    /**
     * ⑤ 深度写作并落版本(⑥ 回查结果进 factRisks)。
     * @param briefId  含 fact_sheet 的 brief
     * @param stylePrompt 风格画像 toneGuidance(由 {@link #startBatch} 按 styleIds 回查后注入)
     * @param styleName 风格名(落版本 style_tag;空回退「深度」)
     * @return 落库的版本 id
     */
    public Long write(Long projectId, Long briefId, String stylePrompt, String styleName) throws Exception {
        ArticleBriefEntity b = briefMapper.selectById(briefId);
        if (b == null) throw new IllegalArgumentException("brief 不存在");
        // 09-27-deep-writing-adaptive-sections:取一次项目快照(wordCountTarget 注入 + title 回退复用),
        // 避免为取目标字数额外新增查询(原 extractH1 内部自有一次,改造后复用本快照)。
        ArticleProjectEntity p = null;
        try {
            p = projectMapper.selectById(projectId);
        } catch (Exception e) {
            log.warn("取项目快照失败 projectId={}: {}", projectId, e.getMessage());
        }
        JsonNode sheet = json.readTree(b.getFactSheet() == null ? "{}" : b.getFactSheet());
        // R5(09-27-tavily-extract-kind-hypotheses):手册条目带 kind 时按「参数事实 / 背景素材」分组呈现;
        // 全无 kind(历史 fact_sheet)时退化为原平铺行为(prompt 与旧实现逐字等价)。
        boolean hasKind = hasKind(sheet.path("entries"));
        StringBuilder factCtx = buildFactContext(sheet.path("entries"), hasKind);
        // R2(09-27-deep-writing-adaptive-sections):排版铁律「节数行」按目标字数自适应;其余铁律逐字保留。
        // C1:固定指令文字块外置为模板(prompts/deep/write-system-*.st 与 shared/reader-rules.st),
        // 动态的 [[layoutRule]] 与 [[hasKind]] 分支仍由 Java 控制,拼接结构逐字不变。
        String system = com.sparkora.ai.PromptTemplateLoader.render("deep/write-system-base.st", java.util.Map.of())
                + (hasKind
                    ? com.sparkora.ai.PromptTemplateLoader.render("deep/write-system-kind.st", java.util.Map.of())
                    : "")
                + layoutRule(p == null ? null : p.getWordCountTarget())
                + com.sparkora.ai.PromptTemplateLoader.render("deep/write-system-layout.st", java.util.Map.of())
                + com.sparkora.ai.PromptTemplateLoader.render("shared/reader-rules.st", java.util.Map.of()) + "\n";
        // 09-10-style-library-enhance:风格指令从 user prompt 迁入 system prompt(与仿写链路统一注入位置)
        if (stylePrompt != null && !stylePrompt.isBlank()) {
            system = system + "\n文风要求:\n" + stylePrompt + "\n" + STYLE_ENFORCE;
        }
        // R1(09-27-deep-writing-adaptive-sections):注入目标字数(null/≤0 → 1500,口径对齐 VersionService)。
        // 09-27-shared-layout-rules R2:默认值归一委托共享 LayoutRules(输出值不变)。
        int target = LayoutRules.normalizeTarget(p == null ? null : p.getWordCountTarget());
        StringBuilder user = new StringBuilder("目标字数：").append(target).append('\n');
        // 10-02 R4:创建输入注入写作 prompt(非空才加)
        if (p != null && p.getAudience() != null && !p.getAudience().isBlank()) {
            user.append("目标读者:").append(p.getAudience()).append('\n');
        }
        if (p != null && p.getContentDescription() != null && !p.getContentDescription().isBlank()) {
            user.append("内容描述:").append(p.getContentDescription()).append('\n');
        }
        user.append("事实手册(数值唯一来源):\n").append(factCtx).append('\n');
        if (b.getClarifyAnswers() != null && !b.getClarifyAnswers().isBlank()) {
            user.append("用户锁定需求:\n").append(b.getClarifyAnswers()).append('\n');
        }
        // R1(09-27-brief-writing-linkage-fix):简报字段显式注入写作 prompt——简报是唯一结构化中间件,
        // 此前 titleCandidates/coreViewpoints/outline/factRisks 在写作阶段零引用(仅末尾一句空指引)。
        // 历史 brief 字段缺失/为空 → 跳过对应块,prompt 退化为旧行为(不报错不阻断)。
        appendBriefSection(user, "标题候选", b.getTitleCandidates(), true);
        // S6 选定标题:项目级 selected_title 非空时注入(与 VersionService 既有措辞同源);
        // 为空的历史项目不追加,prompt 与旧行为逐字等价。
        if (p != null && p.getSelectedTitle() != null && !p.getSelectedTitle().isBlank()) {
            user.append("\n【用户已选定标题,正文一级标题(#)请采用该标题,勿偏离原意】\n")
                .append(p.getSelectedTitle()).append('\n');
        }
        appendBriefSection(user, "核心观点", b.getCoreViewpoints(), true);
        appendBriefSection(user, "大纲", b.getOutline(), false);
        // R1(10-02-fix-meta-leak-in-article-body):原「事实风险:」整块注入改为「禁止写入正文的断言」——
        // fact_risks[].suggestion 是写给作者的祈使句,进正文素材区必被复述/改写成读者话术(线上 version 44 实测);
        // 只抽陈述性的 claim 作禁写清单,保留风险防护价值。
        String forbidden = ReaderViewRules.forbiddenClaimsBlock(b.getFactRisks());
        if (forbidden != null) user.append(forbidden);
        // 末尾指引句与旧实现逐字一致:空字段 brief 的 prompt 与旧行为等价(AC-01);有字段时其内容已在上方列出
        user.append("主题与大纲参考 brief(标题候选/核心观点/大纲),直接写正文 Markdown。");
        // R4(09-27-brief-writing-linkage-fix):正文是全链路最长输出,对齐 R4/R6 范式——
        // 首次 4096;截断(finish_reason=length)或异常提额 8192 重试一次,仅两次均失败才抛。
        // 重试只包裹 AI 调用,版本 insert 仍只执行一次(下方落库逻辑不动)。
        AiClient.ChatResult cr;
        try {
            cr = aiClient.chat(system, user.toString(), 4096);
            if ("length".equals(cr.finishReason())) {
                throw new AiException("AI 输出被 max_tokens 截断(正文)", null);
            }
        } catch (Exception first) {
            log.warn("深度写作首次失败,提额重试(8192) briefId={}: {}", briefId, first.getMessage());
            cr = aiClient.chat(system,
                    user + "\n注意:上次输出被截断,请输出完整正文。", 8192);
            if ("length".equals(cr.finishReason())) {
                throw new AiException("AI 输出两次均被 max_tokens 截断(正文)", first);
            }
        }
        String content = cr.content();
        // R3(10-02-fix-meta-leak-in-article-body):落库前确定性清洗内部元话语(句级删除,见 MetaLeakCleaner)。
        // 位置在 ⑥ 数值回查之前:已删句不再参与数值比对,避免为「无法计算完成率」这类句子误报 high 风险。
        // cleanForPersist:整篇皆为元话语(清洗致空)时回退原文,不落空正文。
        MetaLeakCleaner.CleanResult cleaned = MetaLeakCleaner.cleanForPersist(content);
        if (!cleaned.removed().isEmpty()) {
            log.warn("深度写作清洗正文内部元话语 briefId={} 删句数={} 清洗后无正文回退原文={} 原文={}",
                    briefId, cleaned.removed().size(), cleaned.content().equals(cr.content()), cleaned.removed());
        }
        content = cleaned.content();

        // ⑥ 数值回查
        List<String> unknown = verifyNumbers(content, b.getFactSheet());
        String factRisks;
        if (unknown.isEmpty()) {
            factRisks = "[]";
        } else {
            List<Map<String, Object>> risks = new ArrayList<>();
            for (String u : unknown) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("claim", "正文数值「" + u + "」未收录于事实手册");
                r.put("riskLevel", "high");
                r.put("suggestion", "该数值无事实手册出处,发布前必须人工核实或删除");
                risks.add(r);
            }
            factRisks = json.writeValueAsString(risks);
            log.warn("数值回查发现未收录数值 briefId={} unknown={}", briefId, unknown);
        }

        var v = new com.sparkora.domain.entity.ArticleVersionEntity();
        v.setProjectId(projectId);
        v.setBriefId(briefId);
        // 检索状态(09-09-brief-gen-redesign R3):知识库停用(全局设置)时标 DISABLED,不与 NO_KNOWLEDGE 混淆;
        // 启用时深度写作阶段的本地检索语义维持 OK(fact_sheet 即引用来源)
        v.setRagStatus(settingService.isKbEnabled() ? "OK" : "DISABLED");
        v.setFactRisks(factRisks);
        v.setAiModel(cr.model());
        v.setTokenUsage(cr.totalTokens());
        v.setContentMd(content);
        // 09-10-versions-page-fix:深度链路此前漏填 title/version_label/style_tag/word_count,
        // 与多版本链路(VersionService.generateOne)对齐补齐,消除版本页 undefined/null 与字数统计为空
        v.setTitle(resolveTitle(p == null ? null : p.getSelectedTitle(), content, p == null ? null : p.getTopic()));
        v.setVersionLabel(nextLabel(projectId));
        // style_tag 列 VARCHAR(20),超长截断防御(PG 超长 insert 直接报错会阻断整次生成)
        String tag = styleName == null || styleName.isBlank() ? "深度" : styleName;
        v.setStyleTag(tag.length() > 20 ? tag.substring(0, 20) : tag);
        v.setWordCount(content.length());
        v.setCreatedAt(LocalDateTime.now());
        versionMapper.insert(v);
        return v.getId();
    }

    /**
     * R5(09-27-tavily-extract-kind-hypotheses):按手册条目 kind 分组构造写作事实上下文。
     *
     * <p>条目带 {@code kind} 时分为「参数事实」(kind=param,可逐字引用数值)与「背景素材」
     * (kind=background,仅叙事、不得据此新增数值)两段;全无 kind(历史 fact_sheet)时
     * 退化为原平铺行为——逐字与旧实现一致(AC-05)。
     *
     * <p>逐条仍保留「证据:{snippet}」透传(09-26 R1 降级保真)。
     */
    private static StringBuilder buildFactContext(JsonNode entries, boolean anyKind) {
        StringBuilder out = new StringBuilder();
        if (!anyKind) {
            // 历史手册:平铺(与旧实现逐字等价,含末尾换行)
            for (JsonNode e : entries) out.append(factLine(e));
            return out;
        }
        StringBuilder params = new StringBuilder();
        StringBuilder backgrounds = new StringBuilder();
        for (JsonNode e : entries) {
            String kind = e.path("kind").asText("");
            if ("background".equals(kind)) backgrounds.append(factLine(e));
            else params.append(factLine(e));   // 缺 kind/param 兜底进参数组
        }
        out.append("【参数事实】(可逐字引用数值):\n");
        out.append(params.length() == 0 ? "- (无)\n" : params);
        out.append("【背景素材】(仅用于叙事,不得据此新增数值):\n");
        out.append(backgrounds.length() == 0 ? "- (无)\n" : backgrounds);
        return out;
    }

    /** 手册条目是否带 kind(任一条非空即视为带分类)。 */
    private static boolean hasKind(JsonNode entries) {
        for (JsonNode e : entries) {
            if (!e.path("kind").asText("").isBlank()) return true;
        }
        return false;
    }

    /** 单条手册条目行(参数事实/背景素材共用;保持旧平铺格式逐字一致)。 */
    private static String factLine(JsonNode e) {
        StringBuilder line = new StringBuilder();
        line.append("- ").append(e.path("key").asText());
        String v = e.path("value").asText("");
        if (!v.isBlank()) line.append(" = ").append(v);
        double c = e.path("confidence").asDouble(0);
        line.append("(置信 ").append(String.format("%.2f", c)).append(")");
        // R1(09-26):条目可带降级保留的原始 snippet 证据(背景/来龙去脉素材),写作阶段可见
        String snip = e.path("snippet").asText("");
        if (!snip.isBlank()) {
            line.append(" | 证据:").append(snip.length() > 200 ? snip.substring(0, 200) : snip);
        }
        line.append('\n');
        return line.toString();
    }

    /**
     * R1(09-27-brief-writing-linkage-fix):把简报 JSON 字段块追加进写作 prompt。
     *
     * <p>规则:
     * <ul>
     *   <li>null/空白/{@code "[]"}/{@code "{}"} → 跳过(历史 brief 无字段时 prompt 与旧行为等价);</li>
     *   <li>{@code asArray=true}:解析为数组则逐项 {@code - } 列出(元素为对象时 toString);
     *       解析失败或非数组 → 原样追加(不丢信息);</li>
     *   <li>{@code asArray=false}(outline):解析成功 → toString 追加(结构未知,不强解);失败 → 原样。</li>
     * </ul>
     * 全程 try/catch 仅 warn,绝不因简报字段异常阻断正文生成。
     */
    private void appendBriefSection(StringBuilder sb, String label, String jsonText, boolean asArray) {
        if (jsonText == null || jsonText.isBlank()
                || "[]".equals(jsonText.trim()) || "{}".equals(jsonText.trim())) {
            return;
        }
        try {
            boolean parsed = false;
            if (asArray) {
                JsonNode node = json.readTree(jsonText);
                if (node != null && node.isArray()) {
                    sb.append(label).append(":\n");
                    for (JsonNode item : node) {
                        sb.append("- ").append(item.isTextual() ? item.asText() : item.toString()).append('\n');
                    }
                    parsed = true;
                }
            } else {
                JsonNode node = json.readTree(jsonText);
                if (node != null) {
                    sb.append(label).append(":\n").append(node.toString()).append('\n');
                    parsed = true;
                }
            }
            // 非数组/解析未产出结构:按原文追加(仅在此处补块头,避免解析失败时块头重复)
            if (!parsed) sb.append(label).append(":\n").append(jsonText.trim()).append('\n');
        } catch (Exception e) {
            log.warn("简报字段「{}」注入写作 prompt 失败,按原文追加: {}", label, e.getMessage());
            sb.append(label).append(":\n").append(jsonText.trim()).append('\n');
        }
    }

    /**
     * S6 版本标题优先级:用户选定标题(裁剪 200) > 正文首个 H1 > 项目主题。
     * selected_title 非空时确定性采用,保证 version → preview → publish 全链路标题一致;
     * 为空时行为与旧 extractH1 回退语义完全一致。
     */
    private String resolveTitle(String selectedTitle, String contentMd, String topic) {
        if (selectedTitle != null && !selectedTitle.isBlank()) {
            String s = selectedTitle.trim();
            return s.length() > 200 ? s.substring(0, 200) : s;
        }
        return extractH1(topic, contentMd);
    }

    /**
     * 09-10-versions-page-fix:抽取 AI 正文首个 Markdown H1 作为版本 title。
     * 正则多行首匹配「# 标题」;缺失/空白回退 project.topic(由 {@link #write} 传入的项目快照,不重复查询)。
     */
    private String extractH1(String topic, String contentMd) {
        if (contentMd != null) {
            var m = Pattern.compile("(?m)^#\\s+(.+)$").matcher(contentMd);
            if (m.find()) {
                String h1 = m.group(1).trim();
                if (!h1.isBlank()) {
                    // title 列 VARCHAR(200),防御性截断(仿写链路 title 同列)
                    return h1.length() > 200 ? h1.substring(0, 200) : h1;
                }
            }
        }
        return topic;
    }

    /**
     * 09-10-versions-page-fix:按项目内已有版本数取下一版本标签(A/B/C…,同 VersionService 编号口径);
     * 超出 LABELS 长度回退 'A'。project 查询异常时不阻断生成,回退 'A'。
     */
    private String nextLabel(Long projectId) {
        int idx;
        try {
            idx = Math.toIntExact(versionMapper.selectCount(new QueryWrapper<ArticleVersionEntity>()
                    .eq("project_id", projectId)));
        } catch (Exception e) {
            log.warn("统计项目版本数失败 projectId={}: {}", projectId, e.getMessage());
            idx = 0;
        }
        return idx < LABELS.length() ? String.valueOf(LABELS.charAt(idx)) : "A";
    }

    /**
     * 抽取正文数值并比对手册。C7(10-02-c7-metaleak-numbers):收录判定由「子串 contains」
     * 改为「数值签名归一化集合比对」——复用 {@link ClaimSimilarity#numberValues} 的同一套归一口径
     * (去千分位/空白、万×10000/亿×1e8、{@link java.math.BigDecimal#stripTrailingZeros()})。
     *
     * <p>修复两类偏差:
     * <ul>
     *   <li>漏报:内容 {@code 1200}、手册仅 {@code 12000} → 旧子串 {@code contains("1200")} 命中
     *       {@code "12000"} 而漏报;归一后 {@code 1200 ∉ {12000}} → 报 high;</li>
     *   <li>误报:内容 {@code 200000}、手册 {@code 20万}(或内容 {@code 33.21%}、手册 {@code 33.21%})
     *       → 旧子串失配而误报;归一后两侧同签名 → 不报。</li>
     * </ul>
     *
     * <p>手册为 null/{@code {}}(无手册)→ {@code known} 为空 → 所有数值报 high(与既有语义一致);
     * 归一解析失败由 {@link ClaimSimilarity#numberValues} 内部回退原 token、绝不抛出。
     * unknown 保留原始 token 形态,供 {@code factRisks.claim} 文案原样引用。
     */
    List<String> verifyNumbers(String content, String factSheetJson) throws Exception {
        JsonNode sheet = json.readTree(factSheetJson == null ? "{}" : factSheetJson);
        // 手册全文数值签名集合(与 claim 归并链路同一套归一口径)
        Set<String> known = new HashSet<>(ClaimSimilarity.numberValues(sheet.toString()));
        List<String> unknown = new ArrayList<>();
        // 数值形态:C7 与 numberValues 口径对齐——
        //  1) 「N万/N亿」(含小数,数量级单位);2) 千分位整数「1,200,000」;3) ≥4 位纯数字(上限放开防截断);
        //  4) 小数/百分比;5) 带长度单位的数值;排除年份与孤立 0-9 单字符。
        // 注:千分位须在纯数字前匹配,否则 239,900 会被拆成 239/900 两个 token 漏判。
        var m = java.util.regex.Pattern.compile("\\d[\\d,\\.]*\\s*(?:万|亿)|\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d{4,}(?:\\.\\d+)?|\\d+\\.(?:\\d+)?%?|\\d+(?:\\.\\d+)?\\s*(?:km|kWh|kW|mm|L/100km|s)").matcher(content);
        while (m.find()) {
            String raw = m.group().trim();
            String num = raw.replaceAll("[ ,万]", "");
            if (num.length() < 2 || "0".equals(num)) continue;
            // C7:归一化签名比对(替代旧 haystack.contains 子串匹配,消除 1200⊂12000 漏报)
            String canon = canonicalNumber(raw);
            if (canon == null || !known.contains(canon)) {
                if (!unknown.contains(raw)) unknown.add(raw);
            }
        }
        return unknown;
    }

    /** 复用 {@link ClaimSimilarity} 归一口径:返回该 token 的首个数值签名;无数字→null。 */
    static String canonicalNumber(String raw) {
        List<String> v = ClaimSimilarity.numberValues(raw);
        return v.isEmpty() ? null : v.get(0);
    }
}
