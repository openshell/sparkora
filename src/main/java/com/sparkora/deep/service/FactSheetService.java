package com.sparkora.deep.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.config.DeepProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事实手册服务(S9 ④):合并子代理研究笔记 → 事实手册(正文数值唯一来源)。
 * 置信规则:KB 0.9;本地自建信源(SOURCE)按权威档(默认不启用分档时保守档 0.7);多源交叉(去重来源≥2) 0.85 并标 MULTI;
 * 单 WEB 0.4(进 warnings「待核实」);同 cluster 同时含 KB 与 WEB 时 KB 胜出(0.9),WEB 降 alternatives 并警告「以知识库为准」。
 *
 * <p>10-05-source-web-fusion F:本地信源(SOURCE)与外部 WEB 融合——同 claim 本地胜外部(WEB 降 alternatives + 警告)、
 * 跨 type 同 URL 去重(sourceCount 不虚高)、SOURCE 权威分档、`crossCounted=false` 的来源不计独立交叉。
 *
 * <p>相似条目归并(09-25-fact-claim-merge):按 claim 精确匹配升级为
 * {@link ClaimSimilarity} 贪心聚类——「数值签名一致」为硬前提，措辞近似的不同来源 claim 归为一条，
 * 从而恢复多来源交叉验证；数值冲突/一侧无数值一律不合并。详见 docs/spec/brief-generation.md。
 */
@Slf4j
@Service
public class FactSheetService {

    private final ObjectMapper json;
    /** 是否启用信源权威分档(10-05 F-R4):默认 false = 全部 SOURCE 走单一保守档 0.7(零回归)。 */
    private final boolean sourceAuthorityEnabled;

    /** 生产构造器(Spring 装配;权威分档读 {@link DeepProperties},默认关闭=零回归)。 */
    @org.springframework.beans.factory.annotation.Autowired
    public FactSheetService(ObjectMapper json, DeepProperties deepProps) {
        this(json, deepProps != null && deepProps.isSourceAuthorityEnabled());
    }

    /** 兼容构造器(测试/旧调用方:默认不启用分档)。 */
    public FactSheetService(ObjectMapper json) {
        this(json, false);
    }

    /**
     * 可测构造器:允许显式开启权威分档。
     * @param sourceAuthorityEnabled 是否按 {@code authorityTier} 取置信(true 时官方 0.9/行业 0.7/媒体·自媒体 0.5;缺档 0.7)
     */
    public FactSheetService(ObjectMapper json, boolean sourceAuthorityEnabled) {
        this.json = json;
        this.sourceAuthorityEnabled = sourceAuthorityEnabled;
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
            String kind = ResearchPlannerService.isBackgroundQuestion(note.path("question").asText(""))
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
        int dedupedSameUrlTotal = 0;   // F-R5:跨源同 URL 去重累计(仅计被丢弃的重复 URL)
        int clusterIdx = -1;
        for (List<JsonNode> list : clusters) {
            clusterIdx++;
            // 代表 fact 的 kind 决定整簇 kind(「首条为准」与旧精确匹配一致);缺失兜底 param
            String kind = clusterKinds.get(clusterIdx) == null || clusterKinds.get(clusterIdx).isBlank()
                    ? "param" : clusterKinds.get(clusterIdx);
            JsonNode first = list.get(0);
            String type = first.path("source").path("type").asText("KB");
            // 来源去重(沿用 sameSource 语义:url + modelName + 跨 type 同 URL 合并 + 剔除 crossCounted=false)
            DistinctResult dr = distinctSources(list);
            List<JsonNode> distinctSources = dr.sources();
            int sourceCount = distinctSources.size();
            dedupedSameUrlTotal += dr.dedupedSameUrl();
            double confidence;
            // R2 冲突裁决(2026-09-06):同 claim 同时含 KB 与 WEB 来源时 KB 胜出——
            // 不比较相似度/置信度(量纲不同不可比),按来源类型定优先级:
            // 本系统知识库(比亚迪同步清洗入库) > 外部 WEB(论坛/资讯,不可控)。
            // WEB 条目不删:降级为 alternatives 佐证留证据,warnings 提示「以知识库为准」。
            // 10-05 F-R2:本地自建信源(SOURCE)优先级介于 KB 与 WEB 之间——KB 仍胜 SOURCE;
            // SOURCE 胜外部 WEB(本地可控全文 > 外部摘要级),WEB 降 alternatives + 警告。
            boolean hasKb = list.stream().anyMatch(f -> "KB".equals(f.path("source").path("type").asText()));
            boolean hasSource = list.stream().anyMatch(f -> isSourceType(f.path("source")));
            boolean hasWeb = list.stream().anyMatch(f -> "WEB".equals(f.path("source").path("type").asText()));
            if (hasKb && hasWeb) {
                JsonNode kbFirst = list.stream()
                        .filter(f -> "KB".equals(f.path("source").path("type").asText()))
                        .findFirst().orElse(first);
                // WEB 来源 URL 去重(近似 claim 合并后同一 URL 可能多次出现)
                List<String> altUrls = webAltUrls(distinctSources);
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
            if (hasSource && hasWeb) {
                // F-R2:本地信源优先——取首个 SOURCE 为代表,WEB 降 alternatives 佐证 + 警告
                JsonNode srcFirst = list.stream().filter(f -> isSourceType(f.path("source"))).findFirst().orElse(first);
                List<String> altUrls = webAltUrls(distinctSources);
                confidence = sourceConfidence(srcFirst.path("source"));
                entries.add(entry(srcFirst.path("claim").asText(""),
                        srcFirst.path("value").asText(""),
                        srcFirst.path("source"), distinctSources, sourceCount, confidence,
                        altUrls.isEmpty() ? null : altUrls, firstSnippet(list), kind));
                if (!altUrls.isEmpty()) {
                    warnings.add("「" + truncate(srcFirst.path("claim").asText(""), 30)
                            + "」以本地信源为准;外部来源(" + altUrls.size() + " 条)有异说,未采用");
                }
                continue;
            }
            if (hasKb && hasSource) {
                // F-R2:KB 仍胜 SOURCE(通用知识库为清洗权威),SOURCE 作为来源之一保留(不删除)。
                // 不比较相似度/置信度,按类型定优先级;无 WEB 故不写 alternatives/warnings。
                JsonNode kbFirst = list.stream()
                        .filter(f -> "KB".equals(f.path("source").path("type").asText()))
                        .findFirst().orElse(first);
                confidence = kbFirst.path("confidence").asDouble(0.9);
                entries.add(entry(kbFirst.path("claim").asText(""),
                        kbFirst.path("value").asText(""),
                        kbFirst.path("source"), distinctSources, sourceCount, confidence, null,
                        firstSnippet(list), kind));
                continue;
            }
            if (sourceCount >= 2) {
                confidence = 0.85;   // 多源交叉(去重后不同来源,同类)
                type = "MULTI";
            } else if ("KB".equals(type)) {
                confidence = first.path("confidence").asDouble(0.9);
            } else if (isSourceType(first.path("source"))) {
                // F-R4:纯 SOURCE——按权威档取置信(默认不启用分档时统一保守档 0.7)
                confidence = sourceConfidence(first.path("source"));
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
        // F-R5 融合可观测(增量字段,旧前端不读不报错):本地/外部来源占比 + 同 URL 去重数 + 各档置信计数。
        Map<String, Object> sourceMeta = sourceMeta(entries, dedupedSameUrlTotal);
        if (!sourceMeta.isEmpty()) sheet.put("sourceMeta", sourceMeta);
        return json.writeValueAsString(sheet);
    }

    /** 本地自建信源事实类型判定(SOURCE;兼容大小写)。 */
    private static boolean isSourceType(JsonNode source) {
        return "SOURCE".equalsIgnoreCase(source.path("type").asText(""));
    }

    /** 收集去重来源中的 WEB URL(保序、去重;供 alternatives)。 */
    private static List<String> webAltUrls(List<JsonNode> distinctSources) {
        List<String> altUrls = new ArrayList<>();
        for (JsonNode s : distinctSources) {
            String u = s.path("url").asText("");
            if ("WEB".equals(s.path("type").asText()) && !u.isBlank() && !altUrls.contains(u)) {
                altUrls.add(u);
            }
        }
        return altUrls;
    }

    /**
     * F-R4 权威分档:启用分档时按 {@code authorityTier} 取置信
     * (official 0.9 / industry 0.7 / media 0.5 / ugc 0.5;缺档回退 0.7);
     * 默认不启用分档时全部走单一保守档 0.7(零回归)。
     */
    private double sourceConfidence(JsonNode source) {
        if (!sourceAuthorityEnabled) return 0.7;
        String tier = source.path("authorityTier").asText("").trim().toLowerCase(java.util.Locale.ROOT);
        return switch (tier) {
            case "official" -> 0.9;
            case "industry" -> 0.7;
            case "media", "ugc" -> 0.5;
            default -> 0.7;
        };
    }

    /**
     * F-R5 融合可观测(确定性、不调 LLM):统计本地/外部来源数、跨源同 URL 去重数、各权威档计数。
     * <b>零回归</b>:无本地自建信源(SOURCE)时返回空 Map,完全不写该字段——未启用自建信源的部署
     * {@code fact_sheet} 与现有行为逐位等价。
     *
     * @param dedupedSameUrlTotal 跨源同 URL 去重累计(由 {@link #distinctSources} 统计,仅计被丢弃的重复 URL)
     */
    private static Map<String, Object> sourceMeta(List<Map<String, Object>> entries, int dedupedSameUrlTotal) {
        int local = 0;
        int web = 0;
        int official = 0;
        int industry = 0;
        int media = 0;
        int ugc = 0;
        for (Map<String, Object> e : entries) {
            @SuppressWarnings("unchecked")
            List<JsonNode> sl = (List<JsonNode>) e.get("sourcesList");
            if (sl == null) continue;
            for (JsonNode s : sl) {
                String t = s.path("type").asText("");
                if ("SOURCE".equalsIgnoreCase(t)) {
                    local++;
                    String tier = s.path("authorityTier").asText("").trim().toLowerCase(java.util.Locale.ROOT);
                    switch (tier) {
                        case "official" -> official++;
                        case "industry" -> industry++;
                        case "media" -> media++;
                        case "ugc" -> ugc++;
                        default -> { /* 缺档不计入分档统计 */ }
                    }
                } else if ("WEB".equals(t)) {
                    web++;
                }
            }
        }
        if (local == 0) return Map.of();   // 零回归:无本地信源不新增字段
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("localSourceCount", local);
        m.put("webSourceCount", web);
        m.put("dedupedSameUrl", dedupedSameUrlTotal);
        Map<String, Object> tiers = new LinkedHashMap<>();
        tiers.put("official", official);
        tiers.put("industry", industry);
        tiers.put("media", media);
        tiers.put("ugc", ugc);
        m.put("authorityTierCounts", tiers);
        return m;
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

    /**
     * 来源去重结果:去重后的来源列表 + 被丢弃的跨源同规范化 URL 重复数(仅观测)。
     */
    private record DistinctResult(List<JsonNode> sources, int dedupedSameUrl) {}

    /**
     * 来源去重(保持出现顺序;crossCount 不重复计)。三个语义:
     * <ol>
     *   <li>同 url + modelName 视为同一来源(旧 sameSource 语义,零回归);</li>
     *   <li>10-05 F-R3:同一<b>规范化 URL</b>跨 type 视为同一来源——本地信源(SOURCE)与外部 WEB 命中
     *       同一篇(如 BYD 官网/工信部原文)只保留首次出现的一条,避免 sourceCount 虚高、LLM 重复注入;</li>
     *   <li>10-05 F-R8:剔除 {@code crossCounted=false} 的来源(如盖世排行页 gasgoo-ranking,来源基础
     *       可能派生自乘联会),不计入独立交叉。</li>
     * </ol>
     */
    private static DistinctResult distinctSources(List<JsonNode> facts) {
        List<JsonNode> out = new ArrayList<>();
        java.util.Set<String> seenNormalizedUrls = new java.util.HashSet<>();
        int dedupedSameUrl = 0;
        for (JsonNode f : facts) {
            JsonNode s = f.path("source");
            // F-R8:不可计独立交叉的来源直接剔除(不参与 sourceCount)
            if (s.isObject() && s.path("crossCounted").isBoolean() && !s.path("crossCounted").asBoolean()) {
                continue;
            }
            boolean dup = out.stream().anyMatch(existing -> sameSource(existing, s));
            if (dup) continue;
            // F-R3:跨 type 同规范化 URL 合并(首次出现的来源胜出,保序);被丢弃者计入去重观测
            String norm = dedupUrlKey(s.path("url").asText(""));
            if (norm != null && !seenNormalizedUrls.add(norm)) {
                dedupedSameUrl++;
                continue;
            }
            out.add(s);
        }
        return new DistinctResult(out, dedupedSameUrl);
    }

    /**
     * 跨源同 URL 去重键:复用 {@link com.sparkora.deep.search.WebResultNormalizer#normalizeUrl} 后
     * 归一末尾斜杠(仅去重比较用,<b>不改共享 {@code normalizeUrl}</b>)——本地信源与外部搜索对同一篇
     * 常给出 有/无 末尾斜杠 的等价 URL,应视为同一来源。
     */
    private static String dedupUrlKey(String url) {
        String norm = com.sparkora.deep.search.WebResultNormalizer.normalizeUrl(url);
        if (norm == null) return null;
        while (norm.endsWith("/") && norm.length() > "https://x/".length()) {
            norm = norm.substring(0, norm.length() - 1);
        }
        return norm;
    }

    /** 两个来源是否同一(沿用旧 sameSource 语义:url + modelName 粗判)。 */
    private static boolean sameSource(JsonNode a, JsonNode b) {
        String ka = a.path("url").asText("") + a.path("modelName").asText();
        String kb2 = b.path("url").asText("") + b.path("modelName").asText();
        return ka.equals(kb2);
    }

    private static String truncate(String s, int n) { return s.length() <= n ? s : s.substring(0, n) + "…"; }
}
