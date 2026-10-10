-- 10-10-gasgoo-sales-channel: 将盖世 C-110 销量栏目正名为「车企销量」并归入「销量数据」分类。
-- 背景:盖世 C-110 即「汽车销量」类目(/auto-news/C-110 ≡ /sales/C-110),列表含各车企单独发布的销量稿
--       (长城皮卡/通用/北汽/长安/五菱/零跑/极氪/理想 等)。V17 预置时名为「销量资讯」、分类「官方新闻」,
--       与该栏目用途/语义不符(应归销量数据、名称为车企销量)。本迁移仅对齐元数据,不改 list_url/选择器/表结构。
-- 幂等:重复应用更新 0 行(V16 教训:UPDATE ... FROM 必须显式关联目标行,此处以 c.source_id = s.id 关联)。
-- 禁改已应用的 V17,故以新迁移修正。
UPDATE sparkora_source_channel c
SET name = '车企销量',
    category = '销量数据',
    updated_at = CURRENT_TIMESTAMP
FROM sparkora_source s
WHERE c.source_id = s.id
  AND s.name = '盖世汽车' AND s.deleted = 0
  AND c.list_url = 'https://auto.gasgoo.com/auto-news/C-110'
  AND c.deleted = 0
  AND (c.name <> '车企销量' OR c.category <> '销量数据');
