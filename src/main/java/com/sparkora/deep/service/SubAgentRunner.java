package com.sparkora.deep.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.ai.AiClient;
import com.sparkora.config.DeepProperties;
import com.sparkora.deep.tool.KnowledgeSearchTool;
import com.sparkora.deep.tool.SearchTool;
import com.sparkora.deep.tool.SearxngSearchTool;
import com.sparkora.deep.tool.TavilySearchTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 研究子代理执行器(S9 ③):单问题研究——查本地 KB + 可选 WEB(SEARXNG→Tavily 降级)→ LLM 汇总为研究笔记。
 * 笔记 JSON 容错:畸形 JSON 重试 1 次;仍失败降级为「工具直出原始条目」,不静默丢。
 */
@Slf4j
@Component
public class SubAgentRunner {

    private final AiClient aiClient;
    private final ObjectMapper json;
    private final KnowledgeSearchTool kbTool;
    private final SearxngSearchTool searxngTool;
    private final TavilySearchTool tavilyTool;
    private final com.sparkora.config.DeepProperties props;

    public SubAgentRunner(AiClient aiClient, ObjectMapper json,
                          KnowledgeSearchTool kbTool, SearxngSearchTool searxngTool, TavilySearchTool tavilyTool,
                          com.sparkora.config.DeepProperties props) {
        this.aiClient = aiClient;
        this.json = json;
        this.kbTool = kbTool;
        this.searxngTool = searxngTool;
        this.tavilyTool = tavilyTool;
        this.props = props;
    }

    /** 研究笔记(标准化产物)。factsJson 为 {facts:[…],gaps:[…]} 字符串。 */
    public record Note(String question, String status, String factsJson, int webCount) {}

    /**
     * 执行单个研究问题(锚点感知版,R1 2026-09-06)。
     * KB 检索:复合 query(锚点车型 + 问题)+ 锚点加权统一通道,保证「大唐EV 价格对比」类问题命中价块;
     * WEB:gap 驱动——仅当 KB 命中不足以覆盖(无 KB 命中,或 KB 全是车型域块而问题为对比/策略类)时定向补查,
     * 不再与 KB 平行全问题重搜(WEB 命中不得覆盖 KB 已回答部分)。
     * @param question     研究问题
     * @param toolsAllowed 允许的工具集(如 [KB, WEB])
     * @param webQuota     本问题 WEB 调用剩余额度
     * @param anchors      锚点车型 id(项目关联/主题识别;可空)
     * @param topic        项目主题(复合 query 语料;可空)
     */
    public Note research(String question, List<String> toolsAllowed, int webQuota, List<Long> anchors, String topic) {
        List<SearchTool.SearchHit> hits = new ArrayList<>();
        // 1) 本地 KB(锚点加权,受设置门控):复合语料 = 主题(含车型名) + 研究问题
        //    09-09-brief-gen-redesign R3:toolsAllowed 不含 KB(全局设置停用)时不装配 KB 工具
        if (toolsAllowed.contains("KB")) {
            String kbQuery = compositeQuery(topic, question);
            try {
                hits.addAll(kbTool.search(kbQuery, 8, anchors));
            } catch (Exception e) {
                log.warn("KB 工具调用失败 question={}: {}", question, e.getMessage());
            }
        }
        boolean kbHit = hits.stream().anyMatch(h -> "KB".equals(h.type()));
        // 2) WEB(SEARXNG→Tavily 降级;额度受控):gap 驱动——KB 已命中车型域权威块时不再全问题重搜,
        //    仅在 KB 无命中时补查(WEB 结果只补缺口,不覆盖 KB 结论;冲突裁决在 FactSheetService.merge)
        boolean webUsed = false;
        boolean kbAuthoritative = hits.stream().anyMatch(h ->
                "KB".equals(h.type()) && (h.title() != null && h.title().contains("MODEL_INFO")
                        || (h.snippet() != null && h.snippet().contains("价格区间"))));
        if (toolsAllowed.contains("WEB") && props.isSearchWebEnabled() && webQuota > 0 && !kbAuthoritative) {
            for (SearchTool webTool : List.of(searxngTool, tavilyTool)) {
                if (hits.stream().anyMatch(h -> "WEB".equals(h.type()))) break;
                if (!webTool.available()) continue;
                List<SearchTool.SearchHit> web = webTool.search(question, Math.min(5, webQuota));
                if (!web.isEmpty()) {
                    hits.addAll(web);
                    webUsed = true;
                    break;
                }
            }
        }
        // 3) LLM 汇总为结构化笔记(容错:非法 JSON 重试 1 次;仍失败走原始条目降级)
        try {
            // 09-09-brief-gen-redesign R3:双关(KB/WEB 均被全局设置停用)时明确告知无外部资料,
            // 要求 gaps 标注,不臆造
            boolean noExternalSources = toolsAllowed.isEmpty();
            String system = """
                    你是研究子代理。基于给定检索结果回答研究问题,产出标准化研究笔记。
                    只输出 JSON:
                    {"facts":[{"claim":"事实条目","value":"数值(如无可省略)","source":{"type":"KB|WEB","url":"","modelName":"","docId":0},"confidence":0.9}],
                     "gaps":["未能从检索结果回答的部分"]}
                    规则:数值必须直接来自检索结果原文,禁止推算;KB 来源置信 0.9,单一 WEB 源 0.6;检索不支持的表述不写。
                    """ + (noExternalSources ? """
                    本次未启用任何外部资料检索(知识库与外部搜索均被系统设置停用):不得编造事实,全部要点写入 gaps,
                    并在 gaps 中注明「未检索任何外部资料,数据未核实」。
                    """ : "");
            StringBuilder ctx = new StringBuilder("研究问题:").append(question).append("\n检索结果:\n");
            for (SearchTool.SearchHit h : hits) {
                ctx.append("- [").append(h.type()).append("] ");
                if (h.url() != null && !h.url().isBlank()) ctx.append(h.url()).append(" | ");
                ctx.append(h.title()).append(" : ").append(snippet(h.snippet())).append('\n');
            }
            if (hits.isEmpty()) ctx.append("(无检索结果,请基于空结果产出 gaps)\n");
            String factsJson = chat(system, ctx.toString());
            long webCount = countWeb(factsJson);
            return new Note(question, "DONE", factsJson, (int) Math.max(webCount, webUsed ? 1 : 0));
        } catch (Exception e) {
            log.warn("研究子代理 LLM 汇总失败,降级为原始条目 question={}: {}", question, e.getMessage());
            return new Note(question, "FALLBACK", rawFallback(hits), 0);
        }
    }

    /** 复合 KB 检索语料:主题(通常含车型名) + 研究问题,解决「纯问题如『价格对比』缺车型上下文、相似度必散」的检索短板。(R1) */
    private static String compositeQuery(String topic, String question) {
        String t = topic == null ? "" : topic.trim();
        String q = question == null ? "" : question.trim();
        if (t.isEmpty()) return q;
        if (q.isEmpty()) return t;
        if (t.contains(q) || q.contains(t)) return t.length() >= q.length() ? t : q;
        return t + ", " + q;
    }

    private String chat(String system, String user) throws Exception {
        AiClient.ChatResult cr = aiClient.chatJson(system, user, 2048);
        try {
            json.readTree(cr.content());
            return cr.content();
        } catch (Exception retry) {
            AiClient.ChatResult cr2 = aiClient.chatJson(system,
                    user + "\n注意:上次输出不是合法 JSON,请只输出一个 JSON 对象。", 2048);
            json.readTree(cr2.content());
            return cr2.content();
        }
    }

    /** 降级:检索命中直转原始条目(不经 LLM)。 */
    private static String rawFallback(List<SearchTool.SearchHit> hits) {
        StringBuilder raw = new StringBuilder("{\"facts\":[");
        for (SearchTool.SearchHit h : hits) {
            if (raw.length() > 12) raw.append(',');
            raw.append("{\"claim\":\"").append(esc(h.title())).append("\",\"source\":{\"type\":\"")
               .append(h.type()).append("\",\"url\":\"").append(h.url() == null ? "" : esc(h.url()))
               .append("\",\"modelName\":\"").append(esc(h.modelName() == null ? "" : h.modelName()))
               .append("\",\"docId\":").append(h.docId() == null ? 0 : h.docId())
               .append("},\"confidence\":").append("KB".equals(h.type()) ? "0.6" : "0.4").append("}");
        }
        raw.append("],\"gaps\":[\"研究汇总失败,以下为原始检索条目,请人工核对\"]}");
        return raw.toString();
    }

    private static long countWeb(String factsJson) {
        return factsJson.split("\"WEB\"").length - 1L;
    }

    private static String snippet(String s) { return s == null ? "" : s.length() > 200 ? s.substring(0, 200) : s; }
    private static String esc(String s) { return s == null ? "" : s.replace("\"", "'").replace("\n", " "); }
}