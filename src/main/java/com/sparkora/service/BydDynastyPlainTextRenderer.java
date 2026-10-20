package com.sparkora.service;

import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableBody;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemMarker;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.text.CoreTextContentNodeRenderer;
import org.commonmark.renderer.text.LineBreakRendering;
import org.commonmark.renderer.text.TextContentNodeRendererContext;
import org.commonmark.renderer.text.TextContentRenderer;
import org.commonmark.renderer.text.TextContentWriter;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把文章 Markdown 编译为比亚迪王朝小程序可接受的纯文本、图片和视频块。
 *
 * <p>该类是纯组件：不依赖 Spring、数据库、HTTP 或本机 Agent。Markdown 语法解析交给
 * commonmark-java，HTML 片段交给 Jsoup；这里只实现面向小程序的产品化输出策略。</p>
 *
 * <p>输出不修改原文：调用方拿到的是新的有序块列表，{@code contentMd} 保持不变。</p>
 */
final class BydDynastyPlainTextRenderer {

    /** 图片边界哨兵。CommonMark 会把输入中的 NUL 替换为 U+FFFD，因此正文无法伪造该标记。 */
    private static final String SENTINEL_PREFIX = "\u0000IMG:";
    private static final String SENTINEL_SUFFIX = "\u0000";
    private static final Pattern SENTINEL = Pattern.compile("\u0000IMG:([A-Za-z0-9_-]+)\u0000");
    /** 裸 URL 兜底清理；代码节点不经过 Text 分支，因此代码内容不会被误删。 */
    private static final Pattern BARE_URL = Pattern.compile(
            "(?i)(?:https?://|www\\.)[^\\s<>\\[\\](){}，。；：！？、]+");
    private static final String[] DANGEROUS_HTML =
            {"script", "style", "iframe", "object", "embed", "noscript", "template"};

    private static final List<Extension> EXTENSIONS = List.of(
            TablesExtension.create(),
            StrikethroughExtension.create(),
            TaskListItemsExtension.create(),
            AutolinkExtension.create());

    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();

    private static final TextContentRenderer BLOCK_RENDERER = TextContentRenderer.builder()
            .nodeRendererFactory(PlainTextNodeRenderer::new)
            .extensions(EXTENSIONS)
            .lineBreakRendering(LineBreakRendering.SEPARATE_BLOCKS)
            .build();

    private static final TextContentRenderer INLINE_RENDERER = TextContentRenderer.builder()
            .nodeRendererFactory(PlainTextNodeRenderer::new)
            .extensions(EXTENSIONS)
            .lineBreakRendering(LineBreakRendering.STRIP)
            .build();

    private BydDynastyPlainTextRenderer() {
    }

    private static final String IMAGE_TOKEN_PREFIX = "sparkora-byd-media:";
    private static final String VIDEO_TOKEN_PREFIX = "sparkora-byd-video:";
    private static final String GZH_TOKEN_PREFIX = "sparkora-img:";

    /** 有序内容块：文本或独立媒体。 */
    sealed interface Block permits TextBlock, ImageBlock, VideoBlock {
    }

    record TextBlock(String text) implements Block {
    }

    /** target 为 BYD 暂存 mediaId，或仅作历史兼容的 http(s) URL。 */
    record ImageBlock(String target) implements Block {
    }

    /** target 为 BYD 暂存视频 mediaId。 */
    record VideoBlock(String target) implements Block {
    }

    /**
     * 编译正文。文本、图片与视频按 Markdown 中出现顺序返回；空输入返回空列表。
     */
    static List<Block> compile(String markdown) {
        String source = markdown == null ? "" : markdown;
        String rendered = BLOCK_RENDERER.render(PARSER.parse(source));
        return splitBlocks(rendered);
    }

    /**
     * 渲染单行标题：去除 Markdown/HTML 标记、链接目标和裸 URL，压缩空白。
     */
    static String renderInline(String markdown) {
        String source = markdown == null ? "" : markdown;
        String rendered = INLINE_RENDERER.render(PARSER.parse(source));
        return rendered.replaceAll("[\\s\\u00A0]+", " ").strip();
    }

    private static List<Block> splitBlocks(String rendered) {
        List<Block> blocks = new ArrayList<>();
        Matcher matcher = SENTINEL.matcher(rendered == null ? "" : rendered);
        int cursor = 0;
        while (matcher.find()) {
            appendText(blocks, rendered.substring(cursor, matcher.start()));
            String target = decodeUrl(matcher.group(1));
            blocks.add(classifyMedia(target));
            cursor = matcher.end();
        }
        appendText(blocks, rendered == null ? "" : rendered.substring(cursor));
        return blocks;
    }

    private static Block classifyMedia(String target) {
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("正文媒体缺少目标地址，请删除后重新插入");
        }
        if (target.startsWith(GZH_TOKEN_PREFIX)) {
            throw new IllegalArgumentException("公众号暂存图片不能用于比亚迪小程序，请删除后重新插入");
        }
        if (target.startsWith(IMAGE_TOKEN_PREFIX)) {
            return new ImageBlock(requireMediaId(target.substring(IMAGE_TOKEN_PREFIX.length())));
        }
        if (target.startsWith(VIDEO_TOKEN_PREFIX)) {
            return new VideoBlock(requireMediaId(target.substring(VIDEO_TOKEN_PREFIX.length())));
        }
        if (startsWithIgnoreCase(target, "http://") || startsWithIgnoreCase(target, "https://")) {
            return new ImageBlock(target);
        }
        throw new IllegalArgumentException("正文媒体地址不受支持，请删除后重新插入");
    }

    private static String requireMediaId(String value) {
        String id = value == null ? "" : value.trim();
        if (!id.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("正文媒体标识无效，请删除后重新插入");
        }
        return id;
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static void appendText(List<Block> blocks, String raw) {
        String text = normalizeText(raw);
        if (!text.isEmpty()) blocks.add(new TextBlock(text));
    }

    /** CRLF/CR 统一为 LF，行尾空白清理，连续空行最多保留一个，去掉首尾空行。 */
    static String normalizeText(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String value = raw.replace("\r\n", "\n").replace('\r', '\n');
        value = value.replaceAll("[\\t\\x0B\\f ]+\\n", "\n");
        value = value.replaceAll("\\n{3,}", "\n\n");
        return trimBlankEdges(value);
    }

    private static String trimBlankEdges(String value) {
        String[] lines = value.split("\n", -1);
        int first = 0;
        int last = lines.length - 1;
        while (first <= last && lines[first].isBlank()) first++;
        while (last >= first && lines[last].isBlank()) last--;
        if (first > last) return "";
        StringBuilder out = new StringBuilder(value.length());
        for (int i = first; i <= last; i++) {
            if (i > first) out.append('\n');
            out.append(lines[i].stripTrailing());
        }
        return out.toString();
    }

    private static String sentinel(String url) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(url.getBytes(StandardCharsets.UTF_8));
        return SENTINEL_PREFIX + encoded + SENTINEL_SUFFIX;
    }

    private static String decodeUrl(String encoded) {
        try {
            return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String stripBareUrls(String literal) {
        if (literal == null || literal.isEmpty()) return "";
        Matcher matcher = BARE_URL.matcher(literal);
        if (!matcher.find()) return literal;
        return matcher.replaceAll("").replaceAll("[ \\t]{2,}", " ");
    }

    /** 用 Jsoup 把 HTML 片段转成可读文本；危险节点整体删除，br 与块级标签产生换行。 */
    static String htmlToText(String html) {
        if (html == null || html.isBlank()) return "";
        org.jsoup.nodes.Document doc = Jsoup.parseBodyFragment(html);
        doc.outputSettings().prettyPrint(false);
        for (String selector : DANGEROUS_HTML) doc.select(selector).remove();
        for (Element br : new ArrayList<>(doc.select("br"))) {
            br.replaceWith(new org.jsoup.nodes.TextNode("\n"));
        }
        String text = doc.body().wholeText();
        return text == null ? "" : text;
    }

    /**
     * 只覆盖产品策略相关的节点；段落、标题层级、代码块、软换行等交给 commonmark 的
     * {@link CoreTextContentNodeRenderer}。自定义工厂先注册，因此对同名节点优先于核心实现。
     */
    private static final class PlainTextNodeRenderer extends CoreTextContentNodeRenderer {

        private final TextContentNodeRendererContext renderContext;
        private final TextContentWriter writer;
        private final Deque<ListState> lists = new ArrayDeque<>();

        PlainTextNodeRenderer(TextContentNodeRendererContext context) {
            super(context);
            this.renderContext = context;
            this.writer = context.getWriter();
        }

        @Override
        public Set<Class<? extends Node>> getNodeTypes() {
            Set<Class<? extends Node>> types = new HashSet<>(super.getNodeTypes());
            types.add(Strikethrough.class);
            types.add(TaskListItemMarker.class);
            types.add(TableBlock.class);
            return Set.copyOf(types);
        }

        @Override
        public void visit(Heading heading) {
            visitChildren(heading);
            if (stripNewlines()) writer.whitespace();
            else writer.block();
        }

        @Override
        public void visit(BlockQuote blockQuote) {
            visitChildren(blockQuote);
            writer.block();
        }

        @Override
        public void visit(BulletList bulletList) {
            lists.push(new ListState(false, 0, ""));
            writer.pushTight(bulletList.isTight());
            visitChildren(bulletList);
            writer.popTight();
            writer.block();
            lists.pop();
        }

        @Override
        public void visit(OrderedList orderedList) {
            int start = orderedList.getMarkerStartNumber() == null ? 1 : orderedList.getMarkerStartNumber();
            String delimiter = orderedList.getMarkerDelimiter() == null ? "." : orderedList.getMarkerDelimiter();
            lists.push(new ListState(true, start, delimiter));
            writer.pushTight(orderedList.isTight());
            visitChildren(orderedList);
            writer.popTight();
            writer.block();
            lists.pop();
        }

        @Override
        public void visit(ListItem listItem) {
            ListState state = lists.peek();
            String marker = "";
            if (state != null && !stripNewlines()) {
                marker = state.ordered ? state.counter + state.delimiter : "\u2022";
                writer.write(marker + " ");
            }
            String prefix = " ".repeat(marker.length() + (marker.isEmpty() ? 0 : 1));
            writer.pushPrefix(prefix);
            visitChildren(listItem);
            writer.block();
            writer.popPrefix();
            if (state != null && state.ordered) state.counter++;
        }

        @Override
        public void visit(Code code) {
            writer.write(code.getLiteral());
        }

        @Override
        public void visit(Link link) {
            if (isAutolink(link)) return;
            visitChildren(link);
        }

        @Override
        public void visit(Image image) {
            if (stripNewlines()) return;
            String destination = image.getDestination();
            writer.write(sentinel(destination == null ? "" : destination));
        }

        @Override
        public void visit(ThematicBreak thematicBreak) {
            writer.block();
        }

        @Override
        public void visit(Text text) {
            writer.write(stripBareUrls(text.getLiteral()));
        }

        @Override
        public void visit(HtmlInline htmlInline) {
            writer.write(htmlToText(htmlInline.getLiteral()));
        }

        @Override
        public void visit(HtmlBlock htmlBlock) {
            String text = htmlToText(htmlBlock.getLiteral());
            if (!text.isBlank()) {
                writer.write(text);
                writer.block();
            }
        }

        /**
         * 扩展节点（删除线、任务列表、表格）继承自 CustomNode/CustomBlock，标准 accept 分派
         * 只会进入 visit(CustomNode)，因此在这里按具体类型分派。
         */
        @Override
        public void render(Node node) {
            if (node instanceof Strikethrough strikethrough) {
                visitChildren(strikethrough);
            } else if (node instanceof TaskListItemMarker marker) {
                writer.write(marker.isChecked() ? "\u2611 " : "\u2610 ");
            } else if (node instanceof TableBlock table) {
                renderTable(table);
            } else {
                super.render(node);
            }
        }

        void renderTable(TableBlock table) {
            List<String> headers = new ArrayList<>();
            List<TableRow> rows = new ArrayList<>();
            for (Node child = table.getFirstChild(); child != null; child = child.getNext()) {
                if (child instanceof TableHead head) collectRows(head, headers, rows);
                else if (child instanceof TableBody body) collectRows(body, null, rows);
            }
            writer.pushTight(true);
            for (TableRow row : rows) {
                String line = tableRowLine(row, headers);
                if (!line.isBlank()) {
                    writer.write(line);
                    writer.block();
                }
            }
            writer.popTight();
            writer.block();
        }

        private void collectRows(Node container, List<String> headers, List<TableRow> rows) {
            for (Node child = container.getFirstChild(); child != null; child = child.getNext()) {
                if (!(child instanceof TableRow row)) continue;
                if (headers != null && headers.isEmpty()) headers.addAll(cellTexts(row));
                else rows.add(row);
            }
        }

        private String tableRowLine(TableRow row, List<String> headers) {
            List<String> cells = cellTexts(row);
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < cells.size(); i++) {
                String cell = cells.get(i).strip();
                if (cell.isEmpty()) continue;
                if (line.length() > 0) line.append("\uFF1B");
                if (i < headers.size() && !headers.get(i).isBlank()) {
                    line.append(headers.get(i).strip()).append("\uFF1A");
                }
                line.append(cell);
            }
            return line.toString();
        }

        private List<String> cellTexts(TableRow row) {
            List<String> cells = new ArrayList<>();
            for (Node child = row.getFirstChild(); child != null; child = child.getNext()) {
                if (!(child instanceof TableCell cell)) continue;
                // 渲染单元格的子节点而不是 TableCell 本身：扩展的 TableCell 渲染器会补 "|" 分隔符。
                StringBuilder value = new StringBuilder();
                for (Node inline = cell.getFirstChild(); inline != null; inline = inline.getNext()) {
                    value.append(INLINE_RENDERER.render(inline));
                }
                cells.add(value.toString().replaceAll("\\s+", " ").strip());
            }
            return cells;
        }

        private boolean isAutolink(Link link) {
            Node child = link.getFirstChild();
            if (!(child instanceof Text text) || child.getNext() != null) return false;
            String visible = text.getLiteral() == null ? "" : text.getLiteral().strip();
            String destination = link.getDestination() == null ? "" : link.getDestination().strip();
            if (!looksLikeUrl(visible)) return false;
            return destination.equalsIgnoreCase(visible)
                    || trimScheme(destination).equalsIgnoreCase(trimScheme(visible))
                    || destination.equalsIgnoreCase("mailto:" + visible);
        }

        private static boolean looksLikeUrl(String value) {
            String lower = value.toLowerCase();
            return lower.startsWith("http://") || lower.startsWith("https://")
                    || lower.startsWith("www.") || lower.startsWith("mailto:")
                    || lower.contains("@") && lower.contains(".");
        }

        private static String trimScheme(String value) {
            String lower = value.toLowerCase();
            if (lower.startsWith("http://")) return value.substring(7);
            if (lower.startsWith("https://")) return value.substring(8);
            return value;
        }

        private boolean stripNewlines() {
            return renderContext.lineBreakRendering() == LineBreakRendering.STRIP;
        }

        private static final class ListState {
            private final boolean ordered;
            private int counter;
            private final String delimiter;

            private ListState(boolean ordered, int counter, String delimiter) {
                this.ordered = ordered;
                this.counter = counter;
                this.delimiter = delimiter;
            }
        }
    }
}
