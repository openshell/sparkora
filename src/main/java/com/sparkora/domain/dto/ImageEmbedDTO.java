package com.sparkora.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 图片语义检索入参（09-15 img-semantic-search，子B；POST /api/images/search）。
 *
 * query 必填非空；topK/minScore/tags 可选（默认值与非法规约由服务层收敛，不报错）。
 * tags 为**标签 AND 预过滤**（先按标签缩候选，再在候选内做向量排序），语义与 `GET /api/images` 一致。
 */
@Data
public class ImageEmbedDTO {
    /** 自然语言检索文本（如「销量海报」）。 */
    @NotBlank(message = "检索内容不能为空")
    private String query;
    /** 返回条数上限（默认 10，上限 50；<1 用默认，>50 收敛为 50，均不报错）。 */
    private Integer topK;
    /** 相似度门槛（null 时用 AI_IMAGE_MIN_SCORE，默认 0.3）。 */
    private Double minScore;
    /** 标签预过滤（AND 语义，须同时具备全部标签；空/null = 不筛）。 */
    private List<String> tags;
}
