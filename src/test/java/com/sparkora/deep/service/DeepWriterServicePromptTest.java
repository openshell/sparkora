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
    void 简报四字段_实质注入userPrompt() throws Exception {
        ArticleBriefEntity b = brief();
        b.setTitleCandidates("[\"标题甲\",\"标题乙\"]");
        b.setCoreViewpoints("[\"观点一\",\"观点二\"]");
        b.setOutline("[{\"heading\":\"章节一\"}]");
        b.setFactRisks("[{\"claim\":\"风险X\"}]");
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(b);

        String prompt = capturedUserPrompt();

        assertTrue(prompt.contains("标题候选:"), "应有标题候选块");
        assertTrue(prompt.contains("标题甲") && prompt.contains("标题乙"), "标题候选内容应注入");
        assertTrue(prompt.contains("核心观点:") && prompt.contains("观点一"), "核心观点应注入");
        assertTrue(prompt.contains("大纲:") && prompt.contains("章节一"), "大纲应注入");
        assertTrue(prompt.contains("事实风险:") && prompt.contains("风险X"), "事实风险应注入");
    }

    @Test
    void 简报字段为空_退化为旧prompt且不抛异常() throws Exception {
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());   // 四字段均 null

        String prompt = capturedUserPrompt();

        assertFalse(prompt.contains("标题候选:"), "空字段不得出现标题候选块");
        assertFalse(prompt.contains("核心观点:"), "空字段不得出现核心观点块");
        assertFalse(prompt.contains("大纲:"), "空字段不得出现大纲块");
        assertFalse(prompt.contains("事实风险:"), "空字段不得出现事实风险块");
        // 事实手册仍在(旧行为保留)
        assertTrue(prompt.contains("事实手册(数值唯一来源):"));
    }

    @Test
    void 空数组与空对象字段_跳过不报错() throws Exception {
        ArticleBriefEntity b = brief();
        b.setTitleCandidates("[]");
        b.setCoreViewpoints("{}");
        b.setOutline("");
        b.setFactRisks("   ");
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(b);

        String prompt = capturedUserPrompt();

        assertFalse(prompt.contains("标题候选:"));
        assertFalse(prompt.contains("核心观点:"));
        assertFalse(prompt.contains("大纲:"));
        assertFalse(prompt.contains("事实风险:"));
    }

    @Test
    void 字段为空时_末尾指引句与旧行为逐字一致() throws Exception {
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());   // 四字段均 null

        String prompt = capturedUserPrompt();

        assertTrue(prompt.contains("主题与大纲参考 brief(标题候选/核心观点/大纲),直接写正文 Markdown。"),
                "空字段 brief 的 prompt 必须与旧实现等价(末尾指引句不得改变)");
    }

    @Test
    void 畸形JSON字段_按原文追加且块头不重复() throws Exception {
        ArticleBriefEntity b = brief();
        b.setTitleCandidates("[不是合法JSON");
        b.setOutline("{\"heading\":\"未闭合");
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(b);

        String prompt = capturedUserPrompt();

        assertTrue(prompt.contains("标题候选:\n[不是合法JSON"), "畸形字段按原文追加");
        assertTrue(prompt.contains("大纲:\n{\"heading\":\"未闭合"), "畸形 outline 按原文追加");
        // 块头不得因解析失败重复
        assertEquals(count(prompt, "标题候选:"), 1, "块头只能出现一次");
        assertEquals(count(prompt, "大纲:"), 1, "块头只能出现一次");
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

    private static int count(String s, String sub) {
        int c = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { c++; i += sub.length(); }
        return c;
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
}
