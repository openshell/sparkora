package com.sparkora.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 车型主表实体。对应 sparkora_car_model。
 * 数据源:比亚迪官网 goodsListForSearch / getGoodsInfoById。
 */
@Data
@TableName("sparkora_car_model")
public class CarModelEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String goodsId;        // 官网 goodsId,如 156
    private String name;           // 大唐EV
    private String salesNetwork;   // 王朝 / 海洋
    private String vehicleId;      // 官网 vehicleId
    private String priceRange;     // "239,900 - 309,900"
    private String features;       // JSON 数组,卖点
    private String introImages;    // JSON 数组,图片 URL
    private String detailPage;     // 官网详情页路径
    private String carRights;      // JSON,购车权益
    private String sourceUrl;      // 来源官网 URL
    private String syncStatus;     // PENDING/SYNCING/SUCCESS/FAILED
    private LocalDateTime lastSyncAt;
    private String lastSyncError;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
