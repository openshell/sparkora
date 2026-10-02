package com.sparkora.ai;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt 模板加载/渲染（C1，design §3.3）。
 *
 * <p>提示词资产外置到 classpath {@code prompts/**、<name>.st}，首行注释 {@code # version: vN}（Git 版本管理）。
 * Java 侧只保留动态数据组装，把固定指令文字块外置为模板变量。
 *
 * <p><b>刻意做成无状态静态工具</b>（而非注入 Bean）：prompt 加载是纯函数，静态调用让各领域服务
 * 无需改构造器即可复用，避免为「读资源」扩散框架类型、并保持既有 572 用例的装配不变。
 *
 * <p><b>占位符刻意不用 Spring AI 的 {@code PromptTemplate}</b>：其 {@code {var}} 语法与本项目
 * JSON schema 文本块（大量字面 {@code {} }）冲突，需逐个转义。这里采用无歧义的 {@code {{var}}}
 * 字面替换——模板与变量分离的效果一致，且对 JSON 花括号零侵入。
 */
public final class PromptTemplateLoader {

    private PromptTemplateLoader() {}

    /** 占位符匹配，支持变量名中的字母/数字/下划线。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z0-9_]+)}}");

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    /**
     * 加载模板并替换全部 {@code {{name}}} 占位符。
     *
     * @param path   相对 classpath {@code prompts/} 的路径，如 {@code "brief/deep-brief-system.st"}
     * @param params 变量表（值 null 视为空串）
     * @return 渲染后的文本
     * @throws IllegalArgumentException 模板不存在、读取失败，或存在未提供的占位符（配置错误显式暴露，不静默留 {@code {{x}}}）
     */
    public static String render(String path, Map<String, ?> params) {
        String template = load(path);
        StringBuilder out = new StringBuilder(template.length() + 64);
        Matcher m = PLACEHOLDER.matcher(template);
        int last = 0;
        while (m.find()) {
            out.append(template, last, m.start());
            String key = m.group(1);
            if (params == null || !params.containsKey(key)) {
                throw new IllegalArgumentException("prompt 模板占位符未提供: " + key + " @ " + path);
            }
            Object v = params.get(key);
            out.append(v == null ? "" : v.toString());
            last = m.end();
        }
        out.append(template, last, template.length());
        return out.toString();
    }

    /** 加载模板原文（已剥离首行 {@code # version} 注释）；结果缓存。 */
    public static String load(String path) {
        return CACHE.computeIfAbsent(path, p -> {
            try {
                String raw = new ClassPathResource("prompts/" + p).getContentAsString(StandardCharsets.UTF_8);
                // 剥离首行版本注释（约定首行 # version: vN）
                if (raw.startsWith("# version:")) {
                    int nl = raw.indexOf('\n');
                    raw = nl >= 0 ? raw.substring(nl + 1) : "";
                }
                return raw;
            } catch (IOException e) {
                throw new UncheckedIOException("prompt 模板读取失败: " + p, e);
            }
        });
    }
}
