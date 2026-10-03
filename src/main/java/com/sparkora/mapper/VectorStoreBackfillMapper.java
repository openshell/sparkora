package com.sparkora.mapper;

import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 向量层迁移回填专用 mapper（10-03 E1）。
 *
 * <p>把旧 4 张向量表的「已有向量 + 关联元数据」读出来，搬进单张 {@code vector_store}。
 * **只读旧表，绝不改旧表**；向量以 {@code e.embedding::text} 读出（pgvector 字面量），
 * 回填时直接写入而非重新嵌入——阶段 A 对拍要求嵌入不重算、结果不抖动。
 *
 * <p>返回行字段统一为：{@code refId / modelId(可空) / chunkType / name / embeddingModel / vector / content}。
 */
public interface VectorStoreBackfillMapper {

    /** CAR 域：仅未逻辑删除的车型块（JOIN 车型名）。 */
    @Select("SELECT e.doc_id AS \"refId\", d.model_id AS \"modelId\", d.chunk_type AS \"chunkType\", " +
            "m.name AS \"name\", e.embedding_model AS \"embeddingModel\", e.embedding::text AS \"vector\", " +
            "d.chunk_text AS \"content\" " +
            "FROM sparkora_car_doc_embedding e " +
            "JOIN sparkora_car_doc d ON d.id = e.doc_id AND d.deleted = 0 " +
            "JOIN sparkora_car_model m ON m.id = d.model_id " +
            "ORDER BY e.doc_id")
    List<Map<String, Object>> readCar();

    /** KB 域：仅启用且未逻辑删除的文档块（JOIN 文档标题）。chunkType 固定 KB_CHUNK（与活写路径
     *  {@code KbDocService} 一致，避免回填得到空串、重建后变成 KB_CHUNK 的元数据不一致）。
     * 10-03 E3：回填 source/生效期 + 按生效期计算的 active（避免未来生效行被回填成 active=true）。 */
    @Select("SELECT e.chunk_id AS \"refId\", 'KB_CHUNK' AS \"chunkType\", c.chunk_text AS \"content\", d.title AS \"name\", " +
            "e.embedding_model AS \"embeddingModel\", e.embedding::text AS \"vector\", " +
            "d.source AS \"source\", d.effective_from AS \"effectiveFrom\", d.effective_to AS \"effectiveTo\", " +
            "(d.enabled AND (d.effective_from IS NULL OR CURRENT_DATE >= d.effective_from) " +
            " AND (d.effective_to IS NULL OR CURRENT_DATE <= d.effective_to)) AS \"active\" " +
            "FROM sparkora_kb_chunk_embedding e " +
            "JOIN sparkora_kb_chunk c ON c.id = e.chunk_id " +
            "JOIN sparkora_kb_doc d ON d.id = c.doc_id AND d.deleted = 0 AND d.enabled = TRUE " +
            "ORDER BY e.chunk_id")
    List<Map<String, Object>> readKb();

    /** NEWS 域：仅未逻辑删除的新闻块（JOIN 新闻标题）。 */
    @Select("SELECT e.doc_id AS \"refId\", d.chunk_type AS \"chunkType\", n.title AS \"name\", " +
            "e.embedding_model AS \"embeddingModel\", e.embedding::text AS \"vector\", d.chunk_text AS \"content\" " +
            "FROM sparkora_news_doc_embedding e " +
            "JOIN sparkora_news_doc d ON d.id = e.doc_id AND d.deleted = 0 " +
            "JOIN sparkora_news n ON n.id = d.news_id AND n.deleted = 0 " +
            "ORDER BY e.doc_id")
    List<Map<String, Object>> readNews();

    /** IMAGE 域：全部图片（图库物理删除，无逻辑删）。content = 旧表 source_text，name = 文件名。 */
    @Select("SELECT e.image_id AS \"refId\", e.source_text AS \"content\", a.file_name AS \"name\", " +
            "e.embedding_model AS \"embeddingModel\", e.embedding::text AS \"vector\" " +
            "FROM sparkora_image_embedding e " +
            "JOIN sparkora_image_asset a ON a.id = e.image_id " +
            "ORDER BY e.image_id")
    List<Map<String, Object>> readImage();
}
