package com.sparkora.car.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.car.client.BydCmsClient;
import com.sparkora.car.dto.GoodsAttrListDto;
import com.sparkora.car.dto.GoodsInfoDto;
import com.sparkora.car.dto.GoodsParamsDto;
import com.sparkora.car.dto.CarModelDetailDto;
import com.sparkora.car.dto.CleanStats;
import com.sparkora.domain.entity.CarChunkEntity;
import com.sparkora.domain.entity.CarModelEntity;
import com.sparkora.domain.entity.CarParamCleanEntity;
import com.sparkora.domain.entity.CarParamEntity;
import com.sparkora.domain.entity.CarParamGroupEntity;
import com.sparkora.domain.entity.CarVersionEntity;
import com.sparkora.domain.entity.ImageAssetEntity;
import com.sparkora.mapper.CarChunkMapper;
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
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 *   随后 CarChunkService 切分向量化(仅 PARAM_GROUP 粒度)
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
    private final CarChunkService docService;
    private final CarCleanService cleanService;
    private final CarChunkMapper chunkMapper;
    private final ImageAssetMapper imageMapper;
    private final com.sparkora.service.ImageService imageService;
    private final ImageStorage imageStorage;
    private final ObjectMapper json;
    /** 09-27:向量对账统计须按当前配置模型过滤(countByModel 只计同模型行)。 */
    private final com.sparkora.car.client.EmbeddingClient embeddingClient;
    /** 10-03 E1:单表 store 生命周期同步(删除车型按 modelId 兜底物理清)。 */
    private final com.sparkora.ai.vector.VectorStoreService vectorStoreService;

    private final java.net.http.HttpClient imageClient = java.net.http.HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .build();

    public CarModelService(BydCmsClient client, CarModelMapper modelMapper,
                           CarVersionMapper versionMapper, CarParamGroupMapper groupMapper,
                           CarParamMapper paramMapper, CarParamCleanMapper cleanMapper,
                           CarChunkService docService, CarCleanService cleanService,
                           CarChunkMapper chunkMapper,
                           ImageAssetMapper imageMapper, ImageStorage imageStorage, ObjectMapper json,
                           com.sparkora.service.ImageService imageService,
                           com.sparkora.car.client.EmbeddingClient embeddingClient,
                           com.sparkora.ai.vector.VectorStoreService vectorStoreService) {
        this.client = client;
        this.modelMapper = modelMapper;
        this.versionMapper = versionMapper;
        this.groupMapper = groupMapper;
        this.paramMapper = paramMapper;
        this.cleanMapper = cleanMapper;
        this.docService = docService;
        this.cleanService = cleanService;
        this.chunkMapper = chunkMapper;
        this.imageMapper = imageMapper;
        this.imageStorage = imageStorage;
        this.json = json;
        this.imageService = imageService;
        this.embeddingClient = embeddingClient;
        this.vectorStoreService = vectorStoreService;
    }

    /** 车型列表(按 id 倒序)。 */
    public List<CarModelEntity> list() {
        List<CarModelEntity> list = modelMapper.selectList(new QueryWrapper<CarModelEntity>().orderByDesc("id"));
        for (CarModelEntity m : list) fillImageUrls(m);
        return list;
    }

    /**
     * 填充图片公网 URL 派生字段(非持久化):详情图集 introImageUrls + 封面 coverImageUrl。
     * introImages 语义为图库 asset id 列表 JSON;兼容存量旧数据(URL 数组直接保留)。
     * 单个元素解析失败/图片不存在时跳过,不阻断列表。
     * 封面解析失败/无封面时 coverImageUrl 为 null(不回退详情图首图——「有无封面」统计须真实,
     * 回退由前端展示层完成)。
     */
    private void fillImageUrls(CarModelEntity m) {
        List<String> urls = new ArrayList<>();
        String raw = m.getIntroImages();
        if (raw != null && !raw.isBlank()) {
            try {
                JsonNode arr = json.readTree(raw);
                if (arr.isArray()) {
                    for (JsonNode node : arr) {
                        String v = node.asText(null);
                        if (v == null || v.isBlank()) continue;
                        String t = v.trim();
                        if (t.startsWith("http")) {
                            urls.add(t);                       // 存量旧数据:URL 数组
                            continue;
                        }
                        try {
                            urls.add(imageService.publicUrl(Long.parseLong(t)));
                        } catch (Exception ignore) {
                            // 图片不存在/未转存/非数字,跳过该元素
                        }
                    }
                }
            } catch (Exception ignore) {
                // introImages 非法 JSON,留空
            }
        }
        m.setIntroImageUrls(urls);
        String cover = imageService.publicUrlQuietly(m.getCoverImageId());
        m.setCoverImageUrl(cover == null || cover.isBlank() ? null : cover);
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
        fillImageUrls(m);
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

    /**
     * 删除车型(逻辑删除;级联删除版本/分组/参数/文档块)。
     * 向量兜底:deleteByModel 仅遍历未逻辑删除的 doc,历史已逻辑删除但向量残留的块按 model_id 一条 SQL 全清。
     * 图库:intro_images 指向全局共享图库资产(project_id=null、source=byd、内容哈希去重),可能被其他车型/文章复用,
     * 不随车型删除而删;删除车型行即解除引用。
     */
    @Transactional
    public void delete(Long id) {
        CarModelEntity m = modelMapper.selectById(id);
        if (m == null) throw new IllegalArgumentException("车型不存在");
        modelMapper.deleteById(id);
        versionMapper.delete(new QueryWrapper<CarVersionEntity>().eq("model_id", id));
        groupMapper.delete(new QueryWrapper<CarParamGroupEntity>().eq("model_id", id));
        paramMapper.delete(new QueryWrapper<CarParamEntity>().eq("model_id", id));
        docService.deleteByModel(id);
        // 10-03 E6:旧向量表已退役,仅按 modelId 兜底物理清 store（含历史逻辑删除块残留）
        if (vectorStoreService != null) vectorStoreService.deleteByCarModel(id);
    }

    /**
     * 同步失败回写(10-21-knowledge-car-sync-ux R2):由同步任务 catch 分支调用,确保车型
     * 不永久停留「同步中」。只写 sync_status=FAILED / last_sync_error / updated_at,
     * **不写 last_sync_at**(该字段保留最后成功时间)。error 截断 500 字符(列宽保护)。
     * 回写自身失败由调用方 try/catch 兜底,不覆盖任务原始异常。
     */
    public void markSyncFailed(String goodsId, String error) {
        if (goodsId == null || goodsId.isBlank()) return;
        String msg = (error == null || error.isBlank()) ? "未知错误" : error;
        if (msg.length() > 500) msg = msg.substring(0, 500);
        modelMapper.update(null, new UpdateWrapper<CarModelEntity>()
                .eq("goods_id", goodsId)
                .set("sync_status", "FAILED")
                .set("last_sync_error", msg)
                .set("updated_at", LocalDateTime.now()));
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
        // 2.5) 车型图片(封面主体图 + 详情图集)下载转存图床,写入图库(source='byd');单图失败不阻断整车型同步
        persistImages(model, item);
        // 3) 清洗参数(规则引擎 + AI 兜底);统计随清洗落库,同步任务记录进 job 明细
        CleanStats stats = cleanService.cleanForModel(model.getId());
        // 4) 切分向量化(网络 embedding,无事务;基于清洗后数据)
        docService.rebuildForModel(model.getId());
        return new SyncOutcome(model, stats);
    }

    /** 同步结果:车型 + 清洗方式统计(供同步任务聚合记录)。 */
    /**
     * 全库向量对账统计(S6b 遗留项 R4):块数/有向量块数/缺失块数 + 缺失明细 topN。
     * 消除「静默丢块」对账盲区(体检靠手工 SQL 才发现 31.6% 缺失)。
     */
    public Map<String, Object> vectorStats() {
        List<CarModelEntity> models = modelMapper.selectList(null);
        Map<Long, String> nameById = new java.util.LinkedHashMap<>();
        for (CarModelEntity m : models) nameById.put(m.getId(), m.getName());
        // 10-03 E6:旧表退役,对账改查单表 store:
        //  - chunkCount:未逻辑删除块数(主表权威)
        //  - embeddedCount:store(domain=CAR + 当前模型)按 modelId 聚合的块数
        Map<Long, Long> chunkByModel = new java.util.LinkedHashMap<>();
        for (CarChunkEntity c : chunkMapper.selectList(null)) {
            if (c.getModelId() != null) chunkByModel.merge(c.getModelId(), 1L, Long::sum);
        }
        // store(domain=CAR + 当前模型)按 modelId 的有向量块数
        Map<Long, Long> embeddedByModel = new java.util.LinkedHashMap<>();
        List<Map<String, Object>> rows = vectorStoreService == null
                ? List.of() : vectorStoreService.countCarEmbeddedByModel(embeddingClient.modelName());
        for (Map<String, Object> row : rows) {
            if (row.get("modelId") == null) continue;
            embeddedByModel.put(((Number) row.get("modelId")).longValue(),
                    ((Number) row.get("embeddedCount")).longValue());
        }
        // 以块表为权威遍历每个车型：块数 − store 当前模型块数 = 缺失；store 未覆盖的车型缺失 = 全部块数
        int chunkCount = 0, embedded = 0;
        List<Map<String, Object>> missingTopN = new ArrayList<>();
        for (Map.Entry<Long, Long> e : chunkByModel.entrySet()) {
            long modelId = e.getKey();
            long total = e.getValue();
            long emb = embeddedByModel.getOrDefault(modelId, 0L);
            long missing = Math.max(0, total - emb);
            chunkCount += (int) total;
            embedded += (int) emb;
            if (missing > 0) {
                Map<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("modelId", modelId);
                item.put("modelName", nameById.getOrDefault(modelId, String.valueOf(modelId)));
                item.put("missing", missing);
                missingTopN.add(item);
            }
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("modelCount", models.size());
        out.put("chunkCount", chunkCount);
        out.put("embeddedCount", embedded);
        out.put("missingCount", chunkCount - embedded);
        out.put("missingTopN", missingTopN);
        return out;
    }

    /**
     * 批量重建全部车型向量(S6b 遗留项 R1):逐车型 rebuildForModel(新切块口径+向量补齐)。
     * 单车型失败不阻断其余;返回汇总。一次性运维操作,同步接口(前端 loading)。
     */
    public Map<String, Object> rebuildAll() {
        List<CarModelEntity> models = modelMapper.selectList(new QueryWrapper<CarModelEntity>().orderByAsc("id"));
        int okModels = 0, failedModels = 0;
        StringBuilder failedIds = new StringBuilder();
        for (CarModelEntity m : models) {
            try {
                docService.rebuildForModel(m.getId());
                okModels++;
            } catch (Exception e) {
                failedModels++;
                failedIds.append(m.getId()).append(',');
                log.warn("批量重建失败 model={}: {}", m.getId(), e.getMessage());
            }
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("total", models.size());
        out.put("success", okModels);
        out.put("failed", failedModels);
        out.put("failedModelIds", failedIds.length() > 0 ? failedIds.substring(0, failedIds.length() - 1) : "");
        return out;
    }

    public record SyncOutcome(CarModelEntity model, CleanStats cleanStats) {}

    /**
     * 增量分派/轻回填执行结果(10-15-car-image-backfill B2):note 为任务明细备注(可空,
     * 如「官网无图」「跳过:指纹一致」),outcome 为同步产物(SKIP 分支为 null;轻回填的
     * cleanStats 恒为 null —— 轻回填不触清洗域)。
     */
    public record IncrementalOutcome(String note, SyncOutcome outcome) {}

    /** 指纹序列化专用 Jackson(确定性输出,与全局实例解耦)。 */
    private static final ObjectMapper FP_JSON = new ObjectMapper();

    /**
     * 计算官网 goodsInfo 稳定字段指纹(R2 增量调度依据):name/salesNetworkName/vehicleId/
     * price/features/introduce(逐项原始顺序,Jackson 数组天然保序)/carRights 序列化后
     * SHA-256 → 64 位 hex。
     * 包级静态可见:增量分派单测需以同一口径构造「指纹一致/不一致」用例(纯函数,对齐
     * 「纯算法抽静态方法并单测」先例)。
     * 已知盲区(设计明示):goodsParams 独立变化不触发增量,需手动全目录同步兜底。
     */
    static String computeFingerprint(GoodsInfoDto.Item item) {
        if (item == null) return null;
        try {
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("name", item.getName());
            canonical.put("salesNetworkName", item.getSalesNetworkName());
            canonical.put("vehicleId", item.getVehicleId());
            canonical.put("price", item.getPrice());
            canonical.put("features", item.getFeatures());
            canonical.put("introduce", item.getIntroduce());
            canonical.put("carRights", item.getCarRights());
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(FP_JSON.writeValueAsBytes(canonical));
            return sha256Hex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("车型同步指纹计算失败", e);
        }
    }

    /** 取 goodsInfo 首个 item(空响应返回 null,沿 syncOne 判空口径)。 */
    private static GoodsInfoDto.Item firstGoodsInfoItem(GoodsInfoDto info) {
        if (info == null || info.getGoodsinfoList() == null
                || info.getGoodsinfoList().getItems() == null
                || info.getGoodsinfoList().getItems().isEmpty()) return null;
        return info.getGoodsinfoList().getItems().get(0);
    }

    /** SHA-256 摘要 → 64 位 hex(指纹共用)。 */
    private static String sha256Hex(byte[] digest) {
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    /**
     * 轻回填(存量车型图片补入库,R1):仅拉 goodsInfo(不调 goodsParams/goodsAttrList)→
     * 复用 persistImages(封面+详情图集;下载/内容哈希去重/图床转存/「车型-名」自动打标)→ 收敛
     * sync_fingerprint + syncStatus=SUCCESS + lastSyncAt。不走 persistModel:版本、参数、
     * 清洗、向量域零触碰。introduce 为空(官网无图)时详情图集分支早退、不冲写
     * intro_images,note=「官网无图」供任务明细标注。
     *
     * @return 增量结果:note(官网无图时非空)+ outcome(model + null 清洗统计)
     * @throws IllegalStateException 车型行不存在/已删除(回填范围来自库存行,行缺失说明被并发删除,不复活)
     */
    public IncrementalOutcome backfillImages(String goodsId) {
        GoodsInfoDto.Item item = firstGoodsInfoItem(client.goodsInfo(goodsId));
        CarModelEntity row = modelMapper.selectOne(new QueryWrapper<CarModelEntity>().eq("goods_id", goodsId));
        if (row == null) throw new IllegalStateException("车型不存在或已删除,无法轻回填: goodsId=" + goodsId);
        return backfillInternal(row, item);   // note(官网无图/无数据兜底)由 backfillInternal 生成
    }

    /**
     * 增量分派(定时 INCREMENTAL 任务逐车型入口,R2 分派表):
     *   行不存在            → 全量 syncOne(官网目录新增车型)
     *   指纹 NULL           → 轻回填(存量兼容,顺带初始化指纹)
     *   指纹 == 新 goodsInfo 指纹 → 跳过(零下载/零清洗/零嵌入;note=「跳过:指纹一致」)
     *   指纹不一致          → 全量 syncOne(price/卖点/图片字段均在 goodsInfo 内,可触发)
     * 跳过分支不触碰参数/清洗/向量,也不重写 intro_images。
     */
    public IncrementalOutcome dispatchIncremental(String goodsId) {
        CarModelEntity row = modelMapper.selectOne(new QueryWrapper<CarModelEntity>().eq("goods_id", goodsId));
        if (row == null) return new IncrementalOutcome(null, syncOne(goodsId));
        GoodsInfoDto.Item item = firstGoodsInfoItem(client.goodsInfo(goodsId));
        String stored = row.getSyncFingerprint();
        if (stored == null || stored.isBlank()) {
            // 10-15 真机修订:backfillInternal 含「无条目」兜底与 note 生成,item 职责单一化
            return backfillInternal(row, item);
        }
        if (item != null && stored.equals(computeFingerprint(item))) {
            return new IncrementalOutcome("跳过:指纹一致", null);
        }
        if (item == null && stored.equals(computeRowSnapshotFingerprint(row))) {
            // 官网当前无条目且库存快照未变 → 跳过(每天一次 goodsInfo 调用,零下载/零清洗/零嵌入)
            return new IncrementalOutcome("跳过:官网无数据(与上次快照一致)", null);
        }
        return new IncrementalOutcome(null, syncOne(goodsId));
    }

    /**
     * 轻回填执行体(复用调用方已拉取的 item,免二次网络调用),note 一并在此生成。
     * 有条目:走 persistImages(封面+详情图集,item 口径指纹);无条目:存量 URL 下载兜底 + 库存行快照指纹。
     * 清洗净为 null。
     */
    private IncrementalOutcome backfillInternal(CarModelEntity row, GoodsInfoDto.Item item) {
        String note;
        if (item != null) {
            persistImages(row, item);   // 封面 + 详情图集;单图失败仅告警不阻断;空字段早退不冲写
            row.setSyncFingerprint(computeFingerprint(item));
            note = (item.getIntroduce() == null || item.getIntroduce().isEmpty()) ? "官网无图" : null;
        } else {
            // 10-15 真机发现(10 车型 goodsInfo 返回空条目):无条目 ≠ 无图——用库存行的存量 URL 直接下载兜底。
            // 指纹用库存行快照口径:官网数据恢复后 goodsInfo 指纹必不匹配 → 增量触发一次全量收敛;
            // 未恢复时快照一致 → 增量跳过,每天零成本。
            List<Long> ids = persistLegacyIntroImages(row);
            if (!ids.isEmpty()) row.setIntroImages(toJson(ids));
            row.setSyncFingerprint(computeRowSnapshotFingerprint(row));
            note = ids.isEmpty() ? "官网无数据,且无存量图可兜底" : "官网无数据,已按存量URL兜底入库" + ids.size() + "张";
        }
        row.setSyncStatus("SUCCESS");
        row.setLastSyncError(null);   // 成功同步清除历史失败原因(R2)
        row.setLastSyncAt(LocalDateTime.now());
        row.setUpdatedAt(LocalDateTime.now());
        modelMapper.updateById(row);
        return new IncrementalOutcome(note, new SyncOutcome(row, null));
    }

    /**
     * 存量 URL 兜底(轻回填/增量「无条目」分支):解析库存行 intro_images 中的 http URL 项
     * (旧管线格式)逐张下载转存,复用与 introduce 图片同口径的入库管线(哈希去重/图床/打标)。
     * 单张失败仅告警;全部失败时回退,不打乱存量 intro_images(空返回由调用方语义收敛)。
     */
    private List<Long> persistLegacyIntroImages(CarModelEntity row) {
        List<String> urls = legacyIntroUrls(row);
        List<Long> ids = new ArrayList<>();
        for (String url : urls) persistIntroImageUrl(row, url, ids);
        return ids;
    }

    /** 解析存量 intro_images 中的 http URL 项(JSON 数组;已收敛为 id 列表的行返回空)。 */
    private List<String> legacyIntroUrls(CarModelEntity row) {
        List<String> urls = new ArrayList<>();
        String raw = row.getIntroImages();
        if (raw == null || raw.isBlank()) return urls;
        try {
            JsonNode arr = json.readTree(raw);
            if (arr.isArray()) {
                for (JsonNode node : arr) {
                    String v = node.asText(null);
                    if (v != null && v.trim().startsWith("http")) urls.add(v.trim());
                }
            }
        } catch (Exception ignore) {
            // intro_images 非法 JSON,兜底失败由「无存量图可兜底」备注提示
        }
        return urls;
    }

    /**
     * 无条目时库存行快照指纹(与 computeFingerprint 同哈希口径):官网恢复数据后 goodsInfo 指纹
     * 必不匹配快照 → 增量自动触发一次全量收敛;未恢复则快照一致 → 跳过。features/carRights/
     * intro_images 库内为 JSON 字符串,统一 readTree 还原后再入 canonical(与 item 口径序列化形状一致)。
     */
    static String computeRowSnapshotFingerprint(CarModelEntity row) {
        if (row == null) return null;
        try {
            Map<String, Object> canonical = new java.util.LinkedHashMap<>();
            canonical.put("name", row.getName());
            canonical.put("salesNetworkName", row.getSalesNetwork());
            canonical.put("vehicleId", row.getVehicleId());
            canonical.put("price", row.getPriceRange());
            canonical.put("features", safeParseJson(row.getFeatures()));
            canonical.put("introduce", safeParseJson(row.getIntroImages()));
            canonical.put("carRights", safeParseJson(row.getCarRights()));
            // 必须先做 SHA-256 摘要再转 hex;sha256Hex 本身不做摘要(传原始字节会产出超长串,VARCHAR(64) 装不下)
            return sha256Hex(MessageDigest.getInstance("SHA-256").digest(FP_JSON.writeValueAsBytes(canonical)));
        } catch (Exception e) {
            throw new IllegalStateException("车型同步指纹计算失败(行快照口径)", e);
        }
    }

    /** JSON 字符串 → JsonNode(还原官网数据的结构化形态供序列化);不可解析时原样字符串。 */
    private static Object safeParseJson(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        try {
            return FP_JSON.readTree(raw);
        } catch (Exception e) {
            return raw;
        }
    }

    /**
     * 车型图片统一落库(10-21 R1):封面(goodsInfo.img 主体图)+ 详情图集(goodsInfo.introduce)。
     * 两者分离、互不覆盖;单图失败仅告警不阻断整车型同步。有任一字段实际变化时统一 updateById 一次。
     * 空/失败不冲写既有列(封面为空或下载失败保留旧封面;introduce 为空保留旧图集)。
     * 09-13 image-tags:preset 预设「车型-<车型名>」标签,走统一入库管线自动落标(零额外逻辑)。
     */
    protected void persistImages(CarModelEntity model, GoodsInfoDto.Item item) {
        if (item == null) return;
        boolean changed = false;
        // 封面:官网主体图(透明 PNG),命名 <name>-cover.<ext>;标签额外带「车型主图」便于图库筛选
        if (item.getImg() != null && !item.getImg().isBlank()) {
            Long coverId = persistImageToLibrary(model, item.getImg(), model.getName() + "-cover",
                    List.of("车型-" + model.getName(), "车型主图"));
            if (coverId != null && !coverId.equals(model.getCoverImageId())) {
                model.setCoverImageId(coverId);
                changed = true;
            }
        }
        // 详情图集:introduce 逐张入库,序号命名 <name>-<n>.<ext>
        if (item.getIntroduce() != null && !item.getIntroduce().isEmpty()) {
            List<Long> ids = new ArrayList<>();
            for (String url : item.getIntroduce()) {
                Long id = persistImageToLibrary(model, url, model.getName() + "-" + (ids.size() + 1),
                        List.of("车型-" + model.getName()));
                if (id != null) ids.add(id);
            }
            if (!ids.isEmpty()) {
                String json = toJson(ids);
                if (!json.equals(model.getIntroImages())) {   // 幂等重跑(哈希命中原 id)不产生无效写
                    model.setIntroImages(json);
                    changed = true;
                }
            }
        }
        if (changed) modelMapper.updateById(model);
    }

    /** 单张车型介绍图入库(存量 URL 兜底共用):下载→哈希去重→图床→「车型-名」标签;单图失败仅告警。 */
    private void persistIntroImageUrl(CarModelEntity model, String url, List<Long> ids) {
        Long id = persistImageToLibrary(model, url, model.getName() + "-" + (ids.size() + 1),
                List.of("车型-" + model.getName()));
        if (id != null) ids.add(id);
    }

    /**
     * 通用单图入库(B1):下载图片 → 魔数嗅探扩展名 → ImageService.persistOrReuse(内容哈希去重 +
     * 图床转存 + 标签落标)。fileNamePrefix 为不含扩展名的文件名前缀(如 <name>-1 / <name>-cover)。
     *
     * @return 图库资产 id;URL 空、下载失败或入库失败时返回 null(仅告警,不阻断调用方)
     */
    private Long persistImageToLibrary(CarModelEntity model, String url, String fileNamePrefix, List<String> tags) {
        if (url == null || url.isBlank()) return null;
        try {
            byte[] bytes = downloadImage(url);
            String ext = sniffImageExt(bytes);
            // S10:走 ImageService 统一入库管线（内容哈希去重;同步幂等重跑不重复占图床对象）
            com.sparkora.domain.entity.ImageAssetEntity preset = new com.sparkora.domain.entity.ImageAssetEntity();
            preset.setProjectId(null);                       // 全局图库
            preset.setFileName(fileNamePrefix + "." + ext);
            preset.setSource("byd");
            preset.setCreatedBy("system");
            preset.setTags(tags);                            // 09-13:自动分类标签(管线自动落标)
            ImageAssetEntity e = imageService.persistOrReuse(bytes, ext, preset);
            log.info("比亚迪车型图已入库 model={} url={} id={} dedupe={}",
                    model.getName(), shorten(url), e.getId(), Boolean.TRUE.equals(e.getDedupeHit()));
            return e.getId();
        } catch (Exception ex) {
            log.warn("比亚迪车型图转存失败(跳过): model={} url={} err={}", model.getName(), shorten(url), ex.getMessage());
            return null;
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
            // 10-15 R2:全量路径统一写指纹(增量分派依据;既有 upsert 语义不变,仅加此列写入)
            m.setSyncFingerprint(computeFingerprint(item));
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

    /** 入库版本(先清后插,幂等)。S6b:同名版本去重(官网接口按模块重复推送版本列表,实测 ×2~×10),只保留首个。 */
    @Transactional
    protected void persistVersions(Long modelId, List<GoodsAttrListDto.Version> attrs) {
        versionMapper.delete(new QueryWrapper<CarVersionEntity>().eq("model_id", modelId));
        if (attrs == null) return;
        java.util.Set<String> seen = new java.util.HashSet<>();
        int i = 0;
        for (GoodsAttrListDto.Version v : attrs) {
            if (v.getName() == null || v.getName().isBlank()) continue;
            if (!seen.add(v.getName().trim())) continue;   // 重复版本行跳过(数据源冗余,见 research/flatten-findings.md)
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

    /** 入库参数分组+明细(先清后插,幂等)。S6b:同名分组去重(官网按页签/模块重复推送,车型39 实测 136 组/17 名),只保留首个。 */
    @Transactional
    protected void persistParams(Long modelId, GoodsParamsDto.ParamsData params) {
        groupMapper.delete(new QueryWrapper<CarParamGroupEntity>().eq("model_id", modelId));
        paramMapper.delete(new QueryWrapper<CarParamEntity>().eq("model_id", modelId));
        if (params == null || params.getConfigs() == null) return;
        java.util.Set<String> seenGroups = new java.util.HashSet<>();
        int gi = 0;
        for (GoodsParamsDto.Config cfg : params.getConfigs()) {
            if (cfg.getName() == null || cfg.getName().isBlank()) continue;
            if (!seenGroups.add(cfg.getName().trim())) continue;   // 重复同名分组整组跳过(参数行与组重复绑定)
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
