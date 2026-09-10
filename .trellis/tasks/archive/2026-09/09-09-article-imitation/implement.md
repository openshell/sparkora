# 执行计划：文章仿写功能

## 实施顺序

### 后端
1. schema.sql：`sparkora_article_project` 增 `gen_source/imitation_text/imitation_analysis`；`sparkora_article_brief` 增 `style_recommendations`；`sparkora_article_version` 增 `similarity_score/similarity_report`（幂等）
2. entity：`ArticleProjectEntity` / `ArticleBriefEntity` / `ArticleVersionEntity` 增对应字段
3. `ProjectRequest` 增 `genSource/imitationText`；`ArticleProjectController.create` 校验（IMITATION 时 imitationText 必填非空）
4. `ImitationService`（新增）：`analyze`（状态守护+原子抢占仿 BriefService；AI 分析+风格推荐落 brief）+ `similarityCheck`（5-gram + 最长重复片段，纯本地）
5. `VersionService`：IMITATION 分支 `buildImitationPrompt` + 生成成功后 similarityCheck 落库；输出图片清洗（prompt 约束 + 正则过滤）
6. 控制器新增 `POST /{id}/imitation/analyze`、`GET /{id}/imitation`（@PreAuthorize + R<T>）
7. spec 同步：`docs/s0-spec.md` §3.2/§3.3 + 新 §14

### 前端
8. `ProjectEdit.vue`：创作方式单选（主题创作/文章仿写），仿写分支表单（任务名必填/原文必填 ≤20000 字/隐藏车型关联）
9. `StepBrief.vue`：仿写模式改「原文分析」视图（分析按钮+分析卡片+风格推荐卡）
10. `StepVersions.vue`：原文摘要卡、推荐角标、相似度展示（阈值色+重复片段明细）
11. 前后端联动验证

## 验证命令

```bash
mvn -q -DskipTests compile          # 后端编译
npm run build                        # 前端构建（frontend/ 目录）
# 联调（.reasonix/skills/sparkora-dev 同款流程）
./dev.sh start && ./dev.sh logs backend -f
# 手测路径：创建仿写项目（粘贴原文）→ 分析 → 采用推荐风格 → 生成 → 查看相似度 → 预览确认无图片
# viewer 权限冒烟：analyze/generate 期望 403
```

## 风险文件与回滚点

- `VersionService.generate` 是状态机核心：只加分支不改主流程，若冲突回退该分支即可
- `schema.sql` 全幂等 `ADD COLUMN IF NOT EXISTS`，无需数据迁移；代码回退即回滚
- 前端 `constants/project.js` 零改动，避免触碰状态机映射

## task.py start 前检查

- [ ] prd.md / design.md / implement.md 就绪且用户已批准最终规划总结
- [ ] implement.jsonl / check.jsonl 已配真实条目
- [ ] 规格核对：docs/s0-spec.md §3.2/§3.3/§14 与实现一致（sparkora-spec-check）