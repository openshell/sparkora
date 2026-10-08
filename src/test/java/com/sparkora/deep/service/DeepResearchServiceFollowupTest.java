package com.sparkora.deep.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.config.DeepProperties;
import com.sparkora.deep.search.WebProviderOrder;
import com.sparkora.deep.search.WebSearchSnapshot;
import com.sparkora.domain.entity.ArticleBriefEntity;
import com.sparkora.mapper.ArticleBriefMapper;
import com.sparkora.service.BriefService;
import com.sparkora.service.SettingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DeepResearchService Round 2 覆盖驱动补检索编排单测(10-04-web-followup-budget C-R2/AC-C2/C6/C8)。
 *
 * <p>全部用 SubAgentRunner 桩 + 内存版 ArticleBriefMapper 桩,不连网、不触碰生产数据。
 * 断言:不新建子代理、不重跑 plan、Round 1 notes/search 不被覆盖、generateFromFactSheet 仅一次、
 * 超时降级不阻断、webAllowed=false 不发起 Round 2。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeepResearchServiceFollowupTest {

    @Mock ArticleBriefMapper briefMapper;
    @Mock SubAgentRunner subAgent;
    @Mock FactSheetService factSheet;
    @Mock BriefService briefService;
    @Mock SettingService settingService;

    private static final long BRIEF_ID = 77L;
    private static final Long PROJECT_ID = 55L;
    private final ObjectMapper json = new ObjectMapper();

    private static final class Store {
        final AtomicReference<ArticleBriefEntity> entity = new AtomicReference<>();
    }

    private DeepResearchService service(DeepProperties props) {
        return new DeepResearchService(briefMapper, subAgent, factSheet, json, props,
                briefService, null, null, null, settingService);
    }

    private DeepProperties props(int followupMax, long followupTimeoutMs, int maxAgents) {
        DeepProperties p = new DeepProperties();
        p.setSearchWebEnabled(true);
        p.setMaxAgents(maxAgents);
        p.setResearchTimeoutMs(3000);
        p.setWebFollowupMax(followupMax);
        p.setFollowupTimeoutMs(followupTimeoutMs);
        p.setWebCallBudget(20);
        p.setWebCallBudgetPerRound(12);
        return p;
    }

    private Store wire(String planJson, String factSheetJson) throws Exception {
        Store store = new Store();
        ArticleBriefEntity b = new ArticleBriefEntity();
        b.setId(BRIEF_ID);
        b.setProjectId(PROJECT_ID);
        b.setGenMode("DEEP");
        b.setPlanStatus("READY");
        b.setClarifyAnswers("[{\"q\":\"竞品\",\"a\":\"Model Y\"}]");
        b.setResearchPlan(planJson);
        b.setResearchNotes("[{\"agentId\":1,\"question\":\"q1\",\"status\":\"PENDING\",\"factsJson\":\"{\\\"facts\\\":[],\\\"gaps\\\":[]}\",\"webCount\":0}]");
        store.entity.set(b);
        when(briefMapper.selectById(anyLong())).thenAnswer(inv -> store.entity.get());
        when(briefMapper.updateById(any(ArticleBriefEntity.class))).thenAnswer(inv -> {
            store.entity.set(inv.getArgument(0));
            return 1;
        });
        when(settingService.isKbEnabled()).thenReturn(false);
        when(settingService.isWebSearchEnabled()).thenReturn(true);
        when(settingService.getWebProviderOrder()).thenReturn("TAVILY,SEARXNG");
        when(factSheet.merge(anyString())).thenReturn(factSheetJson);
        when(briefService.generateFromFactSheet(anyLong(), anyLong())).thenReturn(b);
        return store;
    }

    private static String plan(String... questions) {
        StringBuilder sb = new StringBuilder("{\"keyQuestions\":[");
        for (int i = 0; i < questions.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(questions[i]).append('"');
        }
        return sb.append("]}").toString();
    }

    private static WebSearchSnapshot snapshot(boolean allowed) {
        return WebSearchSnapshot.of(WebProviderOrder.defaults(), allowed, BRIEF_ID, 5);
    }

    private static String factSheetWithLowConfParam(String claim) {
        return "{\"entries\":[{\"key\":\"" + claim + "\",\"claim\":\"" + claim + "\",\"kind\":\"param\","
                + "\"confidence\":0.4}],\"gaps\":[],\"warnings\":[]}";
    }

    private SubAgentRunner.Note doneNote(String question, int webCount, String factsJson) {
        SubAgentRunner.SearchMeta meta = new SubAgentRunner.SearchMeta(
                "TAVILY_FIRST", "TAVILY", "query", webCount, 10L, null, List.of());
        return new SubAgentRunner.Note(question, "DONE", factsJson, webCount, meta);
    }

    /** AC-C2/C8:Round 2 不新建子代理、Round 1 notes/search 不覆盖、generate 仅一次。 */
    @Test
    void Round2_不新建子代理_不覆盖Rround1_且generate仅一次() throws Exception {
        Store store = wire(plan("价格是多少", "续航多长"), factSheetWithLowConfParam("价格 23 万"));
        DeepProperties p = props(2, 3000, 2);
        when(subAgent.research(anyString(), any(), anyInt(), any(), anyString(), any(), any(), any()))
                .thenAnswer(inv -> {
                    String q = inv.getArgument(0);
                    // Round 1 facts 含目标 claim(供 Round 2 定位 Note)
                    return doneNote(q, 1, "{\"facts\":[{\"claim\":\"价格 23 万\"}],\"gaps\":[]}");
                });
        when(subAgent.researchFollowup(anyString(), anyString(), any(), anyString(), any(), any(), any()))
                .thenAnswer(inv -> doneNote((String) inv.getArgument(0), 1,
                        "{\"facts\":[{\"claim\":\"价格 23 万 核实\"}],\"gaps\":[]}"));

        service(p).runAsync(BRIEF_ID, snapshot(true));

        // 不新建子代理:Round 1 research 恰好 2 次,Round 2 走 researchFollowup(不增加 agent 数)
        verify(subAgent, times(2)).research(anyString(), any(), anyInt(), any(), anyString(), any(), any(), any());
        // generateFromFactSheet 仅一次(两轮合并之后)
        verify(briefService, times(1)).generateFromFactSheet(PROJECT_ID, BRIEF_ID);
        // fact_sheet 汇总两次(Round 1 + Round 2)
        verify(factSheet, times(2)).merge(anyString());
        // notes 仍为 2 个 agent(不新增),且 Round 1 的 search 未被抹掉
        JsonNode notes = json.readTree(store.entity.get().getResearchNotes());
        assertEquals(2, notes.size(), "Round 2 不得新增 agent 条目");
        for (JsonNode n : notes) {
            assertTrue(n.has("search"), "Round 1 search 元数据不得丢失");
        }
    }

    /** AC-C6:webAllowed=false → Round 2 完全不发起(researchFollowup 零调用)。 */
    @Test
    void webAllowed关闭_Round2不发起() throws Exception {
        wire(plan("价格是多少"), factSheetWithLowConfParam("价格 23 万"));
        DeepProperties p = props(2, 3000, 1);
        when(subAgent.research(anyString(), any(), anyInt(), any(), anyString(), any(), any(), any()))
                .thenAnswer(inv -> doneNote(inv.getArgument(0), 0, "{\"facts\":[],\"gaps\":[]}"));

        service(p).runAsync(BRIEF_ID, snapshot(false));

        verify(subAgent, org.mockito.Mockito.never())
                .researchFollowup(anyString(), anyString(), any(), anyString(), any(), any(), any());
        verify(briefService, times(1)).generateFromFactSheet(PROJECT_ID, BRIEF_ID);
    }

    /** AC-C6:followupMax=0(关闭多轮)→ Round 2 不发起,回单轮研究。 */
    @Test
    void followupMax为0_不发起Round2() throws Exception {
        wire(plan("价格是多少"), factSheetWithLowConfParam("价格 23 万"));
        DeepProperties p = props(0, 3000, 1);
        when(subAgent.research(anyString(), any(), anyInt(), any(), anyString(), any(), any(), any()))
                .thenAnswer(inv -> doneNote(inv.getArgument(0), 0, "{\"facts\":[],\"gaps\":[]}"));

        service(p).runAsync(BRIEF_ID, snapshot(true));

        verify(subAgent, org.mockito.Mockito.never())
                .researchFollowup(anyString(), anyString(), any(), anyString(), any(), any(), any());
        verify(factSheet, times(1)).merge(anyString());
    }

    /** AC-C6:Round 2 补检索超时 → 降级跳过,不阻断研究(仍汇总 + 生成一次)。 */
    @Test
    void Round2超时_降级不阻断() throws Exception {
        Store store = wire(plan("价格是多少"), factSheetWithLowConfParam("价格 23 万"));
        DeepProperties p = props(2, 150, 1);   // Round 2 超时 150ms
        when(subAgent.research(anyString(), any(), anyInt(), any(), anyString(), any(), any(), any()))
                .thenAnswer(inv -> doneNote(inv.getArgument(0), 0, "{\"facts\":[{\"claim\":\"价格 23 万\"}],\"gaps\":[]}"));
        when(subAgent.researchFollowup(anyString(), anyString(), any(), anyString(), any(), any(), any()))
                .thenAnswer(inv -> {
                    Thread.sleep(3000L);   // 故意超时
                    return null;
                });

        service(p).runAsync(BRIEF_ID, snapshot(true));

        verify(briefService, times(1)).generateFromFactSheet(PROJECT_ID, BRIEF_ID);
        assertTrue(store.entity.get().getResearchNotes() != null);
    }
}
