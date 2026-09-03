<template>
  <div class="container">
    <div class="page-header">
      <div>
        <span class="page-kicker">Image Library</span>
        <h2 class="serif">图库</h2>
      </div>
      <span class="muted-small">共 {{ images.length }} 张 · 文章配图在项目「预览」步骤从图库选用</span>
    </div>

    <!-- 上传 + 过滤 -->
    <div class="lib-toolbar">
      <el-upload :show-file-list="false" :before-upload="beforeUpload" :http-request="doUpload"
                 accept=".png,.jpg,.jpeg,.webp" multiple>
        <el-button type="primary" :loading="uploading" icon="Upload">上传图片</el-button>
      </el-upload>
      <el-select v-model="projectFilter" clearable placeholder="按项目过滤" class="proj-filter" size="default" @change="load">
        <el-option label="全部 / 全局图" :value="''" />
        <el-option v-for="p in projects" :key="p.id" :label="`#${p.id} ${p.topic}`" :value="p.id" />
      </el-select>
      <el-button text :icon="Refresh" @click="load">刷新</el-button>
    </div>

    <!-- 加载失败 -->
    <div v-if="loadError && !images.length" class="state-error">
      <el-icon :size="36" color="var(--faint)"><WarningFilled /></el-icon>
      <div class="state-title">图库加载失败</div>
      <div class="state-msg">{{ loadError }}</div>
      <el-button type="primary" plain @click="load">重试</el-button>
    </div>

    <div v-else-if="!images.length" class="empty-state">
      <el-empty description="图库为空：上传图片，或在项目「预览」步骤用 AI 生成" :image-size="100" />
    </div>

    <!-- 图库网格 -->
    <div v-else class="img-grid">
      <div v-for="img in images" :key="img.id" class="img-card" v-show="matchFilter(img)">
        <el-image :src="imgUrl(img)" fit="cover" class="img-thumb"
                  :preview-src-list="[imgUrl(img)]" preview-teleported hide-on-click-modal />
        <div class="img-tags">
          <el-tag size="small" type="info" effect="plain" round>{{ sourceLabel(img.source) }}</el-tag>
          <el-tag size="small" effect="plain" round>{{ projectLabel(img) }}</el-tag>
        </div>
        <div class="img-meta">
          <span class="img-name" :title="img.fileName">{{ img.fileName }}</span>
          <span class="img-dim">{{ img.width && img.height ? `${img.width}×${img.height}` : '' }}</span>
        </div>
        <div class="img-actions">
          <span class="img-own">{{ img.createdBy }} · {{ shortTime(img.createdAt) }}</span>
          <el-button v-if="user.isEditorOrAbove" size="small" type="danger" plain :loading="deletingId === img.id"
                     @click="onDelete(img)">删除</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { imageApi, projectApi } from '../api'
import { useUserStore } from '../store/user'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, WarningFilled } from '@element-plus/icons-vue'

/** 图库维护页:上传 / 浏览 / 删除。文章配图(选用封面/插图)在项目「预览」步骤进行,两处互不混责。 */
const user = useUserStore()
const images = ref([])
const projects = ref([])          // 供「按项目过滤」下拉
const projectFilter = ref('')
const loadError = ref('')
const uploading = ref(false)
const deletingId = ref(null)

const imgUrl = (img) => img?.url || ''
const matchFilter = (img) =>
  projectFilter.value === '' || projectFilter.value == null || img.projectId === projectFilter.value
const sourceLabel = (s) => ({ upload: '上传', 'ai-text2img': '文生图', 'ai-img2img': '图生图', byd: '比亚迪' }[s] || s)
const shortTime = (t) => (t || '').slice(5, 16).replace('T', ' ')
const projectLabel = (img) => {
  if (img.projectId == null) return '全局'
  const p = projects.value.find(x => x.id === img.projectId)
  return p ? `#${p.id}` : `#${img.projectId}`
}

const load = async () => {
  loadError.value = ''
  try {
    const [imgRes, projRes] = await Promise.all([
      imageApi.list(projectFilter.value || undefined),
      // 过滤下拉固定拉全量项目名(浅分页足够 MVP)
      projectApi.list({ page: 1, size: 100 })
    ])
    if (imgRes.code === 0) images.value = imgRes.data || []
    else loadError.value = imgRes.msg || '加载失败'
    if (projRes.code === 0) projects.value = projRes.data?.rows || []
  } catch (e) {
    loadError.value = e.response?.data?.msg || e.message || '网络异常'
  }
}

const beforeUpload = (file) => {
  // 只拦「声明了 MIME 且不是图片」与超限;MIME 为空或罕见变体(pjpeg 等)放行,统一交后端魔数校验定夺
  if (file.type && !/^image\/(png|jpe?g|pjpeg|webp)$/i.test(file.type)) {
    ElMessage.error('仅支持 png/jpg/webp 格式'); return false
  }
  if (file.size > 10 * 1024 * 1024) { ElMessage.error('图片超过 10MB 上限'); return false }
  return true
}
const doUpload = async ({ file }) => {
  uploading.value = true
  try {
    // 全局图库:不带 projectId
    const res = await imageApi.upload(undefined, file)
    if (res.code === 0) { ElMessage.success('已上传进图库'); await load() }
    else ElMessage.error(res.msg || '上传失败')
  } catch (e) {
    ElMessage.error('上传失败：' + (e.response?.data?.msg || e.message || '网络异常'))
  } finally { uploading.value = false }
}

const onDelete = (img) => {
  ElMessageBox.confirm(`删除「${img.fileName}」？被封面/插图引用时会被拒绝。`, '删除确认', { type: 'warning' })
    .then(async () => {
      deletingId.value = img.id
      try {
        const res = await imageApi.remove(img.id)
        if (res.code === 0) { ElMessage.success('已删除'); await load() }
        else ElMessage.error(res.msg || '删除失败')
      } catch (e) {
        ElMessage.error('删除失败：' + (e.response?.data?.msg || e.message))
      } finally { deletingId.value = null }
    })
    .catch(() => {})
}

onMounted(load)
</script>

<style scoped>
.page-header { align-items: baseline; justify-content: space-between; display: flex; flex-wrap: wrap; gap: 8px; }
.page-header h2 { margin: 2px 0 0; }
.muted-small { color: var(--muted); font-size: 12px; }
.lib-toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; flex-wrap: wrap; }
.proj-filter { width: 220px; }
.state-error { padding: 36px 16px; }
.state-title { font-weight: 700; margin: 8px 0 4px; }
.state-msg { color: var(--muted); font-size: 13px; margin-bottom: 12px; }
.empty-state { padding: 30px 0; }

.img-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(230px, 1fr)); gap: 14px; }
.img-card { border: 1px solid var(--line); border-radius: var(--radius-sm); padding: 10px; background: var(--card); transition: box-shadow .2s; }
.img-card:hover { box-shadow: var(--shadow-hover); }
.img-thumb { width: 100%; aspect-ratio: 4 / 3; border-radius: var(--radius-sm); background: var(--paper); }
.img-tags { display: flex; gap: 4px; flex-wrap: wrap; margin-top: 8px; }
.img-meta { display: flex; justify-content: space-between; gap: 6px; margin: 6px 0; font-size: 12px; color: var(--muted); }
.img-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.img-actions { display: flex; align-items: center; justify-content: space-between; gap: 6px; }
.img-own { font-size: 12px; color: var(--faint); }

@media (max-width: 768px) {
  .img-grid { grid-template-columns: repeat(2, 1fr); }
  .proj-filter { width: 100%; }
  .img-actions .el-button { min-height: 44px; }
}
</style>