package com.sparkora.domain.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * 图片信息来源追溯响应（09-15 img-classify，GET /api/images/{id}/source）。
 *
 * 注意：全局 Jackson 配置为 `default-property-inclusion: non_null`，null 字段默认不出现在 JSON 中；
 * 但本接口契约要求非新闻图（upload / AI 生成图 / 车型图）显式返回 `sourceRef: null` + `news: null`
 * （前端据此判定不展示来源行），故这两个字段用 `@JsonInclude(ALWAYS)` 强制输出。
 */
public record ImageSourceDTO(
        @JsonInclude(JsonInclude.Include.ALWAYS) String sourceRef,
        @JsonInclude(JsonInclude.Include.ALWAYS) NewsRef news,
        String imageUrl) {

    /** 来源新闻元信息（source_ref 为官方 news_id 且能反查到新闻时非空）。 */
    public record NewsRef(Long id, String newsId, String title, LocalDateTime publishDate, String url) {
    }
}
