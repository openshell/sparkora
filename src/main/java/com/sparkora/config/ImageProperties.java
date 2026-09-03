package com.sparkora.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 图片配置。对应 .env: IMAGE_STORAGE_DIR（数据盘目录，用于 wenyan 渲染临时文件落位）、IMAGE_MAX_UPLOAD_MB。
 * S6 起图片不再落本地，storageDir 仅作数据盘临时目录（createTempMd 用）。
 */
@Data
@ConfigurationProperties(prefix = "sparkora.image")
public class ImageProperties {
    /** 数据盘目录（wenyan 渲染临时文件落位；不再存图片）。 */
    private String storageDir = "./data/images";
    /** 上传大小上限（MB）。 */
    private int maxUploadMb = 10;

    public Path storageRoot() { return Paths.get(storageDir).toAbsolutePath().normalize(); }
}