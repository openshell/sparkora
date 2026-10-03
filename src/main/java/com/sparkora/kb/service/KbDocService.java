package com.sparkora.kb.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.ai.EmbedStats;
import com.sparkora.ai.EmbeddingBatchRunner;
import com.sparkora.ai.TextChunker;
import com.sparkora.car.client.EmbeddingClient;
import com.sparkora.domain.entity.KbChunkEntity;
import com.sparkora.domain.entity.KbDocEntity;
import com.sparkora.domain.entity.KbDocTagEntity;
import com.sparkora.kb.KbDomain;
import com.sparkora.mapper.KbChunkMapper;
import com.sparkora.mapper.KbDocMapper;
import com.sparkora.mapper.KbDocTagMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 通用汽车知识库文档服务(S7 车型库泛化;10-03 E3 数据模型规范化)。
 *
 * 数据流:create/update → 切块(首行「知识:标题(领域)」)→ 逐块 embedding 入库 + store 元数据。
 * 重建幂等:先清 chunk+embedding(物理删),再重切重嵌(与 CarChunkService.rebuildForModel 同款先清后插)。
 * embedding 单块失败:warn 跳过 + 计数返回(不静默;块缺失可用 rebuild 补齐)。
 * 切块算法:委托 {@link TextChunker}(09-27 知识域写入侧统一;空正文恒保留标题块)。
 *
 * <p>事务边界(09-27 统一为 IMAGE 范式):embedding 网络调用在事务外,向量写入经自注入代理走
 * {@code REQUIRES_NEW} 独立事务——失败回滚不留孤儿块,且不污染调用方事务。
 */
@Slf4j
@Service
public class KbDocService {

    /** 标签名长度上限(schema 列宽 VARCHAR(50))。 */
    private static final int TAG_MAX_LEN = 50;

    private final KbDocMapper docMapper;
    private final KbChunkMapper chunkMapper;
    private final EmbeddingClient embeddingClient;
    private final EmbeddingBatchRunner batchRunner;
    private final ObjectMapper json;
    /** 自注入代理（@Lazy）：让 {@link #persistChunk} 的 REQUIRES_NEW 事务真的生效（this 调用不走代理）。 */
    @Autowired
    @Lazy
    private KbDocService self;
    /** 单表 store（10-03 E1）；字段注入可选，单测直接 new 时为 null（同步守卫降级）。 */
    @Autowired(required = false)
    private com.sparkora.ai.vector.VectorStoreService vectorStoreService;
    /** KB 标签 mapper（10-03 E3）；字段注入可选，单测直接 new 时为 null（标签能力降级）。 */
    @Autowired(required = false)
    private KbDocTagMapper tagMapper;

    public KbDocService(KbDocMapper docMapper, KbChunkMapper chunkMapper,
                        EmbeddingClient embeddingClient,
                        EmbeddingBatchRunner batchRunner, ObjectMapper json) {
        this.docMapper = docMapper;
        this.chunkMapper = chunkMapper;
        this.embeddingClient = embeddingClient;
        this.batchRunner = batchRunner;
        this.json = json;
    }

    // ==================== 写路径 ====================

    /** 新建知识文档(兼容旧签名:无 source/标签/生效期)。 */
    public KbDocEntity create(String title, String domain, String content, String createdBy) {
        return create(title, domain, null, null, null, null, content, createdBy);
    }

    /** 新建知识文档(校验后入库并立即切块向量化)。10-03 E3:落 source/生效期 + 标签关联。 */
    public KbDocEntity create(String title, String domain, String source, List<String> tags,
                              LocalDate effectiveFrom, LocalDate effectiveTo,
                              String content, String createdBy) {
        validate(title, content);
        // 失败先于写库:标签 normalize 可能抛(超长),必须在任何 INSERT 之前 fail-fast,
        // 否则 doc 已落库后才抛 → 响应 400 但留下孤儿文档,重试产生重复(quality-guidelines「多步写入失败顺序」)。
        List<String> normTags = normalizeTags(tags);
        String operator = (createdBy == null || createdBy.isBlank()) ? "system" : createdBy;
        KbDocEntity d = new KbDocEntity();
        d.setTitle(title.trim());
        d.setDomain(KbDomain.normalize(domain));
        d.setSource(blankToNull(source));
        d.setEffectiveFrom(effectiveFrom);
        d.setEffectiveTo(effectiveTo);
        d.setContent(content);
        d.setEnabled(true);
        d.setCreatedBy(operator);
        d.setCreatedAt(LocalDateTime.now());
        d.setUpdatedAt(LocalDateTime.now());
        docMapper.insert(d);
        replaceTags(d.getId(), normTags, operator);
        rebuild(d.getId());
        return d;
    }

    /** 更新知识文档(兼容旧签名)。 */
    public KbDocEntity update(Long id, String title, String domain, String content, Boolean enabled) {
        return update(id, title, domain, null, null, null, null, content, enabled, null);
    }

    /**
     * 更新知识文档(内容变更即重建向量;停用同样触发重建以清块)。
     *
     * <p>10-03 E3:source/effectiveFrom/effectiveTo 为**全量覆盖**语义(null = 清空),故用
     * {@link UpdateWrapper} 无条件 set(MyBatis-Plus {@code updateById} 的 NOT_NULL 策略会跳过 null,
     * 无法清列)。tags 为 null 表示**不改动**,非 null(含空列表)=全量覆盖。
     */
    public KbDocEntity update(Long id, String title, String domain, String source, List<String> tags,
                              LocalDate effectiveFrom, LocalDate effectiveTo,
                              String content, Boolean enabled, String operator) {
        validate(title, content);
        KbDocEntity d = docMapper.selectById(id);
        if (d == null) throw new IllegalArgumentException("知识文档不存在");
        // 失败先于写库:tags 非 null 时先 normalize(fail-fast),避免 doc 已更新后才抛(quality-guidelines「多步写入失败顺序」)。
        List<String> normTags = tags == null ? null : normalizeTags(tags);
        String newDomain = KbDomain.normalize(domain);
        Boolean newEnabled = enabled == null ? d.getEnabled() : enabled;
        String newSource = blankToNull(source);
        LocalDateTime now = LocalDateTime.now();
        docMapper.update(null, new UpdateWrapper<KbDocEntity>()
                .eq("id", id)
                .set("title", title.trim())
                .set("domain", newDomain)
                .set("source", newSource)                 // 无条件 → null 真正清库
                .set("effective_from", effectiveFrom)
                .set("effective_to", effectiveTo)
                .set("content", content)
                .set("enabled", newEnabled)
                .set("updated_at", now));
        if (normTags != null) replaceTags(id, normTags, operator == null ? d.getCreatedBy() : operator);
        rebuild(id);
        return docMapper.selectById(id);
    }

    /** 删除(逻辑删文档 + 物理清块/向量/标签)。 */
    @Transactional
    public void delete(Long id) {
        KbDocEntity d = docMapper.selectById(id);
        if (d == null) return;
        docMapper.deleteById(id);
        deleteChunks(id);
        if (tagMapper != null) tagMapper.deleteByDocId(id);
    }

    // ==================== 向量重建 ====================

    /** 重建向量(幂等先清后插):非启用文档清块后直接返回。串行无重试(KB 失败策略不变)。 */
    public EmbedStats rebuild(Long docId) {
        deleteChunks(docId);
        KbDocEntity d = docMapper.selectById(docId);
        if (d == null || !Boolean.TRUE.equals(d.getEnabled())) {
            return new EmbedStats(0, 0, 0);
        }
        boolean active = isActive(d.getEnabled(), d.getEffectiveFrom(), d.getEffectiveTo(), LocalDate.now());
        List<String> tags = tagNamesOf(docId);
        List<String> chunks = chunkContent(d.getTitle(), d.getDomain(), d.getContent());
        List<KbChunkEntity> entities = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            KbChunkEntity c = new KbChunkEntity();
            c.setDocId(docId);
            c.setSeq(i);
            c.setChunkText(chunks.get(i));
            c.setDocTitle(d.getTitle());          // 10-03 E1:store metadata.name
            c.setStoreActive(active);              // 10-03 E3:生效期 → store metadata.active
            c.setSource(d.getSource());
            c.setEffectiveFrom(d.getEffectiveFrom());
            c.setEffectiveTo(d.getEffectiveTo());
            c.setTags(tags);
            entities.add(c);
        }
        return batchRunner.run(entities, KbChunkEntity::getChunkText,
                (c, vec) -> (self == null ? this : self).persistChunk(c, vec),
                "KB docId=" + docId, 1, 0);
    }

    /**
     * 持久化单个块 + 向量(先插 chunk 拿 id,再插 embedding)——独立事务边界,
     * 见类注释的事务隔离说明。失败则 chunk 与向量一并回滚(不留孤儿块)。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistChunk(KbChunkEntity c, String vec) {
        c.setCreatedAt(LocalDateTime.now());
        chunkMapper.insert(c);
        // 10-03 E6:旧向量表已退役,只写单表 store
        if (vectorStoreService != null) {
            vectorStoreService.upsert(com.sparkora.ai.vector.VectorDomain.KB.name(), c.getId(), null,
                    "KB_CHUNK", c.getDocTitle(),
                    c.getStoreActive() == null || c.getStoreActive(),
                    embeddingClient.modelName(), c.getChunkText(), vec, kbMeta(c));
        }
    }

    /** 10-03 E3:KB store metadata 扩展键(source/生效期/标签),空值不写。 */
    private static Map<String, Object> kbMeta(KbChunkEntity c) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (c.getSource() != null) m.put("source", c.getSource());
        if (c.getEffectiveFrom() != null) m.put("effectiveFrom", c.getEffectiveFrom().toString());
        if (c.getEffectiveTo() != null) m.put("effectiveTo", c.getEffectiveTo().toString());
        if (c.getTags() != null && !c.getTags().isEmpty()) m.put("tags", c.getTags());
        return m;
    }

    // ==================== 读路径 ====================

    /** 列表(含块数统计 + 标签)。 */
    public List<Map<String, Object>> list() {
        List<KbDocEntity> docs = docMapper.selectList(
                new QueryWrapper<KbDocEntity>().orderByDesc("updated_at"));
        Map<Long, List<String>> tagMap = fillTags(docs);
        List<Map<String, Object>> out = new ArrayList<>();
        for (KbDocEntity d : docs) out.add(toVo(d, tagMap.getOrDefault(d.getId(), List.of())));
        return out;
    }

    /** 详情(含 content + 标签)。 */
    public Map<String, Object> get(Long id) {
        KbDocEntity d = docMapper.selectById(id);
        if (d == null) throw new IllegalArgumentException("知识文档不存在");
        Map<String, Object> vo = toVo(d, tagNamesOf(id));
        vo.put("content", d.getContent());
        return vo;
    }

    private Map<String, Object> toVo(KbDocEntity d, List<String> tags) {
        Long cnt = chunkMapper.selectCount(new QueryWrapper<KbChunkEntity>().eq("doc_id", d.getId()));
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", d.getId());
        vo.put("title", d.getTitle());
        vo.put("domain", d.getDomain());
        vo.put("source", d.getSource());
        vo.put("tags", tags);
        vo.put("effectiveFrom", d.getEffectiveFrom());
        vo.put("effectiveTo", d.getEffectiveTo());
        vo.put("enabled", d.getEnabled());
        vo.put("chunkCount", cnt == null ? 0 : cnt);
        vo.put("updatedAt", d.getUpdatedAt());
        return vo;
    }

    /** 物理清块与向量(重建/删除共用)。10-03 E1:同步删除单表 store 行(先读块 id 再删)。 */
    private void deleteChunks(Long docId) {
        List<KbChunkEntity> chunks = chunkMapper.selectList(
                new QueryWrapper<KbChunkEntity>().eq("doc_id", docId));
        List<Long> chunkIds = new ArrayList<>();
        for (KbChunkEntity c : chunks) if (c.getId() != null) chunkIds.add(c.getId());
        docMapper.deleteChunksByDocId(docId);
        if (vectorStoreService != null && !chunkIds.isEmpty()) {
            vectorStoreService.deleteByRef(com.sparkora.ai.vector.VectorDomain.KB.name(), chunkIds);
        }
    }

    // ==================== 标签 ====================

    /** 标签规范化:trim、去空、去重(保序)、单项 ≤50。 */
    static List<String> normalizeTags(List<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String s : raw) {
            if (s == null) continue;
            String t = s.trim();
            if (t.isEmpty()) continue;
            if (t.length() > TAG_MAX_LEN) throw new IllegalArgumentException("标签长度须为1~50字符");
            out.add(t);
        }
        return List.copyOf(new LinkedHashSet<>(out));
    }

    /** 全量覆盖标签(先物理清后插);空列表 = 清空。tagMapper 为空(单测)时降级跳过。 */
    void replaceTags(Long docId, List<String> tags, String operator) {
        if (tagMapper == null || docId == null) return;
        List<String> norm = normalizeTags(tags);
        tagMapper.deleteByDocId(docId);
        if (norm.isEmpty()) return;
        String op = (operator == null || operator.isBlank()) ? "system" : operator;
        for (String tag : norm) {
            try {
                KbDocTagEntity e = new KbDocTagEntity();
                e.setDocId(docId);
                e.setTagName(tag);
                e.setCreatedBy(op);
                e.setCreatedAt(LocalDateTime.now());
                tagMapper.insert(e);
            } catch (DuplicateKeyException ex) {
                log.debug("KB 标签已存在(跳过) doc={} tag={}", docId, tag);
            }
        }
    }

    /** 某文档标签(按名称升序)。 */
    List<String> tagNamesOf(Long docId) {
        if (tagMapper == null || docId == null) return List.of();
        return tagMapper.selectList(new QueryWrapper<KbDocTagEntity>().eq("doc_id", docId))
                .stream().map(KbDocTagEntity::getTagName).sorted().toList();
    }

    /** 批量回填标签(避免 N+1):一次查全部关系行按 doc 分组。 */
    private Map<Long, List<String>> fillTags(List<KbDocEntity> docs) {
        if (tagMapper == null || docs == null || docs.isEmpty()) return Map.of();
        List<Long> ids = docs.stream().map(KbDocEntity::getId).filter(java.util.Objects::nonNull).toList();
        if (ids.isEmpty()) return Map.of();
        return tagMapper.selectList(new QueryWrapper<KbDocTagEntity>().in("doc_id", ids))
                .stream().collect(Collectors.groupingBy(KbDocTagEntity::getDocId,
                        Collectors.mapping(KbDocTagEntity::getTagName, Collectors.toList())));
    }

    // ==================== 生效期 ====================

    /**
     * 生效期判定(10-03 E3,纯静态可单测):{@code enabled && 今天∈[from,to]},边界含端点;
     * null = 不限。
     */
    public static boolean isActive(Boolean enabled, LocalDate from, LocalDate to, LocalDate today) {
        if (!Boolean.TRUE.equals(enabled)) return false;
        if (from != null && today.isBefore(from)) return false;
        if (to != null && today.isAfter(to)) return false;
        return true;
    }

    // ==================== 工具 ====================

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private void validate(String title, String content) {
        if (title == null || title.isBlank()) throw new IllegalArgumentException("标题不能为空");
        if (title.length() > 200) throw new IllegalArgumentException("标题不能超过 200 字");
        if (content == null || content.isBlank()) throw new IllegalArgumentException("正文不能为空");
    }

    /**
     * 切块(薄委托 {@link TextChunker},09-27 统一实现):
     * 首行固定「知识:<title>(<domain>)」;空正文恒保留标题块(KB 语义);超长段按句读切分合并。
     * 10-03 E2:显式启用 {@link TextChunker#DEFAULT_OVERLAP_CHARS} 滑动重叠(减少跨块边界语义切断)。
     */
    static List<String> chunkContent(String title, String domain, String content) {
        String header = "知识：" + (title == null ? "" : title.trim()) + "（" + (domain == null ? "通用" : domain.trim()) + "）";
        return TextChunker.chunk(header, content, true, true, TextChunker.KB_SEPARATORS,
                TextChunker.DEFAULT_OVERLAP_CHARS);
    }
}
