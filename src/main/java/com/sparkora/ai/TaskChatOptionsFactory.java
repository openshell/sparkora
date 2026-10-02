package com.sparkora.ai;

import com.sparkora.config.AiProperties;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.stereotype.Component;

/**
 * 任务级 {@code ChatOptions} 工厂（C1，design §3.2）。
 *
 * <p>按 {@link TaskType} 给出 temperature。max_tokens 仍由各调用点显式传入——既有「提额重试」
 * 范式依赖具体额度值，工厂不接管。模型名沿用 {@code AI_MODEL}（由 Spring AI 默认 options 提供，
 * 不经此工厂覆写，避免破坏 axonhub 的按名路由语义）。
 *
 * <p>温度来源 {@link AiProperties}（{@code sparkora.ai.*}，.env 可覆盖）：
 * 结构化类 {@code sparkora.ai.temperature-structured}（默认 0.2）、
 * 正文类 {@code temperature-prose}（默认 0.7）、问答类 {@code temperature-qa}（默认 0.5）。
 * 与 {@code AiProperties.temperature}（全局兜底 0.7）并存，旧配置/回退可用。
 */
@Component
public class TaskChatOptionsFactory {

    private final AiProperties props;

    public TaskChatOptionsFactory(AiProperties props) {
        this.props = props;
    }

    /** 任务温度：正文类高温、结构化低温、问答中温；均可在 .env 覆盖。 */
    public double temperature(TaskType taskType) {
        if (taskType == null) return props.getTemperature();
        return switch (taskType) {
            case ARTICLE_WRITE -> props.getTemperatureProse();
            case STRUCTURED_EXTRACT -> props.getTemperatureStructured();
            case QA_CHAT -> props.getTemperatureQa();
        };
    }

    /** 构造带任务温度的 options builder（未设 maxTokens；调用点按需 {@code .maxTokens(...)}）。 */
    public OpenAiChatOptions.Builder forTask(TaskType taskType) {
        return OpenAiChatOptions.builder().temperature(temperature(taskType));
    }

    /** 强制 JSON 对象输出（response_format=json_object）：等价旧 {@code chatJson} 语义。 */
    public static OpenAiChatOptions.Builder jsonFormat(OpenAiChatOptions.Builder builder) {
        return builder.responseFormat(OpenAiChatModel.ResponseFormat.builder()
                .type(OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT)
                .build());
    }
}
