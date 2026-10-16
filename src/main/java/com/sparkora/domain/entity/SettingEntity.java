package com.sparkora.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统级检索设置实体。对应 sparkora_setting,单行表(固定 id=1)。
 * 页面控制内部知识库/外部搜索的启用;生成链路运行时经 SettingService 读取(带内存缓存)。
 * 设计:09-09-brief-gen-redesign(契约见 docs/spec/settings.md)。
 */
@Data
@TableName("sparkora_setting")
public class SettingEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 内部知识库启用(默认停用:知识库存疑,暂停引用,优先外部搜索) */
    private Boolean kbEnabled;
    /** 外部搜索启用(默认启用) */
    private Boolean webSearchEnabled;
    /**
     * 外部搜索 provider 顺序(运行时全局策略;逗号分隔,TAVILY,SEARXNG=TAVILY_FIRST)。
     * 09-25-brief-web-search:ADMIN 系统设置调整;空则回退部署级 DEEP_WEB_PROVIDER_ORDER。
     */
    private String webProviderOrder;
    /**
     * 车型库定时增量同步开关(10-15-car-image-backfill R3:运行时设置,免重启;
     * CarSyncScheduler 每分钟 tick 读取)。V22 列默认 FALSE;.env 的 CAR_SYNC_ENABLED
     * 降级为遗留种子(调度不再读取)。
     */
    private Boolean carSyncEnabled;
    /**
     * 车型库定时增量同步 cron(Spring 6 段格式,V22 列默认 0 0 3 * * ?)。
     * 合法性由 SettingService 的 CronExpression.parse 兜底(非法 → 400);改后下一 tick 重算到点。
     */
    private String carSyncCron;
    /** 最近修改人用户 id */
    private Long updatedBy;
    private LocalDateTime updatedAt;
    /** 逻辑删除(全局配置惯例:SMALLINT) */
    private Integer deleted;
}