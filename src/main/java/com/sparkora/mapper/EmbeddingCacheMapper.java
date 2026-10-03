package com.sparkora.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 内容寻址嵌入缓存 mapper（10-03 E5）。
 *
 * <p>对应 {@code sparkora_embedding_cache}:主键 {@code (content_hash, embedding_model)}，
 * 相同 sha256(text) + 相同模型复用已有 pgvector 字面量。缓存只做键查回读，不做 ANN。
 */
public interface EmbeddingCacheMapper {

    /** 按 (内容哈希, 模型) 取缓存向量字面量；未命中返回 null。 */
    @Select("SELECT embedding FROM sparkora_embedding_cache "
            + "WHERE content_hash = #{contentHash} AND embedding_model = #{embeddingModel}")
    String selectByHashModel(@Param("contentHash") String contentHash,
                             @Param("embeddingModel") String embeddingModel);

    /** best-effort 写入缓存；主键冲突静默忽略（并发/重跑幂等）。 */
    @Insert("INSERT INTO sparkora_embedding_cache (content_hash, embedding_model, embedding, created_at) "
            + "VALUES (#{contentHash}, #{embeddingModel}, #{embedding}, CURRENT_TIMESTAMP) "
            + "ON CONFLICT (content_hash, embedding_model) DO NOTHING")
    int insertIgnore(@Param("contentHash") String contentHash,
                     @Param("embeddingModel") String embeddingModel,
                     @Param("embedding") String embedding);
}
