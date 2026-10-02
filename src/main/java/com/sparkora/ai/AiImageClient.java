package com.sparkora.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.config.AiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.image.Image;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.openai.OpenAiImageModel;
import org.springframework.ai.openai.OpenAiImageOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

/**
 * 图片生成客户端：封装「多模型按序轮询」。
 *
 * 背景：axonhub 上的图片模型（如 gpt-image-2-url）不稳定，单个模型偶发超时/5xx。
 * AiProperties.imageModelList() 给出逗号分隔的候选模型，本类按序尝试：
 *  前一个非 4xx 业务错误（连接失败/超时/5xx）时切到下一个；遇 4xx（如鉴权/参数错）也切，避免卡死。
 *  全部失败才抛 AiException，异常信息列出每个模型的失败原因。
 *
 * 接口（OpenAI 兼容，axonhub）：
 *  - 文生图：POST /v1/images/generations  → { model, prompt, n, size }
 *  - 图生图：POST /v1/images/edits         → multipart: model, prompt, image, size, n
 *
 * 返回统一为图片 URL（gpt-image-2-url 这类模型返回 data[].url），若模型返回 base64 则转 data URL。
 *
 * <p><b>10-02-c6-image-model（C6）</b>：文生图改走 Spring AI 2.0 {@link ImageModel}
 * （自动配置的 {@code OpenAiImageModel}，仅调 {@code /v1/images/generations}）。旧实现用
 * {@code byte[]} 收响应是为了绕过 {@code RestClient} 的 String 转换器拒绝
 * {@code application/octet-stream}——官方 {@code com.openai} SDK 对该 Content-Type 包裹的
 * JSON 已实测可正常解析，故不再需要该 hack。图生图 {@code /v1/images/edits} multipart
 * （多参考图保序重复 image part）无 Spring AI 支持，**原样保留自研 RestClient**。
 */
@Slf4j
@Component
public class AiImageClient {

    private final AiProperties props;

    /** Spring AI 自动配置注入的图片模型（生产装配）；单测直 new 时为 null，首次调用按 AiProperties 自建。 */
    private final ImageModel imageModel;

    /** 仅图生图（/v1/images/edits）走的自研 RestClient（Spring AI 不支持 edits multipart）。 */
    private final RestClient rest;

    private final ObjectMapper mapper = new ObjectMapper();

    /** 单测/回退构造路径自建的图片模型（懒构建，避免未触发生图就构造网络客户端）。 */
    private volatile ImageModel fallbackImageModel;

    /** 生产装配：注入 Spring AI 自动配置的 {@link ImageModel}（spring.ai.openai.image.* 配置）。 */
    @Autowired
    public AiImageClient(AiProperties props, ImageModel imageModel) {
        this.props = props;
        this.imageModel = imageModel;
        this.rest = buildRest(props);
    }

    /**
     * 单测/回退构造器：无自动配置 {@link ImageModel}，首次调用文生图时按 {@link AiProperties}
     * 自建 OpenAI 兼容 {@code OpenAiImageModel}（base-url 归一化补 {@code /v1}）。
     * 保留既有 {@code new AiImageClient(props)} 用法（{@code AiImageClientMultiRefTest} 等直 new 单测）。
     */
    public AiImageClient(AiProperties props) {
        this.props = props;
        this.imageModel = null;
        this.rest = buildRest(props);
    }

    /** 自研 RestClient（仅供 edits）：读超时消费 AI_TIMEOUT_MS 放宽一倍，连接超时 10s。 */
    private static RestClient buildRest(AiProperties props) {
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(10))
                .withReadTimeout(Duration.ofMillis(props.getTimeoutMs() * 2));
        return RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.jdk().build(settings))
                .defaultHeader("Authorization", "Bearer " + props.getApiKey())
                .build();
    }

    /** 文生图：按序轮询候选模型，任一成功即返回 URL（http/https）或 data URL（base64）+ 实际命中模型名（S10 留档 gen_model）。 */
    public GenResult generateText2Image(String prompt, String size) {
        List<String> models = props.imageModelList();
        if (models.isEmpty()) throw new AiException("AI_IMAGE_MODELS / AI_IMAGE_MODEL 均未配置", null);
        String normSize = (size == null || size.isBlank()) ? "1024x1024" : size;
        StringBuilder errs = new StringBuilder();
        for (String model : models) {
            try {
                OpenAiImageOptions.Builder ob = OpenAiImageOptions.builder();
                ob.model(model);
                ob.n(1);
                ob.size(normSize);
                ImageResponse resp = imageModel().call(new ImagePrompt(prompt, ob.build()));
                String url = firstUrl(resp);
                log.info("文生图成功 model={} url={}", model, shorten(url));
                return new GenResult(url, model);
            } catch (Exception e) {
                log.warn("文生图模型 {} 失败，尝试下一个: {}", model, e.getMessage());
                errs.append("[").append(model).append("] ").append(e.getMessage()).append("; ");
            }
        }
        throw new AiException("所有图片模型均失败: " + errs, null);
    }

    /** 注入优先；单测/回退缺失时按 AiProperties 自建（懒构建，同 AiClient fallbackChatModel 规则补 /v1）。 */
    private ImageModel imageModel() {
        ImageModel m = imageModel;
        if (m != null) return m;
        ImageModel f = fallbackImageModel;
        if (f == null) {
            synchronized (this) {
                f = fallbackImageModel;
                if (f == null) {
                    OpenAiImageOptions.Builder ob = OpenAiImageOptions.builder();
                    ob.baseUrl(AiClient.normalizeBaseUrl(props.getBaseUrl()));
                    ob.apiKey(props.getApiKey());
                    ob.timeout(Duration.ofMillis(props.getTimeoutMs() * 2));
                    ob.maxRetries(0);
                    f = OpenAiImageModel.builder().options(ob.build()).build();
                    fallbackImageModel = f;
                }
            }
        }
        return f;
    }

    /** 从 ImageResponse 取第一张图：url 非空优先，否则 b64_json 转 data URL；都无视为该模型失败。 */
    private String firstUrl(ImageResponse resp) {
        if (resp == null || resp.getResult() == null || resp.getResult().getOutput() == null) {
            throw new AiException("图片返回无 data", null);
        }
        Image img = resp.getResult().getOutput();
        String url = img.getUrl();
        if (url != null && !url.isBlank()) return url;
        String b64 = img.getB64Json();
        if (b64 != null && !b64.isBlank()) return "data:image/png;base64," + b64;
        throw new AiException("图片返回无 url/b64_json", null);
    }

    /**
     * 图生图（多参考图，09-26 img2img-multi-ref）：multipart POST /v1/images/edits，
     * 对每张参考图追加一个重复的 {@code image} part（**保序**；网关实测支持重复 image part），
     * 再按序轮询候选模型。S10 起返回 URL + 实际命中模型名（留档 gen_model）。
     * @param refImageBytesList 参考图字节列表（保序，1~4 张；空集合抛 AiException）
     * @param refFileNames      参考图文件名列表（与字节**一一对应**同长；长度不匹配抛 AiException；null/空白回退 reference.png）
     */
    public GenResult generateImage2Image(String prompt, List<byte[]> refImageBytesList,
                                         List<String> refFileNames, String size) {
        List<String> models = props.imageModelList();
        if (models.isEmpty()) throw new AiException("AI_IMAGE_MODELS / AI_IMAGE_MODEL 均未配置", null);
        if (refImageBytesList == null || refImageBytesList.isEmpty())
            throw new AiException("图生图参考图为空", null);
        if (refFileNames == null || refFileNames.size() != refImageBytesList.size())
            throw new AiException("图生图参考图与文件名数量不匹配", null);
        for (byte[] bytes : refImageBytesList) {
            if (bytes == null || bytes.length == 0) throw new AiException("图生图参考图为空", null);
        }
        StringBuilder errs = new StringBuilder();
        for (String model : models) {
            try {
                MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
                body.add("model", model);
                body.add("prompt", prompt);
                if (size != null && !size.isBlank()) body.add("size", size);
                body.add("n", 1);
                // 保序重复 image part：第 i 张参考图对应第 i 个 part
                for (int i = 0; i < refImageBytesList.size(); i++) {
                    byte[] bytes = refImageBytesList.get(i);
                    String name = refFileNames.get(i);
                    body.add("image", new ByteArrayResource(bytes) {
                        @Override public String getFilename() {
                            return name == null || name.isBlank() ? "reference.png" : name;
                        }
                    });
                }
                String resp = postMultipartForJsonText("/v1/images/edits", body);
                String url = parseFirstUrl(resp);
                log.info("图生图成功 model={} refCount={} url={}", model, refImageBytesList.size(), shorten(url));
                return new GenResult(url, model);
            } catch (Exception e) {
                log.warn("图生图模型 {} 失败，尝试下一个: {}", model, e.getMessage());
                errs.append("[").append(model).append("] ").append(e.getMessage()).append("; ");
            }
        }
        throw new AiException("所有图片模型均失败(图生图): " + errs + "。若提示接口不存在，"
                + "说明该模型不支持 /v1/images/edits，请改用文生图或更换 AI_IMAGE_MODELS。", null);
    }

    /** 生成结果：图片 URL + 实际命中模型名（S10 留档用）。 */
    public record GenResult(String url, String model) {}

    /** multipart 版本（仅 edits）：以 byte[] 收取再转字符串，兼容网关偶发给 JSON 标 application/octet-stream。 */
    private String postMultipartForJsonText(String path, MultiValueMap<String, Object> multipart) {
        byte[] bytes = rest.post()
                .uri(path)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(multipart)
                .retrieve()
                .body(byte[].class);
        return bytes == null ? "" : new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 从 edits 响应取第一个 data[].url（或 b64_json，若模型返回 base64）。 */
    private String parseFirstUrl(String resp) {
        try {
            JsonNode data = mapper.readTree(resp).path("data");
            if (!data.isArray() || data.isEmpty()) {
                throw new AiException("图片返回无 data: " + resp, null);
            }
            JsonNode first = data.get(0);
            String url = first.path("url").asText("");
            if (!url.isBlank()) return url;
            String b64 = first.path("b64_json").asText("");
            if (!b64.isBlank()) return "data:image/png;base64," + b64;
            throw new AiException("图片返回无 url/b64_json: " + resp, null);
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException("解析图片返回失败: " + resp, e);
        }
    }

    /** 日志里长 URL/dataURL 截断，避免刷屏。 */
    private static String shorten(String s) {
        if (s == null) return "null";
        return s.length() > 80 ? s.substring(0, 80) + "…(" + s.length() + "B)" : s;
    }
}
