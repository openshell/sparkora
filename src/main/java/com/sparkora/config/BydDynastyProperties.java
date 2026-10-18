package com.sparkora.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * BYD 王朝本地推送配置。
 *
 * Server 使用 Agent Token 做专属鉴权；Agent 绑定固定宿主用户，避免跨账号拉取任务。
 */
@Data
@ConfigurationProperties(prefix = "sparkora.byd.dynasty")
public class BydDynastyProperties {
    /** 本机 Agent 访问 Server 的共享密钥；部署时禁止空值。 */
    private String agentToken;

    /** Agent 绑定的宿主用户 id；Agent 只能领取该用户创建的任务。 */
    private Long agentOwnerUserId;

    /** 心跳超时；超过后注册表现记为 OFFLINE。 */
    private long heartbeatTimeoutMs = 60000;

    /** BYD 车友会默认标签；可为空。 */
    private String defaultClubId;
    private String defaultClubTitle;

    /** Agent 专属配置是否齐备。 */
    public boolean agentConfigured() {
        return agentToken != null && !agentToken.isBlank() && agentOwnerUserId != null;
    }
}
