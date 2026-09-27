package com.sparkora.mapper;

import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 向量模型对账统计 mapper（09-27 知识域写入侧统一 R7）。
 *
 * <p>按表统计各 {@code embedding_model} 的行数与模型名，供启动对账 Runner 检测「存在非当前配置模型」的行
 * （换模型后旧向量未重嵌 → WARN 提示）。纯只读聚合，不拉向量本体。
 */
public interface EmbeddingModelStatsMapper {

    @Select("SELECT embedding_model AS \"model\", COUNT(*) AS \"cnt\" " +
            "FROM sparkora_car_doc_embedding GROUP BY embedding_model")
    List<Map<String, Object>> carModelStats();

    @Select("SELECT embedding_model AS \"model\", COUNT(*) AS \"cnt\" " +
            "FROM sparkora_kb_chunk_embedding GROUP BY embedding_model")
    List<Map<String, Object>> kbModelStats();

    @Select("SELECT embedding_model AS \"model\", COUNT(*) AS \"cnt\" " +
            "FROM sparkora_news_doc_embedding GROUP BY embedding_model")
    List<Map<String, Object>> newsModelStats();

    @Select("SELECT embedding_model AS \"model\", COUNT(*) AS \"cnt\" " +
            "FROM sparkora_image_embedding GROUP BY embedding_model")
    List<Map<String, Object>> imageModelStats();
}
