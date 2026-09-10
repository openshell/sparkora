# 执行计划：版本页显示与状态推进修复

## 顺序

1. 后端 `DeepWriterService`：注入 ArticleProjectMapper + write 增 styleName 参数 + 补 title/version_label/style_tag/word_count
2. 后端 `DeepController.generate`：body 取 styleName、成功后推 READY→VERSIONS_READY + 首版设 current
3. 后端 `schema.sql`：幂等回填段（style_tag/word_count/title/version_label）
4. spec 同步：`docs/s0-spec.md` §13 接口表（styleName 可选 + 状态推进语义）+ §4 状态机注记
5. 前端 `api/index.js`：generateDeep 增 styleName 参数
6. 前端 `StepVersions.vue`：深度分支传 style.name；显示兜底（label/styleTag/wordCount）；next-row 增「进入预览 →」
7. 验证：`mvn -q -DskipTests compile` + `npm run build` + 重启后端实测项目 34（存量回填生效）与新建项目深度生成全链

## 验证命令

```bash
mvn -q -DskipTests compile
npm run build        # frontend/
./dev.sh restart backend
# DB 验证回填: SELECT id,version_label,style_tag,word_count FROM sparkora_article_version WHERE id IN (16,21,22,23,24);
# 实测:项目 34 状态应仍为 READY(手动 setCurrent 已设 current),版本页显示兜底正常 + 「进入预览」按钮可见
# 新项目深度生成 → 状态 VERSIONS_READY、current_version_id 指向新版本、版本字段齐全
```

## 风险与回滚

- `DeepWriterService.write` 签名变更：唯一调用方 DeepController，同 commit 内更新，无其他引用。
- schema 回填段幂等，回退代码不影响已回填数据。
- 前端改动不触 store/constants。