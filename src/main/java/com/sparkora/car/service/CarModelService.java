package com.sparkora.car.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.car.client.BydCmsClient;
import com.sparkora.car.dto.GoodsAttrListDto;
import com.sparkora.car.dto.GoodsInfoDto;
import com.sparkora.car.dto.GoodsParamsDto;
import com.sparkora.car.dto.CarModelDetailDto;
import com.sparkora.car.dto.CleanStats;
import com.sparkora.domain.entity.CarModelEntity;
import com.sparkora.domain.entity.CarParamCleanEntity;
import com.sparkora.domain.entity.CarParamEntity;
import com.sparkora.domain.entity.CarParamGroupEntity;
import com.sparkora.domain.entity.CarVersionEntity;
import com.sparkora.domain.entity.ImageAssetEntity;
import com.sparkora.mapper.CarModelMapper;
import com.sparkora.mapper.CarParamCleanMapper;
import com.sparkora.mapper.CarParamGroupMapper;
import com.sparkora.mapper.CarParamMapper;
import com.sparkora.mapper.CarVersionMapper;
import com.sparkora.mapper.ImageAssetMapper;
import com.sparkora.security.SecurityUtil;
import com.sparkora.storage.ImageStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 车型知识库入库编排服务。
 *
 * 数据流(每车型):
 *   goodsListForSearch → upsert car_model(目录)
 *   getGoodsInfoById   → 更新 car_model 基础信息/卖点/图片/权益
 *   getGoodsAttrList   → 生成 car_version(版本+价格)
 *   goodsParams        → 生成 car_param_group + car_param(参数表)
 *   随后 CarDocService 切分向量化(仅 PARAM_GROUP 粒度)
 *
 * 事务边界:网络采集(慢)无事务;本地入库用短事务。参照 BriefService 分阶段模式。
 */
@Slf4j
@Service
public class CarModelService {

    private final BydCmsClient client;
    private final CarModelMapper modelMapper;
    private final CarVersionMapper versionMapper;
    private final CarParamGroupMapper groupMapper;
    private final CarParamMapper paramMapper;
    private final CarParamCleanMapper cleanMapper;
    private final CarDocService docService;
    private final CarCleanService cleanService;
    private final ImageAssetMapper imageMapper;
    private final ImageStorage imageStorage;
    private final ObjectMapper json;

    private final java.net.http.HttpClient imageClient = java.net.http.HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .build();

    public CarModelService(BydCmsClient client, CarModelMapper modelMapper,
                           CarVersionMapper versionMapper, CarParamGroupMapper groupMapper,
                           CarParamMapper paramMapper, CarParamCleanMapper cleanMapper,
                           CarDocService docService, CarCleanService cleanService,
                           ImageAssetMapper imageMapper, ImageStorage imageStorage, ObjectMapper json) {
        this.client = client;
        this.modelMapper = modelMapper;
        this.versionMapper = versionMapper;
        this.groupMapper = groupMapper;
        this.paramMapper = paramMapper;
        this.cleanMapper = cleanMapper;
        this.docService = docService;
        this.cleanService = cleanService;
        this.imageMapper = imageMapper;
        this.imageStorage = imageStorage;
        this.json = json;
    }

    /** 车型列表(按 id 倒序)。 */
    public List<CarModelEntity> list() {
        return modelMapper.selectList(new QueryWrapper<CarModelEntity>().orderByDesc("id"));
    }

    /** 官网车型目录(供同步页手动选择)。返回 goodsListForSearch 原始 data 数组。 */
    public List<Map<String, Object>> catalog() {
        JsonNode list = client.goodsList();
        JsonNode data = list.path("data");
        if (!data.isArray()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode item : data) {
            out.add(json.convertValue(item, Map.class));
        }
        return out;
    }

    /** 车型详情(含版本/分组/参数)。 */
    public CarModelEntity get(Long id) {
        return modelMapper.selectById(id);
    }

    /**
     * 单车型清洗质量统计(可观测,AC4/体检报告用)。
     * 按 clean_method 分组计数;另给 value_type 分布与可疑 STRING 兜底计数。
     * 注意:仅统计新口径数据(clean_method ∈ RULE/AI/FALLBACK);旧数据的 RULE 混入需重清洗后口径才可信。
     */
    public Map<String, Object> cleanStats(Long id) {
        CarModelEntity m = modelMapper.selectById(id);
        if (m == null) throw new IllegalArgumentException("车型不存在");
        List<CarParamCleanEntity> cleans = cleanMapper.selectList(
                new QueryWrapper<CarParamCleanEntity>().eq("model_id", id));
        Map<String, Long> byMethod = cleans.stream().collect(
                java.util.stream.Collectors.groupingBy(
                        c -> c.getCleanMethod() == null ? "UNKNOWN" : c.getCleanMethod(),
                        java.util.stream.Collectors.counting()));
        Map<String, Long> byValueType = cleans.stream().collect(
                java.util.stream.Collectors.groupingBy(
                        c -> c.getValueType() == null ? "UNKNOWN" : c.getValueType(),
                        java.util.stream.Collectors.counting()));
        long fallbackString = cleans.stream()
                .filter(c -> "FALLBACK".equals(c.getCleanMethod()))
                .count();
        long total = cleans.size();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("modelName", m.getName());
        out.put("total", total);
        out.put("byMethod", byMethod);
        out.put("byValueType", byValueType);
        out.put("fallbackCount", fallbackString);
        out.put("fallbackPct", total == 0 ? 0 : fallbackString * 100 / total);
        return out;
    }

    /** 车型详情聚合(含版本/分组/参数)。versionId 指定展示清洗参数的版本,为空取第一个版本。 */
    public CarModelDetailDto detail(Long id, Long versionId) {
        CarModelEntity m = modelMapper.selectById(id);
        if (m == null) throw new IllegalArgumentException("车型不存在");
        CarModelDetailDto dto = new CarModelDetailDto();
        dto.setModel(m);
        List<CarVersionEntity> versions = versionMapper.selectList(
                new QueryWrapper<CarVersionEntity>().eq("model_id", id).orderByAsc("sort_order"));
        dto.setVersions(versions);
        // 确定展示清洗参数的版本
        Long targetVersion = versionId;
        if (targetVersion == null && !versions.isEmpty()) targetVersion = versions.get(0).getId();
        List<CarParamGroupEntity> groups = groupMapper.selectList(
                new QueryWrapper<CarParamGroupEntity>().eq("model_id", id).orderByAsc("sort_order"));
        List<CarModelDetailDto.GroupWithParams> gwp = new ArrayList<>();
        for (CarParamGroupEntity g : groups) {
            CarModelDetailDto.GroupWithParams item = new CarModelDetailDto.GroupWithParams();
            item.setGroup(g);
            item.setParams(paramMapper.selectList(
                    new QueryWrapper<CarParamEntity>().eq("group_id", g.getId()).orderByAsc("sort_order")));
            // 清洗后参数(按目标版本)
            item.setCleans(cleanMapper.selectList(
                    new QueryWrapper<CarParamCleanEntity>()
                            .eq("model_id", id)
                            .eq("version_id", targetVersion)
                            .inSql("param_id", "SELECT id FROM sparkora_car_param WHERE group_id = " + g.getId())
                            .orderByAsc("id")));
            gwp.add(item);
        }
        dto.setGroups(gwp);
        return dto;
    }

    /** 删除车型(逻辑删除;级联删除版本/分组/参数/文档块)。 */
    @Transactional
    public void delete(Long id) {
        CarModelEntity m = modelMapper.selectById(id);
        if (m == null) throw new IllegalArgumentException("车型不存在");
        modelMapper.deleteById(id);
        versionMapper.delete(new QueryWrapper<CarVersionEntity>().eq("model_id", id));
        groupMapper.delete(new QueryWrapper<CarParamGroupEntity>().eq("model_id", id));
        paramMapper.delete(new QueryWrapper<CarParamEntity>().eq("model_id", id));
        docService.deleteByModel(id);
    }

    /**
     * 同步单个车型(按 goodsId)。幂等:已存在则更新,不存在则新建。
     * 网络采集无事务;入库短事务。由 CarSyncJobService 异步任务驱动。
     *
     * @return 同步结果:模型 + 清洗方式统计(可观测)。
     */
    public SyncOutcome syncOne(String goodsId) {
        // 1) 采集(无事务,慢)
        GoodsInfoDto info = client.goodsInfo(goodsId);
        GoodsParamsDto.ParamsData params = client.goodsParams(goodsId);
        List<GoodsAttrListDto.Version> attrs = client.goodsAttrList(goodsId);

        GoodsInfoDto.Item item = (info != null && info.getGoodsinfoList() != null
                && info.getGoodsinfoList().getItems() != null
                && !info.getGoodsinfoList().getItems().isEmpty())
                ? info.getGoodsinfoList().getItems().get(0) : null;

        // 2) 入库(短事务)
        CarModelEntity model = persistModel(goodsId, item);
        persistVersions(model.getId(), attrs);
        persistParams(model.getId(), params);
        // 2.5) 车型图片下载转存图床,写入图库(source='byd');单图失败不阻断整车型同步
        persistIntroImages(model, item);
        // 3) 清洗参数(规则引擎 + AI 兜底);统计随清洗落库,同步任务记录进 job 明细
        CleanStats stats = cleanService.cleanForModel(model.getId());
        // 4) 切分向量化(网络 embedding,无事务;基于清洗后数据)
        docService.rebuildForModel(model.getId());
        return new SyncOutcome(model, stats);
    }

    /** 同步结果:车型 + 清洗方式统计(供同步任务聚合记录)。 */
    public record SyncOutcome(CarModelEntity model, CleanStats cleanStats) {}

    /**
     * 车型介绍图(introduce URL 列表)下载转存图床,写入 sparkora_image_asset(source='byd',全局图库)。
     * car_model.intro_images 更新为图库记录 id 列表(JSON 数组)。单图失败仅告警,不阻断整车型同步。
     */
    protected void persistIntroImages(CarModelEntity model, GoodsInfoDto.Item item) {
        if (item == null || item.getIntroduce() == null || item.getIntroduce().isEmpty()) return;
        List<Long> ids = new ArrayList<>();
        for (String url : item.getIntroduce()) {
            if (url == null || url.isBlank()) continue;
            try {
                byte[] bytes = downloadImage(url);
                String ext = sniffImageExt(bytes);
                ImageAssetEntity e = new ImageAssetEntity();
                e.setProjectId(null);                       // 全局图库
                e.setFileName(model.getName() + "-" + (ids.size() + 1) + "." + ext);
                e.setSource("byd");
                e.setStorageKey(imageStorage.upload(bytes, ext));
                e.setCreatedBy("system");
                e.setCreatedAt(LocalDateTime.now());
                imageMapper.insert(e);
                ids.add(e.getId());
                log.info("比亚迪车型图已转存图床 model={} url={} id={}", model.getName(), shorten(url), e.getId());
            } catch (Exception ex) {
                log.warn("比亚迪车型图转存失败(跳过): model={} url={} err={}", model.getName(), shorten(url), ex.getMessage());
            }
        }
        if (!ids.isEmpty()) {
            model.setIntroImages(toJson(ids));
            modelMapper.updateById(model);
        }
    }

    /** 下载图片字节(官网 URL)。 */
    private byte[] downloadImage(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30)).GET().build();
            HttpResponse<byte[]> resp = imageClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) throw new IllegalStateException("HTTP " + resp.statusCode());
            byte[] bytes = resp.body();
            if (bytes == null || bytes.length == 0) throw new IllegalStateException("空内容");
            return bytes;
        } catch (Exception e) {
            throw new RuntimeException("下载图片失败: " + e.getMessage(), e);
        }
    }

    /** 魔数嗅探图片扩展名(png/jpg/webp),识别不出兜底 png。 */
    private static String sniffImageExt(byte[] bytes) {
        if (bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P') return "png";
        if (bytes.length >= 3 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8) return "jpg";
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') return "webp";
        return "png";
    }

    private static String shorten(String s) {
        if (s == null) return "null";
        return s.length() > 100 ? s.substring(0, 100) + "…" : s;
    }

    /** 入库车型主表(幂等 upsert)。 */
    @Transactional
    protected CarModelEntity persistModel(String goodsId, GoodsInfoDto.Item item) {
        CarModelEntity m = modelMapper.selectOne(new QueryWrapper<CarModelEntity>().eq("goods_id", goodsId));
        boolean isNew = (m == null);
        if (isNew) m = new CarModelEntity();
        m.setGoodsId(goodsId);
        if (item != null) {
            m.setName(item.getName());
            m.setSalesNetwork(item.getSalesNetworkName());
            m.setVehicleId(item.getVehicleId());
            m.setPriceRange(item.getPrice());
            m.setFeatures(item.getFeatures() == null ? null : toJson(item.getFeatures()));
            m.setIntroImages(item.getIntroduce() == null ? null : toJson(item.getIntroduce()));
            m.setDetailPage(item.getDetailPage() == null ? null : item.getDetailPage().get_path());
            if (item.getCarRights() != null) {
                m.setCarRights(toJson(item.getCarRights()));
            }
        }
        m.setSourceUrl("https://www.byd.com/cn/parameter-comparison?goodsId=" + goodsId);
        m.setSyncStatus("SUCCESS");
        m.setLastSyncAt(LocalDateTime.now());
        m.setLastSyncError(null);
        m.setUpdatedAt(LocalDateTime.now());
        if (isNew) {
            m.setCreatedBy(SecurityUtil.current() == null ? "system" : SecurityUtil.current().getUsername());
            m.setCreatedAt(LocalDateTime.now());
            modelMapper.insert(m);
        } else {
            modelMapper.updateById(m);
        }
        return m;
    }

    /** 入库版本(先清后插,幂等)。 */
    @Transactional
    protected void persistVersions(Long modelId, List<GoodsAttrListDto.Version> attrs) {
        versionMapper.delete(new QueryWrapper<CarVersionEntity>().eq("model_id", modelId));
        if (attrs == null) return;
        int i = 0;
        for (GoodsAttrListDto.Version v : attrs) {
            if (v.getName() == null || v.getName().isBlank()) continue;
            CarVersionEntity e = new CarVersionEntity();
            e.setModelId(modelId);
            e.setVersionName(v.getName());
            e.setPrice(v.getPrice() == null ? null : v.getPrice());
            e.setPriceRemark(v.getPriceRemark());
            e.setSortOrder(i++);
            e.setCreatedAt(LocalDateTime.now());
            e.setUpdatedAt(LocalDateTime.now());
            versionMapper.insert(e);
        }
    }

    /** 入库参数分组+明细(先清后插,幂等)。 */
    @Transactional
    protected void persistParams(Long modelId, GoodsParamsDto.ParamsData params) {
        groupMapper.delete(new QueryWrapper<CarParamGroupEntity>().eq("model_id", modelId));
        paramMapper.delete(new QueryWrapper<CarParamEntity>().eq("model_id", modelId));
        if (params == null || params.getConfigs() == null) return;
        int gi = 0;
        for (GoodsParamsDto.Config cfg : params.getConfigs()) {
            if (cfg.getName() == null || cfg.getName().isBlank()) continue;
            CarParamGroupEntity g = new CarParamGroupEntity();
            g.setModelId(modelId);
            g.setGroupName(cfg.getName());
            g.setSortOrder(gi++);
            g.setCreatedAt(LocalDateTime.now());
            g.setUpdatedAt(LocalDateTime.now());
            groupMapper.insert(g);

            if (cfg.getValue() == null) continue;
            int pi = 0;
            for (GoodsParamsDto.ParamRow row : cfg.getValue()) {
                if (row.getName() == null || row.getName().isBlank()) continue;
                CarParamEntity p = new CarParamEntity();
                p.setGroupId(g.getId());
                p.setModelId(modelId);
                p.setParamName(row.getName());
                // 当前选中版本 = 第一个版本(下标 0);全版本值保留下标对齐
                p.setParamValue(row.getValue() == null || row.getValue().isEmpty() ? null : row.getValue().get(0));
                p.setValuesJson(row.getValue() == null ? null : toJson(row.getValue()));
                p.setSortOrder(pi++);
                p.setCreatedAt(LocalDateTime.now());
                p.setUpdatedAt(LocalDateTime.now());
                paramMapper.insert(p);
            }
        }
    }

    private String toJson(Object o) {
        try { return json.writeValueAsString(o); }
        catch (Exception e) { return null; }
    }
}
