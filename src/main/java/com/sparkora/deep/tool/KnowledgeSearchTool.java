package com.sparkora.deep.tool;

import com.sparkora.car.service.CarRagService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地统一知识库搜索(S9):委托 CarRagService.retrieveUnified(S8 统一检索,全库跨车型域与 KB 域)。
 * 永远可用(检索异常时返回空列表由调用方归入 gaps)。
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
        List<SearchHit> out = new ArrayList<>();
        try {
            for (CarRagService.UnifiedHit h : ragService.retrieveUnified(query, maxResults)) {
                out.add(SearchHit.kb(h.modelName() == null ? "" : h.modelName(), h.modelName(), h.modelId(),
                        firstLine(h.chunkText()), h.score()));
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return out;
    }

    /** 块文本首行(标题/车型名)+次行摘要。 */
    private static String firstLine(String text) {
        if (text == null) return "";
        String[] lines = text.split("\n");
        return lines.length > 0 ? lines[0] : "";
    }
}