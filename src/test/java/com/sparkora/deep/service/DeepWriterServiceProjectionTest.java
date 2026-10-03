package com.sparkora.deep.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.ai.AiClient;
import com.sparkora.domain.entity.ArticleBriefEntity;
import com.sparkora.domain.entity.ArticleProjectEntity;
import com.sparkora.domain.entity.ArticleVersionEntity;
import com.sparkora.domain.entity.StyleProfileEntity;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C4(10-03-writer-evidence-projection)「写作按映射取用」单测。
 *
 * <p>覆盖:
 * <ul>
 *   <li>投影模式:每节仅注入该节 evidenceMap.entryKeys 对应条目,未映射条目的 value/claim 不进 prompt;</li>
 *   <li>MISSING 节追加不带数值的定性指令;</li>
 *   <li>评审门:startBatch 在蓝图缺失/REVIEWING → 409 且未 claim/未调 AI;CONFIRMED → 放行;</li>
 *   <li>白名单数值回查:蓝图未映射条目的数值进入正文 → version.factRisks 含 high。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeepWriterServiceProjectionTest {

    private static final Long PROJECT_ID = 1L;
    private static final Long BRIEF_ID = 2L;

    /** 三条目手册:价格/续航 为 param,战略布局 为 background;仅映射 价格 + 战略布局。 */
    private static final String FACT_SHEET = "{\"entries\":["
            + "{\"key\":\"价格\",\"value\":\"239900\",\"kind\":\"param\",\"confidence\":0.9},"
            + "{\"key\":\"续航\",\"value\":\"700km\",\"kind\":\"param\",\"confidence\":0.9},"
            + "{\"key\":\"战略布局\",\"value\":\"\",\"kind\":\"background\",\"confidence\":0.4}"
            + "]}";

    /** 蓝图:映射 价格(COVERED) + 战略布局(MISSING);续航未映射。 */
    private static final String BLUEPRINT = "{"
            + "\"thesis\":\"价格与战略\",\"audienceAngle\":\"购车者\","
            + "\"argumentStructure\":["
            + "{\"sectionId\":\"S1\",\"heading\":\"价格定位\",\"role\":\"ARGUMENT\",\"claim\":\"价格有竞争力\"},"
            + "{\"sectionId\":\"S2\",\"heading\":\"战略规划\",\"role\":\"CONTEXT\",\"claim\":\"战略布局清晰\"}"
            + "],"
            + "\"evidenceMap\":["
            + "{\"sectionId\":\"S1\",\"entryKeys\":[\"价格\"],\"coverage\":\"COVERED\"},"
            + "{\"sectionId\":\"S2\",\"entryKeys\":[\"战略布局\"],\"coverage\":\"MISSING\"}"
            + "]}";

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
        b.setFactSheet(FACT_SHEET);
        b.setWritingBlueprint(BLUEPRINT);
        b.setBlueprintStatus("CONFIRMED");
        return b;
    }

    private ArticleProjectEntity project(String status) {
        ArticleProjectEntity p = new ArticleProjectEntity();
        p.setId(PROJECT_ID);
        p.setTopic("主题");
        p.setStatus(status);
        return p;
    }

    private StyleProfileEntity style(Long id, String name) {
        StyleProfileEntity s = new StyleProfileEntity();
        s.setId(id);
        s.setName(name);
        s.setToneGuidance("语气" + name);
        return s;
    }

    /** 捕获 write 发出的 user prompt(首次 4096 调用)。 */
    private String capturedUserPrompt() throws Exception {
        service.write(PROJECT_ID, BRIEF_ID, "", "深度");
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(aiClient).chat(anyString(), user.capture(), eq(4096));
        return user.getValue();
    }

    // ==================== 投影:按 entryKeys 取用 ====================

    @Test
    void 投影_仅注入映射条目_未映射条目不进prompt() throws Exception {
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());

        String prompt = capturedUserPrompt();

        // 映射条目(价格 param + 战略布局 background)进 prompt
        assertTrue(prompt.contains("价格 = 239900"), "映射条目「价格」应注入本节证据");
        assertTrue(prompt.contains("战略布局"), "映射条目「战略布局」应注入本节证据");
        // 未映射条目「续航 / 700km」一律不进 prompt
        assertFalse(prompt.contains("续航"), "未映射条目「续航」不得进 prompt");
        assertFalse(prompt.contains("700km"), "未映射条目的数值不得进 prompt");
        // 逐节块头 + 蓝图论点
        assertTrue(prompt.contains("## 价格定位"), "应含节标题");
        assertTrue(prompt.contains("## 战略规划"), "应含节标题");
        assertTrue(prompt.contains("价格有竞争力"), "应含本节论点");
    }

    @Test
    void 投影_MISSING节追加定性指令() throws Exception {
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());

        String prompt = capturedUserPrompt();

        assertTrue(prompt.contains("本节所需证据未在事实手册中：正文只能用不带具体数值的定性陈述,不得给出任何数字。"),
                "MISSING 节应出现定性指令");
    }

    @Test
    void 投影_PARTIAL节追加部分证据定性指令() throws Exception {
        ArticleBriefEntity b = brief();
        b.setWritingBlueprint("{"
                + "\"thesis\":\"价格\",\"audienceAngle\":\"购车者\","
                + "\"argumentStructure\":[{\"sectionId\":\"S1\",\"heading\":\"价格定位\",\"claim\":\"价格\"}],"
                + "\"evidenceMap\":[{\"sectionId\":\"S1\",\"entryKeys\":[\"价格\"],\"coverage\":\"PARTIAL\"}]}");
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(b);

        String prompt = capturedUserPrompt();

        assertTrue(prompt.contains("本节所需证据仅部分在事实手册中：只有上方列出的证据可逐字引用数值,未列出的不得给出任何数字。"),
                "PARTIAL 节应出现「仅部分证据」定性指令");
        assertTrue(prompt.contains("价格 = 239900"), "PARTIAL 节仍注入已映射条目");
    }

    /** 多 evidence 项同 sectionId:entryKeys 合并去重,重复 key 不得重复注入条目。 */
    @Test
    void 投影_同一节多evidence项_合并去重() throws Exception {
        ArticleBriefEntity b = brief();
        b.setWritingBlueprint("{"
                + "\"thesis\":\"价格\",\"audienceAngle\":\"购车者\","
                + "\"argumentStructure\":[{\"sectionId\":\"S1\",\"heading\":\"价格定位\",\"claim\":\"价格\"}],"
                + "\"evidenceMap\":["
                + "{\"sectionId\":\"S1\",\"entryKeys\":[\"价格\"],\"coverage\":\"COVERED\"},"
                + "{\"sectionId\":\"S1\",\"entryKeys\":[\"价格\",\"续航\"],\"coverage\":\"COVERED\"}"
                + "]}");
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(b);

        String prompt = capturedUserPrompt();

        // 合并后 价格/续航 均进本节证据
        assertTrue(prompt.contains("价格 = 239900"), "合并后应含价格条目");
        assertTrue(prompt.contains("续航 = 700km"), "合并后应含续航条目");
        // 「价格」被两项重复引用,去重后只出现一次条目行
        assertEquals(1, count(prompt, "- 价格 = 239900"), "重复 entryKey 去重后条目只注入一次");
    }

    /** entryKeys 引用了 fact_sheet 不存在的 key → 该节无证据(不得注入其它条目)。 */
    @Test
    void 投影_key不匹配_该节无证据且不注入其它条目() throws Exception {
        ArticleBriefEntity b = brief();
        b.setWritingBlueprint("{"
                + "\"thesis\":\"价格\",\"audienceAngle\":\"购车者\","
                + "\"argumentStructure\":[{\"sectionId\":\"S1\",\"heading\":\"价格定位\",\"claim\":\"价格\"}],"
                + "\"evidenceMap\":[{\"sectionId\":\"S1\",\"entryKeys\":[\"不存在的key\"],\"coverage\":\"MISSING\"}]}");
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(b);

        String prompt = capturedUserPrompt();

        assertTrue(prompt.contains("(无)"), "key 不匹配时本节证据应显示 (无)");
        assertFalse(prompt.contains("价格 = 239900"), "key 不匹配不得回退注入其它条目");
        assertFalse(prompt.contains("续航 = 700km"), "key 不匹配不得回退注入其它条目");
    }

    private static int count(String s, String sub) {
        int c = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { c++; i += sub.length(); }
        return c;
    }

    @Test
    void 投影_system含蓝图硬约束() throws Exception {
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());

        service.write(PROJECT_ID, BRIEF_ID, "", "深度");
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(aiClient).chat(system.capture(), anyString(), eq(4096));

        assertTrue(system.getValue().contains("蓝图未映射到本节的任何事实/数值不得出现"),
                "system 应含蓝图硬约束");
    }

    /** 无蓝图(降级模式)→ 仍注入整本手册(不崩,回归原有行为)。 */
    @Test
    void 降级_无蓝图注入整本手册() throws Exception {
        ArticleBriefEntity b = brief();
        b.setWritingBlueprint(null);
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(b);

        String prompt = capturedUserPrompt();

        assertTrue(prompt.contains("事实手册(数值唯一来源):"), "降级模式应注入整本手册");
        assertTrue(prompt.contains("价格 = 239900") && prompt.contains("续航 = 700km"),
                "降级模式应含整本手册条目");
    }

    /** argumentStructure 为空数组 → 降级模式(不崩)。 */
    @Test
    void 降级_蓝图结构为空注入整本手册() throws Exception {
        ArticleBriefEntity b = brief();
        b.setWritingBlueprint("{\"argumentStructure\":[],\"evidenceMap\":[]}");
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(b);

        String prompt = capturedUserPrompt();

        assertTrue(prompt.contains("事实手册(数值唯一来源):"), "空 argumentStructure 应降级整本手册");
    }

    // ==================== 评审门(同步 startBatch) ====================

    @Test
    void 评审门_蓝图REVIEWING_抛409且未claim未调AI() {
        ArticleBriefEntity b = brief();
        b.setBlueprintStatus("REVIEWING");
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(b);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project("READY"));

        assertThrows(IllegalStateException.class,
                () -> service.startBatch(PROJECT_ID, BRIEF_ID, null));
        verify(statusService, never()).claimDeepVersionsGenerating(any(), any(), anyString());
        verify(aiClient, never()).chat(anyString(), anyString(), anyInt());
    }

    @Test
    void 评审门_蓝图缺失_抛409且未claim未调AI() {
        ArticleBriefEntity b = brief();
        b.setWritingBlueprint(null);
        b.setBlueprintStatus(null);
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(b);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project("READY"));

        assertThrows(IllegalStateException.class,
                () -> service.startBatch(PROJECT_ID, BRIEF_ID, null));
        verify(statusService, never()).claimDeepVersionsGenerating(any(), any(), anyString());
        verify(aiClient, never()).chat(anyString(), anyString(), anyInt());
    }

    @Test
    void 评审门_蓝图CONFIRMED_放行并生成() {
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project("READY"));
        when(styleMapper.selectBatchIds(List.of(1L))).thenReturn(List.of(style(1L, "正式")));
        when(aiClient.chat(anyString(), anyString(), anyInt()))
                .thenReturn(new AiClient.ChatResult("正文内容", "m", 10));
        doAnswer(inv -> { ((ArticleVersionEntity) inv.getArgument(0)).setId(501L); return 1; })
                .when(versionMapper).insert(any(ArticleVersionEntity.class));

        service.startBatch(PROJECT_ID, BRIEF_ID, List.of(1L));

        verify(statusService).claimDeepVersionsGenerating(eq(PROJECT_ID), any(), eq("生成版本"));
        verify(versionMapper).insert(any(ArticleVersionEntity.class));
        verify(statusService).advanceVersionsReady(PROJECT_ID, 501L, null);
    }

    // ==================== 白名单数值回查 ====================

    /** 越界数值:正文含未映射条目「续航 700km」的数字 → factRisks high;映射条目数字不报。 */
    @Test
    void 白名单_未映射条目数值被标high_映射条目不报() throws Exception {
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(aiClient.chat(anyString(), anyString(), anyInt())).thenReturn(new AiClient.ChatResult(
                "售价239900元，续航达到700km。", "m", 10));

        service.write(PROJECT_ID, BRIEF_ID, "", "深度");

        ArgumentCaptor<ArticleVersionEntity> v = ArgumentCaptor.forClass(ArticleVersionEntity.class);
        verify(versionMapper).insert(v.capture());
        String risks = v.getValue().getFactRisks();
        assertTrue(risks.contains("700km"), "未映射条目的数值应被标 high: " + risks);
        assertTrue(risks.contains("high"), "风险级别应为 high");
        assertFalse(risks.contains("239900"), "映射条目(价格)的数值不得误报");
    }
}
