package com.sparkora.car.client;

import com.sparkora.mapper.EmbeddingCacheMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 内容寻址嵌入缓存读写（10-03 E5）。
 *
 * <p>读为普通查询（失败降级为未命中，仅 warn）；写经 {@code REQUIRES_NEW} 独立事务——
 * 缓存写失败（磁盘/约束等）绝不污染调用方事务，也不影响主流程（best-effort，参考
 * database-guidelines.md「向量/派生数据写入需与调用方事务隔离」）。
 */
@Slf4j
@Service
public class EmbeddingCacheService {

    private final EmbeddingCacheMapper mapper;

    public EmbeddingCacheService(EmbeddingCacheMapper mapper) {
        this.mapper = mapper;
    }

    /** 命中返回缓存向量字面量；未命中或读取异常返回 null（调用方按未命中处理）。 */
    public String get(String contentHash, String embeddingModel) {
        if (contentHash == null || embeddingModel == null) return null;
        try {
            return mapper.selectByHashModel(contentHash, embeddingModel);
        } catch (Exception e) {
            log.warn("嵌入缓存读取失败(按未命中处理) model={}: {}", embeddingModel, e.getMessage());
            return null;
        }
    }

    /** best-effort 写缓存；独立事务 + 冲突忽略，失败仅记日志。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void put(String contentHash, String embeddingModel, String embedding) {
        if (contentHash == null || embeddingModel == null || embedding == null) return;
        mapper.insertIgnore(contentHash, embeddingModel, embedding);
    }
}
