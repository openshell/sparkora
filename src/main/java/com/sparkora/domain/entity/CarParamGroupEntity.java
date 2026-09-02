package com.sparkora.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 参数分组实体。对应 sparkora_car_param_group。
 * 数据源:goodsParams.configs。
 */
@Data
@TableName("sparkora_car_param_group")
public class CarParamGroupEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long modelId;
    private String groupName;      // 尺寸参数 / 动力性能 / DiPilot智能辅助驾驶
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
