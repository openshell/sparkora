package com.sparkora.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 通用知识块向量 mapper。对应 sparkora_kb_chunk_embedding(S7)。
 * VECTOR 类型 MyBatis-Plus BaseMapper 无法直接处理,用注解 SQL(参照 CarDocEmbeddingMapper)。
 * 与车型域检索的区别:无 model_id 约束,全库检索。
 */
public interface KbChunkEmbeddingMapper {

    /** 插入一条向量。embedding 传 pgvector 字面量字符串,如 "[0.1,0.2,...]"。 */
    @Insert("INSERT INTO sparkora_kb_chunk_embedding (chunk_id, embedding, created_at) " +
            "VALUES (#{chunkId}, #{embedding}::vector, CURRENT_TIMESTAMP)")
    int insert(@Param("chunkId") Long chunkId, @Param("embedding") String embedding);

    /** 删除某文档全部块的向量(重算时先清)。 */
    @Delete("DELETE FROM sparkora_kb_chunk_embedding WHERE chunk_id IN (SELECT id FROM sparkora_kb_chunk WHERE doc_id = #{docId})")
    int deleteByDocId(@Param("docId") Long docId);

    /**
     * 通用域余弦相似度检索 top-K(全库,无车型约束)。
     * 仅取启用文档的块;返回 chunk_id + chunk_text + score(余弦相似度,越大越相关)。
     */
    @Select("SELECT e.chunk_id AS \"chunkId\", c.chunk_text AS \"chunkText\", " +
            "1 - (e.embedding <=> #{queryVec}::vector) AS \"score\" " +
            "FROM sparkora_kb_chunk_embedding e " +
            "JOIN sparkora_kb_chunk c ON c.id = e.chunk_id " +
            "JOIN sparkora_kb_doc d ON d.id = c.doc_id AND d.deleted = 0 AND d.enabled = TRUE " +
            "ORDER BY e.embedding <=> #{queryVec}::vector " +
            "LIMIT #{limit}")
    List<Map<String, Object>> searchTopK(@Param("queryVec") String queryVec,
                                         @Param("limit") int limit);
}