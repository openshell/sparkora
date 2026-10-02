package com.sparkora.ai;

/**
 * C1 任务级参数类型（design §3.2，按现有调用形态归纳）。
 *
 * <p>本次收敛保持 {@code AiClient} 公共 API 完全不变（design §2.2 组件映射），
 * 故任务类型由「调用方法」唯一决定：{@code chat}=正文创意、{@code chatJson}=结构化抽取、
 * {@code chatMessages}=知识问答。三者使用不同 temperature（见 {@link TaskChatOptionsFactory}），
 * 满足 AC「结构化类与正文类使用不同 temperature」。
 */
public enum TaskType {
    /** 正文写作/多版本正文/仿写（创意，高温）。 */
    ARTICLE_WRITE,
    /** 结构化抽取：澄清研究计划、深度简报、子代理事实抽取、风格提炼、参数清洗、车型匹配（低温）。 */
    STRUCTURED_EXTRACT,
    /** 知识问答（中温）。 */
    QA_CHAT
}
