package com.sparkora.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sparkora.domain.entity.KbDocTagEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * KB 文档标签 Mapper（10-03 E3）。镜像 {@code ImageTagMapper}。
 */
@Mapper
public interface KbDocTagMapper extends BaseMapper<KbDocTagEntity> {

    /** 物理删除某文档的全部标签行（全量覆盖 / 文档删除清理）。 */
    @Delete("DELETE FROM sparkora_kb_doc_tag WHERE doc_id = #{docId}")
    int deleteByDocId(@Param("docId") Long docId);
}
