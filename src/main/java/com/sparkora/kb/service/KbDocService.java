package com.sparkora.kb.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.ai.EmbedStats;
import com.sparkora.ai.EmbeddingBatchRunner;
import com.sparkora.ai.TextChunker;
import com.sparkora.car.client.EmbeddingClient;
import com.sparkora.domain.entity.KbChunkEntity;
import com.sparkora.domain.entity.KbDocEntity;
import com.sparkora.mapper.KbChunkEmbeddingMapper;
import com.sparkora.mapper.KbChunkMapper;
import com.sparkora.mapper.KbDocMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
 * 切块算法:委托 {@link TextChunker}(09-27 知识域写入侧统一;空正文恒保留标题块)。
 *
 * 事务边界(09-27 统一为 IMAGE 范式):embedding 网络调用在事务外,向量写入经自注入代理走
 * {@code REQUIRES_NEW} 独立事务——失败回滚不留孤儿块,且不污染调用方事务。
 */
@Slf4j
@Service
public class KbDocService {

    private final KbDocMapper docMapper;
    private final KbChunkMapper chunkMapper;
    private final KbChunkEmbeddingMapper embMapper;
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

    public KbDocService(KbDocMapper docMapper, KbChunkMapper chunkMapper,
                        KbChunkEmbeddingMapper embMapper, EmbeddingClient embeddingClient,
                        EmbeddingBatchRunner batchRunner, ObjectMapper json) {
        this.docMapper = docMapper;
        this.chunkMapper = chunkMapper;
        this.embMapper = embMapper;
        this.embeddingClient = embeddingClient;
        this.batchRunner = batchRunner;
        this.json = json;
    }

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

    /** 重建向量(幂等先清后插):非启用文档清块后直接返回。串行无重试(KB 失败策略不变)。 */
    public EmbedStats rebuild(Long docId) {
        deleteChunks(docId);
        KbDocEntity d = docMapper.selectById(docId);
        if (d == null || !Boolean.TRUE.equals(d.getEnabled())) {
            return new EmbedStats(0, 0, 0);
        }
        List<String> chunks = chunkContent(d.getTitle(), d.getDomain(), d.getContent());
        List<KbChunkEntity> entities = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            KbChunkEntity c = new KbChunkEntity();
            c.setDocId(docId);
            c.setSeq(i);
            c.setChunkText(chunks.get(i));
            c.setDocTitle(d.getTitle());   // 10-03 E1:store metadata.name
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
        embMapper.insert(c.getId(), vec, embeddingClient.modelName());
        if (vectorStoreService != null) {
            vectorStoreService.upsert(com.sparkora.ai.vector.VectorDomain.KB.name(), c.getId(), null,
                    "KB_CHUNK", c.getDocTitle(), true, embeddingClient.modelName(), c.getChunkText(), vec);
        }
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

    /** 物理清块与向量(重建/删除共用)。10-03 E1:同步删除单表 store 行(先读块 id 再删)。 */
    private void deleteChunks(Long docId) {
        List<KbChunkEntity> chunks = chunkMapper.selectList(
                new QueryWrapper<KbChunkEntity>().eq("doc_id", docId));
        List<Long> chunkIds = new ArrayList<>();
        for (KbChunkEntity c : chunks) if (c.getId() != null) chunkIds.add(c.getId());
        embMapper.deleteByDocId(docId);
        docMapper.deleteChunksByDocId(docId);
        if (vectorStoreService != null && !chunkIds.isEmpty()) {
            vectorStoreService.deleteByRef(com.sparkora.ai.vector.VectorDomain.KB.name(), chunkIds);
        }
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
