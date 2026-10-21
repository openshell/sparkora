package com.sparkora.car.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sparkora.car.dto.CleanStats;
import com.sparkora.domain.entity.CarModelEntity;
import com.sparkora.domain.entity.CarParamCleanEntity;
import com.sparkora.domain.entity.CarParamEntity;
import com.sparkora.mapper.CarModelMapper;
import com.sparkora.mapper.CarParamCleanMapper;
import com.sparkora.mapper.CarParamMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 车型参数清洗存量修复（10-21-car-clean-repair）。
 *
 * <p>只基于库内 {@code car_param} 重跑清洗，不请求官网、不改版本/参数/图片/向量；
 * 用于修复「有原始参数但 car_param_clean 为空」导致详情页空白的历史数据。
 */
@Slf4j
@Service
public class CarCleanRepairService {

    private final CarModelMapper modelMapper;
    private final CarParamMapper paramMapper;
    private final CarParamCleanMapper cleanMapper;
    private final CarCleanService cleanService;

    public CarCleanRepairService(CarModelMapper modelMapper, CarParamMapper paramMapper,
                                 CarParamCleanMapper cleanMapper, CarCleanService cleanService) {
        this.modelMapper = modelMapper;
        this.paramMapper = paramMapper;
        this.cleanMapper = cleanMapper;
        this.cleanService = cleanService;
    }

    /** 单车型补清洗（显式调用可重跑；参数为空时 cleanForModel 自身会保留旧结果）。 */
    public CleanRepairResult cleanOne(Long modelId) {
        CarModelEntity model = modelMapper.selectById(modelId);
        if (model == null) throw new IllegalArgumentException("车型不存在");
        long params = countParams(modelId);
        long before = countCleans(modelId);
        CleanStats stats = cleanService.cleanForModel(modelId);
        long after = countCleans(modelId);
        log.info("车型补清洗完成 model={} name={} params={} cleans {}→{} RULE={} AI={} FALLBACK={}",
                modelId, model.getName(), params, before, after,
                stats.getRule(), stats.getAi(), stats.getFallback());
        return new CleanRepairResult(modelId, model.getName(), params, before, after,
                stats.getRule(), stats.getAi(), stats.getFallback());
    }

    /** 对「有参数、无清洗」的车型批量补清洗；失败不中断其余车型。 */
    public CleanRepairSummary cleanMissing() {
        List<CarModelEntity> models = modelMapper.selectList(new QueryWrapper<CarModelEntity>().orderByAsc("id"));
        int cleaned = 0, skipped = 0, failed = 0;
        List<Map<String, Object>> failures = new ArrayList<>();
        for (CarModelEntity m : models) {
            long params = countParams(m.getId());
            long cleans = countCleans(m.getId());
            if (params == 0 || cleans > 0) {
                skipped++;
                continue;
            }
            try {
                CleanRepairResult r = cleanOne(m.getId());
                if (r.cleansAfter() > 0) cleaned++;
                else skipped++;
            } catch (Exception e) {
                failed++;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("modelId", m.getId());
                item.put("goodsId", m.getGoodsId());
                item.put("name", m.getName());
                item.put("error", com.sparkora.car.client.CarSourceException.classify(e));
                failures.add(item);
                log.warn("车型补清洗失败 model={} name={}: {}", m.getId(), m.getName(), e.getMessage());
            }
        }
        log.info("车型补清洗汇总 total={} cleaned={} skipped={} failed={}", models.size(), cleaned, skipped, failed);
        return new CleanRepairSummary(models.size(), cleaned, skipped, failed, failures);
    }

    private long countParams(Long modelId) {
        Long n = paramMapper.selectCount(new QueryWrapper<CarParamEntity>().eq("model_id", modelId));
        return n == null ? 0 : n;
    }

    private long countCleans(Long modelId) {
        Long n = cleanMapper.selectCount(new QueryWrapper<CarParamCleanEntity>().eq("model_id", modelId));
        return n == null ? 0 : n;
    }

    public record CleanRepairResult(Long modelId, String name, long params,
                                    long cleansBefore, long cleansAfter,
                                    int rule, int ai, int fallback) {}

    public record CleanRepairSummary(int total, int cleaned, int skipped, int failed,
                                     List<Map<String, Object>> failures) {}
}
