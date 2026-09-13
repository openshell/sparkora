package com.sparkora.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sparkora.domain.entity.ImageTagEntity;
import org.apache.ibatis.annotations.Mapper;

/** 图片标签 Mapper（09-13 image-tags）。 */
@Mapper
public interface ImageTagMapper extends BaseMapper<ImageTagEntity> {
}