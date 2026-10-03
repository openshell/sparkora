package com.sparkora.car.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.ai.EmbeddingBatchRunner;
import com.sparkora.car.client.EmbeddingClient;
import com.sparkora.domain.entity.CarDocEntity;
import com.sparkora.domain.entity.CarModelEntity;
import com.sparkora.domain.entity.CarParamCleanEntity;
import com.sparkora.domain.entity.CarParamGroupEntity;
import com.sparkora.mapper.CarDocEmbeddingMapper;
import com.sparkora.mapper.CarDocMapper;
import com.sparkora.mapper.CarModelMapper;
import com.sparkora.mapper.CarParamCleanMapper;
import com.sparkora.mapper.CarParamGroupMapper;
import com.sparkora.mapper.CarVersionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档块切分 + 向量化服务。
 *
 * 切分粒度:仅 PARAM_GROUP(每参数分组一个文档块,供 RAG 检索)。
 * 另含 MODEL_INFO(车型基础信息)与 RIGHTS(购车权益)块,便于概览检索。
 *
 * 流程:先清旧文档块+向量,再按分组生成 chunk_text,逐个调 embedding 入库。
 * 网络 embedding 无事务;本地入库短事务(09-27 统一为 REQUIRES_NEW 自注入范式,见 rebuildForModel)。
 *
 * S6 重构:参数分组块基于清洗后数据(car_param_clean)生成,取值干净、类型化。
 */
@Slf4j
@Service
public class CarDocService {

    private final CarModelMapper modelMapper;
    private final CarParamGroupMapper groupMapper;
    private final CarParamCleanMapper cleanMapper;
    private final CarVersionMapper versionMapper;
    private final CarDocMapper docMapper;
    private final CarDocEmbeddingMapper embMapper;
    private final EmbeddingClient embeddingClient;
    private final EmbeddingBatchRunner batchRunner;
    private final ObjectMapper json;
    /** 自注入代理（@Lazy）：让 {@link #persistCarDoc} 的 REQUIRES_NEW 事务真的生效（this 调用不走代理）。 */
    @Autowired
    @Lazy
    private CarDocService self;
    /** 单表 store（10-03 E1）；字段注入可选，单测直接 new 时为 null（同步守卫降级）。 */
    @Autowired(required = false)
    private com.sparkora.ai.vector.VectorStoreService vectorStoreService;

    public CarDocService(CarModelMapper modelMapper, CarParamGroupMapper groupMapper,
                         CarParamCleanMapper cleanMapper, CarVersionMapper versionMapper,
                         CarDocMapper docMapper, CarDocEmbeddingMapper embMapper,
                         EmbeddingClient embeddingClient, EmbeddingBatchRunner batchRunner,
                         ObjectMapper json) {
        this.modelMapper = modelMapper;
        this.groupMapper = groupMapper;
        this.cleanMapper = cleanMapper;
        this.versionMapper = versionMapper;
        this.docMapper = docMapper;
        this.embMapper = embMapper;
        this.embeddingClient = embeddingClient;
        this.batchRunner = batchRunner;
        this.json = json;
    }

    /** 重建某车型的全部文档块 + 向量(先清后建)。 */
    public void rebuildForModel(Long modelId) {
        deleteByModel(modelId);
        CarModelEntity m = modelMapper.selectById(modelId);
        if (m == null) return;

        List<CarDocEntity> docs = new ArrayList<>();
        // 1) 车型基础信息块
        docs.add(buildModelInfoDoc(m));
        // 2) 购车权益块(按条切)
        docs.addAll(buildRightsDocs(m));
        // 3) 参数分组块(核心,仅 PARAM_GROUP 粒度)
        docs.addAll(buildParamGroupDocs(m));
        // 10-03 E1:填充车型名(写 vector_store metadata.name 用)
        for (CarDocEntity d : docs) d.setModelName(m.getName());

        // S6b:embedding 调用并发化(固定小线程池,不随车型数膨胀)+ 单块失败重试 1 次;
        // 结束输出成功/失败计数,失败块记 sortOrder——消除「静默丢块」与千次串行 HTTP。
        // 09-27:委托 EmbeddingBatchRunner;embed 在事务外,持久化走 REQUIRES_NEW 独立事务。
        batchRunner.run(docs, CarDocEntity::getChunkText,
                (doc, vec) -> (self == null ? this : self).persistCarDoc(doc, vec),
                "model=" + modelId, 4, 1);
    }

    /** 删除某车型的全部文档块 + 向量。 */
    @Transactional
    public void deleteByModel(Long modelId) {
        List<CarDocEntity> docs = docMapper.selectList(new QueryWrapper<CarDocEntity>().eq("model_id", modelId));
        List<Long> docIds = new ArrayList<>();
        for (CarDocEntity d : docs) {
            embMapper.deleteByDocId(d.getId());
            if (d.getId() != null) docIds.add(d.getId());
        }
        if (vectorStoreService != null && !docIds.isEmpty()) {
            vectorStoreService.deleteByRef(com.sparkora.ai.vector.VectorDomain.CAR.name(), docIds);
        }
        docMapper.delete(new QueryWrapper<CarDocEntity>().eq("model_id", modelId));
    }

    /**
     * 持久化文档块 + 向量(先插 doc 拿 id,再插 embedding)——独立事务边界(09-27 统一):
     * embedding 网络调用在事务外,此处 REQUIRES_NEW 保证向量插入失败时 doc 一并回滚(不留孤儿块),
     * 且不加入调用方环境事务。此前的 {@code @Transactional insertDocWithEmbedding} 由线程池 lambda
     * 内 this 调用,代理不生效、注解被忽略。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistCarDoc(CarDocEntity doc, String vec) {
        doc.setCreatedAt(LocalDateTime.now());
        doc.setUpdatedAt(LocalDateTime.now());
        docMapper.insert(doc);
        embMapper.insert(doc.getId(), doc.getModelId(), vec, embeddingClient.modelName());
        if (vectorStoreService != null) {
            vectorStoreService.upsert(com.sparkora.ai.vector.VectorDomain.CAR.name(), doc.getId(),
                    doc.getModelId(), doc.getChunkType(), doc.getModelName(), true,
                    embeddingClient.modelName(), doc.getChunkText(), vec);
        }
    }

    /** 车型基础信息块。 */
    private CarDocEntity buildModelInfoDoc(CarModelEntity m) {
        StringBuilder sb = new StringBuilder();
        sb.append("车型：").append(nv(m.getName())).append("\n");
        sb.append("销售网络：").append(nv(m.getSalesNetwork())).append("\n");
        sb.append("价格区间：").append(nv(m.getPriceRange())).append("\n");
        if (m.getFeatures() != null) {
            try {
                JsonNode arr = json.readTree(m.getFeatures());
                if (arr.isArray()) {
                    sb.append("核心卖点：\n");
                    for (JsonNode f : arr) sb.append("- ").append(f.asText()).append("\n");
                }
            } catch (Exception ignored) {}
        }
        CarDocEntity d = new CarDocEntity();
        d.setModelId(m.getId());
        d.setChunkType("MODEL_INFO");
        d.setChunkText(sb.toString());
        d.setSortOrder(0);
        return d;
    }

    /** 购车权益块(按条切)。 */
    private List<CarDocEntity> buildRightsDocs(CarModelEntity m) {
        List<CarDocEntity> out = new ArrayList<>();
        if (m.getCarRights() == null) return out;
        try {
            JsonNode rights = json.readTree(m.getCarRights());
            JsonNode content = rights.path("content");
            if (content.isArray()) {
                int i = 0;
                for (JsonNode c : content) {
                    String text = c.asText();
                    if (text.isBlank()) continue;
                    CarDocEntity d = new CarDocEntity();
                    d.setModelId(m.getId());
                    d.setChunkType("RIGHTS");
                    d.setChunkText("车型：" + nv(m.getName()) + " 购车权益：" + text);
                    d.setSortOrder(100 + i++);
                    out.add(d);
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** 参数分组块(核心,仅 PARAM_GROUP 粒度;基于清洗后数据)。
     *  S6b:块文本改用清洗值(「参数名:值+单位」,清洗缺失回退原始值),首行加车型全名——
     *  消除跨动力版本(EV/DM-i)同名车系检索混淆(S6.2 P1 遗留),并让清洗价值传导到检索层。 */
    private List<CarDocEntity> buildParamGroupDocs(CarModelEntity m) {
        Long modelId = m.getId();
        List<CarDocEntity> out = new ArrayList<>();
        List<CarParamGroupEntity> groups = groupMapper.selectList(
                new QueryWrapper<CarParamGroupEntity>().eq("model_id", modelId).orderByAsc("sort_order"));
        // 取第一个版本的清洗数据(全局展示用)
        Long firstVersionId = null;
        List<com.sparkora.domain.entity.CarVersionEntity> versions = versionMapper.selectList(
                new QueryWrapper<com.sparkora.domain.entity.CarVersionEntity>()
                        .eq("model_id", modelId).orderByAsc("sort_order"));
        if (!versions.isEmpty()) firstVersionId = versions.get(0).getId();
        for (CarParamGroupEntity g : groups) {
            // 取该分组下清洗后参数(第一个版本)
            List<CarParamCleanEntity> cleans = cleanMapper.selectList(
                    new QueryWrapper<CarParamCleanEntity>()
                            .eq("model_id", modelId)
                            .eq("version_id", firstVersionId)
                            .inSql("param_id", "SELECT id FROM sparkora_car_param WHERE group_id = " + g.getId())
                            .orderByAsc("id"));
            if (cleans.isEmpty()) continue;
            // S6.2:剔除"车型"表头行后仅剩不足 2 条有效参数的分组视为零信息表头块(如「XX参数表及配置表」),
            // 跳过切块——这类块检索得分高(与主题名相似)但无任何参数值,会挤占 topK 配额
            long meaningful = cleans.stream()
                    .filter(c -> !"车型".equals(c.getParamKey()))
                    .count();
            if (meaningful < 2) continue;
            StringBuilder sb = new StringBuilder();
            sb.append("车型：").append(nv(m.getName())).append("\n");
            sb.append("参数分组：").append(g.getGroupName()).append("\n");
            for (CarParamCleanEntity c : cleans) {
                String display = cleanDisplay(c);
                if (display == null) continue;   // 清洗与原始值均缺失,跳过该行
                sb.append(c.getParamKey()).append("：").append(display).append("\n");
            }
            CarDocEntity d = new CarDocEntity();
            d.setModelId(modelId);
            d.setGroupId(g.getId());
            d.setChunkType("PARAM_GROUP");
            d.setChunkText(sb.toString());
            d.setSortOrder(g.getSortOrder() == null ? 0 : g.getSortOrder());
            out.add(d);
        }
        return out;
    }

    private static String nv(String s) { return s == null || s.isBlank() ? "—" : s; }

    /**
     * 清洗值展示文本(块内「参数名：值」的值部分)。
     * 优先清洗主值(clean.param_value);缺失回退原始值(rawValue)。
     * NUMBER/LIST 类型且值本身不含单位时拼接单位(如 2820→2820mm),让块文本自带量纲、利于向量检索对齐。
     * ENUM(有/无/可选装)与 STRING 保持原样不拼单位。
     */
    static String cleanDisplay(CarParamCleanEntity c) {
        String v = c.getParamValue();
        if (v == null || v.isBlank()) v = c.getRawValue();
        if (v == null || v.isBlank()) return null;
        String unit = c.getUnit();
        String type = c.getValueType();
        boolean numericLike = "NUMBER".equals(type) || "LIST".equals(type);
        if (numericLike && unit != null && !unit.isBlank() && !v.contains(unit)) {
            return v + unit;
        }
        return v;
    }
}
