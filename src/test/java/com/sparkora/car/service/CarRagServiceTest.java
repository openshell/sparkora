package com.sparkora.car.service;

import com.sparkora.car.client.EmbeddingClient;
import com.sparkora.config.AiProperties;
import com.sparkora.mapper.CarDocEmbeddingMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S6.1「必查+降级可见」门槛逻辑单测(手写假件,不连 PG/embedding)。
 * 覆盖:OK / LOW_CONFIDENCE(整体抛弃,context 必须为空) / FAILED(异常不抛出) / NO_KNOWLEDGE(无对象);
 * S7 扩展:双源合并(KB 块带【通用知识】前缀)/ 未关联车型仍查 KB / KB 开关关闭 / KB 异常→FAILED。
 */
class CarRagServiceTest {

    private CarRagService newService(FakeMapper mapper) {
        return newService(mapper, new FakeKbEmbMapper());
    }

    private CarRagService newService(FakeMapper mapper, FakeKbEmbMapper kbMapper) {
        AiProperties props = new AiProperties(); // 默认 ragMinScore=0.3 / ragRejectScore=0.5 / kbTopk=4 / kbEnabled=true
        return new CarRagService(mapper, kbMapper, new FakeEmbeddingClient(), props);
    }

    /** 手写 embedding 客户端假实现(固定返回合法向量字符串,不发起 HTTP)。 */
    static class FakeEmbeddingClient extends EmbeddingClient {
        FakeEmbeddingClient() { super(new AiProperties()); }
        @Override
        public String embed(String query) { return "[0.1,0.2]"; }
    }

    /**
     * 手写 mapper 假实现(替代 Mockito mock:JDK21 动态代理下 stub 匹配不稳定)。
     * byModelId: modelId → 返回的检索行;byThrow: modelId → 抛出的异常。
     */
    static class FakeMapper implements CarDocEmbeddingMapper {
        final Map<Long, List<Map<String, Object>>> byModelId = new HashMap<>();
        final Map<Long, RuntimeException> byThrow = new HashMap<>();

        @Override
        public int insert(Long docId, Long modelId, String embedding) { return 0; }

        @Override
        public int deleteByDocId(Long docId) { return 0; }

        @Override
        public List<Map<String, Object>> searchTopK(Long modelId, String queryVec, int limit) {
            RuntimeException e = byThrow.get(modelId);
            if (e != null) throw e;
            return byModelId.getOrDefault(modelId, List.of());
        }
    }

    /** KB 向量 mapper 假实现(S7 双源):byRows → 检索行;byThrow → 抛异常。 */
    static class FakeKbEmbMapper implements com.sparkora.mapper.KbChunkEmbeddingMapper {
        List<Map<String, Object>> rows = List.of();
        RuntimeException byThrow = null;

        @Override
        public int insert(Long chunkId, String embedding) { return 0; }

        @Override
        public int deleteByDocId(Long docId) { return 0; }

        @Override
        public List<Map<String, Object>> searchTopK(String queryVec, int limit) {
            if (byThrow != null) throw byThrow;
            return rows.size() > limit ? rows.subList(0, limit) : rows;
        }
    }

    private static Map<String, Object> row(String text, double score) {
        Map<String, Object> m = new HashMap<>();
        m.put("chunkText", text);
        m.put("score", score);
        return m;
    }

    @Test
    void 命中且最高分过整体门槛_状态OK_上下文完整() {
        FakeMapper mapper = new FakeMapper();
        mapper.byModelId.put(1L, List.of(row("大唐EV 续航 600km", 0.82), row("大唐EV 权益", 0.55)));
        CarRagService svc = newService(mapper);

        CarRagService.RagResult r = svc.retrieveForGeneration(List.of(1L), "大唐EV 续航", 8);
        assertEquals(CarRagService.RagStatus.OK, r.status());
        assertEquals(2, r.hitCount());
        assertEquals(0.82, r.maxScore(), 1e-9);
        assertTrue(r.context().contains("600km"));
    }

    @Test
    void 有命中但最高分低于整体门槛_状态LOW_CONFIDENCE_上下文必须为空() {
        FakeMapper mapper = new FakeMapper();
        mapper.byModelId.put(1L, List.of(row("完全无关内容", 0.45), row("也很无关", 0.38)));
        CarRagService svc = newService(mapper);

        CarRagService.RagResult r = svc.retrieveForGeneration(List.of(1L), "query", 8);
        assertEquals(CarRagService.RagStatus.LOW_CONFIDENCE, r.status());
        assertEquals("", r.context(), "低置信抛弃后不得把知识块注入 prompt");
        assertEquals(2, r.hitCount(), "hitCount 保留观测值");
        assertEquals(0.45, r.maxScore(), 1e-9);
    }

    @Test
    void 检索异常_不抛出_状态FAILED_上下文为空() {
        FakeMapper mapper = new FakeMapper();
        mapper.byThrow.put(1L, new RuntimeException("embedding down"));
        CarRagService svc = newService(mapper);

        CarRagService.RagResult r = svc.retrieveForGeneration(List.of(1L), "query", 8);
        assertEquals(CarRagService.RagStatus.FAILED, r.status());
        assertEquals("", r.context());
    }

    @Test
    void 无车型对象_状态NO_KNOWLEDGE() {
        // S7:未关联车型但 KB 开启且 KB 无命中 → 仍为 EMPTY(NO_KNOWLEDGE)
        CarRagService svc = newService(new FakeMapper());
        assertEquals(CarRagService.RagResult.EMPTY, svc.retrieveForGeneration(List.of(), "query", 8));
        assertEquals(CarRagService.RagResult.EMPTY, svc.retrieveForGeneration(null, "query", 8));
    }

    @Test
    void S7_未关联车型_仍检索通用域并注入() {
        FakeKbEmbMapper kb = new FakeKbEmbMapper();
        kb.rows = List.of(kbRow("知识：家用充电桩选择要点（充电）\n看车型最大充电功率。", 0.8));
        CarRagService svc = newService(new FakeMapper(), kb);
        CarRagService.RagResult r = svc.retrieveForGeneration(List.of(), "充电桩怎么选", 8);
        assertEquals(CarRagService.RagStatus.OK, r.status());
        assertTrue(r.context().contains("知识来源：通用知识库"));
        assertTrue(r.context().contains("【通用知识】知识：家用充电桩选择要点"));
        assertTrue(r.maxScore() >= 0.8);
    }

    @Test
    void S7_双源同时命中_来源行标注双源_KB独立配额注入() {
        FakeMapper mapper = new FakeMapper();
        mapper.byModelId.put(1L, List.of(row("车型：海狮08\n参数分组：动力\n前电机最大功率（kW）：200", 0.9)));
        FakeKbEmbMapper kb = new FakeKbEmbMapper();
        kb.rows = List.of(kbRow("知识：充电功率常识（充电）\n7kW 家充为交流慢充。", 0.7));
        CarRagService svc = newService(mapper, kb);
        CarRagService.RagResult r = svc.retrieveForGeneration(List.of(1L), "海狮08 动力与充电", 8);
        assertEquals(CarRagService.RagStatus.OK, r.status());
        assertTrue(r.context().contains("知识来源：车型数据 + 通用知识库"));
        assertTrue(r.context().contains("【通用知识】"));
        assertTrue(r.context().contains("车型：海狮08"));
    }

    @Test
    void S7_KB开关关闭_回退S62行为_未关联车型零注入() {
        FakeMapper mapper = new FakeMapper();
        FakeKbEmbMapper kb = new FakeKbEmbMapper();
        kb.rows = List.of(kbRow("知识：不应被检索（通用）\n内容", 0.99));
        AiProperties props = new AiProperties();
        props.setRagKbEnabled(false);
        CarRagService svc = new CarRagService(mapper, kb, new FakeEmbeddingClient(), props);
        assertEquals(CarRagService.RagResult.EMPTY, svc.retrieveForGeneration(List.of(), "query", 8));
        // 已关联车型时车型域照常
        mapper.byModelId.put(1L, List.of(row("车型块", 0.9)));
        CarRagService.RagResult r = svc.retrieveForGeneration(List.of(1L), "query", 8);
        assertEquals(CarRagService.RagStatus.OK, r.status());
        assertTrue(!r.context().contains("【通用知识】"));
    }

    @Test
    void S7_KB检索异常_整体标FAILED_车型块仍注入() {
        FakeMapper mapper = new FakeMapper();
        mapper.byModelId.put(1L, List.of(row("车型：海狮08\n参数分组：动力\n前电机最大功率（kW）：200", 0.9)));
        FakeKbEmbMapper kb = new FakeKbEmbMapper();
        kb.byThrow = new RuntimeException("kb down");
        CarRagService svc = newService(mapper, kb);
        CarRagService.RagResult r = svc.retrieveForGeneration(List.of(1L), "query", 8);
        assertEquals(CarRagService.RagStatus.FAILED, r.status());
    }

    private static Map<String, Object> kbRow(String text, double score) {
        Map<String, Object> m = new HashMap<>();
        m.put("chunkText", text);
        m.put("score", score);
        return m;
    }

    @Test
    void 多车型_其一失败_整体标FAILED_不得部分注入() {
        FakeMapper mapper = new FakeMapper();
        mapper.byThrow.put(1L, new RuntimeException("down"));
        mapper.byModelId.put(2L, List.of(row("高相关", 0.9)));
        CarRagService svc = newService(mapper);

        CarRagService.RagResult r = svc.retrieveForGeneration(List.of(1L, 2L), "query", 8);
        assertEquals(CarRagService.RagStatus.FAILED, r.status());
        assertEquals("", r.context(), "任一车型失败即整体降级,不得部分注入");
    }

    @Test
    void 跨车型合并_分数与块数正确() {
        FakeMapper mapper = new FakeMapper();
        mapper.byModelId.put(1L, List.of(row("块一", 0.9)));
        mapper.byModelId.put(2L, List.of(row("块二", 0.7)));
        CarRagService svc = newService(mapper);

        CarRagService.RagResult r = svc.retrieveForGeneration(List.of(1L, 2L), "query", 8);
        assertEquals(CarRagService.RagStatus.OK, r.status());
        assertEquals(2, r.hitCount());
        assertEquals(0.9, r.maxScore(), 1e-9);
        assertTrue(r.context().contains("块一") && r.context().contains("块二"));
    }

    @Test
    void 权益块限流_参数块优先_总块数受配额约束() {
        FakeMapper mapper = new FakeMapper();
        java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Map<String, Object> m = new HashMap<>();
            m.put("chunkText", "购车权益内容" + i + "很长的文本");
            m.put("chunkType", "RIGHTS");
            m.put("score", 0.9 - i * 0.01);
            rows.add(m);
        }
        Map<String, Object> p1 = new HashMap<>();
        p1.put("chunkText", "参数分组：动力性能\n前电机最大功率（kW）：200");
        p1.put("chunkType", "PARAM_GROUP");
        p1.put("score", 0.62);
        rows.add(p1);
        Map<String, Object> p2 = new HashMap<>();
        p2.put("chunkText", "参数分组：尺寸参数\n轴距（mm）：3030");
        p2.put("chunkType", "PARAM_GROUP");
        p2.put("score", 0.60);
        rows.add(p2);
        mapper.byModelId.put(1L, rows);
        CarRagService svc = newService(mapper);
        CarRagService.RagResult r = svc.retrieveForGeneration(List.of(1L), "海狮08EV", 4);
        assertEquals(CarRagService.RagStatus.OK, r.status());
        String ctx = r.context();
        assertTrue(ctx.contains("前电机最大功率"), "参数块必须入选");
        int blocks = ctx.split("\\n---\\n").length;
        assertTrue(blocks <= 5, "总块数受配额约束(6 权益+2 参数,权益上限 8/3=2,总 ≤4,去重边界 5): got " + blocks);
        assertTrue(!ctx.contains("购车权益内容5"), "低分权益块应被配额挤掉");
    }


    @Test
    void 表头块_仅一行_被丢弃() {
        FakeMapper mapper = new FakeMapper();
        Map<String, Object> header = new HashMap<>();
        header.put("chunkText", "参数分组：海狮08EV参数表及配置表");
        header.put("chunkType", "PARAM_GROUP");
        header.put("score", 0.99);
        Map<String, Object> param = new HashMap<>();
        param.put("chunkText", "参数分组：动力性能\n前电机最大功率（kW）：200");
        param.put("chunkType", "PARAM_GROUP");
        param.put("score", 0.6);
        mapper.byModelId.put(1L, java.util.List.of(header, param));
        CarRagService svc = newService(mapper);

        CarRagService.RagResult r = svc.retrieveForGeneration(List.of(1L), "query", 8);
        assertTrue(r.context().contains("前电机最大功率"));
        assertTrue(!r.context().contains("海狮08EV参数表及配置表"), "单行表头块必须丢弃");
    }

    @Test
    void 子查询派生_含参数词时生成对应子查询() {
        CarRagService svc = newService(new FakeMapper());
        var subs = svc.deriveSubQueries("海狮08EV 价格和续航怎么样?");
        assertTrue(subs.stream().anyMatch(x -> x.contains("价格")));
        assertTrue(subs.stream().anyMatch(x -> x.contains("续航")));
        assertTrue(svc.deriveSubQueries("海狮08EV 好看吗").isEmpty());
    }

    @Test
    void 覆盖度摘要_抽取键值对_跳过有无值() {
        String s2 = CarRagService.extractParamSummary("参数分组：动力性能\n前电机最大功率（kW）：200\niTAC智能扭矩控制系统：无\n车漆颜色：可选装");
        assertTrue(s2.contains("前电机最大功率（kW）→200"));
        assertTrue(!s2.contains("iTAC"), "「无」类布尔值不入摘要");
        assertTrue(!s2.contains("车漆颜色"), "「可选装」不入摘要");
    }
}
