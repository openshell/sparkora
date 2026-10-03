package com.sparkora.deep.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sparkora.ai.AiClient;
import com.sparkora.ai.AiException;
import com.sparkora.ai.BlueprintDto;
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

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BlueprintService 单测（10-03-gen-cognitive-redesign C3；Mockito 不连库/不调 AI）。
 *
 * <p>覆盖：evidenceMap 白名单绑定（非法 key 剔除）、coverage 三态 + MISSING/PARTIAL 入 gaps、
 * 质量信号确定性计算、8192→16384 提额重试、两次失败抛 AiException、confirm 评审门（含幂等/无蓝图 409 /
 * 编辑重算）、重生成覆盖回 REVIEWING、只写约定列（不含项目 status / plan_status）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BlueprintServiceTest {

    private static final Long PROJECT_ID = 3L;
    private static final Long BRIEF_ID = 7L;

    @Mock AiClient aiClient;
    @Mock ArticleBriefMapper briefMapper;
    @Mock ArticleProjectMapper projectMapper;

    private final ObjectMapper json = new ObjectMapper();
    BlueprintService service;

    @BeforeEach
    void setUp() {
        service = new BlueprintService(aiClient, json, briefMapper, projectMapper);
    }

    // ==================== 夹具 ====================

    private ArticleProjectEntity project() {
        ArticleProjectEntity p = new ArticleProjectEntity();
        p.setId(PROJECT_ID);
        p.setTopic("如何看待比亚迪建成第2000座高速闪充站");
        p.setContentDescription("围绕第2000座闪充站落成写一篇");
        p.setAudience("汽车行业分析师");
        p.setWordCountTarget(2400);
        return p;
    }

    private ArticleBriefEntity brief() {
        ArticleBriefEntity b = new ArticleBriefEntity();
        b.setId(BRIEF_ID);
        b.setProjectId(PROJECT_ID);
        b.setGenMode("DEEP");
        b.setTaskBrief("{\"purpose\":{\"value\":\"介绍闪充站意义\"},"
                + "\"audience\":{\"value\":\"行业分析师\"},"
                + "\"mustCover\":[{\"value\":\"2000座落成\"}]}");
        b.setFactSheet("{\"entries\":["
                + "{\"key\":\"年内建成2万座\",\"value\":\"2万座\"},"
                + "{\"key\":\"充电功率600kW\",\"value\":\"600kW\"},"
                + "{\"key\":\"覆盖30城\",\"value\":\"30城\"}],\"gaps\":[],\"warnings\":[]}");
        return b;
    }

    /** 蓝图 DTO JSON：S1 全有效 / S2 部分有效 / S3 全非法 / S4 空。 */
    private static final String BLUEPRINT_JSON = """
            {
              "thesis": "闪充网络是新能源竞争的下半场",
              "audienceAngle": "面向行业分析师,从网络密度切入",
              "narrativeArc": "问题→张力→解答→行动",
              "argumentStructure": [
                {"sectionId":"S1","heading":"开场","role":"HOOK","claim":"补能焦虑"},
                {"sectionId":"S2","heading":"网络","role":"ARGUMENT","claim":"密度领先"},
                {"sectionId":"S3","heading":"风险","role":"COUNTER","claim":"成本压力"},
                {"sectionId":"S4","heading":"结论","role":"CONCLUSION","claim":"行动"}
              ],
              "evidenceMap": [
                {"sectionId":"S1","argument":"补能焦虑","evidenceNeeded":"规模","entryKeys":["年内建成2万座","覆盖30城"],"coverage":"MISSING"},
                {"sectionId":"S2","argument":"密度领先","evidenceNeeded":"功率","entryKeys":["充电功率600kW","编造条目"],"coverage":"COVERED"},
                {"sectionId":"S3","argument":"成本压力","evidenceNeeded":"成本","entryKeys":["编造条目A"],"coverage":"COVERED"},
                {"sectionId":"S4","argument":"行动","evidenceNeeded":"数据","entryKeys":[],"coverage":"COVERED"}
              ],
              "constraints": ["不得承诺具体销量"],
              "gaps": [{"sectionId":"S2","reason":"模型自报缺口"}]
            }
            """;

    private AiClient.TypedResult<BlueprintDto> typed(String blueprintJson) {
        try {
            BlueprintDto dto = new ObjectMapper().readValue(blueprintJson, BlueprintDto.class);
            return new AiClient.TypedResult<>(dto,
                    new AiClient.ChatResult(blueprintJson, "glm-5.2", 321, "stop", null));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void stubHappy(String blueprintJson) {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(aiClient.structured(anyString(), anyString(), eq(8192), eq(BlueprintDto.class)))
                .thenReturn(typed(blueprintJson));
    }

    // ==================== evidenceMap 绑定 + coverage + gaps ====================

    @Test
    void 生成_白名单绑定_coverage三态_MISSING_PARTIAL入gaps() throws Exception {
        stubHappy(BLUEPRINT_JSON);

        ArticleBriefEntity out = service.generate(PROJECT_ID, BRIEF_ID);
        JsonNode blueprint = json.readTree(out.getWritingBlueprint());
        JsonNode em = blueprint.get("evidenceMap");

        // S1：两个 key 全有效 → COVERED，LLM 谎报的 MISSING 被覆盖；非法 key 被剔除
        JsonNode s1 = em.get(0);
        assertEquals("COVERED", s1.path("coverage").asText());
        assertEquals(2, s1.path("entryKeys").size());

        // S2：1 有效 / 2 原始 → PARTIAL，非法 key 剔除
        JsonNode s2 = em.get(1);
        assertEquals("PARTIAL", s2.path("coverage").asText());
        assertEquals(1, s2.path("entryKeys").size(), "非法 key 应剔除");
        assertEquals("充电功率600kW", s2.path("entryKeys").get(0).asText());

        // S3：全非法 → MISSING，entryKeys 清空
        JsonNode s3 = em.get(2);
        assertEquals("MISSING", s3.path("coverage").asText());
        assertEquals(0, s3.path("entryKeys").size());

        // S4：空 → MISSING
        assertEquals("MISSING", em.get(3).path("coverage").asText());

        // gaps：模型自报 S2 保留；S3/S4 MISSING 追加；S1 COVERED 不入
        JsonNode gaps = blueprint.get("gaps");
        assertTrue(gaps.size() >= 3, "S2 + S3 + S4 缺口");
        Set<String> gapSections = new java.util.HashSet<>();
        for (JsonNode g : gaps) gapSections.add(g.path("sectionId").asText());
        assertTrue(gapSections.contains("S3"));
        assertTrue(gapSections.contains("S4"));
        assertTrue(gapSections.contains("S2"), "模型自报 S2 缺口应保留");
        assertFalse(gapSections.contains("S1"), "COVERED 不得入缺口");
        // MISSING 论点保留但标注
        assertEquals(4, blueprint.path("argumentStructure").size());

        // 质量信号落库：argumentDensity=4, evidenceCoverage=1/4, gapCount=3, consistency=1
        JsonNode q = json.readTree(out.getBlueprintQuality());
        assertEquals(4, q.path("argumentDensity").asInt());
        assertEquals(0.25, q.path("evidenceCoverage").asDouble(), 1e-9);
        assertEquals(3, q.path("gapCount").asInt());
        assertEquals(1.0, q.path("taskBriefConsistency").asDouble(), 1e-9);
        // 蓝图内嵌同一份 quality
        assertEquals(q.get("gapCount").asInt(), blueprint.path("quality").path("gapCount").asInt());
        assertEquals("REVIEWING", out.getBlueprintStatus());
    }

    @Test
    void userPrompt_注入TaskBrief_研究假设_事实手册() {
        ArticleBriefEntity b = brief();
        b.setResearchPlan("{\"hypotheses\":[\"假设A:换电将让位于闪充\"]}");
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(b);
        when(aiClient.structured(anyString(), anyString(), eq(8192), eq(BlueprintDto.class)))
                .thenReturn(typed(BLUEPRINT_JSON));

        service.generate(PROJECT_ID, BRIEF_ID);

        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(aiClient).structured(anyString(), user.capture(), eq(8192), eq(BlueprintDto.class));
        String prompt = user.getValue();
        assertTrue(prompt.contains("主题:如何看待比亚迪建成第2000座高速闪充站"));
        assertTrue(prompt.contains("内容描述:围绕第2000座闪充站落成写一篇"));
        assertTrue(prompt.contains("目标读者:汽车行业分析师"));
        assertTrue(prompt.contains("目标字数:2400"));
        assertTrue(prompt.contains("介绍闪充站意义"), "TaskBrief 注入");
        assertTrue(prompt.contains("假设A:换电将让位于闪充"), "研究假设注入");
        assertTrue(prompt.contains("年内建成2万座"), "事实手册注入");
    }

    // ==================== 提额重试 ====================

    @Test
    void 首次失败_提额16384重试成功() {
        stubHappy(BLUEPRINT_JSON);
        when(aiClient.structured(anyString(), anyString(), eq(8192), eq(BlueprintDto.class)))
                .thenThrow(new AiException("AI 输出被 max_tokens 截断", null))
                .thenReturn(typed(BLUEPRINT_JSON));
        when(aiClient.structured(anyString(), anyString(), eq(16384), eq(BlueprintDto.class)))
                .thenReturn(typed(BLUEPRINT_JSON));

        ArticleBriefEntity out = service.generate(PROJECT_ID, BRIEF_ID);

        verify(aiClient).structured(anyString(), anyString(), eq(16384), eq(BlueprintDto.class));
        verify(aiClient, times(2)).structured(anyString(), anyString(), anyInt(), eq(BlueprintDto.class));
        assertEquals("REVIEWING", out.getBlueprintStatus());
    }

    @Test
    void 两次均失败_抛AiException() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(aiClient.structured(anyString(), anyString(), eq(8192), eq(BlueprintDto.class)))
                .thenThrow(new AiException("截断", null));
        when(aiClient.structured(anyString(), anyString(), eq(16384), eq(BlueprintDto.class)))
                .thenThrow(new AiException("仍截断", null));

        assertThrows(AiException.class, () -> service.generate(PROJECT_ID, BRIEF_ID));

        verify(aiClient).structured(anyString(), anyString(), eq(8192), eq(BlueprintDto.class));
        verify(aiClient).structured(anyString(), anyString(), eq(16384), eq(BlueprintDto.class));
        verify(briefMapper, never()).update(isNull(), any());
    }

    // ==================== 前置校验 ====================

    @Test
    void 事实手册为空_抛IllegalState() {
        ArticleBriefEntity b = brief();
        b.setFactSheet(null);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(b);

        assertThrows(IllegalStateException.class, () -> service.generate(PROJECT_ID, BRIEF_ID));
        verify(aiClient, never()).structured(anyString(), anyString(), anyInt(), eq(BlueprintDto.class));
    }

    @Test
    void 非DEEP_brief_抛IllegalArgument() {
        ArticleBriefEntity b = brief();
        b.setGenMode("FAST");
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(b);

        assertThrows(IllegalArgumentException.class, () -> service.generate(PROJECT_ID, BRIEF_ID));
        verify(aiClient, never()).structured(anyString(), anyString(), anyInt(), eq(BlueprintDto.class));
    }

    @Test
    void TaskBrief为空_抛IllegalState() {
        ArticleBriefEntity b = brief();
        b.setTaskBrief(null);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(b);

        assertThrows(IllegalStateException.class, () -> service.generate(PROJECT_ID, BRIEF_ID));
    }

    // ==================== 只写约定列 ====================

    @Test
    void 生成_只写约定列_不含项目status与planStatus() {
        stubHappy(BLUEPRINT_JSON);

        service.generate(PROJECT_ID, BRIEF_ID);

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<ArticleBriefEntity>> uw =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper.class);
        verify(briefMapper).update(isNull(), uw.capture());
        List<String> cols = setColumns(uw.getValue().getSqlSet());
        assertTrue(cols.contains("writing_blueprint"));
        assertTrue(cols.contains("blueprint_status"));
        assertTrue(cols.contains("blueprint_quality"));
        assertTrue(cols.contains("ai_model"));
        assertTrue(cols.contains("token_usage"));
        // 不得回写项目 status 或研究计划态（注意别被 blueprint_status 误伤）
        assertFalse(cols.contains("status"), "不得写项目 status");
        assertFalse(cols.contains("plan_status"), "不得写 plan_status");
    }

    @Test
    void 重生成_覆盖回REVIEWING() {
        ArticleBriefEntity b = brief();
        b.setBlueprintStatus("CONFIRMED");
        b.setWritingBlueprint("{\"thesis\":\"旧\"}");
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(b);
        when(aiClient.structured(anyString(), anyString(), eq(8192), eq(BlueprintDto.class)))
                .thenReturn(typed(BLUEPRINT_JSON));

        ArticleBriefEntity out = service.generate(PROJECT_ID, BRIEF_ID);

        assertEquals("REVIEWING", out.getBlueprintStatus());
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<ArticleBriefEntity>> uw =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper.class);
        verify(briefMapper).update(isNull(), uw.capture());
        var values = uw.getValue().getParamNameValuePairs().values();
        assertTrue(values.contains("REVIEWING"), "重生成覆盖回 REVIEWING");
    }

    // ==================== 人工评审门 confirm ====================

    @Test
    void confirm_置CONFIRMED_只写blueprint_status() {
        ArticleBriefEntity b = brief();
        b.setWritingBlueprint("{\"thesis\":\"x\"}");
        b.setBlueprintQuality("{\"gapCount\":0}");
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(b);

        ArticleBriefEntity out = service.confirm(PROJECT_ID, BRIEF_ID, null);

        assertEquals("CONFIRMED", out.getBlueprintStatus());
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<ArticleBriefEntity>> uw =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper.class);
        verify(briefMapper).update(isNull(), uw.capture());
        List<String> cols = setColumns(uw.getValue().getSqlSet());
        assertEquals(List.of("blueprint_status"), cols, "普通确认只 set blueprint_status");
    }

    @Test
    void confirm_幂等_已CONFIRMED再确认不报错() {
        ArticleBriefEntity b = brief();
        b.setWritingBlueprint("{\"thesis\":\"x\"}");
        b.setBlueprintStatus("CONFIRMED");
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(b);

        ArticleBriefEntity out = service.confirm(PROJECT_ID, BRIEF_ID, null);
        assertEquals("CONFIRMED", out.getBlueprintStatus());
    }

    @Test
    void confirm_无蓝图且无编辑_抛IllegalState409() {
        ArticleBriefEntity b = brief();   // 无 writing_blueprint
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(b);

        assertThrows(IllegalStateException.class, () -> service.confirm(PROJECT_ID, BRIEF_ID, null));
        verify(briefMapper, never()).update(isNull(), any());
    }

    @Test
    void confirm_无蓝图仅带编辑_仍抛IllegalState_不得凭空造蓝图() {
        ArticleBriefEntity b = brief();   // 无 writing_blueprint
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(b);

        String edited = "{\"thesis\":\"凭空蓝图\",\"evidenceMap\":[]}";
        assertThrows(IllegalStateException.class, () -> service.confirm(PROJECT_ID, BRIEF_ID, edited));
        verify(briefMapper, never()).update(isNull(), any());
    }

    @Test
    void confirm_带编辑蓝图_重算coverage与质量后落库() throws Exception {
        ArticleBriefEntity b = brief();
        b.setWritingBlueprint("{\"thesis\":\"旧\"}");
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(b);

        // 人工编辑：把 S3 的 entryKeys 改为合法 key → 应变 COVERED
        String edited = """
                {"thesis":"人工编辑后",
                 "evidenceMap":[{"sectionId":"S3","argument":"成本","evidenceNeeded":"规模","entryKeys":["年内建成2万座"],"coverage":"MISSING"}],
                 "gaps":[]}
                """;
        ArticleBriefEntity out = service.confirm(PROJECT_ID, BRIEF_ID, edited);

        assertEquals("CONFIRMED", out.getBlueprintStatus());
        JsonNode blueprint = json.readTree(out.getWritingBlueprint());
        assertEquals("人工编辑后", blueprint.path("thesis").asText());
        assertEquals("COVERED", blueprint.path("evidenceMap").get(0).path("coverage").asText(),
                "编辑后按白名单重算 coverage");
        JsonNode q = json.readTree(out.getBlueprintQuality());
        assertEquals(0, q.path("argumentDensity").asInt(), "编辑蓝图无 argumentStructure → 0");
        assertEquals(1.0, q.path("evidenceCoverage").asDouble(), 1e-9, "唯一证据项 COVERED");
        assertEquals(0, q.path("gapCount").asInt());

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<ArticleBriefEntity>> uw =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper.class);
        verify(briefMapper).update(isNull(), uw.capture());
        List<String> cols = setColumns(uw.getValue().getSqlSet());
        assertTrue(cols.contains("writing_blueprint"));
        assertTrue(cols.contains("blueprint_quality"));
        assertTrue(cols.contains("blueprint_status"));
    }

    @Test
    void confirm_编辑蓝图非法JSON_抛IllegalArgument() {
        ArticleBriefEntity b = brief();
        b.setWritingBlueprint("{\"thesis\":\"x\"}");
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(b);

        assertThrows(IllegalArgumentException.class, () -> service.confirm(PROJECT_ID, BRIEF_ID, "{not-json"));
        verify(briefMapper, never()).update(isNull(), any());
    }

    // ==================== 确定性纯函数 ====================

    @Test
    void coverageOf_三态() {
        assertEquals("MISSING", BlueprintService.coverageOf(0, 0));
        assertEquals("MISSING", BlueprintService.coverageOf(2, 0));
        assertEquals("PARTIAL", BlueprintService.coverageOf(3, 2));
        assertEquals("COVERED", BlueprintService.coverageOf(2, 2));
    }

    @Test
    void taskBriefConsistency_必要槽位比例() {
        assertEquals(1.0, BlueprintService.taskBriefConsistency(
                "{\"purpose\":{\"value\":\"p\"},\"audience\":{\"value\":\"a\"},\"mustCover\":[{\"value\":\"m\"}]}"), 1e-9);
        assertEquals(2.0 / 3, BlueprintService.taskBriefConsistency(
                "{\"purpose\":{\"value\":\"p\"},\"audience\":{\"value\":\"a\"},\"mustCover\":[]}"), 1e-9);
        assertEquals(0.0, BlueprintService.taskBriefConsistency(null), 1e-9);
        assertEquals(0.0, BlueprintService.taskBriefConsistency("{bad"), 1e-9);
    }

    @Test
    void bindEvidenceAndGaps_白名单空_全部剔除并MISSING() throws Exception {
        ObjectNode blueprint = (ObjectNode) json.readTree(BLUEPRINT_JSON);
        BlueprintService.bindEvidenceAndGaps(blueprint, Set.of());

        JsonNode em = blueprint.get("evidenceMap");
        for (JsonNode e : em) {
            assertEquals("MISSING", e.path("coverage").asText());
            assertEquals(0, e.path("entryKeys").size());
        }
        assertTrue(blueprint.path("gaps").size() >= 4);
    }

    /** 从 MyBatis-Plus UpdateWrapper.getSqlSet() 解析列名集合。 */
    private static List<String> setColumns(String sqlSet) {
        List<String> cols = new java.util.ArrayList<>();
        if (sqlSet == null || sqlSet.isBlank()) return cols;
        for (String seg : sqlSet.split(",")) {
            String s = seg.trim();
            int eq = s.indexOf('=');
            if (eq > 0) cols.add(s.substring(0, eq).trim());
        }
        return cols;
    }
}
