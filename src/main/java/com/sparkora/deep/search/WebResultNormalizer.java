package com.sparkora.deep.search;

import com.sparkora.deep.tool.SearchTool;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * WEB 命中治理(09-25-brief-web-search R8/R9):协议校验 → URL 规范化 → 去重 → 截断 → 分配稳定 sourceId。
 *
 * <p>治理必须在子代理/LLM 之前完成:LLM 只做事实抽取,不负责来源合法性。
 * sourceId 规则:{@code W1,W2,…} 按本次输入顺序分配,一次研究输入内稳定;事实只能引用本次输入的 sourceId。
 */
public final class WebResultNormalizer {

    private WebResultNormalizer() {
    }

    /**
     * 单条治理后的 WEB 命中:稳定 sourceId + 规范化 URL + provider(来源工具名)。
     *
     * <p>09-27-tavily-extract-kind-hypotheses R2 增量:{@code content}=正文片段(nullable,
     * 与 {@code snippet} 摘要语义严格区分——引用/预览仍用 snippet)。保留 5 参构造器兼容既有调用方。
     */
    public record WebHit(String sourceId, String title, String url, String snippet, String provider,
                         String content) {

        /** 兼容构造器(5 参,content=null):既有调用方(router/测试)不受影响。 */
        public WebHit(String sourceId, String title, String url, String snippet, String provider) {
            this(sourceId, title, url, snippet, provider, null);
        }

        /** 转为带 sourceId/provider/content 的 SearchHit(type=WEB)。 */
        public SearchTool.SearchHit toSearchHit() {
            return new SearchTool.SearchHit("WEB", title, url, snippet, provider, null, 0, sourceId, provider, content);
        }
    }

    /**
     * 治理原始 WEB 命中列表。
     *
     * @param raw        原始命中(可能 URL 非法/重复/provider 为空)
     * @param maxResults 上限(≤0 视为不限制)
     * @return 规范、去重、截断后并带 sourceId 的命中列表;无有效时返回空列表(不返回 null)
     */
    public static List<WebHit> normalize(List<SearchTool.SearchHit> raw, int maxResults) {
        List<WebHit> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        Map<String, Boolean> seen = new LinkedHashMap<>();   // 规范化 URL → 已收录(去重)
        int limit = maxResults <= 0 ? Integer.MAX_VALUE : maxResults;
        for (SearchTool.SearchHit h : raw) {
            if (h == null) continue;
            String normalized = normalizeUrl(h.url());
            if (normalized == null) continue;              // 非 http/https / 非绝对 URL:丢弃
            if (seen.containsKey(normalized)) continue;    // 重复 URL 只保留一条
            seen.put(normalized, Boolean.TRUE);
            String provider = h.provider() != null && !h.provider().isBlank()
                    ? h.provider() : (h.modelName() == null ? "" : h.modelName());
            out.add(new WebHit("W" + (out.size() + 1), h.title(), normalized, h.snippet(), provider, h.content()));
            if (out.size() >= limit) break;
        }
        return out;
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
