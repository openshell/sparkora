package com.sparkora.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sparkora.article.illustrate.AnchorExtractor;
import com.sparkora.config.AiProperties;
import com.sparkora.domain.dto.ImageSearchHit;
import com.sparkora.domain.entity.ArticleProjectEntity;
import com.sparkora.domain.entity.ArticleVersionEntity;
import com.sparkora.domain.entity.IllustrationDismissEntity;
import com.sparkora.mapper.ArticleProjectMapper;
import com.sparkora.mapper.ArticleVersionMapper;
import com.sparkora.mapper.IllustrationDismissMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 文章配图建议服务（09-15 article-auto-illustrate，子C）。
 *
 * 把图库从「人找图」升级为「系统建议、用户定夺」：正文按段落锚点逐段语义检索（子B
 * {@link ImageEmbeddingService#searchImages}），产出**配图建议**。
 *
 * <h3>硬约束（不可违背）</h3>
 * <ul>
 *   <li><b>只产出建议，绝不自动写入</b>：本服务**零副作用**——不写 {@code content_md}、
 *       不写 {@code body_image_ids}。配图进入正文的唯一路径是用户在预览页显式点「采用」。</li>
 *   <li><b>无自动插入开关</b>：不存在 {@code AUTO_ILLUSTRATE_ENABLED} 之类配置，从设计上排除无人值守自动配图。
 *       本类刻意**不注入** {@link ImageService}（避免任何 {@code modifyBodyImage}/{@code setCover} 写路径可达）。</li>
 * </ul>
 *
 * 建议候选**不落库**：建议是「当前正文 + 当前图库」的派生视图，按需重算且结果稳定；
 * 落库只会引入「建议陈旧」问题。只有用户的「忽略」决策需要持久化
 * （{@code sparkora_illustration_dismiss}，版本 + 锚点指纹唯一）。
 */
@Slf4j
@Service
public class IllustrationSuggestionService {

    /** 锚点指纹列宽上限（schema VARCHAR(200)）。 */
    static final int ANCHOR_KEY_MAX_LEN = 200;

    private final ArticleProjectMapper projectMapper;
    private final ArticleVersionMapper versionMapper;
    private final ImageEmbeddingService embeddingService;
    private final IllustrationDismissMapper dismissMapper;
    private final AiProperties aiProps;

    public IllustrationSuggestionService(ArticleProjectMapper projectMapper,
                                         ArticleVersionMapper versionMapper,
                                         ImageEmbeddingService embeddingService,
                                         IllustrationDismissMapper dismissMapper,
                                         AiProperties aiProps) {
        this.projectMapper = projectMapper;
        this.versionMapper = versionMapper;
        this.embeddingService = embeddingService;
        this.dismissMapper = dismissMapper;
        this.aiProps = aiProps;
    }

    /**
     * 按锚点分组的配图建议。
     *
     * @param anchorKey   锚点指纹（见 {@link AnchorExtractor#fingerprint}），用于前端忽略/采用定位
     * @param anchorIndex 锚点序号（0 起，仅在本次建议列表内保序）
     * @param headingPath 标题路径（开头段落为空串）
     * @param anchorText  锚点正文摘要（已剔 markdown 标记）
     * @param candidates  语义检索候选图（按相似度降序；空 = 该锚点无候选）
     */
    public record AnchorSuggestion(String anchorKey, int anchorIndex, String headingPath,
                                   String anchorText, List<ImageSearchHit> candidates) {
    }

    /**
     * 生成建议（**只读，零副作用**）：当前版本正文 → 锚点切分 → 过滤已忽略 → 逐锚点语义检索 → 组装。
     *
     * 无候选的锚点不出现在结果中（不报错）；单个锚点检索失败仅 warn 跳过，不影响其余锚点
     * （图库/模型偶发失败不应让整页建议不可用）。
     *
     * @param tags     可选的项目级主题标签预过滤（AND 语义，复用子B）
     * @param minScore 相似度门槛（null → {@code AI_IMAGE_MIN_SCORE}；须在 [0,1]，否则 IllegalArgumentException）
     */
    public List<AnchorSuggestion> suggest(Long projectId, List<String> tags, Double minScore) {
        // R6「可关闭」:关闭的是**建议的生成**（与 R3「禁止自动写入」是两件事——
        // 本开关关闭后系统仍然不会自动写入正文，只是不再产生建议）。
        // 注意:本项**不是**自动插入开关,不存在任何自动写入路径。
        if (!aiProps.isIllustrationSuggestEnabled()) {
            throw new IllegalArgumentException("配图建议功能已关闭");
        }
        double threshold = requireMinScore(minScore);
        ArticleProjectEntity p = projectMapper.selectById(projectId);
        if (p == null) throw new IllegalArgumentException("项目不存在");
        ArticleVersionEntity v = currentVersion(p);
        if (v == null) throw new IllegalArgumentException("尚未生成正文版本，无法生成配图建议");
        String contentMd = v.getContentMd();
        if (contentMd == null || contentMd.isBlank()) throw new IllegalArgumentException("正文为空，无法生成配图建议");

        int maxAnchors = Math.max(1, aiProps.getIllustrationMaxAnchors());
        int topN = Math.max(1, aiProps.getIllustrationTopN());
        List<AnchorExtractor.Anchor> anchors = AnchorExtractor.extract(contentMd, maxAnchors);
        if (anchors.isEmpty()) return List.of();

        Set<String> dismissed = dismissedKeys(v.getId());
        List<AnchorSuggestion> out = new ArrayList<>(anchors.size());
        for (AnchorExtractor.Anchor a : anchors) {
            if (dismissed.contains(a.key())) continue;   // 用户已忽略该锚点：不再推荐
            List<ImageSearchHit> hits;
            try {
                hits = embeddingService.searchImages(a.text(), topN, threshold, tags);
            } catch (Exception e) {
                // 单锚点失败不整体失败（其余锚点照常返回）；图库无候选属常态
                log.warn("配图建议检索失败(跳过该锚点) version={} anchor={}: {}", v.getId(), a.key(), e.getMessage());
                continue;
            }
            if (hits == null || hits.isEmpty()) continue;   // 门槛以下无候选 → 该锚点不出现
            out.add(new AnchorSuggestion(a.key(), out.size(), a.headingPath(), a.text(), hits));
        }
        return out;
    }

    /**
     * 忽略某锚点的建议组（ADMIN/EDITOR）。写 dismiss 表，幂等：
     * 重复忽略同一 (version, anchorKey) 不报错（先查 + 唯一约束兜底并发）。
     */
    public void dismiss(Long projectId, String anchorKey, String operator) {
        String key = normalizeAnchorKey(anchorKey);
        ArticleProjectEntity p = projectMapper.selectById(projectId);
        if (p == null) throw new IllegalArgumentException("项目不存在");
        ArticleVersionEntity v = currentVersion(p);
        if (v == null) throw new IllegalArgumentException("尚未生成正文版本，无法忽略配图建议");

        Long existing = dismissMapper.selectCount(new QueryWrapper<IllustrationDismissEntity>()
                .eq("version_id", v.getId()).eq("anchor_key", key));
        if (existing != null && existing > 0) return;   // 已忽略：幂等直接返回

        IllustrationDismissEntity e = new IllustrationDismissEntity();
        e.setProjectId(projectId);
        e.setVersionId(v.getId());
        e.setAnchorKey(key);
        e.setCreatedBy(operator == null || operator.isBlank() ? "system" : operator);
        e.setCreatedAt(LocalDateTime.now());
        try {
            dismissMapper.insert(e);
        } catch (DuplicateKeyException dup) {
            // 并发重复忽略：唯一约束兜底，幂等吞掉。
            // 本方法是一次独立写（控制器无外层事务），故冲突后无后续 SQL 会撞 aborted 事务。
            log.debug("配图建议重复忽略(忽略) version={} anchor={}", v.getId(), key);
        }
    }

    // ==================== 内部工具 ====================

    /** 该版本已忽略的锚点指纹集合。 */
    private Set<String> dismissedKeys(Long versionId) {
        List<IllustrationDismissEntity> rows = dismissMapper.selectList(
                new QueryWrapper<IllustrationDismissEntity>().eq("version_id", versionId));
        Set<String> keys = new LinkedHashSet<>();
        if (rows != null) for (IllustrationDismissEntity r : rows) {
            if (r.getAnchorKey() != null) keys.add(r.getAnchorKey());
        }
        return keys;
    }

    private ArticleVersionEntity currentVersion(ArticleProjectEntity p) {
        if (p.getCurrentVersionId() == null) return null;
        return versionMapper.selectById(p.getCurrentVersionId());
    }

    /** minScore 校验：null → 配置默认；越界（&lt;0 或 &gt;1）→ IllegalArgumentException（控制器 400）。 */
    private double requireMinScore(Double minScore) {
        if (minScore == null) return aiProps.getImageMinScore();
        if (minScore < 0 || minScore > 1) throw new IllegalArgumentException("相似度门槛须在 0~1 之间");
        return minScore;
    }

    /** anchorKey 校验/归一化：trim 后非空且不超列宽。 */
    static String normalizeAnchorKey(String anchorKey) {
        if (anchorKey == null || anchorKey.isBlank()) throw new IllegalArgumentException("锚点标识不能为空");
        String k = anchorKey.trim();
        if (k.length() > ANCHOR_KEY_MAX_LEN) throw new IllegalArgumentException("锚点标识过长");
        return k;
    }
}
