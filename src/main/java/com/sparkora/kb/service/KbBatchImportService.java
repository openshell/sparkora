package com.sparkora.kb.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.domain.entity.KbDocEntity;
import com.sparkora.kb.KbBatchParser;
import com.sparkora.kb.KbDomain;
import com.sparkora.kb.KbImportRow;
import com.sparkora.mapper.KbDocMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * KB 批量导入服务(10-03 C 批量导入)。
 *
 * <p>职责:判定/解析来源文件 → 逐条委托 {@link KbDocService#create} → 汇总结果。
 * **不修改单条链路**,也不引入异步/事务整批回滚:单批同步、逐条隔离。
 *
 * <p>失败边界(quality-guidelines「多步写入失败顺序」):
 * <ul>
 *   <li>整体解析失败(格式无法识别、CSV 缺表头、JSON 非数组)抛 {@link IllegalArgumentException}
 *       → 控制器 400,**先于任何写库**。</li>
 *   <li>单条失败(非法 domain/空标题/空正文/超长/行级日期错)→ 计入 {@code results} 的失败项,
 *       其余继续;已落库部分不受影响。</li>
 *   <li>{@code title+domain} 重复(库内已有或本批内重复)→ **默认跳过** + 中文回报,不覆盖。</li>
 * </ul>
 */
@Service
public class KbBatchImportService {

    /** 单批条目上限(超出报错引导分批,不做异步)。 */
    public static final int MAX_ROWS = 200;
    /** 单条正文长度上限(对齐 KbDocSaveDto)。 */
    public static final int MAX_CONTENT_LEN = 50000;
    /** 单条来源长度上限(对齐 KbDocSaveDto / sparkora_kb_doc.source VARCHAR(200))。 */
    public static final int MAX_SOURCE_LEN = 200;
    /** 去重键分隔符(标题中不会出现的控制字符)。 */
    private static final String KEY_SEP = "\u0000";

    private final KbDocService docService;
    private final KbDocMapper docMapper;
    private final ObjectMapper json;

    public KbBatchImportService(KbDocService docService, KbDocMapper docMapper, ObjectMapper json) {
        this.docService = docService;
        this.docMapper = docMapper;
        this.json = json;
    }

    /**
     * 批量导入入口。
     *
     * @param fileName 上传文件名(用于格式判定与 Markdown 兜底标题)
     * @param format   显式格式(csv/json/md/markdown);null/空 = 按后缀/内容判定
     * @param text     文件文本内容
     * @param operator 操作人(空 → system)
     */
    public Map<String, Object> importBatch(String fileName, String format, String text, String operator) {
        String fmt = KbBatchParser.normalizeFormat(format);
        if (fmt == null) fmt = KbBatchParser.detectFormat(fileName, text);
        List<KbImportRow> rows = switch (fmt) {
            case "csv" -> KbBatchParser.parseCsv(text);
            case "json" -> KbBatchParser.parseJson(text, json);
            default -> KbBatchParser.parseMarkdown(text, markdownFallbackTitle(fileName));
        };
        if (rows.size() > MAX_ROWS) {
            throw new IllegalArgumentException("单批最多 " + MAX_ROWS + " 条,当前 " + rows.size() + " 条,请分批导入");
        }

        String op = (operator == null || operator.isBlank()) ? "system" : operator;
        Set<String> existing = existingKeys();
        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> results = new ArrayList<>();
        int success = 0;
        for (int i = 0; i < rows.size(); i++) {
            KbImportRow row = rows.get(i);
            String error = importOne(row, op, existing, seen);
            boolean ok = error == null;
            if (ok) success++;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("index", i);
            r.put("title", row.getTitle());
            r.put("success", ok);
            r.put("error", error);
            results.add(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", rows.size());
        out.put("success", success);
        out.put("failed", rows.size() - success);
        out.put("results", results);
        return out;
    }

    /**
     * 逐条导入:返回 {@code null} 表示成功,否则返回中文失败原因。
     * 校验/去重均先于写库,避免产生孤儿文档。
     */
    private String importOne(KbImportRow row, String operator, Set<String> existing, Set<String> seen) {
        if (row.getParseError() != null) return row.getParseError();
        String title = row.getTitle() == null ? null : row.getTitle().trim();
        if (title == null || title.isBlank()) return "标题不能为空";
        if (title.length() > 200) return "标题不能超过 200 字";
        String content = row.getContent();
        if (content == null || content.isBlank()) return "正文不能为空";
        if (content.length() > MAX_CONTENT_LEN) return "正文不能超过 " + MAX_CONTENT_LEN + " 字";
        // source 列 VARCHAR(200);批量链路绕过 KbDocSaveDto @Size,须在此 fail-fast 给出中文提示,
        // 否则会在 INSERT 时抛 DB 异常(非中文、可能含框架内部串)。
        String source = row.getSource();
        if (source != null && source.trim().length() > MAX_SOURCE_LEN) {
            return "来源不能超过 " + MAX_SOURCE_LEN + " 字";
        }

        String domain;
        try {
            domain = KbDomain.normalize(row.getDomain());
        } catch (IllegalArgumentException e) {
            return textOr(e, "领域标签不合法");
        }
        String key = title + KEY_SEP + domain;
        if (existing.contains(key) || seen.contains(key)) return "已存在同标题同领域文档，已跳过";
        seen.add(key);

        try {
            docService.create(title, domain, row.getSource(), row.getTags(),
                    row.getEffectiveFrom(), row.getEffectiveTo(), content, operator);
            return null;
        } catch (IllegalArgumentException e) {
            return textOr(e, "参数不合法");
        } catch (Exception e) {
            return "导入失败: " + textOr(e, "未知错误");
        }
    }

    /** 异常消息兜底:避免 message 为 null 时被当成成功(error==null)。 */
    private static String textOr(Throwable e, String fallback) {
        return e.getMessage() == null || e.getMessage().isBlank() ? fallback : e.getMessage();
    }

    /** 预取库内「标题 + 领域」集合(一次查询,避免逐条 check-then-set;@TableLogic 自动过滤已删除)。 */
    private Set<String> existingKeys() {
        Set<String> keys = new HashSet<>();
        List<KbDocEntity> docs = docMapper.selectList(new QueryWrapper<>());
        if (docs == null) return keys;
        for (KbDocEntity d : docs) {
            if (d == null || d.getTitle() == null || d.getDomain() == null) continue;
            keys.add(d.getTitle().trim() + KEY_SEP + d.getDomain());
        }
        return keys;
    }

    /** Markdown 无 H1 时整文件一篇:标题 = 文件名去后缀;空文件名 → 通用知识。 */
    static String markdownFallbackTitle(String fileName) {
        if (fileName == null || fileName.isBlank()) return "未命名知识";
        String name = fileName.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name.isBlank() ? "未命名知识" : name;
    }
}
