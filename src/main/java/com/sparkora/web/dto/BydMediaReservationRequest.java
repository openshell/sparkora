package com.sparkora.web.dto;

import lombok.Data;

import java.math.BigDecimal;

/** 预留一条 BYD 小程序正文媒体。 */
@Data
public class BydMediaReservationRequest {
    /** IMAGE / VIDEO；大小写不敏感。 */
    private String mediaType;
    private String fileName;
    private String mimeType;
    private Long byteSize;
    private Integer width;
    private Integer height;
    private BigDecimal durationSeconds;
}
