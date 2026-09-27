import { ref, computed, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import { imageApi } from '../api'

/**
 * 预览页「配图」抽屉的图库分页检索（S10：抽屉「图库」tab 触底加载）。
 *
 * 从 StepPreview.vue 抽出（09-27-split-monoliths，纯结构重构，零行为变化）。
 * 状态与请求逻辑自洽：仅依赖 projectId + imageApi，触底 append，筛选变化重置。
 */
export const SOURCE_LABELS = { upload: '上传', 'ai-text2img': '文生图', 'ai-img2img': '图生图', byd: '比亚迪' }

export function usePreviewLibrary() {
  const libraryImages = ref([])       // 抽屉网格数据（分页接口累积）
  const libPage = ref(1)
  const LIB_SIZE = 24
  const libTotal = ref(0)
  const libLoading = ref(false)
  const libSource = ref('')
  const libKeyword = ref('')
  const libHasMore = computed(() => libraryImages.value.length < libTotal.value)

  /** 拉一页图库(筛选条件变化时由 reloadLibrary 重置;触底时 append)。 */
  const loadLibraryPage = async (append) => {
    if (libLoading.value) return
    libLoading.value = true
    try {
      const res = await imageApi.list({
        page: libPage.value, size: LIB_SIZE,
        source: libSource.value || undefined,
        keyword: libKeyword.value.trim() || undefined
      })
      if (res.code === 0) {
        const rows = res.data?.rows || []
        libraryImages.value = append ? [...libraryImages.value, ...rows] : rows
        libTotal.value = res.data?.total || 0
      } else ElMessage.error(res.msg || '图库加载失败')
    } catch (e) {
      ElMessage.error('图库加载失败：' + (e.response?.data?.msg || e.message || '网络异常'))
    } finally { libLoading.value = false }
  }
  const reloadLibrary = () => {
    libPage.value = 1
    loadLibraryPage(false)
  }
  const loadMoreLibrary = () => {
    if (!libHasMore.value || libLoading.value) return
    libPage.value += 1
    loadLibraryPage(true)
  }
  let libKwTimer = null
  const onLibKeywordInput = () => {
    clearTimeout(libKwTimer)
    libKwTimer = setTimeout(reloadLibrary, 300)
  }
  onBeforeUnmount(() => clearTimeout(libKwTimer))

  return {
    SOURCE_LABELS,
    libraryImages, libPage, LIB_SIZE, libTotal, libLoading, libSource, libKeyword, libHasMore,
    loadLibraryPage, reloadLibrary, loadMoreLibrary, onLibKeywordInput
  }
}
