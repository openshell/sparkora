package com.sparkora.car.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * 比亚迪官网 getGoodsInfoById 响应 DTO。
 * 只取入库所需字段,其余忽略。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class GoodsInfoDto {
    private GoodsInfoList goodsinfoList;

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    public static class GoodsInfoList {
        private List<Item> items;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    public static class Item {
        private String id;                 // goodsId
        private String salesNetworkName;   // 王朝 / 海洋
        private String name;               // 大唐EV
        private String vehicleId;
        private String price;              // "239,900 - 309,900"
        private List<String> features;     // 卖点
        private List<String> introduce;    // 图片 URL
        private DetailPage detailPage;
        private CarRights carRights;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    public static class DetailPage {
        private String _path;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    public static class CarRights {
        private String title;
        private List<String> content;
    }
}
