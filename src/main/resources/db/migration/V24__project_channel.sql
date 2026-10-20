-- 10-19-channel-editor-publish:项目绑定单一发布渠道。
-- gzh = 微信公众号(现有四步向导);byd_miniapp = 比亚迪王朝小程序(三步工作台)。
-- 存量项目一律回填 gzh,行为与改造前完全一致。
ALTER TABLE sparkora_article_project
    ADD COLUMN channel VARCHAR(20) NOT NULL DEFAULT 'gzh';

COMMENT ON COLUMN sparkora_article_project.channel IS '发布渠道:gzh(公众号) / byd_miniapp(王朝小程序),默认 gzh';
