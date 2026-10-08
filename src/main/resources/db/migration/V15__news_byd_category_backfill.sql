-- V15__news_byd_category_backfill.sql
-- 10-05-source-center-ui 修复:BYD 新闻此前未写 sparkora_news.category(仅写 vector_store.metadata),
-- 导致 /api/source-contents 按 category='官方新闻' 筛选时漏掉 BYD 行。
-- 本迁移把存量 BYD 行(source='byd-news' 或 source_id IS NULL)补 category,幂等可重入。
-- 不改 id,不改向量;仅回填列值,故不触发任何重嵌。

UPDATE sparkora_news
SET category = '官方新闻'
WHERE (source = 'byd-news' OR source_id IS NULL)
  AND category IS NULL;
