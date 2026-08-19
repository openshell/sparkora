package com.sparkora.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sparkora.domain.entity.ArticleVersionEntity;
import org.apache.ibatis.annotations.Mapper;

/** 文章版本 Mapper。 */
@Mapper
public interface ArticleVersionMapper extends BaseMapper<ArticleVersionEntity> {
}
