package com.sparkora.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * 新闻向量表 mapper。对应 sparkora_news_doc_embedding(C2)。
 * VECTOR 类型 MyBatis-Plus BaseMapper 无法直接处理,用注解 SQL(参照 CarDocEmbeddingMapper)。
 * 向量以字符串形式传入(如 "[0.1,0.2,...]"),由 pgvector 解析。
 */
public interface NewsDocEmbeddingMapper {

    /** 插入一条向量。embedding 传 pgvector 字面量字符串,如 "[0.1,0.2,...]"。 */
    @Insert("INSERT INTO sparkora_news_doc_embedding (doc_id, news_id, embedding, created_at) " +
            "VALUES (#{docId}, #{newsId}, #{embedding}::vector, CURRENT_TIMESTAMP)")
    int insert(@Param("docId") Long docId, @Param("newsId") Long newsId, @Param("embedding") String embedding);

    /** 删除某文档块的全部向量(重算时先清)。 */
    @Delete("DELETE FROM sparkora_news_doc_embedding WHERE doc_id = #{docId}")
    int deleteByDocId(@Param("docId") Long docId);

    /** 按新闻物理清理全部向量(删除/重建兜底:含历史逻辑删除但向量残留的块)。 */
    @Delete("DELETE FROM sparkora_news_doc_embedding WHERE news_id = #{newsId}")
    int deleteByNewsId(@Param("newsId") Long newsId);
}
