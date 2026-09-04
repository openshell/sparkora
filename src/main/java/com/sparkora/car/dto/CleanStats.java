package com.sparkora.car.dto;

import lombok.extern.slf4j.Slf4j;

/**
 * 单车型参数清洗方式统计(可观测)。
 * RULE=规则引擎命中,AI=LLM 兜底,FALLBACK=双失败原样 STRING(需人工关注),
 * TOTAL=入库清洗行总数(= RULE+AI+FALLBACK)。
 */
@Slf4j
public class CleanStats {

    private int rule;
    private int ai;
    private int fallback;

    public void count(String cleanMethod) {
        if (cleanMethod == null) return;
        switch (cleanMethod) {
            case "RULE" -> rule++;
            case "AI" -> ai++;
            case "FALLBACK" -> fallback++;
            default -> { /* 未知方式不统计,避免掩盖问题 */ }
        }
    }

    public int total() {
        return rule + ai + fallback;
    }

    /** 汇总日志(同步与重清洗时输出,便于观察规则退化)。 */
    public void logSummary(org.slf4j.Logger logger, Long modelId) {
        int t = total();
        logger.info("参数清洗统计 model={} total={} RULE={} AI={} FALLBACK={} (aiPct={}%, fallbackPct={}%)",
                modelId, t, rule, ai, fallback,
                t == 0 ? 0 : ai * 100 / t,
                t == 0 ? 0 : fallback * 100 / t);
    }

    public int getRule() { return rule; }
    public int getAi() { return ai; }
    public int getFallback() { return fallback; }
}