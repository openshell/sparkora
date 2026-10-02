package com.sparkora.ai;

import com.sparkora.config.AiProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C2 结构化输出契约单测：本地 stub HTTP 走真实 Spring AI OpenAI SDK，验证
 *  - schema 由 DTO 类型单一派生（{@link AiClient#jsonSchema}）；
 *  - 缺字段/多余字段响应经 {@code validateSchema} 自纠错后**收到具体校验错误**并重试成功；
 *  - 自纠错后 model/totalTokens/finishReason/reasoning 仍透传（{@link AiClient.ChatResult} 可用）；
 *  - 截断（finish_reason=length）抛截断异常，与 schema 违规解耦（交服务层提额重试）。
 */
class AiClientStructuredTest {

    private HttpServer server;
    private final List<String> requests = new ArrayList<>();
    private final AtomicReference<String> content = new AtomicReference<>("{}");
    private final AtomicReference<String> finish = new AtomicReference<>("stop");

    private static final String VALID = "{\"titleCandidates\":[\"a\"],\"audienceRefine\":\"x\","
            + "\"coreViewpoints\":[\"v\"],\"outline\":[{\"heading\":\"h\",\"subPoints\":[\"s\"]}],"
            + "\"factRisks\":[{\"claim\":\"c\",\"riskLevel\":\"low\",\"suggestion\":\"s\"}]}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            requests.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            // 按 JSON 规范转义:反斜杠/引号/控制字符——控制字符需编码为 \\n 等,SDK 解码后 message.content
            // 才会还原出真实裸换行,从而驱动 sanitizeAiJson 的转义路径(模拟模型偶发违规输出)。
            String esc = content.get().replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
            String resp = "{\"id\":\"c\",\"choices\":[{\"index\":0,\"message\":{\"content\":\"" + esc
                    + "\",\"reasoning\":\"推理X\"},\"finish_reason\":\"" + finish.get() + "\"}],\"model\":\"m\","
                    + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2,\"total_tokens\":3}}";
            byte[] r = resp.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, r.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(r); }
        });
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    private AiClient client() {
        AiProperties p = new AiProperties();
        p.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        p.setApiKey("k");
        p.setModel("test-model");
        return new AiClient(p);
    }

    /** AC-结构化自纠错：缺字段的响应被校验拒绝 → 第二次请求携带具体错误 → 补全后成功。 */
    @Test
    void 缺字段响应_收到具体校验错误后重试成功() {
        // 首次缺 titleCandidates 等必填字段；若第二次调用(由 advisor 触发)才返回合法结果
        final int[] n = {0};
        content.set("{\"audienceRefine\":\"x\"}");   // 首次非法
        // 用可变内容：stub 每次请求按 n 选择
        server.removeContext("/v1/chat/completions");
        server.createContext("/v1/chat/completions", ex -> {
            requests.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            n[0]++;
            String c = n[0] == 1 ? "{\"audienceRefine\":\"x\"}" : VALID;
            String esc = c.replace("\\", "\\\\").replace("\"", "\\\"");
            String resp = "{\"id\":\"c\",\"choices\":[{\"index\":0,\"message\":{\"content\":\"" + esc
                    + "\"},\"finish_reason\":\"stop\"}],\"model\":\"m\","
                    + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2,\"total_tokens\":3}}";
            byte[] r = resp.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, r.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(r); }
        });

        AiClient.TypedResult<BriefDto> tr = client().structured("S", "U", 100, BriefDto.class);

        assertNotNull(tr.entity(), "自纠错后应得到 entity");
        assertEquals(List.of("a"), tr.entity().getTitleCandidates());
        assertEquals(2, requests.size(), "首次违规应触发一次自纠错重试");
        assertTrue(requests.get(1).contains("JSON validation failed"),
                "第二次请求必须回填具体校验错误: " + requests.get(1));
        assertEquals("m", tr.chat().model());
        // 自纠错为两轮调用,UsageAccumulator 累加两轮 usage(3+3=6),元数据仍透传
        assertEquals(6, tr.chat().totalTokens(), "自纠错两轮的 usage 应累加透传");
        assertEquals("stop", tr.chat().finishReason());
    }

    /** AC:类型错误（titleCandidates 应 array 却给 string）经校验拒绝 → 收到具体错误后重试成功。 */
    @Test
    void 类型错误响应_收到具体校验错误后重试成功() {
        server.removeContext("/v1/chat/completions");
        server.createContext("/v1/chat/completions", ex -> {
            requests.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int n = requests.size();
            // 首次 titleCandidates 为字符串（类型错）
            String c = n == 1 ? "{\"titleCandidates\":\"not-an-array\"}" : VALID;
            String esc = c.replace("\\", "\\\\").replace("\"", "\\\"");
            String resp = "{\"id\":\"c\",\"choices\":[{\"index\":0,\"message\":{\"content\":\"" + esc
                    + "\"},\"finish_reason\":\"stop\"}],\"model\":\"m\","
                    + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2,\"total_tokens\":3}}";
            byte[] r = resp.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, r.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(r); }
        });

        AiClient.TypedResult<BriefDto> tr = client().structured("S", "U", 100, BriefDto.class);
        assertEquals(2, requests.size(), "类型错应触发一次自纠错");
        assertTrue(requests.get(1).contains("JSON validation failed"), "第二次请求携带具体校验错误");
        assertEquals(List.of("a"), tr.entity().getTitleCandidates());
    }

    /** 多余字段同样触发校验自纠错（schema 单一来源 = DTO，additionalProperties=false）。 */
    @Test
    void 多余字段响应_触发自纠错() {
        final int[] n = {0};
        server.removeContext("/v1/chat/completions");
        server.createContext("/v1/chat/completions", ex -> {
            requests.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            n[0]++;
            String c = n[0] == 1 ? "{\"titleCandidates\":[\"a\"],\"audienceRefine\":\"x\","
                    + "\"coreViewpoints\":[\"v\"],\"outline\":[{\"heading\":\"h\",\"subPoints\":[\"s\"]}],"
                    + "\"factRisks\":[{\"claim\":\"c\",\"riskLevel\":\"low\",\"suggestion\":\"s\"}],\"bogus\":1}" : VALID;
            String esc = c.replace("\\", "\\\\").replace("\"", "\\\"");
            String resp = "{\"id\":\"c\",\"choices\":[{\"index\":0,\"message\":{\"content\":\"" + esc
                    + "\"},\"finish_reason\":\"stop\"}],\"model\":\"m\","
                    + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2,\"total_tokens\":3}}";
            byte[] r = resp.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, r.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(r); }
        });

        AiClient.TypedResult<BriefDto> tr = client().structured("S", "U", 100, BriefDto.class);
        assertEquals(2, requests.size(), "多余字段应触发一次自纠错");
        assertTrue(requests.get(1).contains("JSON validation failed"), "携带校验错误");
        assertNotNull(tr.entity());
    }

    /** 截断（finish_reason=length）抛截断 AiException，与 schema 违规解耦（服务层据此提额重试）。 */
    @Test
    void 截断响应_抛截断异常而非schema异常() {
        content.set("{\"titleCandidates\":[\"a\"");   // 半截 JSON
        finish.set("length");

        AiException ex = assertThrows(AiException.class,
                () -> client().structured("S", "U", 100, BriefDto.class));
        assertTrue(ex.getMessage().contains("截断"), "应为截断专用异常: " + ex.getMessage());
        // 锁定已知代价:advisor 在 parseChat 之前,半截 JSON 触发一次同额度自纠错(=2 次同额度调用),
        // 之后才由 parseChat 抛截断;服务层再据此提额重试。改行为须同步更新 AiClient.structured 注释。
        assertEquals(2, requests.size(), "截断路径应恰好 2 次同额度调用(含 advisor 一次自纠错)");
    }

    /** 自纠错后 model/token/reasoning 透传，ChatResult 契约可用。 */
    @Test
    void 自纠错后_元数据与reasoning保留() {
        content.set(VALID);
        finish.set("stop");
        AiClient.TypedResult<BriefDto> tr = client().structured("S", "U", 100, BriefDto.class);
        assertEquals("m", tr.chat().model());
        assertEquals(3, tr.chat().totalTokens());
        assertEquals("stop", tr.chat().finishReason());
        assertEquals("推理X", tr.chat().reasoning());
    }

    /** 围栏剥离:模型无视「不要包代码块围栏」时仍可解析。 */
    @Test
    void 围栏响应_sanitize剥离后可解析() {
        content.set("```json\n" + VALID + "\n```");
        finish.set("stop");
        AiClient.TypedResult<BriefDto> tr = client().structured("S", "U", 100, BriefDto.class);
        assertEquals(List.of("a"), tr.entity().getTitleCandidates(), "```json 围栏应被剥离后解析成功");
    }

    /** 裸控制字符:字符串值内的裸换行(Jackson 严格模式会报 CTRL-CHAR)经 sanitize 转义后可解析。 */
    @Test
    void 裸控制字符响应_sanitize转义后可解析() {
        // 字符串值内嵌真实换行/制表(非法 JSON 控制字符),模拟模型偶发违规
        content.set("{\"titleCandidates\":[\"a\"],\"audienceRefine\":\"第一行\n第二行\t缩进\","
                + "\"coreViewpoints\":[\"v\"],\"outline\":[{\"heading\":\"h\",\"subPoints\":[\"s\"]}],"
                + "\"factRisks\":[{\"claim\":\"c\",\"riskLevel\":\"low\",\"suggestion\":\"s\"}]}");
        finish.set("stop");
        AiClient.TypedResult<BriefDto> tr = client().structured("S", "U", 100, BriefDto.class);
        assertTrue(tr.entity().getAudienceRefine().contains("第一行"),
                "裸换行应被转义、内容保留: " + tr.entity().getAudienceRefine());
    }

    /** schema 由三个 DTO 类型单一派生且含各自关键字段。 */
    @Test
    void 三DTO_schema可派生且含关键字段() {
        String brief = AiClient.jsonSchema(BriefDto.class);
        assertTrue(brief.contains("titleCandidates"));
        assertTrue(brief.contains("factRisks"));

        String clarify = AiClient.jsonSchema(ClarifyPlanDto.class);
        assertTrue(clarify.contains("keyQuestions"));
        assertTrue(clarify.contains("toolHints"));
        assertTrue(clarify.contains("questions"));

        String facts = AiClient.jsonSchema(SubAgentFactsDto.class);
        assertTrue(facts.contains("facts"));
        assertTrue(facts.contains("sourceId"));
        assertTrue(facts.contains("confidence"));
    }
}
