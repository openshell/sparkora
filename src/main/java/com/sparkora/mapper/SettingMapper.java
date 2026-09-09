package com.sparkora.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sparkora.domain.entity.SettingEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 系统检索设置 Mapper。单行表,仅按 id 查。
 */
@Mapper
public interface SettingMapper extends BaseMapper<SettingEntity> {
}