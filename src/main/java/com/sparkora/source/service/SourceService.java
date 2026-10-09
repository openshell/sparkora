package com.sparkora.source.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.domain.dto.ChannelDTO;
import com.sparkora.domain.dto.SourceCreateDTO;
import com.sparkora.domain.dto.SourceUpdateDTO;
import com.sparkora.domain.entity.SourceChannelEntity;
import com.sparkora.domain.entity.SourceEntity;
import com.sparkora.mapper.SourceChannelMapper;
import com.sparkora.mapper.SourceMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 信源注册表读写服务(10-05-source-crawl-base;10-09-cpca-gasgoo-collection G4 扩展)。源详情带 {@code channels[]};
 * 源级/栏目变更后触发调度重注册。
 *
 * <p>采用部分更新({@link UpdateWrapper}.set)而非 {@code updateById}——避免全字段回写覆盖并发列,
 * 且可置空 window/cron(见 database-guidelines「部分更新与字段置空」)。
 */
@Slf4j
@Service
public class SourceService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SourceMapper sourceMapper;
    private final SourceChannelMapper channelMapper;
    private final SourceScheduleService scheduleService;

    public SourceService(SourceMapper sourceMapper, SourceChannelMapper channelMapper,
                         SourceScheduleService scheduleService) {
        this.sourceMapper = sourceMapper;
        this.channelMapper = channelMapper;
        this.scheduleService = scheduleService;
    }

    /** 列表(按 id 倒序;回填栏目数与下次运行时间)。 */
    public List<SourceEntity> list() {
        List<SourceEntity> sources = sourceMapper.selectList(new QueryWrapper<SourceEntity>().orderByDesc("id"));
        for (SourceEntity s : sources) {
            s.setChannelCount(channelMapper.selectCount(
                    new QueryWrapper<SourceChannelEntity>().eq("source_id", s.getId())));
            s.setNextRunAt(scheduleService.nextRunAt(s));
        }
        return sources;
    }

    /** 详情(含 channels[];回填下次运行时间)。不存在抛 IllegalArgumentException。 */
    public SourceEntity get(Long id) {
        SourceEntity s = sourceMapper.selectById(id);
        if (s == null) throw new IllegalArgumentException("信源不存在");
        s.setChannels(channelMapper.selectList(
                new QueryWrapper<SourceChannelEntity>().eq("source_id", id).orderByAsc("id")));
        s.setChannelCount((long) s.getChannels().size());
        s.setNextRunAt(scheduleService.nextRunAt(s));
        return s;
    }

    /**
     * 新建信源(G4):事务内插入 source + channels,返回详情(触发调度重注册)。
     *
     * <p>校验:type ∈ {RSS,SITE}(DTO @Pattern 已兜)、栏目 name/listUrl 必填、parseRules 合法 JSON;非法抛 400。
     */
    @Transactional
    public SourceEntity create(SourceCreateDTO dto) {
        if (dto == null) throw new IllegalArgumentException("请求体不能为空");
        if (dto.getName() == null || dto.getName().isBlank()) throw new IllegalArgumentException("信源名不能为空");
        String type = dto.getType() == null ? "" : dto.getType().trim().toUpperCase(java.util.Locale.ROOT);
        if (!"RSS".equals(type) && !"SITE".equals(type)) throw new IllegalArgumentException("类型必须是 RSS 或 SITE");

        LocalDateTime now = LocalDateTime.now();
        SourceEntity s = new SourceEntity();
        s.setName(dto.getName().trim());
        s.setType(type);
        s.setVertical(dto.getVertical());
        s.setCron(dto.getCron());
        s.setWindowStartDay(dto.getWindowStartDay());
        s.setWindowEndDay(dto.getWindowEndDay());
        s.setAuthorityTier(dto.getAuthorityTier());
        s.setNeedCrawl4ai(Boolean.TRUE.equals(dto.getNeedCrawl4ai()));
        s.setEnabled(Boolean.TRUE.equals(dto.getEnabled()));
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        sourceMapper.insert(s);

        if (dto.getChannels() != null) {
            for (ChannelDTO c : dto.getChannels()) {
                insertChannel(s.getId(), c, now);
            }
        }
        scheduleService.register(s);
        return get(s.getId());
    }

    /** 新增栏目(G4):改后触发调度重注册。 */
    @Transactional
    public SourceChannelEntity addChannel(Long sourceId, ChannelDTO dto) {
        SourceEntity s = sourceMapper.selectById(sourceId);
        if (s == null) throw new IllegalArgumentException("信源不存在");
        LocalDateTime now = LocalDateTime.now();
        SourceChannelEntity ch = insertChannel(sourceId, dto, now);
        scheduleService.register(s);
        return ch;
    }

    /** 编辑栏目(G4,部分更新):null=不改;listUrl 置空非法。改后触发调度重注册。 */
    @Transactional
    public SourceChannelEntity updateChannel(Long channelId, ChannelDTO dto) {
        SourceChannelEntity existing = channelMapper.selectById(channelId);
        if (existing == null) throw new IllegalArgumentException("栏目不存在");
        if (dto == null) dto = new ChannelDTO();
        if (dto.getListUrl() != null && dto.getListUrl().isBlank()) throw new IllegalArgumentException("列表地址不能为空");
        validateParseRules(dto.getParseRules());

        UpdateWrapper<SourceChannelEntity> w = new UpdateWrapper<SourceChannelEntity>().eq("id", channelId);
        w.set(dto.getName() != null, "name", dto.getName());
        w.set(dto.getListUrl() != null, "list_url", dto.getListUrl());
        // detail_base_url/category 允许置空(空串=清空,与既有多数字段语义一致)
        w.set(dto.getDetailBaseUrl() != null, "detail_base_url", blankToNull(dto.getDetailBaseUrl()));
        w.set(dto.getCategory() != null, "category", blankToNull(dto.getCategory()));
        w.set(dto.getParseRules() != null, "parse_rules", blankToNull(dto.getParseRules()));
        w.set(dto.getNeedCrawl4ai() != null, "need_crawl4ai", dto.getNeedCrawl4ai());
        w.set(dto.getEnabled() != null, "enabled", dto.getEnabled());
        w.set("updated_at", LocalDateTime.now());
        channelMapper.update(null, w);

        SourceEntity s = sourceMapper.selectById(existing.getSourceId());
        if (s != null) scheduleService.register(s);
        return channelMapper.selectById(channelId);
    }

    /** 删除栏目(G4,逻辑删):改后触发调度重注册。 */
    @Transactional
    public boolean deleteChannel(Long channelId) {
        SourceChannelEntity existing = channelMapper.selectById(channelId);
        if (existing == null) throw new IllegalArgumentException("栏目不存在");
        channelMapper.deleteById(channelId);
        SourceEntity s = sourceMapper.selectById(existing.getSourceId());
        if (s != null) scheduleService.register(s);
        return true;
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

    /** 插入单个栏目(校验 name/listUrl/parseRules)。 */
    private SourceChannelEntity insertChannel(Long sourceId, ChannelDTO c, LocalDateTime now) {
        if (c == null) throw new IllegalArgumentException("栏目不能为空");
        if (c.getName() == null || c.getName().isBlank()) throw new IllegalArgumentException("栏目名不能为空");
        if (c.getListUrl() == null || c.getListUrl().isBlank()) throw new IllegalArgumentException("列表地址不能为空");
        validateParseRules(c.getParseRules());
        SourceChannelEntity ch = new SourceChannelEntity();
        ch.setSourceId(sourceId);
        ch.setName(c.getName().trim());
        ch.setListUrl(c.getListUrl().trim());
        ch.setDetailBaseUrl(blankToNull(c.getDetailBaseUrl()));
        ch.setCategory(blankToNull(c.getCategory()));
        ch.setParseRules(blankToNull(c.getParseRules()));
        ch.setNeedCrawl4ai(c.getNeedCrawl4ai());
        ch.setEnabled(c.getEnabled() == null || c.getEnabled());
        ch.setCreatedAt(now);
        ch.setUpdatedAt(now);
        channelMapper.insert(ch);
        return ch;
    }

    /** parseRules 非空时须为合法 JSON 对象;非法抛 400。 */
    private static void validateParseRules(String raw) {
        if (raw == null || raw.isBlank()) return;
        try {
            JSON.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("parseRules 不是合法 JSON");
        }
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
