package com.sparkora.car.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 比亚迪官网 getGoodsAttrListForCompareByGoodsId 响应 DTO。
 * 顶层是车型版本列表(含价格),每个版本含外观/内饰等。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class GoodsAttrListDto {
    private List<Version> data;

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    public static class Version {
        private String name;            // 版本名,如 205领航版
        private BigDecimal price;        // 129900
        private String priceRemark;      // "129,900起"
        private String type;             // 车型
    }
}
