import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { imageApi } from '../api'
import * as imageRefCache from '../utils/imageRefCache'
import { canRegenerate as canRegenerateImpl, cacheHasRefs } from '../utils/imageRegenerate'

/**
 * 图库页各类副操作（上传 / 单卡删除与重生成 / 来源追溯 / 标签对话框）。
 * 从 ImageLibrary.vue 抽出（09-27-split-monoliths，纯结构重构，零行为变化）。
 * 通过注入回调与共享 ref 与宿主编排（加载/刷新/选择/标签）协作。
 */

/** 来源追溯（09-15 img-classify）：hover/移动端显示「来源：<新闻标题>·<日期>」。 */
export function useImageSourceTrace() {
  const sourceMap = ref({})
  const sourceInfo = (id) => sourceMap.value[id]
  const openNews = (info) => {
    const u = info?.news?.url
    if (u) window.open(/^https?:\/\//i.test(u) ? u : 'https://www.byd.com' + u, '_blank', 'noopener')
  }
  /** 页内批查来源（仅新闻来源图有值；失败静默，卡片不显示来源行）；结果按当前页收敛，避免长会话累积 */
  const loadSources = async (rows) => {
    const targets = rows.filter(r => r.sourceRef)
    const next = {}
    if (!targets.length) { sourceMap.value = next; return }
    const list = await Promise.all(targets.map(async r => {
      try {
        const res = await imageApi.getSource(r.id)
        return res.code === 0 ? [r.id, res.data] : null
      } catch { return null }
    }))
    for (const item of list) if (item) next[item[0]] = item[1]
    sourceMap.value = next
  }
  return { sourceMap, sourceInfo, openNews, loadSources }
}

/** 上传（工具条 el-upload + 空态隐藏 input 复用）。 */
export function useImageUpload({ presetTags, refreshView }) {
  const uploading = ref(false)
  const uploadInput = ref(null)
  const triggerUpload = () => uploadInput.value?.click()
  const onUploadInput = (e) => {
    const files = [...(e.target.files || [])]
    e.target.value = ''
    files.forEach(f => doUpload({ file: f }))
  }
  const beforeUpload = (file) => {
    if (file.type && !/^image\/(png|jpe?g|pjpeg|webp)$/i.test(file.type)) {
      ElMessage.error('仅支持 png/jpg/webp 格式'); return false
    }
    if (file.size > 10 * 1024 * 1024) { ElMessage.error('图片超过 10MB 上限'); return false }
    return true
  }
  const doUpload = async ({ file }) => {
    uploading.value = true
    try {
      const res = await imageApi.upload(undefined, file, presetTags.value)
      if (res.code === 0) {
        if (res.data?.dedupeHit) ElMessage.info(`图库已有相同图片（#${res.data.id}），已复用并以并集补写标签`)
        else ElMessage.success('已上传进图库')
        await refreshView()
      }
      else ElMessage.error(res.msg || '上传失败')
    } catch (e) {
      ElMessage.error('上传失败：' + (e.response?.data?.msg || e.message || '网络异常'))
    } finally { uploading.value = false }
  }
  return { uploading, uploadInput, triggerUpload, onUploadInput, beforeUpload, doUpload }
}

/** 单卡删除 / 重生成（含会话缓存复用与移动端命令分派）。 */
export function useImageCardOps({ refreshView }) {
  const deletingId = ref(null)
  const regenId = ref(null)

  const canRegenerate = (img) => canRegenerateImpl(img, imageRefCache)
  const onDelete = (img) => {
    ElMessageBox.confirm(`删除「${img.fileName}」？被封面/插图引用时会被拒绝。`, '删除确认', { type: 'warning' })
      .then(async () => {
        deletingId.value = img.id
        try {
          const res = await imageApi.remove(img.id)
          if (res.code === 0) { ElMessage.success('已删除'); await refreshView() }
          else ElMessage.error(res.msg || '删除失败')
        } catch (e) {
          ElMessage.error('删除失败：' + (e.response?.data?.msg || e.message))
        } finally { deletingId.value = null }
      })
      .catch(() => {})
  }
  const onRegenerate = async (img) => {
    if (!canRegenerate(img)) return
    regenId.value = img.id
    try {
      // 会话缓存命中（多参考图来源）→ 复用整组参考图 + prompt 走多图 multipart；
      // 否则图库/文生图来源走后端 /regenerate（复用 refImageId / prompt）。
      // projectId 用缓存里的原值（与后端 /regenerate 的 orphanFallback(src.projectId) 同口径，
      // 也与 AiImageDrawer 组件内重生成一致）；硬编码 null 会把项目内图重生成到全局图库。
      const cached = imageRefCache.get(img.id)
      const res = cacheHasRefs(cached)
        ? await imageApi.generateFromImageUpload(cached.projectId ?? null, cached.files || [], cached.refImageIds || [], cached.prompt, cached.size, 1, cached.tags)
        : await imageApi.regenerate(img.id)
      if (res.code === 0) {
        const list = res.data || []
        // 新图同样可用同一组参考图再重生成
        if (cacheHasRefs(cached)) {
          for (const n of list) {
            imageRefCache.put(n.id, { files: cached.files, refImageIds: cached.refImageIds, names: cached.names, prompt: cached.prompt, size: cached.size, tags: cached.tags, projectId: cached.projectId })
          }
        }
        ElMessage.success(`已重新生成 ${list.length} 张（新图在列表最前）`)
        await refreshView()
      } else ElMessage.error(res.msg || '重新生成失败')
    } catch (e) {
      ElMessage.error('重新生成失败：' + (e.response?.data?.msg || e.message || '网络异常'))
    } finally { regenId.value = null }
  }

  return { deletingId, regenId, canRegenerate, onDelete, onRegenerate }
}

/** 单图编辑标签（全量覆盖）+ 批量打标/移除。 */
export function useImageTagDialogs({ selectedIds, exitSelectMode, refreshView, tagDialogTags, bulkTagTags }) {
  const tagDialog = ref(false)
  const tagDialogImage = ref(null)
  const tagSaving = ref(false)
  const openTagDialog = (img) => {
    tagDialogImage.value = img
    tagDialogTags.value = [...(img.tags || [])]
    tagDialog.value = true
  }
  const onSaveTags = async () => {
    const img = tagDialogImage.value
    if (!img) return
    tagSaving.value = true
    try {
      const res = await imageApi.updateTags(img.id, tagDialogTags.value)
      if (res.code === 0) {
        ElMessage.success('标签已保存')
        tagDialog.value = false
        await refreshView()
      } else ElMessage.error(res.msg || '保存失败')
    } catch (e) {
      ElMessage.error('保存失败：' + (e.response?.data?.msg || e.message || '网络异常'))
    } finally { tagSaving.value = false }
  }

  const bulkTagDialog = ref(false)
  const bulkTagAction = ref('add')
  const bulkTagSaving = ref(false)
  const openBulkTag = () => { bulkTagTags.value = []; bulkTagAction.value = 'add'; bulkTagDialog.value = true }
  const onBulkTag = async () => {
    if (!bulkTagTags.value.length) { ElMessage.warning('请选择或输入标签'); return }
    bulkTagSaving.value = true
    try {
      const res = await imageApi.batchTags([...selectedIds.value], bulkTagTags.value, bulkTagAction.value)
      if (res.code === 0) {
        ElMessage.success(bulkTagAction.value === 'add' ? '已补打标签' : '已移除标签')
        bulkTagDialog.value = false
        exitSelectMode()
        await refreshView()
      } else ElMessage.error(res.msg || '操作失败')
    } catch (e) {
      ElMessage.error('操作失败：' + (e.response?.data?.msg || e.message || '网络异常'))
    } finally { bulkTagSaving.value = false }
  }

  return {
    tagDialog, tagDialogImage, tagSaving, openTagDialog, onSaveTags,
    bulkTagDialog, bulkTagAction, bulkTagSaving, openBulkTag, onBulkTag
  }
}
