package com.sparkora.deep.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.ai.AiClient;
import com.sparkora.domain.entity.ArticleBriefEntity;
import com.sparkora.domain.entity.ArticleProjectEntity;
import com.sparkora.domain.entity.ArticleVersionEntity;
import com.sparkora.mapper.ArticleBriefMapper;
import com.sparkora.mapper.ArticleProjectMapper;
import com.sparkora.mapper.ArticleVersionMapper;
import com.sparkora.mapper.StyleProfileMapper;
import com.sparkora.service.ProjectStatusService;
import com.sparkora.service.SettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DeepWriterService 简报字段注入写作 prompt 单测(09-27-brief-writing-linkage-fix R1/AC-01)。
 *
 * <p>断言:含简报四字段的 brief → user prompt 实质包含这些内容;
 * 字段为空的 brief → 不出现对应块,且不抛异常(历史 brief 兼容)。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeepWriterServicePromptTest {

    private static final Long PROJECT_ID = 1L;
    private static final Long BRIEF_ID = 2L;

    @Mock AiClient aiClient;
    @Mock ArticleBriefMapper briefMapper;
    @Mock ArticleVersionMapper versionMapper;
    @Mock ArticleProjectMapper projectMapper;
    @Mock SettingService settingService;
    @Mock StyleProfileMapper styleMapper;
    @Mock ProjectStatusService statusService;

    DeepWriterService service;

    @BeforeEach
    void setUp() {
        service = new DeepWriterService(aiClient, new ObjectMapper(), briefMapper, versionMapper,
                projectMapper, settingService, styleMapper, statusService);
        when(settingService.isKbEnabled()).thenReturn(true);
        when(aiClient.chat(anyString(), anyString(), anyInt()))
                .thenReturn(new AiClient.ChatResult("正文内容", "m", 10));
    }

    private ArticleBriefEntity brief() {
        ArticleBriefEntity b = new ArticleBriefEntity();
        b.setId(BRIEF_ID);
        b.setProjectId(PROJECT_ID);
        b.setGenMode("DEEP");
        b.setFactSheet("{\"entries\":[{\"key\":\"价格\",\"value\":\"239900\",\"confidence\":0.9}]}");
        return b;
    }

    /** 捕获 write 发出的 user prompt(首次成功的 4096 调用)。 */
    private String capturedUserPrompt() throws Exception {
        service.write(PROJECT_ID, BRIEF_ID, "", "深度");
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(aiClient).chat(anyString(), user.capture(), eq(4096));
        return user.getValue();
    }

    @Test
    void 简报字段为空_退化为旧prompt且不抛异常() throws Exception {
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());   // 四字段均 null

        String prompt = capturedUserPrompt();

        assertFalse(prompt.contains("标题候选:"), "空字段不得出现标题候选块");
        assertFalse(prompt.contains("核心观点:"), "空字段不得出现核心观点块");
        assertFalse(prompt.contains("大纲:"), "空字段不得出现大纲块");
        assertFalse(prompt.contains(FORBIDDEN_HEADER), "空字段不得出现禁写断言块");
        // C4 降级模式(无蓝图):整本手册仍在(旧行为保留)
        assertTrue(prompt.contains("事实手册(数值唯一来源):"));
    }

    // ==================== R5(09-27-tavily-extract-kind-hypotheses):按 kind 分组 ====================

    /** 有 kind → 分「参数事实」「背景素材」两段,条目归属正确。 */
    @Test
    void 有kind_分参数事实与背景素材两段() throws Exception {
        ArticleBriefEntity b = new ArticleBriefEntity();
        b.setId(BRIEF_ID);
        b.setProjectId(PROJECT_ID);
        b.setGenMode("DEEP");
        b.setFactSheet("{\"entries\":["
                + "{\"key\":\"价格\",\"value\":\"239900\",\"kind\":\"param\",\"confidence\":0.9},"
                + "{\"key\":\"行业背景\",\"value\":\"\",\"kind\":\"background\",\"confidence\":0.4}"
                + "]}");
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(b);

        String prompt = capturedUserPrompt();

        assertTrue(prompt.contains("【参数事实】"), "应出现参数事实段");
        assertTrue(prompt.contains("【背景素材】"), "应出现背景素材段");
        // 归属:价格在参数段、行业背景在背景段
        assertTrue(prompt.contains("价格 = 239900"), "参数条目应在参数段");
        assertTrue(prompt.contains("行业背景"), "背景条目应出现");
        int paramIdx = prompt.indexOf("【参数事实】");
        int bgIdx = prompt.indexOf("【背景素材】");
        int priceIdx = prompt.indexOf("价格 = 239900");
        int bgItemIdx = prompt.indexOf("行业背景");
        assertTrue(priceIdx > paramIdx && priceIdx < bgIdx, "价格应落在参数段内");
        assertTrue(bgItemIdx > bgIdx, "行业背景应落在背景段内");
    }

    /** 全无 kind(历史手册)→ 与旧平铺行为等价:不出现两段块头,逐字平铺。 */
    @Test
    void 全无kind_退化为旧平铺且不出现分组块头() throws Exception {
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());   // entries 无 kind

        String prompt = capturedUserPrompt();

        assertFalse(prompt.contains("【参数事实】"), "无 kind 不得出现分组块头");
        assertFalse(prompt.contains("【背景素材】"), "无 kind 不得出现分组块头");
        assertTrue(prompt.contains("- 价格 = 239900(置信 0.90)\n"), "旧平铺格式逐字保留");
    }

    /** 全无 kind → system prompt 不得出现「参数事实/背景素材」分组铁律(实质契约,不逐字锁定排版文案)。 */
    @Test
    void 全无kind_systemPrompt不含分组铁律() throws Exception {
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        service.write(PROJECT_ID, BRIEF_ID, "", "深度");
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(aiClient).chat(system.capture(), anyString(), eq(4096));
        assertFalse(system.getValue().contains("参数事实"), "无 kind 不得出现分组铁律");
        assertFalse(system.getValue().contains("背景素材"), "无 kind 不得出现分组铁律");
        // 其余铁律仍逐字保留(改造只动态化排版铁律「节数行」)
        assertTrue(system.getValue().contains("3. 结构清晰,用 Markdown;长度按用户需求。"), "铁律 1~3 不得变");
        assertTrue(system.getValue().contains("关键数据、核心结论用 **加粗** 突出,每节至少一处;单段不超过 5 行,长段拆分。"),
                "结尾排版铁律不得变");
    }

    /** 有 kind → system prompt 增「参数事实/背景素材」分组铁律。 */
    @Test
    void 有kind_systemPrompt含分组约束() throws Exception {
        ArticleBriefEntity b = new ArticleBriefEntity();
        b.setId(BRIEF_ID);
        b.setProjectId(PROJECT_ID);
        b.setGenMode("DEEP");
        b.setFactSheet("{\"entries\":[{\"key\":\"价格\",\"value\":\"239900\",\"kind\":\"param\",\"confidence\":0.9}]}");
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(b);
        service.write(PROJECT_ID, BRIEF_ID, "", "深度");
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(aiClient).chat(system.capture(), anyString(), eq(4096));
        assertTrue(system.getValue().contains("参数事实"), "有 kind 应出现分组铁律");
        assertTrue(system.getValue().contains("背景素材"));
    }

    /** 缺 kind 的条目在混合手册中兜底进参数组。 */
    @Test
    void 混合kind_缺kind条目兜底进参数组() throws Exception {
        ArticleBriefEntity b = new ArticleBriefEntity();
        b.setId(BRIEF_ID);
        b.setProjectId(PROJECT_ID);
        b.setGenMode("DEEP");
        b.setFactSheet("{\"entries\":["
                + "{\"key\":\"续航\",\"value\":\"700km\",\"confidence\":0.9},"
                + "{\"key\":\"战略布局\",\"value\":\"\",\"kind\":\"background\",\"confidence\":0.4}"
                + "]}");
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(b);

        String prompt = capturedUserPrompt();

        int paramIdx = prompt.indexOf("【参数事实】");
        int bgIdx = prompt.indexOf("【背景素材】");
        int rangeIdx = prompt.indexOf("续航 = 700km");
        assertTrue(rangeIdx > paramIdx && rangeIdx < bgIdx, "缺 kind 条目应兜底进参数段");
    }

    // ==================== 09-27-deep-writing-adaptive-sections:目标字数 + 自适应分节 ====================

    private ArticleProjectEntity project(Integer wordCountTarget) {
        ArticleProjectEntity p = new ArticleProjectEntity();
        p.setId(PROJECT_ID);
        p.setTopic("主题X");
        p.setWordCountTarget(wordCountTarget);
        return p;
    }

    /** AC-01:user prompt 注入目标字数;null → 1500(与 VersionService 口径一致)。 */
    @Test
    void 目标字数注入_userPrompt含默认1500() throws Exception {
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(null));

        String prompt = capturedUserPrompt();

        assertTrue(prompt.contains("目标字数：1500"), "null 目标字数应回退 1500");
    }

    /** AC-01:显式目标字数原样注入。 */
    @Test
    void 目标字数注入_显式值() throws Exception {
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(5000));

        String prompt = capturedUserPrompt();

        assertTrue(prompt.contains("目标字数：5000"), "显式目标字数应注入");
    }

    /** AC-01:project 缺失/查询异常时容错默认 1500,不抛。 */
    @Test
    void 目标字数注入_project缺失容错1500() throws Exception {
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(null);

        String prompt = capturedUserPrompt();

        assertTrue(prompt.contains("目标字数：1500"), "project 缺失应容错默认档");
    }

    // ==================== 10-02-brief-reasoning-maxtokens R4:目标读者/内容描述注入 ====================

    /** R4:目标读者 + 内容描述非空 → user prompt 注入。 */
    @Test
    void 内容描述与目标读者_非空注入() throws Exception {
        ArticleProjectEntity p = project(3000);
        p.setAudience("汽车行业分析师");
        p.setContentDescription("围绕第2000座闪充站落成写一篇");
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(p);

        String prompt = capturedUserPrompt();
        assertTrue(prompt.contains("目标读者:汽车行业分析师"), "目标读者应注入");
        assertTrue(prompt.contains("内容描述:围绕第2000座闪充站落成写一篇"), "内容描述应注入");
    }

    /** R4:空目标读者/内容描述 → 不注入对应行(历史项目零回归)。 */
    @Test
    void 内容描述与目标读者_空则不出现() throws Exception {
        ArticleProjectEntity p = project(3000);
        p.setAudience("  ");
        p.setContentDescription(null);
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(p);

        String prompt = capturedUserPrompt();
        assertFalse(prompt.contains("目标读者:"), "空目标读者不得出现该行");
        assertFalse(prompt.contains("内容描述:"), "空内容描述不得出现该行");
    }

    /** AC-02:默认目标字数(1500)→ 中档 3~5 个。 */
    @Test
    void 分节_默认档为中档3to5() throws Exception {
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(1500));
        service.write(PROJECT_ID, BRIEF_ID, "", "深度");
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(aiClient).chat(system.capture(), anyString(), eq(4096));

        assertTrue(system.getValue().contains("全文用 3~5 个「## 小标题」分节,每节 2~3 段"),
                "1500 字应为 3~5 个 / 2~3 段");
        assertFalse(system.getValue().contains("2~4 个"), "不得再出现写死的 2~4");
        // 动态化只替换「节数行」文案,与下一行之间仍为单个换行(不引入空行,与旧实现排版一致)
        assertTrue(system.getValue().contains("段,禁止整篇无分节;\n关键数据、核心结论"),
                "排版铁律两行间不得插入空行");
    }

    /** AC-02:目标 ≤800 → 2~3 个且每节 2~3 段。 */
    @Test
    void 分节_短文档为2to3() throws Exception {
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(500));
        service.write(PROJECT_ID, BRIEF_ID, "", "深度");
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(aiClient).chat(system.capture(), anyString(), eq(4096));

        assertTrue(system.getValue().contains("全文用 2~3 个「## 小标题」分节,每节 2~3 段"),
                "≤800 应为 2~3 个 / 2~3 段");
    }

    /** AC-02:目标 >3000 → 8~12 个且每节 2~4 段。 */
    @Test
    void 分节_长文档为8to12每节2to4() throws Exception {
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(5000));
        service.write(PROJECT_ID, BRIEF_ID, "", "深度");
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(aiClient).chat(system.capture(), anyString(), eq(4096));

        assertTrue(system.getValue().contains("全文用 8~12 个「## 小标题」分节,每节 2~4 段"),
                ">3000 应为 8~12 个 / 2~4 段");
    }

    /** AC-03:分档纯函数边界覆盖(null/-1/0/800/801/1800/1801/3000/3001/10000),不抛。 */
    @Test
    void 分档纯函数_边界值() {
        assertSpec(null, "3~5", "2~3");
        assertSpec(-1, "3~5", "2~3");
        assertSpec(0, "3~5", "2~3");
        assertSpec(800, "2~3", "2~3");
        assertSpec(801, "3~5", "2~3");
        assertSpec(1800, "3~5", "2~3");
        assertSpec(1801, "5~8", "2~3");
        assertSpec(3000, "5~8", "2~3");
        assertSpec(3001, "8~12", "2~4");
        assertSpec(10000, "8~12", "2~4");
        assertSpec(Integer.MAX_VALUE, "8~12", "2~4");   // 极端大值仍落顶档,不抛
    }

    /** AC-01:project 查询异常时容错默认 1500,不阻断生成。 */
    @Test
    void 目标字数注入_project查询异常容错1500() throws Exception {
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(projectMapper.selectById(PROJECT_ID)).thenThrow(new RuntimeException("db down"));

        String prompt = capturedUserPrompt();

        assertTrue(prompt.contains("目标字数：1500"), "查询异常应容错默认档");
        assertTrue(prompt.contains("事实手册(数值唯一来源):"), "异常不得阻断手册注入");
    }

    /** AC-01/AC-02:≤0 项目字号 → user prompt 回退 1500,且 system 走中档 3~5(口径一致)。 */
    @Test
    void 目标字数注入_非正值回退默认档() throws Exception {
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(-5));
        service.write(PROJECT_ID, BRIEF_ID, "", "深度");
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(aiClient).chat(system.capture(), user.capture(), eq(4096));

        assertTrue(user.getValue().contains("目标字数：1500"), "≤0 应回退 1500");
        assertTrue(system.getValue().contains("全文用 3~5 个「## 小标题」分节"),
                "≤0 与 null 同档(1500 → 3~5)");
    }

    private static void assertSpec(Integer target, String headings, String paras) {
        com.sparkora.service.LayoutRules.SectionSpec s = com.sparkora.service.LayoutRules.sectionSpec(target);
        assertEquals(headings, s.headings(), "目标 " + target + " 的小标题数档");
        assertEquals(paras, s.parasPerSection(), "目标 " + target + " 的每节段数档");
    }

    // ==================== S6:选定标题生效(选定 > 正文 H1 > 项目主题) ====================

    /** 捕获 write 落库的版本实体。 */
    private ArticleVersionEntity capturedVersion() throws Exception {
        service.write(PROJECT_ID, BRIEF_ID, "", "深度");
        ArgumentCaptor<ArticleVersionEntity> v = ArgumentCaptor.forClass(ArticleVersionEntity.class);
        verify(versionMapper).insert(v.capture());
        return v.getValue();
    }

    private ArticleProjectEntity projectWithSelectedTitle(String selectedTitle) {
        ArticleProjectEntity p = project(null);
        p.setSelectedTitle(selectedTitle);
        return p;
    }

    /** AC3:selectedTitle 非空 + AI 正文无 H1 → 版本标题采用选定标题。 */
    @Test
    void 选定标题非空_无H1_采用选定标题() throws Exception {
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(projectWithSelectedTitle("选定标题甲"));

        assertEquals("选定标题甲", capturedVersion().getTitle());
    }

    /** AC3:selectedTitle 非空 + AI 正文含 H1 → 选定标题优先于 H1。 */
    @Test
    void 选定标题非空_含H1_仍采用选定标题() throws Exception {
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(projectWithSelectedTitle("选定标题甲"));
        when(aiClient.chat(anyString(), anyString(), anyInt()))
                .thenReturn(new AiClient.ChatResult("# 正文H1标题\n\n正文", "m", 10));

        assertEquals("选定标题甲", capturedVersion().getTitle());
    }

    /** AC4:selectedTitle 为空 + 正文含 H1 → 回退正文 H1(现状保留)。 */
    @Test
    void 选定标题为空_含H1_回退H1() throws Exception {
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(projectWithSelectedTitle(null));
        when(aiClient.chat(anyString(), anyString(), anyInt()))
                .thenReturn(new AiClient.ChatResult("# 正文H1标题\n\n正文", "m", 10));

        assertEquals("正文H1标题", capturedVersion().getTitle());
    }

    /** AC4:selectedTitle 为空 + 无 H1 → 回退项目主题(现状保留)。 */
    @Test
    void 选定标题为空_无H1_回退主题() throws Exception {
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(projectWithSelectedTitle("  "));

        assertEquals("主题X", capturedVersion().getTitle());
    }

    /** AC6:selectedTitle 非空 → prompt 含注入块与标题文本。 */
    @Test
    void 选定标题非空_prompt含注入块() throws Exception {
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(projectWithSelectedTitle("选定标题甲"));

        String prompt = capturedUserPrompt();

        assertTrue(prompt.contains("【用户已选定标题,正文一级标题(#)请采用该标题,勿偏离原意】"), "应含选定标题注入块");
        assertTrue(prompt.contains("选定标题甲"), "应含选定标题文本");
    }

    /** AC6:selectedTitle 为空 → prompt 不含注入块(旧行为等价,现有用例不回归)。 */
    @Test
    void 选定标题为空_prompt不含注入块() throws Exception {
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(projectWithSelectedTitle(null));

        String prompt = capturedUserPrompt();

        assertFalse(prompt.contains("【用户已选定标题"), "空选定标题不得出现注入块");
    }

    // ==================== 10-02-fix-meta-leak-in-article-body:元话语泄漏三层防线(R1/R2/R3) ====================

    /** 禁写断言块头(与 DeepWriterService.FORBIDDEN_CLAIMS_HEADER 同文)。 */
    private static final String FORBIDDEN_HEADER = "【禁止写入正文的断言】";

    /** 线上真实样本 brief 76 第 3 条 fact_risks:suggestion 是写给作者的祈使句(泄漏源)。 */
    private static final String BRIEF76_FACT_RISKS = "[{\"claim\":\"手册未提供比亚迪2026年度销量目标，也无完成进度数据\","
            + "\"riskLevel\":\"high\","
            + "\"suggestion\":\"此表述必须删除或改为「手册未披露年度目标，完成率无法计算」\"}]";

    /** AC1:brief 76 真实 suggestion 原文绝不进 prompt,claim 进禁写断言块。 */
    @Test
    void 事实风险suggestion祈使句_不进userPrompt() throws Exception {
        ArticleBriefEntity b = brief();
        b.setFactRisks(BRIEF76_FACT_RISKS);
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(b);

        String prompt = capturedUserPrompt();

        assertTrue(prompt.contains(FORBIDDEN_HEADER), "应注入禁写断言块");
        assertTrue(prompt.contains("手册未提供比亚迪2026年度销量目标，也无完成进度数据"), "claim 应作为禁写断言");
        assertFalse(prompt.contains("此表述必须删除或改为"), "suggestion 祈使句不得进 prompt");
        assertFalse(prompt.contains("完成率无法计算"), "suggestion 原文不得进 prompt");
        assertFalse(prompt.contains("suggestion"), "整个 fact_risks JSON 不得再整体注入");
    }

    /** AC1:畸形/非数组 fact_risks 一律不按原文追加(否则祈使句随畸形数据回流)。 */
    @Test
    void factRisks畸形或非数组_不注入禁写块() throws Exception {
        for (String bad : new String[]{"[不是合法JSON", "{\"claim\":\"风险Y\"}", "[{\"riskLevel\":\"high\"}]", "[]", "  "}) {
            ArticleBriefEntity b = brief();
            b.setFactRisks(bad);
            when(briefMapper.selectById(BRIEF_ID)).thenReturn(b);
            org.mockito.Mockito.clearInvocations(aiClient);   // 循环内逐轮独立捕获首次调用

            String prompt = capturedUserPrompt();

            assertFalse(prompt.contains(FORBIDDEN_HEADER), "不可用的 fact_risks 不得注入: " + bad);
            assertFalse(prompt.contains("风险Y"), "非数组原文不得按原样注入: " + bad);
            // 写作链路其余部分不受影响(不阻断)
            assertTrue(prompt.contains("事实手册(数值唯一来源):"), "异常不阻断手册注入: " + bad);
        }
    }

    /** AC2:system prompt 含读者视角铁律(内部元话语黑名单 + 禁止解释数据缺失)。 */
    @Test
    void systemPrompt含读者视角铁律() throws Exception {
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        service.write(PROJECT_ID, BRIEF_ID, "", "深度");
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(aiClient).chat(system.capture(), anyString(), eq(4096));
        String sys = system.getValue();

        assertTrue(sys.contains("读者视角铁律"), "应含读者视角铁律");
        assertTrue(sys.contains("你只写给读者看"), "应含读者视角声明");
        for (String word : new String[]{"手册", "事实手册", "简报", "大纲", "事实风险", "未收录", "未提供",
                "无法计算", "待核实", "不应作为结论", "知识库未覆盖"}) {
            assertTrue(sys.contains(word), "黑名单应含内部话术: " + word);
        }
        assertTrue(sys.contains("禁止在正文中解释"), "应显式禁止解释数据缺失");
        // AC7:既有铁律 1~3 与排版铁律逐字不变(R4 不改既有注入文案)
        assertTrue(sys.contains("1. 正文中出现的所有具体数值(价格/尺寸/续航/百分比等)必须逐字出自下方事实手册,禁止改写/换算/推算。"));
        assertTrue(sys.contains("2. 手册未覆盖的参数,用定性表述,不得给出具体数值。"));
        assertTrue(sys.contains("3. 结构清晰,用 Markdown;长度按用户需求。"));
        assertTrue(sys.contains("关键数据、核心结论用 **加粗** 突出,每节至少一处;单段不超过 5 行,长段拆分。"));
    }

    /** AC6:落库正文 = 清洗后正文(泄漏句消失、正常句保留、字数按清洗后计)。 */
    @Test
    void 落库正文为清洗后内容() throws Exception {
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(aiClient.chat(anyString(), anyString(), anyInt())).thenReturn(new AiClient.ChatResult(
                "9月销量46.36万辆，同比33.21%。\n\n合资品牌同期在华销量的具体数据，手册未提供，只能提示一个方向：份额承压仍在继续。",
                "m", 10));

        ArticleVersionEntity v = capturedVersion();

        assertFalse(v.getContentMd().contains("手册未提供"), "泄漏句必须删除");
        assertEquals("9月销量46.36万辆，同比33.21%。", v.getContentMd(), "正常句逐字保留、泄漏段落整体消失");
        assertEquals(v.getContentMd().length(), v.getWordCount(), "字数按清洗后正文计");
    }

    /** AC6:数值回查基于清洗后正文(已删句不再参与数值比对,不误报 high 风险)。 */
    @Test
    void 数值回查基于清洗后正文() throws Exception {
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(aiClient.chat(anyString(), anyString(), anyInt())).thenReturn(new AiClient.ChatResult(
                "9月销量46.36万辆，同比33.21%。\n\n完成率无法计算，也没有行业排名或份额数据。",
                "m", 10));
        DeepWriterService spyService = org.mockito.Mockito.spy(service);
        org.mockito.Mockito.doReturn(List.of()).when(spyService).verifyNumbers(anyString(), anyString());

        spyService.write(PROJECT_ID, BRIEF_ID, "", "深度");

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(spyService).verifyNumbers(content.capture(), anyString());
        assertEquals("9月销量46.36万辆，同比33.21%。", content.getValue(),
                "verifyNumbers 必须收到清洗后正文(泄漏句已删)");
    }

    // ==================== C7(10-02-c7-metaleak-numbers):verifyNumbers 数值归一化比对 ====================

    /** C7 漏报修复:内容 1200、手册仅 12000 → 不再被子串命中,报 high。 */
    @Test
    void verifyNumbers_1200与12000不再漏报() throws Exception {
        List<String> unknown = service.verifyNumbers(
                "该工厂年产能为1200辆。",
                "{\"entries\":[{\"key\":\"年产能\",\"value\":\"12000\"}]}");

        assertTrue(unknown.contains("1200"), "1200 不在手册 {12000} 中,应报未收录");
    }

    /** C7 误报修复:内容 200000、手册 20万 → 归一后同值,不报。 */
    @Test
    void verifyNumbers_万与千分位等价不误报() throws Exception {
        assertTrue(service.verifyNumbers(
                "起售价200000元。",
                "{\"entries\":[{\"key\":\"起售价\",\"value\":\"20万\"}]}").isEmpty(),
                "200000 与 20万 归一后等价,不得误报");
        assertTrue(service.verifyNumbers(
                "同比33.21%。",
                "{\"entries\":[{\"key\":\"同比\",\"value\":\"33.21%\"}]}").isEmpty(),
                "33.21% 与 33.21 归一后等价,不得误报");
    }

    /** C7 误报修复:内容 239900、手册 239,900(千分位)→ 归一后同值,不报(旧子串失配会误报)。 */
    @Test
    void verifyNumbers_千分位等价不误报() throws Exception {
        assertTrue(service.verifyNumbers(
                "售价239900元。",
                "{\"entries\":[{\"key\":\"售价\",\"value\":\"239,900\"}]}").isEmpty(),
                "239900 与 239,900 归一后等价,不得误报");
        // 内容侧写千分位:抽取正则须整体命中 239,900,不得拆成 239/900 两个 token
        assertTrue(service.verifyNumbers(
                "售价 239,900 元。",
                "{\"entries\":[{\"key\":\"售价\",\"value\":\"239900\"}]}").isEmpty(),
                "内容 239,900 与手册 239900 归一后等价,不得误报");
        assertTrue(service.verifyNumbers(
                "销量 1,200,000 辆。",
                "{\"entries\":[{\"key\":\"销量\",\"value\":\"1200000\"}]}").isEmpty(),
                "内容 1,200,000 与手册 1200000 归一后等价,不得误报");
    }

    /** C7 正常通过:内容 2000、手册「第2000座」→ 收录,不报。 */
    @Test
    void verifyNumbers_收录值正常通过() throws Exception {
        assertTrue(service.verifyNumbers(
                "第2000座闪充站落成。",
                "{\"entries\":[{\"key\":\"里程碑\",\"value\":\"第2000座\"}]}").isEmpty(),
                "手册含 2000,应通过");
    }

    /** C7 边界:手册为 {}/null(无手册)→ 所有数值仍报 high(与既有语义一致)。 */
    @Test
    void verifyNumbers_无手册时数值仍报high() throws Exception {
        assertTrue(service.verifyNumbers("续航达到700km。", "{}").contains("700km"),
                "空手册 { } 时数值应报未收录");
        assertTrue(service.verifyNumbers("续航达到700km。", null).contains("700km"),
                "null 手册时数值应报未收录");
    }

    /** C7:canonicalNumber 复用 ClaimSimilarity 归一口径(万/千分位/小数)。 */
    @Test
    void canonicalNumber_归一口径与ClaimSimilarity一致() {
        assertEquals("200000", DeepWriterService.canonicalNumber("20万"));
        assertEquals("200000", DeepWriterService.canonicalNumber("200,000"));
        assertEquals("33.21", DeepWriterService.canonicalNumber("33.21%"));
        assertEquals("700", DeepWriterService.canonicalNumber("700km"));
        assertEquals("150000000", DeepWriterService.canonicalNumber("1.5亿"));
    }

    /**
     * C7 回归:内容「N亿」必须与手册同值(亿换算)不误报——此前抽取正则只含「万」不含「亿」,
     * 命中截成小数部分(1.5亿→1.5),与 numberValues 的亿换算签名对不上而误报。
     */
    @Test
    void verifyNumbers_亿与手册同值不误报() throws Exception {
        assertTrue(service.verifyNumbers(
                "项目投资1.5亿元。",
                "{\"entries\":[{\"key\":\"投资\",\"value\":\"1.5亿\"}]}").isEmpty(),
                "1.5亿 与手册 1.5亿 归一后等价,不得误报");
        assertTrue(service.verifyNumbers(
                "营收100亿元。",
                "{\"entries\":[{\"key\":\"营收\",\"value\":\"10000000000\"}]}").isEmpty(),
                "100亿 与手册 10000000000 归一后等价,不得误报");
    }

    /** C7 漏报:内容「N亿」与手册不同量级 → 仍须报(确认亿参与比对而非被截断成小数)。 */
    @Test
    void verifyNumbers_亿与手册不同值仍报() throws Exception {
        assertTrue(service.verifyNumbers(
                "营收200亿元。",
                "{\"entries\":[{\"key\":\"营收\",\"value\":\"100亿\"}]}").contains("200亿"),
                "200亿 不在手册 {100亿} 中,应报未收录");
    }

    /**
     * C7 回归:≥8 位纯数字必须与手册同值不误报——此前抽取正则上限 {@code \d{4,7}} 把
     * {@code 12000000} 只截成 {@code 1200000}(前 7 位),归一后与手册对不上而误报。
     */
    @Test
    void verifyNumbers_八位以上数值与手册同值不误报() throws Exception {
        assertTrue(service.verifyNumbers(
                "销量12000000辆。",
                "{\"entries\":[{\"key\":\"销量\",\"value\":\"12000000\"}]}").isEmpty(),
                "8 位数值与手册同值,不得因抽取截断而误报");
        assertTrue(service.verifyNumbers(
                "产能100000000台。",
                "{\"entries\":[{\"key\":\"产能\",\"value\":\"100000000\"}]}").isEmpty(),
                "9 位数值与手册同值,不得因抽取截断而误报");
    }

    /**
     * C7 漏报:内容大数、手册「N万」等价写法 → 不再因抽取截断而漏判(旧子串会误命中同前缀而漏,
     * 新归一口径必须命中正确签名)。
     */
    @Test
    void verifyNumbers_大数与万等价不误报() throws Exception {
        assertTrue(service.verifyNumbers(
                "建成12000000座。",
                "{\"entries\":[{\"key\":\"数量\",\"value\":\"1200万\"}]}").isEmpty(),
                "12000000 与 1200万 归一后等价,不得误报");
    }
}
