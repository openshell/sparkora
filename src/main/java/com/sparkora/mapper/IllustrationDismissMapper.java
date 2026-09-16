package com.sparkora.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sparkora.domain.entity.IllustrationDismissEntity;
import org.apache.ibatis.annotations.Mapper;

/** 配图建议「忽略」记录 Mapper（09-15 article-auto-illustrate，子C）。 */
@Mapper
public interface IllustrationDismissMapper extends BaseMapper<IllustrationDismissEntity> {
}
