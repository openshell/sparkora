package com.sparkora.config;

import com.sparkora.car.client.EmbeddingClient;
import com.sparkora.mapper.EmbeddingModelStatsMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 向量模型对账启动任务（09-27 知识域写入侧统一 R7）。
 *
 * <p>背景：4 张向量表新增 {@code embedding_model} 列后，检索按当前配置模型过滤。若库中存在**非当前模型**
 * 的旧向量行（换过 embedding 模型但未重嵌），这些行不再是「命中错误」而是「静默不参与检索」——本 Runner
 * 在启动时把它们显式暴露为 WARN，提示需重嵌，避免「换模型后数据凭空消失」无人知晓。
 *
 * <p>顺序：Flyway 先于所有 ApplicationRunner 执行，故本 Runner 能看到 V3 补列后的表结构；
 * {@code @Order(60)} 置于图片标签/向量补齐 runner（10/20）之后，让回填先完成再对账。
 *
 * <p>容错：查询异常仅 warn，**绝不阻断应用启动**（对账是观测性任务）。
 */
@Slf4j
@Component
@Order(60)
public class EmbeddingModelReconcileRunner implements ApplicationRunner {

    private final EmbeddingClient embeddingClient;
    private final EmbeddingModelStatsMapper statsMapper;

    public EmbeddingModelReconcileRunner(EmbeddingClient embeddingClient,
                                         EmbeddingModelStatsMapper statsMapper) {
        this.embeddingClient = embeddingClient;
        this.statsMapper = statsMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        String current = embeddingClient.modelName();
        warnIfStale("sparkora_car_doc_embedding", safe(statsMapper::carModelStats), current);
        warnIfStale("sparkora_kb_chunk_embedding", safe(statsMapper::kbModelStats), current);
        warnIfStale("sparkora_news_doc_embedding", safe(statsMapper::newsModelStats), current);
        warnIfStale("sparkora_image_embedding", safe(statsMapper::imageModelStats), current);
    }

    /** 该表存在 model != 当前配置模型的行时输出 WARN（列出模型名 + 条数）。 */
    private void warnIfStale(String table, List<Map<String, Object>> rows, String current) {
        if (rows == null) return;
        for (Map<String, Object> row : rows) {
            Object model = row.get("model");
            String name = model == null ? null : String.valueOf(model);
            if (name == null || name.isBlank() || name.equals(current)) continue;
            long cnt = row.get("cnt") == null ? 0 : ((Number) row.get("cnt")).longValue();
            log.warn("向量表存在非当前模型的行: table={} model={} count={}（当前模型 {}，需重嵌该域向量）",
                    table, name, cnt, current);
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
