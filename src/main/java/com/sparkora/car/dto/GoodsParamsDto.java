package com.sparkora.car.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * 比亚迪官网 goodsParams 响应 DTO（完整参数表,核心数据源）。
 * 结构:data.configs[] = 参数分组;configs[].value[] = 参数行;
 *      参数行.value[] 下标与「车型」行下标一一对应(第 i 个值属于第 i 个车型版本)。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class GoodsParamsDto {
    private ParamsData data;

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    public static class ParamsData {
        private String title;
        private String remark;
        private List<Config> configs;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    public static class Config {
        private String name;          // 分组名,如 尺寸参数
        private String id;
        private List<ParamRow> value;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    public static class ParamRow {
        private String name;          // 参数名,如 长×宽×高(mm)
        private String id;
        private List<String> value;   // 全版本值,下标对齐「车型」行
    }
}
