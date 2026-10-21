-- 10-21-knowledge-car-sync-ux A1:车型主表加封面图库资产列
-- 语义:封面 = 官网 goodsInfo.img(车辆主体图,透明 PNG)转存到图库后的 sparkora_image_asset.id。
-- NULL = 无封面,前端展示层回退 intro_images 首图;封面与详情图集(intro_images)分离,互不覆盖。
-- 不加 NOT NULL:存量行为 NULL,由「图片补入库」/全量同步自然补齐。
ALTER TABLE sparkora_car_model ADD COLUMN cover_image_id BIGINT;
COMMENT ON COLUMN sparkora_car_model.cover_image_id IS '封面图库资产 id（官网 img 主体图转存；NULL=无封面，前端回退详情图首图）';
