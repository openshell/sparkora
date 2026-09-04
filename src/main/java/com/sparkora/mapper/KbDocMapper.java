package com.sparkora.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

/**
 * 通用知识文档 mapper。对应 sparkora_kb_doc(S7)。
 */
public interface KbDocMapper extends com.baomidou.mybatisplus.core.mapper.BaseMapper<com.sparkora.domain.entity.KbDocEntity> {

    /** 物理删除某文档的全部切块(重建幂等的先清后插)。 */
    @Delete("DELETE FROM sparkora_kb_chunk WHERE doc_id = #{docId}")
    int deleteChunksByDocId(@Param("docId") Long docId);
}