package com.sparkora.kb.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.car.client.EmbeddingClient;
import com.sparkora.domain.entity.KbChunkEntity;
import com.sparkora.domain.entity.KbDocEntity;
import com.sparkora.mapper.KbChunkEmbeddingMapper;
import com.sparkora.mapper.KbChunkMapper;
import com.sparkora.mapper.KbDocMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用汽车知识库文档服务(S7 车型库泛化)。
 *
 * 数据流:create/update → 切块(首行「知识:标题(领域)」)→ 逐块 embedding 入库。
 * 重建幂等:先清 chunk+embedding(物理删),再重切重嵌(与 CarDocService.rebuildForModel 同款先清后插)。
 * embedding 单块失败:warn 跳过 + 计数返回(不静默;块缺失可用 rebuild 补齐)。
 * 切块算法:空行分段;单段 ≤500 字符直接成块;超长段按句读(。;;!?)切分合并。
 */
@Slf4j
@Service
public class KbDocService {

    /** 单块正文目标上限(不含首行标题)。 */
    static final int MAX_BODY_LEN = 500;

    private final KbDocMapper docMapper;
    private final KbChunkMapper chunkMapper;
    private final KbChunkEmbeddingMapper embMapper;
    private final EmbeddingClient embeddingClient;
    private final ObjectMapper json;

    public KbDocService(KbDocMapper docMapper, KbChunkMapper chunkMapper,
                        KbChunkEmbeddingMapper embMapper, EmbeddingClient embeddingClient,
                        ObjectMapper json) {
        this.docMapper = docMapper;
        this.chunkMapper = chunkMapper;
        this.embMapper = embMapper;
        this.embeddingClient = embeddingClient;
        this.json = json;
    }

    /** 向量化结果(可观测):成功/失败块计数,供前端提示「部分块未向量化」。 */
    public record EmbedStats(int total, int success, int failed) {}

    /** 新建知识文档(校验后入库并立即切块向量化)。 */
    public KbDocEntity create(String title, String domain, String content, String createdBy) {
        validate(title, content);
        KbDocEntity d = new KbDocEntity();
        d.setTitle(title.trim());
        d.setDomain((domain == null || domain.isBlank()) ? "通用" : domain.trim());
        d.setContent(content);
        d.setEnabled(true);
        d.setCreatedBy(createdBy == null || createdBy.isBlank() ? "system" : createdBy);
        d.setCreatedAt(LocalDateTime.now());
        d.setUpdatedAt(LocalDateTime.now());
        docMapper.insert(d);
        rebuild(d.getId());
        return d;
    }

    /** 更新知识文档(内容变更即重建向量;停用同样触发重建以清块)。 */
    public KbDocEntity update(Long id, String title, String domain, String content, Boolean enabled) {
        validate(title, content);
        KbDocEntity d = docMapper.selectById(id);
        if (d == null) throw new IllegalArgumentException("知识文档不存在");
        if (title != null) d.setTitle(title.trim());
        if (domain != null && !domain.isBlank()) d.setDomain(domain.trim());
        if (content != null) d.setContent(content);
        if (enabled != null) d.setEnabled(enabled);
        d.setUpdatedAt(LocalDateTime.now());
        docMapper.updateById(d);
        rebuild(id);
        return d;
    }

    /** 删除(逻辑删文档 + 物理清块与向量)。 */
    @Transactional
    public void delete(Long id) {
        KbDocEntity d = docMapper.selectById(id);
        if (d == null) return;
        docMapper.deleteById(id);
        deleteChunks(id);
    }

    /** 重建向量(幂等先清后插):非启用文档清块后直接返回。 */
    public EmbedStats rebuild(Long docId) {
        deleteChunks(docId);
        KbDocEntity d = docMapper.selectById(docId);
        if (d == null || !Boolean.TRUE.equals(d.getEnabled())) {
            return new EmbedStats(0, 0, 0);
        }
        List<String> chunks = chunkContent(d.getTitle(), d.getDomain(), d.getContent());
        int ok = 0, fail = 0;
        for (int i = 0; i < chunks.size(); i++) {
            KbChunkEntity c = new KbChunkEntity();
            c.setDocId(docId);
            c.setSeq(i);
            c.setChunkText(chunks.get(i));
            c.setCreatedAt(LocalDateTime.now());
            chunkMapper.insert(c);
            try {
                String vec = embeddingClient.embed(c.getChunkText());
                embMapper.insert(c.getId(), vec);
                ok++;
            } catch (Exception e) {
                fail++;
                log.warn("KB 块向量化失败 docId={} seq={}: {}", docId, i, e.getMessage());
            }
        }
        if (fail > 0) {
            log.warn("KB 文档向量重建完成(有缺失) docId={} 成功 {}/{} 失败 {}", docId, ok, chunks.size(), fail);
        } else {
            log.info("KB 文档向量重建完成 docId={} 成功 {}/{}", docId, ok, chunks.size());
        }
        return new EmbedStats(chunks.size(), ok, fail);
    }

    /** 列表(含块数统计)。 */
    public List<Map<String, Object>> list() {
        List<KbDocEntity> docs = docMapper.selectList(
                new QueryWrapper<KbDocEntity>().orderByDesc("updated_at"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (KbDocEntity d : docs) {
            out.add(toVo(d));
        }
        return out;
    }

    /** 详情(含 content)。 */
    public Map<String, Object> get(Long id) {
        KbDocEntity d = docMapper.selectById(id);
        if (d == null) throw new IllegalArgumentException("知识文档不存在");
        Map<String, Object> vo = toVo(d);
        vo.put("content", d.getContent());
        return vo;
    }

    private Map<String, Object> toVo(KbDocEntity d) {
        Long cnt = chunkMapper.selectCount(new QueryWrapper<KbChunkEntity>().eq("doc_id", d.getId()));
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", d.getId());
        vo.put("title", d.getTitle());
        vo.put("domain", d.getDomain());
        vo.put("enabled", d.getEnabled());
        vo.put("chunkCount", cnt == null ? 0 : cnt);
        vo.put("updatedAt", d.getUpdatedAt());
        return vo;
    }

    /** 物理清块与向量(重建/删除共用)。 */
    private void deleteChunks(Long docId) {
        embMapper.deleteByDocId(docId);
        docMapper.deleteChunksByDocId(docId);
    }

    private void validate(String title, String content) {
        if (title == null || title.isBlank()) throw new IllegalArgumentException("标题不能为空");
        if (title.length() > 200) throw new IllegalArgumentException("标题不能超过 200 字");
        if (content == null || content.isBlank()) throw new IllegalArgumentException("正文不能为空");
    }

    /**
     * 切块算法(纯函数,便于单测):
     * 1) 每块首行固定「知识:<title>(<domain>)」(跨域检索主题锚点);
     * 2) 正文按空行分段;单段 ≤MAX_BODY_LEN 直接成块;
     * 3) 超长段按句读(。;;!?)切分并合并至 ≤MAX_BODY_LEN。
     */
    static List<String> chunkContent(String title, String domain, String content) {
        String header = "知识：" + (title == null ? "" : title.trim()) + "（" + (domain == null ? "通用" : domain.trim()) + "）";
        List<String> out = new ArrayList<>();
        // 兜底:正文无有效字符(全空白)时保留标题块(校验层已拦空,这里防御性兜底)
        if (content == null || content.strip().isEmpty()) {
            out.add(header);
            return out;
        }
        // 空行分段
        String[] paragraphs = content.split("\\n\\s*\\n");
        List<String> bodies = new ArrayList<>();
        StringBuilder carry = null;   // 超长段切分后的合并中转
        for (String pRaw : paragraphs) {
            String p = pRaw.replaceAll("\\s*\\n\\s*", " ").trim();   // 段内换行转空格
            if (p.isEmpty()) continue;
            if (p.length() <= MAX_BODY_LEN) {
                if (carry != null) { bodies.add(carry.toString()); carry = null; }
                bodies.add(p);
                continue;
            }
            // 超长段:按句读切分
            List<String> sentences = splitSentences(p);
            for (String s : sentences) {
                if (carry == null) {
                    carry = new StringBuilder(s);
                } else if (carry.length() + s.length() <= MAX_BODY_LEN) {
                    carry.append(s);
                } else {
                    bodies.add(carry.toString());
                    carry = new StringBuilder(s);
                }
            }
        }
        if (carry != null) bodies.add(carry.toString());
        for (String b : bodies) {
            out.add(header + "\n" + b);
        }
        if (out.isEmpty()) out.add(header);   // 双保险:正文全为符号等极端情况
        return out;
    }

    /** 按句读切分(。;;!?),保留分隔符;无句读的长段按 MAX_BODY_LEN 硬切。 */
    private static List<String> splitSentences(String p) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < p.length(); i++) {
            char ch = p.charAt(i);
            cur.append(ch);
            if (ch == '。' || ch == '；' || ch == '；' || ch == '!' || ch == '!' || ch == '?' || ch == '?') {
                String s = cur.toString();
                if (!s.isBlank()) out.add(s);
                cur.setLength(0);
            }
        }
        if (!cur.isEmpty()) {
            String tail = cur.toString();
            if (tail.length() <= MAX_BODY_LEN) {
                if (!tail.isBlank()) out.add(tail);
            } else {
                // 无句读的超长尾巴硬切
                for (int i = 0; i < tail.length(); i += MAX_BODY_LEN) {
                    out.add(tail.substring(i, Math.min(tail.length(), i + MAX_BODY_LEN)));
                }
            }
        }
        return out;
    }
}