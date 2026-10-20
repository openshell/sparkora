package com.sparkora.web.dto;

import lombok.Data;

/** 将当前项目图库中的已有图片登记成 BYD 兼容媒体（source_kind=URL）。 */
@Data
public class BydMediaFromImageRequest {
    private Long imageId;
}
