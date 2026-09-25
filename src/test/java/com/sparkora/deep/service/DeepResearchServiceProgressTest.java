package com.sparkora.deep.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.config.DeepProperties;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DeepResearchService 逐 agent 实时回写单测(09-26-deep-progress-realtime)。
 *
 * <p>覆盖 AC-01..AC-06:启动批量置 RUNNING、完成即乱序回写、并发写不丢字段、超时/异常失败隔离、
 * 产物结构与汇总顺序不回归。全部用可控 SubAgentRunner 桩 + 内存版 ArticleBriefMapper 桩,
 * 不触碰任何生产数据(quality-guidelines 红线)。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeepResearchServiceProgressTest {

    @Mock ArticleBriefMapper briefMapper;
    @Mock SubAgentRunner subAgent;
    @Mock FactSheetService factSheet;
    @Mock BriefService briefService;
    @Mock SettingService settingService;

    private static final long BRIEF_ID = 64L;
    private static final Long PROJECT_ID = 50L;

    private final ObjectMapper json = new ObjectMapper();

    /** 内存版 research_notes 存储:selectById 恒返回同一实体,updateById 记录快照与终止顺序。 */
    private static final class Store {
        final AtomicReference<ArticleBriefEntity> entity = new AtomicReference<>();
        final List<String> snapshots = new CopyOnWriteArrayList<>();
        final List<String> terminalOrder = new CopyOnWriteArrayList<>();
        final Set<String> seenTerminal = ConcurrentHashMap.newKeySet();
        final AtomicBoolean mergeSawAllTerminal = new AtomicBoolean(false);
    }

    private DeepResearchService service(DeepProperties props) {
        return new DeepResearchService(briefMapper, subAgent, factSheet, json, props,
                briefService, null, null, null, settingService);
    }

    private DeepProperties props(long timeoutMs, int maxAgents) {
        DeepProperties p = new DeepProperties();
        p.setSearchWebEnabled(true);
        p.setMaxAgents(maxAgents);
        p.setResearchTimeoutMs(timeoutMs);
        return p;
    }

    /** 装配内存版 mapper 桩:selectById 返回同一实体;updateById 落库并记录快照/终止顺序。 */
    private Store wireStore(String planJson) throws Exception {
        Store store = new Store();
        ArticleBriefEntity b = new ArticleBriefEntity();
        b.setId(BRIEF_ID);
        b.setProjectId(PROJECT_ID);
        b.setGenMode("DEEP");
        b.setPlanStatus("READY");
        b.setClarifyAnswers("[{\"q\":\"竞品\",\"a\":\"Model Y\"}]");
        b.setResearchPlan(planJson);
        // 模拟 run() 已落 PENDING 占位(runAsync 启动后会整体覆写为 RUNNING)
        b.setResearchNotes("[{\"agentId\":1,\"question\":\"q1\",\"status\":\"PENDING\",\"factsJson\":\"{\\\"facts\\\":[],\\\"gaps\\\":[]}\",\"webCount\":0}]");
        store.entity.set(b);

        when(briefMapper.selectById(anyLong())).thenAnswer(inv -> store.entity.get());
        when(briefMapper.updateById(any(ArticleBriefEntity.class))).thenAnswer(inv -> {
            ArticleBriefEntity updated = inv.getArgument(0);
            store.entity.set(updated);
            String notes = updated.getResearchNotes();
            if (notes != null) {
                store.snapshots.add(notes);
                JsonNode arr = json.readTree(notes);
                for (JsonNode n : arr) {
                    String status = n.path("status").asText("");
                    if (isTerminal(status)) {
                        String key = n.path("agentId").asInt() + ":" + status;
                        if (store.seenTerminal.add(key)) store.terminalOrder.add(key);
                    }
                }
            }
            return 1;
        });
        when(settingService.isKbEnabled()).thenReturn(true);
        when(settingService.isWebSearchEnabled()).thenReturn(true);
        when(settingService.getWebProviderOrder()).thenReturn("TAVILY,SEARXNG");
        // 事实手册汇总:记录 merge 时刻是否全部 agent 已落定(AC-06 顺序断言)
        when(factSheet.merge(anyString())).thenAnswer(inv -> {
            store.mergeSawAllTerminal.set(allTerminal(store));
            return "{\"entries\":[],\"gaps\":[],\"warnings\":[]}";
        });
        when(briefService.generateFromFactSheet(anyLong(), anyLong())).thenReturn(b);
        return store;
    }

    private static boolean isTerminal(String s) {
        return "DONE".equals(s) || "FALLBACK".equals(s) || "FAILED".equals(s);
    }

    private boolean allTerminal(Store store) throws Exception {
        JsonNode arr = json.readTree(store.entity.get().getResearchNotes());
        if (arr.size() == 0) return false;
        for (JsonNode n : arr) if (!isTerminal(n.path("status").asText(""))) return false;
        return true;
    }

    private static String plan(String... questions) {
        StringBuilder sb = new StringBuilder("{\"keyQuestions\":[");
        for (int i = 0; i < questions.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(questions[i]).append('"');
        }
        return sb.append("]}").toString();
    }

    private static WebSearchSnapshot snapshot() {
        return WebSearchSnapshot.of(com.sparkora.deep.search.WebProviderOrder.defaults(), false, BRIEF_ID, 0);
    }

    private static int idxOf(String question) {
        return Integer.parseInt(question.substring(1)) - 1;   // "q1" -> 0
    }

    private SubAgentRunner.Note doneNote(String question, int webCount, String factsJson) {
        return new SubAgentRunner.Note(question, "DONE", factsJson, webCount);
    }

    private String facts(String tag) {
        return "{\"facts\":[{\"claim\":\"" + tag + "\"}],\"gaps\":[]}";
    }

    // ---------- AC-01:启动批量置 RUNNING,首个子代理完成前全部 RUNNING ----------
    @Test
    void 启动阶段全部agent即置RUNNING_首个子代理完成前无PENDING() throws Exception {
        Store store = wireStore(plan("q1", "q2", "q3", "q4"));
        DeepProperties p = props(2000, 4);
        // 屏障:全部子代理进入 research 并阻塞;首个到达者捕获 research_notes 后才放行全部,
        // 确保捕获发生在任何子代理返回(继而有收集器回写)之前。
        CountDownLatch allEntered = new CountDownLatch(4);
        CountDownLatch captureDone = new CountDownLatch(1);
        AtomicReference<String> captured = new AtomicReference<>();
        AtomicBoolean captureOnce = new AtomicBoolean(false);
        when(subAgent.research(anyString(), any(), anyInt(), any(), anyString(), any(), any())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            allEntered.countDown();
            allEntered.await(2, TimeUnit.SECONDS);
            if (captureOnce.compareAndSet(false, true)) {
                captured.set(store.entity.get().getResearchNotes());
                captureDone.countDown();
            } else {
                captureDone.await(2, TimeUnit.SECONDS);
            }
            return doneNote(q, 0, facts(q));
        });

        service(p).runAsync(BRIEF_ID, snapshot());

        assertNotNull(captured.get(), "应捕获到启动阶段快照");
        JsonNode arr = json.readTree(captured.get());
        assertEquals(4, arr.size(), "4 个 agent 均应在列");
        for (JsonNode n : arr) {
            assertEquals("RUNNING", n.path("status").asText(),
                    "首个子代理完成前 agent" + n.path("agentId").asInt() + " 必须为 RUNNING(消除 PENDING 长挂)");
        }
    }

    // ---------- AC-02:完成先后决定回写顺序(agent2 先完成则先落库) ----------
    @Test
    void 完成即乱序回写_后启动的先完成则先落库() throws Exception {
        Store store = wireStore(plan("q1", "q2"));
        DeepProperties p = props(5000, 2);
        when(subAgent.research(anyString(), any(), anyInt(), any(), anyString(), any(), any())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            int idx = idxOf(q);
            // agent1(q1) 慢、agent2(q2) 快 → agent2 先 DONE
            Thread.sleep(idx == 0 ? 300L : 30L);
            return doneNote(q, 0, facts(q));
        });

        service(p).runAsync(BRIEF_ID, snapshot());

        List<String> order = store.terminalOrder;
        assertTrue(order.indexOf("2:DONE") >= 0 && order.indexOf("1:DONE") >= 0, "两个 agent 均应 DONE");
        assertTrue(order.indexOf("2:DONE") < order.indexOf("1:DONE"),
                "agent2 先完成必须先落库(实际顺序=" + order + ")");
    }

    // ---------- AC-03:并发回写不丢字段(status/factsJson/webCount/search 各自终值) ----------
    @Test
    void 并发回写不丢字段_各agent终值完整() throws Exception {
        Store store = wireStore(plan("q1", "q2", "q3", "q4"));
        DeepProperties p = props(5000, 4);
        when(subAgent.research(anyString(), any(), anyInt(), any(), anyString(), any(), any())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            int idx = idxOf(q);
            Thread.sleep(50L);   // 四者几乎同时完成,最大化写竞争
            SubAgentRunner.SearchMeta meta = new SubAgentRunner.SearchMeta(
                    "TAVILY_FIRST", "TAVILY", "query" + idx, idx, 10L + idx, null, List.of());
            return new SubAgentRunner.Note(q, "DONE", facts(q + "-facts"), idx, meta);
        });

        service(p).runAsync(BRIEF_ID, snapshot());

        JsonNode arr = json.readTree(store.entity.get().getResearchNotes());
        assertEquals(4, arr.size());
        for (JsonNode n : arr) {
            int agentId = n.path("agentId").asInt();
            int idx = agentId - 1;
            assertEquals("DONE", n.path("status").asText(), "agent" + agentId + " status 不得被覆盖");
            assertEquals(facts("q" + (idx + 1) + "-facts"), n.path("factsJson").asText(), "agent" + agentId + " factsJson 不得被覆盖");
            assertEquals(idx, n.path("webCount").asInt(), "agent" + agentId + " webCount 不得被覆盖");
            // search 元数据(读改写保留,不被其它 agent 覆盖/丢失)
            assertEquals("query" + idx, n.path("search").path("query").asText(), "agent" + agentId + " search 不得丢失");
            assertEquals("TAVILY", n.path("search").path("provider").asText(), "agent" + agentId + " search.provider 不得丢失");
        }
    }

    // ---------- AC-04:单 agent 超时 → FAILED + cancel;其余 DONE ----------
    @Test
    void 单agent超时写FAILED并取消_其余正常DONE() throws Exception {
        Store store = wireStore(plan("q1", "q2"));
        DeepProperties p = props(250, 2);   // 超时 250ms
        AtomicBoolean cancelled = new AtomicBoolean(false);
        when(subAgent.research(anyString(), any(), anyInt(), any(), anyString(), any(), any())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            try {
                Thread.sleep(idxOf(q) == 0 ? 3000L : 20L);
            } catch (InterruptedException ie) {
                cancelled.set(true);
                throw ie;
            }
            return doneNote(q, 0, facts(q));
        });

        service(p).runAsync(BRIEF_ID, snapshot());

        JsonNode arr = json.readTree(store.entity.get().getResearchNotes());
        Map<Integer, String> status = statuses(arr);
        assertEquals("FAILED", status.get(1), "超时 agent 必须 FAILED");
        assertEquals("DONE", status.get(2), "其余 agent 仍正常 DONE");
        // 超时后必须 cancel(true) 中断底层任务(不产生外部继续调用)
        long deadline = System.currentTimeMillis() + 2000;
        while (!cancelled.get() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertTrue(cancelled.get(), "超时 agent 的 future 必须被 cancel(true) 中断");
    }

    // ---------- AC-05:单 agent 抛异常 → FAILED,失败隔离,其余 DONE ----------
    @Test
    void 单agent异常隔离_其余正常完成并落库() throws Exception {
        Store store = wireStore(plan("q1", "q2", "q3"));
        DeepProperties p = props(3000, 3);
        when(subAgent.research(anyString(), any(), anyInt(), any(), anyString(), any(), any())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            if (idxOf(q) == 1) throw new IllegalStateException("模拟子代理异常");
            Thread.sleep(20L);
            return doneNote(q, 0, facts(q));
        });

        service(p).runAsync(BRIEF_ID, snapshot());

        Map<Integer, String> status = statuses(json.readTree(store.entity.get().getResearchNotes()));
        assertEquals("DONE", status.get(1));
        assertEquals("FAILED", status.get(2), "抛异常 agent 必须 FAILED");
        assertEquals("DONE", status.get(3), "失败隔离:其余 agent 不受影响");
    }

    // ---------- AC-06:产物结构不变;fact_sheet 汇总在全部落定后调用一次 ----------
    @Test
    void 产物结构不变且汇总在全部落定后执行一次() throws Exception {
        Store store = wireStore(plan("q1", "q2"));
        DeepProperties p = props(3000, 2);
        when(subAgent.research(anyString(), any(), anyInt(), any(), anyString(), any(), any())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            Thread.sleep(20L);
            return doneNote(q, 0, facts(q));
        });

        service(p).runAsync(BRIEF_ID, snapshot());

        // 字段集合不变:agentId/question/status/factsJson/webCount(+可选 search)
        JsonNode arr = json.readTree(store.entity.get().getResearchNotes());
        for (JsonNode n : arr) {
            assertTrue(n.has("agentId") && n.has("question") && n.has("status")
                    && n.has("factsJson") && n.has("webCount"), "research_notes 字段结构不得改变: " + n);
            assertTrue(isTerminal(n.path("status").asText("")), "最终必须为终止态");
        }
        // fact_sheet 汇总恰好一次,且发生在全部 agent 落定之后
        verify(factSheet, times(1)).merge(anyString());
        assertTrue(store.mergeSawAllTerminal.get(), "fact_sheet 必须在全部 agent 落定后汇总");
        assertNotNull(store.entity.get().getFactSheet(), "fact_sheet 必须落库");
        verify(briefService, times(1)).generateFromFactSheet(PROJECT_ID, BRIEF_ID);
    }

    private static Map<Integer, String> statuses(JsonNode arr) {
        Map<Integer, String> out = new LinkedHashMap<>();
        for (JsonNode n : arr) out.put(n.path("agentId").asInt(), n.path("status").asText());
        return out;
    }
}
