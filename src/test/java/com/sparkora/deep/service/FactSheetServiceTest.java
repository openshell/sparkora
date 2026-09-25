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
        DeepWriterService w = new DeepWriterService(null, new ObjectMapper(), null, null, null, null);
        String sheet = "{\"entries\":[{\"key\":\"海狮08EV起售价\",\"value\":\"239900\",\"confidence\":0.9}]}";
        String content = "海狮08EV 起售价 239,900 元,续航 610km,竞品卖 258000。";
        var unknown = w.verifyNumbers(content, sheet);
        // 610km 手册没有 → 应标;258000 手册没有 → 应标
        assertTrue(unknown.contains("610km") || unknown.contains("610"), "未收录数值应被标: " + unknown);
        assertTrue(unknown.contains("258000"), "未收录数值应被标: " + unknown);
    }

    @Test
    void 数值回查_手册内数值_不标() throws Exception {
        DeepWriterService w = new DeepWriterService(null, new ObjectMapper(), null, null, null, null);
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
        DeepWriterService w = new DeepWriterService(null, new ObjectMapper(), null, null, null, null);
        var unknown = w.verifyNumbers("比亚迪第 2000 座闪充高速站正式落成。", merged);
        assertTrue(unknown.isEmpty(), () -> "合并条目数值应仍被覆盖: " + unknown);
    }

    private void assertNotNullEntry(JsonNode sheet, String fragment) {
        for (JsonNode e : sheet.path("entries")) {
            if (e.path("claim").asText("").contains(fragment)) return;
        }
        throw new AssertionError("未找到条目: " + fragment + " in " + sheet);
    }
}
