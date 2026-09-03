package com.sparkora.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 七牛图床配置。对应 .env: QINIU_*(兼容旧裸名 AK/SK)。
 * 用途(S4):预览/发布前把本地配图转存到七牛,得到公网 URL 供浏览器预览与 wenyan-server 拉取。
 * 实测事实:caiqz bucket 在 z2 区域(上传走 up-z2.qiniup.com),外链域名 http://pic.caiqz.cn(无 https 证书)。
 */
@Data
@ConfigurationProperties(prefix = "sparkora.qiniu")
public class QiniuProperties {
    /** 开关:AK 未配置时为 false,上传/生成直接报错(图床未配置)。 */
    private boolean enabled;
    private String accessKey;
    private String secretKey;
    private String bucket = "caiqz";
    /** 上传端点(z2 区域实测;其余区域 http://up-z0/u-north 等)。 */
    private String uploadHost = "http://up-z2.qiniup.com";
    /** 外链 CDN 域名(尾部不带 /)。 */
    private String publicDomain = "http://pic.caiqz.cn";
    /** 上传令牌有效期(秒)。 */
    private long tokenTtlSeconds = 3600;

    public boolean configured() {
        return accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank();
    }

    /** 由 key 拼公网 URL。 */
    public String publicUrl(String key) {
        return publicDomain + "/" + key;
    }

    /**
     * 生成「指定 key 的覆盖上传」令牌:AK:sign:data。
     * sign = urlsafe_b64(HMAC-SHA1(SK, urlsafe_b64(putPolicy)));putPolicy 含 scope=bucket:key(覆盖语义)。
     */
    public String uploadToken(String key) {
        long deadline = System.currentTimeMillis() / 1000 + tokenTtlSeconds;
        String putPolicy = "{\"scope\":\"" + bucket + ":" + key + "\",\"deadline\":" + deadline + "}";
        String encoded = base64Url(putPolicy.getBytes(StandardCharsets.UTF_8));
        byte[] mac = hmacSha1(secretKey, encoded.getBytes(StandardCharsets.UTF_8));
        String sign = base64Url(mac);
        return accessKey + ":" + sign + ":" + encoded;
    }

    /** 管理 API 签名头:QBox AK:b64url(HMAC-SHA1(SK, path + "\n" + body))。 */
    public String qboxAuthorization(String path, byte[] body) {
        byte[] data = path.getBytes(StandardCharsets.UTF_8);
        if (body != null && body.length > 0) {
            byte[] merged = new byte[data.length + 1 + body.length];
            System.arraycopy(data, 0, merged, 0, data.length);
            merged[data.length] = '\n';
            System.arraycopy(body, 0, merged, data.length + 1, body.length);
            data = merged;
        }
        return "QBox " + accessKey + ":" + base64Url(hmacSha1(secretKey, data));
    }

    private static byte[] hmacSha1(String secretKey, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA1 签名失败: " + e.getMessage(), e);
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().encodeToString(bytes);
    }
}