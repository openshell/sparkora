-- 10-04-serper-provider A-R9: 放开外部搜索 provider 顺序以容纳 SERPER。
-- 原列 VARCHAR(20) 仅够 TAVILY,SEARXNG（14 字符）；三源串 TAVILY,SERPER,SEARXNG = 21 字符会截断。
-- 列宽放宽到 VARCHAR(50)，默认值不变（SERPER 默认不启用，零回归）。
ALTER TABLE sparkora_setting ALTER COLUMN web_provider_order TYPE VARCHAR(50);
