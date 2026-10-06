package com.sparkora.deep.tool;

import java.util.List;

/**
 * 研究子代理搜索工具抽象(S9 深度生成)。
 * 实现:KnowledgeSearchTool(本地统一检索)/ SearxngSearchTool / TavilySearchTool / SerperSearchTool。
 * 所有 WEB 来源条目必须带 url;KB 条目带 docId/modelName 供事实手册溯源。
 *
 * <p><b>认证差异(跨实现约定,务必遵守)</b>:{@code SerperSearchTool} 用 <b>Header {@code X-API-KEY}</b> 认证;
 * {@code TavilySearchTool} 用 <b>body {@code api_key}</b>。两者不通用——把 Tavily 的 body 写法复制到 Serper
 * 会 401。新增付费 provider 时先确认其认证方式。
 *
 * <p><b>垂直(vertical)</b>:{@link #searchVertical(String, String, int)} 默认委托 {@link #search(String, int)}
 * (等价 {@code vertical=web});未知 vertical 值按 {@code web} 处理并 warn，<b>不抛异常</b>——垂直选择是
 * 运行时启发式路由，可回退;这与 {@code WebProvider.from()} 对<b>运维配置错误</b>抛
 * {@link IllegalArgumentException} 的做法刻意不同(配置错误必须暴露 vs 启发式可回退)。
 */
public interface SearchTool {

    /** 工具名:KB / SEARXNG / TAVILY(研究计划 toolHints 与工具健康展示用)。 */
    String name();

    /** 是否可用(仅判配置就绪:密钥/地址缺失时 false,调用方降级其他工具;不含调用结果)。 */
    boolean available();

    /** 配置态:密钥/地址是否就绪(不随调用结果变化)。 */
    default boolean configured() { return true; }

    /** 最近一次调用是否成功(初值 true=未调用过,乐观;仅供健康展示,不参与 available 判定)。 */
    default boolean lastCallOk() { return true; }

    /**
     * 搜索。返回命中条目(不超过 maxResults);异常由实现内部捕获并返回空列表(不抛出,避免子代理整体失败)。
     */
    List<SearchHit> search(String query, int maxResults);

    /**
     * 按垂直搜索(10-04-serper-provider A-R3)。
     *
     * <p>{@code vertical ∈ {web, news}}:{@code web}=通用网页({@code /search}，{@code organic[]})；
     * {@code news}=时效新闻垂直(仅有此垂直返回 {@code date}/{@code source}，供 R4b 时效能力使用)。
     *
     * <p>默认实现委托 {@link #search(String, int)}(等价 {@code vertical=web})——不支持垂直的工具
     * (SearxNG/KB)零改动即满足契约。{@code SerperSearchTool} 覆写为真实垂直路由;
     * 未知 vertical 值回落 {@code web} + warn，不抛出。
     *
     * @param query      查询串
     * @param vertical   垂直({@code web}/{@code news}；null/未知按 {@code web})
     * @param maxResults 返回条数上限
     * @return 命中列表;异常实现内部捕获返回空列表(不抛出)
     */
    default List<SearchHit> searchVertical(String query, String vertical, int maxResults) {
        return search(query, maxResults);
    }

    /**
     * 按 URL 抽取正文片段(09-27-tavily-extract-kind-hypotheses R1,机制 B:search 拿摘要 + 按需 extract 补正文)。
     *
     * <p>默认返回空列表(不支持正文抽取的工具无需实现);{@code TavilySearchTool} 覆写为 {@code POST /extract}。
     * 实现约定:异常/空结果(含 {@code failed_results})一律降级为空列表,**绝不抛出**——补正文是增强,
     * 失败必须回退为「仅摘要」,不得阻断研究链路。
     *
     * @param urls  待抽取 URL(已治理/规范化)
     * @param query 语义重排查询(通常为研究问题;可空)
     * @return 每条 {url, content};无正文时返回空列表(不返回 null)
     */
    default List<SearchHit> extract(List<String> urls, String query) {
        return List.of();
    }

    /**
     * 单条搜索命中。type: KB/WEB;url 仅 WEB 有;modelName 复用为 WEB 工具名(TAVILY/SEARXNG)。
     * 09-25-brief-web-search 增量:WEB 命中带稳定 {@code sourceId}(W1/W2…)与 {@code provider}(来源工具名),
     * 供 LLM 事实引用与后验校验;KB 命中两者为空。
     * 09-27-tavily-extract-kind-hypotheses 增量:{@code content}=正文片段(nullable,仅供研究注入与降级留证),
     * 与 {@code snippet}(摘要,引用/预览语义)严格区分。
     */
    record SearchHit(String type, String title, String url, String snippet,
                     String modelName, Long docId, double score,
                     String sourceId, String provider, String content) {

        /**
         * 兼容构造器(9 参,content=null):既有调用方(工具实现/WebResultNormalizer/测试)不受影响。
         */
        public SearchHit(String type, String title, String url, String snippet,
                         String modelName, Long docId, double score,
                         String sourceId, String provider) {
            this(type, title, url, snippet, modelName, docId, score, sourceId, provider, null);
        }

        /**
         * 兼容构造器(7 参,sourceId/provider/content 为空):既有调用方(工具实现/测试)不受影响。
         */
        public SearchHit(String type, String title, String url, String snippet,
                         String modelName, Long docId, double score) {
            this(type, title, url, snippet, modelName, docId, score, null, null, null);
        }

        public static SearchHit kb(String title, String modelName, Long docId, String snippet, double score) {
            return new SearchHit("KB", title, null, snippet, modelName, docId, score);
        }
        /** WEB 命中统一 type=WEB(来源工具名记入 title 前缀由调用方处理);tool 单独字段。 */
        public static SearchHit web(String toolName, String title, String url, String snippet) {
            return new SearchHit("WEB", title, url, snippet, toolName, null, 0);
        }
        /** WEB 命中(带正文片段,09-27 R1/R2 增量;旧 4 参重载委托 content=null)。 */
        public static SearchHit web(String toolName, String title, String url, String snippet, String content) {
            return new SearchHit("WEB", title, url, snippet, toolName, null, 0, null, null, content);
        }
        /** 正文抽取结果条目(type=WEB,仅 url + content;供 SubAgentRunner 按 URL 回填命中)。 */
        public static SearchHit webContent(String toolName, String url, String content) {
            return new SearchHit("WEB", "", url, "", toolName, null, 0, null, null, content);
        }
    }
}