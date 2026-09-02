package com.sparkora.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 车型知识库配置。对应 .env: CAR_*。
 * 数据源为比亚迪官网公开 JSON API(无鉴权)。
 */
@Data
@ConfigurationProperties(prefix = "sparkora.car")
public class CarProperties {
    /** 车型目录接口(全车型列表)。 */
    private String goodsListUrl = "https://cms-api.byd.com/car/byd/cn/goodsListForSearch";
    /** 车型基础信息接口。 */
    private String goodsInfoUrl = "https://cms-api.byd.com/car/byd/cn/getGoodsInfoById";
    /** 完整参数表接口(核心)。 */
    private String goodsParamsUrl = "https://site-api.byd.com/domestic-official-api/goods/goodsParams";
    /** 版本/外观/内饰/价格接口。 */
    private String goodsAttrListUrl = "https://cms-api.byd.com/car/byd/cn/getGoodsAttrListForCompareByGoodsId";
    /** 采集 HTTP 读超时(毫秒)。 */
    private long timeoutMs = 30000;
    /** goodsParams 接口 HMAC 签名 key(官网 JS 硬编码,可覆盖)。 */
    private String hmacSignKey = "4a3688a5gcd88g443fga6b7fcb";
    /** goodsParams 接口 HMAC 签名 secret(官网 JS 硬编码,可覆盖)。 */
    private String hmacSecretKey = "fcb8f0ddg5c92g45b7g9d33g04cc55d3be3b";
    /** 定时同步开关。 */
    private boolean syncEnabled = false;
    /** 定时同步 cron(默认每日 03:00)。 */
    private String syncCron = "0 0 3 * * ?";
}
