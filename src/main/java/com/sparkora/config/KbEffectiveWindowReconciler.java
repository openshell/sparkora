package com.sparkora.config;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sparkora.ai.vector.VectorDomain;
import com.sparkora.ai.vector.VectorStoreService;
import com.sparkora.domain.entity.KbChunkEntity;
import com.sparkora.domain.entity.KbDocEntity;
import com.sparkora.kb.service.KbDocService;
import com.sparkora.mapper.KbChunkMapper;
import com.sparkora.mapper.KbDocMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * KB 生效期对账（10-03 E3 D-f）。
 *
 * <p>背景：KB store metadata {@code active} 承载「启用 + 生效期」的检索可用性
 * （{@code active = enabled && 今天∈[effectiveFrom,effectiveTo]}）。生效期会随日历自然翻转
 * （未来生效 / 到期），启动时算一次不足以覆盖长期运行，故**每日 + 启动各重算一次**，
 * 经 {@link VectorStoreService#setActive} 覆盖 store metadata.active。
 *
 * <p>顺序：{@code @Order(70)} 置于向量回填（50）与模型对账（60）之后；即便回填守护线程尚未完成，
 * 本任务按 chunkId 直更，未落库的行 no-op，次日重算仍会纠正（对账是幂等的）。
 *
 * <p>容错：异常全吞仅 warn，**绝不阻断启动/定时线程**（对账是观测性 + 自愈任务）。
 */
@Slf4j
@Component
@Order(70)
public class KbEffectiveWindowReconciler implements ApplicationRunner {

    private final KbDocMapper docMapper;
    private final KbChunkMapper chunkMapper;
    private final VectorStoreService store;

    public KbEffectiveWindowReconciler(KbDocMapper docMapper, KbChunkMapper chunkMapper,
                                       VectorStoreService store) {
        this.docMapper = docMapper;
        this.chunkMapper = chunkMapper;
        this.store = store;
    }

    @Override
    public void run(ApplicationArguments args) {
        reconcile();
    }

    /** 每日 00:05 重算（错开车型/新闻同步 03:00/03:30）。 */
    @Scheduled(cron = "${sparkora.kb.effective-cron:0 5 0 * * ?}")
    public void scheduledReconcile() {
        reconcile();
    }

    /** 遍历启用文档，按当前日期重算每块 active 并同步 store。异常仅 warn。 */
    void reconcile() {
        try {
            List<KbDocEntity> docs = docMapper.selectList(
                    new QueryWrapper<KbDocEntity>().eq("enabled", true));
            if (docs == null || docs.isEmpty()) return;
            LocalDate today = LocalDate.now();
            int flipped = 0;
            for (KbDocEntity d : docs) {
                try {
                    boolean active = KbDocService.isActive(d.getEnabled(),
                            d.getEffectiveFrom(), d.getEffectiveTo(), today);
                    List<KbChunkEntity> chunks = chunkMapper.selectList(
                            new QueryWrapper<KbChunkEntity>().eq("doc_id", d.getId()));
                    List<Long> ids = new ArrayList<>();
                    for (KbChunkEntity c : chunks) if (c.getId() != null) ids.add(c.getId());
                    if (!ids.isEmpty()) flipped += store.setActive(VectorDomain.KB.name(), ids, active);
                } catch (Exception e) {
                    log.warn("KB 生效期对账单文档失败(跳过) docId={}: {}", d.getId(), e.getMessage());
                }
            }
            log.info("KB 生效期对账完成:文档 {} 篇,更新 store 行 {}", docs.size(), flipped);
        } catch (Exception e) {
            log.warn("KB 生效期对账异常(不阻断): {}", e.getMessage());
        }
    }
}
