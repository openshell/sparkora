package com.sparkora.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 信源注册表实体(10-05-source-crawl-base)。对应 sparkora_source。
 *
 * <p>一个源 = 一个站点/机构;一个源 ≥1 个栏目({@link SourceChannelEntity});列表地址/分类/选择器下沉到栏目级。
 * 只挂一个栏目的源行为等价「一源一列表页」(零回归)。排期支持单点 cron 或发布窗口(window_start_day/window_end_day)。
 */
@Data
@TableName("sparkora_source")
public class SourceEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;             // 源名(工信部/乘联会/盖世/...)
    private String type;             // RSS | SITE
    private String vertical;         // 汽车/政策/...(预留)
    private String cron;             // 每源 cron(动态注册,源级默认)
    private Integer windowStartDay;  // 发布窗口起始日(可选)
    private Integer windowEndDay;    // 发布窗口结束日(可选;窗口内每日触发)
    private String authorityTier;    // official|industry|media|ugc(F 用;默认不启用分档)
    private Boolean needCrawl4ai;    // 源级默认是否需 Crawl4AI(可被栏目覆盖)
    private Boolean enabled;
    private String lastBatchKey;     // 发布窗口本批完成标记(sourceId+年月)
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;

    /** 非持久化派生字段:源下栏目(列表/详情接口填充)。 */
    @TableField(exist = false)
    private List<SourceChannelEntity> channels;
    /** 非持久化派生字段:栏目数(列表接口填充)。 */
    @TableField(exist = false)
    private Long channelCount;
    /**
     * 非持久化派生字段:下次运行时间(G6/R11 运维面板计划可视化;由 {@code SourceScheduleService} 只读计算,
     * 停用/总开关关闭/无排期时为 null)。
     */
    @TableField(exist = false)
    private LocalDateTime nextRunAt;
}
