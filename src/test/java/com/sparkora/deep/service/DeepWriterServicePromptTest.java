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

    private static int count(String s, String sub) {
        int c = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { c++; i += sub.length(); }
        return c;
    }
}
