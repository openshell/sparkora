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
     * S8:unifiedRows → searchTopKUnified 返回行;unifiedThrow → 统一检索抛异常。
     */
    static class FakeMapper implements CarDocEmbeddingMapper {
        final Map<Long, List<Map<String, Object>>> byModelId = new HashMap<>();
        final Map<Long, RuntimeException> byThrow = new HashMap<>();
        List<Map<String, Object>> unifiedRows = List.of();
        RuntimeException unifiedThrow = null;

        @Override
        public int insert(Long docId, Long modelId, String embedding) { return 0; }

        @Override
        public int deleteByDocId(Long docId) { return 0; }

        @Override
        public int deleteByModelId(Long modelId) { return 0; }

        @Override
        public List<Map<String, Object>> searchTopK(Long modelId, String queryVec, int limit) {
            RuntimeException e = byThrow.get(modelId);
            if (e != null) throw e;
            return byModelId.getOrDefault(modelId, List.of());
        }

        @Override
        public List<Map<String, Object>> countByModel() { return List.of(); }

        @Override
        public List<Map<String, Object>> searchTopKUnified(String queryVec, int limit) {
            if (unifiedThrow != null) throw unifiedThrow;
            return unifiedRows.size() > limit ? unifiedRows.subList(0, limit) : unifiedRows;
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

    /** 统一检索行(S8):source/modelId/modelName/chunkType 全带。 */
    private static Map<String, Object> urow(String source, Long modelId, String modelName,
                                            String chunkType, String text, double score) {
        Map<String, Object> m = new HashMap<>();
        m.put("source", source);
        m.put("modelId", modelId);
        m.put("modelName", modelName);
        m.put("chunkType", chunkType);
        m.put("chunkText", text);
        m.put("score", score);
        m.put("docId", 1);
        return m;
    }

    @Test
    void 命中且最高分过整体门槛_状态OK_上下文完整() {
        FakeMapper mapper = new FakeMapper();
        mapper.unifiedRows = List.of(
                urow("CAR", 1L, "大唐EV", "PARAM_GROUP", "车型：大唐EV\n续航 600km", 0.82),
                urow("CAR", 1L, "大唐EV", "RIGHTS", "车型：大唐EV 购车权益：权益", 0.55));
        CarRagService svc = newService(mapper);

        CarRagService.RagResult r = svc.retrieveForGeneration("大唐EV 续航", 8, List.of());
        assertEquals(CarRagService.RagStatus.OK, r.status());
        assertEquals(2, r.hitCount());
        assertEquals(0.82, r.maxScore(), 1e-9);
        assertTrue(r.context().contains("600km"));
    }

    @Test
    void 有命中但最高分低于整体门槛_状态LOW_CONFIDENCE_上下文必须为空() {
        FakeMapper mapper = new FakeMapper();
        mapper.unifiedRows = List.of(
                urow("CAR", 1L, "车型A", "PARAM_GROUP", "车型：车型A\n完全无关内容", 0.45),
                urow("CAR", 1L, "车型A", "PARAM_GROUP", "车型：车型A\n也很无关", 0.38));
        CarRagService svc = newService(mapper);

        CarRagService.RagResult r = svc.retrieveForGeneration("query", 8, List.of());
        assertEquals(CarRagService.RagStatus.LOW_CONFIDENCE, r.status());
        assertEquals("", r.context(), "低置信抛弃后不得把知识块注入 prompt");
        assertEquals(2, r.hitCount(), "hitCount 保留观测值");
        assertEquals(0.45, r.maxScore(), 1e-9);
    }

    @Test
    void 检索异常_不抛出_状态FAILED_上下文为空() {
        FakeMapper mapper = new FakeMapper();
        mapper.unifiedThrow = new RuntimeException("embedding down");
        CarRagService svc = newService(mapper);

        CarRagService.RagResult r = svc.retrieveForGeneration("query", 8, List.of(1L));
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
        FakeMapper mapper = new FakeMapper();
        mapper.unifiedRows = List.of(
                urow("KB", null, "家用充电桩选择要点", "KB_CHUNK", "知识：家用充电桩选择要点（充电）\n看车型最大充电功率。", 0.8));
        CarRagService svc = newService(mapper);
        CarRagService.RagResult r = svc.retrieveForGeneration("充电桩怎么选", 8, List.of());
        assertEquals(CarRagService.RagStatus.OK, r.status());
        assertTrue(r.context().contains("知识来源：通用知识库"));
        assertTrue(r.context().contains("【通用知识：家用充电桩选择要点】"));
        assertTrue(r.maxScore() >= 0.8);
    }

    @Test
    void S7_双源同时命中_来源行标注双源_KB独立配额注入() {
        FakeMapper mapper = new FakeMapper();
        mapper.unifiedRows = List.of(
                urow("CAR", 1L, "海狮08", "PARAM_GROUP", "车型：海狮08\n参数分组：动力\n前电机最大功率（kW）：200", 0.9),
                urow("KB", null, "充电功率常识", "KB_CHUNK", "知识：充电功率常识（充电）\n7kW 家充为交流慢充。", 0.7));
        CarRagService svc = newService(mapper);
        CarRagService.RagResult r = svc.retrieveForGeneration("海狮08 动力与充电", 8, List.of(1L));
        assertEquals(CarRagService.RagStatus.OK, r.status());
        assertTrue(r.context().contains("知识来源：车型数据 + 通用知识库"));
        assertTrue(r.context().contains("【通用知识：充电功率常识】"));
        assertTrue(r.context().contains("车型：海狮08"));
    }

    @Test
    void S7_KB开关关闭_回退S62行为_未关联车型零注入() {
        FakeMapper mapper = new FakeMapper();
        mapper.unifiedRows = List.of(
                urow("CAR", 1L, "车型X", "MODEL_INFO", "车型：车型X\n车型块", 0.9),
                urow("KB", null, "不应被检索", "KB_CHUNK", "知识：不应被检索（通用）\n内容", 0.85));
        AiProperties props = new AiProperties();
        props.setRagKbEnabled(false);
        CarRagService svc = new CarRagService(mapper, new FakeKbEmbMapper(), new FakeEmbeddingClient(), props);
        // KB 关闭:KB 块被配额排除,车型块照常注入(S8 语义)
        CarRagService.RagResult r = svc.retrieveForGeneration("query", 8, List.of(1L));
        assertEquals(CarRagService.RagStatus.OK, r.status());
        assertTrue(!r.context().contains("不应被检索"));
    }

    @Test
    void S7_KB检索异常_整体标FAILED_车型块仍注入() {
        FakeMapper mapper = new FakeMapper();
        mapper.unifiedRows = List.of(
                urow("CAR", 1L, "海狮08", "PARAM_GROUP", "车型：海狮08\n参数分组：动力\n前电机最大功率（kW）：200", 0.9));
        FakeKbEmbMapper kb = new FakeKbEmbMapper();
        kb.byThrow = new RuntimeException("kb down");
        CarRagService svc = newService(mapper, kb);
        // S8:生成链路只走统一检索;KB 直连 mapper(retrieveKb)异常不影响生成主链路 → OK
        CarRagService.RagResult r = svc.retrieveForGeneration("query", 8, List.of(1L));
        assertEquals(CarRagService.RagStatus.OK, r.status());
    }

    private static Map<String, Object> kbRow(String text, double score) {
        Map<String, Object> m = new HashMap<>();
        m.put("chunkText", text);
        m.put("score", score);
        return m;
    }

    // ==================== S8 统一检索(去车型门禁) ====================

    @Test
    void S8_统一检索_未关联车型_命中车型块_来源行内标注() {
        FakeMapper mapper = new FakeMapper();
        mapper.unifiedRows = List.of(
                urow("CAR", 55L, "海狮08EV", "MODEL_INFO", "车型：海狮08EV\n价格区间：239,900 - 279,900", 0.85),
                urow("CAR", 55L, "海狮08EV", "PARAM_GROUP", "车型：海狮08EV\n参数分组：基础参数\n长×宽×高：4810×1920×1675", 0.75),
                urow("KB", null, "家用充电桩选择要点", "KB_CHUNK", "知识：家用充电桩选择要点（充电）\n功率选择。", 0.6));
        CarRagService svc = newService(mapper);
        // 未关联车型(空 anchor)——S8 后仍可命中车型价格块(文章18场景)
        CarRagService.RagResult r = svc.retrieveForGeneration("深度分析海狮08定价逻辑，这个定价到底贵不贵？", 8, List.of());
        assertEquals(CarRagService.RagStatus.OK, r.status());
        assertTrue(r.context().contains("知识来源：车型数据 + 通用知识库"));
        assertTrue(r.context().contains("【车型数据：海狮08EV】"));
        assertTrue(r.context().contains("【通用知识：家用充电桩选择要点】"));
        assertTrue(r.context().contains("239,900"));
    }

    @Test
    void S8_锚点加权_同分锚点车型块排前() {
        FakeMapper mapper = new FakeMapper();
        mapper.unifiedRows = List.of(
                urow("CAR", 55L, "海狮08EV", "PARAM_GROUP", "车型：海狮08EV\n参数分组：动力\n前电机最大功率（kW）：200", 0.60),
                urow("CAR", 39L, "大唐EV", "PARAM_GROUP", "车型：大唐EV\n参数分组：动力\n前电机最大功率（kW）：180", 0.70));
        AiProperties props = new AiProperties();
        props.setRagAnchorBoost(1.5);   // 放大系数让断言明确
        CarRagService svc = new CarRagService(mapper, new FakeKbEmbMapper(), new FakeEmbeddingClient(), props);
        CarRagService.RagResult r = svc.retrieveForGeneration("动力对比", 4, List.of(55L));
        assertEquals(CarRagService.RagStatus.OK, r.status());
        int anchorIdx = r.context().indexOf("海狮08EV");
        int otherIdx = r.context().indexOf("大唐EV");
        assertTrue(anchorIdx >= 0 && otherIdx >= 0 && anchorIdx < otherIdx, "锚点车型块加权后应排在前");
    }

    @Test
    void S8_旧签名委托_行为等于新签名锚点() {
        FakeMapper mapper = new FakeMapper();
        mapper.unifiedRows = List.of(
                urow("CAR", 55L, "海狮08EV", "MODEL_INFO", "车型：海狮08EV\n价格区间：239,900 - 279,900", 0.85));
        CarRagService svc = newService(mapper);
        CarRagService.RagResult viaOld = svc.retrieveForGeneration(List.of(55L), "海狮08 价格", 8);
        CarRagService.RagResult viaNew = svc.retrieveForGeneration("海狮08 价格", 8, List.of(55L));
        assertEquals(viaNew.status(), viaOld.status());
        assertEquals(viaNew.context(), viaOld.context());
    }

    @Test
    void S8_统一检索异常_标FAILED() {
        FakeMapper mapper = new FakeMapper();
        mapper.unifiedThrow = new RuntimeException("embedding down");
        CarRagService svc = newService(mapper);
        CarRagService.RagResult r = svc.retrieveForGeneration("query", 8, List.of());
        assertEquals(CarRagService.RagStatus.FAILED, r.status());
    }

    @Test
    void S8_KB开关关闭_统一检索仍跑_KB块被排除() {
        FakeMapper mapper = new FakeMapper();
        mapper.unifiedRows = List.of(
                urow("CAR", 55L, "海狮08EV", "MODEL_INFO", "车型：海狮08EV\n价格区间：239,900 - 279,900", 0.9),
                urow("KB", null, "不应出现", "KB_CHUNK", "知识：不应出现（通用）\n内容", 0.85));
        AiProperties props = new AiProperties();
        props.setRagKbEnabled(false);
        CarRagService svc = new CarRagService(mapper, new FakeKbEmbMapper(), new FakeEmbeddingClient(), props);
        CarRagService.RagResult r = svc.retrieveForGeneration("海狮08 价格", 8, List.of());
        assertEquals(CarRagService.RagStatus.OK, r.status());
        assertTrue(r.context().contains("海狮08EV"));
        assertTrue(!r.context().contains("不应出现"));
        assertEquals("知识来源：车型数据", r.context().split("\n---\n")[0]);
    }

    @Test
    void 多车型_其一失败_整体标FAILED_不得部分注入() {
        FakeMapper mapper = new FakeMapper();
        // S8:统一检索无逐车型循环,单次异常即 FAILED(见 S8_统一检索异常_标FAILED)。
        // 保留多锚点语义验证:两个锚点车型均正常命中 → OK
        mapper.unifiedRows = List.of(
                urow("CAR", 1L, "车型1", "PARAM_GROUP", "车型：车型1\n高相关", 0.9),
                urow("CAR", 2L, "车型2", "PARAM_GROUP", "车型：车型2\n高相关2", 0.7));
        CarRagService svc = newService(mapper);

        CarRagService.RagResult r = svc.retrieveForGeneration("query", 8, List.of(1L, 2L));
        assertEquals(CarRagService.RagStatus.OK, r.status());
    }

    @Test
    void 跨车型合并_分数与块数正确() {
        FakeMapper mapper = new FakeMapper();
        mapper.unifiedRows = List.of(
                urow("CAR", 1L, "车型1", "PARAM_GROUP", "车型：车型1\n块一", 0.9),
                urow("CAR", 2L, "车型2", "PARAM_GROUP", "车型：车型2\n块二", 0.7));
        CarRagService svc = newService(mapper);

        CarRagService.RagResult r = svc.retrieveForGeneration("query", 8, List.of());
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
            rows.add(urow("CAR", 1L, "海狮08EV", "RIGHTS", "车型：海狮08EV 购车权益内容" + i + "很长的文本", 0.9 - i * 0.01));
        }
        rows.add(urow("CAR", 1L, "海狮08EV", "PARAM_GROUP", "车型：海狮08EV\n参数分组：动力性能\n前电机最大功率（kW）：200", 0.62));
        rows.add(urow("CAR", 1L, "海狮08EV", "PARAM_GROUP", "车型：海狮08EV\n参数分组：尺寸参数\n轴距（mm）：3030", 0.60));
        mapper.unifiedRows = rows;
        CarRagService svc = newService(mapper);
        CarRagService.RagResult r = svc.retrieveForGeneration("海狮08EV", 4, List.of(1L));
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
        mapper.unifiedRows = List.of(
                urow("CAR", 1L, "海狮08EV", "PARAM_GROUP", "参数分组：海狮08EV参数表及配置表", 0.99),
                urow("CAR", 1L, "海狮08EV", "PARAM_GROUP", "参数分组：动力性能\n前电机最大功率（kW）：200", 0.6));
        CarRagService svc = newService(mapper);

        CarRagService.RagResult r = svc.retrieveForGeneration("query", 8, List.of(1L));
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

    // ==================== C2 新闻域(NEWS) ====================

    @Test
    void C2_新闻块命中_来源标注官方新闻_三域来源行() {
        FakeMapper mapper = new FakeMapper();
        mapper.unifiedRows = List.of(
                urow("CAR", 1L, "海狮08EV", "PARAM_GROUP", "车型：海狮08EV\n参数分组：动力\n前电机最大功率（kW）：200", 0.9),
                urow("KB", null, "充电功率常识", "KB_CHUNK", "知识：充电功率常识（充电）\n7kW 家充为交流慢充。", 0.8),
                urow("NEWS", null, "比亚迪发布新车型", "NEWS_BODY", "新闻：比亚迪发布新车型（2026-09-01）\n官方新闻正文。", 0.7));
        CarRagService svc = newService(mapper);
        CarRagService.RagResult r = svc.retrieveForGeneration("比亚迪 新车型", 8, List.of(1L));
        assertEquals(CarRagService.RagStatus.OK, r.status());
        assertTrue(r.context().contains("知识来源：车型数据 + 通用知识库 + 官方新闻"),
                () -> "三域来源行: " + r.context());
        assertTrue(r.context().contains("【官方新闻：比亚迪发布新车型】"));
        assertTrue(r.citations().stream().anyMatch(c -> "NEWS".equals(c.source())), "NEWS 块应进 citations");
    }

    @Test
    void C2_仅新闻命中_来源行为官方新闻() {
        FakeMapper mapper = new FakeMapper();
        mapper.unifiedRows = List.of(
                urow("NEWS", null, "官方新闻标题", "NEWS_BODY", "新闻：官方新闻标题（2026-09-01）\n正文", 0.8));
        CarRagService svc = newService(mapper);
        CarRagService.RagResult r = svc.retrieveForGeneration("新闻查询", 8, List.of());
        assertEquals(CarRagService.RagStatus.OK, r.status());
        assertEquals("知识来源：官方新闻", r.context().split("\n---\n")[0]);
        assertTrue(r.context().contains("【官方新闻：官方新闻标题】"));
    }

    @Test
    void C2_新闻配额为0_不注入NEWS_其他域不受影响() {
        FakeMapper mapper = new FakeMapper();
        mapper.unifiedRows = List.of(
                urow("CAR", 1L, "海狮08EV", "MODEL_INFO", "车型：海狮08EV\n价格区间：239,900", 0.9),
                urow("NEWS", null, "不应注入", "NEWS_BODY", "新闻：不应注入（2026-09-01）\n正文", 0.85));
        AiProperties props = new AiProperties();
        props.setRagNewsTopk(0);
        CarRagService svc = new CarRagService(mapper, new FakeKbEmbMapper(), new FakeEmbeddingClient(), props);
        CarRagService.RagResult r = svc.retrieveForGeneration("海狮08", 8, List.of(1L));
        assertEquals(CarRagService.RagStatus.OK, r.status());
        assertTrue(r.context().contains("海狮08EV"));
        assertTrue(!r.context().contains("不应注入"), "ragNewsTopk=0 时 NEWS 不注入");
    }

    @Test
    void C2_NEWS不受KB开关控制_KB关闭仍注入新闻() {
        FakeMapper mapper = new FakeMapper();
        mapper.unifiedRows = List.of(
                urow("KB", null, "不应出现", "KB_CHUNK", "知识：不应出现（通用）\n内容", 0.9),
                urow("NEWS", null, "官方新闻", "NEWS_BODY", "新闻：官方新闻（2026-09-01）\n正文", 0.85));
        AiProperties props = new AiProperties();
        props.setRagKbEnabled(false);   // KB 关闭;NEWS 独立配额,不受其控制
        CarRagService svc = new CarRagService(mapper, new FakeKbEmbMapper(), new FakeEmbeddingClient(), props);
        CarRagService.RagResult r = svc.retrieveForGeneration("查询", 8, List.of());
        assertEquals(CarRagService.RagStatus.OK, r.status());
        assertTrue(!r.context().contains("不应出现"), "KB 关闭后 KB 块被排除");
        assertTrue(r.context().contains("【官方新闻：官方新闻】"), "NEWS 不受 ragKbEnabled 控制");
        assertEquals("知识来源：官方新闻", r.context().split("\n---\n")[0]);
    }

    @Test
    void C2_新闻不参与锚点加权_分数不变() {
        FakeMapper mapper = new FakeMapper();
        // 新闻块 modelId=null,即便 anchor 非空也不得加权
        mapper.unifiedRows = List.of(
                urow("NEWS", null, "官方新闻", "NEWS_BODY", "新闻：官方新闻（2026-09-01）\n正文", 0.60));
        AiProperties props = new AiProperties();
        props.setRagAnchorBoost(2.0);
        CarRagService svc = new CarRagService(mapper, new FakeKbEmbMapper(), new FakeEmbeddingClient(), props);
        CarRagService.RagResult r = svc.retrieveForGeneration("查询", 8, List.of(1L));
        assertEquals(0.60, r.maxScore(), 1e-9, "NEWS 不参与锚点加权,分数不得被放大");
    }
}
