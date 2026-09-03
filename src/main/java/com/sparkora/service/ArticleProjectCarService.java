package com.sparkora.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sparkora.domain.entity.ArticleProjectCarEntity;
import com.sparkora.mapper.ArticleProjectCarMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 创作项目-车型关联服务(S6 多车型)。
 * 一篇文章可关联多个车型;生成时跨车型检索知识库注入事实约束。
 * 关联顺序(sort_order)即用户选择顺序,首个为主车型。
 */
@Slf4j
@Service
public class ArticleProjectCarService {

    private final ArticleProjectCarMapper carMapper;

    public ArticleProjectCarService(ArticleProjectCarMapper carMapper) {
        this.carMapper = carMapper;
    }

    /** 取项目关联的全部车型 id(按关联顺序)。 */
    public List<Long> listModelIds(Long projectId) {
        List<ArticleProjectCarEntity> rows = carMapper.selectList(
                new QueryWrapper<ArticleProjectCarEntity>()
                        .eq("project_id", projectId)
                        .orderByAsc("sort_order"));
        List<Long> ids = new ArrayList<>();
        for (ArticleProjectCarEntity r : rows) ids.add(r.getCarModelId());
        return ids;
    }

    /** 覆盖式写入项目关联车型(先清后插,幂等)。 */
    @Transactional
    public void replace(Long projectId, List<Long> carModelIds) {
        carMapper.delete(new QueryWrapper<ArticleProjectCarEntity>().eq("project_id", projectId));
        if (carModelIds == null) return;
        int i = 0;
        for (Long modelId : carModelIds) {
            if (modelId == null) continue;
            ArticleProjectCarEntity e = new ArticleProjectCarEntity();
            e.setProjectId(projectId);
            e.setCarModelId(modelId);
            e.setSortOrder(i++);
            e.setCreatedAt(LocalDateTime.now());
            carMapper.insert(e);
        }
    }
}
