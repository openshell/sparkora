import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { imageApi } from '../api'

/**
 * 图库语义搜索模式（09-15 img-semantic-search 子B）。
 *
 * 与精确筛选（关键字/来源/标签/项目）互斥：语义搜索走 POST /images/search，
 * 按相关度排序、无分页。展示复用同一网格（命中项字段为 ImageSearchHit 子集）。
 * 从 ImageLibrary.vue 抽出（09-27-split-monoliths，纯结构重构，零行为变化）。
 *
 * @param opts
 *   - clearResults: () => void 清空网格（images=[]/total=0/sourceMap={}）
 *   - applyResults: (list) => void 写入命中（含 total 与来源批查）
 *   - setError: (msg) => void 写 loadError
 *   - exitSelectMode: () => void 进入语义模式前退出批量选择
 *   - exitToList: () => void 回到浏览态并重载（page=1 + load）
 */
export function useSemanticSearch({ clearResults, applyResults, setError, exitSelectMode, exitToList }) {
  const semanticMode = ref(false)
  const semanticQuery = ref('')
  const semanticTags = ref([])         // 语义检索的标签 AND 预过滤（可选）
  const semanticLoading = ref(false)
  const semanticActive = ref(false)    // 已执行过检索（区分「未搜」与「搜了没结果」）
  const semanticMinScore = ref(0.3)    // 相关度门槛（可选调；默认与后端 AI_IMAGE_MIN_SCORE 一致）

  /** 退出检索态（只清模式标记与结果，由调用方决定是否重置输入/重载列表）。 */
  const deactivate = ({ resetInputs = false } = {}) => {
    semanticMode.value = false
    semanticActive.value = false
    if (resetInputs) { semanticQuery.value = ''; semanticTags.value = [] }
  }

  const toggleSemanticMode = () => {
    if (semanticMode.value) {
      exitSemantic()
      return
    }
    if (exitSelectMode) exitSelectMode()   // 两种模式互斥：进入语义搜索前退出批量选择
    semanticMode.value = true
    semanticQuery.value = ''
    semanticTags.value = []
    semanticActive.value = false
    clearResults()
  }
  /** 退出语义搜索 → 回到图库浏览态（保留原有精确筛选状态）。 */
  const exitSemantic = () => {
    deactivate()
    clearResults()
    exitToList()
  }
  /** 语义检索命中（ImageSearchHit）→ 网格卡片形状（补 id/url/thumbUrl，保留 score/sourceText 供展示）。
   *  命中缺少 projectId/width/height/createdBy 等字段——这些在语义模式下仅作次要信息，缺失时模板自动留空。 */
  const hitToCard = (h) => ({
    id: h.imageId,
    fileName: h.fileName,
    source: h.source,
    sourceRef: h.sourceRef,
    url: h.url,
    thumbUrl: h.thumbUrl,
    tags: h.tags || [],
    score: h.score,
    sourceText: h.sourceText
  })
  /** 执行语义检索：空 query 提示；结果直接替换网格并按相关度降序（后端已排序）。 */
  const runSemanticSearch = async () => {
    const q = semanticQuery.value.trim()
    if (!q) { ElMessage.warning('请输入搜索描述'); return }
    semanticLoading.value = true
    setError('')
    try {
      const res = await imageApi.search(q, { tags: semanticTags.value, minScore: semanticMinScore.value })
      if (res.code === 0) {
        const list = (res.data || []).map(hitToCard)
        applyResults(list)
        semanticActive.value = true
      } else setError(res.msg || '检索失败')
    } catch (e) {
      setError(e.response?.data?.msg || e.message || '网络异常')
    } finally {
      semanticLoading.value = false
    }
  }
  /** 标签变化后若已搜索过则重跑（未搜索过不动）。 */
  const refreshSemantic = () => { if (semanticActive.value) runSemanticSearch() }

  return {
    semanticMode, semanticQuery, semanticTags, semanticLoading, semanticActive, semanticMinScore,
    toggleSemanticMode, exitSemantic, deactivate, hitToCard, runSemanticSearch, refreshSemantic
  }
}
