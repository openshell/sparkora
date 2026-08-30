package com.sparkora.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 图片存储配置。对应 .env: IMAGE_STORAGE_DIR（图片本地存储目录，上传/AI 生成图统一转存于此）。
 */
@Data
@ConfigurationProperties(prefix = "sparkora.image")
public class ImageProperties {
    /** 本地存储目录；/images/** 静态映射该目录。 */
    private String storageDir = "./data/images";
    /** 上传大小上限（MB）。 */
    private int maxUploadMb = 10;

    public Path storageRoot() { return Paths.get(storageDir).toAbsolutePath().normalize(); }
}