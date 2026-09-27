import { ref, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { sourceLabel } from '../utils/imageDisplay'

/**
 * 图库筛选域：关键字/来源/标签/项目 + 筛选 chip + 路由 query 双向同步。
 * 从 ImageLibrary.vue 抽出（09-27-split-monoliths，纯结构重构，零行为变化）。
 *
 * @param opts
 *   - projects: 项目清单 ref（项目 chip 显示 `#id` 用）
 *   - onFilterChange: 任一筛选变更后的动作（重置分页 + 重载，由宿主提供）
 *   - onExternalTag: 外部带 tag 跳入且当前处于语义模式时，退出语义模式（宿主提供）
 *   - isSemanticMode: () => boolean 当前是否语义搜索模式
 */
export function useImageFilters({ projects, onFilterChange, onExternalTag, isSemanticMode }) {
  const route = useRoute()
  const router = useRouter()

  const keyword = ref('')
  const sourceFilter = ref('')
  const tagFilter = ref([])          // 09-15 起为数组，多标签 AND
  const projectFilter = ref('')

  /** 路由 query 的 tag（支持重复/逗号）解析为筛选数组。 */
  const tagsFromRoute = () => {
    const raw = route.query.tag
    const arr = Array.isArray(raw) ? raw : raw == null ? [] : [raw]
    return [...new Set(arr.flatMap(v => String(v).split(',')).map(s => s.trim()).filter(Boolean))]
  }
  /** 路由 query 的 tag 预置到多选筛选（跨页面跳转/刷新落地）。 */
  const applyFilterFromRoute = () => { tagFilter.value = tagsFromRoute() }
  /**
   * 标签筛选变化后把 route.query.tag 同步为当前选中（router.replace，不进历史栈）。
   * 必要性：清掉 chip 后若 URL 仍留旧 tag，再次从新闻页点同一主题时 query 未变 →
   * vue-router 判定重复导航、watch 不触发 → 筛选不生效（点了没反应）。
   */
  const syncRouteTag = () => {
    const cur = tagsFromRoute()
    const next = tagFilter.value
    if (cur.length === next.length && cur.every((t, i) => next[i] === t)) return
    const query = { ...route.query }
    if (next.length) query.tag = [...next]
    else delete query.tag
    router.replace({ query })
  }
  watch(() => route.query.tag, () => {
    const tags = tagsFromRoute()
    // 自身 syncRouteTag 触发的回流：与当前筛选一致则不动（防重复 load）
    if (tags.length === tagFilter.value.length && tags.every((t, i) => tagFilter.value[i] === t)) return
    // 外部跳入带 tag（如新闻页点主题标签）→ 退出语义搜索，回到浏览态按标签筛选
    if (isSemanticMode && isSemanticMode()) onExternalTag()
    tagFilter.value = tags
    onFilterChange()
  })

  const hasFilter = computed(() => !!(keyword.value.trim() || sourceFilter.value || tagFilter.value.length || projectFilter.value !== ''))
  const activeChips = computed(() => {
    const chips = []
    if (sourceFilter.value) chips.push({ key: 'src', label: `来源: ${sourceLabel(sourceFilter.value)}`, clear: () => { sourceFilter.value = ''; onFilterChange() } })
    // 09-15 img-classify:每个选中标签各一个 chip,可单独清除（多标签 AND）
    for (const t of tagFilter.value) {
      chips.push({ key: `tag:${t}`, label: `标签: ${t}`, clear: () => { tagFilter.value = tagFilter.value.filter(x => x !== t); syncRouteTag(); onFilterChange() } })
    }
    if (projectFilter.value !== '') {
      const p = (projects.value || []).find(x => x.id === projectFilter.value)
      chips.push({ key: 'proj', label: `项目: ${p ? '#' + p.id : '#' + projectFilter.value}`, clear: () => { projectFilter.value = ''; onFilterChange() } })
    }
    if (keyword.value.trim()) chips.push({ key: 'kw', label: `关键字: ${keyword.value.trim()}`, clear: () => { keyword.value = ''; onFilterChange() } })
    return chips
  })

  const clearAllFilters = () => { sourceFilter.value = ''; projectFilter.value = ''; keyword.value = ''; tagFilter.value = []; syncRouteTag(); onFilterChange() }
  /** 点卡片标签 → 直接按该标签筛选（09-13 image-tags；09-15 起为多选数组，点已选标签则取消）。 */
  const toggleFilterTag = (name) => {
    tagFilter.value = tagFilter.value.includes(name)
      ? tagFilter.value.filter(t => t !== name)
      : [...tagFilter.value, name]
    syncRouteTag()
    onFilterChange()
  }

  return {
    route, router,
    keyword, sourceFilter, tagFilter, projectFilter,
    hasFilter, activeChips, tagsFromRoute, applyFilterFromRoute, syncRouteTag, clearAllFilters, toggleFilterTag
  }
}
