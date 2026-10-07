package com.sparkora.deep.search;

import com.sparkora.deep.tool.SearchTool;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * WEB 命中治理(09-25-brief-web-search R8/R9):协议校验 → URL 规范化 → 去重 → 截断 → 分配稳定 sourceId。
 *
 * <p>治理必须在子代理/LLM 之前完成:LLM 只做事实抽取,不负责来源合法性。
 * sourceId 规则:{@code W1,W2,…} 按本次输入顺序分配,一次研究输入内稳定;事实只能引用本次输入的 sourceId。
 *
 * <p>10-04-web-fanout-merge B 增量:
 * <ul>
 *   <li>{@link #merge} 跨 provider 合并(去重/见证累加/order 位次稳定排序/截断/**统一分配 sourceId**);</li>
 *   <li>{@link #applyQualityGate} SearXNG 结果质量门(域名黑白名单 + URL 类型 + 空内容)。</li>
 * </ul>
 * {@link #normalize} 保留给 FIRST_HIT 单源路径(逐位等价现状)。
 */
public final class WebResultNormalizer {

    private WebResultNormalizer() {
    }

    /**
     * 单条治理后的 WEB 命中:稳定 sourceId + 规范化 URL + provider(来源工具名)。
     *
     * <p>09-27-tavily-extract-kind-hypotheses R2 增量:{@code content}=正文片段(nullable,
     * 与 {@code snippet} 摘要语义严格区分——引用/预览仍用 snippet)。保留 5 参构造器兼容既有调用方。
     *
     * <p>10-04-web-fanout-merge B-R3 增量:{@code witnessCount}=同一 URL 被多个 <b>provider</b> 命中的次数
     * (首个 provider 计 1);{@code witnessEndpoints}=同一 provider 多个 endpoint 命中同一 URL 的次数。
     * 二者均<b>不</b>参与 sourceCount/confidence(仅可观测)。保留 6 参构造器兼容既有调用方(默认 1/1)。
     */
    public record WebHit(String sourceId, String title, String url, String snippet, String provider,
                         String content, int witnessCount, int witnessEndpoints) {

        /** 兼容构造器(6 参,content):witness 默认 1/1(单源语义)。 */
        public WebHit(String sourceId, String title, String url, String snippet, String provider, String content) {
            this(sourceId, title, url, snippet, provider, content, 1, 1);
        }

        /** 兼容构造器(5 参,content=null):既有调用方(router/测试)不受影响。 */
        public WebHit(String sourceId, String title, String url, String snippet, String provider) {
            this(sourceId, title, url, snippet, provider, null, 1, 1);
        }

        /** 转为带 sourceId/provider/content 的 SearchHit(type=WEB)。 */
        public SearchTool.SearchHit toSearchHit() {
            return new SearchTool.SearchHit("WEB", title, url, snippet, provider, null, 0, sourceId, provider, content);
        }
    }

    /**
     * 治理原始 WEB 命中列表(FIRST_HIT 单源路径)。
     *
     * @param raw        原始命中(可能 URL 非法/重复/provider 为空)
     * @param maxResults 上限(≤0 视为不限制)
     * @return 规范、去重、截断后并带 sourceId 的命中列表;无有效时返回空列表(不返回 null)
     */
    public static List<WebHit> normalize(List<SearchTool.SearchHit> raw, int maxResults) {
        List<WebHit> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        Set<String> seen = new LinkedHashSet<>();           // 规范化 URL → 已收录(去重)
        int limit = maxResults <= 0 ? Integer.MAX_VALUE : maxResults;
        for (SearchTool.SearchHit h : raw) {
            if (h == null) continue;
            String normalized = normalizeUrl(h.url());
            if (normalized == null) continue;              // 非 http/https / 非绝对 URL:丢弃
            if (seen.contains(normalized)) continue;       // 重复 URL 只保留一条
            seen.add(normalized);
            String provider = h.provider() != null && !h.provider().isBlank()
                    ? h.provider() : (h.modelName() == null ? "" : h.modelName());
            out.add(new WebHit("W" + (out.size() + 1), h.title(), normalized, h.snippet(), provider, h.content()));
            if (out.size() >= limit) break;
        }
        return out;
    }

    /**
     * 跨 provider 合并(10-04-web-fanout-merge B-R3)。
     *
     * <p>规则:
     * <ol>
     *   <li><b>跨源去重</b>:按 {@link #normalizeUrl} 去重,首次出现的 provider 胜出(order 靠前 = 优先级高);</li>
     *   <li><b>见证累加</b>:同一 URL 被多个 provider 命中 → {@code witnessCount++};
     *       同一 provider 再次命中(多 endpoint) → {@code witnessEndpoints++}。
     *       <b>不</b>参与 sourceCount/confidence(独立来源计数仍按 url+modelName);</li>
     *   <li><b>排序</b>:provider 在 {@code order} 中的位次升序(稳定),provider 内保持原 rank;</li>
     *   <li><b>截断</b>:合并排序后截到 {@code maxResults}(<b>不放大</b>);</li>
     *   <li><b>sourceId 统一分配</b>:对合并后列表重排 {@code W1..Wn}——若各 provider 各从 W1 起号,
     *       会让 {@code validateFacts} 的 URL/provider 严格比对误剔引用(正确性关键)。</li>
     * </ol>
     *
     * @param order        provider 优先级顺序(决定排序位次)
     * @param perProvider  按 provider 分组的已治理命中(保持插入序,provider 内为原 rank)
     * @param maxResults   合并后上限(≤0 视为不限制)
     * @return 合并去重后的命中列表(可能为空,不返回 null);sourceId 全局唯一
     */
    public static List<WebHit> merge(List<WebProvider> order, Map<WebProvider, List<WebHit>> perProvider,
                                     int maxResults) {
        List<WebHit> merged = new ArrayList<>();
        if (perProvider == null || perProvider.isEmpty()) return merged;
        // 按 order 位次遍历;order 未覆盖的 provider 追加在末尾(按枚举声明序,保证确定)
        List<WebProvider> visit = new ArrayList<>();
        if (order != null) {
            for (WebProvider p : order) if (perProvider.containsKey(p) && !visit.contains(p)) visit.add(p);
        }
        List<WebProvider> extras = new ArrayList<>();
        for (WebProvider p : perProvider.keySet()) if (!visit.contains(p)) extras.add(p);
        extras.sort(java.util.Comparator.comparingInt(Enum::ordinal));
        visit.addAll(extras);

        Map<String, Integer> indexByUrl = new LinkedHashMap<>();     // 规范化 URL → merged 下标
        for (WebProvider p : visit) {
            List<WebHit> hits = perProvider.get(p);
            if (hits == null) continue;
            for (WebHit h : hits) {
                if (h == null) continue;
                String url = normalizeUrl(h.url());
                if (url == null) continue;
                Integer idx = indexByUrl.get(url);
                if (idx == null) {
                    indexByUrl.put(url, merged.size());
                    merged.add(new WebHit(null, h.title(), url, h.snippet(), h.provider(), h.content(), 1, 1));
                } else {
                    WebHit prev = merged.get(idx);
                    String prevProvider = prev.provider() == null ? "" : prev.provider();
                    String curProvider = h.provider() == null ? "" : h.provider();
                    int wc = prev.witnessCount();
                    int we = prev.witnessEndpoints();
                    if (curProvider.equalsIgnoreCase(prevProvider)) we++;   // 同 provider 多 endpoint
                    else wc++;                                              // 多 provider 交叉见证
                    merged.set(idx, new WebHit(prev.sourceId(), prev.title(), prev.url(), prev.snippet(),
                            prev.provider(), prev.content(), wc, we));
                }
            }
        }
        int limit = maxResults <= 0 ? Integer.MAX_VALUE : maxResults;
        if (merged.size() > limit) merged = new ArrayList<>(merged.subList(0, limit));
        // sourceId 统一分配(W1..Wn 全局唯一,合并后重排)
        List<WebHit> out = new ArrayList<>(merged.size());
        for (int i = 0; i < merged.size(); i++) {
            WebHit h = merged.get(i);
            out.add(new WebHit("W" + (i + 1), h.title(), h.url(), h.snippet(), h.provider(), h.content(),
                    h.witnessCount(), h.witnessEndpoints()));
        }
        return out;
    }

    /**
     * SearXNG 结果质量门(10-04-web-fanout-merge B-R2a):只影响是否进合并池,不改变独立交叉计数。
     *
     * <p>过滤规则(命中白名单者直接放行):
     * <ul>
     *   <li>域名黑名单(host == domain 或 host 以 {@code .domain} 结尾)→ 丢弃;</li>
     *   <li>URL 类型非正文页:含 {@code /video/} 或 {@code link?url=} → 丢弃;</li>
     *   <li>空 title 且空 snippet/content → 丢弃;非法 URL → 丢弃。</li>
     * </ul>
     *
     * @param hits         已治理命中(单 provider)
     * @param denyDomains  黑名单域名(小写,可空)
     * @param allowDomains 白名单域名(小写,命中者跳过黑名单/URL 类型过滤,可空)
     * @return 过滤后的命中(可能为空,不返回 null)
     */
    public static List<WebHit> applyQualityGate(List<WebHit> hits, List<String> denyDomains,
                                                List<String> allowDomains) {
        List<WebHit> out = new ArrayList<>();
        if (hits == null) return out;
        for (WebHit h : hits) {
            if (h == null) continue;
            String url = h.url();
            String normalized = normalizeUrl(url);
            if (normalized == null) continue;
            String host = hostOf(normalized);
            boolean allowed = host != null && matchesDomain(host, allowDomains);
            if (!allowed) {
                if (host != null && matchesDomain(host, denyDomains)) continue;
                String lower = normalized.toLowerCase(Locale.ROOT);
                if (lower.contains("/video/") || lower.contains("link?url=")) continue;
                boolean blankTitle = h.title() == null || h.title().isBlank();
                boolean blankBody = (h.snippet() == null || h.snippet().isBlank())
                        && (h.content() == null || h.content().isBlank());
                if (blankTitle && blankBody) continue;
            }
            out.add(h);
        }
        return out;
    }

    /** host 是否命中域名清单(host == domain 或 host 以 .domain 结尾)。 */
    static boolean matchesDomain(String host, List<String> domains) {
        if (host == null || domains == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        for (String d : domains) {
            if (d == null || d.isBlank()) continue;
            String dom = d.toLowerCase(Locale.ROOT);
            if (h.equals(dom) || h.endsWith("." + dom)) return true;
        }
        return false;
    }

    /** 提取规范化 URL 的 host(小写);非法返回 null。 */
    static String hostOf(String url) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * URL 规范化:仅接受绝对 http/https URL;去 fragment、统一小写 scheme/host、保留 path/query。
     *
     * @return 规范化 URL;非法(空/非 http(s)/非绝对/解析失败)返回 null
     */
    public static String normalizeUrl(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || host.isBlank()) return null;      // 非绝对 URL(如 /path、example.com)
            String lower = scheme.toLowerCase(Locale.ROOT);
            if (!"http".equals(lower) && !"https".equals(lower)) return null;        // 协议校验:仅 http/https
            StringBuilder sb = new StringBuilder(lower).append("://").append(host.toLowerCase(Locale.ROOT));
            if (uri.getPort() >= 0) sb.append(':').append(uri.getPort());
            String path = uri.getRawPath();
            sb.append(path == null || path.isEmpty() ? "/" : path);
            if (uri.getRawQuery() != null) sb.append('?').append(uri.getRawQuery());
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
