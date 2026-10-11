/** 图库展示派生常量与纯函数（ImageLibrary / ImageCard 共用；09-27-split-monoliths 抽出，零行为变化）。 */

/** 图片来源显示名（含 09-13 新增 byd-news）。 */
export const SOURCE_LABELS = { upload: '上传', 'ai-text2img': '文生图', 'ai-img2img': '图生图', byd: '比亚迪', 'byd-news': '比亚迪新闻' }

/** 网格缩略图 URL（S10：优先 imageView2/webp 派生）。 */
export const imgUrl = (img) => img?.thumbUrl || img?.url || ''
/** 原图 URL（连续预览 / 插入正文用，绝不用 thumbUrl）。 */
export const originUrl = (img) => img?.url || ''
export const sourceLabel = (s) => SOURCE_LABELS[s] || s
export const shortTime = (t) => (t || '').slice(5, 16).replace('T', ' ')
/** 日期短串（来源追溯行用）：「2026-09-30T10:00」→「2026-09-30」。 */
export const shortDay = (t) => (t ? String(t).replace('T', ' ').slice(0, 10) : '')
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

// ==== 全屏预览详情面板（10-10-img-gallery-ui-polish） ====
/**
 * 预览信息面板行拼装：全部「非空才产出该行」，字段缺失隐藏不臆断。
 * 语义检索命中是 ImageSearchHit 子集（经 useSemanticSearch.hitToCard 归一后仅
 * id/fileName/source/sourceRef/url/thumbUrl/tags/score/sourceText，无 projectId/尺寸/创建人等），
 * 空段一律过滤；projectId === undefined（语义命中）沿用 projectLabel 约定不显示「全局」。
 *
 * @returns {{
 *   rows: {key,name,value,full?}[],  // 纯文本行：name=文件名(标题样式)；base=来源·项目·尺寸；
 *                                    // created=创建人·时间；gen=生成模型·规格；prompt=生成 Prompt(full 大块)
 *   tags: string[],                  // 标签（组件拼 pills，只展示不交互）
 *   score: number|null,              // 语义相关度（0~1，浏览态恒无）
 *   scoreText: string,               // 相关度命中原文（嵌入文本）
 *   news: object|null                // 来源追溯新闻 {title, publishDate, url}（可空）
 * }}
 */
export const viewerRowsOf = (img, projects, sourceInfo) => {
  if (!img) return { rows: [], tags: [], score: null, scoreText: '', news: null }
  const rows = []
  const add = (key, value) => { if (value) rows.push({ key, value }) }
  add('name', img.fileName)
  add('base', hpSubText(img, projects))
  add('created', [img.createdBy, shortTime(img.createdAt)].filter(Boolean).join(' · '))
  add('gen', [img.genModel, img.genSize].filter(Boolean).join(' · '))
  const prompt = String(img.promptText || '').trim()
  if (prompt) rows.push({ key: 'prompt', name: '生成 Prompt', value: prompt, full: true })
  const tags = (Array.isArray(img.tags) ? img.tags : []).filter(Boolean)
  const scoreNum = img.score == null ? null : Number(img.score)
  return {
    rows, tags,
    score: scoreNum == null || Number.isNaN(scoreNum) ? null : scoreNum,
    scoreText: img.sourceText || '',
    news: sourceInfo?.news || null
  }
}
