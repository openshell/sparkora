package com.sparkora.deep.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.ai.AiClient;
import com.sparkora.ai.AiException;
import com.sparkora.ai.ResearchPlanDto;
import com.sparkora.car.service.CarModelService;
import com.sparkora.domain.entity.ArticleBriefEntity;
import com.sparkora.domain.entity.ArticleProjectEntity;
import com.sparkora.mapper.ArticleBriefMapper;
import com.sparkora.mapper.ArticleProjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ResearchPlannerService 单测(10-03-gen-cognitive-redesign C2)。
 *
 * <p>覆盖:规划仅含事实问题(即使 LLM 输出意图题字段也不产出)、背景题兜底幂等、toolHints 1:1、
 * LLM 失败提额重试一次、task_brief 缺失抛 IllegalState、reasoning 落库。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResearchPlannerServiceTest {

    private static final Long PROJECT_ID = 3L;
    private static final Long BRIEF_ID = 7L;

    @Mock AiClient aiClient;
    @Mock ArticleBriefMapper briefMapper;
    @Mock ArticleProjectMapper projectMapper;
    @Mock CarModelService carModelService;

    private final ObjectMapper json = new ObjectMapper();
    ResearchPlannerService service;

    @BeforeEach
    void setUp() {
        service = new ResearchPlannerService(aiClient, json, briefMapper, projectMapper, carModelService);
        when(carModelService.list()).thenReturn(List.of());
    }

    private ArticleProjectEntity project() {
        ArticleProjectEntity p = new ArticleProjectEntity();
        p.setId(PROJECT_ID);
        p.setTopic("比亚迪9月销量发布");
        p.setContentDescription("围绕第2000座闪充站落成写一篇");
        p.setAudience("汽车行业分析师");
        p.setWordCountTarget(2200);
        return p;
    }

    private ArticleBriefEntity brief() {
        ArticleBriefEntity b = new ArticleBriefEntity();
        b.setId(BRIEF_ID);
        b.setProjectId(PROJECT_ID);
        b.setTaskBrief("{\"purpose\":{\"value\":\"介绍销量\",\"source\":\"USER\",\"confidence\":1.0}}");
        return b;
    }

    private static AiClient.TypedResult<ResearchPlanDto> typed(String planJson) throws Exception {
        ResearchPlanDto dto = new ObjectMapper().readValue(planJson, ResearchPlanDto.class);
        return new AiClient.TypedResult<>(dto, new AiClient.ChatResult(planJson, "m", 10, "stop", null));
    }

    private static AiClient.TypedResult<ResearchPlanDto> typed(String planJson, String reasoning) throws Exception {
        ResearchPlanDto dto = new ObjectMapper().readValue(planJson, ResearchPlanDto.class);
        return new AiClient.TypedResult<>(dto, new AiClient.ChatResult(planJson, "m", 10, "stop", reasoning));
    }

    @Test
    void 基于TaskBrief生成纯事实计划_落库列与READY() throws Exception {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        String plan = "{\"keyQuestions\":[\"比亚迪9月销量是多少\"],\"dataNeeds\":[\"销量数据\"],"
                + "\"hypotheses\":[\"销量同比增长\"],\"toolHints\":[{\"question\":\"比亚迪9月销量是多少\",\"tools\":[\"KB\",\"WEB\"]}]}";
        when(aiClient.structured(anyString(), anyString(), anyInt(), eq(ResearchPlanDto.class)))
                .thenReturn(typed(plan));

        ArticleBriefEntity out = service.plan(PROJECT_ID, BRIEF_ID);

        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(aiClient).structured(anyString(), user.capture(), eq(8192), eq(ResearchPlanDto.class));
        assertTrue(user.getValue().contains("主题:比亚迪9月销量发布"), "含主题");
        // 迁移自 ClarifyServicePlanTest:项目创建输入(内容描述/目标读者/目标字数)仍须注入 user prompt
        assertTrue(user.getValue().contains("内容描述:围绕第2000座闪充站落成写一篇"), "含内容描述");
        assertTrue(user.getValue().contains("目标读者:汽车行业分析师"), "含目标读者");
        assertTrue(user.getValue().contains("目标字数:2200"), "含目标字数");
        assertTrue(user.getValue().contains("意图契约"), "含 TaskBrief");
        assertTrue(user.getValue().contains("介绍销量"), "TaskBrief 内容注入");
        assertEquals("READY", out.getPlanStatus());
        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<ArticleBriefEntity>> uw =
                org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper.class);
        verify(briefMapper).update(eq(null), uw.capture());
        // 单列显式 set:research_plan/plan_status;不整体回写
        assertTrue(uw.getValue().getSqlSet().contains("research_plan"), "应显式 set research_plan");
        assertTrue(uw.getValue().getSqlSet().contains("plan_status"), "应显式 set plan_status");
    }

    /** 规划只含事实问题:LLM 若误产出意图题字段,DTO 无该字段 → 落库结构不含意图题。 */
    @Test
    void 规划仅含事实问题_无意图题字段() throws Exception {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        // 响应里带一个意图问题字段(旧 ClarifyPlanDto 的 questions),新 DTO 忽略之
        String plan = "{\"keyQuestions\":[\"价格是多少\"],\"dataNeeds\":[],\"hypotheses\":[],"
                + "\"toolHints\":[{\"question\":\"价格是多少\",\"tools\":[\"KB\"]}],"
                + "\"questions\":[{\"q\":\"目标读者是谁\",\"type\":\"input\"}]}";
        when(aiClient.structured(anyString(), anyString(), anyInt(), eq(ResearchPlanDto.class)))
                .thenReturn(typed(plan));

        ArticleBriefEntity out = service.plan(PROJECT_ID, BRIEF_ID);

        assertFalse(out.getResearchPlan().contains("目标读者是谁"), "意图题不得进入研究规划");
        assertFalse(out.getResearchPlan().contains("\"questions\""), "规划结构不得含 questions 字段");
        assertTrue(out.getResearchPlan().contains("keyQuestions"));
    }

    @Test
    void 首次截断_提额16384重试成功() throws Exception {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        String plan = "{\"keyQuestions\":[\"价格\"],\"dataNeeds\":[],\"hypotheses\":[],"
                + "\"toolHints\":[{\"question\":\"价格\",\"tools\":[\"KB\"]}]}";
        when(aiClient.structured(anyString(), anyString(), anyInt(), eq(ResearchPlanDto.class)))
                .thenThrow(new AiException("AI 输出被 max_tokens 截断", null))
                .thenReturn(typed(plan));

        service.plan(PROJECT_ID, BRIEF_ID);

        verify(aiClient).structured(anyString(), anyString(), eq(16384), eq(ResearchPlanDto.class));
    }

    @Test
    void taskBrief缺失_抛IllegalState() throws Exception {
        ArticleBriefEntity b = brief();
        b.setTaskBrief(null);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(b);

        assertThrows(IllegalStateException.class, () -> service.plan(PROJECT_ID, BRIEF_ID));
    }

    @Test
    void brief不属于项目_抛IllegalArgument() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        ArticleBriefEntity b = brief();
        b.setProjectId(99L);
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(b);

        assertThrows(IllegalArgumentException.class, () -> service.plan(PROJECT_ID, BRIEF_ID));
    }

    @Test
    void reasoning透出_落库() throws Exception {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        String plan = "{\"keyQuestions\":[\"价格\"],\"dataNeeds\":[],\"hypotheses\":[],"
                + "\"toolHints\":[{\"question\":\"价格\",\"tools\":[\"KB\"]}]}";
        when(aiClient.structured(anyString(), anyString(), anyInt(), eq(ResearchPlanDto.class)))
                .thenReturn(typed(plan, "推理:先分析销量再结论"));

        ArticleBriefEntity out = service.plan(PROJECT_ID, BRIEF_ID);

        assertEquals("推理:先分析销量再结论", out.getResearchReasoning(), "思考过程应落库");
    }

    /** 背景题兜底:信号词命中且无背景型问题时补一条,并与 toolHints 1:1;可重复调用幂等。 */
    @Test
    void 背景题兜底且幂等_toolHints一比一() throws Exception {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        String noBg = "{\"keyQuestions\":[\"销量多少\"],\"dataNeeds\":[],\"hypotheses\":[],"
                + "\"toolHints\":[{\"question\":\"销量多少\",\"tools\":[\"KB\"]}]}";
        when(aiClient.structured(anyString(), anyString(), anyInt(), eq(ResearchPlanDto.class)))
                .thenReturn(typed(noBg));

        ArticleBriefEntity out = service.plan(PROJECT_ID, BRIEF_ID);

        assertTrue(out.getResearchPlan().contains("行业背景"), "命中信号词应追加背景题: " + out.getResearchPlan());
        var node = json.readTree(out.getResearchPlan());
        assertEquals(2, node.path("keyQuestions").size());
        assertEquals(2, node.path("toolHints").size(), "toolHints 必须与 keyQuestions 1:1");
        assertEquals(node.path("keyQuestions").get(1).asText(),
                node.path("toolHints").get(1).path("question").asText(), "补题与 toolHint 对齐");
    }

    // ===== 迁移自 ClarifyServiceTest:确定性兜底纯函数 =====

    private Map<String, Object> plan(List<String> questions, com.fasterxml.jackson.databind.node.ArrayNode hints) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("keyQuestions", questions);
        p.put("toolHints", hints);
        return p;
    }

    private com.fasterxml.jackson.databind.node.ArrayNode hints(String... questionToolsPairs) {
        com.fasterxml.jackson.databind.node.ArrayNode arr = json.createArrayNode();
        for (String q : questionToolsPairs) {
            var h = arr.objectNode();
            h.put("question", q);
            var t = h.putArray("tools");
            t.add("KB");
            t.add("WEB");
            arr.add(h);
        }
        return arr;
    }

    @Test
    void 信号词命中且无背景题_补一条且toolHint对齐() {
        Map<String, Object> p = plan(new ArrayList<>(List.of("价格是多少", "续航多长")), hints("价格是多少", "续航多长"));

        ResearchPlannerService.ensureBackgroundQuestion(p, "如何看待比亚迪宣布建成第2000座闪充站", null);

        @SuppressWarnings("unchecked")
        List<String> questions = (List<String>) p.get("keyQuestions");
        assertEquals(3, questions.size(), "应补一条背景题");
        assertTrue(questions.get(2).contains("行业背景"));
        var h = (com.fasterxml.jackson.databind.node.ArrayNode) p.get("toolHints");
        assertEquals(3, h.size(), "toolHints 与 keyQuestions 1:1");
        assertEquals(questions.get(2), h.get(2).path("question").asText());
    }

    @Test
    void 已有背景型问题_幂等不重复补() {
        Map<String, Object> p = plan(new ArrayList<>(List.of("价格是多少", "该战略的行业背景与意义是什么")), hints("a", "b"));
        ResearchPlannerService.ensureBackgroundQuestion(p, "比亚迪宣布新战略", null);
        @SuppressWarnings("unchecked")
        List<String> questions = (List<String>) p.get("keyQuestions");
        assertEquals(2, questions.size(), "已含背景题不得重复补");
    }

    @Test
    void 窄参数主题_未命中信号词_不补() {
        Map<String, Object> p = plan(new ArrayList<>(List.of("价格是多少", "尺寸参数如何")), hints("a", "b"));
        ResearchPlannerService.ensureBackgroundQuestion(p, "海狮08EV 参数详解", null);
        @SuppressWarnings("unchecked")
        List<String> questions = (List<String>) p.get("keyQuestions");
        assertEquals(2, questions.size(), "窄参数主题不得强制补背景题");
    }

    @Test
    void 信号词在内容描述中也命中() {
        Map<String, Object> p = plan(new ArrayList<>(List.of("价格是多少")), hints("a"));
        ResearchPlannerService.ensureBackgroundQuestion(p, "海狮08EV", "围绕第2000座闪充站落成写一篇");
        @SuppressWarnings("unchecked")
        List<String> questions = (List<String>) p.get("keyQuestions");
        assertEquals(2, questions.size());
    }

    @Test
    void toolHints非数组_不抛异常_仍补问题() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("keyQuestions", new ArrayList<>(List.of("价格是多少")));
        p.put("toolHints", "not-an-array");
        assertDoesNotThrow(() -> ResearchPlannerService.ensureBackgroundQuestion(p, "比亚迪宣布建成新工厂", null));
        @SuppressWarnings("unchecked")
        List<String> questions = (List<String>) p.get("keyQuestions");
        assertEquals(2, questions.size());
    }

    @Test
    void keyQuestions非列表_不抛异常() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("keyQuestions", "not-a-list");
        assertDoesNotThrow(() -> ResearchPlannerService.ensureBackgroundQuestion(p, "比亚迪宣布建成", null));
    }

    @Test
    void 主题空白_背景题使用通用措辞() {
        Map<String, Object> p = plan(new ArrayList<>(List.of("价格")), hints("a"));
        ResearchPlannerService.ensureBackgroundQuestion(p, "", "比亚迪宣布落成");
        @SuppressWarnings("unchecked")
        List<String> questions = (List<String>) p.get("keyQuestions");
        assertEquals(2, questions.size());
        assertTrue(questions.get(1).startsWith("该主题"));
    }

    // ===== 迁移自 ClarifyServiceTest:背景型问题判定 =====

    @Test
    void isBackgroundQuestion_背景词表命中() {
        assertTrue(ResearchPlannerService.isBackgroundQuestion("该车型的行业背景与意义是什么?"));
        assertTrue(ResearchPlannerService.isBackgroundQuestion("企业战略与长期目标?"));
        assertTrue(ResearchPlannerService.isBackgroundQuestion("发展规划与布局如何?"));
        assertTrue(ResearchPlannerService.isBackgroundQuestion("为什么会这样?"));
        assertTrue(ResearchPlannerService.isBackgroundQuestion("发展历程回顾"));
    }

    @Test
    void isBackgroundQuestion_主题信号词命中() {
        assertTrue(ResearchPlannerService.isBackgroundQuestion("第2000座闪充站落成的意义?"));
        assertTrue(ResearchPlannerService.isBackgroundQuestion("这次发布会宣布了什么?"));
        assertTrue(ResearchPlannerService.isBackgroundQuestion("该里程碑事件的影响?"));
    }

    @Test
    void isBackgroundQuestion_参数型问题为负例() {
        assertEquals(false, ResearchPlannerService.isBackgroundQuestion("海狮08的价格是多少?"));
        assertEquals(false, ResearchPlannerService.isBackgroundQuestion("续航里程与充电速度?"));
        assertEquals(false, ResearchPlannerService.isBackgroundQuestion("车身尺寸参数?"));
    }

    @Test
    void isBackgroundQuestion_空输入为false() {
        assertEquals(false, ResearchPlannerService.isBackgroundQuestion(null));
        assertEquals(false, ResearchPlannerService.isBackgroundQuestion(""));
        assertEquals(false, ResearchPlannerService.isBackgroundQuestion("   "));
    }

    // ===== 10-04-serper-provider A-R3:时效题判定(垂直路由) =====

    @Test
    void isTimeSensitiveQuestion_时效词命中() {
        assertTrue(ResearchPlannerService.isTimeSensitiveQuestion("最近的销量如何?"));
        assertTrue(ResearchPlannerService.isTimeSensitiveQuestion("最新的价格动态?"));
        assertTrue(ResearchPlannerService.isTimeSensitiveQuestion("今年发布了哪些车型?"));
        assertTrue(ResearchPlannerService.isTimeSensitiveQuestion("当前市场表现?"));
    }

    @Test
    void isTimeSensitiveQuestion_参数题为负例() {
        assertEquals(false, ResearchPlannerService.isTimeSensitiveQuestion("海狮08的价格是多少?"));
        assertEquals(false, ResearchPlannerService.isTimeSensitiveQuestion("续航里程与充电速度?"));
        assertEquals(false, ResearchPlannerService.isTimeSensitiveQuestion("车身尺寸参数?"));
    }

    @Test
    void isTimeSensitiveQuestion_空输入为false() {
        assertEquals(false, ResearchPlannerService.isTimeSensitiveQuestion(null));
        assertEquals(false, ResearchPlannerService.isTimeSensitiveQuestion(""));
        assertEquals(false, ResearchPlannerService.isTimeSensitiveQuestion("   "));
    }
}
