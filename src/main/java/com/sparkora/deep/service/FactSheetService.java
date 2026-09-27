package com.sparkora.deep.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事实手册服务(S9 ④):合并子代理研究笔记 → 事实手册(正文数值唯一来源)。
 * 置信规则:KB 0.9;多源交叉(去重来源≥2) 0.85 并标 MULTI;单 WEB 0.4(进 warnings「待核实」);
 * 同 cluster 同时含 KB 与 WEB 时 KB 胜出(0.9),WEB 降 alternatives 并警告「以知识库为准」。
 *
 * <p>相似条目归并(09-25-fact-claim-merge):按 claim 精确匹配升级为
 * {@link ClaimSimilarity} 贪心聚类——「数值签名一致」为硬前提，措辞近似的不同来源 claim 归为一条，
 * 从而恢复多来源交叉验证；数值冲突/一侧无数值一律不合并。详见 docs/spec/brief-generation.md。
 */
@Slf4j
@Service
public class FactSheetService {

    private final ObjectMapper json;

    public FactSheetService(ObjectMapper json) {
        this.json = json;
    }

    /**
     * 合并研究笔记 → 事实手册 JSON 字符串。
     * @param notesJson 子代理笔记 JSON 数组 [{agentId,question,status,facts:{facts:[…],gaps:[…]}}]
     */
    public String merge(String notesJson) throws Exception {
        JsonNode notes = json.readTree(notesJson == null || notesJson.isBlank() ? "[]" : notesJson);
        // 展开为有序 fact 列表(保持出现顺序;代表 fact = 簇首条,"首条为准"与旧精确匹配一致)
        // R4(09-27-tavily-extract-kind-hypotheses):并行记录每条 fact 所属研究问题的 kind
        // (背景型问题 → background,其余/无问题信号 → param),聚类后取簇首条对应 kind 写入 entry。
        List<JsonNode> facts = new ArrayList<>();
        List<String> kinds = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        for (JsonNode note : notes) {
            JsonNode factsJson = note.path("factsJson").isMissingNode()
                    ? note.path("facts") : json.readTree(note.path("factsJson").asText("{}"));
            // kind 继承「产出该 fact 的研究问题类型」:问题为背景/来龙去脉型 → background,否则 param
            String kind = ClarifyService.isBackgroundQuestion(note.path("question").asText(""))
                    ? "background" : "param";
            for (JsonNode f : factsJson.path("facts")) {
                String claim = f.path("claim").asText("").trim();
                if (claim.isEmpty()) continue;
                facts.add(f);
                kinds.add(kind);
            }
            for (JsonNode g : factsJson.path("gaps")) {
                String g0 = g.asText("");
                if (!g0.isBlank() && !gaps.contains(g0)) gaps.add(g0);
            }
        }
        // 贪心簇:与已有簇的代表 fact 满足 sameClaim 则归入(近似 claim 合并),否则新开簇。
        // O(n²),n 为单 brief fact 条数(实践中数十条),可接受。
        List<List<JsonNode>> clusters = new ArrayList<>();
        List<String> clusterKinds = new ArrayList<>();   // 与 clusters 同索引:簇首条的 kind
        for (int fi = 0; fi < facts.size(); fi++) {
            JsonNode f = facts.get(fi);
            List<JsonNode> target = null;
            for (List<JsonNode> cluster : clusters) {
                JsonNode rep = cluster.get(0);
                if (ClaimSimilarity.sameClaim(
                        rep.path("claim").asText(""), rep.path("value").asText(""),
                        f.path("claim").asText(""), f.path("value").asText(""))) {
                    target = cluster;
                    break;
                }
            }
            if (target == null) {
                target = new ArrayList<>();
                clusters.add(target);
                clusterKinds.add(kinds.get(fi));
            }
            target.add(f);
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int clusterIdx = -1;
        for (List<JsonNode> list : clusters) {
            clusterIdx++;
            // 代表 fact 的 kind 决定整簇 kind(「首条为准」与旧精确匹配一致);缺失兜底 param
            String kind = clusterKinds.get(clusterIdx) == null || clusterKinds.get(clusterIdx).isBlank()
                    ? "param" : clusterKinds.get(clusterIdx);
            JsonNode first = list.get(0);
            String type = first.path("source").path("type").asText("KB");
            // 来源去重(沿用 sameSource 语义:url + modelName),crossCount = 去重来源数
            List<JsonNode> distinctSources = distinctSources(list);
            int sourceCount = distinctSources.size();
            double confidence;
            // R2 冲突裁决(2026-09-06):同 claim 同时含 KB 与 WEB 来源时 KB 胜出——
            // 不比较相似度/置信度(量纲不同不可比),按来源类型定优先级:
            // 本系统知识库(比亚迪同步清洗入库) > 外部 WEB(论坛/资讯,不可控)。
            // WEB 条目不删:降级为 alternatives 佐证留证据,warnings 提示「以知识库为准」。
            boolean hasKb = list.stream().anyMatch(f -> "KB".equals(f.path("source").path("type").asText()));
            boolean hasWeb = list.stream().anyMatch(f -> "WEB".equals(f.path("source").path("type").asText()));
            if (hasKb && hasWeb) {
                JsonNode kbFirst = list.stream()
                        .filter(f -> "KB".equals(f.path("source").path("type").asText()))
                        .findFirst().orElse(first);
                // WEB 来源 URL 去重(近似 claim 合并后同一 URL 可能多次出现)
                List<String> altUrls = new ArrayList<>();
                for (JsonNode s : distinctSources) {
                    String u = s.path("url").asText("");
                    if ("WEB".equals(s.path("type").asText()) && !u.isBlank() && !altUrls.contains(u)) {
                        altUrls.add(u);
                    }
                }
                confidence = kbFirst.path("confidence").asDouble(0.9);
                entries.add(entry(kbFirst.path("claim").asText(""),
                        kbFirst.path("value").asText(""),
                        kbFirst.path("source"), distinctSources, sourceCount, confidence,
                        altUrls.isEmpty() ? null : altUrls, firstSnippet(list), kind));
                if (!altUrls.isEmpty()) {
                    warnings.add("「" + truncate(kbFirst.path("claim").asText(""), 30)
                            + "」以知识库为准;外部来源(" + altUrls.size() + " 条)有异说,未采用");
                }
                continue;
            }
            if (sourceCount >= 2) {
                confidence = 0.85;   // 多源交叉(去重后不同来源,同类)
                type = "MULTI";
            } else if ("KB".equals(type)) {
                confidence = first.path("confidence").asDouble(0.9);
            } else {
                confidence = 0.4;
                warnings.add("「" + truncate(first.path("claim").asText(""), 30) + "」仅单一 WEB 源,待核实");
            }
            entries.add(entry(first.path("claim").asText(""), first.path("value").asText(""),
                    sourceNode(first.path("source"), type), distinctSources, sourceCount, confidence, null,
                    firstSnippet(list), kind));
        }
        Map<String, Object> sheet = new LinkedHashMap<>();
        sheet.put("entries", entries);
        sheet.put("gaps", gaps);
        sheet.put("warnings", warnings);
        return json.writeValueAsString(sheet);
    }

    /**
     * 组装条目。主字段(key/claim/value/sources/crossCount/confidence)保持旧契约；
     * sourcesList/sourceCount 为 09-25 增量字段(保留全部来源证据,前端旧逻辑不读也不报错)。
     * snippet 为 09-26 增量字段(R1 降级保真):仅当簇内首个非空 snippet 存在时写入,
     * 无则完全不出现该字段(旧契约与既有消费方零回归)。
     * kind 为 09-27 增量字段(R4):{@code param|background},继承产出该 fact 的研究问题类型,
     * 缺省兜底 {@code param}(历史/无问题关联数据);旧消费方不读不报错。
     */
    private static Map<String, Object> entry(String claim, String value, JsonNode source,
                                             List<JsonNode> distinctSources, int sourceCount,
                                             double confidence, List<String> alternatives, String snippet,
                                             String kind) {
        Map<String, Object> e = new LinkedHashMap<>();
        // key 沿用旧语义:代表 fact 的 claim(旧实现 byClaim 的 key 即 claim)
        e.put("key", claim);
        e.put("value", value);
        e.put("claim", claim);
        e.put("kind", kind == null || kind.isBlank() ? "param" : kind);
        e.put("sources", source);
        e.put("crossCount", sourceCount);
        e.put("confidence", confidence);
        e.put("sourcesList", distinctSources);
        e.put("sourceCount", sourceCount);
        if (alternatives != null && !alternatives.isEmpty()) e.put("alternatives", alternatives);
        if (snippet != null && !snippet.isBlank()) e.put("snippet", snippet);
        return e;
    }

    /** 簇内首个非空 snippet(R1 降级保真);均无则返回 null(不写字段)。 */
    private static String firstSnippet(List<JsonNode> facts) {
        for (JsonNode f : facts) {
            String s = f.path("snippet").asText("");
            if (!s.isBlank()) return s;
        }
        return null;
    }

    /**
     * 条目 sources 主字段:多源交叉时把 type 标为 {@code MULTI}(前端据此渲染「多源交叉」,
     * 旧实现仅赋值局部变量未落 JSON → 前端 MULTI 分支从未命中);其余情形原样保留代表来源。
     * 代表来源缺失/非对象时兜底造一个带 type 的对象,避免 deepCopy 强转异常。
     */
    private static JsonNode sourceNode(JsonNode source, String effectiveType) {
        if (!"MULTI".equals(effectiveType)) return source;
        if (source != null && source.isObject()) {
            com.fasterxml.jackson.databind.node.ObjectNode copy =
                    (com.fasterxml.jackson.databind.node.ObjectNode) source.deepCopy();
            copy.put("type", "MULTI");
            return copy;
        }
        com.fasterxml.jackson.databind.node.ObjectNode fallback =
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        fallback.put("type", "MULTI");
        return fallback;
    }

    /** 来源去重(保持出现顺序;同 url+modelName 视为同一来源,crossCount 不重复计)。 */
    private static List<JsonNode> distinctSources(List<JsonNode> facts) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode f : facts) {
            JsonNode s = f.path("source");
            boolean dup = out.stream().anyMatch(existing -> sameSource(existing, s));
            if (!dup) out.add(s);
        }
        return out;
    }

    /** 两个来源是否同一(沿用旧 sameSource 语义:url + modelName 粗判)。 */
    private static boolean sameSource(JsonNode a, JsonNode b) {
        String ka = a.path("url").asText("") + a.path("modelName").asText();
        String kb2 = b.path("url").asText("") + b.path("modelName").asText();
        return ka.equals(kb2);
    }

    private static String truncate(String s, int n) { return s.length() <= n ? s : s.substring(0, n) + "…"; }
}
