-- ============================================================================
-- 10-09-source-metadata-completion M:NEWS 域 metadata 补 url + authorityTier
--
-- 背景(父 design §2.4/§2.5.1,10-05 F 复核缺口):本地自建信源事实在检索链路缺两个字段——
--   url          :F-R3「跨源同 URL 去重」/ F-R2「本地优先裁决」依赖,当前 Citation.url 恒空;
--   authorityTier:F-R4「权威分档」依赖,当前恒落保守档 0.7。
-- 本迁移幂等回填存量 NEWS 向量行(新写入由 SourceDocService/NewsDocService 负责):
--   url          :refId → sparkora_news_doc.news_id → sparkora_news.url 派生;
--   authorityTier:byd-news 行固定 official;通用信源行由 news.source_id → sparkora_source.authority_tier 派生。
--
-- 保留 domain=NEWS 不改名(VectorStoreService.docId 用 UUID(domain+":"+refId) 确定性主键,
-- 改名会使存量 NEWS 向量 id 失配、须全量重嵌),仅补 metadata 键。
--
-- 幂等/可重入:每条带 (metadata->'key') IS NULL 守卫,重复执行 0 变更;id/embedding 列不动 → 零重嵌。
-- 不用 DO $$(Spring ScriptUtils 不支持 dollar-quote),全部单条 UPDATE。V1–V15 不改。
-- ============================================================================

-- 1) 补 url(仅 domain=NEWS 且键缺失,且经 refId=news_doc.id → sparkora_news.url 非空可派生)
UPDATE vector_store v
SET metadata = jsonb_set((v.metadata)::jsonb, '{url}', to_jsonb(n.url), true)::json
FROM sparkora_news_doc d
JOIN sparkora_news n ON n.id = d.news_id
WHERE (v.metadata->>'domain') = 'NEWS'
  AND (v.metadata->'url') IS NULL
  AND (v.metadata->>'refId') = d.id::text
  AND n.url IS NOT NULL
  AND n.url <> '';

-- 2) 补 BYD 官方新闻 authorityTier=official(仅 byd-news 行且键缺失)
UPDATE vector_store
SET metadata = jsonb_set((metadata)::jsonb, '{authorityTier}', '"official"'::jsonb, true)::json
WHERE (metadata->>'domain') = 'NEWS'
  AND (metadata->>'sourceType') = 'byd-news'
  AND (metadata->'authorityTier') IS NULL;

-- 3) 补通用信源 authorityTier(由 news.source_id → sparkora_source.authority_tier 派生;缺档不写,走 0.7 兜底)
UPDATE vector_store v
SET metadata = jsonb_set((v.metadata)::jsonb, '{authorityTier}', to_jsonb(s.authority_tier), true)::json
FROM sparkora_news_doc d
JOIN sparkora_news n ON n.id = d.news_id
JOIN sparkora_source s ON s.id = n.source_id
WHERE (v.metadata->>'domain') = 'NEWS'
  -- 关键:必须与 refId 关联(否则 FROM 集合与目标行交叉连接,会把别的来源的档位写到本行)
  AND (v.metadata->>'refId') = d.id::text
  AND (v.metadata->>'sourceType') IS NOT NULL
  AND (v.metadata->>'sourceType') <> 'byd-news'
  AND (v.metadata->'authorityTier') IS NULL
  AND s.authority_tier IS NOT NULL
  AND s.authority_tier <> '';
