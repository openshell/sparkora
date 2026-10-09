-- 10-09-cpca-gasgoo-collection 收口:注销不可达的「盖世销量排行」栏目。
-- 原因:盖世排行详情页(/qcxl/article/*、/qcxl/cqph、/qcxl/xlph)由腾讯 WAF 拦截
--      (HTTP 200 / 1543B 验证码挑战页,需人工交互;Crawl4AI 亦无法渲染),真机不可采。
-- 保留:G3 结构化列表解析能力(SourceTableParser.parseListRows)仍在代码中,已单测覆盖,
--      未来接入可达排行源时可直接复用。
-- 处理:逻辑删除该栏目(V17 已应用不可改,故以 V18 追加);幂等可重入。不改表结构。
-- 影响:乘联会「车市解读」「乘联分会论坛」与盖世「销量资讯」保留,销售数据由这两条主线覆盖。

UPDATE sparkora_source_channel
SET deleted = 1, updated_at = CURRENT_TIMESTAMP
WHERE name = '销量排行'
  AND deleted = 0
  AND source_id IN (SELECT id FROM sparkora_source WHERE name = '盖世汽车');
