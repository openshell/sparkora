package com.sparkora.deep.tool;

import java.util.List;

/**
 * 研究子代理搜索工具抽象(S9 深度生成)。
 * 实现:KnowledgeSearchTool(本地统一检索)/ SearxngSearchTool / TavilySearchTool。
 * 所有 WEB 来源条目必须带 url;KB 条目带 docId/modelName 供事实手册溯源。
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

    /** 单条搜索命中。type: KB/WEB;url 仅 WEB 有;modelName 复用为 WEB 工具名(TAVILY/SEARXNG)。 */
    record SearchHit(String type, String title, String url, String snippet,
                     String modelName, Long docId, double score) {
        public static SearchHit kb(String title, String modelName, Long docId, String snippet, double score) {
            return new SearchHit("KB", title, null, snippet, modelName, docId, score);
        }
        /** WEB 命中统一 type=WEB(来源工具名记入 title 前缀由调用方处理);tool 单独字段。 */
        public static SearchHit web(String toolName, String title, String url, String snippet) {
            return new SearchHit("WEB", title, url, snippet, toolName, null, 0);
        }
    }
}