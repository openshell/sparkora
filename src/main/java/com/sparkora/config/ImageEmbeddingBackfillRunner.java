package com.sparkora.config;

import com.sparkora.service.ImageEmbeddingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 存量图片向量补齐（09-15 img-semantic-search，子B）。
 *
 * 背景：向量能力晚于图库入库能力——存量图片（含 09-15 img-classify 刚补标的 157 张新闻图）
 * 没有向量，语义检索看不到它们；本启动任务一次性补齐。
 *
 * 幂等：{@code rebuildMissing()} 只处理 `LEFT JOIN` 差集为空向量的图（先清后插），重跑零副作用
 * （第二次启动差集为空 → total=0 直接跳过）。
 * 容错：捕获全部异常仅 warn，**绝不阻断应用启动**——embedding 服务不可用时应用照常就绪，
 * 之后可由 `POST /api/images/embeddings/rebuild` 补齐（与 ImageTagBackfillRunner 同容错哲学）。
 *
 * 顺序（关键）：必须**晚于** {@link ImageTagBackfillRunner}（{@code @Order(20) > 10}）。嵌入文本
 * 依赖标签信号（byd-news 的 `主题/*`/`年份/*`、车型图的 `车型-*`），若先嵌入后补标，这批图会拿到
 * 「无标签」的低质量向量——且因已存在向量，后续 {@code rebuildMissing} 不会再修（静默、需人工
 * 调全量重建接口）。故显式排序，保证「补标 → 建向量」。
 *
 * 异步：实测 166 图串行 embedding 约 173s，远超 implement.md 的 30s 阈值，故放独立守护线程执行，
 * 不占启动主线程（应用就绪不被拖慢；日志输出进度与结果）。图库规模小时通常数秒完成。
 */
@Slf4j
@Component
@Order(20)
public class ImageEmbeddingBackfillRunner implements ApplicationRunner {

    private final ImageEmbeddingService embeddingService;

    public ImageEmbeddingBackfillRunner(ImageEmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 独立守护线程：embedding 为网络串行调用（存量 ~170 图实测约 3 分钟），不阻塞启动主线程
        Thread t = new Thread(this::backfill, "image-embedding-backfill");
        t.setDaemon(true);
        t.start();
    }

    /** 补齐主体（线程内执行）：幂等 + 全异常吞掉，绝不向上抛（守护线程抛异常无意义，且要保证不阻断启动）。 */
    private void backfill() {
        try {
            long start = System.currentTimeMillis();
            ImageEmbeddingService.EmbedStats st = embeddingService.rebuildMissing();
            long cost = System.currentTimeMillis() - start;
            if (st.total() == 0) {
                log.info("图片向量补齐完成:无缺失,跳过(total=0,耗时{}ms)", cost);
                return;
            }
            log.info("图片向量补齐完成:total={} success={} failed={}(耗时{}ms)",
                    st.total(), st.success(), st.failed(), cost);
        } catch (Exception e) {
            // 补齐任务失败不阻断应用启动(向量可事后用 rebuild 接口补齐)
            log.warn("图片向量补齐任务异常(不阻断启动,可调 POST /api/images/embeddings/rebuild 补齐): {}", e.getMessage());
        }
    }
}
