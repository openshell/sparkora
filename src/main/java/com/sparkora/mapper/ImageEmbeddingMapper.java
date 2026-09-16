package com.sparkora.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 图片语义向量表 mapper（09-15 img-semantic-search，子B）。对应 sparkora_image_embedding。
 *
 * VECTOR 类型 MyBatis-Plus BaseMapper 无法直接处理，用注解 SQL（参照 CarDocEmbeddingMapper /
 * KbChunkEmbeddingMapper 先例）；向量以 pgvector 字面量字符串（如 "[0.1,0.2,...]"）传入，由 pgvector 解析。
 *
 * 检索不 JOIN 主表：主表字段（fileName/source/sourceRef）由 service 批查回填，
 * 避免与图库表的删除语义耦合（图库表无逻辑删除，物理删）。
 */
public interface ImageEmbeddingMapper {

    /** 插入一条图片向量。embedding 传 pgvector 字面量字符串。 */
    @Insert("INSERT INTO sparkora_image_embedding (image_id, embedding, source_text, created_at) " +
            "VALUES (#{imageId}, #{embedding}::vector, #{sourceText}, CURRENT_TIMESTAMP)")
    int insert(@Param("imageId") Long imageId,
               @Param("embedding") String embedding,
               @Param("sourceText") String sourceText);

    /** 删除某图的向量（重建/增量共用：先物理清后插，幂等）。 */
    @Delete("DELETE FROM sparkora_image_embedding WHERE image_id = #{imageId}")
    int deleteByImageId(@Param("imageId") Long imageId);

    /** 无向量的图片 id 集（rebuildMissing 用；一次 JOIN 求出差集，不走全量拉取）。 */
    @Select("SELECT a.id FROM sparkora_image_asset a " +
            "LEFT JOIN sparkora_image_embedding e ON e.image_id = a.id " +
            "WHERE e.id IS NULL ORDER BY a.id")
    List<Long> findImageIdsWithoutEmbedding();

    /**
     * 余弦相似度检索 top-K；ids 非空时限定候选集（标签 AND 预过滤后的白名单，候选集 ≤500）。
     * 返回行：imageId / sourceText / score（余弦相似度，越大越相关）。
     *
     * 门槛在 SQL 外层过滤（`WHERE 1 - (embedding <=> vec) >= minScore`）：不传输注定被丢弃的行。
     * 注意：门槛写在 ORDER BY/LIMIT 同一层，HNSW 索引（`idx_image_emb_vec_hnsw`）仍可用于排序；
     * 图库规模小（~170 张），实际代价可忽略。
     *
     * 「&lt;script&gt;」标签内是 XML 解析的：pgvector 的 `&lt;=&gt;` 距离运算符与 `&gt;=` 比较符必须写成
     * 实体（`&lt;=&gt;` / `&gt;=`），否则 SAXParser 报「元素内容必须由格式正确的字符数据或标记组成」（启动期 mapper 注册失败）。
     * `<if test='ids.size() > 0'>` 里的 `&gt;` 同理。
     */
    @Select("<script>SELECT e.image_id AS \"imageId\", e.source_text AS \"sourceText\", " +
            "1 - (e.embedding &lt;=&gt; #{queryVec}::vector) AS \"score\" " +
            "FROM sparkora_image_embedding e " +
            "WHERE 1 - (e.embedding &lt;=&gt; #{queryVec}::vector) &gt;= #{minScore} " +
            "<if test='ids != null and ids.size() &gt; 0'>" +
            "AND e.image_id IN <foreach item='i' collection='ids' open='(' separator=',' close=')'>#{i}</foreach> " +
            "</if>" +
            "ORDER BY e.embedding &lt;=&gt; #{queryVec}::vector LIMIT #{limit}</script>")
    List<Map<String, Object>> searchTopK(@Param("queryVec") String queryVec,
                                         @Param("ids") List<Long> ids,
                                         @Param("minScore") double minScore,
                                         @Param("limit") int limit);
}
