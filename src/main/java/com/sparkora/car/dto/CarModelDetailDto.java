package com.sparkora.car.dto;

import com.sparkora.domain.entity.CarModelEntity;
import com.sparkora.domain.entity.CarParamCleanEntity;
import com.sparkora.domain.entity.CarParamEntity;
import com.sparkora.domain.entity.CarParamGroupEntity;
import com.sparkora.domain.entity.CarVersionEntity;
import lombok.Data;

import java.util.List;

/**
 * 车型详情聚合 DTO(列表页用主表,详情页用此聚合版本/分组/参数)。
 * 每个分组含原始参数(params)与清洗后参数(cleans),前端可切换展示。
 */
@Data
public class CarModelDetailDto {
    private CarModelEntity model;
    private List<CarVersionEntity> versions;
    private List<GroupWithParams> groups;

    @Data
    public static class GroupWithParams {
        private CarParamGroupEntity group;
        private List<CarParamEntity> params;          // 原始参数
        private List<CarParamCleanEntity> cleans;     // 清洗后参数(全局,version_id=null)
    }
}
