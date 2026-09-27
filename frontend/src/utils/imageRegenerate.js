/**
 * 图库/预览 共用的「重生成可用性」判定与参考图缓存判定（09-26 multi-ref）。
 * 从 ImageLibrary.vue 抽出（09-27-split-monoliths，纯结构重构，零行为变化），
 * 与 AiImageDrawer.vue 内 `canRegenerate` 同口径。
 *
 * @param img 图片（浏览态实体或语义检索命中 ImageSearchHit 投影）
 * @param imageRefCache 参考图会话缓存（utils/imageRefCache）
 */

/** 会话缓存条目是否持有可复用参考图（本地 files 或图库 ids）。 */
export const cacheHasRefs = (cached) => !!cached && ((cached.files?.length || 0) > 0 || (cached.refImageIds?.length || 0) > 0)

/**
 * 重生成可用性：会话缓存命中（整组参考图可复用）／文生图（prompt 可复现）／
 * 图库图生图（refImageId 可复用）；本地来源且缓存失效 → 置灰（后端无参考图可复现）。
 */
export const canRegenerate = (img, imageRefCache) => {
  if (!img) return false
  if (imageRefCache.has(img.id)) return true
  if (img.source === 'ai-text2img') return true
  if (img.source === 'ai-img2img') {
    // 语义检索命中（ImageSearchHit 投影）不含 refImageId，无从判断来源，故不在此误禁用——
    // 交后端 /regenerate 按 DB 实体校验（缓存缺失的粘贴/本地来源会返回中文错误）。
    if (img.score != null) return true
    // 浏览态实体：refImageId 被 Jackson non_null 省略 ⇔ 后端为 NULL（粘贴/本地直传来源）→ 置灰。
    return img.refImageId != null
  }
  return false
}
