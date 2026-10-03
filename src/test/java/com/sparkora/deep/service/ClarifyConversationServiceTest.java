package com.sparkora.deep.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.ai.AiClient;
import com.sparkora.ai.AiException;
import com.sparkora.ai.ClarifyNextDto;
import com.sparkora.ai.TaskBriefDto;
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
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ClarifyConversationService 单测（C1 意图澄清对话，Mockito 无 DB/无真实 AI）。
 *
 * 覆盖：必要槽位硬兜底（够即停/不足追问）、LLM 收敛才产出 TaskBrief、TaskBrief source/confidence 装配、
 * 首次失败提额 16384 重试一次、两次失败降级不落错误状态、撞唯一索引转 409、会话 JSON 解析容错。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClarifyConversationServiceTest {

    private static final Long PROJECT_ID = 1L;
    private static final Long BRIEF_ID = 2L;

    @Mock ArticleProjectMapper projectMapper;
    @Mock ArticleBriefMapper briefMapper;
    @Mock AiClient aiClient;
    @Mock com.sparkora.car.service.CarModelService carModelService;

    private final ObjectMapper json = new ObjectMapper();
    ClarifyConversationService service;

    @BeforeEach
    void setUp() {
        service = new ClarifyConversationService(aiClient, json, briefMapper, projectMapper, carModelService);
    }

    private ArticleProjectEntity project() {
        ArticleProjectEntity p = new ArticleProjectEntity();
        p.setId(PROJECT_ID);
        p.setTopic("比亚迪海狮08上市解析");
        p.setStatus("DRAFT");
        return p;
    }

    private ArticleBriefEntity askingBrief(String sessionJson) {
        ArticleBriefEntity b = new ArticleBriefEntity();
        b.setId(BRIEF_ID);
        b.setProjectId(PROJECT_ID);
        b.setGenMode("DEEP");
        b.setClarifyStatus("ASKING");
        b.setClarifySession(sessionJson);
        return b;
    }

    /** 构造会话 JSON：slots 已填槽位 id 列表；currentQuestion 指向待答问题。 */
    private String session(List<String> filledSlots, String currentSlot) {
        StringBuilder slots = new StringBuilder("[");
        for (int i = 0; i < filledSlots.size(); i++) {
            String id = filledSlots.get(i);
            if (i > 0) slots.append(',');
            String value = switch (id) {
                case "mustCover", "angles", "mustAvoid" -> "[\"要点A\"]";
                default -> "已有值-" + id;
            };
            slots.append("{\"id\":\"").append(id).append("\",\"label\":\"").append(id)
                    .append("\",\"value\":").append(value.startsWith("[") ? value : "\"" + value + "\"")
                    .append(",\"source\":\"USER\",\"confidence\":0.9,\"done\":true}");
        }
        slots.append(']');
        String q = "{\"id\":\"" + currentSlot + "\",\"text\":\"问" + currentSlot + "\",\"type\":\"single\","
                + "\"options\":[\"选项1\",\"选项2\"],\"required\":true,\"slotId\":\"" + currentSlot + "\"}";
        return "{\"status\":\"ASKING\",\"converged\":false,\"slots\":" + slots
                + ",\"turns\":[],\"currentQuestion\":" + q + "}";
    }

    private AiClient.ChatResult chat() {
        return new AiClient.ChatResult("{}", "glm-5.2", 100);
    }

    private ClarifyNextDto nextConverged() {
        ClarifyNextDto dto = new ClarifyNextDto();
        dto.setConverged(true);
        return dto;
    }

    private ClarifyNextDto nextQuestion(String slotId) {
        ClarifyNextDto dto = new ClarifyNextDto();
        dto.setConverged(false);
        ClarifyNextDto.Question q = new ClarifyNextDto.Question();
        q.setId(slotId);
        q.setSlotId(slotId);
        q.setText("问" + slotId);
        q.setType("single");
        q.setOptions(List.of("甲", "乙"));
        q.setRequired(true);
        dto.setNextQuestion(q);
        return dto;
    }

    private TaskBriefDto taskBriefDto() {
        TaskBriefDto dto = new TaskBriefDto();
        dto.setPurpose("让读者了解海狮08竞争力");
        dto.setAudience("潜在购车用户");
        dto.setMustCover(List.of("价格", "续航"));
        dto.setTone("专业理性");
        dto.setLengthTarget(1500);
        return dto;
    }

    private AiClient.TypedResult<ClarifyNextDto> typed(ClarifyNextDto dto) {
        return new AiClient.TypedResult<>(dto, chat());
    }

    private AiClient.TypedResult<TaskBriefDto> typedTb(TaskBriefDto dto) {
        return new AiClient.TypedResult<>(dto, chat());
    }

    /** 捕获 brief 表 UpdateWrapper(本服务列写入一律 UpdateWrapper 部分更新,不 updateById 全量回写)。 */
    @SuppressWarnings("unchecked")
    private UpdateWrapper<ArticleBriefEntity> capturedUpdateWrapper() {
        ArgumentCaptor<UpdateWrapper<ArticleBriefEntity>> captor =
                (ArgumentCaptor) ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(briefMapper).update(isNull(), captor.capture());
        return captor.getValue();
    }

    // ==================== 1. 充分性收敛：不足追问 / 够即停 ====================

    /** 必要槽位必须覆盖 缺失 → 即使 LLM converged=true 仍强制续问(本轮答的是可选槽位 tone)。 */
    @Test
    void 必要槽位未填_忽略LLM收敛_强制续问() {
        // 只有 purpose/audience,缺 mustCover;当前问题问的是可选槽位 tone
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(askingBrief(session(List.of("purpose", "audience"), "tone")));
        when(aiClient.structured(anyString(), anyString(), eq(8192), eq(ClarifyNextDto.class)))
                .thenReturn(typed(nextConverged()));

        Map<String, Object> out = service.answer(PROJECT_ID, BRIEF_ID, "tone", "专业理性");

        assertFalse((Boolean) out.get("converged"), "必要槽位未齐不得收敛");
        assertNotNull(out.get("question"), "必须给出下一问");
        JsonNode q = (JsonNode) out.get("question");
        assertEquals("mustCover", q.path("slotId").asText(), "应继续追问缺失的必要槽位");
        // 收敛装配不应被调用
        verify(aiClient, never()).structured(anyString(), anyString(), anyInt(), eq(TaskBriefDto.class));
    }

    /** 必要槽位已填 + LLM converged → 产出 TaskBrief 并置 CONVERGED。 */
    @Test
    void 必要槽位已填_LLM收敛_产出TaskBrief() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(
                askingBrief(session(List.of("purpose", "audience", "mustCover"), "tone")));
        when(aiClient.structured(anyString(), anyString(), eq(8192), eq(ClarifyNextDto.class)))
                .thenReturn(typed(nextConverged()));
        when(aiClient.structured(anyString(), anyString(), eq(8192), eq(TaskBriefDto.class)))
                .thenReturn(typedTb(taskBriefDto()));

        Map<String, Object> out = service.answer(PROJECT_ID, BRIEF_ID, "tone", "专业理性");

        assertTrue((Boolean) out.get("converged"));
        assertNotNull(out.get("taskBrief"));
        // 落库：显式 set task_brief/clarify_session/clarify_status=CONVERGED,且不触碰项目 status 列
        UpdateWrapper<ArticleBriefEntity> uw = capturedUpdateWrapper();
        // 解析实际列名(MyBatis-Plus getSqlSet 形如 "clarify_session=#{...},task_brief=#{...},...")
        java.util.List<String> cols = java.util.Arrays.stream(uw.getSqlSet().split(","))
                .map(s -> s.trim().split("=")[0]).toList();
        assertTrue(cols.containsAll(java.util.List.of("clarify_session", "task_brief", "clarify_status")),
                "收敛必须仅/至少写 clarify_session/task_brief/clarify_status,实际=" + cols);
        assertFalse(cols.contains("status"), "澄清不写项目状态机列 status");
        assertFalse(cols.contains("plan_status"), "澄清不写研究计划态 plan_status");
        assertTrue(uw.getParamNameValuePairs().values().stream().anyMatch("CONVERGED"::equals),
                "clarify_status 值应为 CONVERGED");
    }

    /** TaskBrief 每槽位含 value/source/confidence(来自会话槽位元数据)。 */
    @Test
    void TaskBrief装配含source与confidence() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(
                askingBrief(session(List.of("purpose", "audience", "mustCover"), "tone")));
        when(aiClient.structured(anyString(), anyString(), eq(8192), eq(ClarifyNextDto.class)))
                .thenReturn(typed(nextConverged()));
        when(aiClient.structured(anyString(), anyString(), eq(8192), eq(TaskBriefDto.class)))
                .thenReturn(typedTb(taskBriefDto()));

        Map<String, Object> out = service.answer(PROJECT_ID, BRIEF_ID, "tone", "专业理性");
        JsonNode tb = (JsonNode) out.get("taskBrief");

        // 标量槽位 {value,source,confidence}
        JsonNode purpose = tb.path("purpose");
        assertTrue(purpose.has("value") && purpose.has("source") && purpose.has("confidence"),
                "标量槽位必须包 {value,source,confidence}");
        assertEquals("USER", purpose.path("source").asText());
        // 多值槽位为数组,每项带 source/confidence
        JsonNode mustCover = tb.path("mustCover");
        assertTrue(mustCover.isArray() && mustCover.size() > 0);
        assertTrue(mustCover.get(0).has("source") && mustCover.get(0).has("confidence"));
        // slotMeta 每槽位标 filled/source
        assertTrue(tb.path("slotMeta").isArray() && tb.path("slotMeta").size() > 0);
    }

    // ==================== 2. LLM 失败提额重试 / 降级 ====================

    /** 首次 8192 失败 → 提额 16384 重试一次并成功。 */
    @Test
    void 下一问首次失败_提额16384重试成功() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(askingBrief(session(List.of("purpose"), "audience")));
        when(aiClient.structured(anyString(), anyString(), eq(8192), eq(ClarifyNextDto.class)))
                .thenThrow(new AiException("AI 输出被 max_tokens 截断（finish_reason=length）", null));
        when(aiClient.structured(anyString(), anyString(), eq(16384), eq(ClarifyNextDto.class)))
                .thenReturn(typed(nextQuestion("audience")));

        Map<String, Object> out = service.answer(PROJECT_ID, BRIEF_ID, "audience", "选项1");

        verify(aiClient).structured(anyString(), anyString(), eq(16384), eq(ClarifyNextDto.class));
        verify(aiClient, times(2)).structured(anyString(), anyString(), anyInt(), eq(ClarifyNextDto.class));
        assertNotNull(out.get("question"));
    }

    /** 两次均失败 → 降级为确定性追问,不抛异常、不落错误状态。 */
    @Test
    void 两次均失败_降级追问不落错误状态() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(askingBrief(session(List.of("purpose"), "audience")));
        when(aiClient.structured(anyString(), anyString(), eq(8192), eq(ClarifyNextDto.class)))
                .thenThrow(new AiException("截断", null));
        when(aiClient.structured(anyString(), anyString(), eq(16384), eq(ClarifyNextDto.class)))
                .thenThrow(new AiException("仍失败", null));

        Map<String, Object> out = service.answer(PROJECT_ID, BRIEF_ID, "audience", "选项1");

        verify(aiClient, times(2)).structured(anyString(), anyString(), anyInt(), eq(ClarifyNextDto.class));
        assertFalse((Boolean) out.get("converged"));
        assertNotNull(out.get("question"), "降级仍应给出确定性追问");
        // 会话仍落库(仅 clarify_session),不置错误/中止态、不写 task_brief 或项目状态列
        UpdateWrapper<ArticleBriefEntity> uw = capturedUpdateWrapper();
        java.util.List<String> cols = java.util.Arrays.stream(uw.getSqlSet().split(","))
                .map(s -> s.trim().split("=")[0]).toList();
        assertEquals(java.util.List.of("clarify_session"), cols,
                "降级仅写 clarify_session(不写 clarify_status 为错误态/不写 task_brief)");
    }

    /** TaskBrief 两次失败 → 收敛时按会话信息兜底装配(不抛,必要槽位用已有值)。 */
    @Test
    void TaskBrief两次失败_兜底装配不抛() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(
                askingBrief(session(List.of("purpose", "audience", "mustCover"), "tone")));
        when(aiClient.structured(anyString(), anyString(), eq(8192), eq(ClarifyNextDto.class)))
                .thenReturn(typed(nextConverged()));
        when(aiClient.structured(anyString(), anyString(), eq(8192), eq(TaskBriefDto.class)))
                .thenThrow(new AiException("截断", null));
        when(aiClient.structured(anyString(), anyString(), eq(16384), eq(TaskBriefDto.class)))
                .thenThrow(new AiException("仍失败", null));

        Map<String, Object> out = service.answer(PROJECT_ID, BRIEF_ID, "tone", "专业理性");

        assertTrue((Boolean) out.get("converged"), "两次失败仍应基于会话信息兜底收敛");
        JsonNode tb = (JsonNode) out.get("taskBrief");
        assertEquals("已有值-purpose", tb.path("purpose").path("value").asText(), "兜底取会话已有值");
    }

    // ==================== 3. 并发 / 自愈 ====================

    /** 撞部分唯一索引 uq_brief_clarify_asking → IllegalStateException(409)。 */
    @Test
    void 撞唯一索引_转IllegalStateException() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        doAnswer(inv -> { throw new DuplicateKeyException("uq_brief_clarify_asking"); })
                .when(briefMapper).insert(any(ArticleBriefEntity.class));

        assertThrows(IllegalStateException.class, () -> service.start(PROJECT_ID));
    }

    // ==================== 4. 会话 JSON 解析容错 ====================

    @Test
    void 会话JSON畸形_解析不抛回退空会话() {
        JsonNode s = service.parseSession("{not-json");
        assertEquals("ASKING", s.path("status").asText());
        assertTrue(s.path("slots").isArray());

        JsonNode nullSession = service.parseSession(null);
        assertEquals("ASKING", nullSession.path("status").asText());

        JsonNode arr = service.parseSession("[1,2,3]");   // 非对象 → 回退
        assertEquals("ASKING", arr.path("status").asText());
    }

    @Test
    void applySlot_非法source归INFERRED_confidence截断_空值忽略() {
        var session = service.emptySession();
        service.applySlot(session, "purpose", "让读者了解", "WEIRD", 9.0);
        JsonNode slot = session.path("slots").get(0);
        assertEquals("INFERRED", slot.path("source").asText());
        assertEquals(1.0, slot.path("confidence").asDouble(), 1e-9);
        // 空值忽略,不新增槽位
        service.applySlot(session, "audience", "  ", "USER", 1.0);
        assertEquals(1, session.path("slots").size());
    }
}
