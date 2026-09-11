package com.sparkora.domain.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 保存发布元信息请求(09-11-preview-publish-bridge):author/source_url 手填、项目级落库。
 * 字段可选:null 表示不改该项;允许空串(用户清空)。
 */
@Data
public class PublishMetaRequest {

    /** 发布 frontmatter author(≤100) */
    @Size(max = 100, message = "作者不能超过 100 字")
    private String author;

    /** 发布 frontmatter source_url(≤500) */
    @Size(max = 500, message = "原文地址不能超过 500 字")
    private String sourceUrl;
}
