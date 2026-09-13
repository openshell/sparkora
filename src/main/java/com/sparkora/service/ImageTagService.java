package com.sparkora.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sparkora.domain.entity.ImageAssetEntity;
import com.sparkora.domain.entity.ImageTagEntity;
import com.sparkora.mapper.ImageAssetMapper;
import com.sparkora.mapper.ImageTagMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 图片标签服务（09-13 image-tags）。
 *
 * 标签域独立于 ImageService 的写路径：sparkora_image_tag 存「图片↔标签」关联行，
 * 按名称使用（不建标签字典表）。核心语义：
 *  - UNIQUE(image_id, tag_name) 数据库级防重，应用层捕 DuplicateKeyException 静默吞（幂等）；
 *  - 无逻辑删除列：关系行生命周期 = 图片生命周期，物理删（图库删图联动清理）。
 * 读路径（列表/引用集回填）由 ImageService 调用本服务 fillTags。
 */
@Slf4j
@Service
public class ImageTagService {

    /** 标签名长度上限（schema 列宽 VARCHAR(50)）。 */
    private static final int TAG_MAX_LEN = 50;

    private final ImageTagMapper tagMapper;
    private final ImageAssetMapper imageMapper;

    public ImageTagService(ImageTagMapper tagMapper, ImageAssetMapper imageMapper) {
        this.tagMapper = tagMapper;
        this.imageMapper = imageMapper;
    }

    // ==================== 规范化 ====================

    /**
     * 标签规范化：trim、去空、去重（保序 LinkedHashSet），单项长度 1~50 校验。
     * 入参 null/空白项直接丢弃；超长抛 IllegalArgumentException（控制器映射 400）。
     */
    public List<String> normalize(List<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String s : raw) {
            if (s == null) continue;
            String t = s.trim();
            if (t.isEmpty()) continue;
            if (t.length() > TAG_MAX_LEN) throw new IllegalArgumentException("标签长度须为1~50字符");
            out.add(t);
        }
        return List.copyOf(new LinkedHashSet<>(out));   // 保序去重
    }

    // ==================== 写路径 ====================

    /**
     * 批量写标签（新图入库场景）：逐行 insert，撞 UNIQUE 捕 DuplicateKeyException 静默吞（幂等）。
     * tags 已由调用方 normalize（或直接传规范值——内部同样 normalize 兜底）。
     */
    public void saveTags(Long imageId, List<String> tags, String operator) {
        if (imageId == null) return;
        List<String> norm = normalize(tags);
        if (norm.isEmpty()) return;
        for (String tag : norm) {
            try {
                ImageTagEntity e = new ImageTagEntity();
                e.setImageId(imageId);
                e.setTagName(tag);
                e.setCreatedBy(operator);
                e.setCreatedAt(java.time.LocalDateTime.now());
                tagMapper.insert(e);
            } catch (DuplicateKeyException ex) {
                // 同图同标签已存在：幂等语义,静默吞
                log.debug("标签已存在(跳过) image={} tag={}", imageId, tag);
            }
        }
    }

    /**
     * 合并补标（dedupeHit 复用 / BYD 存量追溯场景）：已有标签 ∪ 传入，只插差集。
     * 用户预选必须生效——去重复用已有图时把本次预选但缺失的标签补写到已有图上。
     * merge 只插差集 → 重复执行零副作用（幂等，启动追溯任务可安全重跑）。
     */
    public void mergeTags(Long imageId, List<String> tags, String operator) {
        if (imageId == null) return;
        List<String> norm = normalize(tags);
        if (norm.isEmpty()) return;
        // 防写孤儿标签行：图已删/不存在时跳过（存量 intro_images 可能引用已删图 id）
        if (imageMapper.selectById(imageId) == null) {
            log.debug("合并标签跳过(图片不存在) image={}", imageId);
            return;
        }
        java.util.Set<String> existing = new java.util.HashSet<>(tagNamesOf(imageId));
        List<String> missing = norm.stream().filter(t -> !existing.contains(t)).toList();
        if (missing.isEmpty()) return;
        saveTags(imageId, missing, operator);
    }

    /** 复制源图标签到新图（regenerate 继承场景：同主题成组）。 */
    public void copyTags(Long fromId, Long toId, String operator) {
        if (fromId == null || toId == null || fromId.equals(toId)) return;
        List<String> from = tagNamesOf(fromId);
        if (from.isEmpty()) return;
        saveTags(toId, from, operator);
    }

    /** 批量合并补标（存量追溯场景）：对多张图逐张 merge 同一组标签（幂等，只插差集）。 */
    public void mergeTags(List<Long> imageIds, List<String> tags, String operator) {
        if (imageIds == null || imageIds.isEmpty()) return;
        for (Long imageId : imageIds) {
            if (imageId != null) mergeTags(imageId, tags, operator);
        }
    }

    /**
     * 全量覆盖（单图编辑场景）：delete 后 insert，事务内。
     * tags 为空列表 = 清空该图全部标签（合法语义）。
     * 图片不存在抛 IllegalArgumentException（控制器映射 400），防止写孤儿标签行。
     */
    @Transactional
    public void replaceTags(Long imageId, List<String> tags, String operator) {
        if (imageId == null || imageMapper.selectById(imageId) == null)
            throw new IllegalArgumentException("图片不存在");
        List<String> norm = normalize(tags);
        tagMapper.delete(new QueryWrapper<ImageTagEntity>().eq("image_id", imageId));
        if (!norm.isEmpty()) saveTags(imageId, norm, operator);
    }

    /**
     * 批量打标/移除（批量管理场景）：
     * action=add  → 逐图 mergeTags（幂等，已有不重插）；
     * action=remove → 逐图按 tag 名删（不存在也视为成功）。
     */
    public void batchApply(List<Long> ids, List<String> tags, String action, String operator) {
        if (ids == null || ids.isEmpty()) throw new IllegalArgumentException("请先选择图片");
        List<String> norm = normalize(tags);
        if (norm.isEmpty()) throw new IllegalArgumentException("请选择或输入标签");
        boolean add;
        if ("add".equals(action)) add = true;
        else if ("remove".equals(action)) add = false;
        else throw new IllegalArgumentException("action 仅支持 add/remove");
        for (Long id : ids) {
            if (id == null) continue;
            if (add) {
                mergeTags(id, norm, operator);
            } else {
                for (String tag : norm) {
                    tagMapper.delete(new QueryWrapper<ImageTagEntity>()
                            .eq("image_id", id).eq("tag_name", tag));
                }
            }
        }
    }

    // ==================== 读路径 ====================

    /** 按图取标签名列表（无则空）。 */
    public List<String> tagNamesOf(Long imageId) {
        if (imageId == null) return List.of();
        return tagMapper.selectList(new QueryWrapper<ImageTagEntity>().eq("image_id", imageId))
                .stream().map(ImageTagEntity::getTagName).sorted().toList();
    }

    /**
     * 全库标签清单（预选控件与筛选联想同源复用）：GROUP BY tag_name 出 {name, count}，
     * count 降序（「常用优先」）。
     */
    public List<Map<String, Object>> listAll() {
        List<Map<String, Object>> rows = tagMapper.selectMaps(new QueryWrapper<ImageTagEntity>()
                .select("tag_name", "COUNT(*) AS cnt")
                .groupBy("tag_name")
                .orderByDesc("cnt"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", row.get("tag_name"));
            m.put("count", row.get("cnt"));
            out.add(m);
        }
        return out;
    }

    /** 按标签名查图片 id 列表（图库 tag 筛选两段查询的第一段；空返回空列表）。 */
    public List<Long> imageIdsByTag(String name) {
        if (name == null || name.isBlank()) return List.of();
        return tagMapper.selectList(new QueryWrapper<ImageTagEntity>()
                        .select("DISTINCT image_id").eq("tag_name", name.trim()))
                .stream().map(ImageTagEntity::getImageId).toList();
    }

    /**
     * 页内图片批量回填标签（避免 N+1）：收集 id 批查 WHERE image_id IN (...) 按图分组，
     * 每图标签按名称排序写入非持久化字段 tags。空列表直接 return。
     */
    public void fillTags(List<ImageAssetEntity> images) {
        if (images == null || images.isEmpty()) return;
        List<Long> ids = images.stream().map(ImageAssetEntity::getId).filter(java.util.Objects::nonNull).toList();
        if (ids.isEmpty()) return;
        Map<Long, List<String>> byImage = tagMapper.selectList(
                        new QueryWrapper<ImageTagEntity>().in("image_id", ids))
                .stream()
                .collect(Collectors.groupingBy(ImageTagEntity::getImageId,
                        Collectors.mapping(ImageTagEntity::getTagName, Collectors.toList())));
        for (ImageAssetEntity img : images) {
            List<String> tags = byImage.getOrDefault(img.getId(), List.of());
            img.setTags(tags.stream().sorted(Comparator.naturalOrder()).toList());
        }
    }

    // ==================== 清理 ====================

    /** 删图联动清理：物理删该图全部标签行（图库表无逻辑删除，tag 行生命周期=图片生命周期）。 */
    public void deleteByImageId(Long imageId) {
        if (imageId == null) return;
        tagMapper.delete(new QueryWrapper<ImageTagEntity>().eq("image_id", imageId));
    }
}