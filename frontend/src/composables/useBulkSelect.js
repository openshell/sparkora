import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { imageApi } from '../api'

/**
 * 图库批量选择模式。
 * 从 ImageLibrary.vue 抽出（09-27-split-monoliths，纯结构重构，零行为变化）。
 *
 * @param opts
 *   - getPageIds: () => number[] 当前页图片 id（「全选本页」用）
 *   - reload: () => Promise 删除完成后重载列表
 */
export function useBulkSelect({ getPageIds, reload }) {
  const selectMode = ref(false)
  const selectedIds = ref(new Set())
  const bulkDeleting = ref(false)

  const enter = () => { selectMode.value = true; selectedIds.value = new Set() }
  const exit = () => { selectMode.value = false; selectedIds.value = new Set() }
  const toggleSelect = (img) => {
    const s = new Set(selectedIds.value)
    s.has(img.id) ? s.delete(img.id) : s.add(img.id)
    selectedIds.value = s
  }
  const selectAllPage = () => { selectedIds.value = new Set(getPageIds()) }

  const onBulkDelete = () => {
    const ids = [...selectedIds.value]
    ElMessageBox.confirm(`删除选中的 ${ids.length} 张图片？被封面/插图引用的会被拒绝。`, '批量删除确认', { type: 'warning' })
      .then(async () => {
        bulkDeleting.value = true
        const failed = []
        for (const id of ids) {
          try {
            const res = await imageApi.remove(id)
            if (res.code !== 0) failed.push({ id, msg: res.msg })
          } catch (e) {
            failed.push({ id, msg: e.response?.data?.msg || e.message || '网络异常' })
          }
        }
        bulkDeleting.value = false
        if (failed.length) {
          ElMessage.warning(`已删除 ${ids.length - failed.length} 张，${failed.length} 张失败（多为被引用）`)
        } else {
          ElMessage.success(`已删除 ${ids.length} 张`)
        }
        exit()
        await reload()
      })
      .catch(() => {})
  }

  return { selectMode, selectedIds, bulkDeleting, enter, exit, toggleSelect, selectAllPage, onBulkDelete }
}
