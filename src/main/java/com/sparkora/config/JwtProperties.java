package com.sparkora.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 配置。对应 .env: JWT_SECRET / JWT_EXPIRE_MINUTES。
 */
@Data
@ConfigurationProperties(prefix = "sparkora.jwt")
public class JwtProperties {
    private String secret;
    private long expireMinutes = 1440;
}
