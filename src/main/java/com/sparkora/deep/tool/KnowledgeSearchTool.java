package com.sparkora.deep.tool;

import com.sparkora.car.service.CarRagService;
import com.sparkora.source.service.SourceCatalog;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地统一知识库搜索(S9):委托 CarRagService.retrieveUnified(S8 统一检索,全库跨车型域与 KB 域)。
 * 永远可用(检索异常时返回空列表由调用方归入 gaps)。
 *
 * <p>10-05-source-web-fusion F-R1:NEWS 域命中的自建信源(用户采集源,{@code sourceType != null 且 != byd-news})
 * 返回 {@code SOURCE} 类型,不再一律标 KB(避免网络采集内容被当手写权威知识库拿 0.9);
 * BYD 官方新闻({@code sourceType=byd-news} 或缺省兜底)仍返回 {@code KB}——保持 BYD 现有语义逐位等价。
 */
@Component
public class KnowledgeSearchTool implements SearchTool {

    private final CarRagService ragService;

    public KnowledgeSearchTool(CarRagService ragService) {
        this.ragService = ragService;
    }

    @Override
    public String name() { return "KB"; }

    @Override
    public boolean available() { return true; }

    @Override
    public List<SearchHit> search(String query, int maxResults) {
        return search(query, maxResults, null);
    }

    /**
     * 锚点感知检索(R1,2026-09-06):委托 CarRagService.retrieveForGeneration 统一通道——
     * 锚点车型加权 + 参数级子查询 + 核心块/权益块分层配额(S6.2/S8 资产全复用)。
     * 解决「全库无锚点 top-N 单查询把 MODEL_INFO 价块挤出、RIGHTS 占满配额」的选拔缺陷(项目 28 实测)。
     * @param anchors 锚点车型 id(项目关联/主题识别;可空=退化为无锚点全库检索)
     */
    public List<SearchHit> search(String query, int maxResults, List<Long> anchors) {
        List<SearchHit> out = new ArrayList<>();
        try {
            CarRagService.RagResult r = ragService.retrieveForGeneration(query, maxResults,
                    anchors == null ? List.of() : anchors);
            if (!r.ok()) return List.of();   // LOW_CONFIDENCE/FAILED/NO_KNOWLEDGE 与旧行为一致:空列表归 gaps
            for (CarRagService.Citation c : r.citations()) {
                // citations 与注入 prompt 的 context 同源,单条文本已截断 120 字符;snippet 展示足够
                String title = citeTitle(c);
                // F-R1:NEWS 域自建信源(用户采集源)按 SOURCE 输出;byd-news/非 NEWS 域保持 KB(零回归)
                if (isUserSource(c)) {
                    out.add(SearchHit.source(title, c.modelName(), c.docId(), c.chunkText(), c.score(),
                            c.sourceType(), null, SourceCatalog.crossCounted(c.sourceType())));
                } else {
                    out.add(SearchHit.kb(title, c.modelName(), null, c.chunkText(), c.score()));
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return out;
    }

    /**
     * F-R1:该引用是否为「用户采集自建信源」——source=NEWS 且 sourceType 非空且非 byd-news
     * (byd-news 及 sourceType 缺省兜底/非 NEWS 域走原 KB 语义)。判定复用 {@link SourceCatalog#BYD_NEWS}
     * 单一来源,避免字面量散落。
     */
    private static boolean isUserSource(CarRagService.Citation c) {
        if (c == null || !"NEWS".equals(c.source())) return false;
        String st = c.sourceType();
        if (st == null || st.isBlank()) return false;
        return !SourceCatalog.BYD_NEWS.equals(st);
    }

    /** 引用条目标题:车型名 + 块类型(与行内【车型数据:名称】标注同源可读化)。 */
    private static String citeTitle(CarRagService.Citation c) {
        String base = c.modelName() == null || c.modelName().isBlank() ? "" : c.modelName();
        return base.isBlank() ? c.chunkType() : base + "·" + c.chunkType();
    }
}