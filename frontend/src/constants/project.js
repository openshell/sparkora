// 项目状态 → 展示映射的唯一事实源:文案 / 标签色 / 步骤条位置
// 后端状态机(S5 起 7 态):DRAFT → GENERATING_BRIEF → READY → GENERATING_VERSIONS → VERSIONS_READY → IMAGES_READY → PUBLISHED_DRAFT
// (2026-08-28 决策:「校验」步骤彻底取消,六步改五步;失败会回退并写 lastBriefError / lastVersionError;
//  S5 决策:发布成功进 PUBLISHED_DRAFT(公众号草稿箱已收),可重发覆盖,不再回退)
export const PROJECT_STATUS = {
  DRAFT:               { label: '草稿',       tagType: 'info',    step: 0 },
  GENERATING_BRIEF:    { label: '简报生成中', tagType: 'warning', step: 0, generating: 'brief' },
  READY:               { label: '简报就绪',   tagType: 'success', step: 1 },
  GENERATING_VERSIONS: { label: '版本生成中', tagType: 'warning', step: 1, generating: 'versions' },
  VERSIONS_READY:      { label: '版本就绪',   tagType: 'success', step: 2 },
  IMAGES_READY:        { label: '配图完成',   tagType: 'success', step: 3 },
  PUBLISHED_DRAFT:     { label: '已发草稿',   tagType: 'success', step: 4 }
}

export const statusMeta = (s) => PROJECT_STATUS[s] || { label: s || '未知', tagType: 'info', step: 0 }
export const statusLabel = (s) => statusMeta(s).label
export const statusTagType = (s) => statusMeta(s).tagType

// 步骤条推进位置(0 基,五步:简报/版本/配图/预览/发布):生成中停在当前步骤
export const activeStepOf = (s) => statusMeta(s).step

// 当前可达的最远步骤(决定步骤导航可点范围):S5 起发布步(index 4)对 IMAGES_READY/PUBLISHED_DRAFT 解锁
export const maxReachableStepOf = (s) => Math.min(activeStepOf(s), 4)

// 生成中判定:前端恢复「进行中」视图、禁用重复提交的唯一依据
export const isGeneratingBrief = (s) => s === 'GENERATING_BRIEF'
export const isGeneratingVersions = (s) => s === 'GENERATING_VERSIONS'
export const isGenerating = (s) => isGeneratingBrief(s) || isGeneratingVersions(s)

// 发布相关状态判定(S5):可发布 = 完成配图后;已发布 = 进过草稿箱(可重发)
export const isPublishable = (s) => s === 'IMAGES_READY' || s === 'PUBLISHED_DRAFT'
export const isPublished = (s) => s === 'PUBLISHED_DRAFT'