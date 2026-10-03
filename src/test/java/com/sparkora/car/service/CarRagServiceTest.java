package com.sparkora.car.service;

import com.sparkora.ai.vector.SearchStore;
import com.sparkora.car.client.EmbeddingClient;
import com.sparkora.config.AiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S6.1「必查+降级可见」门槛逻辑单测(手写假件,不连 PG/embedding)。
 * 覆盖:OK / LOW_CONFIDENCE(整体抛弃,context 必须为空) / FAILED(异常不抛出) / NO_KNOWLEDGE(无对象);
 * S7 扩展:双源合并(KB 块带【通用知识】前缀)/ 未关联车型仍查 KB / KB 开关关闭 / KB 异常→FAILED。
 *
 * 10-03 E1:读路径改经 {@link SearchStore}(单表 store 抽象),用 {@link FakeSearchStore} 复现
 * 旧行集语义(按域过滤 + topK)。
 */
class CarRagServiceTest {

    private CarRagService newService(FakeSearchStore store) {
        AiProperties props = new AiProperties(); // 默认 ragMinScore=0.3 / ragRejectScore=0.5 / kbTopk=4 / kbEnabled=true
        return new CarRagService(store, new FakeEmbeddingClient(), props);
    }

    /** 手写 embedding 客户端假实现(固定返回合法向量字符串,不发起 HTTP)。 */
    static class FakeEmbeddingClient extends EmbeddingClient {
        FakeEmbeddingClient() { super(new AiProperties()); }
        @Override
        public String embed(String query) { return "[0.1,0.2]"; }
    }

    /** 单表 store 读取假件:把「旧检索行」映射为 Document,按 domain 过滤。 */
    static class FakeSearchStore implements SearchStore {
        List<Map<String, Object>> unifiedRows = List.of();
        RuntimeException unifiedThrow = null;
        Map<Long, List<Map<String, Object>>> byModelId = new HashMap<>();
        RuntimeException byModelThrow = null;

        @Override
        public List<Document> searchDomains(Collection<String> domains, String query, int topK,
                                            double similarityThreshold, String embeddingModel) {
            if (unifiedThrow != null) throw unifiedThrow;
            List<Document> out = new ArrayList<>();
            for (Map<String, Object> r : unifiedRows) {
                String src = r.get("source") == null ? "CAR" : String.valueOf(r.get("source"));
                if (!domains.contains(src)) continue;
                out.add(toDoc(r, src));
                if (out.size() >= topK) break;
            }
            return out;
        }

        @Override
        public List<Document> searchByModel(Long modelId, String query, int topK,
                                            double similarityThreshold, String embeddingModel) {
            if (byModelThrow != null) throw byModelThrow;
            List<Map<String, Object>> rows = byModelId.getOrDefault(modelId, List.of());
            List<Document> out = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                out.add(toDoc(r, "CAR"));
                if (out.size() >= topK) break;
            }
            return out;
        }

        @Override
        public List<Document> searchImages(String query, Collection<Long> refIds, double minScore,
                                           int topK, String embeddingModel) {
            return List.of();
        }

        private static Document toDoc(Map<String, Object> r, String source) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("domain", source);
            if (r.get("refId") != null) meta.put("refId", ((Number) r.get("refId")).longValue());
            else if (r.get("docId") != null) meta.put("refId", ((Number) r.get("docId")).longValue());
            if (r.get("modelId") != null) meta.put("modelId", ((Number) r.get("modelId")).longValue());
            if (r.get("chunkType") != null) meta.put("chunkType", String.valueOf(r.get("chunkType")));
            if (r.get("modelName") != null) meta.put("name", String.valueOf(r.get("modelName")));
            if (r.get("score") != null) meta.put("score", ((Number) r.get("score")).doubleValue());
            Document.Builder b = Document.builder()
                    .id(String.valueOf(r.getOrDefault("refId", r.getOrDefault("docId", 0))))
                    .text(r.get("chunkText") == null ? "" : String.valueOf(r.get("chunkText")))
                    .metadata(meta);
            if (r.get("score") != null) b.score(((Number) r.get("score")).doubleValue());
            return b.build();
        }
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

    /** 统一检索行(带显式 docId;09-15 qa-auto-illustrate 补读该列,供 NEWS 配图定位)。 */
    private static Map<String, Object> urowDoc(String source, Long modelId, String modelName,
                                               String chunkType, String text, double score, Long docId) {
        Map<String, Object> m = urow(source, modelId, modelName, chunkType, text, score);
        if (docId == null) m.remove("docId"); else m.put("docId", docId);
        return m;
    }

    @Test
    void 命中且最高分过整体门槛_状态OK_上下文完整() {
        FakeSearchStore store = new FakeSearchStore();
        store.unifiedRows = List.of(
                urow("CAR", 1L, "大唐EV", "PARAM_GROUP", "车型：大唐EV\n续航 600km", 0.82),
                urow("CAR", 1L, "大唐EV", "RIGHTS", "车型：大唐EV 购车权益：权益", 0.55));
        CarRagService svc = newService(store);

        CarRagService.RagResult r = svc.retrieveForGeneration("大唐EV 续航", 8, List.of());
        assertEquals(CarRagService.RagStatus.OK, r.status());
        assertEquals(2, r.hitCount());
        assertEquals(0.82, r.maxScore(), 1e-9);
        assertTrue(r.context().contains("600km"));
    }

    @Test
    void 有命中但最高分低于整体门槛_状态LOW_CONFIDENCE_上下文必须为空() {
        FakeSearchStore store = new FakeSearchStore();
        store.unifiedRows = List.of(
                urow("CAR", 1L, "车型A", "PARAM_GROUP", "车型：车型A\n完全无关内容", 0.45),
                urow("CAR", 1L, "车型A", "PARAM_GROUP", "车型：车型A\n也很无关", 0.38));
        CarRagService svc = newService(store);

        CarRagService.RagResult r = svc.retrieveForGeneration("query", 8, List.of());
        assertEquals(CarRagService.RagStatus.LOW_CONFIDENCE, r.status());
        assertEquals("", r.context(), "低置信抛弃后不得把知识块注入 prompt");
        assertEquals(2, r.hitCount(), "hitCount 保留观测值");
        assertEquals(0.45, r.maxScore(), 1e-9);
    }

    @Test
    void 检索异常_不抛出_状态FAILED_上下文为空() {
        FakeSearchStore store = new FakeSearchStore();
        store.unifiedThrow = new RuntimeException("embedding down");
        CarRagService svc = newService(store);

        CarRagService.RagResult r = svc.retrieveForGeneration("query", 8, List.of(1L));
        assertEquals(CarRagService.RagStatus.FAILED, r.status());
        assertEquals("", r.context());
    }

    @Test
    void 无车型对象_状态NO_KNOWLEDGE() {
        // S7:未关联车型但 KB 开启且 KB 无命中 → 仍为 EMPTY(NO_KNOWLEDGE)
        CarRagService svc = newService(new FakeSearchStore());
        assertEquals(CarRagService.RagResult.EMPTY, svc.retrieveForGeneration(List.of(), "query", 8));
        assertEquals(CarRagService.RagResult.EMPTY, svc.retrieveForGeneration(null, "query", 8));
    }

    @Test
    void S7_未关联车型_仍检索通用域并注入() {
        FakeSearchStore store = new FakeSearchStore();
        store.unifiedRows = List.of(
                urow("KB", null, "家用充电桩选择要点", "KB_CHUNK", "知识：家用充电桩选择要点（充电）\n看车型最大充电功率。", 0.8));
        CarRagService svc = newService(store);
        CarRagService.RagResult r = svc.retrieveForGeneration("充电桩怎么选", 8, List.of());
        assertEquals(CarRagService.RagStatus.OK, r.status());
        assertTrue(r.context().contains("知识来源：通用知识库"));
        assertTrue(r.context().contains("【通用知识：家用充电桩选择要点】"));
        assertTrue(r.maxScore() >= 0.8);
    }

    @Test
    void S7_双源同时命中_来源行标注双源_KB独立配额注入() {
        FakeSearchStore store = new FakeSearchStore();
        store.unifiedRows = List.of(
                urow("CAR", 1L, "海狮08", "PARAM_GROUP", "车型：海狮08\n参数分组：动力\n前电机最大功率（kW）：200", 0.9),
                urow("KB", null, "充电功率常识", "KB_CHUNK", "知识：充电功率常识（充电）\n7kW 家充为交流慢充。", 0.7));
        CarRagService svc = newService(store);
        CarRagService.RagResult r = svc.retrieveForGeneration("海狮08 动力与充电", 8, List.of(1L));
        assertEquals(CarRagService.RagStatus.OK, r.status());
        assertTrue(r.context().contains("知识来源：车型数据 + 通用知识库"));
        assertTrue(r.context().contains("【通用知识：充电功率常识】"));
        assertTrue(r.context().contains("车型：海狮08"));
    }

    @Test
    void S7_KB开关关闭_回退S62行为_未关联车型零注入() {
        FakeSearchStore store = new FakeSearchStore();
        store.unifiedRows = List.of(
                urow("CAR", 1L, "车型X", "MODEL_INFO", "车型：车型X\n车型块", 0.9),
                urow("KB", null, "不应被检索", "KB_CHUNK", "知识：不应被检索（通用）\n内容", 0.85));
        AiProperties props = new AiProperties();
        props.setRagKbEnabled(false);
        CarRagService svc = new CarRagService(store, new FakeEmbeddingClient(), props);
        // KB 关闭:KB 块被配额排除,车型块照常注入(S8 语义)
        CarRagService.RagResult r = svc.retrieveForGeneration("query", 8, List.of(1L));
        assertEquals(CarRagService.RagStatus.OK, r.status());
        assertTrue(!r.context().contains("不应被检索"));
    }

    @Test
    void S7_KB检索异常_整体标FAILED_车型块仍注入() {
        FakeSearchStore store = new FakeSearchStore();
        store.unifiedRows = List.of(
                urow("CAR", 1L, "海狮08", "PARAM_GROUP", "车型：海狮08\n参数分组：动力\n前电机最大功率（kW）：200", 0.9));
        // S8:生成链路只走统一检索(单次);此用例保留语义:正常命中即 OK
        CarRagService svc = newService(store);
        CarRagService.RagResult r = svc.retrieveForGeneration("query", 8, List.of(1L));
        assertEquals(CarRagService.RagStatus.OK, r.status());
    }

    // ==================== S8 统一检索(去车型门禁) ====================

    @Test
    void S8_统一检索_未关联车型_命中车型块_来源行内标注() {
        FakeSearchStore store = new FakeSearchStore();
        store.unifiedRows = List.of(
                urow("CAR", 55L, "海狮08EV", "MODEL_INFO", "车型：海狮08EV\n价格区间：239,900 - 279,900", 0.85),
                urow("CAR", 55L, "海狮08EV", "PARAM_GROUP", "车型：海狮08EV\n参数分组：基础参数\n长×宽×高：4810×1920×1675", 0.75),
                urow("KB", null, "家用充电桩选择要点", "KB_CHUNK", "知识：家用充电桩选择要点（充电）\n功率选择。", 0.6));
        CarRagService svc = newService(store);
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
        FakeSearchStore store = new FakeSearchStore();
        store.unifiedRows = List.of(
                urow("CAR", 55L, "海狮08EV", "PARAM_GROUP", "车型：海狮08EV\n参数分组：动力\n前电机最大功率（kW）：200", 0.60),
                urow("CAR", 39L, "大唐EV", "PARAM_GROUP", "车型：大唐EV\n参数分组：动力\n前电机最大功率（kW）：180", 0.70));
        AiProperties props = new AiProperties();
        props.setRagAnchorBoost(1.5);   // 放大系数让断言明确
        CarRagService svc = new CarRagService(store, new FakeEmbeddingClient(), props);
        CarRagService.RagResult r = svc.retrieveForGeneration("动力对比", 4, List.of(55L));
        assertEquals(CarRagService.RagStatus.OK, r.status());
        int anchorIdx = r.context().indexOf("海狮08EV");
        int otherIdx = r.context().indexOf("大唐EV");
        assertTrue(anchorIdx >= 0 && otherIdx >= 0 && anchorIdx < otherIdx, "锚点车型块加权后应排在前");
    }

    @Test
    void S8_旧签名委托_行为等于新签名锚点() {
        FakeSearchStore store = new FakeSearchStore();
        store.unifiedRows = List.of(
                urow("CAR", 55L, "海狮08EV", "MODEL_INFO", "车型：海狮08EV\n价格区间：239,900 - 279,900", 0.85));
        CarRagService svc = newService(store);
        CarRagService.RagResult viaOld = svc.retrieveForGeneration(List.of(55L), "海狮08 价格", 8);
        CarRagService.RagResult viaNew = svc.retrieveForGeneration("海狮08 价格", 8, List.of(55L));
        assertEquals(viaNew.status(), viaOld.status());
        assertEquals(viaNew.context(), viaOld.context());
    }

    @Test
    void S8_统一检索异常_标FAILED() {
        FakeSearchStore store = new FakeSearchStore();
        store.unifiedThrow = new RuntimeException("embedding down");
        CarRagService svc = newService(store);
        CarRagService.RagResult r = svc.retrieveForGeneration("query", 8, List.of());
        assertEquals(CarRagService.RagStatus.FAILED, r.status());
    }

    @Test
    void S8_KB开关关闭_统一检索仍跑_KB块被排除() {
        FakeSearchStore store = new FakeSearchStore();
        store.unifiedRows = List.of(
                urow("CAR", 55L, "海狮08EV", "MODEL_INFO", "车型：海狮08EV\n价格区间：239,900 - 279,900", 0.9),
                urow("KB", null, "不应出现", "KB_CHUNK", "知识：不应出现（通用）\n内容", 0.85));
        AiProperties props = new AiProperties();
        props.setRagKbEnabled(false);
        CarRagService svc = new CarRagService(store, new FakeEmbeddingClient(), props);
        CarRagService.RagResult r = svc.retrieveForGeneration("海狮08 价格", 8, List.of());
        assertEquals(CarRagService.RagStatus.OK, r.status());
        assertTrue(r.context().contains("海狮08EV"));
        assertTrue(!r.context().contains("不应出现"));
        assertEquals("知识来源：车型数据", r.context().split("\n---\n")[0]);
    }

    @Test
    void 多车型_其一失败_整体标FAILED_不得部分注入() {
        FakeSearchStore store = new FakeSearchStore();
        // S8:统一检索无逐车型循环,单次异常即 FAILED(见 S8_统一检索异常_标FAILED)。
        // 保留多锚点语义验证:两个锚点车型均正常命中 → OK
        store.unifiedRows = List.of(
                urow("CAR", 1L, "车型1", "PARAM_GROUP", "车型：车型1\n高相关", 0.9),
                urow("CAR", 2L, "车型2", "PARAM_GROUP", "车型：车型2\n高相关2", 0.7));
        CarRagService svc = newService(store);

        CarRagService.RagResult r = svc.retrieveForGeneration("query", 8, List.of(1L, 2L));
        assertEquals(CarRagService.RagStatus.OK, r.status());
    }

    @Test
    void 跨车型合并_分数与块数正确() {
        FakeSearchStore store = new FakeSearchStore();
        store.unifiedRows = List.of(
                urow("CAR", 1L, "车型1", "PARAM_GROUP", "车型：车型1\n块一", 0.9),
                urow("CAR", 2L, "车型2", "PARAM_GROUP", "车型：车型2\n块二", 0.7));
        CarRagService svc = newService(store);

        CarRagService.RagResult r = svc.retrieveForGeneration("query", 8, List.of());
        assertEquals(CarRagService.RagStatus.OK, r.status());
        assertEquals(2, r.hitCount());
        assertEquals(0.9, r.maxScore(), 1e-9);
        assertTrue(r.context().contains("块一") && r.context().contains("块二"));
    }

    @Test
    void 权益块限流_参数块优先_总块数受配额约束() {
        FakeSearchStore store = new FakeSearchStore();
        java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            rows.add(urow("CAR", 1L, "海狮08EV", "RIGHTS", "车型：海狮08EV 购车权益内容" + i + "很长的文本", 0.9 - i * 0.01));
        }
        rows.add(urow("CAR", 1L, "海狮08EV", "PARAM_GROUP", "车型：海狮08EV\n参数分组：动力性能\n前电机最大功率（kW）：200", 0.62));
        rows.add(urow("CAR", 1L, "海狮08EV", "PARAM_GROUP", "车型：海狮08EV\n参数分组：尺寸参数\n轴距（mm）：3030", 0.60));
        store.unifiedRows = rows;
        CarRagService svc = newService(store);
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
        FakeSearchStore store = new FakeSearchStore();
        store.unifiedRows = List.of(
                urow("CAR", 1L, "海狮08EV", "PARAM_GROUP", "参数分组：海狮08EV参数表及配置表", 0.99),
                urow("CAR", 1L, "海狮08EV", "PARAM_GROUP", "参数分组：动力性能\n前电机最大功率（kW）：200", 0.6));
        CarRagService svc = newService(store);

        CarRagService.RagResult r = svc.retrieveForGeneration("query", 8, List.of(1L));
        assertTrue(r.context().contains("前电机最大功率"));
        assertTrue(!r.context().contains("海狮08EV参数表及配置表"), "单行表头块必须丢弃");
    }

    @Test
    void 子查询派生_含参数词时生成对应子查询() {
        CarRagService svc = newService(new FakeSearchStore());
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

    // ==================== 10-03 E5：覆盖度三域统一 ====================

    /**
     * CAR-only 历史行为回归锁：仅 CAR 参数块命中时，coveredText 与改造前
     * （逐块 extractParamSummary 直接拼接）**逐字等价**。
     */
    @Test
    void E5_coveredText_CARonly_与改造前逐字等价() {
        FakeSearchStore store = new FakeSearchStore();
        String carText = "车型：海狮08EV\n参数分组：动力\n前电机最大功率（kW）：200\n续航（km）：700";
        store.unifiedRows = List.of(urow("CAR", 55L, "海狮08EV", "PARAM_GROUP", carText, 0.9));
        CarRagService svc = newService(store);

        CarRagService.RagResult r = svc.retrieveForGeneration("海狮08EV", 8, List.of());

        assertEquals(CarRagService.RagStatus.OK, r.status());
        // 改造前语义 = 对每个 CAR 选中块调用 extractParamSummary 后直接拼接
        assertEquals(CarRagService.extractParamSummary(carText), r.coveredText(),
                "CAR-only 时 coveredText 必须与改造前逐字等价（回归锁）");
        assertTrue(!r.coveredText().contains("〔"), "CAR-only 不得出现 KB/NEWS 覆盖段");
    }

    @Test
    void E5_coveredText_KB数值事实_格式带标题() {
        FakeSearchStore store = new FakeSearchStore();
        store.unifiedRows = List.of(
                urow("KB", null, "充电功率常识", "KB_CHUNK", "知识：充电功率常识（充电）\n7kW 家充为交流慢充。", 0.8));
        CarRagService svc = newService(store);

        CarRagService.RagResult r = svc.retrieveForGeneration("充电桩怎么选", 8, List.of());

        assertEquals(CarRagService.RagStatus.OK, r.status());
        assertEquals("〔通用知识：充电功率常识〕7", r.coveredText());
    }

    @Test
    void E5_coveredText_NEWS数值事实_格式带标题() {
        FakeSearchStore store = new FakeSearchStore();
        store.unifiedRows = List.of(
                urow("NEWS", null, "官方新闻", "NEWS_BODY",
                        "新闻：官方新闻（2026-09-01）\n正文", 0.8));
        CarRagService svc = newService(store);

        CarRagService.RagResult r = svc.retrieveForGeneration("查询", 8, List.of());

        assertEquals(CarRagService.RagStatus.OK, r.status());
        assertTrue(r.coveredText().startsWith("〔官方新闻：官方新闻〕"),
                () -> "NEWS 数值段: " + r.coveredText());
    }

    @Test
    void E5_coveredText_三域并存_CAR在前_KB_NEWS以分号追加() {
        FakeSearchStore store = new FakeSearchStore();
        store.unifiedRows = List.of(
                urow("CAR", 1L, "海狮08", "PARAM_GROUP", "车型：海狮08\n参数分组：动力\n前电机最大功率（kW）：200", 0.9),
                urow("KB", null, "充电常识", "KB_CHUNK", "知识：充电常识（充电）\n7kW 家充。", 0.8),
                urow("NEWS", null, "官方新闻", "NEWS_BODY",
                        "新闻：官方新闻（2026-09-01）\n续航 700km 正文。", 0.7));
        CarRagService svc = newService(store);

        CarRagService.RagResult r = svc.retrieveForGeneration("海狮08 充电", 8, List.of(1L));

        String cv = r.coveredText();
        assertTrue(cv.contains("前电机最大功率（kW）→200"), "CAR 参数段保留");
        assertTrue(cv.contains("〔通用知识：充电常识〕"), "KB 数值段存在");
        assertTrue(cv.contains("〔官方新闻：官方新闻〕"), "NEWS 数值段存在");
        assertTrue(cv.indexOf("前电机最大功率") < cv.indexOf("〔通用知识"), "CAR 段在 KB/NEWS 段之前");
    }

    @Test
    void E5_coveredText_无标题保留冒号() {
        String seg = CarRagService.coverageSegment("通用知识", null, "知识：无标题\n参数 700");
        assertEquals("〔通用知识：〕700", seg);
    }

    @Test
    void E5_coveredText_块内无数值_不产出覆盖段() {
        FakeSearchStore store = new FakeSearchStore();
        store.unifiedRows = List.of(
                urow("KB", null, "定性知识", "KB_CHUNK", "知识：定性知识（通用）\n无任何数字的定性描述。", 0.8));
        CarRagService svc = newService(store);

        CarRagService.RagResult r = svc.retrieveForGeneration("查询", 8, List.of());

        assertEquals(CarRagService.RagStatus.OK, r.status());
        assertEquals("", r.coveredText(), "无数值块不产出覆盖度");
    }

    @Test
    void E5_coverageSegment_数值口径与C7同源_1200与12000不误配() {
        assertEquals("〔通用知识：X〕1200", CarRagService.coverageSegment("通用知识", "X", "值 1200"));
        assertEquals("〔通用知识：X〕12000", CarRagService.coverageSegment("通用知识", "X", "值 12000"));
    }

    @Test
    void E5_buildExtraCoverage_去重保序_长度上限() {
        String a = "〔通用知识：A〕1";
        String b = "〔官方新闻：B〕2";
        assertEquals(a + "；" + b, CarRagService.buildExtraCoverage(List.of(a, a, b)));
        assertEquals(a, CarRagService.buildExtraCoverage(List.of("", a, "")));
        assertEquals("", CarRagService.buildExtraCoverage(List.of()));
    }

    // ==================== C2 新闻域(NEWS) ====================

    @Test
    void C2_新闻块命中_来源标注官方新闻_三域来源行() {
        FakeSearchStore store = new FakeSearchStore();
        store.unifiedRows = List.of(
                urow("CAR", 1L, "海狮08EV", "PARAM_GROUP", "车型：海狮08EV\n参数分组：动力\n前电机最大功率（kW）：200", 0.9),
                urow("KB", null, "充电功率常识", "KB_CHUNK", "知识：充电功率常识（充电）\n7kW 家充为交流慢充。", 0.8),
                urow("NEWS", null, "比亚迪发布新车型", "NEWS_BODY", "新闻：比亚迪发布新车型（2026-09-01）\n官方新闻正文。", 0.7));
        CarRagService svc = newService(store);
        CarRagService.RagResult r = svc.retrieveForGeneration("比亚迪 新车型", 8, List.of(1L));
        assertEquals(CarRagService.RagStatus.OK, r.status());
        assertTrue(r.context().contains("知识来源：车型数据 + 通用知识库 + 官方新闻"),
                () -> "三域来源行: " + r.context());
        assertTrue(r.context().contains("【官方新闻：比亚迪发布新车型】"));
        assertTrue(r.citations().stream().anyMatch(c -> "NEWS".equals(c.source())), "NEWS 块应进 citations");
    }

    @Test
    void C2_仅新闻命中_来源行为官方新闻() {
        FakeSearchStore store = new FakeSearchStore();
        store.unifiedRows = List.of(
                urow("NEWS", null, "官方新闻标题", "NEWS_BODY", "新闻：官方新闻标题（2026-09-01）\n正文", 0.8));
        CarRagService svc = newService(store);
        CarRagService.RagResult r = svc.retrieveForGeneration("新闻查询", 8, List.of());
        assertEquals(CarRagService.RagStatus.OK, r.status());
        assertEquals("知识来源：官方新闻", r.context().split("\n---\n")[0]);
        assertTrue(r.context().contains("【官方新闻：官方新闻标题】"));
    }

    @Test
    void C2_新闻配额为0_不注入NEWS_其他域不受影响() {
        FakeSearchStore store = new FakeSearchStore();
        store.unifiedRows = List.of(
                urow("CAR", 1L, "海狮08EV", "MODEL_INFO", "车型：海狮08EV\n价格区间：239,900", 0.9),
                urow("NEWS", null, "不应注入", "NEWS_BODY", "新闻：不应注入（2026-09-01）\n正文", 0.85));
        AiProperties props = new AiProperties();
        props.setRagNewsTopk(0);
        CarRagService svc = new CarRagService(store, new FakeEmbeddingClient(), props);
        CarRagService.RagResult r = svc.retrieveForGeneration("海狮08", 8, List.of(1L));
        assertEquals(CarRagService.RagStatus.OK, r.status());
        assertTrue(r.context().contains("海狮08EV"));
        assertTrue(!r.context().contains("不应注入"), "ragNewsTopk=0 时 NEWS 不注入");
    }

    @Test
    void C2_NEWS不受KB开关控制_KB关闭仍注入新闻() {
        FakeSearchStore store = new FakeSearchStore();
        store.unifiedRows = List.of(
                urow("KB", null, "不应出现", "KB_CHUNK", "知识：不应出现（通用）\n内容", 0.9),
                urow("NEWS", null, "官方新闻", "NEWS_BODY", "新闻：官方新闻（2026-09-01）\n正文", 0.85));
        AiProperties props = new AiProperties();
        props.setRagKbEnabled(false);   // KB 关闭;NEWS 独立配额,不受其控制
        CarRagService svc = new CarRagService(store, new FakeEmbeddingClient(), props);
        CarRagService.RagResult r = svc.retrieveForGeneration("查询", 8, List.of());
        assertEquals(CarRagService.RagStatus.OK, r.status());
        assertTrue(!r.context().contains("不应出现"), "KB 关闭后 KB 块被排除");
        assertTrue(r.context().contains("【官方新闻：官方新闻】"), "NEWS 不受 ragKbEnabled 控制");
        assertEquals("知识来源：官方新闻", r.context().split("\n---\n")[0]);
    }

    @Test
    void C2_新闻不参与锚点加权_分数不变() {
        FakeSearchStore store = new FakeSearchStore();
        // 新闻块 modelId=null,即便 anchor 非空也不得加权
        store.unifiedRows = List.of(
                urow("NEWS", null, "官方新闻", "NEWS_BODY", "新闻：官方新闻（2026-09-01）\n正文", 0.60));
        AiProperties props = new AiProperties();
        props.setRagAnchorBoost(2.0);
        CarRagService svc = new CarRagService(store, new FakeEmbeddingClient(), props);
        CarRagService.RagResult r = svc.retrieveForGeneration("查询", 8, List.of(1L));
        assertEquals(0.60, r.maxScore(), 1e-9, "NEWS 不参与锚点加权,分数不得被放大");
    }

    // ==================== 09-15 qa-auto-illustrate：Citation/UnifiedHit docId ====================

    @Test
    void retrieveUnified_读入docId_可空不NPE() {
        FakeSearchStore store = new FakeSearchStore();
        store.unifiedRows = List.of(
                urowDoc("NEWS", null, "官方新闻标题", "NEWS_BODY", "新闻正文", 0.8, 1688L),
                urowDoc("CAR", 1L, "海狮08EV", "PARAM_GROUP", "车型块", 0.7, null));   // 无 docId 行
        CarRagService svc = newService(store);

        List<CarRagService.UnifiedHit> hits = svc.retrieveUnified("查询", 32);

        assertEquals(2, hits.size());
        assertEquals(1688L, hits.get(0).docId(), "docId 必须从 store metadata 读入");
        assertNull(hits.get(1).docId(), "缺列/空值必须为 null 而非 NPE");
    }

    @Test
    void retrieveForGeneration_citations透传docId_NEWS块可定位来源新闻() {
        FakeSearchStore store = new FakeSearchStore();
        store.unifiedRows = List.of(
                urowDoc("NEWS", null, "比亚迪发布新车型", "NEWS_BODY", "新闻：比亚迪发布新车型（2026-09-01）\n正文", 0.8, 1688L));
        CarRagService svc = newService(store);

        CarRagService.RagResult r = svc.retrieveForGeneration("比亚迪 新车型", 8, List.of());

        assertEquals(CarRagService.RagStatus.OK, r.status());
        CarRagService.Citation newsCite = r.citations().stream()
                .filter(c -> "NEWS".equals(c.source())).findFirst().orElseThrow();
        assertEquals(1688L, newsCite.docId(), "NEWS 引用必须带 docId(问答配图链路依赖)");
    }

    /**
     * 最高风险点回归：锚点加权分支会**重建** UnifiedHit，漏传 docId 会让域内 id 在加权后丢失
     * → NEWS/CAR 配图静默失效。此处用 CAR 锚点块走该分支，断言 docId 保留。
     */
    @Test
    void 锚点加权重排_docId必须透传不丢失() {
        FakeSearchStore store = new FakeSearchStore();
        store.unifiedRows = List.of(
                urowDoc("CAR", 55L, "海狮08EV", "PARAM_GROUP", "车型：海狮08EV\n参数分组：动力\n前电机最大功率（kW）：200", 0.60, 777L),
                urowDoc("NEWS", null, "官方新闻", "NEWS_BODY", "新闻：官方新闻（2026-09-01）\n正文", 0.65, 1688L));
        AiProperties props = new AiProperties();
        props.setRagAnchorBoost(1.5);   // 放大系数让锚点块走加权重建分支,且重排后仍入选
        CarRagService svc = new CarRagService(store, new FakeEmbeddingClient(), props);

        CarRagService.RagResult r = svc.retrieveForGeneration("动力对比", 4, List.of(55L));

        assertEquals(CarRagService.RagStatus.OK, r.status());
        CarRagService.Citation carCite = r.citations().stream()
                .filter(c -> "CAR".equals(c.source())).findFirst().orElseThrow();
        assertEquals(777L, carCite.docId(), "boost 重排重建 UnifiedHit 时必须透传 docId");
        CarRagService.Citation newsCite = r.citations().stream()
                .filter(c -> "NEWS".equals(c.source())).findFirst().orElseThrow();
        assertEquals(1688L, newsCite.docId(), "NEWS 块不走加权分支,docId 同样不得丢失");
    }

    @Test
    void Citation五参兼容构造器_docId为null() {
        CarRagService.Citation c = new CarRagService.Citation("CAR", "比亚迪", "PARAM_GROUP", 0.9, "块文本");
        assertNull(c.docId(), "既有 5 参调用方(简报/深度检索/测试)不受影响,docId 为 null");
        assertEquals("CAR", c.source());
        assertEquals("块文本", c.chunkText());
    }

    // ==================== A rerank（10-03-a-rerank） ====================

    /** 记录调用次数的 Reranker 假件；默认 identity（原序）。 */
    static class RecordingReranker implements Reranker {
        int calls = 0;
        List<Integer> lastOrder;
        RuntimeException error;

        @Override
        public List<CarRagService.UnifiedHit> rerank(String query, List<CarRagService.UnifiedHit> candidates, int keepTopN) {
            calls++;
            if (error != null) throw error;
            if (lastOrder == null) return candidates;
            // 按给定下标重排
            List<CarRagService.UnifiedHit> out = new java.util.ArrayList<>();
            for (int i : lastOrder) out.add(candidates.get(i));
            return out;
        }
    }

    @Test
    void A_关闭态_不调用reranker_行为与现状一致() {
        FakeSearchStore store = new FakeSearchStore();
        store.unifiedRows = List.of(
                urow("CAR", 1L, "海狮08", "PARAM_GROUP", "车型：海狮08\n参数分组：动力\n前电机最大功率（kW）：200", 0.9),
                urow("KB", null, "充电常识", "KB_CHUNK", "知识：充电常识（充电）\n7kW 家充。", 0.7));
        AiProperties props = new AiProperties();   // ragRerankEnabled 默认 false
        RecordingReranker reranker = new RecordingReranker();
        CarRagService svc = new CarRagService(store, new FakeEmbeddingClient(), props, reranker);

        CarRagService.RagResult r = svc.retrieveForGeneration("海狮08 充电", 8, List.of(1L));

        assertEquals(CarRagService.RagStatus.OK, r.status());
        assertEquals(0, reranker.calls, "关闭态绝不调用 reranker(零回归)");
        assertTrue(r.context().contains("前电机最大功率"));
    }

    @Test
    void A_开启态_调用reranker并按新序注入() {
        FakeSearchStore store = new FakeSearchStore();
        // 原分序: b(0.9) 在 a(0.7) 前;重排置 a 在前
        store.unifiedRows = List.of(
                urow("CAR", 1L, "车型A", "PARAM_GROUP", "车型：车型A\n参数分组：动力\nAAA：100", 0.7),
                urow("CAR", 1L, "车型B", "PARAM_GROUP", "车型：车型B\n参数分组：动力\nBBB：200", 0.9));
        AiProperties props = new AiProperties();
        props.setRagRerankEnabled(true);
        RecordingReranker reranker = new RecordingReranker();
        // merged 经 retrieveUnified 已按分数降序 = [b(0.9), a(0.7)];order [1,0] 把 a 放前
        reranker.lastOrder = List.of(1, 0);
        CarRagService svc = new CarRagService(store, new FakeEmbeddingClient(), props, reranker);

        CarRagService.RagResult r = svc.retrieveForGeneration("动力", 8, List.of(1L));

        assertEquals(1, reranker.calls);
        assertEquals(CarRagService.RagStatus.OK, r.status());
        assertTrue(r.context().indexOf("AAA") < r.context().indexOf("BBB"), "重排后 a 应排前");
    }

    @Test
    void A_重排不改分数_四态与maxScore基于原分() {
        FakeSearchStore store = new FakeSearchStore();
        store.unifiedRows = List.of(
                urow("CAR", 1L, "车型A", "PARAM_GROUP", "车型：车型A\n参数分组：动力\nAAA：100", 0.7));
        AiProperties props = new AiProperties();
        props.setRagRerankEnabled(true);
        RecordingReranker reranker = new RecordingReranker();
        CarRagService svc = new CarRagService(store, new FakeEmbeddingClient(), props, reranker);

        // 空 anchor,避免锚点加权干扰「分数不被重排改动」的断言
        CarRagService.RagResult r = svc.retrieveForGeneration("动力", 8, List.of());

        assertEquals(0.7, r.maxScore(), 1e-9, "重排不得改分数,maxScore 仍为原相似度");
    }

    @Test
    void A_reranker抛异常_降级原序_不阻断生成() {
        FakeSearchStore store = new FakeSearchStore();
        store.unifiedRows = List.of(
                urow("CAR", 1L, "车型A", "PARAM_GROUP", "车型：车型A\n参数分组：动力\nAAA：100", 0.9));
        AiProperties props = new AiProperties();
        props.setRagRerankEnabled(true);
        RecordingReranker reranker = new RecordingReranker();
        reranker.error = new RuntimeException("LLM down");
        CarRagService svc = new CarRagService(store, new FakeEmbeddingClient(), props, reranker);

        CarRagService.RagResult r = svc.retrieveForGeneration("动力", 8, List.of(1L));

        assertEquals(CarRagService.RagStatus.OK, r.status(), "重排异常必须降级原序,不阻断生成");
        assertTrue(r.context().contains("AAA"));
    }
}
