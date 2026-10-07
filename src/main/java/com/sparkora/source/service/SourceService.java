package com.sparkora.source.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.sparkora.domain.dto.SourceUpdateDTO;
import com.sparkora.domain.entity.SourceChannelEntity;
import com.sparkora.domain.entity.SourceEntity;
import com.sparkora.mapper.SourceChannelMapper;
import com.sparkora.mapper.SourceMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 信源注册表读写服务(10-05-source-crawl-base)。源详情带 {@code channels[]};源级变更后触发调度重注册。
 *
 * <p>采用部分更新({@link UpdateWrapper}.set)而非 {@code updateById}——避免全字段回写覆盖并发列,
 * 且可置空 window/cron(见 database-guidelines「部分更新与字段置空」)。
 */
@Slf4j
@Service
public class SourceService {

    private final SourceMapper sourceMapper;
    private final SourceChannelMapper channelMapper;
    private final SourceScheduleService scheduleService;

    public SourceService(SourceMapper sourceMapper, SourceChannelMapper channelMapper,
                         SourceScheduleService scheduleService) {
        this.sourceMapper = sourceMapper;
        this.channelMapper = channelMapper;
        this.scheduleService = scheduleService;
    }

    /** 列表(按 id 倒序;回填栏目数)。 */
    public List<SourceEntity> list() {
        List<SourceEntity> sources = sourceMapper.selectList(new QueryWrapper<SourceEntity>().orderByDesc("id"));
        for (SourceEntity s : sources) {
            s.setChannelCount(channelMapper.selectCount(
                    new QueryWrapper<SourceChannelEntity>().eq("source_id", s.getId())));
        }
        return sources;
    }

    /** 详情(含 channels[])。不存在抛 IllegalArgumentException。 */
    public SourceEntity get(Long id) {
        SourceEntity s = sourceMapper.selectById(id);
        if (s == null) throw new IllegalArgumentException("信源不存在");
        s.setChannels(channelMapper.selectList(
                new QueryWrapper<SourceChannelEntity>().eq("source_id", id).orderByAsc("id")));
        s.setChannelCount((long) s.getChannels().size());
        return s;
    }

    /** 编辑源级字段(部分更新),随后触发调度重注册。 */
    public SourceEntity update(Long id, SourceUpdateDTO dto) {
        SourceEntity existing = sourceMapper.selectById(id);
        if (existing == null) throw new IllegalArgumentException("信源不存在");
        if (dto == null) dto = new SourceUpdateDTO();

        UpdateWrapper<SourceEntity> w = new UpdateWrapper<SourceEntity>().eq("id", id);
        w.set(dto.getName() != null, "name", dto.getName());
        w.set(dto.getType() != null, "type", dto.getType());
        w.set(dto.getVertical() != null, "vertical", dto.getVertical());
        w.set(dto.getCron() != null, "cron", dto.getCron());
        // 发布窗口:两端同时给才写;传非法/清空时置空(window 语义整组,不半写)
        boolean windowGiven = dto.getWindowStartDay() != null || dto.getWindowEndDay() != null;
        if (windowGiven) {
            w.set("window_start_day", dto.getWindowStartDay());
            w.set("window_end_day", dto.getWindowEndDay());
        }
        w.set(dto.getAuthorityTier() != null, "authority_tier", dto.getAuthorityTier());
        w.set(dto.getNeedCrawl4ai() != null, "need_crawl4ai", dto.getNeedCrawl4ai());
        w.set(dto.getEnabled() != null, "enabled", dto.getEnabled());
        w.set("updated_at", LocalDateTime.now());
        sourceMapper.update(null, w);

        // 覆盖栏目级 need_crawl4ai(可选):把 null(继承源级)之外的栏目同步,便于统一降级
        if (Boolean.TRUE.equals(dto.getApplyCrawl4aiToChannels()) && dto.getNeedCrawl4ai() != null) {
            channelMapper.update(null, new UpdateWrapper<SourceChannelEntity>()
                    .eq("source_id", id)
                    .set("need_crawl4ai", dto.getNeedCrawl4ai())
                    .set("updated_at", LocalDateTime.now()));
        }

        SourceEntity updated = sourceMapper.selectById(id);
        scheduleService.register(updated);   // 增删改后热更新(先注销再按新 cron/enabled 注册)
        return get(id);
    }
}
