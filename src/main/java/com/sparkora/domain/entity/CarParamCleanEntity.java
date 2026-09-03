package com.sparkora.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 清洗后结构化参数实体。对应 sparkora_car_param_clean。
 * 由规则引擎 + AI 兜底清洗 car_param 原始串生成,支撑文章生成干净取值/跨版本对比/数值计算。
 */
@Data
@TableName("sparkora_car_param_clean")
public class CarParamCleanEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long paramId;          // 关联原始参数 sparkora_car_param.id
    private Long modelId;
    private Long versionId;        // 可空:全局参数为空,版本专属指向版本
    private String paramKey;       // 规范化参数名,如 轴距 / 纯电续航
    private String paramValue;     // 清洗后的值(字符串/枚举/布尔)
    private String valueType;      // STRING / NUMBER / BOOLEAN / ENUM / LIST
    private BigDecimal numericValue; // value_type=NUMBER 时的数值
    private String unit;           // 单位,如 mm / km / kWh
    private String enumValue;      // value_type=ENUM 时的枚举(有/无/可选装)
    private String listValues;     // value_type=LIST 时的 JSON 数组
    private String rawValue;       // 清洗前原始串(回溯)
    private String cleanMethod;    // RULE / AI / RULE_AI
    private BigDecimal confidence; // AI 清洗置信度
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
