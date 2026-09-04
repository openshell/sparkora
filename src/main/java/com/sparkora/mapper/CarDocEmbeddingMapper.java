package com.sparkora.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 向量表 mapper。对应 sparkora_car_doc_embedding。
 * VECTOR 类型 MyBatis-Plus BaseMapper 无法直接处理,用注解 SQL。
 * 向量以字符串形式传入(如 "[0.1,0.2,...]"),由 pgvector 解析。
 */
public interface CarDocEmbeddingMapper {

    /** 插入一条向量。embedding 传 pgvector 字面量字符串,如 "[0.1,0.2,...]"。 */
    @Insert("INSERT INTO sparkora_car_doc_embedding (doc_id, model_id, embedding, created_at) " +
            "VALUES (#{docId}, #{modelId}, #{embedding}::vector, CURRENT_TIMESTAMP)")
    int insert(@Param("docId") Long docId, @Param("modelId") Long modelId, @Param("embedding") String embedding);

    /** 删除某文档块的全部向量(重算时先清)。 */
    @Insert("DELETE FROM sparkora_car_doc_embedding WHERE doc_id = #{docId}")
    int deleteByDocId(@Param("docId") Long docId);

    /**
     * 余弦相似度检索 top-K。embedding 传查询向量字面量字符串。
     * 返回 doc_id + chunk_text + score(余弦相似度,越大越相关)。
     */
    @Select("SELECT e.doc_id AS \"docId\", d.chunk_text AS \"chunkText\", d.chunk_type AS \"chunkType\", " +
            "1 - (e.embedding <=> #{queryVec}::vector) AS \"score\" " +
            "FROM sparkora_car_doc_embedding e " +
            "JOIN sparkora_car_doc d ON d.id = e.doc_id AND d.deleted = 0 " +
            "WHERE e.model_id = #{modelId} " +
            "ORDER BY e.embedding <=> #{queryVec}::vector " +
            "LIMIT #{limit}")
    List<Map<String, Object>> searchTopK(@Param("modelId") Long modelId,
                                         @Param("queryVec") String queryVec,
                                         @Param("limit") int limit);

    /** 全库向量对账统计(S6b):每车型块数与有向量块数(不拉向量本体,轻量聚合)。仅统计未逻辑删除的块。 */
    @Select("SELECT d.model_id AS \"modelId\", COUNT(*) AS \"chunkCount\", " +
            "COUNT(e.id) AS \"embeddedCount\" " +
            "FROM sparkora_car_doc d " +
            "LEFT JOIN sparkora_car_doc_embedding e ON e.doc_id = d.id " +
            "WHERE d.deleted = 0 " +
            "GROUP BY d.model_id")
    List<Map<String, Object>> countByModel();
}
