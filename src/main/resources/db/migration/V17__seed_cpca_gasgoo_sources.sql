-- 10-09-cpca-gasgoo-collection: 预置乘联会 / 盖世汽车两个真实信源与 4 个栏目(开箱即可验证)。
-- 幂等:`INSERT ... SELECT ... WHERE NOT EXISTS`,重复应用不新增;不改既有表结构。
-- 默认 enabled=false(避免误触发采集);SOURCE_COLLECT_ENABLED 仍默认 false → 零回归。
-- 也可改用 POST /api/sources(G4)运行时注册;本迁移仅给「开箱即得」种子。

-- ============ 乘联会(www.cpcaauto.com;直连 403,须 Crawl4AI 渲染) ============
INSERT INTO sparkora_source (name, type, vertical, cron, window_start_day, window_end_day,
                             authority_tier, need_crawl4ai, enabled, created_at, updated_at, deleted)
SELECT '乘联会', 'SITE', '汽车', '0 30 3 * * ?', 8, 11,
       'industry', TRUE, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (SELECT 1 FROM sparkora_source WHERE name = '乘联会' AND deleted = 0);

-- 车市解读(月度销量快报/深度分析/周报/预测;类型 csjd)
INSERT INTO sparkora_source_channel (source_id, name, list_url, detail_base_url, category, parse_rules,
                                     need_crawl4ai, enabled, created_at, updated_at, deleted)
SELECT s.id, '车市解读',
       'https://www.cpcaauto.com/news.php?types=csjd',
       'https://www.cpcaauto.com',
       '销量数据',
       '{"list":".list_d li.q","link":"a","title":"a","date":"span","detail":"div.read_content","tables":"","images":""}',
       NULL, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
FROM sparkora_source s
WHERE s.name = '乘联会' AND s.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sparkora_source_channel c
                  WHERE c.source_id = s.id AND c.name = '车市解读' AND c.deleted = 0);

-- 乘联分会论坛(区域市场流向分析等;类型 yjsy)
INSERT INTO sparkora_source_channel (source_id, name, list_url, detail_base_url, category, parse_rules,
                                     need_crawl4ai, enabled, created_at, updated_at, deleted)
SELECT s.id, '乘联分会论坛',
       'https://www.cpcaauto.com/news.php?types=yjsy',
       'https://www.cpcaauto.com',
       '行业资讯',
       '{"list":".list_d li.q","link":"a","title":"a","date":"span","detail":"div.read_content","tables":"","images":""}',
       NULL, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
FROM sparkora_source s
WHERE s.name = '乘联会' AND s.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sparkora_source_channel c
                  WHERE c.source_id = s.id AND c.name = '乘联分会论坛' AND c.deleted = 0);

-- ============ 盖世汽车(auto.gasgoo.com;直连 200,HTTP 通道) ============
INSERT INTO sparkora_source (name, type, vertical, cron, window_start_day, window_end_day,
                             authority_tier, need_crawl4ai, enabled, created_at, updated_at, deleted)
SELECT '盖世汽车', 'SITE', '汽车', '0 30 3 * * ?', NULL, NULL,
       'media', FALSE, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (SELECT 1 FROM sparkora_source WHERE name = '盖世汽车' AND deleted = 0);

-- 销量资讯(文章列表页;正文容器 #ArticleContent;正文图带 deny 过滤)
INSERT INTO sparkora_source_channel (source_id, name, list_url, detail_base_url, category, parse_rules,
                                     need_crawl4ai, enabled, created_at, updated_at, deleted)
SELECT s.id, '销量资讯',
       'https://auto.gasgoo.com/auto-news/C-110',
       'https://auto.gasgoo.com',
       '官方新闻',
       '{"list":"div.contentList dl","link":"a","title":"h2 a","date":"span.time","detail":"#ArticleContent","images":"#ArticleContent img","imageDeny":"160_110"}',
       FALSE, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
FROM sparkora_source s
WHERE s.name = '盖世汽车' AND s.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sparkora_source_channel c
                  WHERE c.source_id = s.id AND c.name = '销量资讯' AND c.deleted = 0);

-- 销量排行(/qcxl 首页为排行文章列表;排行正文为 div.data ul li 行结构,非 table → listRows/rowCells 抽取)
INSERT INTO sparkora_source_channel (source_id, name, list_url, detail_base_url, category, parse_rules,
                                     need_crawl4ai, enabled, created_at, updated_at, deleted)
SELECT s.id, '销量排行',
       'https://auto.gasgoo.com/qcxl',
       'https://auto.gasgoo.com',
       '销量数据',
       '{"list":"div.frontlist ul.newslist li","link":"a","title":"a","detail":"div.data","listRows":"div.data ul li","rowCells":"span","bydOnly":false}',
       FALSE, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
FROM sparkora_source s
WHERE s.name = '盖世汽车' AND s.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sparkora_source_channel c
                  WHERE c.source_id = s.id AND c.name = '销量排行' AND c.deleted = 0);
