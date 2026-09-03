package com.sparkora.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 参数明细实体。对应 sparkora_car_param。
 * 数据源:goodsParams.configs[].value[]。
 */
@Data
@TableName("sparkora_car_param")
public class CarParamEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long groupId;
    private Long modelId;
    private String paramName;      // 长×宽×高(mm) / 轴距(mm)
    private String paramValue;     // 该参数在「当前选中版本」下的值
    private String valuesJson;     // JSON 数组,全版本值(保留下标对齐)
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
