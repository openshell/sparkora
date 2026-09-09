package com.sparkora.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统级检索设置实体。对应 sparkora_setting,单行表(固定 id=1)。
 * 页面控制内部知识库/外部搜索的启用;生成链路运行时经 SettingService 读取(带内存缓存)。
 * 设计:09-09-brief-gen-redesign §2.1。
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
    /** 最近修改人用户 id */
    private Long updatedBy;
    private LocalDateTime updatedAt;
    /** 逻辑删除(全局配置惯例:SMALLINT) */
    private Integer deleted;
}