package com.sparkora.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * BYD 王朝本地推送与正文媒体暂存配置。
 *
 * <p>媒体文件只落服务器数据卷，Agent 通过任务范围接口读取；URL 配置不得用于把任意远端
 * 文件交给 Agent 直接访问。</p>
 */
@Data
@ConfigurationProperties(prefix = "sparkora.byd.dynasty")
public class BydDynastyProperties {
    /** 本机 Agent 访问 Server 的共享密钥；部署时禁止空值。 */
    private String agentToken;

    /** Agent 绑定的宿主用户 id；Agent 只能领取该用户创建的任务。 */
    private Long agentOwnerUserId;

    /** 心跳超时；超过后注册表记为 OFFLINE。 */
    private long heartbeatTimeoutMs = 60000;

    /** BYD 车友会默认标签；可为空。 */
    private String defaultClubId;
    private String defaultClubTitle;

    /** BYD 正文媒体根目录；必须落在现有 ./data 数据卷内。 */
    private String mediaStorageDir = "./data/byd-staging";
    /** 单张图片上限（MB）。 */
    private int mediaMaxImageMb = 20;
    /** 单个视频上限（MB）。 */
    private int mediaMaxVideoMb = 300;
    /** 每篇文章最多视频数。 */
    private int mediaMaxVideos = 3;
    /** 未被任务引用时的默认暂存有效期。 */
    private long mediaTtlHours = 168;
    /** 创建任务后至少保留的媒体时长，覆盖 Agent 执行窗口。 */
    private long mediaJobRetentionHours = 72;
    /** 过期媒体清理 cron。 */
    private String mediaCleanupCron = "0 17 * * * ?";

    /** Agent 专属配置是否齐备。 */
    public boolean agentConfigured() {
        return agentToken != null && !agentToken.isBlank() && agentOwnerUserId != null;
    }

    public Path mediaStorageRoot() {
        return Paths.get(mediaStorageDir == null ? "./data/byd-staging" : mediaStorageDir)
                .toAbsolutePath().normalize();
    }

    public long maxImageBytes() {
        return Math.max(1, mediaMaxImageMb) * 1024L * 1024L;
    }

    public long maxVideoBytes() {
        return Math.max(1, mediaMaxVideoMb) * 1024L * 1024L;
    }
}
