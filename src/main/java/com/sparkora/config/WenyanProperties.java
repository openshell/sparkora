package com.sparkora.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * wenyan-mcp 配置。对应 .env: WENYAN_MCP_*。S0 默认 disabled，S4 启用。
 */
@Data
@ConfigurationProperties(prefix = "sparkora.wenyan")
public class WenyanProperties {
    private boolean enabled = false;
    private String bin = "wenyan-mcp";
    private String serverUrl;
    private String serverApiKey;
}
