package com.sparkora.config;

import com.sparkora.ai.vector.VectorStoreService;
import com.sparkora.car.client.EmbeddingClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 向量模型对账启动任务（09-27 知识域写入侧统一 R7；10-03 E6 改查单表 store）。
 *
 * <p>背景：向量行带 {@code embeddingModel} metadata 后，检索按当前配置模型过滤。若库中存在**非当前模型**
 * 的行（换过 embedding 模型但未重嵌），这些行不再是「命中错误」而是「静默不参与检索」——本 Runner
 * 在启动时把它们显式暴露为 WARN，提示需重嵌，避免「换模型后数据凭空消失」无人知晓。
 *
 * <p>10-03 E6：旧 4 张 {@code *_embedding} 表退役，对账统一走单表 {@code vector_store}
 * （{@code GROUP BY metadata.domain, metadata.embeddingModel}），按 {@code domain} 逐域告警。
 *
 * <p>顺序：Flyway 先于所有 ApplicationRunner 执行，故本 Runner 能看到 V5/V9 迁移后的表结构；
 * {@code @Order(60)} 置于图片标签/向量补齐 runner（10/20）之后，让补齐先完成再对账。
 *
 * <p>容错：查询异常仅 warn，**绝不阻断应用启动**（对账是观测性任务）。
 */
@Slf4j
@Component
@Order(60)
public class EmbeddingModelReconcileRunner implements ApplicationRunner {

    private final EmbeddingClient embeddingClient;
    private final VectorStoreService store;

    public EmbeddingModelReconcileRunner(EmbeddingClient embeddingClient, VectorStoreService store) {
        this.embeddingClient = embeddingClient;
        this.store = store;
    }

    @Override
    public void run(ApplicationArguments args) {
        String current = embeddingClient.modelName();
        warnIfStale(safe(store::embeddingModelStats), current);
    }

    /** 逐（domain, model）聚合行判断：model != 当前配置模型时输出 WARN（域 + 模型名 + 条数）。 */
    private void warnIfStale(List<Map<String, Object>> rows, String current) {
        if (rows == null) return;
        for (Map<String, Object> row : rows) {
            Object model = row.get("model");
            String name = model == null ? null : String.valueOf(model);
            if (name == null || name.isBlank() || name.equals(current)) continue;
            String domain = row.get("domain") == null ? null : String.valueOf(row.get("domain"));
            long cnt = row.get("cnt") == null ? 0 : ((Number) row.get("cnt")).longValue();
            log.warn("向量 store 存在非当前模型的行: domain={} model={} count={}（当前模型 {}，需重嵌该域向量）",
                    domain, name, cnt, current);
        }
    }

    /** 查询异常仅 warn 返回 null，绝不阻断启动。 */
    private List<Map<String, Object>> safe(java.util.function.Supplier<List<Map<String, Object>>> q) {
        try {
            return q.get();
        } catch (Exception e) {
            log.warn("向量模型对账查询失败(不阻断启动): {}", e.getMessage());
            return null;
        }
    }
}
