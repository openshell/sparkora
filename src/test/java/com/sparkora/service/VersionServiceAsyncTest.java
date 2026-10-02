package com.sparkora.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VersionService 异步切分单测(09-27-gen-async AC7,Mockito 不连库/不调 AI)。
 *
 * <p>自注入代理在单测直 new 场景为 null,`self == null ? this : self` 回退为同步执行,
 * 因此可直接断言同步阶段(claim/占位/校验/410 由控制器判定)与异步体的成功/部分失败/全失败落状态。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VersionServiceAsyncTest {

    private static final Long PROJECT_ID = 1L;
    private static final Long BRIEF_ID = 2L;

    @Mock ArticleProjectMapper projectMapper;
    @Mock ArticleBriefMapper briefMapper;
    @Mock ArticleVersionMapper versionMapper;
    @Mock StyleProfileMapper styleMapper;
    @Mock AiClient aiClient;
    @Mock CarRagService ragService;
    @Mock ArticleProjectCarService carService;
    @Mock ImitationService imitationService;
    @Mock ProjectStatusService statusService;

    VersionService service;

    private static final String VERSION_JSON = "{\"title\":\"标题\",\"contentMd\":\"# 正文\\n内容\"}";

    @BeforeEach
    void setUp() {
        service = new VersionService(projectMapper, briefMapper, versionMapper, styleMapper,
                aiClient, ragService, carService, new ObjectMapper(), imitationService, statusService);
        when(carService.listModelIds(PROJECT_ID)).thenReturn(List.of());
        when(ragService.retrieveForGeneration(any(), anyInt(), any())).thenReturn(CarRagService.RagResult.EMPTY);
    }

    private ArticleProjectEntity project(String genSource, String status) {
        ArticleProjectEntity p = new ArticleProjectEntity();
        p.setId(PROJECT_ID);
        p.setTopic("主题");
        p.setCurrentBriefId(BRIEF_ID);
        p.setGenSource(genSource);
        p.setStatus(status);
        return p;
    }

    private ArticleBriefEntity brief() {
        ArticleBriefEntity b = new ArticleBriefEntity();
        b.setId(BRIEF_ID);
        b.setProjectId(PROJECT_ID);
        b.setGenMode("IMITATION");
        return b;
    }

    private StyleProfileEntity style(Long id, String name) {
        StyleProfileEntity s = new StyleProfileEntity();
        s.setId(id);
        s.setName(name);
        s.setToneGuidance("语气" + name);
        return s;
    }

    @Test
    void 仿写同步阶段_claim后返回占位并推进VERSIONS_READY() {
        ArticleProjectEntity p = project("IMITATION", "READY");
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(p);
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(styleMapper.selectBatchIds(List.of(1L, 2L))).thenReturn(List.of(style(1L, "正式"), style(2L, "活泼")));
        when(aiClient.chatJson(anyString(), anyString(), anyInt()))
                .thenReturn(new AiClient.ChatResult(VERSION_JSON, "m", 10));
        doAnswer(inv -> { ((ArticleVersionEntity) inv.getArgument(0)).setId(101L); return 1; })
                .when(versionMapper).insert(any(ArticleVersionEntity.class));

        Map<String, Object> out = service.generate(PROJECT_ID, List.of(1L, 2L));

        assertEquals("GENERATING_VERSIONS", out.get("status"));
        assertEquals(2, out.get("styleCount"));
        verify(statusService).claimVersionsGenerating(PROJECT_ID, project("IMITATION", "READY"), "生成版本");
        // 两风格两版,首版 id 101
        verify(versionMapper, times(2)).insert(any(ArticleVersionEntity.class));
        verify(statusService).advanceVersionsReady(eq(PROJECT_ID), eq(101L), eq(null));
        verify(statusService, never()).failVersionsToReady(any(), anyString());
    }

    @Test
    void 仿写部分失败_记录last_version_error但状态仍推进() {
        ArticleProjectEntity p = project("IMITATION", "READY");
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(p);
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(styleMapper.selectBatchIds(List.of(1L, 2L))).thenReturn(List.of(style(1L, "正式"), style(2L, "活泼")));
        // 第一风格成功,第二风格 AI 抛错
        when(aiClient.chatJson(anyString(), anyString(), anyInt()))
                .thenReturn(new AiClient.ChatResult(VERSION_JSON, "m", 10))
                .thenThrow(new AiException("AI 超时", null));
        doAnswer(inv -> { ((ArticleVersionEntity) inv.getArgument(0)).setId(201L); return 1; })
                .when(versionMapper).insert(any(ArticleVersionEntity.class));

        service.generate(PROJECT_ID, List.of(1L, 2L));

        verify(statusService).advanceVersionsReady(eq(PROJECT_ID), eq(201L), org.mockito.ArgumentMatchers.contains("[B:活泼]"));
        verify(statusService, never()).failVersionsToReady(any(), anyString());
    }

    @Test
    void 仿写全部失败_failVersionsToReady回READY() {
        ArticleProjectEntity p = project("IMITATION", "READY");
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(p);
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(styleMapper.selectBatchIds(List.of(1L, 2L))).thenReturn(List.of(style(1L, "正式"), style(2L, "活泼")));
        when(aiClient.chatJson(anyString(), anyString(), anyInt()))
                .thenThrow(new AiException("AI 超时", null));

        service.generate(PROJECT_ID, List.of(1L, 2L));

        verify(statusService).failVersionsToReady(eq(PROJECT_ID), anyString());
        verify(statusService, never()).advanceVersionsReady(any(), any(), any());
    }

    @Test
    void 同步阶段参数校验_空风格400_未claim() {
        assertThrows(IllegalArgumentException.class, () -> service.generate(PROJECT_ID, List.of()));
        verify(statusService, never()).claimVersionsGenerating(any(), any(), anyString());
    }

    @Test
    void 同步阶段超过10风格400() {
        List<Long> ids = java.util.stream.LongStream.rangeClosed(1, 11).boxed().toList();
        assertThrows(IllegalArgumentException.class, () -> service.generate(PROJECT_ID, ids));
        verify(statusService, never()).claimVersionsGenerating(any(), any(), anyString());
    }

    @Test
    void 同步阶段brief未就绪409_未claim() {
        ArticleProjectEntity p = project("IMITATION", "DRAFT");
        p.setCurrentBriefId(null);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(p);

        assertThrows(NotReadyException.class, () -> service.generate(PROJECT_ID, List.of(1L)));
        verify(statusService, never()).claimVersionsGenerating(any(), any(), anyString());
    }

    @Test
    void claim冲突409_不触发AI() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project("IMITATION", "READY"));
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(styleMapper.selectBatchIds(List.of(1L))).thenReturn(List.of(style(1L, "正式")));
        doThrow(new IllegalStateException("该项目正在生成中")).when(statusService)
                .claimVersionsGenerating(eq(PROJECT_ID), any(), eq("生成版本"));

        assertThrows(IllegalStateException.class, () -> service.generate(PROJECT_ID, List.of(1L)));
        verify(aiClient, never()).chatJson(anyString(), anyString(), anyInt());
    }

    @Test
    void 追加生成_现有current时advance保留用户选择() {
        // advanceVersionsReady 的首版两拆分语义在 ProjectStatusServiceTest 断言;此处只验证委托参数
        ArticleProjectEntity p = project("IMITATION", "VERSIONS_READY");
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(p);
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(styleMapper.selectBatchIds(List.of(3L))).thenReturn(List.of(style(3L, "干货")));
        when(aiClient.chatJson(anyString(), anyString(), anyInt()))
                .thenReturn(new AiClient.ChatResult(VERSION_JSON, "m", 10));
        doAnswer(inv -> { ((ArticleVersionEntity) inv.getArgument(0)).setId(301L); return 1; })
                .when(versionMapper).insert(any(ArticleVersionEntity.class));

        service.generate(PROJECT_ID, List.of(3L));

        verify(statusService).advanceVersionsReady(eq(PROJECT_ID), eq(301L), eq(null));
    }

    // ==================== 09-27-shared-layout-rules R3/R4:排版分节档位随目标字数自适应 ====================

    /** 捕获 runGenerate 发出的 chatJson system prompt。 */
    private String capturedSystem(Integer wordCountTarget) {
        ArticleProjectEntity p = project("TOPIC", "READY");
        p.setWordCountTarget(wordCountTarget);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(p);
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(styleMapper.selectBatchIds(List.of(1L))).thenReturn(List.of(style(1L, "正式")));
        when(aiClient.chatJson(anyString(), anyString(), anyInt()))
                .thenReturn(new AiClient.ChatResult(VERSION_JSON, "m", 10));
        doAnswer(inv -> { ((ArticleVersionEntity) inv.getArgument(0)).setId(401L); return 1; })
                .when(versionMapper).insert(any(ArticleVersionEntity.class));
        service.generate(PROJECT_ID, List.of(1L));
        org.mockito.ArgumentCaptor<String> system = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(aiClient).chatJson(system.capture(), anyString(), eq(4096));
        return system.getValue();
    }

    /** AC-03:1500 → 3~5 个 / 2~3 段,且不再出现写死的 2~4。 */
    @Test
    void 分节档位_1500为中档3to5() {
        String sys = capturedSystem(1500);
        org.junit.jupiter.api.Assertions.assertTrue(
                sys.contains("全文用 3~5 个「## 小标题」分节,每节 2~3 段"), "1500 应为 3~5 个 / 2~3 段");
        org.junit.jupiter.api.Assertions.assertFalse(sys.contains("2~4 个"), "不得再出现写死的 2~4");
    }

    /** AC-03:5000 → 8~12 个 / 2~4 段。 */
    @Test
    void 分节档位_5000为顶档8to12每节2to4() {
        String sys = capturedSystem(5000);
        org.junit.jupiter.api.Assertions.assertTrue(
                sys.contains("全文用 8~12 个「## 小标题」分节,每节 2~4 段"), "5000 应为 8~12 个 / 2~4 段");
    }

    /** AC-03:null → 1500 档(3~5 / 2~3)。 */
    @Test
    void 分节档位_null回退1500档() {
        String sys = capturedSystem(null);
        org.junit.jupiter.api.Assertions.assertTrue(
                sys.contains("全文用 3~5 个「## 小标题」分节,每节 2~3 段"), "null 应回退 1500 档");
    }

    /** AC-03:其余两条排版 bullet(加粗 / 单段行数)逐字保留。 */
    @Test
    void 分节档位_其余bullet文案逐字保留() {
        String sys = capturedSystem(1500);
        org.junit.jupiter.api.Assertions.assertTrue(sys.contains("- 关键数据、核心结论用 **加粗** 突出,每节至少一处;"));
        org.junit.jupiter.api.Assertions.assertTrue(sys.contains("- 单段不超过 5 行,长段拆分。"));
    }

    // ==================== 10-02-brief-reasoning-maxtokens R5:内容描述替换关键词注入 user prompt ====================

    /** 捕获 runGenerate 发出的 chatJson user prompt。 */
    private String capturedUserPrompt(ArticleProjectEntity p) {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(p);
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(styleMapper.selectBatchIds(List.of(1L))).thenReturn(List.of(style(1L, "正式")));
        when(aiClient.chatJson(anyString(), anyString(), anyInt()))
                .thenReturn(new AiClient.ChatResult(VERSION_JSON, "m", 10));
        doAnswer(inv -> { ((ArticleVersionEntity) inv.getArgument(0)).setId(501L); return 1; })
                .when(versionMapper).insert(any(ArticleVersionEntity.class));
        service.generate(PROJECT_ID, List.of(1L));
        org.mockito.ArgumentCaptor<String> user = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(aiClient).chatJson(anyString(), user.capture(), eq(4096));
        return user.getValue();
    }

    /** R5:头部「内容描述：」替换原「关键词：」;删除独立「用户补充信息」块。 */
    @Test
    void 版本prompt_头部内容描述替换关键词_无用户补充信息块() {
        ArticleProjectEntity p = project("TOPIC", "READY");
        p.setContentDescription("围绕第2000座闪充站落成写一篇");

        String user = capturedUserPrompt(p);

        org.junit.jupiter.api.Assertions.assertTrue(user.contains("内容描述：围绕第2000座闪充站落成写一篇"), "头部应含内容描述");
        org.junit.jupiter.api.Assertions.assertFalse(user.contains("关键词："), "不得再出现关键词行");
        org.junit.jupiter.api.Assertions.assertFalse(user.contains("用户补充信息"), "独立补充信息块应删除(已并入头部)");
    }

    // ==================== S6:仿写链路版本标题优先级(选定 > AI title > 主题) ====================

    /** 捕获插入的版本实体(单风格单版)。 */
    private ArticleVersionEntity capturedInsertedVersion(ArticleProjectEntity p, String aiTitle) {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(p);
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(styleMapper.selectBatchIds(List.of(1L))).thenReturn(List.of(style(1L, "正式")));
        when(aiClient.chatJson(anyString(), anyString(), anyInt()))
                .thenReturn(new AiClient.ChatResult(
                        "{\"title\":\"" + aiTitle + "\",\"contentMd\":\"正文\"}", "m", 10));
        org.mockito.ArgumentCaptor<ArticleVersionEntity> cap =
                org.mockito.ArgumentCaptor.forClass(ArticleVersionEntity.class);
        service.generate(PROJECT_ID, List.of(1L));
        verify(versionMapper).insert(cap.capture());
        return cap.getValue();
    }

    /** 选定标题非空 → 版本标题采用选定标题(优先于 AI 产出标题)。 */
    @Test
    void 仿写选定标题非空_采用选定标题() {
        ArticleProjectEntity p = project("IMITATION", "READY");
        p.setSelectedTitle("选定标题甲");
        assertEquals("选定标题甲", capturedInsertedVersion(p, "AI标题").getTitle());
    }

    /** 选定标题为空 → 回退 AI 产出标题(现状保留)。 */
    @Test
    void 仿写选定标题为空_回退AI标题() {
        ArticleProjectEntity p = project("IMITATION", "READY");
        p.setSelectedTitle("  ");
        assertEquals("AI标题", capturedInsertedVersion(p, "AI标题").getTitle());
    }

    // ==================== 10-02-fix-meta-leak-in-article-body:多版本链路防御性对齐(R1/R2/R3) ====================

    /** 禁写断言块头(与 DeepWriterService.FORBIDDEN_CLAIMS_HEADER 同文)。 */
    private static final String FORBIDDEN_HEADER = "【禁止写入正文的断言】";

    /** 线上真实样本 brief 76 第 3 条 fact_risks:suggestion 是写给作者的祈使句(泄漏源)。 */
    private static final String BRIEF76_FACT_RISKS = "[{\"claim\":\"手册未提供比亚迪2026年度销量目标，也无完成进度数据\","
            + "\"riskLevel\":\"high\","
            + "\"suggestion\":\"此表述必须删除或改为「手册未披露年度目标，完成率无法计算」\"}]";

    /** 捕获主题分支 runGenerate 发出的 chatJson system prompt。 */
    private String capturedTopicSystem() {
        service.generate(PROJECT_ID, List.of(1L));
        org.mockito.ArgumentCaptor<String> system = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(aiClient).chatJson(system.capture(), anyString(), eq(4096));
        return system.getValue();
    }

    /** 捕获主题分支 chatJson user prompt(brief 带指定 factRisks)。 */
    private String capturedTopicUserPrompt(String factRisks) {
        ArticleBriefEntity b = brief();
        b.setFactRisks(factRisks);
        org.mockito.Mockito.clearInvocations(aiClient);   // 循环内逐轮独立捕获
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project("TOPIC", "READY"));
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(b);
        when(styleMapper.selectBatchIds(List.of(1L))).thenReturn(List.of(style(1L, "正式")));
        when(aiClient.chatJson(anyString(), anyString(), anyInt()))
                .thenReturn(new AiClient.ChatResult(VERSION_JSON, "m", 10));
        doAnswer(inv -> { ((ArticleVersionEntity) inv.getArgument(0)).setId(601L); return 1; })
                .when(versionMapper).insert(any(ArticleVersionEntity.class));
        service.generate(PROJECT_ID, List.of(1L));
        org.mockito.ArgumentCaptor<String> user = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(aiClient).chatJson(anyString(), user.capture(), eq(4096));
        return user.getValue();
    }

    /** 捕获落库的版本正文(contentMd 为 AI 产出的原文;genSource 决定清洗分支)。 */
    private String capturedContentMd(String genSource, String aiContentMd) {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(genSource, "READY"));
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(styleMapper.selectBatchIds(List.of(1L))).thenReturn(List.of(style(1L, "正式")));
        when(aiClient.chatJson(anyString(), anyString(), anyInt()))
                .thenReturn(new AiClient.ChatResult(
                        "{\"title\":\"标题\",\"contentMd\":\"" + aiContentMd + "\"}", "m", 10));
        org.mockito.ArgumentCaptor<ArticleVersionEntity> cap =
                org.mockito.ArgumentCaptor.forClass(ArticleVersionEntity.class);
        service.generate(PROJECT_ID, List.of(1L));
        verify(versionMapper).insert(cap.capture());
        return cap.getValue().getContentMd();
    }

    /** AC8:主题分支 user prompt 不再出现「事实风险点」与 factRisks 原文(suggestion 祈使句不回流)。 */
    @Test
    void 主题prompt_无事实风险点且suggestion不回流() {
        String user = capturedTopicUserPrompt(BRIEF76_FACT_RISKS);

        org.junit.jupiter.api.Assertions.assertFalse(user.contains("事实风险点"), "旧「事实风险点」行必须删除");
        org.junit.jupiter.api.Assertions.assertFalse(user.contains("按建议弱化或标注"), "旧注入话术必须删除");
        org.junit.jupiter.api.Assertions.assertFalse(user.contains("此表述必须删除或改为"), "suggestion 祈使句不得进 prompt");
        org.junit.jupiter.api.Assertions.assertFalse(user.contains("完成率无法计算"), "suggestion 原文不得进 prompt");
        org.junit.jupiter.api.Assertions.assertFalse(user.contains("suggestion"), "factRisks 原文不得整体注入");
        // claim 进禁写断言块(保留风险防护价值)
        org.junit.jupiter.api.Assertions.assertTrue(user.contains(FORBIDDEN_HEADER), "应注入禁写断言块");
        org.junit.jupiter.api.Assertions.assertTrue(user.contains("手册未提供比亚迪2026年度销量目标，也无完成进度数据"));
    }

    /** AC8:factRisks 为空/不可用 → 不注入禁写断言块(历史 brief 零回归)。 */
    @Test
    void 主题prompt_factRisks为空则不注入禁写块() {
        org.junit.jupiter.api.Assertions.assertFalse(capturedTopicUserPrompt(null).contains(FORBIDDEN_HEADER));
        org.junit.jupiter.api.Assertions.assertFalse(capturedTopicUserPrompt("[]").contains(FORBIDDEN_HEADER));
        org.junit.jupiter.api.Assertions.assertFalse(capturedTopicUserPrompt("[不是合法JSON").contains(FORBIDDEN_HEADER));
    }

    /** AC2:主题分支 system prompt 含读者视角铁律(与深度链路共用同一常量文本)。 */
    @Test
    void 主题system_含读者视角铁律() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project("TOPIC", "READY"));
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(styleMapper.selectBatchIds(List.of(1L))).thenReturn(List.of(style(1L, "正式")));
        when(aiClient.chatJson(anyString(), anyString(), anyInt()))
                .thenReturn(new AiClient.ChatResult(VERSION_JSON, "m", 10));
        doAnswer(inv -> { ((ArticleVersionEntity) inv.getArgument(0)).setId(701L); return 1; })
                .when(versionMapper).insert(any(ArticleVersionEntity.class));

        String sys = capturedTopicSystem();

        org.junit.jupiter.api.Assertions.assertTrue(sys.contains("读者视角铁律"), "主题分支应含读者视角铁律");
        org.junit.jupiter.api.Assertions.assertTrue(sys.contains("禁止在正文中解释"), "应显式禁止解释数据缺失");
    }

    /** R3:主题分支落库前清洗内部元话语(泄漏句消失、正常句保留)。 */
    @Test
    void 主题正文落库前清洗元话语() {
        String md = capturedContentMd("TOPIC",
                "9月销量46.36万辆，同比33.21%。\\n\\n合资品牌同期在华销量的具体数据，手册未提供，只能提示一个方向：份额承压仍在继续。");

        org.junit.jupiter.api.Assertions.assertFalse(md.contains("手册未提供"), "泄漏句必须删除");
        org.junit.jupiter.api.Assertions.assertEquals("9月销量46.36万辆，同比33.21%。", md, "正常句逐字保留、泄漏段整体消失");
    }

    /** R3:仿写分支不接清洗器(正文源自用户原文,二次清洗有误删作者原意风险)。 */
    @Test
    void 仿写正文不接清洗器_原样保留() {
        String md = capturedContentMd("IMITATION", "需要说明的是，该口径手册未披露，只能定性表述。");

        org.junit.jupiter.api.Assertions.assertTrue(md.contains("手册未披露"), "仿写分支必须跳过清洗器");
    }
}
