package com.sparkora.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 车型版本实体。对应 sparkora_car_version。
 * 数据源:goodsParams 的「车型」行 + getGoodsAttrListForCompareByGoodsId 的价格。
 */
@Data
@TableName("sparkora_car_version")
public class CarVersionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long modelId;
    private String versionName;    // 800KM后驱激光雷达尊荣型
    private BigDecimal price;       // 239900
    private String priceRemark;     // "239,900起"
    private Integer sortOrder;      // 对应 value[] 下标
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
