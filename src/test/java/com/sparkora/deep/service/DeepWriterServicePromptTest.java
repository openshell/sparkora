package com.sparkora.deep.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.ai.AiClient;
import com.sparkora.domain.entity.ArticleBriefEntity;
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

    /** 全无 kind → system prompt 与旧实现逐字等价(不得出现「参数事实/背景素材」铁律)。 */
    @Test
    void 全无kind_systemPrompt与旧行为等价() throws Exception {
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        service.write(PROJECT_ID, BRIEF_ID, "", "深度");
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(aiClient).chat(system.capture(), anyString(), eq(4096));
        String legacy = """
                你是资深汽车内容作者。基于【事实手册】与用户锁定需求撰写文章正文。
                铁律:
                1. 正文中出现的所有具体数值(价格/尺寸/续航/百分比等)必须逐字出自下方事实手册,禁止改写/换算/推算。
                2. 手册未覆盖的参数,用定性表述,不得给出具体数值。
                3. 结构清晰,用 Markdown;长度按用户需求。
                排版铁律(公众号正文可读性,必须遵守):全文用 2~4 个「## 小标题」分节,每节 2~3 段,禁止整篇无分节;
                关键数据、核心结论用 **加粗** 突出,每节至少一处;单段不超过 5 行,长段拆分。
                """;
        assertEquals(legacy, system.getValue(), "无 kind 时 system prompt 必须与旧实现逐字等价");
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
}
