package com.sparkora.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sparkora.domain.entity.NewsDocEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

/**
 * 新闻切块 mapper。对应 sparkora_news_doc(C2)。
 */
public interface NewsDocMapper extends BaseMapper<NewsDocEntity> {

    /** 物理删除某新闻的全部切块(重建幂等的先清后插)。 */
    @Delete("DELETE FROM sparkora_news_doc WHERE news_id = #{newsId}")
    int deleteByNewsId(@Param("newsId") Long newsId);
}
