package com.sparkora.service;

import com.sparkora.domain.entity.SettingEntity;
import com.sparkora.mapper.SettingMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 系统级检索设置服务。单行表(固定 id=1)+ 内存缓存,写后刷缓存。
 * 生成链路(深度模式子代理工具装配、rag_status 判定)运行时调用 isKbEnabled()/isWebSearchEnabled()。
 * 默认:kbEnabled=false(知识库存疑,暂停引用)、webSearchEnabled=true(外部搜索优先)。
 */
@Service
public class SettingService {

    public static final long SINGLE_ROW_ID = 1L;

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

    /** 更新设置(字段 null 不改),写库后刷缓存。 */
    @Transactional
    public SettingEntity update(Boolean kbEnabled, Boolean webSearchEnabled, Long userId) {
        SettingEntity row = mapper.selectById(SINGLE_ROW_ID);
        if (row == null) {
            // 首次写入:按入参(缺省取默认)插入单行
            row = new SettingEntity();
            row.setId(SINGLE_ROW_ID);
            row.setKbEnabled(kbEnabled != null ? kbEnabled : false);
            row.setWebSearchEnabled(webSearchEnabled != null ? webSearchEnabled : true);
            row.setDeleted(0);
        } else {
            if (kbEnabled != null) row.setKbEnabled(kbEnabled);
            if (webSearchEnabled != null) row.setWebSearchEnabled(webSearchEnabled);
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