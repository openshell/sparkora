package com.sparkora.ai;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数值签名工具（10-03 E5 从 {@code ClaimSimilarity} 抽出的 public 单一实现）。
 *
 * <p>原实现位于 {@code com.sparkora.deep.service.ClaimSimilarity}（包级可见），C7「正文数值回查」与
 * 事实手册 claim 归并均复用其口径。E5「覆盖度三域统一」需要 CAR/KB/NEWS 抽取数值事实时保持同一口径，
 * 但 {@code CarRagService} 在 {@code com.sparkora.car.service} 包、无法访问包级成员；为避免
 * 「再抄一套数值归一」与「car→deep 反向包依赖」，把纯静态逻辑上移到中立的 {@code com.sparkora.ai}。
 *
 * <p>{@code ClaimSimilarity.numberValues} 现委托本类，行为**逐字不变**（既有单测锁定）。
 */
public final class NumericSignature {

    /** 数值 token:纯数字/千分位/小数 + 可选「万/亿」单位(如 2000、239,900、20万、1.5亿)。 */
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*\\s*(?:万|亿)?");

    /** 数值签名排序:优先按数值大小，不可解析时按字符串(保证输出稳定)。 */
    private static final Comparator<String> NUMBER_ORDER = (x, y) -> {
        try {
            return new BigDecimal(x).compareTo(new BigDecimal(y));
        } catch (NumberFormatException e) {
            return x.compareTo(y);
        }
    };

    private NumericSignature() {
    }

    /**
     * 抽取文本中的数值签名:归一化(去千分位/空白；万×10000、亿×1e8)后去重、稳定排序。
     *
     * <p>用 {@link BigDecimal} 归一比较，使 {@code 200000} 与 {@code 20万} 视为同一数值。
     * 解析失败时回退为原 token 字符串，绝不抛出异常。
     *
     * @param texts 待抽取文本(claim 与 value 可一并传入，数值来自两者任一处)
     */
    public static List<String> numberValues(String... texts) {
        Set<String> out = new TreeSet<>(NUMBER_ORDER);
        if (texts != null) {
            for (String t : texts) {
                if (t == null) continue;
                Matcher m = NUMBER.matcher(t);
                while (m.find()) {
                    String v = normalizeNumber(m.group());
                    if (v != null && !v.isEmpty()) out.add(v);
                }
            }
        }
        return new ArrayList<>(out);
    }

    /** 单个数值 token 归一:去空白/千分位，万/亿换算，BigDecimal 规范化;失败回退原 token。 */
    private static String normalizeNumber(String token) {
        if (token == null) return null;
        String t = token.replaceAll("[\\s,]", "");
        if (t.isEmpty()) return null;
        String unit = "";
        if (t.endsWith("万")) {
            unit = "万";
            t = t.substring(0, t.length() - 1);
        } else if (t.endsWith("亿")) {
            unit = "亿";
            t = t.substring(0, t.length() - 1);
        }
        if (t.isEmpty()) return null;
        try {
            BigDecimal bd = new BigDecimal(t);
            if ("万".equals(unit)) bd = bd.multiply(new BigDecimal("10000"));
            else if ("亿".equals(unit)) bd = bd.multiply(new BigDecimal("100000000"));
            return bd.stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            // 解析失败回退原 token(去空白)，保证签名提取永不抛异常
            return token.trim().replaceAll("\\s+", "");
        }
    }
}
