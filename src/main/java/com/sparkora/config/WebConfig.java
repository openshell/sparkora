package com.sparkora.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;


/**
 * Web MVC 配置：把 /images/** 静态映射到本地图片存储目录（S3b）。
 * 图片 URL 形如 /images/2026/08/uuid.png → {IMAGE_STORAGE_DIR}/2026/08/uuid.png。
 * 该路径不在 /api 下，SecurityConfig 中本来就 permitAll（anyRequest().permitAll()）。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ImageProperties imageProps;

    public WebConfig(ImageProperties imageProps) { this.imageProps = imageProps; }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // toUri() 生成规范 file URI(带尾斜杠、Windows 盘符前有 /),避免手拼 "file:" + path
        // 在 Windows 上丢盘符斜杠导致资源映射失效
        String location = imageProps.storageRoot().toUri().toString();
        registry.addResourceHandler("/images/**").addResourceLocations(location);
    }
}