package com.sparkora.config;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.domain.entity.CarModelEntity;
import com.sparkora.mapper.CarModelMapper;
import com.sparkora.service.ImageTagService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 存量比亚迪车型图追溯补标（09-13 image-tags）。
 *
 * 背景：车型介绍图早于标签能力入库（source=byd），无「车型-<车型名>」分类。
 * 本启动任务遍历 car_model.intro_images（图库 asset id 列表 JSON），逐车型 mergeTags，
 * 幂等可重跑（merge 只插差集），异常仅告警不阻断启动。
 * 说明：只追溯车型图——新闻封面图重下载有网络成本，留给「重新同步」可控触发，不在此追溯。
 */
@Slf4j
@Component
public class ImageTagBackfillRunner implements ApplicationRunner {

    private final CarModelMapper modelMapper;
    private final ImageTagService tagService;
    private final ObjectMapper json;

    public ImageTagBackfillRunner(CarModelMapper modelMapper, ImageTagService tagService, ObjectMapper json) {
        this.modelMapper = modelMapper;
        this.tagService = tagService;
        this.json = json;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<CarModelEntity> models = modelMapper.selectList(new QueryWrapper<CarModelEntity>().orderByAsc("id"));
            int tagged = 0;
            for (CarModelEntity m : models) {
                if (m.getIntroImages() == null || m.getIntroImages().isBlank()) continue;
                List<Long> ids = parseAssetIds(m.getIntroImages());
                if (ids.isEmpty()) continue;
                try {
                    tagService.mergeTags(ids, List.of("车型-" + m.getName()), "system");
                    tagged++;
                } catch (Exception e) {
                    log.warn("车型图追溯补标失败(跳过) model={} name={}: {}", m.getId(), m.getName(), e.getMessage());
                }
            }
            if (tagged > 0) log.info("图库标签追溯完成:{} 个车型的介绍图已补「车型-<名>」标签", tagged);
        } catch (Exception e) {
            // 启动任务失败不阻断应用启动(标签能力可后续重跑)
            log.warn("图库标签追溯任务异常(不阻断启动): {}", e.getMessage());
        }
    }

    /**
     * 解析 car_model.intro_images 为 asset id 列表。
     * 兼容存量：旧格式可能是 URL 字符串数组（非数字），解析不出数字的项跳过。
     */
    private List<Long> parseAssetIds(String raw) {
        List<Long> ids = new ArrayList<>();
        try {
            JsonNode arr = json.readTree(raw);
            if (!arr.isArray()) return ids;
            for (JsonNode node : arr) {
                String s = node.asText();
                if (s == null || s.isBlank()) continue;
                try {
                    ids.add(Long.valueOf(s.trim()));
                } catch (NumberFormatException ignore) {
                    // 存量 URL 字符串：非 asset id，跳过（不做下载追溯，范围外）
                }
            }
        } catch (Exception e) {
            log.debug("intro_images 解析失败(跳过) raw={}: {}", raw, e.getMessage());
        }
        return ids;
    }
}