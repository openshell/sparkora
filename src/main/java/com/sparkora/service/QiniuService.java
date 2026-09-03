package com.sparkora.service;

import com.sparkora.config.QiniuProperties;
import com.sparkora.storage.ImageStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 七牛图床实现（S6 图库完全依赖图床）。轻量自研接入:签名直传,不引 qiniu SDK。
 *
 * 职责（实现 {@link ImageStorage}）:
 *  - upload(bytes, ext):把图片字节直接上传七牛,返回 key(服务端生成,无用户可控成分);
 *  - publicUrl(key):由 key 拼公网 URL(浏览器预览可见 + wenyan-server 可拉);
 *  - delete(key):删除对象(非阻塞,失败仅 warn)。
 *
 * key 策略:sparkora/{uuid}.{ext} —— 服务端生成,无用户可控成分。
 * 失败语义:上传失败抛 RuntimeException(控制器转 R.fail(500, 中文));configured=false 时由调用方决定降级。
 */
@Slf4j
@Service
public class QiniuService implements ImageStorage {

    private final QiniuProperties props;
    private final RestClient rest;

    public QiniuService(QiniuProperties props) {
        this.props = props;
        this.rest = RestClient.builder()
                .requestFactory(org.springframework.boot.web.client.ClientHttpRequestFactories.get(
                        org.springframework.boot.web.client.ClientHttpRequestFactorySettings.DEFAULTS
                                .withConnectTimeout(Duration.ofSeconds(10))
                                .withReadTimeout(Duration.ofSeconds(30))))
                .build();
    }

    @Override
    public boolean configured() {
        return props.isEnabled() && props.configured();
    }

    /** 上传字节到七牛,返回 key。key=sparkora/{uuid}.{ext}(服务端生成,无用户可控成分)。 */
    @Override
    public String upload(byte[] bytes, String ext) {
        if (!configured()) throw new IllegalStateException("图床未配置(QINIU_ACCESS_KEY/QINIU_SECRET_KEY)");
        String key = "sparkora/" + java.util.UUID.randomUUID() + "." + ext;
        doUpload(key, bytes, "image/" + (ext.equals("jpg") ? "jpeg" : ext));
        log.info("图床上传成功 key={}（{}KB）", key, bytes.length / 1024);
        return key;
    }

    @Override
    public String publicUrl(String key) {
        return props.publicUrl(key);
    }

    /** 由 key 下载图床对象字节（图生图参考图等场景）。 */
    @Override
    public byte[] download(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("图床 key 为空");
        try {
            byte[] bytes = rest.get()
                    .uri(props.publicUrl(key))
                    .retrieve()
                    .body(byte[].class);
            if (bytes == null || bytes.length == 0) throw new IllegalStateException("图床对象为空: " + key);
            return bytes;
        } catch (Exception e) {
            throw new RuntimeException("图床下载失败: " + key + " - " + e.getMessage(), e);
        }
    }

    /** 上传字节到七牛(multipart token/key/file;错误信息中文冒泡)。 */
    private void doUpload(String key, byte[] bytes, String mime) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("token", props.uploadToken(key));
        body.add("key", key);
        body.add("file", new ByteArrayResource(bytes) {
            @Override public String getFilename() {
                int slash = key.lastIndexOf('/');
                return slash < 0 ? key : key.substring(slash + 1);
            }
        });
        try {
            byte[] respBytes = rest.post()
                    .uri(props.getUploadHost() + "/")
                    .contentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(byte[].class);
            String resp = respBytes == null ? "" : new String(respBytes, StandardCharsets.UTF_8);
            if (!resp.contains("\"key\"") && !resp.contains("\"hash\""))
                throw new IllegalStateException("七牛返回异常: " + resp);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("图床上传失败: " + e.getMessage(), e);
        }
    }

    /** 删除七牛对象(非阻塞:失败仅告警)。 */
    @Override
    public void delete(String key) {
        if (!configured() || key == null || key.isBlank()) return;
        try {
            String entry = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString((props.getBucket() + ":" + key).getBytes(StandardCharsets.UTF_8));
            String path = "/delete/" + entry;
            byte[] resp = rest.post()
                    .uri("http://rs.qbox.me" + path)
                    .header("Authorization", props.qboxAuthorization(path, new byte[0]))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .retrieve()
                    .body(byte[].class);
            String msg = resp == null ? "" : new String(resp, StandardCharsets.UTF_8);
            // 200 空 body=删成功;612=对象已不存在,均视为成功
            if (!msg.isEmpty() && !msg.contains("\"id\"") && !msg.contains("614"))
                log.warn("七牛删除返回: {}", msg);
            log.info("图床对象已删除 key={}", key);
        } catch (Exception e) {
            log.warn("图床对象删除失败(忽略): {} {}", key, e.getMessage());
        }
    }
}
