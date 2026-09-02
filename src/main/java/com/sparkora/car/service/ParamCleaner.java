package com.sparkora.car.service;

import com.sparkora.car.dto.ParamCleanResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 参数规则引擎清洗器。基于官网 goodsParams 数据结构(已实测分析)编写规则:
 *  - 符号解析: ●→有 / ○→可选装 / —→无 (ENUM)
 *  - 数值提取: 从参数名提取单位, "2820"→{NUMBER, 2820, mm}
 *  - 多值拆分: "4810×1920×1675"→LIST、"1650/1670"→LIST、"190/长续航模式205"→LIST
 *  - 颜色/多行: "●\n曜石黑\n远山灰"→LIST(去符号)
 *
 * 规则覆盖不了的复合/歧义值返回 null,由 AI 兜底。
 */
@Slf4j
@Component
public class ParamCleaner {

    /** 参数名中的单位提取:如 轴距(mm) / 电池容量（kWh） / 0-100km/h加速时间（s）。 */
    private static final Pattern UNIT_PATTERN = Pattern.compile(
            "[（(]\\s*([0-9.]+(?:/[0-9.]+)?(?:km/h)?[a-zA-Z%·×/]*|[a-zA-Z%·×/]+)\\s*[）)]");

    /** 纯数值(可带空格/千分位)。 */
    private static final Pattern NUMERIC_PATTERN = Pattern.compile("^\\s*([0-9][0-9,.]*)\\s*$");

    /** 无空格数字对分隔符(如 1650/1670 / 255/50)。 */
    private static final Pattern NUMERIC_SLASH = Pattern.compile("^\\s*([0-9.]+)/([0-9.]+)\\s*$");

    /** 符号标记。 */
    private static final String HAS = "●";
    private static final String OPTIONAL = "○";
    private static final String NONE = "—";

    /**
     * 清洗单个参数值。
     * @param paramName 原始参数名(如 轴距(mm))
     * @param rawValue  原始值(如 "2820" / "●" / "4810×1920×1675")
     * @return 清洗结果;规则覆盖不了返回 null(由 AI 兜底)
     */
    public ParamCleanResult clean(String paramName, String rawValue) {
        if (rawValue == null) return null;
        String v = rawValue.trim();
        if (v.isEmpty()) return null;

        ParamCleanResult r = new ParamCleanResult();
        r.setParamKey(normalizeKey(paramName));
        r.setCleanMethod("RULE");
        String unit = extractUnit(paramName);

        // 1) 符号解析(纯符号或符号+文本)
        if (isPureSymbol(v)) {
            r.setValueType("ENUM");
            r.setEnumValue(symbolToEnum(v));
            r.setValue(symbolToEnum(v));
            return r;
        }

        // 2) 纯数值
        Matcher num = NUMERIC_PATTERN.matcher(v);
        if (num.matches()) {
            r.setValueType("NUMBER");
            r.setNumericValue(parseNumber(num.group(1)));
            r.setUnit(unit);
            r.setValue(r.getNumericValue().toPlainString());
            return r;
        }

        // 3) 无空格数字对(如 1650/1670 轮距, 255/50 轮胎) → 拆成 LIST
        Matcher slash = NUMERIC_SLASH.matcher(v);
        if (slash.matches()) {
            r.setValueType("LIST");
            r.setListValues(List.of(slash.group(1), slash.group(2)));
            r.setValue(slash.group(1) + "/" + slash.group(2));
            r.setUnit(unit);
            return r;
        }

        // 4) × 分隔(如 4810×1920×1675 长宽高) → 拆成 LIST
        if (v.contains("×")) {
            List<String> parts = splitBy(v, "×");
            if (parts.size() > 1) {
                r.setValueType("LIST");
                r.setListValues(parts);
                r.setValue(String.join("×", parts));
                r.setUnit(unit);
                return r;
            }
        }

        // 5) 多行(颜色列表等,去符号)
        if (v.contains("\n")) {
            List<String> lines = new ArrayList<>();
            for (String line : v.split("\n")) {
                String t = line.trim();
                if (t.isEmpty() || isPureSymbol(t)) continue;
                lines.add(t);
            }
            if (!lines.isEmpty()) {
                r.setValueType("LIST");
                r.setListValues(lines);
                r.setValue(String.join("、", lines));
                return r;
            }
        }

        // 6) 带符号的文本(如 "● 有" 之类)
        if (v.contains(HAS) || v.contains(OPTIONAL) || v.contains(NONE)) {
            String cleaned = v.replace(HAS, "").replace(OPTIONAL, "").replace(NONE, "").trim();
            if (!cleaned.isEmpty()) {
                r.setValueType("STRING");
                r.setValue(cleaned);
                return r;
            }
        }

        // 7) 纯文本
        r.setValueType("STRING");
        r.setValue(v);
        return r;
    }

    /** 规范化参数名:去单位括号,如 "轴距(mm)"→"轴距"。 */
    private String normalizeKey(String paramName) {
        if (paramName == null) return "";
        String k = paramName.replaceAll("[（(]\\s*[0-9.]+(?:/[0-9.]+)?(?:km/h)?[a-zA-Z%·×/]*\\s*[）)]", "").trim();
        return k.isEmpty() ? paramName.trim() : k;
    }

    /** 从参数名提取单位,如 "轴距(mm)"→"mm"。 */
    private String extractUnit(String paramName) {
        if (paramName == null) return null;
        Matcher m = UNIT_PATTERN.matcher(paramName);
        return m.find() ? m.group(1).trim() : null;
    }

    private boolean isPureSymbol(String v) {
        String t = v.replace(HAS, "").replace(OPTIONAL, "").replace(NONE, "").trim();
        return t.isEmpty();
    }

    private String symbolToEnum(String v) {
        if (v.contains(HAS)) return "有";
        if (v.contains(OPTIONAL)) return "可选装";
        if (v.contains(NONE)) return "无";
        return "有";
    }

    private BigDecimal parseNumber(String s) {
        try {
            return new BigDecimal(s.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private List<String> splitBy(String v, String sep) {
        List<String> out = new ArrayList<>();
        for (String part : v.split(java.util.regex.Pattern.quote(sep))) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
