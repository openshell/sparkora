package com.sparkora.car.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.ai.EmbedStats;
import com.sparkora.car.client.BydCmsClient;
import com.sparkora.car.client.CarSourceException;
import com.sparkora.car.client.SourceIncompleteException;
import com.sparkora.car.client.SourceUnavailableException;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
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

    /**
     * 自注入代理(10-21):让 {@code persistModel/persistVersions/persistParams} 的
     * {@code @Transactional} 真正生效(同类 this 调用不走 Spring 代理,事务会被静默忽略)。
     * 单测直接 new 时为 null,调用方退化为 this。
     */
    @Autowired
    @Lazy
    private CarModelService self;

    /** 图片下载安全边界;单测可注入宽松实现,生产默认 HTTPS + 白名单 + 大小/像素上限。 */
    private CarImageDownloader imageDownloader = new CarImageDownloader();

    /** 同步/删除互斥锁(字段注入;单测不注入时退化为无锁)。 */
    @Autowired(required = false)
    private CarSyncLockService lockService;

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

    /** 测试用:注入图片下载器(生产默认 {@link CarImageDownloader})。 */
    void setImageDownloader(CarImageDownloader downloader) {
        if (downloader != null) this.imageDownloader = downloader;
    }

    /** 事务代理(单测 self 为空时退化为 this)。 */
    private CarModelService tx() {
        return self == null ? this : self;
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

    /**
     * 官网车型目录(供同步页手动选择)。返回 goodsListForSearch 原始 data 数组。
     * 10-21:客户端已校验 code/data,失败抛 {@link com.sparkora.car.client.SourceUnavailableException};
     * 这里不再把故障吞成空目录(否则定时任务会静默「无车可同步」)。
     */
    public List<Map<String, Object>> catalog() {
        JsonNode data = client.goodsList();
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
        // 与同步互斥:1) 全局任务锁——避免「删完车型后,尚未处理到它的同步任务又用 syncOne 把它复活」;
        // 2) 按车型锁——避免与详情页阻塞同步竞争同一车型。
        String globalOwner = lockService == null ? null : lockService.tryLock(CarSyncLockService.GLOBAL_JOB_KEY);
        if (lockService != null && globalOwner == null) {
            throw new IllegalStateException("同步任务运行中,请稍后删除");
        }
        String lockKey = CarSyncLockService.goodsKey(m.getGoodsId());
        String owner = lockService == null || m.getGoodsId() == null ? null : lockService.tryLock(lockKey);
        if (lockService != null && owner == null && m.getGoodsId() != null) {
            releaseGoodsLockAfterTransaction(CarSyncLockService.GLOBAL_JOB_KEY, globalOwner);
            throw new IllegalStateException("该车型正在同步,请稍后重试");
        }
        try {
            modelMapper.deleteById(id);
            versionMapper.delete(new QueryWrapper<CarVersionEntity>().eq("model_id", id));
            groupMapper.delete(new QueryWrapper<CarParamGroupEntity>().eq("model_id", id));
            paramMapper.delete(new QueryWrapper<CarParamEntity>().eq("model_id", id));
            docService.deleteByModel(id);
            // 10-03 E6:旧向量表已退役,仅按 modelId 兜底物理清 store（含历史逻辑删除块残留）
            if (vectorStoreService != null) vectorStoreService.deleteByCarModel(id);
        } finally {
            // 锁的 INSERT/DELETE 参与本方法事务:事务回滚时 finally 里的释放也会被回滚,锁会残留到 TTL。
            // 因此事务内只登记 afterCompletion 释放,让释放发生在提交/回滚之后(独立自动提交)。
            releaseGoodsLockAfterTransaction(lockKey, owner);
            releaseGoodsLockAfterTransaction(CarSyncLockService.GLOBAL_JOB_KEY, globalOwner);
        }
    }

    /** 事务结束后释放车型锁;无活动事务时立即释放。 */
    private void releaseGoodsLockAfterTransaction(String lockKey, String owner) {
        if (lockService == null || owner == null) return;
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            lockService.unlock(lockKey, owner);
                        }
                    });
        } else {
            lockService.unlock(lockKey, owner);
        }
    }

    /**
     * 同步失败回写(10-21-knowledge-car-sync-ux R2):由同步任务 catch 分支调用,确保车型
     * 不永久停留「同步中」。只写 sync_status=FAILED / last_sync_error / updated_at,
     * **不写 last_sync_at**(该字段保留最后成功时间)。error 截断 500 字符(列宽保护)。
     * 回写自身失败由调用方 try/catch 兜底,不覆盖任务原始异常。
     */
    public void markSyncFailed(String goodsId, String error) {
        if (goodsId == null || goodsId.isBlank()) return;
        String msg = truncate((error == null || error.isBlank()) ? "未知错误" : error, 500);
        modelMapper.update(null, new UpdateWrapper<CarModelEntity>()
                .eq("goods_id", goodsId)
                .set("sync_status", "FAILED")
                .set("last_sync_error", msg)
                .set("last_checked_at", LocalDateTime.now())
                .set("updated_at", LocalDateTime.now()));
    }

    /**
     * 官网数据为空/不完整:只更新 source_state / sync_note / last_checked_at(以及 sync_status=FAILED,
     * 避免旧 SUCCESS 误导),**不动** last_sync_at 与任何业务表(版本/参数/清洗/块/向量/图集)。
     */
    public void markSourceIncomplete(String goodsId, String sourceState, String note) {
        if (goodsId == null || goodsId.isBlank()) return;
        modelMapper.update(null, new UpdateWrapper<CarModelEntity>()
                .eq("goods_id", goodsId)
                .set("source_state", sourceState)
                .set("sync_note", truncate(note, 500))
                .set("sync_status", "FAILED")
                .set("last_sync_error", truncate(note, 500))
                .set("last_checked_at", LocalDateTime.now())
                .set("updated_at", LocalDateTime.now()));
    }

    /** 完整成功:SUCCESS + last_sync_at + last_checked_at + source_state=OK。 */
    public void markSyncSuccess(Long modelId, String note) {
        if (modelId == null) return;
        LocalDateTime now = LocalDateTime.now();
        modelMapper.update(null, new UpdateWrapper<CarModelEntity>()
                .eq("id", modelId)
                .set("sync_status", "SUCCESS")
                .set("source_state", "OK")
                .set("sync_note", truncate(note, 500))
                .set("last_sync_error", null)
                .set("last_sync_at", now)
                .set("last_checked_at", now)
                .set("updated_at", now));
    }

    /** 部分成功(图片/向量部分失败):PARTIAL + note,**不前进** last_sync_at。 */
    public void markSyncPartial(Long modelId, String note) {
        if (modelId == null) return;
        LocalDateTime now = LocalDateTime.now();
        modelMapper.update(null, new UpdateWrapper<CarModelEntity>()
                .eq("id", modelId)
                .set("sync_status", "PARTIAL")
                .set("source_state", "OK")
                .set("sync_note", truncate(note, 500))
                .set("last_sync_error", null)
                .set("last_checked_at", now)
                .set("updated_at", now));
    }

    /** 只更新检查时间 + note(跳过/无变化),不改同步状态、指纹与 last_sync_at。 */
    public void markChecked(Long modelId, String note) {
        if (modelId == null) return;
        modelMapper.update(null, new UpdateWrapper<CarModelEntity>()
                .eq("id", modelId)
                .set("last_checked_at", LocalDateTime.now())
                .set("sync_note", truncate(note, 500))
                .set("updated_at", LocalDateTime.now()));
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    /**
     * 采集异常 → 数据源状态:限流/超时/5xx 等可重试故障标 {@code STALE}(保留旧数据,官网暂不可用);
     * 其余(业务 code/data 结构异常)标 {@code INCOMPLETE}。两者都只写状态列,不碰业务表。
     */
    private static String sourceStateOf(CarSourceException e) {
        if (e instanceof SourceUnavailableException sue && sue.transientFailure()) return "STALE";
        return "INCOMPLETE";
    }

    /**
     * 同步单个车型(按 goodsId)。幂等:已存在则更新,不存在则新建。
     * 网络采集无事务;入库短事务。由 CarSyncJobService 异步任务驱动。
     *
     * <p>10-21 安全顺序:先拉三接口并校验(任一无数据/不完整 → 只标记 source_state,零业务写入并抛
     * {@link SourceIncompleteException});全部完整后才进入写入。SUCCESS 与 last_sync_at 只在
     * 图片/清洗/向量全链路成功后写;图片或向量部分失败记 PARTIAL。
     *
     * @return 同步结果:模型 + 清洗方式统计(可观测)。
     */
    public SyncOutcome syncOne(String goodsId) {
        // 1) 采集(无事务,慢) + 预检;任何空/不完整响应都在写库前中止
        GoodsInfoDto.Item item;
        try {
            item = firstGoodsInfoItem(client.goodsInfo(goodsId));
        } catch (CarSourceException e) {
            String state = sourceStateOf(e);
            markSourceIncomplete(goodsId, state, e.userMessage());
            throw new SourceIncompleteException(state, e.userMessage(), e.getMessage());
        }
        if (item == null) {
            markSourceIncomplete(goodsId, "EMPTY", "官网无数据");
            throw new SourceIncompleteException("EMPTY", "官网无数据", "goodsInfo items 为空, goodsId=" + goodsId);
        }
        GoodsParamsDto.ParamsData params;
        List<GoodsAttrListDto.Version> attrs;
        try {
            params = client.goodsParams(goodsId);
            attrs = client.goodsAttrList(goodsId);
        } catch (CarSourceException e) {
            String state = sourceStateOf(e);
            markSourceIncomplete(goodsId, state, e.userMessage());
            throw new SourceIncompleteException(state, e.userMessage(), e.getMessage());
        }
        try {
            validateSnapshot(item, params, attrs);
        } catch (SourceIncompleteException e) {
            markSourceIncomplete(goodsId, e.sourceState(), e.userMessage());
            throw e;
        }

        CarModelEntity model;
        try {
            // 2) 入库(单事务:主表 + 版本 + 参数;网络已在事务外完成)
            CorePersistResult core = tx().persistCore(goodsId, item, params, attrs);
            model = core.model();
            boolean versionsOk = core.versionsOk();
            boolean paramsOk = core.paramsOk();
            if (!versionsOk || !paramsOk) {
                markSyncPartial(model.getId(), "官网版本/参数不完整,已保留旧数据");
            }
            // 2.5) 图片(封面 + 详情图集);失败保留旧图集/封面并进入 note
            ImagePersistResult images = persistImages(model, item);
            // 3) 清洗参数(规则引擎 + AI 兜底)
            CleanStats stats = cleanService.cleanForModel(model.getId());
            // 4) 切分向量化(先构建新块/向量,成功后原子替换;失败保留旧块)
            EmbedStats embed = docService.rebuildForModel(model.getId());
            // 5) 指纹收敛:图片失败保留旧 image_fingerprint;向量未全部成功、或版本/参数因空数据
            //    未真正替换时,**不推进**基础/参数指纹 —— 否则下一次增量会因指纹一致而跳过,
            //    旧块/旧向量/旧参数永久不补。
            boolean vectorOk = embed == null || embed.failed() == 0;
            boolean coreOk = versionsOk && paramsOk;
            boolean fpOk = vectorOk && coreOk;
            updateFingerprints(model.getId(), item, params, attrs, images.failed() == 0, fpOk);
            // 响应实体同步最新指纹(与库里条件写入口径一致)
            if (fpOk) {
                model.setSyncFingerprint(computeFingerprint(item));
                model.setParamsFingerprint(computeParamsFingerprint(params, attrs));
            }
            if (images.failed() == 0) model.setImageFingerprint(computeImageFingerprint(item));

            java.util.List<String> notes = new ArrayList<>();
            if (!versionsOk) notes.add("版本不完整");
            if (!paramsOk) notes.add("参数不完整");
            if (stats == null || stats.total() == 0) notes.add("清洗结果为空");
            if (images.failed() > 0) notes.add("图片失败" + images.failed() + "张");
            if (embed != null && embed.failed() > 0) notes.add("向量失败" + embed.failed() + "/" + embed.total() + "块");
            if (notes.isEmpty()) {
                markSyncSuccess(model.getId(), null);
                model.setSyncStatus("SUCCESS");
                model.setSyncNote(null);
            } else {
                String note = String.join(";", notes);
                markSyncPartial(model.getId(), note);
                model.setSyncStatus("PARTIAL");
                model.setSyncNote(note);
            }
            // 响应携带最新派生状态
            model.setLastCheckedAt(LocalDateTime.now());
            model.setLastSyncError(null);
            model.setSourceState("OK");
            return new SyncOutcome(model, stats);
        } catch (SourceIncompleteException e) {
            throw e;
        } catch (Exception e) {
            // 写入/清洗/向量失败:落 FAILED + 分类短文案;last_sync_at 不前进
            String msg = CarSourceException.classify(e);
            try {
                markSyncFailed(goodsId, msg);
            } catch (Exception ex) {
                log.warn("车型失败状态回写失败(忽略) goodsId={}: {}", goodsId, ex.getMessage());
            }
            log.warn("车型同步写入失败 goodsId={}: {}", goodsId, e.getMessage(), e);
            throw e;
        }
    }

    /** 三接口预检:任一不完整即抛出(由调用方标记 source_state;本方法零写入)。 */
    static void validateSnapshot(GoodsInfoDto.Item item, GoodsParamsDto.ParamsData params,
                                 List<GoodsAttrListDto.Version> attrs) {
        if (item == null || item.getName() == null || item.getName().isBlank()) {
            throw new SourceIncompleteException("INCOMPLETE", "官网车型信息不完整", "车型名缺失");
        }
        if (params == null || params.getConfigs() == null || params.getConfigs().isEmpty()) {
            throw new SourceIncompleteException("INCOMPLETE", "官网参数数据为空", "goodsParams.configs 为空");
        }
        if (attrs == null || attrs.isEmpty()) {
            throw new SourceIncompleteException("INCOMPLETE", "官网版本数据为空", "goodsAttrList 为空");
        }
    }

    /** 参数/版本定向同步(仅参数指纹变化时):不重下图片,仅替换版本/参数/清洗/块。 */
    public SyncOutcome syncParamsOnly(CarModelEntity row, GoodsInfoDto.Item item,
                                      GoodsParamsDto.ParamsData params, List<GoodsAttrListDto.Version> attrs) {
        try {
            validateSnapshot(item, params, attrs);
        } catch (SourceIncompleteException e) {
            markSourceIncomplete(row.getGoodsId(), e.sourceState(), e.userMessage());
            throw e;
        }
        ParamsPersistResult pr = tx().persistParamsCore(row.getId(), params, attrs);
        boolean versionsOk = pr.versionsOk();
        boolean paramsOk = pr.paramsOk();
        CleanStats stats = cleanService.cleanForModel(row.getId());
        EmbedStats embed = docService.rebuildForModel(row.getId());
        boolean vectorOk = embed == null || embed.failed() == 0;
        boolean fpOk = vectorOk && versionsOk && paramsOk;
        // 图片域本次未参与:仅当已有图片指纹时才写回(未初始化时保持 NULL,交给轻回填收敛)。
        boolean imageOk = !isBlank(row.getImageFingerprint());
        updateFingerprints(row.getId(), item, params, attrs, imageOk, fpOk);
        if (fpOk) {
            row.setSyncFingerprint(computeFingerprint(item));
            row.setParamsFingerprint(computeParamsFingerprint(params, attrs));
        }
        if (imageOk) row.setImageFingerprint(computeImageFingerprint(item));
        java.util.List<String> notes = new ArrayList<>();
        if (!versionsOk) notes.add("版本不完整");
        if (!paramsOk) notes.add("参数不完整");
        if (stats == null || stats.total() == 0) notes.add("清洗结果为空");
        if (embed != null && embed.failed() > 0) notes.add("向量失败" + embed.failed() + "/" + embed.total() + "块(下次增量重试)");
        if (notes.isEmpty()) {
            markSyncSuccess(row.getId(), null);
            row.setSyncStatus("SUCCESS");
            row.setSyncNote(null);
        } else {
            String note = String.join(";", notes);
            markSyncPartial(row.getId(), note);
            row.setSyncStatus("PARTIAL");
            row.setSyncNote(note);
        }
        row.setSourceState("OK");
        row.setLastCheckedAt(LocalDateTime.now());
        return new SyncOutcome(row, stats);
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
                EmbedStats st = docService.rebuildForModel(m.getId());
                if (st != null && st.failed() > 0) {
                    failedModels++;
                    failedIds.append(m.getId()).append(',');
                    log.warn("批量重建部分失败 model={} 失败块={}/{}", m.getId(), st.failed(), st.total());
                } else {
                    okModels++;
                }
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
     * 基础信息指纹(10-21 拆分):name/salesNetworkName/vehicleId/price/features/carRights。
     * 图片域(introduce/img)移到 {@link #computeImageFingerprint}、参数域移到
     * {@link #computeParamsFingerprint};base 变化才触发全量同步。
     *
     * <p>注意:口径变更会使存量 sync_fingerprint 在部署后首轮不匹配,触发一次全量收敛,之后稳定。
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
            canonical.put("carRights", item.getCarRights());
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(FP_JSON.writeValueAsBytes(canonical));
            return sha256Hex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("车型同步指纹计算失败", e);
        }
    }

    /**
     * 旧口径基础指纹(含 introduce):仅用于存量兼容迁移。
     * 10-21 前 sync_fingerprint 含 introduce;新口径把图片拆到 image_fingerprint。
     * 若存量值等于旧口径,视为「基础信息未变」——只更新指纹(避免全库一次性重清洗/重嵌入),
     * 图片变化由 image_fingerprint 判定。
     */
    static String computeLegacyFingerprint(GoodsInfoDto.Item item) {
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
            throw new IllegalStateException("车型旧口径指纹计算失败", e);
        }
    }

    /** 图片域指纹(10-21):SHA-256(img + introduce,逐项原始顺序)。 */
    static String computeImageFingerprint(GoodsInfoDto.Item item) {
        if (item == null) return null;
        try {
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("img", item.getImg());
            canonical.put("introduce", item.getIntroduce());
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(FP_JSON.writeValueAsBytes(canonical));
            return sha256Hex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("车型图片指纹计算失败", e);
        }
    }

    /** 参数域指纹(10-21):SHA-256(goodsParams + goodsAttrList 原始结构,列表顺序敏感)。 */
    static String computeParamsFingerprint(GoodsParamsDto.ParamsData params, List<GoodsAttrListDto.Version> attrs) {
        try {
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("params", params);
            canonical.put("attrs", attrs);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(FP_JSON.writeValueAsBytes(canonical));
            return sha256Hex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("车型参数指纹计算失败", e);
        }
    }

    /**
     * 统一写三类指纹(全量/定向同步共用)。
     *
     * <p>imageOk=false 时保留旧 image_fingerprint:图片下载/入库失败后必须让下一次增量继续重试,
     * 不能提前把新 hash 写进库造成「指纹已收敛但图片仍是旧图集」的永久跳过。
     * vectorOk=false(embedding 部分/全部失败,旧块已保留)时**不推进基础/参数指纹**:否则下一次
     * 增量会因指纹一致而跳过,旧块永远补不上;保留旧指纹可让下一次增量重跑全量/参数域。
     */
    private void updateFingerprints(Long modelId, GoodsInfoDto.Item item,
                                    GoodsParamsDto.ParamsData params, List<GoodsAttrListDto.Version> attrs,
                                    boolean imageOk, boolean vectorOk) {
        if (modelId == null) return;
        modelMapper.update(null, new UpdateWrapper<CarModelEntity>()
                .eq("id", modelId)
                .set(vectorOk, "sync_fingerprint", computeFingerprint(item))
                .set(imageOk, "image_fingerprint", computeImageFingerprint(item))
                .set(vectorOk, "params_fingerprint", computeParamsFingerprint(params, attrs))
                .set("updated_at", LocalDateTime.now()));
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
        CarModelEntity row = modelMapper.selectOne(new QueryWrapper<CarModelEntity>().eq("goods_id", goodsId));
        if (row == null) throw new IllegalStateException("车型不存在或已删除,无法轻回填: goodsId=" + goodsId);
        GoodsInfoDto.Item item;
        try {
            item = firstGoodsInfoItem(client.goodsInfo(goodsId));
        } catch (CarSourceException e) {
            // 官网不可用:只标 source_state(STALE/INCOMPLETE),不动业务表,也不冒充成功
            String state = sourceStateOf(e);
            markSourceIncomplete(goodsId, state, e.userMessage());
            throw new SourceIncompleteException(state, e.userMessage(), e.getMessage());
        }
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
        GoodsInfoDto.Item item;
        try {
            item = firstGoodsInfoItem(client.goodsInfo(goodsId));
        } catch (CarSourceException e) {
            // 官网不可用:只标 source_state(STALE/INCOMPLETE),不触业务表,增量任务记失败
            String state = sourceStateOf(e);
            markSourceIncomplete(goodsId, state, e.userMessage());
            throw new SourceIncompleteException(state, e.userMessage(), e.getMessage());
        }
        String stored = row.getSyncFingerprint();
        if (stored == null || stored.isBlank()) {
            // 存量兼容:基础指纹未初始化 → 轻回填(图片域),不重建参数/清洗/向量
            return backfillInternal(row, item);
        }
        if (item == null) {
            if (stored.equals(computeRowSnapshotFingerprint(row))) {
                // 官网当前无条目且库存快照未变 → 跳过(仅一次 goodsInfo 调用;零下载/零清洗/零嵌入)。
                // 仍必须如实标 EMPTY:10-15 老口径的存量行可能还是 SUCCESS/NULL,不能继续冒充已同步。
                markSourceIncomplete(goodsId, "EMPTY", "官网无数据(与上次快照一致)");
                return new IncrementalOutcome("跳过:官网无数据(与上次快照一致)", null);
            }
            return new IncrementalOutcome(null, syncOne(goodsId));
        }

        // 拉参数/版本用于拆分指纹(参数独立变化也要能发现)
        GoodsParamsDto.ParamsData params;
        List<GoodsAttrListDto.Version> attrs;
        try {
            params = client.goodsParams(goodsId);
            attrs = client.goodsAttrList(goodsId);
            validateSnapshot(item, params, attrs);
        } catch (CarSourceException e) {
            // 明确「空/不完整」(SourceIncompleteException)沿其状态;采集不可用按瞬态标 STALE。
            String state = e instanceof SourceIncompleteException sie ? sie.sourceState() : sourceStateOf(e);
            String msg = e.userMessage();
            markSourceIncomplete(goodsId, state, msg);
            throw new SourceIncompleteException(state, msg, e.getMessage());
        }

        String baseFp = computeFingerprint(item);
        String imageFp = computeImageFingerprint(item);
        String paramsFp = computeParamsFingerprint(params, attrs);
        // 存量兼容:旧口径(含 introduce)指纹视为基础信息未变,只迁移指纹不触发全量
        boolean legacyBase = stored.equals(computeLegacyFingerprint(item));
        boolean baseChanged = !stored.equals(baseFp) && !legacyBase;
        boolean imageMissing = isBlank(row.getImageFingerprint());
        boolean paramsMissing = isBlank(row.getParamsFingerprint());
        boolean imageChanged = !imageMissing && !row.getImageFingerprint().equals(imageFp);
        boolean paramsChanged = !paramsMissing && !row.getParamsFingerprint().equals(paramsFp);

        if (!baseChanged && !imageChanged && !paramsChanged) {
            if (imageMissing || paramsMissing || legacyBase) {
                // 首次拆分/旧口径迁移:参数域只补指纹、不重建(避免存量全量重清洗/重嵌入)。
                // 图片域不同:若官网有条目图片而指纹未初始化,必须真正补图,且只有全部成功才收敛指纹;
                // 失败时保持 NULL,保证下一次增量仍会重试(不能只写 hash 造成「图未补、指纹已收敛」)。
                ImagePersistResult imgResult = null;
                boolean imageOk = true;
                if (imageMissing && hasImages(item)) {
                    imgResult = persistImages(row, item);
                    imageOk = imgResult.failed() == 0;
                }
                modelMapper.update(null, new UpdateWrapper<CarModelEntity>()
                        .eq("id", row.getId())
                        .set("sync_fingerprint", baseFp)
                        .set(imageOk, "image_fingerprint", imageFp)
                        .set("params_fingerprint", paramsFp)
                        .set("last_checked_at", LocalDateTime.now())
                        .set("updated_at", LocalDateTime.now()));
                if (imgResult != null) {
                    String note = imageOk ? null
                            : "图片失败" + imgResult.failed() + "张(保留旧图集;图片指纹未推进)";
                    if (imageOk) {
                        markSyncSuccess(row.getId(), note);
                        row.setSyncStatus("SUCCESS");
                    } else {
                        markSyncPartial(row.getId(), note);
                        row.setSyncStatus("PARTIAL");
                    }
                    row.setSyncNote(note);
                    if (imageOk) row.setImageFingerprint(imageFp);
                    row.setSourceState("OK");
                    row.setLastCheckedAt(LocalDateTime.now());
                    return new IncrementalOutcome(note, new SyncOutcome(row, null));
                }
                return new IncrementalOutcome("跳过:指纹一致(已初始化图片/参数指纹)", null);
            }
            markChecked(row.getId(), "跳过:指纹一致");
            return new IncrementalOutcome("跳过:指纹一致", null);
        }
        if (baseChanged) {
            // 基础信息变化 → 全量(图片/参数/清洗/向量)
            return new IncrementalOutcome(null, syncOne(goodsId));
        }
        if (imageChanged && !paramsChanged) {
            // 只改图片 → 只更新图片域,不触清洗/向量
            ImagePersistResult r = persistImages(row, item);
            // 图片失败时保留旧 image_fingerprint,让下一次增量继续重试;成功才推进指纹。
            modelMapper.update(null, new UpdateWrapper<CarModelEntity>()
                    .eq("id", row.getId())
                    .set(r.failed() == 0, "image_fingerprint", imageFp)
                    .set("last_checked_at", LocalDateTime.now())
                    .set("updated_at", LocalDateTime.now()));
            if (r.failed() == 0) row.setImageFingerprint(imageFp);
            String note = r.failed() > 0 ? "图片失败" + r.failed() + "张(保留旧图集)" : null;
            row.setSyncNote(note);
            if (r.failed() > 0) {
                markSyncPartial(row.getId(), note);
                row.setSyncStatus("PARTIAL");
            } else {
                markSyncSuccess(row.getId(), note);
                row.setSyncStatus("SUCCESS");
            }
            row.setSourceState("OK");
            row.setLastCheckedAt(LocalDateTime.now());
            return new IncrementalOutcome(note, new SyncOutcome(row, null));
        }
        if (paramsChanged && !imageChanged) {
            // 只改参数/版本 → 只重建参数/清洗/块/向量,图片不动
            return new IncrementalOutcome(null, syncParamsOnly(row, item, params, attrs));
        }
        // 图片 + 参数同时变化 → 全量收敛
        return new IncrementalOutcome(null, syncOne(goodsId));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 官网条目是否带任何图片(主体图或详情图集),用于判断图片指纹未初始化时是否需要真正补图。 */
    /** 是否已有清洗结果(用于判断图片补入库能否标完整成功)。 */
    private boolean hasCleanData(Long modelId) {
        if (modelId == null) return false;
        Long n = cleanMapper.selectCount(new QueryWrapper<CarParamCleanEntity>().eq("model_id", modelId));
        return n != null && n > 0;
    }

    private static boolean hasImages(GoodsInfoDto.Item item) {
        if (item == null) return false;
        return (item.getImg() != null && !item.getImg().isBlank())
                || (item.getIntroduce() != null && !item.getIntroduce().isEmpty());
    }

    /**
     * 轻回填执行体(复用调用方已拉取的 item,免二次网络调用),note 一并在此生成。
     * 有条目:走 persistImages(封面+详情图集,item 口径指纹);无条目:存量 URL 下载兜底 + 库存行快照指纹。
     * 清洗净为 null。
     *
     * <p>10-21:无条目(官网无数据)不得写 SUCCESS / last_sync_at,只标 source_state=EMPTY + note;
     * 有图/无图但 item 存在时,图片补入库成功可标 SUCCESS(与「官网无数据」区分)。
     */
    private IncrementalOutcome backfillInternal(CarModelEntity row, GoodsInfoDto.Item item) {
        String note;
        if (item != null) {
            ImagePersistResult r = persistImages(row, item);   // 封面 + 详情图集;失败保留旧值
            String imageFp = computeImageFingerprint(item);
            row.setSyncFingerprint(computeFingerprint(item));
            // 失败保留旧图片指纹,保证下一次补图仍会重试;成功才推进。
            if (r.failed() == 0) row.setImageFingerprint(imageFp);
            note = (item.getIntroduce() == null || item.getIntroduce().isEmpty()) ? "官网无图" : null;
            if (r.failed() > 0) {
                note = (note == null ? "" : note + ";") + "图片失败" + r.failed() + "张";
            }
            // 图片补入库不是完整同步:没有参数清洗结果时不能冒充 SUCCESS/last_sync_at。
            // 旧 job 9 正是这样把 7 台「有版本无参数」的车标成了已同步。
            boolean coreReady = hasCleanData(row.getId());
            if (!coreReady) {
                note = (note == null ? "" : note + ";") + "仅图片已补,参数/清洗未同步";
            }
            if (r.failed() > 0 || !coreReady) {
                row.setSyncStatus("PARTIAL");
                row.setSyncNote(note);
                row.setLastSyncError(null);
            } else {
                row.setSyncStatus("SUCCESS");
                row.setLastSyncError(null);   // 成功同步清除历史失败原因(R2)
                row.setLastSyncAt(LocalDateTime.now());
                row.setSyncNote(note);
            }
            row.setSourceState("OK");
            row.setLastCheckedAt(LocalDateTime.now());
            row.setUpdatedAt(LocalDateTime.now());
            modelMapper.updateById(row);
            return new IncrementalOutcome(note, new SyncOutcome(row, null));
        }
        // 10-15 真机发现(10 车型 goodsInfo 返回空条目):无条目 ≠ 无图——用库存行的存量 URL 直接下载兜底。
        // 10-21:这是「官网无数据」,不得算成功同步(不写 SUCCESS / last_sync_at),只标 EMPTY + note。
        LegacyImageResult legacy = persistLegacyIntroImages(row);
        if (!legacy.ids().isEmpty()) row.setIntroImages(toJson(legacy.ids()));
        row.setSyncFingerprint(computeRowSnapshotFingerprint(row));
        if (legacy.failed() > 0) {
            note = "官网无数据,存量图部分失败(保留旧图集)";
        } else {
            note = legacy.ids().isEmpty() ? "官网无数据,且无存量图可兜底"
                    : "官网无数据,已按存量URL兜底入库" + legacy.ids().size() + "张";
        }
        row.setSourceState("EMPTY");
        row.setSyncNote(note);
        row.setSyncStatus("FAILED");
        row.setLastSyncError(note);
        row.setLastCheckedAt(LocalDateTime.now());
        row.setUpdatedAt(LocalDateTime.now());
        modelMapper.updateById(row);
        return new IncrementalOutcome(note, new SyncOutcome(row, null));
    }

    /**
     * 存量 URL 兜底(轻回填/增量「无条目」分支):解析库存行 intro_images 中的 http URL 项
     * (旧管线格式)逐张下载转存,复用与 introduce 图片同口径的入库管线(哈希去重/图床/打标)。
     * 单张失败仅告警;全部失败时回退,不打乱存量 intro_images(空返回由调用方语义收敛)。
     */
    private LegacyImageResult persistLegacyIntroImages(CarModelEntity row) {
        List<String> urls = legacyIntroUrls(row);
        List<Long> ids = new ArrayList<>();
        for (String url : urls) persistIntroImageUrl(row, url, ids);
        int failed = urls.size() - ids.size();
        // 部分失败不得用短列表替换旧图集:全部成功才返回 id,否则保留旧数据
        return failed > 0 ? new LegacyImageResult(List.of(), failed) : new LegacyImageResult(ids, 0);
    }

    /** 存量 URL 兜底结果:成功 id 列表 / 失败张数(失败>0 时 ids 为空、保留旧图集)。 */
    private record LegacyImageResult(List<Long> ids, int failed) {}

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
     * 无条目时库存行快照指纹(沿用 10-15 旧口径,**含 introduce**):官网恢复数据后 goodsInfo 指纹
     * 必不匹配快照(或与旧口径 {@link #computeLegacyFingerprint} 一致、由图片/参数指纹接管)
     * → 增量自动触发收敛;未恢复则快照一致 → 跳过。features/carRights/intro_images 库内为
     * JSON 字符串,统一 readTree 还原后再入 canonical(与旧 item 口径序列化形状一致)。
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
    protected ImagePersistResult persistImages(CarModelEntity model, GoodsInfoDto.Item item) {
        if (item == null) return ImagePersistResult.empty();
        boolean changed = false;
        Long coverId = null;
        List<String> errors = new ArrayList<>();
        // failed 记录失败张数(而非错误分类条数),note 里的「图片失败N张」必须准确。
        int failureCount = 0;
        // 封面:官网主体图(透明 PNG),命名 <name>-cover.<ext>;标签额外带「车型主图」便于图库筛选。
        // 下载/入库失败保留旧封面(不冲写)。
        if (item.getImg() != null && !item.getImg().isBlank()) {
            coverId = persistImageToLibrary(model, item.getImg(), model.getName() + "-cover",
                    List.of("车型-" + model.getName(), "车型主图"));
            if (coverId != null && !coverId.equals(model.getCoverImageId())) {
                model.setCoverImageId(coverId);
                changed = true;
            } else if (coverId == null) {
                errors.add("封面图失败");
                failureCount++;
            }
        }
        // 详情图集:introduce 逐张入库;全有或全无——任一张失败则不替换旧图集(不得缩短)。
        List<Long> introIds = null;
        if (item.getIntroduce() != null && !item.getIntroduce().isEmpty()) {
            List<Long> ids = new ArrayList<>();
            int introFailed = 0;
            for (String url : item.getIntroduce()) {
                Long id = persistImageToLibrary(model, url, model.getName() + "-" + (ids.size() + introFailed + 1),
                        List.of("车型-" + model.getName()));
                if (id != null) ids.add(id); else introFailed++;
            }
            if (introFailed > 0) {
                errors.add("详情图失败" + introFailed + "张(保留旧图集)");
                failureCount += introFailed;
            } else if (!ids.isEmpty()) {
                String json = toJson(ids);
                if (!json.equals(model.getIntroImages())) {   // 幂等重跑(哈希命中原 id)不产生无效写
                    model.setIntroImages(json);
                    changed = true;
                }
                introIds = ids;
            }
        }
        if (changed) modelMapper.updateById(model);
        return new ImagePersistResult(coverId, introIds, failureCount, errors);
    }

    /** 图片入库结果:封面 id / 新图集 id(未替换为 null) / 失败数 / 分类短文案。 */
    public record ImagePersistResult(Long coverId, List<Long> introIds, int failed, List<String> errors) {
        static ImagePersistResult empty() {
            return new ImagePersistResult(null, null, 0, List.of());
        }
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
            CarImageDownloader.DownloadedImage img = imageDownloader.download(url);
            byte[] bytes = img.bytes();
            String ext = img.ext();
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
            // 完整 URL/堆栈只进日志;任务明细由调用方用分类短文案
            log.warn("比亚迪车型图转存失败(跳过): model={} url={} err={}", model.getName(), shorten(url), ex.getMessage());
            return null;
        }
    }

    private static String shorten(String s) {
        if (s == null) return "null";
        return s.length() > 100 ? s.substring(0, 100) + "…" : s;
    }

    /** 主表 + 版本 + 参数的一次事务写入结果(版本/参数为空时保留旧数据)。 */
    public record CorePersistResult(CarModelEntity model, boolean versionsOk, boolean paramsOk) {}

    /** 版本 + 参数的一次事务写入结果。 */
    public record ParamsPersistResult(boolean versionsOk, boolean paramsOk) {}

    /**
     * 单事务写入主表 + 版本 + 参数(10-21):任一步失败整体回滚,不留下「新主表 + 旧参数」半套数据。
     * 网络采集/图片/embedding 必须在此之前或之后(事务外)完成。
     */
    @Transactional
    public CorePersistResult persistCore(String goodsId, GoodsInfoDto.Item item,
                                         GoodsParamsDto.ParamsData params, List<GoodsAttrListDto.Version> attrs) {
        CarModelEntity model = persistModel(goodsId, item);
        boolean versionsOk = persistVersions(model.getId(), attrs);
        boolean paramsOk = persistParams(model.getId(), params);
        return new CorePersistResult(model, versionsOk, paramsOk);
    }

    /** 单事务替换版本 + 参数(定向参数同步用)。 */
    @Transactional
    public ParamsPersistResult persistParamsCore(Long modelId, GoodsParamsDto.ParamsData params,
                                                 List<GoodsAttrListDto.Version> attrs) {
        boolean versionsOk = persistVersions(modelId, attrs);
        boolean paramsOk = persistParams(modelId, params);
        return new ParamsPersistResult(versionsOk, paramsOk);
    }

    /**
     * 入库车型主表(幂等 upsert,10-21 安全合并)。
     *
     * <p>只合并非空字段:字段缺失时保留旧值,绝不用 null 覆盖 name/salesNetwork/vehicleId/price/
     * features/carRights/introImages。intro_images 只由 {@link #persistImages} 写图库 asset id,
     * 本方法不再写 raw URL。SUCCESS/last_sync_at 也从本方法移除(由 syncOne 末尾统一落终态)。
     */
    @Transactional
    protected CarModelEntity persistModel(String goodsId, GoodsInfoDto.Item item) {
        CarModelEntity m = modelMapper.selectOne(new QueryWrapper<CarModelEntity>().eq("goods_id", goodsId));
        boolean isNew = (m == null);
        if (isNew) m = new CarModelEntity();
        m.setGoodsId(goodsId);
        if (item != null) {
            if (notBlank(item.getName())) m.setName(item.getName());
            if (notBlank(item.getSalesNetworkName())) m.setSalesNetwork(item.getSalesNetworkName());
            if (notBlank(item.getVehicleId())) m.setVehicleId(item.getVehicleId());
            if (notBlank(item.getPrice())) m.setPriceRange(item.getPrice());
            if (item.getFeatures() != null && !item.getFeatures().isEmpty()) m.setFeatures(toJson(item.getFeatures()));
            if (item.getDetailPage() != null && notBlank(item.getDetailPage().get_path())) {
                m.setDetailPage(item.getDetailPage().get_path());
            }
            if (item.getCarRights() != null) m.setCarRights(toJson(item.getCarRights()));
            // 指纹统一在 syncOne 末尾由 updateFingerprints 按「图片/向量是否真正收敛」条件写入。
            // 不在此提前写 sync_fingerprint:否则向量失败后基础指纹已推进,下一次增量会跳过、旧块永不补齐。
        }
        m.setSourceUrl("https://www.byd.com/cn/parameter-comparison?goodsId=" + goodsId);
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

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * 入库版本(先清后插,幂等)。S6b:同名版本去重(官网接口按模块重复推送版本列表,实测 ×2~×10),只保留首个。
     *
     * @return false = 新数据为空/无有效版本,已保留旧数据(不 delete)
     */
    @Transactional
    protected boolean persistVersions(Long modelId, List<GoodsAttrListDto.Version> attrs) {
        if (attrs == null || attrs.isEmpty()) return false;   // 空数据不得先删旧版本
        List<GoodsAttrListDto.Version> valid = attrs.stream()
                .filter(v -> v != null && notBlank(v.getName())).toList();
        if (valid.isEmpty()) return false;
        versionMapper.delete(new QueryWrapper<CarVersionEntity>().eq("model_id", modelId));
        java.util.Set<String> seen = new java.util.HashSet<>();
        int i = 0;
        for (GoodsAttrListDto.Version v : valid) {
            if (!seen.add(v.getName().trim())) continue;   // 重复版本行跳过(数据源冗余)
            CarVersionEntity e = new CarVersionEntity();
            e.setModelId(modelId);
            e.setVersionName(v.getName());
            e.setPrice(v.getPrice());
            e.setPriceRemark(v.getPriceRemark());
            e.setSortOrder(i++);
            e.setCreatedAt(LocalDateTime.now());
            e.setUpdatedAt(LocalDateTime.now());
            versionMapper.insert(e);
        }
        return true;
    }

    /**
     * 入库参数分组+明细(先清后插,幂等)。S6b:同名分组去重。
     *
     * @return false = 新数据为空/无有效分组,已保留旧数据(不 delete)
     */
    @Transactional
    protected boolean persistParams(Long modelId, GoodsParamsDto.ParamsData params) {
        if (params == null || params.getConfigs() == null || params.getConfigs().isEmpty()) return false;
        List<GoodsParamsDto.Config> valid = params.getConfigs().stream()
                .filter(c -> c != null && notBlank(c.getName())).toList();
        if (valid.isEmpty()) return false;
        groupMapper.delete(new QueryWrapper<CarParamGroupEntity>().eq("model_id", modelId));
        paramMapper.delete(new QueryWrapper<CarParamEntity>().eq("model_id", modelId));
        java.util.Set<String> seenGroups = new java.util.HashSet<>();
        int gi = 0;
        for (GoodsParamsDto.Config cfg : valid) {
            if (!seenGroups.add(cfg.getName().trim())) continue;   // 重复同名分组整组跳过
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
        return true;
    }

    private String toJson(Object o) {
        try { return json.writeValueAsString(o); }
        catch (Exception e) { return null; }
    }
}
