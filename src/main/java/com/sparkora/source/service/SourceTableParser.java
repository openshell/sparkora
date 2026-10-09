package com.sparkora.source.service;

import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.List;

/**
 * HTML 表格 → 保留行列语义的逐行文本(10-05-source-crawl-base,父 design §6)。
 *
 * <p>数据型源(乘联会销量表等)内容多为 HTML 表格。若不先转行文本,直接交给 {@code TextChunker} 会被
 * 「段内换行转空格」压平,数值/行列对应关系丢失。本类把每个 {@code <table>} 转成
 * 「列1 | 列2 | 列3」(每行一条,空单元格保留占位),使下游切块后仍可检索命中数值。
 *
 * <p>纯静态、无 Spring 依赖、可单测。
 */
public final class SourceTableParser {

    /** 单元格分隔符(与 markdown 表格一致,直观且不与正文句读冲突)。 */
    static final String CELL_SEP = " | ";

    private SourceTableParser() {
    }

    /**
     * 把 HTML 中匹配 {@code tableSelector} 的表格逐个转成行文本。
     *
     * @param root          正文容器(或整页)
     * @param tableSelector 表格选择器(null/空白则用 {@code table})
     * @return 每张表格一段(多行用 {@code \n} 连接);无表格返回空列表
     */
    public static List<String> parseTables(Element root, String tableSelector) {
        if (root == null) return List.of();
        String sel = (tableSelector == null || tableSelector.isBlank()) ? "table" : tableSelector;
        Elements tables = root.select(sel);
        List<String> out = new ArrayList<>();
        for (Element table : tables) {
            String text = tableToText(table);
            if (!text.isBlank()) out.add(text);
        }
        return out;
    }

    /**
     * 列表型结构化抽取(10-09-cpca-gasgoo-collection,design §4):把 {@code listRows} 命中的每个行元素
     * 内的 {@code rowCells} 单元格文本用 {@link #CELL_SEP} 拼成一行,{@code \n} 连接。
     *
     * <p>用于无 {@code <table>} 的排行/榜单(如盖世 {@code /qcxl} 的 {@code div.data ul li});
     * 与 {@link #parseTables} 并列,先转表格再转列表行,均并入正文块序列,保留行/列语义以免下游
     * {@code TextChunker} 压平数值。{@code rowCells} 缺省取整行文本(单格)。
     *
     * @param root        正文容器(或整页)
     * @param listRows    行元素选择器(空/空白则不处理,返回空列表=零回归)
     * @param rowCells    行内单元格选择器(空/空白则整行取文本)
     * @return 所有行合成的一段文本;无命中返回空列表
     */
    public static List<String> parseListRows(Element root, String listRows, String rowCells) {
        if (root == null) return List.of();
        if (listRows == null || listRows.isBlank()) return List.of();
        Elements rows = root.select(listRows);
        StringBuilder sb = new StringBuilder();
        for (Element row : rows) {
            List<String> cells = new ArrayList<>();
            if (rowCells != null && !rowCells.isBlank()) {
                for (Element cell : row.select(rowCells)) {
                    cells.add(normalize(cell.text()));
                }
            } else {
                cells.add(normalize(row.text()));
            }
            if (cells.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(String.join(CELL_SEP, cells));
        }
        return sb.length() == 0 ? List.of() : List.of(sb.toString());
    }

    /** 单元格文本归一(NBSP→空格、连续空白压成单个、trim)。 */
    private static String normalize(String raw) {
        if (raw == null) return "";
        return raw.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
    }

    /** 单张表格 → 行文本(每行 {@code \n},空表返回空串)。 */
    public static String tableToText(Element table) {
        if (table == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Element tr : table.select("tr")) {
            List<String> cells = new ArrayList<>();
            // 同时覆盖 td/th(表头行与数据行均保留);colspan 不展开,保持简单可预期
            for (Element cell : tr.select("td, th")) {
                cells.add(cell.text().replace('\u00a0', ' ').replaceAll("\\s+", " ").trim());
            }
            if (cells.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(String.join(CELL_SEP, cells));
        }
        return sb.toString();
    }
}
