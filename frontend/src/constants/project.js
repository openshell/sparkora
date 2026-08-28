// 项目状态 → 展示映射的唯一事实源:文案 / 标签色 / 步骤条位置
// 后端状态机:DRAFT → GENERATING_BRIEF → READY → GENERATING_VERSIONS → VERSIONS_READY
// (失败会回退并写 lastBriefError / lastVersionError,见 BriefService / VersionService)
export const PROJECT_STATUS = {
  DRAFT:               { label: '草稿',       tagType: 'info',    step: 0 },
  GENERATING_BRIEF:    { label: '简报生成中', tagType: 'warning', step: 0, generating: 'brief' },
  READY:               { label: '简报就绪',   tagType: 'success', step: 1 },
  GENERATING_VERSIONS: { label: '版本生成中', tagType: 'warning', step: 1, generating: 'versions' },
  VERSIONS_READY:      { label: '版本就绪',   tagType: 'success', step: 2 }
}

export const statusMeta = (s) => PROJECT_STATUS[s] || { label: s || '未知', tagType: 'info', step: 0 }
export const statusLabel = (s) => statusMeta(s).label
export const statusTagType = (s) => statusMeta(s).tagType

// 步骤条推进位置(0 基):生成中停在当前步骤
export const activeStepOf = (s) => statusMeta(s).step

// 当前可达的最远步骤(决定步骤导航可点范围):
// 「校验」及之后步骤属 S3+ 阶段,未实现前最远到「版本」(index 1)
export const maxReachableStepOf = (s) => Math.min(activeStepOf(s), 1)

// 生成中判定:前端恢复「进行中」视图、禁用重复提交的唯一依据
export const isGeneratingBrief = (s) => s === 'GENERATING_BRIEF'
export const isGeneratingVersions = (s) => s === 'GENERATING_VERSIONS'
export const isGenerating = (s) => isGeneratingBrief(s) || isGeneratingVersions(s)