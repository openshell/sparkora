package com.sparkora.config;

import com.sparkora.ai.vector.VectorDomain;
import com.sparkora.ai.vector.VectorStoreService;
import com.sparkora.mapper.VectorStoreBackfillMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 存量 4 域向量回填到单表 {@code vector_store}（10-03 E1 阶段 A）。
 *
 * <p>幂等：按「确定性 uuid 差集」只补缺失行（{@link VectorStoreService#existingIds()}），重跑
 * {@code total=0} 自然跳过。**复用旧表已算好的向量**（读 {@code embedding::text} 直写），
 * 不重新嵌入——阶段 A 对拍要求嵌入不重算、结果不抖动。
 *
 * <p>顺序：{@code @Order(50)}，晚于图片标签/向量补齐（10/20），早于模型对账（60），
 * 保证回填时旧表向量已就绪。
 *
 * <p>异步 + 异常全吞：回填是纯 SQL（约 1900 行）但为对齐既有的启动补齐范式，放独立守护线程；
 * 失败仅 warn，绝不阻断启动（可重启再补）。
 */
@Slf4j
@Component
@Order(50)
public class VectorStoreBackfillRunner implements ApplicationRunner {

    private final VectorStoreBackfillMapper readMapper;
    private final VectorStoreService store;

    public VectorStoreBackfillRunner(VectorStoreBackfillMapper readMapper, VectorStoreService store) {
        this.readMapper = readMapper;
        this.store = store;
    }

    @Override
    public void run(ApplicationArguments args) {
        Thread t = new Thread(this::backfill, "vector-store-backfill");
        t.setDaemon(true);
        t.start();
    }

    private void backfill() {
        try {
            long start = System.currentTimeMillis();
            Set<String> existing = store.existingIds();
            int car = backfill(VectorDomain.CAR.name(), readMapper.readCar(), false, existing);
            int kb = backfill(VectorDomain.KB.name(), readMapper.readKb(), false, existing);
            int news = backfill(VectorDomain.NEWS.name(), readMapper.readNews(), false, existing);
            int img = backfill(VectorDomain.IMAGE.name(), readMapper.readImage(), true, existing);
            log.info("向量 store 回填完成:car={} kb={} news={} image={}(已有 {} 行,耗时{}ms)",
                    car, kb, news, img, existing.size(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("向量 store 回填任务异常(不阻断启动,可重启再补): {}", e.getMessage());
        }
    }

    /**
     * 回填单域差集。
     *
     * @param image true 时 chunkType 固定 IMAGE，且 content 来自旧表 source_text；否则 content=chunk_text
     */
    private int backfill(String domain, List<Map<String, Object>> rows, boolean image, Set<String> existing) {
        if (rows == null || rows.isEmpty()) return 0;
        String model = null;
        int n = 0;
        for (Map<String, Object> r : rows) {
            Long refId = asLong(r.get("refId"));
            String vector = asString(r.get("vector"));
            if (refId == null || vector == null || vector.isBlank()) continue;
            if (existing.contains(VectorStoreService.docId(domain, refId).toString())) continue;
            String embeddingModel = asString(r.get("embeddingModel"));
            if (model == null) model = embeddingModel;
            Long modelId = asLong(r.get("modelId"));
            String chunkType = image ? "IMAGE" : (asString(r.get("chunkType")) == null ? "" : asString(r.get("chunkType")));
            String name = asString(r.get("name"));
            String content = asString(r.get("content"));
            try {
                store.upsert(domain, refId, modelId, chunkType, name, true, embeddingModel, content, vector);
                n++;
            } catch (Exception e) {
                log.warn("向量 store 回填单行失败(跳过) domain={} refId={}: {}", domain, refId, e.getMessage());
            }
        }
        return n;
    }

    private static Long asLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number num) return num.longValue();
        try {
            return Long.valueOf(String.valueOf(o));
        } catch (Exception e) {
            return null;
        }
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
