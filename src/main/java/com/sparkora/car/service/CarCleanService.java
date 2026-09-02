package com.sparkora.car.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.car.dto.ParamCleanResult;
import com.sparkora.domain.entity.CarParamCleanEntity;
import com.sparkora.domain.entity.CarParamEntity;
import com.sparkora.domain.entity.CarVersionEntity;
import com.sparkora.mapper.CarParamCleanMapper;
import com.sparkora.mapper.CarParamMapper;
import com.sparkora.mapper.CarVersionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 参数清洗管线服务。规则引擎优先 + AI 兜底,写入 sparkora_car_param_clean。
 *
 * 流程(每车型):
 *   1. 读取 car_param 原始参数(含全版本值 values_json)
 *   2. 对每个参数行,按版本下标逐个清洗(规则优先,规则覆盖不了走 AI)
 *   3. 写入 car_param_clean(先清后插,幂等)
 *
 * 清洗结果支撑文章生成干净取值/跨版本对比/数值计算。
 */
@Slf4j
@Service
public class CarCleanService {

    private final CarParamMapper paramMapper;
    private final CarVersionMapper versionMapper;
    private final CarParamCleanMapper cleanMapper;
    private final ParamCleaner ruleCleaner;
    private final AiParamCleaner aiCleaner;
    private final ObjectMapper json;

    public CarCleanService(CarParamMapper paramMapper, CarVersionMapper versionMapper,
                           CarParamCleanMapper cleanMapper, ParamCleaner ruleCleaner,
                           AiParamCleaner aiCleaner, ObjectMapper json) {
        this.paramMapper = paramMapper;
        this.versionMapper = versionMapper;
        this.cleanMapper = cleanMapper;
        this.ruleCleaner = ruleCleaner;
        this.aiCleaner = aiCleaner;
        this.json = json;
    }

    /** 清洗某车型的全部参数(先清后插,幂等)。 */
    @Transactional
    public void cleanForModel(Long modelId) {
        cleanMapper.delete(new QueryWrapper<CarParamCleanEntity>().eq("model_id", modelId));
        List<CarVersionEntity> versions = versionMapper.selectList(
                new QueryWrapper<CarVersionEntity>().eq("model_id", modelId).orderByAsc("sort_order"));
        List<CarParamEntity> params = paramMapper.selectList(
                new QueryWrapper<CarParamEntity>().eq("model_id", modelId).orderByAsc("sort_order"));

        for (CarParamEntity p : params) {
            // 解析全版本值(下标对齐版本)
            List<String> values = parseValues(p.getValuesJson());
            if (values.isEmpty() && p.getParamValue() != null) {
                values = List.of(p.getParamValue());
            }
            for (int i = 0; i < values.size(); i++) {
                Long versionId = i < versions.size() ? versions.get(i).getId() : null;
                String raw = values.get(i);
                ParamCleanResult r = cleanOne(p.getParamName(), raw);
                if (r == null) continue;
                insertClean(p, versionId, raw, r);
            }
        }
    }

    /** 清洗单个值:规则优先,规则覆盖不了走 AI。 */
    private ParamCleanResult cleanOne(String paramName, String raw) {
        ParamCleanResult r = ruleCleaner.clean(paramName, raw);
        if (r != null) return r;
        // 规则覆盖不了,AI 兜底
        ParamCleanResult ai = aiCleaner.clean(paramName, raw);
        if (ai != null) return ai;
        // AI 也失败,降级为 STRING 原样
        ParamCleanResult fallback = new ParamCleanResult();
        fallback.setParamKey(paramName);
        fallback.setValueType("STRING");
        fallback.setValue(raw);
        fallback.setCleanMethod("RULE");
        return fallback;
    }

    private void insertClean(CarParamEntity p, Long versionId, String raw, ParamCleanResult r) {
        CarParamCleanEntity e = new CarParamCleanEntity();
        e.setParamId(p.getId());
        e.setModelId(p.getModelId());
        e.setVersionId(versionId);
        e.setParamKey(r.getParamKey());
        e.setParamValue(r.getValue());
        e.setValueType(r.getValueType());
        e.setNumericValue(r.getNumericValue());
        e.setUnit(r.getUnit());
        e.setEnumValue(r.getEnumValue());
        e.setListValues(r.getListValues() == null ? null : toJson(r.getListValues()));
        e.setRawValue(raw);
        e.setCleanMethod(r.getCleanMethod());
        e.setConfidence(r.getConfidence());
        e.setCreatedAt(LocalDateTime.now());
        e.setUpdatedAt(LocalDateTime.now());
        cleanMapper.insert(e);
    }

    private List<String> parseValues(String valuesJson) {
        if (valuesJson == null) return List.of();
        try {
            return json.readValue(valuesJson,
                    json.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private String toJson(Object o) {
        try { return json.writeValueAsString(o); }
        catch (Exception e) { return null; }
    }
}
