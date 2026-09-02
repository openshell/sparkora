package com.sparkora.car.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 参数清洗结果 DTO。由规则引擎或 AI 生成,写入 sparkora_car_param_clean。
 */
@Data
public class ParamCleanResult {
    /** 规范化参数名,如 轴距 / 纯电续航。 */
    private String paramKey;
    /** STRING / NUMBER / BOOLEAN / ENUM / LIST。 */
    private String valueType;
    /** 清洗后的值(字符串/枚举/布尔)。 */
    private String value;
    /** value_type=NUMBER 时的数值。 */
    private BigDecimal numericValue;
    /** 单位,如 mm / km / kWh。 */
    private String unit;
    /** value_type=ENUM 时的枚举(有/无/可选装)。 */
    private String enumValue;
    /** value_type=LIST 时的列表。 */
    private List<String> listValues;
    /** 清洗方式:RULE / AI / RULE_AI。 */
    private String cleanMethod;
    /** AI 清洗置信度(0-1)。 */
    private BigDecimal confidence;
}
