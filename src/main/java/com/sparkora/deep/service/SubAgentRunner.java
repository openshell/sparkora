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
     * 执行单个研究问题。
     * @param question     研究问题
     * @param toolsAllowed 允许的工具集(如 [KB, WEB])
     * @param webQuota     本问题 WEB 调用剩余额度
     */
    public Note research(String question, List<String> toolsAllowed, int webQuota) {
        List<SearchTool.SearchHit> hits = new ArrayList<>();
        // 1) 本地 KB(必用)
        try {
            hits.addAll(kbTool.search(question, 8));
        } catch (Exception e) {
            log.warn("KB 工具调用失败 question={}: {}", question, e.getMessage());
        }
        // 2) WEB(SEARXNG→Tavily 降级;额度受控)
        boolean webUsed = false;
        if (toolsAllowed.contains("WEB") && props.isSearchWebEnabled() && webQuota > 0) {
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
            String system = """
                    你是研究子代理。基于给定检索结果回答研究问题,产出标准化研究笔记。
                    只输出 JSON:
                    {"facts":[{"claim":"事实条目","value":"数值(如无可省略)","source":{"type":"KB|WEB","url":"","modelName":"","docId":0},"confidence":0.9}],
                     "gaps":["未能从检索结果回答的部分"]}
                    规则:数值必须直接来自检索结果原文,禁止推算;KB 来源置信 0.9,单一 WEB 源 0.6;检索不支持的表述不写。
                    """;
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