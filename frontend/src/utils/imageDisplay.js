/** 图库展示派生常量与纯函数（ImageLibrary / ImageCard 共用；09-27-split-monoliths 抽出，零行为变化）。 */

/** 图片来源显示名（含 09-13 新增 byd-news）。 */
export const SOURCE_LABELS = { upload: '上传', 'ai-text2img': '文生图', 'ai-img2img': '图生图', byd: '比亚迪', 'byd-news': '比亚迪新闻' }

/** 网格缩略图 URL（S10：优先 imageView2/webp 派生）。 */
export const imgUrl = (img) => img?.thumbUrl || img?.url || ''
/** 原图 URL（连续预览 / 插入正文用，绝不用 thumbUrl）。 */
export const originUrl = (img) => img?.url || ''
export const sourceLabel = (s) => SOURCE_LABELS[s] || s
export const shortTime = (t) => (t || '').slice(5, 16).replace('T', ' ')
export const isAiImage = (img) => img.source === 'ai-text2img' || img.source === 'ai-img2img'
export const projectLabel = (img, projects) => {
  if (img.projectId === undefined) return ''   // 语义检索命中无 projectId 字段（非持久化投影），不臆断为「全局」
  if (img.projectId == null) return '全局'
  const p = (projects || []).find(x => x.id === img.projectId)
  return p ? `#${p.id}` : `#${img.projectId}`
}
/** hover 副标题：过滤空段，避免语义模式下项目段缺失留下多余分隔符。 */
export const hpSubText = (img, projects) => {
  const dims = img.width && img.height ? `${img.width}×${img.height}` : ''
  return [sourceLabel(img.source), projectLabel(img, projects), dims].filter(Boolean).join(' · ')
}
