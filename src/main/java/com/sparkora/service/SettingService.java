package com.sparkora.service;

import com.sparkora.domain.entity.SettingEntity;
import com.sparkora.mapper.SettingMapper;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 系统级检索设置服务。单行表(固定 id=1)+ 内存缓存,写后刷缓存。
 * 生成链路(深度模式子代理工具装配、rag_status 判定)运行时调用 isKbEnabled()/isWebSearchEnabled()。
 * 默认:kbEnabled=false(知识库存疑,暂停引用)、webSearchEnabled=true(外部搜索优先)。
 * 10-15-car-image-backfill R3:扩车型库定时同步运行时设置(carSyncEnabled/carSyncCron),
 * CarSyncScheduler 每分钟 tick 读取;改即生效,免重启(env 的 CAR_SYNC_* 降级为遗留种子)。
 */
@Service
public class SettingService {

    public static final long SINGLE_ROW_ID = 1L;

    /** 外部搜索 provider 顺序默认值(TAVILY 优先、SearxNG 兜底,2026-09-25 反转旧决策)。 */
    public static final String DEFAULT_PROVIDER_ORDER = "TAVILY,SEARXNG";

    /** 车型库定时同步默认值(与 V22 列默认一致;首次初始化/首次写入时作为种子)。 */
    public static final boolean DEFAULT_CAR_SYNC_ENABLED = false;
    /** 车型库定时同步默认 cron(与 V22 列默认一致)。 */
    public static final String DEFAULT_CAR_SYNC_CRON = "0 0 3 * * ?";

    private final SettingMapper mapper;
    private volatile SettingEntity cache;

    public SettingService(SettingMapper mapper) {
        this.mapper = mapper;
    }

    /** 读取设置(带缓存);行不存在时插入默认行(单例初始化,避免「无行=默认」歧义)。 */
    public SettingEntity get() {
        SettingEntity c = cache;
        if (c != null) return c;
        synchronized (this) {
            if (cache == null) {
                SettingEntity row = mapper.selectById(SINGLE_ROW_ID);
                if (row == null) {
                    row = new SettingEntity();
                    row.setId(SINGLE_ROW_ID);
                    row.setKbEnabled(false);
                    row.setWebSearchEnabled(true);
                    row.setWebProviderOrder(DEFAULT_PROVIDER_ORDER);
                    // 车型库同步种子默认值(与 V22 列默认一致;INSERT 时显式写入,内存缓存与库一致)
                    row.setCarSyncEnabled(DEFAULT_CAR_SYNC_ENABLED);
                    row.setCarSyncCron(DEFAULT_CAR_SYNC_CRON);
                    row.setUpdatedAt(LocalDateTime.now());
                    row.setDeleted(0);
                    try {
                        mapper.insert(row);
                    } catch (org.springframework.dao.DuplicateKeyException e) {
                        // 并发初始化:另一请求已插入,读回即可
                        row = mapper.selectById(SINGLE_ROW_ID);
                    }
                }
                cache = row;
            }
            return cache;
        }
    }

    /** 内部知识库是否启用。 */
    public boolean isKbEnabled() {
        return Boolean.TRUE.equals(get().getKbEnabled());
    }

    /** 外部搜索是否启用。 */
    public boolean isWebSearchEnabled() {
        return Boolean.TRUE.equals(get().getWebSearchEnabled());
    }

    /** 外部搜索 provider 顺序(运行时全局策略;空则回退部署级默认,由调用方处理)。 */
    public String getWebProviderOrder() {
        return get().getWebProviderOrder();
    }

    /** 更新设置(字段 null 不改),写库后刷缓存。车型库 cron 非法抛 IllegalArgumentException(控制器映射 400)。 */
    @Transactional
    public SettingEntity update(Boolean kbEnabled, Boolean webSearchEnabled, String webProviderOrder,
                                Boolean carSyncEnabled, String carSyncCron, Long userId) {
        // 车型库 cron 合法性兜底(格式校验收敛到 CronExpression.parse;非法 → IllegalArgumentException → 400)
        if (carSyncCron != null && !carSyncCron.isBlank()) {
            try {
                CronExpression.parse(carSyncCron.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("车型库定时同步 cron 表达式非法: " + carSyncCron);
            }
        }
        SettingEntity row = mapper.selectById(SINGLE_ROW_ID);
        if (row == null) {
            // 首次写入:按入参(缺省取默认)插入单行
            row = new SettingEntity();
            row.setId(SINGLE_ROW_ID);
            row.setKbEnabled(kbEnabled != null ? kbEnabled : false);
            row.setWebSearchEnabled(webSearchEnabled != null ? webSearchEnabled : true);
            row.setWebProviderOrder(webProviderOrder != null ? webProviderOrder : DEFAULT_PROVIDER_ORDER);
            row.setCarSyncEnabled(carSyncEnabled != null ? carSyncEnabled : DEFAULT_CAR_SYNC_ENABLED);
            row.setCarSyncCron(carSyncCron != null && !carSyncCron.isBlank() ? carSyncCron.trim() : DEFAULT_CAR_SYNC_CRON);
            row.setDeleted(0);
        } else {
            if (kbEnabled != null) row.setKbEnabled(kbEnabled);
            if (webSearchEnabled != null) row.setWebSearchEnabled(webSearchEnabled);
            if (webProviderOrder != null && !webProviderOrder.isBlank()) row.setWebProviderOrder(webProviderOrder);
            if (carSyncEnabled != null) row.setCarSyncEnabled(carSyncEnabled);
            if (carSyncCron != null && !carSyncCron.isBlank()) row.setCarSyncCron(carSyncCron.trim());
        }
        row.setUpdatedBy(userId);
        row.setUpdatedAt(LocalDateTime.now());
        if (mapper.updateById(row) == 0) {
            // 兜底并发插入竞争:更新影响 0 行说明行被并发删除重建,插回
            mapper.insert(row);
        }
        cache = mapper.selectById(SINGLE_ROW_ID);
        return cache;
    }
}