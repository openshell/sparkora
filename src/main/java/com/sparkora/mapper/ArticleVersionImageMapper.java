package com.sparkora.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sparkora.domain.entity.ArticleVersionImageEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 版本-正文插图关联 Mapper（P1-⑦ 原逗号列规范化）。
 *
 * 对应 sparkora_article_version_image。insert 走 BaseMapper（应用层组装 sort_order）；
 * 有序读 / 按图反查引用 / 幂等删除用注解 SQL 固化，避免读路径在 ImageService 与 PreviewService 各写一份。
 */
@Mapper
public interface ArticleVersionImageMapper extends BaseMapper<ArticleVersionImageEntity> {

    /** 有序取某版本的全部插图 id（sort_order 升序，与原逗号串顺序一致）。 */
    @Select("SELECT image_id FROM sparkora_article_version_image " +
            "WHERE version_id = #{versionId} ORDER BY sort_order ASC, id ASC")
    List<Long> findImageIdsByVersion(@Param("versionId") Long versionId);

    /** 引用反查：引用该图的全部版本 id（替代原 LIKE 粗筛，精确匹配，消除 id=5 误配 15/51）。 */
    @Select("SELECT version_id FROM sparkora_article_version_image WHERE image_id = #{imageId}")
    List<Long> findVersionIdsByImage(@Param("imageId") Long imageId);

    /** 当前最大 sort_order（无行返回 null）；追加时 sort_order = max + 1。 */
    @Select("SELECT MAX(sort_order) FROM sparkora_article_version_image WHERE version_id = #{versionId}")
    Integer maxSortOrder(@Param("versionId") Long versionId);

    /** 删某版本的某插图（幂等：不存在也视为成功）。 */
    @Delete("DELETE FROM sparkora_article_version_image " +
            "WHERE version_id = #{versionId} AND image_id = #{imageId}")
    int deleteByVersionAndImage(@Param("versionId") Long versionId, @Param("imageId") Long imageId);

    /** 物理清某版本全部关联行（版本删除路径预留；当前无版本级联删除路径）。 */
    @Delete("DELETE FROM sparkora_article_version_image WHERE version_id = #{versionId}")
    int deleteByVersion(@Param("versionId") Long versionId);
}
