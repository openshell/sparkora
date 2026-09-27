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
 *
 * <p><b>09-27 更正</b>:原注释「server 2.0.11 鉴权中间件对错误 key 会挂起(超时)而非 401,
 * 客户端须设较短超时」是<b>已被实测推翻</b>的旧结论——当前部署无效 key 即刻 401,不再挂起。
 * 照旧结论把 {@code publishTimeoutMs} 压在 30s 正是「客户端超时但服务端已写入草稿 → 重试即重复草稿」
 * 事故的根因(实耗 43s+)。阈值现按实测实耗设定,探针另用 {@link #verifyTimeoutMs} 独立短阈值。
 * 详见 docs/wenyan.md §7 与 docs/spec/publish.md §1.1。
 */
@Data
@ConfigurationProperties(prefix = "sparkora.wenyan")
public class WenyanProperties {
    // 注:旧 stdio 模式的 enabled/bin 配置(WECHAT/WENYAN_MCP_ENABLED、WENYAN_MCP_BIN)已废弃移除——
    // 方案 A(S4/S5)下发布通道只取决于 serverUrl+serverApiKey,enabled 开关不再参与判定。
    private String serverUrl;
    private String serverApiKey;

    /** 本机 wenyan 可执行文件(绝对路径或 PATH 可见名),用于 render。 */
    private String cliPath = "wenyan";
    /** 预览默认主题。 */
    private String defaultTheme = "default";
    /**
     * @deprecated 09-11-wenyan-themes 起主题清单改由 {@link com.sparkora.service.WenyanThemeCatalog}
     * 权威目录提供(含社区 custom:* 主题,.env 无法表达 CLI CSS 路径),本字段不再参与校验/下发。
     * 保留仅为兼容旧 .env,配置与否不影响行为。
     */
    @Deprecated
    private String themeNames = "default";
    /** 代码高亮主题。 */
    private String highlight = "solarized-light";
    /** 代码块 Mac 风格(默认开)。 */
    private boolean macStyle = true;
    /** 链接转脚注(默认开)。 */
    private boolean footnote = true;
    /** render 进程读超时(毫秒)。 */
    private long renderTimeoutMs = 30000;
    /**
     * 发布(S5)HTTP 调用读超时(毫秒)。
     * <p>09-27 实测校正:带 7 张正文图(17MB)的 /publish 实耗 ~43s(拉图转存 + 微信写草稿),
     * 原 30s 会「客户端超时但服务端已写入草稿」→ 前端报失败而草稿箱已有文章,重试即产生重复草稿。
     * 故默认提到 180s(约 4 倍余量,覆盖图更多/微信侧慢/VPN 抖动)。调回 30s 以下会重现该事故。
     * 注意:/verify 探针<b>不</b>用此超时,见 {@link #verifyTimeoutMs}。
     */
    private long publishTimeoutMs = 180000;

    /**
     * 探针(GET /verify、/health)读超时(毫秒)。
     * <p>必须与 {@link #publishTimeoutMs} 解耦:两者曾共用一个 RestClient,调大发布超时会把探针
     * 一起调到 180s,导致通道不可用时「发布参数」接口(进而整个发布页)被挂住 3 分钟。
     * 探针是纯只读、同机往返毫秒级,5s 足够;也不加自动重试(通道真不可达时等待会翻倍)。
     */
    private long verifyTimeoutMs = 5000;

    /** 发布通道(S5)配置是否完整:serverUrl + serverApiKey 均非空。 */
    public boolean serverConfigured() {
        return serverUrl != null && !serverUrl.isBlank()
                && serverApiKey != null && !serverApiKey.isBlank();
    }

    /** @deprecated 见 {@link #themeNames};不再参与校验,新代码用 WenyanThemeCatalog。 */
    @Deprecated
    public List<String> themeNameList() {
        return Arrays.stream((themeNames == null || themeNames.isBlank() ? defaultTheme : themeNames).split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}