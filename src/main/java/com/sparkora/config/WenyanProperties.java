package com.sparkora.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.List;

/**
 * wenyan 集成配置。对应 .env: WENYAN_MCP_*。
 *
 * 双通道(方案 A):
 *  - 预览:本机 wenyan CLI(@wenyan-md/cli) `render` 命令 —— 与发布同核渲染引擎,纯排版不碰微信;
 *  - 发布(S5):远程 wenyan-server(serverUrl),Auth x-api-key,接口 /health /verify /upload /publish。
 * 注意:server 2.0.11 鉴权中间件对错误 key 会挂起(超时)而非 401,客户端须设较短超时并按「挂起=key 失效」处理。
 */
@Data
@ConfigurationProperties(prefix = "sparkora.wenyan")
public class WenyanProperties {
    private boolean enabled = false;
    private String bin = "wenyan-mcp";
    private String serverUrl;
    private String serverApiKey;

    /** 本机 wenyan 可执行文件(绝对路径或 PATH 可见名),用于 render。 */
    private String cliPath = "wenyan";
    /** 预览默认主题。 */
    private String defaultTheme = "default";
    /** 可选主题清单(逗号分隔),预览页下拉读此配置;server 2.0.11 无主题查询接口。 */
    private String themeNames = "default";
    /** 代码高亮主题。 */
    private String highlight = "solarized-light";
    /** 代码块 Mac 风格(默认开)。 */
    private boolean macStyle = true;
    /** 链接转脚注(默认开)。 */
    private boolean footnote = true;
    /** render 进程读超时(毫秒)。 */
    private long renderTimeoutMs = 30000;

    public List<String> themeNameList() {
        return Arrays.stream((themeNames == null || themeNames.isBlank() ? defaultTheme : themeNames).split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}