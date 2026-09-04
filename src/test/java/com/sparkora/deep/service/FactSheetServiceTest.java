package com.sparkora.deep.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FactSheetService 合并/置信/冲突单测(纯函数级,不连库)。
 */
class FactSheetServiceTest {

    private final FactSheetService svc = new FactSheetService(new ObjectMapper());

    private String notes(String factsJson) {
        return "[{\"agentId\":1,\"question\":\"q\",\"status\":\"DONE\",\"factsJson\":\"" +
                factsJson.replace("\"", "\\\"") + "\",\"webCount\":0}]";
    }

    @Test
    void 单KB条目_置信09_无警告() throws Exception {
        String notes = notes("{\"facts\":[{\"claim\":\"海狮08EV起售价239900\",\"value\":\"239900\",\"source\":{\"type\":\"KB\",\"docId\":1},\"confidence\":0.9}],\"gaps\":[]}");
        String sheet = svc.merge(notes);
        assertTrue(sheet.contains("239900"));
        assertTrue(sheet.contains("0.9"));
        assertTrue(!sheet.contains("待核实"));
    }

    @Test
    void 单WEB条目_置信04_进warnings() throws Exception {
        String notes = notes("{\"facts\":[{\"claim\":\"竞品ModelY起售价\",\"source\":{\"type\":\"WEB\",\"url\":\"https://x\"},\"confidence\":0.6}],\"gaps\":[]}");
        String sheet = svc.merge(notes);
        assertTrue(sheet.contains("待核实"));
    }

    @Test
    void 同claim两源_交叉置信085_无警告() throws Exception {
        String notes = "["
                + "{\"agentId\":1,\"question\":\"q\",\"status\":\"DONE\",\"factsJson\":\"{\\\"facts\\\":[{\\\"claim\\\":\\\"海狮08EV 239,900\\\",\\\"source\\\":{\\\"type\\\":\\\"KB\\\"},\\\"confidence\\\":0.9}],\\\"gaps\\\":[]}\",\"webCount\":0},"
                + "{\"agentId\":2,\"question\":\"q\",\"status\":\"DONE\",\"factsJson\":\"{\\\"facts\\\":[{\\\"claim\\\":\\\"海狮08EV 239,900\\\",\\\"source\\\":{\\\"type\\\":\\\"WEB\\\",\\\"url\\\":\\\"https://a\\\"},\\\"confidence\\\":0.6}],\\\"gaps\\\":[]}\",\"webCount\":1}]";
        String sheet = svc.merge(notes);
        assertTrue(sheet.contains("0.85"));
        assertTrue(!sheet.contains("待核实"));
    }

    @Test
    void gaps_去重聚合() throws Exception {
        String notes = "[{\"agentId\":1,\"question\":\"q\",\"status\":\"DONE\",\"factsJson\":\"{\\\"facts\\\":[],\\\"gaps\\\":[\\\"残值数据\\\"]}\",\"webCount\":0},"
                + "{\"agentId\":2,\"question\":\"q\",\"status\":\"DONE\",\"factsJson\":\"{\\\"facts\\\":[],\\\"gaps\\\":[\\\"残值数据\\\",\\\"保值率\\\"]}\",\"webCount\":0}]";
        String sheet = svc.merge(notes);
        assertTrue(sheet.contains("残值数据"));
        assertTrue(sheet.contains("保值率"));
        assertEquals(1, sheet.split("残值数据").length - 1, "gaps 去重");
    }

    @Test
    void 数值回查_手册外数值_被标() throws Exception {
        DeepWriterService w = new DeepWriterService(null, new ObjectMapper(), null, null);
        String sheet = "{\"entries\":[{\"key\":\"海狮08EV起售价\",\"value\":\"239900\",\"confidence\":0.9}]}";
        String content = "海狮08EV 起售价 239,900 元,续航 610km,竞品卖 258000。";
        var unknown = w.verifyNumbers(content, sheet);
        // 610km 手册没有 → 应标;258000 手册没有 → 应标
        assertTrue(unknown.contains("610km") || unknown.contains("610"), "未收录数值应被标: " + unknown);
        assertTrue(unknown.contains("258000"), "未收录数值应被标: " + unknown);
    }

    @Test
    void 数值回查_手册内数值_不标() throws Exception {
        DeepWriterService w = new DeepWriterService(null, new ObjectMapper(), null, null);
        String sheet = "{\"entries\":[{\"key\":\"起售价\",\"value\":\"239900\",\"confidence\":0.9}]}";
        var unknown = w.verifyNumbers("起售价 239,900 元", sheet);
        assertTrue(unknown.isEmpty(), () -> "手册内数值不应被标: " + unknown);
    }
}