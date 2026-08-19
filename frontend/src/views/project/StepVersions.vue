<template>
  <el-card class="step-card" shadow="never">
    <template #header>
      <span class="card-head">
        Step 2 · 多版本正文生成
        <span v-if="versions.length" class="meta">共 {{ versions.length }} 版 · 当前：{{ currentVersionLabel }}</span>
      </span>
    </template>

    <el-skeleton v-if="generatingVersions" :rows="8" animated />

    <template v-else>
      <!-- 风格选择区 -->
      <div v-if="!versions.length" class="style-pick">
        <p class="muted">从风格库选择 1~N 个风格，每个选中的风格生成一版正文用于对比。</p>
        <div v-if="!styleOptions.length" class="empty-style">
          风格库为空，请先到 <router-link to="/styles">风格库</router-link> 提炼入库。
        </div>
        <el-checkbox-group v-else v-model="selectedStyleIds" class="style-list">
          <el-checkbox v-for="s in styleOptions" :key="s.id" :label="s.id" border class="style-cb">
            <span class="style-name">{{ s.name }}</span>
            <span class="style-desc">{{ s.description }}</span>
          </el-checkbox>
        </el-checkbox-group>
        <div class="gen-actions">
          <el-button type="primary" :disabled="!selectedStyleIds.length" :loading="generatingVersions" @click="onGenerate">
            生成 {{ selectedStyleIds.length || '' }} 版
          </el-button>
        </div>
      </div>

      <!-- 版本对比区 -->
      <div v-else>
        <div class="regen-row">
          <el-button size="small" :loading="generatingVersions" @click="onReopenPick">重新选风格</el-button>
          <el-button size="small" :loading="generatingVersions" @click="onRegenerate">用同样风格重生成</el-button>
        </div>
        <div class="version-grid">
          <div v-for="v in versions" :key="v.id" class="version-card" :class="{ active: v.id === project?.currentVersionId }">
            <div class="version-head">
              <el-tag size="small">{{ v.versionLabel }}</el-tag>
              <el-tag size="small" type="info" effect="plain">{{ v.styleTag }}</el-tag>
              <span class="version-meta">{{ v.wordCount }}字 · {{ v.aiModel }}</span>
            </div>
            <div class="version-title">{{ v.title }}</div>
            <div class="version-content markdown-body" v-html="renderMd(v.contentMd)"></div>
            <div class="version-actions">
              <el-button size="small" :type="v.id === project?.currentVersionId ? 'success' : 'default'" @click="onSetCurrent(v.id)">
                {{ v.id === project?.currentVersionId ? '✓ 当前' : '设为当前' }}
              </el-button>
            </div>
          </div>
        </div>
      </div>
    </template>
  </el-card>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { useRoute } from 'vue-router'
import MarkdownIt from 'markdown-it'
import { projectApi, styleApi } from '../../api'
import { ElMessage } from 'element-plus'

const props = defineProps({ project: Object, reloadProject: Function })

const md = new MarkdownIt({ html: false, breaks: true, linkify: true })
const renderMd = (src) => { try { return md.render(src || '') } catch { return '' } }

const route = useRoute()
const versions = ref([])
const styleOptions = ref([])
const selectedStyleIds = ref([])
const generatingVersions = ref(false)

const currentVersionLabel = computed(() => {
  const cur = versions.value.find(v => v.id === props.project?.currentVersionId)
  return cur ? `${cur.versionLabel}·${cur.styleTag}` : '未选'
})

const loadVersions = async () => {
  const res = await projectApi.listVersions(route.params.id)
  versions.value = res.data || []
}
const loadStyles = async () => {
  const res = await styleApi.list(true)
  styleOptions.value = res.data || []
}
const onGenerate = async () => {
  if (!selectedStyleIds.value.length) return
  generatingVersions.value = true
  try {
    const res = await projectApi.generateVersions(route.params.id, selectedStyleIds.value)
    if (res.code === 0) { versions.value = res.data || []; ElMessage.success(`已生成 ${versions.value.length} 版`) }
    else ElMessage.error(res.msg || '生成失败')
    await props.reloadProject()
  } catch (e) { ElMessage.error('生成失败：' + (e.message || e)); await props.reloadProject() }
  finally { generatingVersions.value = false }
}
const onRegenerate = () => onGenerate()
const onReopenPick = () => { versions.value = [] }
const onSetCurrent = async (versionId) => {
  const res = await projectApi.setCurrentVersion(route.params.id, versionId)
  if (res.code === 0) { await props.reloadProject(); ElMessage.success('已设为当前版本') }
  else ElMessage.error(res.msg || '设置失败')
}
onMounted(async () => { await loadVersions(); await loadStyles() })
</script>

<style scoped>
.step-card { margin-bottom: 12px; }
.card-head { display: flex; justify-content: space-between; align-items: center; width: 100%; }
.card-head .meta { font-size: 12px; color: var(--muted); font-weight: normal; }
.muted { color: var(--muted); font-size: 13px; }
.style-list { display: flex; flex-direction: column; gap: 8px; margin: 12px 0; }
.style-cb { display: flex; align-items: flex-start; height: auto; white-space: normal; }
.style-cb :deep(.el-checkbox__label) { white-space: normal; line-height: 1.5; }
.style-name { font-weight: 600; margin-right: 6px; }
.style-desc { color: var(--muted); font-size: 12px; }
.empty-style { font-size: 13px; color: var(--muted); margin: 8px 0; }
.gen-actions { margin-top: 8px; }
.regen-row { display: flex; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; }
.version-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 12px; }
.version-card { border: 1px solid var(--el-border-color); border-radius: 8px; padding: 12px; }
.version-card.active { border-color: var(--el-color-success); box-shadow: 0 0 0 2px var(--el-color-success-light-7); }
.version-head { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; margin-bottom: 6px; }
.version-meta { font-size: 12px; color: var(--muted); margin-left: auto; }
.version-title { font-weight: 600; font-size: 15px; margin-bottom: 8px; line-height: 1.4; }
.version-content { font-size: 14px; line-height: 1.7; max-height: 520px; overflow-y: auto; }
.version-content :deep(h1) { font-size: 18px; margin: 12px 0 6px; }
.version-content :deep(h2) { font-size: 16px; margin: 10px 0 5px; }
.version-content :deep(h3) { font-size: 15px; margin: 8px 0 4px; }
.version-content :deep(p) { margin: 6px 0; }
.version-content :deep(ul), .version-content :deep(ol) { padding-left: 20px; margin: 6px 0; }
.version-content :deep(code) { background: var(--el-fill-color-light); padding: 1px 4px; border-radius: 3px; font-size: 13px; }
.version-actions { display: flex; gap: 8px; margin-top: 10px; }
@media (max-width: 768px) { .version-grid { grid-template-columns: 1fr; } .version-actions .el-button { flex: 1; } }
</style>
