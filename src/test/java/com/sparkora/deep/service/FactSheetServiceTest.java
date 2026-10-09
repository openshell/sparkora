package com.sparkora.deep.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FactSheetService 合并/置信/冲突单测(纯函数级,不连库)。
 */
class FactSheetServiceTest {

    private final ObjectMapper json = new ObjectMapper();
    private final FactSheetService svc = new FactSheetService(json);

    private String notes(String factsJson) {
        return "[{\"agentId\":1,\"question\":\"q\",\"status\":\"DONE\",\"factsJson\":\"" +
                factsJson.replace("\"", "\\\"") + "\",\"webCount\":0}]";
    }

    /** 两条 agent 笔记(各自 factsJson),方便组装异源用例。 */
    private String twoNotes(String facts1, String facts2) {
        return "["
                + "{\"agentId\":1,\"question\":\"q\",\"status\":\"DONE\",\"factsJson\":\"" + facts1.replace("\"", "\\\"") + "\",\"webCount\":0},"
                + "{\"agentId\":2,\"question\":\"q\",\"status\":\"DONE\",\"factsJson\":\"" + facts2.replace("\"", "\\\"") + "\",\"webCount\":1}]";
    }

    private JsonNode sheet(String merged) throws Exception {
        return json.readTree(merged);
    }

    @Test
    void 单KB条目_置信09_无警告() throws Exception {
        String notes = notes("{\"facts\":[{\"claim\":\"海狮08EV起售价239900\",\"value\":\"239900\",\"source\":{\"type\":\"KB\",\"docId\":1},\"confidence\":0.9}],\"gaps\":[]}");
        String merged = svc.merge(notes);
        assertTrue(merged.contains("239900"));
        assertTrue(merged.contains("0.9"));
        assertTrue(!merged.contains("待核实"));
        assertNotNullEntry(sheet(merged), "海狮08EV起售价239900");
    }

    @Test
    void 单WEB条目_置信04_进warnings() throws Exception {
        String notes = notes("{\"facts\":[{\"claim\":\"竞品ModelY起售价\",\"source\":{\"type\":\"WEB\",\"url\":\"https://x\"},\"confidence\":0.6}],\"gaps\":[]}");
        String merged = svc.merge(notes);
        assertTrue(merged.contains("待核实"));
    }

    @Test
    void 同claim两源_KB胜出_R2冲突裁决() throws Exception {
        // R2 冲突裁决(2026-09-06):同 claim 含 KB+WEB 时 KB 胜出(置信取 KB 0.9),
        // WEB 降为 alternatives 并警告「以知识库为准」——取代旧的交叉置信 0.85 规则
        String notes = "["
                + "{\"agentId\":1,\"question\":\"q\",\"status\":\"DONE\",\"factsJson\":\"{\\\"facts\\\":[{\\\"claim\\\":\\\"海狮08EV 239,900\\\",\\\"source\\\":{\\\"type\\\":\\\"KB\\\"},\\\"confidence\\\":0.9}],\\\"gaps\\\":[]}\",\"webCount\":0},"
                + "{\"agentId\":2,\"question\":\"q\",\"status\":\"DONE\",\"factsJson\":\"{\\\"facts\\\":[{\\\"claim\\\":\\\"海狮08EV 239,900\\\",\\\"source\\\":{\\\"type\\\":\\\"WEB\\\",\\\"url\\\":\\\"https://a\\\"},\\\"confidence\\\":0.6}],\\\"gaps\\\":[]}\",\"webCount\":1}]";
        String merged = svc.merge(notes);
        assertTrue(merged.contains("0.9"), "KB 置信胜出");
        assertTrue(merged.contains("https://a"), "WEB 降为 alternatives 佐证");
        assertTrue(merged.contains("以知识库为准"), "冲突警告");
        assertTrue(!merged.contains("待核实"), "KB 在场不标待核实");
    }

    @Test
    void gaps_去重聚合() throws Exception {
        String notes = "[{\"agentId\":1,\"question\":\"q\",\"status\":\"DONE\",\"factsJson\":\"{\\\"facts\\\":[],\\\"gaps\\\":[\\\"残值数据\\\"]}\",\"webCount\":0},"
                + "{\"agentId\":2,\"question\":\"q\",\"status\":\"DONE\",\"factsJson\":\"{\\\"facts\\\":[],\\\"gaps\\\":[\\\"残值数据\\\",\\\"保值率\\\"]}\",\"webCount\":0}]";
        String merged = svc.merge(notes);
        assertTrue(merged.contains("残值数据"));
        assertTrue(merged.contains("保值率"));
        assertEquals(1, merged.split("残值数据").length - 1, "gaps 去重");
    }

    @Test
    void 数值回查_手册外数值_被标() throws Exception {
        DeepWriterService w = new DeepWriterService(null, new ObjectMapper(), null, null, null, null, null, null);
        String sheet = "{\"entries\":[{\"key\":\"海狮08EV起售价\",\"value\":\"239900\",\"confidence\":0.9}]}";
        String content = "海狮08EV 起售价 239,900 元,续航 610km,竞品卖 258000。";
        var unknown = w.verifyNumbers(content, sheet);
        // 610km 手册没有 → 应标;258000 手册没有 → 应标
        assertTrue(unknown.contains("610km") || unknown.contains("610"), "未收录数值应被标: " + unknown);
        assertTrue(unknown.contains("258000"), "未收录数值应被标: " + unknown);
    }

    @Test
    void 数值回查_手册内数值_不标() throws Exception {
        DeepWriterService w = new DeepWriterService(null, new ObjectMapper(), null, null, null, null, null, null);
        String sheet = "{\"entries\":[{\"key\":\"起售价\",\"value\":\"239900\",\"confidence\":0.9}]}";
        var unknown = w.verifyNumbers("起售价 239,900 元", sheet);
        assertTrue(unknown.isEmpty(), () -> "手册内数值不应被标: " + unknown);
    }

    // ==================== 09-25-fact-claim-merge 归并/交叉验证 ====================

    /** AC-01:同 URL 的措辞差异 claim 合并为一条。 */
    @Test
    void AC01_同URL近义claim_合并为一条() throws Exception {
        String notes = twoNotes(
                "{\"facts\":[{\"claim\":\"比亚迪第2000座高速闪充站已正式落成\",\"source\":{\"type\":\"WEB\",\"url\":\"http://www.stnn.cc/detail/6ab4ccc9158f681db4232fb1.html\",\"provider\":\"TAVILY\"},\"confidence\":0.4}],\"gaps\":[]}",
                "{\"facts\":[{\"claim\":\"比亚迪第2000座闪充高速站正式落成\",\"source\":{\"type\":\"WEB\",\"url\":\"http://www.stnn.cc/detail/6ab4ccc9158f681db4232fb1.html\",\"provider\":\"TAVILY\"},\"confidence\":0.4}],\"gaps\":[]}");
        JsonNode sheet = sheet(svc.merge(notes));
        assertEquals(1, sheet.path("entries").size(), "近义 claim 应合并为一条");
        assertEquals(1, sheet.path("entries").get(0).path("crossCount").asInt(), "同 URL 只算 1 个来源");
        assertFalse(sheet.toString().contains("MULTI"), "同 URL 不产生交叉");
    }

    /** AC-02:异 URL 近义 claim 合并 → MULTI 0.85、crossCount=2(真实样本)。 */
    @Test
    void AC02_异URL近义claim_合并为MULTI085() throws Exception {
        String notes = twoNotes(
                "{\"facts\":[{\"claim\":\"比亚迪第2000座高速闪充站已正式落成\",\"source\":{\"type\":\"WEB\",\"url\":\"http://www.stnn.cc/detail/6ab4ccc9158f681db4232fb1.html\",\"provider\":\"TAVILY\"},\"confidence\":0.4}],\"gaps\":[]}",
                "{\"facts\":[{\"claim\":\"比亚迪第 2000 座闪充高速站正式落成 - IT之家\",\"source\":{\"type\":\"WEB\",\"url\":\"https://www.ithome.com/1/006/791.htm\",\"provider\":\"TAVILY\"},\"confidence\":0.4}],\"gaps\":[]}");
        JsonNode sheet = sheet(svc.merge(notes));
        assertEquals(1, sheet.path("entries").size(), "异 URL 近义 claim 应合并");
        JsonNode e = sheet.path("entries").get(0);
        assertEquals("MULTI", e.path("sources").path("type").asText(), "多源交叉标 MULTI");
        assertEquals(0.85, e.path("confidence").asDouble(), 1e-9);
        assertEquals(2, e.path("crossCount").asInt());
        assertEquals(2, e.path("sourceCount").asInt());
        assertEquals(2, e.path("sourcesList").size(), "保留全部来源证据");
    }

    /** AC-03:数值冲突不得合并。 */
    @Test
    void AC03_数值冲突_不合并() throws Exception {
        String notes = twoNotes(
                "{\"facts\":[{\"claim\":\"比亚迪第2000座高速闪充站落成\",\"source\":{\"type\":\"WEB\",\"url\":\"https://a\"},\"confidence\":0.4}],\"gaps\":[]}",
                "{\"facts\":[{\"claim\":\"比亚迪第1500座高速闪充站落成\",\"source\":{\"type\":\"WEB\",\"url\":\"https://b\"},\"confidence\":0.4}],\"gaps\":[]}");
        JsonNode sheet = sheet(svc.merge(notes));
        assertEquals(2, sheet.path("entries").size(), "数值冲突必须保持两条");
    }

    /** AC-03 回归:规范化会丢弃小数点,「1.5万」与「15万」数值冲突不得因此误并。 */
    @Test
    void AC03_小数点致数值冲突_不合并() throws Exception {
        String notes = twoNotes(
                "{\"facts\":[{\"claim\":\"海狮08EV售价1.5万\",\"source\":{\"type\":\"WEB\",\"url\":\"https://a\"},\"confidence\":0.4}],\"gaps\":[]}",
                "{\"facts\":[{\"claim\":\"海狮08EV售价15万\",\"source\":{\"type\":\"WEB\",\"url\":\"https://b\"},\"confidence\":0.4}],\"gaps\":[]}");
        JsonNode sheet = sheet(svc.merge(notes));
        assertEquals(2, sheet.path("entries").size(), "小数点数值冲突必须保持两条");
    }

    /** AC-04:语义不同但共享部分文字不得合并。 */
    @Test
    void AC04_语义不同共享文字_不合并() throws Exception {
        String notes = twoNotes(
                "{\"facts\":[{\"claim\":\"海狮08EV起售价200000\",\"source\":{\"type\":\"WEB\",\"url\":\"https://a\"},\"confidence\":0.4}],\"gaps\":[]}",
                "{\"facts\":[{\"claim\":\"海狮08EV续航700km\",\"source\":{\"type\":\"WEB\",\"url\":\"https://b\"},\"confidence\":0.4}],\"gaps\":[]}");
        JsonNode sheet = sheet(svc.merge(notes));
        assertEquals(2, sheet.path("entries").size(), "语义不同不得合并");
    }

    /** AC-05:同一 URL 重复命中即使措辞不同也不计交叉(不产生 0.85)。 */
    @Test
    void AC05_同URL重复命中_不产生交叉置信() throws Exception {
        String notes = twoNotes(
                "{\"facts\":[{\"claim\":\"海狮08EV起售价239900\",\"source\":{\"type\":\"WEB\",\"url\":\"https://x/1\"},\"confidence\":0.4}],\"gaps\":[]}",
                "{\"facts\":[{\"claim\":\"海狮08EV起售价239,900元\",\"source\":{\"type\":\"WEB\",\"url\":\"https://x/1\"},\"confidence\":0.4}],\"gaps\":[]}");
        JsonNode sheet = sheet(svc.merge(notes));
        assertEquals(1, sheet.path("entries").size(), "近义 claim 合并");
        JsonNode e = sheet.path("entries").get(0);
        assertEquals(1, e.path("crossCount").asInt());
        assertEquals(0.4, e.path("confidence").asDouble(), 1e-9, "同 URL 不升为交叉置信");
        assertEquals("WEB", e.path("sources").path("type").asText());
    }

    /** AC-06:KB+WEB 合并后仍 KB 胜出、WEB 进 alternatives、不标待核实,sourcesList 保留 URL。 */
    @Test
    void AC06_KB_WEB合并_KB胜出且保留来源() throws Exception {
        String notes = twoNotes(
                "{\"facts\":[{\"claim\":\"海狮08EV 起售价 239,900 元\",\"source\":{\"type\":\"KB\",\"modelName\":\"海狮08EV\"},\"confidence\":0.9}],\"gaps\":[]}",
                "{\"facts\":[{\"claim\":\"海狮08EV 起售价 239900 元 - 汽车之家\",\"source\":{\"type\":\"WEB\",\"url\":\"https://auto.example/1\",\"provider\":\"TAVILY\"},\"confidence\":0.4}],\"gaps\":[]}");
        JsonNode sheet = sheet(svc.merge(notes));
        assertEquals(1, sheet.path("entries").size(), "KB+WEB 近义合并为一条");
        JsonNode e = sheet.path("entries").get(0);
        assertEquals("KB", e.path("sources").path("type").asText(), "KB 胜出");
        assertEquals(0.9, e.path("confidence").asDouble(), 1e-9);
        assertTrue(e.path("alternatives").toString().contains("https://auto.example/1"), "WEB 进 alternatives");
        assertFalse(sheet.toString().contains("待核实"), "KB 在场不标待核实");
        assertEquals(2, e.path("sourcesList").size(), "保留全部来源证据");
        boolean hasWebUrl = false;
        for (JsonNode s : e.path("sourcesList")) {
            if ("https://auto.example/1".equals(s.path("url").asText())) hasWebUrl = true;
        }
        assertTrue(hasWebUrl, "sourcesList 保留 WEB URL");
    }

    /** 数值签名:一侧有数字一侧没有 → 不合并。 */
    @Test
    void 一侧有数值一侧无数值_不合并() throws Exception {
        String notes = twoNotes(
                "{\"facts\":[{\"claim\":\"海狮08EV起售价239900\",\"source\":{\"type\":\"WEB\",\"url\":\"https://a\"},\"confidence\":0.4}],\"gaps\":[]}",
                "{\"facts\":[{\"claim\":\"海狮08EV起售价待公布\",\"source\":{\"type\":\"WEB\",\"url\":\"https://b\"},\"confidence\":0.4}],\"gaps\":[]}");
        JsonNode sheet = sheet(svc.merge(notes));
        assertEquals(2, sheet.path("entries").size(), "一侧有数值不得合并");
    }

    /** AC-09:归并后合并条目的数值仍被 verifyNumbers haystack 覆盖(不回归)。 */
    @Test
    void 归并后数值回查_不回归() throws Exception {
        String notes = twoNotes(
                "{\"facts\":[{\"claim\":\"比亚迪第2000座高速闪充站已正式落成\",\"source\":{\"type\":\"WEB\",\"url\":\"http://www.stnn.cc/x\",\"provider\":\"TAVILY\"},\"confidence\":0.4}],\"gaps\":[]}",
                "{\"facts\":[{\"claim\":\"比亚迪第 2000 座闪充高速站正式落成 - IT之家\",\"source\":{\"type\":\"WEB\",\"url\":\"https://www.ithome.com/y\",\"provider\":\"TAVILY\"},\"confidence\":0.4}],\"gaps\":[]}");
        String merged = svc.merge(notes);
        DeepWriterService w = new DeepWriterService(null, new ObjectMapper(), null, null, null, null, null, null);
        var unknown = w.verifyNumbers("比亚迪第 2000 座闪充高速站正式落成。", merged);
        assertTrue(unknown.isEmpty(), () -> "合并条目数值应仍被覆盖: " + unknown);
    }

    /** R1/AC-01(09-26):降级 fact 带 snippet → entry 透传 snippet 正文。 */
    @Test
    void 降级fact带snippet_entry透传snippet() throws Exception {
        String notes = notes("{\"facts\":[{\"claim\":\"比亚迪第2000座闪充站落成\",\"snippet\":\"比亚迪计划2026年底前建成2万座闪充站\",\"source\":{\"type\":\"WEB\",\"url\":\"https://a\"},\"confidence\":0.4}],\"gaps\":[]}");
        JsonNode sheet = sheet(svc.merge(notes));
        JsonNode e = sheet.path("entries").get(0);
        assertEquals("比亚迪计划2026年底前建成2万座闪充站", e.path("snippet").asText(), "snippet 应透传到手册条目");
    }

    /** R1/AC-01:无 snippet 的 fact → entry 不含该字段(旧契约保持)。 */
    @Test
    void 无snippet_entry不含该字段() throws Exception {
        String notes = notes("{\"facts\":[{\"claim\":\"海狮08EV起售价239900\",\"value\":\"239900\",\"source\":{\"type\":\"KB\"},\"confidence\":0.9}],\"gaps\":[]}");
        JsonNode sheet = sheet(svc.merge(notes));
        JsonNode e = sheet.path("entries").get(0);
        assertFalse(e.has("snippet"), "无 snippet 时不得出现该字段");
    }

    // ==================== 09-27-tavily-extract-kind-hypotheses R4:kind 分类 ====================

    /** 背景型问题产出的 fact → kind=background。 */
    @Test
    void kind_背景题产出fact_标background() throws Exception {
        String notes = "[{\"agentId\":1,\"question\":\"该车型的行业背景与战略目标是什么?\",\"status\":\"DONE\","
                + "\"factsJson\":\"{\\\"facts\\\":[{\\\"claim\\\":\\\"比亚迪计划建成2万座闪充站\\\",\\\"source\\\":{\\\"type\\\":\\\"WEB\\\",\\\"url\\\":\\\"https://a\\\"},\\\"confidence\\\":0.4}],\\\"gaps\\\":[]}\",\"webCount\":1}]";
        JsonNode e = sheet(svc.merge(notes)).path("entries").get(0);
        assertEquals("background", e.path("kind").asText(), "背景题产出的 fact 应标 background");
    }

    /** 参数型问题产出的 fact → kind=param。 */
    @Test
    void kind_参数题产出fact_标param() throws Exception {
        String notes = "[{\"agentId\":1,\"question\":\"海狮08的价格是多少?\",\"status\":\"DONE\","
                + "\"factsJson\":\"{\\\"facts\\\":[{\\\"claim\\\":\\\"海狮08EV起售价239900\\\",\\\"value\\\":\\\"239900\\\",\\\"source\\\":{\\\"type\\\":\\\"KB\\\"},\\\"confidence\\\":0.9}],\\\"gaps\\\":[]}\",\"webCount\":0}]";
        JsonNode e = sheet(svc.merge(notes)).path("entries").get(0);
        assertEquals("param", e.path("kind").asText(), "参数题产出的 fact 应标 param");
    }

    /** 无 question 信号(历史数据/缺字段)→ kind 兜底 param,不抛异常。 */
    @Test
    void kind_无问题信号_兜底param且不抛() throws Exception {
        // 旧历史 notes 无 question 字段(或为空)
        String notes = "[{\"agentId\":1,\"status\":\"DONE\","
                + "\"factsJson\":\"{\\\"facts\\\":[{\\\"claim\\\":\\\"某事实\\\",\\\"source\\\":{\\\"type\\\":\\\"KB\\\"},\\\"confidence\\\":0.9}],\\\"gaps\\\":[]}\",\"webCount\":0}]";
        JsonNode e = sheet(svc.merge(notes)).path("entries").get(0);
        assertEquals("param", e.path("kind").asText(), "无问题信号应兜底 param");
    }

    /** 近似归并后 kind 取簇首条(代表 fact)的问题类型。 */
    @Test
    void kind_归并后取簇首条问题类型() throws Exception {
        // 簇首为背景题,第二条为参数题近义 claim(同数值签名) → kind 应继承 background
        String notes = "["
                + "{\"agentId\":1,\"question\":\"该车型的行业背景与战略目标是什么?\",\"status\":\"DONE\","
                + "\"factsJson\":\"{\\\"facts\\\":[{\\\"claim\\\":\\\"比亚迪第2000座闪充站落成\\\",\\\"source\\\":{\\\"type\\\":\\\"WEB\\\",\\\"url\\\":\\\"https://a\\\",\\\"provider\\\":\\\"TAVILY\\\"},\\\"confidence\\\":0.4}],\\\"gaps\\\":[]}\",\"webCount\":1},"
                + "{\"agentId\":2,\"question\":\"落成情况如何?\",\"status\":\"DONE\","
                + "\"factsJson\":\"{\\\"facts\\\":[{\\\"claim\\\":\\\"比亚迪第 2000 座闪充站正式落成 - 新闻\\\",\\\"source\\\":{\\\"type\\\":\\\"WEB\\\",\\\"url\\\":\\\"https://b\\\",\\\"provider\\\":\\\"TAVILY\\\"},\\\"confidence\\\":0.4}],\\\"gaps\\\":[]}\",\"webCount\":1}]";
        JsonNode sheet = sheet(svc.merge(notes));
        assertEquals(1, sheet.path("entries").size(), "近义应合并");
        assertEquals("background", sheet.path("entries").get(0).path("kind").asText(),
                "归并后 kind 取簇首条(代表 fact)的问题类型");
    }

    // ==================== 10-05-tavily-endpoint-priority T-R4:双端点同 URL 只算 1 源 ====================

    /**
     * AC-T4:中转与官方命中同一 URL(provider 均为 TAVILY)→ distinctSources 按 url+modelName 去重只计 1 源,
     * MULTI 不因双端点触发。这是「不因多端点抬升独立来源计数」的落点。
     */
    @Test
    void AC_T4_双端点同URL_只计1源不触发MULTI() throws Exception {
        // 同一 URL 被 relay 与 official 两端点命中:provider 名固定 TAVILY,url 相同
        String notes = twoNotes(
                "{\"facts\":[{\"claim\":\"海狮08EV起售价239900\",\"source\":{\"type\":\"WEB\",\"url\":\"https://x/1\",\"provider\":\"TAVILY\"},\"confidence\":0.4}],\"gaps\":[]}",
                "{\"facts\":[{\"claim\":\"海狮08EV起售价239,900元\",\"source\":{\"type\":\"WEB\",\"url\":\"https://x/1\",\"provider\":\"TAVILY\"},\"confidence\":0.4}],\"gaps\":[]}");
        JsonNode sheet = sheet(svc.merge(notes));
        assertEquals(1, sheet.path("entries").size(), "近义 claim 合并");
        JsonNode e = sheet.path("entries").get(0);
        assertEquals(1, e.path("crossCount").asInt(), "同 URL 双端点只算 1 源");
        assertEquals(1, e.path("sourceCount").asInt());
        assertEquals(0.4, e.path("confidence").asDouble(), 1e-9, "不因双端点升为交叉置信");
        assertEquals("WEB", e.path("sources").path("type").asText());
        assertFalse(sheet.toString().contains("MULTI"), "AC-T4:双端点不得触发 MULTI");
    }

    private void assertNotNullEntry(JsonNode sheet, String fragment) {
        for (JsonNode e : sheet.path("entries")) {
            if (e.path("claim").asText("").contains(fragment)) return;
        }
        throw new AssertionError("未找到条目: " + fragment + " in " + sheet);
    }

    // ==================== 10-05-source-web-fusion F:本地信源融合 ====================

    /** 权威分档开启的 svc(默认构造器 = 不启用分档)。 */
    private final FactSheetService svcTiered = new FactSheetService(json, true);

    private static final String SRC_FACT =
            "{\"facts\":[{\"claim\":\"比亚迪10月销量30万辆\",\"value\":\"300000\",\"source\":{\"type\":\"SOURCE\","
            + "\"modelName\":\"乘联会\",\"sourceType\":\"user-source\",\"authorityTier\":\"official\","
            + "\"crossCounted\":true},\"confidence\":0.7}],\"gaps\":[]}";
    private static final String WEB_FACT =
            "{\"facts\":[{\"claim\":\"比亚迪10月销量30万辆\",\"value\":\"300000\",\"source\":{\"type\":\"WEB\","
            + "\"url\":\"https://auto.example/1\",\"provider\":\"TAVILY\"},\"confidence\":0.4}],\"gaps\":[]}";
    private static final String KB_FACT =
            "{\"facts\":[{\"claim\":\"比亚迪10月销量30万辆\",\"value\":\"300000\",\"source\":{\"type\":\"KB\","
            + "\"modelName\":\"比亚迪\"},\"confidence\":0.9}],\"gaps\":[]}";

    /** AC-F1:同 claim SOURCE + WEB → 本地信源胜出,WEB 降 alternatives + 警告「以本地信源为准」。 */
    @Test
    void AC_F1_本地信源胜外部WEB_降alternatives与警告() throws Exception {
        JsonNode sheet = sheet(svc.merge(twoNotes(SRC_FACT, WEB_FACT)));
        assertEquals(1, sheet.path("entries").size(), "同 claim 应合并为一条");
        JsonNode e = sheet.path("entries").get(0);
        assertEquals("SOURCE", e.path("sources").path("type").asText(), "本地信源胜出");
        assertEquals("user-source", e.path("sources").path("sourceType").asText());
        assertTrue(e.path("alternatives").toString().contains("https://auto.example/1"), "WEB 进 alternatives");
        assertTrue(sheet.toString().contains("以本地信源为准"), "本地优先警告");
        assertFalse(sheet.toString().contains("待核实"), "本地信源在场不标待核实");
    }

    /** AC-F7/KB 兜底:同 claim KB + SOURCE → KB 仍胜,不误伤知识库。 */
    @Test
    void KB仍胜SOURCE_不误伤() throws Exception {
        JsonNode sheet = sheet(svc.merge(twoNotes(KB_FACT, SRC_FACT)));
        JsonNode e = sheet.path("entries").get(0);
        assertEquals("KB", e.path("sources").path("type").asText(), "KB 优先于 SOURCE");
        assertEquals(0.9, e.path("confidence").asDouble(), 1e-9);
        assertFalse(sheet.toString().contains("以本地信源为准"), "KB 胜出不应写本地优先警告");
    }

    /** AC-F2:本地 SOURCE 与外部 WEB 命中同一 URL → 去重,sourceCount 不虚高。 */
    @Test
    void AC_F2_跨type同URL去重_sourceCount不虚高() throws Exception {
        String srcSameUrl =
                "{\"facts\":[{\"claim\":\"比亚迪第2000座闪充站落成\",\"source\":{\"type\":\"SOURCE\","
                + "\"url\":\"https://news.example/a\",\"modelName\":\"信源\",\"sourceType\":\"user-source\","
                + "\"crossCounted\":true},\"confidence\":0.7}],\"gaps\":[]}";
        String webSameUrl =
                "{\"facts\":[{\"claim\":\"比亚迪第2000座闪充站落成\",\"source\":{\"type\":\"WEB\","
                + "\"url\":\"https://news.example/a/\",\"provider\":\"TAVILY\"},\"confidence\":0.4}],\"gaps\":[]}";
        JsonNode sheet = sheet(svc.merge(twoNotes(srcSameUrl, webSameUrl)));
        assertEquals(1, sheet.path("entries").size(), "同 URL 近义 claim 合并");
        JsonNode e = sheet.path("entries").get(0);
        assertEquals(1, e.path("crossCount").asInt(), "跨 type 同 URL 只算 1 源");
        assertEquals(1, e.path("sourceCount").asInt());
        assertTrue(sheet.path("sourceMeta").path("dedupedSameUrl").asInt() >= 1, "去重计数可见");
    }

    /** AC-F3:本地 BYD 新闻(KB)+ 外部搜到同篇(同 URL)不触发 MULTI。 */
    @Test
    void AC_F3_本地BYD新闻与外部同篇_不触发MULTI() throws Exception {
        String kbNews =
                "{\"facts\":[{\"claim\":\"比亚迪第2000座闪充站落成\",\"source\":{\"type\":\"KB\","
                + "\"url\":\"https://news.example/byd\",\"modelName\":\"官方新闻\"},\"confidence\":0.9}],\"gaps\":[]}";
        String webSame =
                "{\"facts\":[{\"claim\":\"比亚迪第2000座闪充站落成\",\"source\":{\"type\":\"WEB\","
                + "\"url\":\"https://news.example/byd\",\"provider\":\"TAVILY\"},\"confidence\":0.4}],\"gaps\":[]}";
        JsonNode sheet = sheet(svc.merge(twoNotes(kbNews, webSame)));
        JsonNode e = sheet.path("entries").get(0);
        assertEquals(1, e.path("crossCount").asInt(), "同 URL 只算 1 源");
        assertFalse(sheet.toString().contains("MULTI"), "同源多通道不得触发 MULTI");
    }

    /** AC-F3:盖世 ranking(crossCounted=false)不计独立交叉,与乘联会不构成 MULTI。 */
    @Test
    void AC_F3_gasgooRanking不计独立交叉() throws Exception {
        String cpca =
                "{\"facts\":[{\"claim\":\"比亚迪10月销量30万辆\",\"source\":{\"type\":\"SOURCE\","
                + "\"modelName\":\"乘联会\",\"sourceType\":\"user-source\",\"crossCounted\":true},\"confidence\":0.7}],\"gaps\":[]}";
        String ranking =
                "{\"facts\":[{\"claim\":\"比亚迪10月销量30万辆\",\"source\":{\"type\":\"SOURCE\","
                + "\"url\":\"https://gasgoo.example/rank\",\"modelName\":\"盖世排行\","
                + "\"sourceType\":\"gasgoo-ranking\",\"crossCounted\":false},\"confidence\":0.7}],\"gaps\":[]}";
        JsonNode sheet = sheet(svc.merge(twoNotes(cpca, ranking)));
        JsonNode e = sheet.path("entries").get(0);
        assertEquals(1, e.path("crossCount").asInt(), "ranking 被剔除,只剩 1 源");
        assertFalse(sheet.toString().contains("MULTI"), "ranking 不与乘联会构成独立交叉");
    }

    /** AC-F3:盖世 announce(crossCounted=true)可与乘联会构成独立交叉(反例)。 */
    @Test
    void AC_F3_gasgooAnnounce可构成独立交叉() throws Exception {
        String cpca =
                "{\"facts\":[{\"claim\":\"比亚迪10月销量30万辆\",\"source\":{\"type\":\"SOURCE\","
                + "\"modelName\":\"乘联会\",\"sourceType\":\"user-source\",\"crossCounted\":true},\"confidence\":0.7}],\"gaps\":[]}";
        String announce =
                "{\"facts\":[{\"claim\":\"比亚迪10月销量30万辆\",\"source\":{\"type\":\"SOURCE\","
                + "\"url\":\"https://gasgoo.example/announce\",\"modelName\":\"盖世官宣\","
                + "\"sourceType\":\"gasgoo-announce\",\"crossCounted\":true},\"confidence\":0.7}],\"gaps\":[]}";
        JsonNode sheet = sheet(svc.merge(twoNotes(cpca, announce)));
        JsonNode e = sheet.path("entries").get(0);
        assertEquals(2, e.path("crossCount").asInt());
        assertEquals(0.85, e.path("confidence").asDouble(), 1e-9, "announce 与乘联会独立交叉 0.85");
        assertEquals("MULTI", e.path("sources").path("type").asText());
    }

    /** AC-F7:权威分档 official 0.9 / industry 0.7 / ugc 0.5(启用分档构造器)。 */
    @Test
    void AC_F7_权威分档_按档取置信() throws Exception {
        assertTierConfidence("official", 0.9);
        assertTierConfidence("industry", 0.7);
        assertTierConfidence("media", 0.5);
        assertTierConfidence("ugc", 0.5);
        assertTierConfidence(null, 0.7);   // 缺档回退保守档
    }

    private void assertTierConfidence(String tier, double expected) throws Exception {
        String tierField = tier == null ? "" : ",\"authorityTier\":\"" + tier + "\"";
        String facts = "{\"facts\":[{\"claim\":\"纯本地信源事实\",\"source\":{\"type\":\"SOURCE\","
                + "\"modelName\":\"信源\",\"sourceType\":\"user-source\",\"crossCounted\":true"
                + tierField + "},\"confidence\":0.7}],\"gaps\":[]}";
        JsonNode e = sheet(svcTiered.merge(notes(facts))).path("entries").get(0);
        assertEquals(expected, e.path("confidence").asDouble(), 1e-9, "tier=" + tier);
    }

    /** AC-F7/AC-F5:默认不启用分档 → 纯 SOURCE 统一保守档 0.7(零回归)。 */
    @Test
    void AC_F7_默认不分档_统一保守档07() throws Exception {
        String official =
                "{\"facts\":[{\"claim\":\"纯本地信源事实\",\"source\":{\"type\":\"SOURCE\","
                + "\"sourceType\":\"user-source\",\"authorityTier\":\"official\",\"crossCounted\":true},"
                + "\"confidence\":0.7}],\"gaps\":[]}";
        JsonNode e = sheet(svc.merge(notes(official))).path("entries").get(0);
        assertEquals(0.7, e.path("confidence").asDouble(), 1e-9, "默认不分档应走保守档 0.7");
        assertEquals("SOURCE", e.path("sources").path("type").asText());
    }

    /** AC-F5:未启用自建信源(无 SOURCE)时,既有 KB/WEB/MULTI 分支逐位等价。 */
    @Test
    void AC_F5_无SOURCE_零回归() throws Exception {
        // KB+WEB 冲突仍 KB 胜
        JsonNode s1 = sheet(svc.merge(twoNotes(
                "{\"facts\":[{\"claim\":\"海狮08起售价239900\",\"source\":{\"type\":\"KB\"},\"confidence\":0.9}],\"gaps\":[]}",
                "{\"facts\":[{\"claim\":\"海狮08起售价239900\",\"source\":{\"type\":\"WEB\",\"url\":\"https://a\"},\"confidence\":0.4}],\"gaps\":[]}")));
        assertEquals("KB", s1.path("entries").get(0).path("sources").path("type").asText());
        assertEquals(0.9, s1.path("entries").get(0).path("confidence").asDouble(), 1e-9);
        // 无任何本地/WEB 来源时 sourceMeta 增量字段不出现
        assertFalse(s1.has("sourceMeta"), "无来源时 sourceMeta 不应出现");
        // 纯 WEB 仍 0.4 + 待核实
        JsonNode s2 = sheet(svc.merge(notes(
                "{\"facts\":[{\"claim\":\"竞品价格\",\"source\":{\"type\":\"WEB\",\"url\":\"https://x\"},\"confidence\":0.6}],\"gaps\":[]}")));
        assertEquals(0.4, s2.path("entries").get(0).path("confidence").asDouble(), 1e-9);
    }

    /** AC-F4:融合可观测——本地/外部来源数与权威档计数可读。 */
    @Test
    void AC_F4_融合可观测_本地外部占比与档位计数() throws Exception {
        JsonNode sheet = sheet(svc.merge(notes(
                "{\"facts\":[{\"claim\":\"本地事实一\",\"source\":{\"type\":\"SOURCE\","
                + "\"modelName\":\"乘联会\",\"sourceType\":\"user-source\",\"authorityTier\":\"official\","
                + "\"crossCounted\":true},\"confidence\":0.7}],\"gaps\":[]}")));
        JsonNode meta = sheet.path("sourceMeta");
        assertEquals(1, meta.path("localSourceCount").asInt());
        assertEquals(0, meta.path("webSourceCount").asInt());
        assertEquals(1, meta.path("authorityTierCounts").path("official").asInt());
    }

    /**
     * Spring 装配回归(10-05-source-web-fusion):本仓无 {@code @SpringBootTest},多构造器
     * {@code @Service} 若漏 {@code @Autowired} 会导致应用启动失败但 {@code mvn test} 全绿
     * (先例 {@code FetchTransportWiringTest}/{@code SourceWiringTest})。此探针锁定
     * {@code FactSheetService} 生产构造器(ObjectMapper, DeepProperties)可被容器实例化。
     */
    @Test
    void FactSheetService可被Spring装配_多构造器须显式Autowired() {
        try (org.springframework.context.annotation.AnnotationConfigApplicationContext ctx =
                     new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            ctx.registerBean(com.fasterxml.jackson.databind.ObjectMapper.class);
            ctx.registerBean(com.sparkora.config.DeepProperties.class);
            ctx.register(FactSheetService.class);
            ctx.refresh();
            org.junit.jupiter.api.Assertions.assertNotNull(ctx.getBean(FactSheetService.class),
                    "FactSheetService 应可装配");
        }
    }

    // ==================== 10-09 M：生产链路 url / authorityTier 端到端 ====================

    /**
     * AC-M1：用**真实生产链路字段**触发 F-R3——Citation(带 url) → KnowledgeSearchTool 出 SOURCE(带 url)
     * → SubAgentRunner.rawFallback 产 factsJson → FactSheetService.merge 与外部 WEB 同 URL 去重。
     * 此前 Citation.url 恒空，该去重仅在构造输入下成立。
     */
    @Test
    void AC_M1_生产链路SOURCE_url触发跨源同URL去重() throws Exception {
        com.sparkora.car.service.CarRagService rag = org.mockito.Mockito.mock(
                com.sparkora.car.service.CarRagService.class);
        com.sparkora.car.service.CarRagService.Citation cite =
                new com.sparkora.car.service.CarRagService.Citation("NEWS", "信源", "NEWS_BODY", 0.8,
                "信源：工信部公示 新车公示", null, "user-source", "政策公示",
                "https://news.example/a", "official");
        org.mockito.Mockito.when(rag.retrieveForGeneration(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.sparkora.car.service.CarRagService.RagResult(
                        com.sparkora.car.service.CarRagService.RagStatus.OK, "ctx", 1, 0.8, "",
                        java.util.List.of(cite)));
        java.util.List<com.sparkora.deep.tool.SearchTool.SearchHit> hits =
                new com.sparkora.deep.tool.KnowledgeSearchTool(rag).search("q", 8);
        assertEquals("SOURCE", hits.get(0).type());
        assertEquals("https://news.example/a", hits.get(0).url(), "生产链路 SOURCE.url 必须非空");

        // rawFallback 是 SOURCE 元数据到达 FactSheet 的降级通路(字段原样透传)
        String srcFactsJson = SubAgentRunner.rawFallback(hits);
        String claim = hits.get(0).title();   // rawFallback 产出的 claim(=引用标题),外部 WEB 用同 claim 归簇
        String webSameUrl =
                "{\"facts\":[{\"claim\":\"" + claim + "\",\"source\":{\"type\":\"WEB\","
                + "\"url\":\"https://news.example/a/\",\"provider\":\"TAVILY\"},\"confidence\":0.4}],\"gaps\":[]}";
        JsonNode sheet = sheet(svc.merge(twoNotes(srcFactsJson, webSameUrl)));
        JsonNode e = sheet.path("entries").get(0);
        assertEquals(1, e.path("crossCount").asInt(), "跨 type 同 URL 只算 1 源(真实链路字段)");
        assertEquals(1, e.path("sourceCount").asInt());
        assertTrue(sheet.path("sourceMeta").path("dedupedSameUrl").asInt() >= 1, "去重计数可见");
    }

    /** AC-M2：生产链路 authorityTier 贯通后，启用分档按 official 取 0.9。 */
    @Test
    void AC_M2_生产链路authorityTier触发分档() throws Exception {
        com.sparkora.car.service.CarRagService rag = org.mockito.Mockito.mock(
                com.sparkora.car.service.CarRagService.class);
        com.sparkora.car.service.CarRagService.Citation cite =
                new com.sparkora.car.service.CarRagService.Citation("NEWS", "乘联会", "NEWS_BODY", 0.8,
                "信源：乘联会销量 销量 12000", null, "user-source", "销量数据",
                "https://news.example/b", "official");
        org.mockito.Mockito.when(rag.retrieveForGeneration(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.sparkora.car.service.CarRagService.RagResult(
                        com.sparkora.car.service.CarRagService.RagStatus.OK, "ctx", 1, 0.8, "",
                        java.util.List.of(cite)));
        java.util.List<com.sparkora.deep.tool.SearchTool.SearchHit> hits =
                new com.sparkora.deep.tool.KnowledgeSearchTool(rag).search("q", 8);
        assertEquals("official", hits.get(0).authorityTier(), "生产链路 SOURCE.authorityTier 必须非空");

        JsonNode e = sheet(svcTiered.merge(notes(SubAgentRunner.rawFallback(hits)))).path("entries").get(0);
        assertEquals(0.9, e.path("confidence").asDouble(), 1e-9, "official 分档须取到 0.9");
    }
}
