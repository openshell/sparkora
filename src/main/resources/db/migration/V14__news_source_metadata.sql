-- ============================================================================
-- 10-05-source-domain-retrieval E:NEWS 域内来源细化 metadata 回填
--
-- 保留 domain=NEWS 不改名(VectorStoreService.docId 用 UUID(domain+":"+refId) 确定性主键,
-- 改名会使全部存量 NEWS 向量 id 失配、须全量重嵌),仅对存量 domain=NEWS 行补 metadata:
--   sourceType=byd-news  (存量 NEWS 均为 BYD 官方新闻;source_id IS NULL)
--   category=官方新闻
--   publishDate          (由 sparkora_news_doc → sparkora_news.publish_date 派生,可解析才写)
--
-- 幂等/可重入:仅当键缺失时写(?? 保留已有值);id 列不动、向量不动 → 零重嵌。
-- 不用 DO $$(Spring ScriptUtils 不支持 dollar-quote),全部单条 UPDATE。
-- ============================================================================

-- 1) 补 sourceType(仅 domain=NEWS 且键缺失)
UPDATE vector_store
SET metadata = jsonb_set((metadata)::jsonb, '{sourceType}', '"byd-news"'::jsonb, true)::json
WHERE (metadata->>'domain') = 'NEWS'
  AND (metadata->'sourceType') IS NULL;

-- 2) 补 category(仅 domain=NEWS 且键缺失)
UPDATE vector_store
SET metadata = jsonb_set((metadata)::jsonb, '{category}', '"官方新闻"'::jsonb, true)::json
WHERE (metadata->>'domain') = 'NEWS'
  AND (metadata->'category') IS NULL;

-- 3) 补 publishDate(仅 domain=NEWS 且键缺失、且经由 refId=news_doc.id → sparkora_news.publish_date 可解析)
UPDATE vector_store v
SET metadata = jsonb_set((v.metadata)::jsonb, '{publishDate}',
                          to_jsonb(to_char(n.publish_date, 'YYYY-MM-DD')), true)::json
FROM sparkora_news_doc d
JOIN sparkora_news n ON n.id = d.news_id
WHERE (v.metadata->>'domain') = 'NEWS'
  AND (v.metadata->'publishDate') IS NULL
  AND (v.metadata->>'refId') = d.id::text
  AND n.publish_date IS NOT NULL;
