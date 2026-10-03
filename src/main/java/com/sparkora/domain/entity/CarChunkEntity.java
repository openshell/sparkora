package com.sparkora.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 车型块实体。对应 sparkora_car_chunk（E4 块语义重命名）。
 * RAG 检索单元;chunk_type: MODEL_INFO / PARAM_GROUP / RIGHTS / FEATURE。
 * 切分粒度:仅 PARAM_GROUP(每参数分组一个块)。
 */
@Data
@TableName("sparkora_car_chunk")
public class CarChunkEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long modelId;
    private Long versionId;        // 可空:全局块为空,版本专属块指向版本
    private Long groupId;          // 可空:来源参数分组
    private String chunkType;      // MODEL_INFO / PARAM_GROUP / RIGHTS / FEATURE
    private String chunkText;      // 切分后的文本块(喂给 embedding 的原文)
    private Integer tokenCount;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
    /** 非持久化:车型名(构建期填充,写 vector_store metadata.name 用,不落 sparkora_car_chunk）。 */
    @TableField(exist = false)
    private String modelName;
}
