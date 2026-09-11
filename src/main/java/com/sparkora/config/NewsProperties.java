package com.sparkora.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 新闻知识域配置(C2)。对应 .env: NEWS_*。
 * 数据源:比亚迪官方 CMS 列表接口(POST /es/search)+ www.byd.com 详情页 SSR HTML。
 */
@Data
@ConfigurationProperties(prefix = "sparkora.news")
public class NewsProperties {
    /** 新闻列表接口(POST JSON,无鉴权)。 */
    private String listUrl = "https://cms-api.byd.com/es/search";
    /** 详情页站点前缀(详情 URL = 前缀 + 列表 url 相对路径)。 */
    private String detailBaseUrl = "https://www.byd.com";
    /** 采集 HTTP 读超时(毫秒)。 */
    private long timeoutMs = 30000;
    /** 列表分页大小(实测 size=12)。 */
    private int pageSize = 12;
    /** 定时增量同步开关(默认关闭,避免个人项目环境空跑)。 */
    private boolean syncEnabled = false;
    /** 定时增量同步 cron(默认每天 03:30,错开车型同步)。 */
    private String syncCron = "0 30 3 * * ?";
}
