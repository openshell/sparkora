package com.sparkora.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.config.AiProperties;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.StructuredOutputValidationAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI 文本客户端（C1 起收敛到 Spring AI {@code ChatClient}）。
 *
 * <p><b>10-02-c1-chatclient-prompt</b>：把原手写 RestClient 直连 chat completions 端点的
 * 实现替换为 Spring AI 2.0 {@link ChatClient} 委托；<b>公共 API（三方法 + {@link ChatResult} +
 * {@link #sanitizeAiJson} + {@link #REASONING_MAX_CHARS}）完全不变</b>，调用点与测试零改动。
 * 任务级参数：{@code chat}=正文创意高温、{@code chatJson}=结构化低温、{@code chatMessages}=问答中温。
 *
 * <p>设计要点（来自真机联调）：
 *  - axonhub 把模型名路由到实际模型（如 deepseek-v4-pro-cus → deepseek-v4.1-flash），无需关心。
 *  - 部分 GLM 系模型会先输出 reasoning_content 再输出 content；Spring AI 的
 *    {@code message.metadata["reasoningContent"]} 已做 {@code reasoning_content}→{@code reasoning} 回退，parseChat 据此透出。
 *  - 调用强制 response_format=json_object，要求模型返回纯 JSON，避免解析不稳。
 *  - 失败抛 AiException，由上层决定状态回滚与错误展示。
 */
@Slf4j
@Component
public class AiClient {

    /**
     * reasoning 落库前截断上限（10-02-brief-reasoning-maxtokens）：reasoning 模型单次推理可达
     * 上万字，直接落 TEXT 列既撑库又灌爆前端；统一截断到该上限。
     */
    public static final int REASONING_MAX_CHARS = 20000;

    /** Spring AI 把推理过程写入 {@code assistantMessage.metadata} 的 key（与 OpenAiChatModel.REASONING_CONTENT 同值）。 */
    private static final String REASONING_METADATA_KEY = "reasoningContent";

    private final AiProperties props;
    private final TaskChatOptionsFactory optionsFactory;
    private final ObjectProvider<MeterRegistry> meterRegistry;

    /** Spring AI 自动配置注入的 ChatModel；单测直 new 时为 null，回退到按 AiProperties 自建。 */
    private final ChatModel chatModel;

    /** 模型名以 .env（AiProperties.model，即 AI_MODEL）为准：单测直 new 无默认模型时提供，注入路径同值。 */
    private final String modelOverride;

    /** 懒构建的 ChatClient（线程安全双检锁；避免单测未触发调用时构造网络客户端）。 */
    private volatile ChatClient chatClient;

    /** Spring 注入构造器：复用自动配置的 {@link ChatModel}（spring.ai.openai.* 配置）。 */
    @Autowired
    public AiClient(AiProperties props, ChatModel chatModel, TaskChatOptionsFactory optionsFactory,
                    ObjectProvider<MeterRegistry> meterRegistry) {
        this.props = props;
        this.chatModel = chatModel;
        this.optionsFactory = optionsFactory;
        this.meterRegistry = meterRegistry;
        // 仅当 AiProperties.model 已配置（.env 的 AI_MODEL）时才覆盖自动配置模型，避免空串覆盖
        this.modelOverride = (props.getModel() == null || props.getModel().isBlank()) ? null : props.getModel();
    }

    /**
     * 单测/回退构造器：无自动配置依赖，首次调用时按 {@link AiProperties} 自建 OpenAI 兼容
     * {@code ChatModel}（base-url 归一化补 {@code /v1}，因 OpenAI SDK 只追加 {@code chat/completions}）。
     * 保留既有 {@code new AiClient(props)} 用法（测试与匿名子类覆写）。
     */
    public AiClient(AiProperties props) {
        this(props, null, new TaskChatOptionsFactory(props), null);
    }

    /**
     * chat 调用结果。
     *
     * <p>09-27-brief-writing-linkage-fix R4:增 {@code finishReason}(模型给出 stop/length 等;
     * 截断=length)。非 JSON 调用不会抛截断异常,调用方(DeepWriterService)需据此判定重试,
     * 故统一透出。
     *
     * <p>10-02-brief-reasoning-maxtokens:增第 5 分量 {@code reasoning}(推理模型的思考过程;
     * 非推理模型/缺失为 null,已按 {@link #REASONING_MAX_CHARS} 截断)。保留 3 参/4 参构造器
     * 以兼容既有调用方与测试(对齐 {@code Citation.docId} 的 record 加字段先例)。
     */
    public record ChatResult(String content, String model, int totalTokens, String finishReason, String reasoning) {
        public ChatResult(String content, String model, int totalTokens) {
            this(content, model, totalTokens, null, null);
        }
        public ChatResult(String content, String model, int totalTokens, String finishReason) {
            this(content, model, totalTokens, finishReason, null);
        }
    }

    /**
     * AI 输出 JSON 容错清洗(2026-09-10):模型偶发违反 response_format=json_object 约束,
     * 在字符串值内输出裸换行/制表等控制字符,Jackson 严格模式直接报
     * "Illegal unquoted character (CTRL-CHAR, code 10)" 导致整版生成失败。
     * 处理:①剥 markdown 代码围栏;②把 JSON 字符串字面量内部的裸控制字符转义成 unicode 转义
     * (逐字符扫描,只在引号内的普通字符上生效,不会破坏已有的合法转义序列)。
     * 供所有「AI 产物 JSON 解析点」统一调用(VersionService/ImitationService 等)。
     */
    public static String sanitizeAiJson(String raw) {
        if (raw == null) return "{}";
        String s = raw.trim();
        // 剥 ```json ... ``` / ``` ... ``` 围栏(模型偶发无视"不要包代码块围栏")
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            int lastFence = s.lastIndexOf("```");
            if (firstNl >= 0 && lastFence > firstNl) s = s.substring(firstNl + 1, lastFence);
            else s = s.substring(firstNl >= 0 ? firstNl + 1 : 3);
            s = s.trim();
        }
        StringBuilder out = new StringBuilder(s.length() + 16);
        boolean inStr = false, esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) { out.append(c); esc = false; continue; }   // 已是合法转义,原样带过
            if (c == '\\' && inStr) { out.append(c); esc = true; continue; }
            if (c == '"') { inStr = !inStr; out.append(c); continue; }
            if (inStr && c < 0x20) {   // 字符串值内的裸控制字符 → 转义
                out.append(String.format("\\u%04x", (int) c));
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * 调用 chat/completions，要求模型以 JSON 对象回应（结构化任务，低温）。
     * @param systemPrompt 系统指令
     * @param userPrompt   用户输入
     * @param maxTokens    上限（reasoning 模型会先用一部分做 reasoning，需给足）
     * @return ChatResult
     */
    public ChatResult chatJson(String systemPrompt, String userPrompt, int maxTokens) {
        return call(TaskType.STRUCTURED_EXTRACT, true,
                List.of(new SystemMessage(systemPrompt), new UserMessage(userPrompt)), maxTokens);
    }

    /**
     * 普通文本 chat（非 JSON 约束；正文写作，高温）。
     */
    public ChatResult chat(String systemPrompt, String userPrompt, int maxTokens) {
        return call(TaskType.ARTICLE_WRITE, false,
                List.of(new SystemMessage(systemPrompt), new UserMessage(userPrompt)), maxTokens);
    }

    /**
     * 结构化调用结果（C2）：强类型 entity + 原 {@link ChatResult}（model/totalTokens/finishReason/reasoning 透传）。
     *
     * <p>保留两分量而非替换 {@link ChatResult}：既有调用方继续用 chat/ChatResult，结构化站点取 entity 同时
     * 仍能落 aiModel/tokenUsage/reasoning（先例：{@code Citation.docId} 的 record 加字段范式）。
     */
    public record TypedResult<T>(T entity, ChatResult chat) {}

    /**
     * C2 结构化调用：schema 由 DTO 类型单一派生 + 响应侧 schema 校验自纠错（{@link StructuredOutputValidationAdvisor}）。
     *
     * <p><b>与 {@link #chatJson} 的差异</b>：chatJson 只强制 {@code response_format=json_object}，字段/类型错误
     * 只能靠上层「提额重试」盲试；本方法用 DTO 类型生成 JSON Schema，校验失败时把**具体校验错误**回填 user prompt
     * 再试（默认 {@code maxRepeatAttempts=1}，即最多 2 次净调用）。axonhub 忽略 provider 原生 {@code json_schema}
     * strict（C0 探针），故正确性只依赖此响应侧校验；{@code useProviderStructuredOutput()} 不启用（会无效）。
     *
     * <p><b>截断与 schema 违规分离（语义上）</b>：{@code finish_reason=length} 最终由 {@link #parseChat} 抛截断
     * {@link AiException} → 交服务层提额重试；字段/类型/多余字段由 advisor 在校验内用**同额度**自纠错。
     *
     * <p><b>截断的实际代价（已知）</b>：advisor 在 {@code parseChat} **之前**执行，半截 JSON 在它眼里同样
     * 是「schema 违规」，故会先补发一次**同额度**请求（注定仍截断），{@code parseChat} 才抛截断异常。
     * 因此截断路径本方法净调用 = 1 + ({@code maxRepeatAttempts=1}) = **2 次同额度**，叠加服务层 1 次提额
     * 重试，最坏 ≤4 次；正常与纯 schema 违规路径分别 1 / 2 次。此处保留 1 次自纠错（换取 schema 违规
     * 真正被修复的收益），不因截断场景的这次空烧降到 0——截断是额度问题，advisor 无法也不应修复它。
     *
     * <p>schema 文本（{@link #jsonSchema(Class)}）由调用方注入 prompt 的 {@code {{schema}}} 占位；prompt 中不再
     * 内联 schema 字面量（单一来源 = DTO 类型）。
     *
     * @param system    系统指令（已含 schema 文本）
     * @param user      用户输入
     * @param maxTokens 额度（reasoning 模型需覆盖推理 + 正文）
     * @param type      DTO 类型（schema 来源 + 反序列化目标）
     * @return entity + ChatResult
     */
    public <T> TypedResult<T> structured(String system, String user, int maxTokens, Class<T> type) {
        try {
            OpenAiChatOptions.Builder opts = optionsFactory.forTask(TaskType.STRUCTURED_EXTRACT);
            opts.model(modelOverride != null ? modelOverride : resolveTextModel());
            opts.maxTokens(maxTokens);
            TaskChatOptionsFactory.jsonFormat(opts);
            // 响应侧 schema 校验 + 具体错误回填自纠错；maxRepeatAttempts=1 收敛重试（见方法注释）
            StructuredOutputValidationAdvisor advisor = StructuredOutputValidationAdvisor.builder()
                    .outputType(type)
                    .maxRepeatAttempts(1)
                    .build();
            ChatResponse resp = chatClient().prompt()
                    .system(system)
                    .user(user)
                    .options(opts)
                    .advisors(advisor)
                    .call()
                    .chatResponse();
            // 先复用 parseChat：空内容/截断（length）走既有 AiException 语义，与 schema 违规解耦
            ChatResult cr = parseChat(resp, true);
            T entity = new BeanOutputConverter<>(type).convert(sanitizeAiJson(cr.content()));
            return new TypedResult<>(entity, cr);
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException("AI 结构化调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 由 DTO 类型派生 JSON Schema 文本（供 prompt {@code {{schema}}} 占位注入）。
     * 与 {@link #structured} 校验所用 schema 同源（均为 {@code BeanOutputConverter}）。
     */
    public static <T> String jsonSchema(Class<T> type) {
        return new BeanOutputConverter<>(type).getJsonSchema();
    }

    /**
     * 多轮消息 chat（知识问答，中温；不强制 JSON）。
     * messages 每项为 {role, content},按顺序原样送模型(system / user / assistant 交替)。
     *
     * @param messages  有序消息列表
     * @param maxTokens 上限
     * @return ChatResult
     */
    public ChatResult chatMessages(List<Map<String, String>> messages, int maxTokens) {
        List<Message> converted = new ArrayList<>(messages.size());
        for (Map<String, String> m : messages) {
            converted.add(toMessage(m.get("role"), m.get("content")));
        }
        return call(TaskType.QA_CHAT, false, converted, maxTokens);
    }

    /**
     * C4：多轮问答走 Spring AI {@code ChatMemory}（{@link MessageChatMemoryAdvisor}）。
     *
     * <p>调用方给「本轮 system + 本轮 user + 历史窗口」，历史由 {@link ChatMemory} 装配
     * （advisor 在 system 之后、本轮 user 之前插入历史，与本服务旧手拼顺序逐字等价）。
     * 每次调用用**局部** memory（DB 仍是历史唯一权威，不回写全局 memory），故无状态泄漏。
     *
     * @param conversationId 会话标识（advisor 取历史的 key；用 sessionId 稳定即可）
     * @param systemPrompt   本轮 system（含知识上下文）
     * @param userPrompt     本轮 user 问题
     * @param history        历史窗口（时间升序；role∈system/user/assistant）
     */
    public ChatResult chatWithMemory(String conversationId, String systemPrompt, String userPrompt,
                                     List<Map<String, String>> history, int maxTokens) {
        ChatMemory memory = MessageWindowChatMemory.builder()
                .maxMessages(Math.max((history == null ? 0 : history.size()) + 2, 2))
                .build();
        if (history != null && !history.isEmpty()) {
            List<Message> seed = new ArrayList<>(history.size());
            for (Map<String, String> m : history) seed.add(toMessage(m.get("role"), m.get("content")));
            memory.add(conversationId, seed);
        }
        MessageChatMemoryAdvisor advisor = MessageChatMemoryAdvisor.builder(memory).build();
        try {
            OpenAiChatOptions.Builder opts = optionsFactory.forTask(TaskType.QA_CHAT);
            opts.model(modelOverride != null ? modelOverride : resolveTextModel());
            opts.maxTokens(maxTokens);
            ChatResponse resp = chatClient().prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .options(opts)
                    .advisors(advisor)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .call()
                    .chatResponse();
            return parseChat(resp, false);
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException("AI chat 调用失败: " + e.getMessage(), e);
        }
    }

    /** role → Spring AI Message（缺省按 user 处理），供多轮组装复用。 */
    private static Message toMessage(String role, String content) {
        return switch (role == null ? "" : role) {
            case "system" -> new SystemMessage(content);
            case "assistant" -> new AssistantMessage(content);
            default -> new UserMessage(content);
        };
    }

    /** 统一调用：任务级 options + ChatClient + parseChat。 */
    private ChatResult call(TaskType taskType, boolean requireJson, List<Message> messages, int maxTokens) {
        try {
            OpenAiChatOptions.Builder opts = optionsFactory.forTask(taskType);
            opts.model(modelOverride != null ? modelOverride : resolveTextModel());
            opts.maxTokens(maxTokens);
            if (requireJson) TaskChatOptionsFactory.jsonFormat(opts);
            ChatResponse resp = chatClient().prompt()
                    .messages(messages)
                    .options(opts)
                    .call()
                    .chatResponse();
            return parseChat(resp, requireJson);
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException("AI chat 调用失败: " + e.getMessage(), e);
        }
    }

    /** 懒构建 ChatClient（注入 ChatModel 优先，缺失时按 AiProperties 自建；挂观测 Advisor）。 */
    private ChatClient chatClient() {
        ChatClient c = chatClient;
        if (c == null) {
            synchronized (this) {
                c = chatClient;
                if (c == null) {
                    ChatModel model = chatModel != null ? chatModel : fallbackChatModel();
                    ChatClient.Builder builder = ChatClient.builder(model);
                    if (meterRegistry != null) builder.defaultAdvisors(new AiObservabilityAdvisor(meterRegistry));
                    c = builder.build();
                    chatClient = c;
                }
            }
        }
        return c;
    }

    /** 单测/回退自建 OpenAI 兼容 ChatModel：base-url 归一化补 {@code /v1}。 */
    private ChatModel fallbackChatModel() {
        return OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .baseUrl(normalizeBaseUrl(props.getBaseUrl()))
                        .apiKey(props.getApiKey())
                        .build())
                .build();
    }

    /**
     * 归一化 OpenAI 兼容 base-url：Spring AI 的 OpenAI SDK 只在 base-url 后追加 {@code chat/completions}，
     * 不带 {@code /v1}；而既有 {@code AI_BASE_URL}（及 .env）为网关根地址，须补 {@code /v1} 才命中
     * chat completions 端点（C1 实测：不补则返回网关 HTML → 解析失败）。
     */
    static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return baseUrl;
        String b = baseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b.endsWith("/v1") ? b : b + "/v1";
    }

    /**
     * @param requireJson 该次调用是否强制 response_format=json_object。
     *  Model 命中 max_tokens 被截断(finish_reason=length)时,JSON 内容必然是半截,
     *  直接给出可操作的错误(增大 max_tokens),避免上层 Jackson 抛
     *  "Unexpected end-of-input" 这类无法定位的解析报错(2026-09-13 ClarifyService 实测)。
     */
    private ChatResult parseChat(ChatResponse resp, boolean requireJson) {
        try {
            if (resp == null || resp.getResult() == null) {
                throw new AiException("AI 返回无 choices", null);
            }
            AssistantMessage msg = resp.getResult().getOutput();
            String content = msg == null ? "" : msg.getText();
            if (content == null || content.isBlank()) {
                throw new AiException("AI content 为空（可能 reasoning 截断，需增大 max_tokens）", null);
            }
            String finishReason = finishReason(resp);
            if (requireJson && "length".equals(finishReason)) {
                throw new AiException("AI 输出被 max_tokens 截断（finish_reason=length），JSON 不完整，请增大 max_tokens: " + truncate(content), null);
            }
            int tokens = 0;
            String model = "";
            if (resp.getMetadata() != null) {
                Usage usage = resp.getMetadata().getUsage();
                if (usage != null && usage.getTotalTokens() != null) tokens = usage.getTotalTokens();
                model = resp.getMetadata().getModel() == null ? "" : resp.getMetadata().getModel();
            }
            // R4(09-27-brief-writing-linkage-fix):始终透出 finish_reason——非 JSON 调用(如正文写作)
            // 截断时不抛异常,调用方需据此判定是否提额重试。Spring AI 返回大写 STOP/LENGTH,归一为小写。
            // 10-02-brief-reasoning-maxtokens:透出 reasoning(推理模型思考过程)。Spring AI 已把
            // reasoning_content/reasoning 写入 metadata["reasoningContent"](含回退);截断上限防超长落库。
            String reasoning = reasoning(msg);
            if (reasoning != null && reasoning.length() > REASONING_MAX_CHARS) {
                reasoning = reasoning.substring(0, REASONING_MAX_CHARS);
            }
            return new ChatResult(content, model, tokens, finishReason, reasoning);
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException("解析 AI 返回失败: " + truncate(e.getMessage()), e);
        }
    }

    /** finish_reason 归一小写（Spring AI/OpenAI SDK 返回大写 {@code STOP}/{@code LENGTH}）；无则 null。 */
    private static String finishReason(ChatResponse resp) {
        if (resp.getResult() == null || resp.getResult().getMetadata() == null) return null;
        String fr = resp.getResult().getMetadata().getFinishReason();
        return fr == null ? null : fr.toLowerCase();
    }

    /** 从 assistant metadata 取 reasoningContent（Spring AI 已做 reasoning_content→reasoning 回退），空白归 null。 */
    private static String reasoning(AssistantMessage msg) {
        if (msg == null || msg.getMetadata() == null) return null;
        Object r = msg.getMetadata().get(REASONING_METADATA_KEY);
        if (r == null) return null;
        String s = r.toString();
        return s.isBlank() ? null : s;
    }

    private String resolveTextModel() {
        String m = props.getModel();
        if (m == null || m.isBlank()) throw new AiException("AI_MODEL 未配置", null);
        return m;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
