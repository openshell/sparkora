package com.sparkora.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sparkora.config.QiniuProperties;
import com.sparkora.domain.entity.ImageAssetEntity;
import com.sparkora.mapper.ImageAssetMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 七牛图床服务(S4)。轻量自研接入:签名直传,不引 qiniu SDK。
 *
 * 职责:
 *  - ensureUploaded(imageId):把本地配图懒转存七牛(幂等:qiniu_key 已存在直接复用;同 key 覆盖上传天然幂等);
 *  - publicUrlByAsset:由 key 拼公网 URL(浏览器预览可见 + wenyan-server 可拉);
 *  - delete(imageId):删除记录后同步删对象(非阻塞,失败仅 warn)。
 *
 * key 策略:sparkora/{imageId}.{ext} —— 服务端生成,无用户可控成分。
 * 失败语义:转存失败抛 RuntimeException(控制器转 R.fail(500, 中文));enabled=false 时由调用方决定降级。
 */
@Slf4j
@Service
public class QiniuService {

    private final QiniuProperties props;
    private final ImageAssetMapper imageMapper;
    private final ImageService imageService;   // @Lazy:与 ImageService.deleteObject 互引,懒加载断环
    private final RestClient rest;

    public QiniuService(QiniuProperties props, ImageAssetMapper imageMapper,
                        @org.springframework.context.annotation.Lazy ImageService imageService) {
        this.props = props;
        this.imageMapper = imageMapper;
        this.imageService = imageService;
        this.rest = RestClient.builder()
                .requestFactory(org.springframework.boot.web.client.ClientHttpRequestFactories.get(
                        org.springframework.boot.web.client.ClientHttpRequestFactorySettings.DEFAULTS
                                .withConnectTimeout(Duration.ofSeconds(10))
                                .withReadTimeout(Duration.ofSeconds(30))))
                .build();
    }

    /** 七牛是否可用(配置齐备)。 */
    public boolean configured() {
        return props.isEnabled() && props.configured();
    }

    /** 确保资产已转存七牛,返回公网 URL。已转存(qiniu_key 非空)直接拼 URL,不重复上传。 */
    public String ensureUploaded(Long imageId) {
        ImageAssetEntity img = imageMapper.selectById(imageId);
        if (img == null) throw new IllegalArgumentException("图片不存在: " + imageId);
        return ensureUploadedByAsset(img);
    }

    /** 按本地相对存储路径(如 2026/08/uuid.png)查资产并转存(正文引用 URL 化用)。 */
    public String ensureUploadedByStoragePath(String storagePath) {
        ImageAssetEntity img = imageMapper.selectOne(new QueryWrapper<ImageAssetEntity>()
                .eq("storage_path", storagePath).last("limit 1"));
        if (img == null) throw new IllegalArgumentException("图库中不存在该图片: " + storagePath);
        return ensureUploadedByAsset(img);
    }

    private String ensureUploadedByAsset(ImageAssetEntity img) {
        if (img.getQiniuKey() != null && !img.getQiniuKey().isBlank()) {
            return props.publicUrl(img.getQiniuKey());
        }
        if (!configured()) throw new IllegalStateException("图床未配置(QINIU_ACCESS_KEY/QINIU_SECRET_KEY)");
        byte[] bytes = imageService.readLocalBytes(img.getStoragePath());
        String ext = includeExtOf(img.getStoragePath());
        String key = "sparkora/" + img.getId() + "." + ext;
        doUpload(key, bytes, "image/" + (ext.equals("jpg") ? "jpeg" : ext));
        img.setQiniuKey(key);
        imageMapper.updateById(img);
        String url = props.publicUrl(key);
        log.info("图床转存 image={} → {}（{}KB）", img.getId(), url, bytes.length / 1024);
        return url;
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
    public void deleteObject(String key) {
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

    private static String includeExtOf(String storagePath) {
        String name = storagePath;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "png" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        if (!Set_of("png", "jpg", "jpeg", "webp", "gif").contains(ext)) ext = "png";
        return ext.equals("jpeg") ? "jpg" : ext;
    }

    private static java.util.Set<String> Set_of(String... items) {
        return new java.util.HashSet<>(java.util.Arrays.asList(items));
    }
}