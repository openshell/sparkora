-- 10-10-gasgoo-sales-url-and-enable: 盖世「车企销量」栏目 list_url 切换为站内规范入口 /sales/C-110。
-- 背景: /sales/C-110 与 /auto-news/C-110 逐条等价(HTTP 200、各 20 条、集合完全相同、分页相同),
--       但 /sales/C-110 是站内导航「销量」指向的规范落地页(有正常 <title>「汽车销量…」),
--       /auto-news/C-110 无标题(疑为别名)。本迁移仅改 list_url,不改选择器/表结构。
-- 幂等: 以旧值(别名 URL)为条件,重复应用更新 0 行(V16 教训:UPDATE ... FROM 必须显式关联目标行)。
UPDATE sparkora_source_channel c
SET list_url = 'https://auto.gasgoo.com/sales/C-110',
    updated_at = CURRENT_TIMESTAMP
FROM sparkora_source s
WHERE c.source_id = s.id
  AND s.name = '盖世汽车' AND s.deleted = 0
  AND c.list_url = 'https://auto.gasgoo.com/auto-news/C-110'
  AND c.deleted = 0;
