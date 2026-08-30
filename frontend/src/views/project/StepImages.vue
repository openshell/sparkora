<template>
  <el-card class="step-card" shadow="never">
    <template #header>
      <span class="card-head">
        <span class="step-title serif">Step 3 · 配图</span>
        <span v-if="snapshot" class="meta">
          封面 {{ snapshot.coverImageId ? '已选' : '未选' }} · 插图 {{ bodyIds.length }} 张
        </span>
      </span>
    </template>

    <!-- 前置未就绪:未生成版本时不可配图 -->
    <div v-if="!hasCurrentVersion" class="state-error">
      <el-icon :size="36" color="var(--faint)"><WarningFilled /></el-icon>
      <div class="state-title">尚未生成正文版本</div>
      <div class="state-msg">请先回到「版本」步骤生成并选定当前版本，再进行配图。</div>
      <el-button type="primary" plain @click="$router.push({ name: 'project-versions', params: { id: projectId } })">去生成版本</el-button>
    </div>

    <template v-else>
      <!-- 配图快照加载失败:可见化 + 重试 -->
      <div v-if="snapshotError && !snapshot" class="state-error">
        <el-icon :size="36" color="var(--faint)"><WarningFilled /></el-icon>
        <div class="state-title">配图数据加载失败</div>
        <div class="state-msg">{{ snapshotError }}</div>
        <el-button type="primary" plain @click="loadSnapshot">重试</el-button>
      </div>

      <template v-else-if="snapshot">
        <!-- 图库选用(主入口):本步不再承担图库维护,上传/删除去「图库」页 -->
        <p class="muted">
          从图库点「设封面 / 加插图」选用；图不够时先到 <router-link to="/images">图库</router-link> 上传，
          或在本页用 AI 生成后选用。
        </p>

        <!-- AI 生成(可选补充)：文生图 / 图生图 -->
        <el-tabs v-model="activeTab" class="src-tabs">
          <el-tab-pane label="AI 文生图" name="text2img">
            <el-form label-position="top" class="gen-form" @submit.prevent>
              <el-form-item label="画面描述（prompt）" required>
                <el-input v-model="t2iPrompt" type="textarea" :rows="3"
                          placeholder="例：俯瞰一杯咖啡与摊开的笔记本，晨光，暖色调，杂志摄影风格" />
              </el-form-item>
              <el-form-item label="尺寸">
                <el-select v-model="genSize" class="size-select">
                  <el-option label="方图 1024×1024（推荐封面）" value="1024x1024" />
                  <el-option label="横图 1536×1024" value="1536x1024" />
                  <el-option label="竖图 1024×1536" value="1024x1536" />
                </el-select>
              </el-form-item>
              <div class="gen-actions">
                <el-button type="primary" :loading="generating" @click="onGenerateText">
                  {{ generating ? '生成中…（约 1~2 分钟）' : '生成并进图库' }}
                </el-button>
              </div>
            </el-form>
          </el-tab-pane>

          <el-tab-pane label="AI 图生图" name="img2img">
            <p class="muted">选一张参考图 + 画面描述；若模型不支持图生图会给出明确提示，可改用文生图。</p>
            <el-form label-position="top" class="gen-form" @submit.prevent>
              <el-form-item label="参考图" required>
                <div v-if="refImage" class="ref-pick">
                  <img :src="imgUrl(refImage)" class="ref-thumb" alt="参考图" />
                  <div class="ref-meta">
                    <div class="ref-name">#{{ refImage.id }} {{ shortName(refImage.fileName) }}</div>
                    <el-button size="small" text type="primary" @click="refDialog = true">重新选择</el-button>
                  </div>
                </div>
                <el-button v-else plain @click="refDialog = true">从图库选择参考图</el-button>
              </el-form-item>
              <el-form-item label="画面描述（prompt）" required>
                <el-input v-model="i2iPrompt" type="textarea" :rows="3"
                          placeholder="例：保持构图，改为蓝灰色科技感色调" />
              </el-form-item>
              <el-form-item label="尺寸">
                <el-select v-model="genSize" class="size-select">
                  <el-option label="方图 1024×1024（推荐封面）" value="1024x1024" />
                  <el-option label="横图 1536×1024" value="1536x1024" />
                  <el-option label="竖图 1024×1536" value="1024x1536" />
                </el-select>
              </el-form-item>
              <div class="gen-actions">
                <el-button type="primary" :disabled="!refImage" :loading="generating" @click="onGenerateFromImage">
                  {{ generating ? '生成中…（约 1~2 分钟）' : '基于参考图生成并进图库' }}
                </el-button>
              </div>
            </el-form>
          </el-tab-pane>
        </el-tabs>

        <el-divider />

        <!-- 图库选用网格(全量图,不只本项目) -->
        <div class="section-head">
          <span class="sec-title">图库（{{ images.length }} 张可选）</span>
          <span class="muted-small">挂到当前版本 #{{ snapshot.currentVersionId }} · <router-link to="/images">去图库上传/管理</router-link></span>
        </div>
        <div v-if="!images.length" class="empty-state">
          <el-empty description="图库为空：先到「图库」上传，或用上方 AI 生成" :image-size="80" />
        </div>
        <div v-else class="img-grid">
          <div v-for="img in images" :key="img.id" class="img-card"
               :class="{ cover: img.id === snapshot.coverImageId, inBody: bodyIdSet.has(img.id) }">
            <el-image :src="imgUrl(img)" fit="cover" class="img-thumb" :preview-src-list="[imgUrl(img)]"
                      preview-teleported hide-on-click-modal />
            <div class="img-tags">
              <el-tag v-if="img.id === snapshot.coverImageId" type="success" size="small" effect="dark" round>封面</el-tag>
              <el-tag v-if="bodyIdSet.has(img.id)" size="small" round>插图</el-tag>
              <el-tag size="small" type="info" effect="plain" round class="src-tag">{{ sourceLabel(img.source) }}</el-tag>
            </div>
            <div class="img-meta">
              <span class="img-name" :title="img.fileName">{{ img.fileName }}</span>
              <span class="img-dim">{{ img.width && img.height ? `${img.width}×${img.height}` : '' }}</span>
            </div>
            <div class="img-actions">
              <el-button size="small" :type="img.id === snapshot.coverImageId ? 'success' : 'default'"
                         :disabled="img.id === snapshot.coverImageId || busy" @click="onSetCover(img.id)">
                {{ img.id === snapshot.coverImageId ? '✓ 封面' : '设封面' }}
              </el-button>
              <el-button v-if="!bodyIdSet.has(img.id)" size="small" :disabled="busy" @click="onAddBody(img.id)">加插图</el-button>
              <el-button v-else size="small" type="warning" plain :disabled="busy" @click="onRemoveBody(img.id)">移出插图</el-button>
            </div>
          </div>
        </div>

        <!-- 完成配图推进状态 -->
        <div class="next-row">
          <span class="muted-small">需先选定封面或至少一张插图；完成后进入「预览」步骤(S4)</span>
          <el-button type="success" :loading="completing" :disabled="!canComplete" @click="onComplete">
            {{ project?.status === 'IMAGES_READY' ? '已完成配图 ✓' : '完成配图，去预览' }}
          </el-button>
        </div>
      </template>

      <!-- 参考图选择弹窗 -->
      <el-dialog v-model="refDialog" title="选择参考图" width="720px" class="ref-dialog">
        <div v-if="!images.length" class="empty-state">
          <el-empty description="图库为空，请先到「图库」上传或用 AI 生成" :image-size="80" />
        </div>
        <div v-else class="ref-grid">
          <div v-for="img in images" :key="img.id" class="ref-cell" @click="chooseRef(img)">
            <el-image :src="imgUrl(img)" fit="cover" class="ref-cell-thumb" />
            <span class="ref-cell-name">#{{ img.id }} {{ shortName(img.fileName) }}</span>
          </div>
        </div>
      </el-dialog>
    </template>
  </el-card>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { imageApi } from '../../api'
import { ElMessage } from 'element-plus'
import { useProjectDetailStore } from '../../store/project-detail'
import { WarningFilled } from '@element-plus/icons-vue'

/**
 * Step 3 · 配图（S3b）。职责:从图库选用(封面/插图)+ AI 生成补充。
 * 图库维护(上传/删除/浏览)在独立「图库」页 —— 配图步骤不承担素材管理。
 * 配图数据自持局部状态;project 详情(状态推进)走布局层共用的 store。
 */
const props = defineProps({ project: Object })
const route = useRoute()
const router = useRouter()
const store = useProjectDetailStore()
const projectId = computed(() => route.params.id)

const activeTab = ref('text2img')
const snapshot = ref(null)          // {images,currentVersionId,coverImageId,bodyImageIds}
const snapshotError = ref('')
const generating = ref(false)       // AI 生成中(按钮 loading)
const completing = ref(false)
const busy = ref(false)             // 封面/插图操作中
const t2iPrompt = ref('')
const i2iPrompt = ref('')
const genSize = ref('1024x1024')
const refImage = ref(null)          // 图生图参考图
const refDialog = ref(false)

const images = computed(() => snapshot.value?.images || [])
const bodyIds = computed(() => snapshot.value?.bodyImageIds || [])
const bodyIdSet = computed(() => new Set(bodyIds.value))

const hasCurrentVersion = computed(() => !!props.project
  && ['VERSIONS_READY', 'IMAGES_READY'].includes(props.project.status))
const canComplete = computed(() =>
  hasCurrentVersion.value && !!props.project
  && (snapshot.value?.coverImageId || bodyIds.value.length))

onMounted(() => { if (hasCurrentVersion.value) loadSnapshot() })
watch([() => props.project, hasCurrentVersion], ([p, ok]) => {
  if (p && ok && !snapshot.value && !snapshotError.value) loadSnapshot()
})

const loadSnapshot = async () => {
  snapshotError.value = ''
  try {
    const res = await imageApi.projectImages(projectId.value)
    if (res.code === 0) snapshot.value = res.data
    else snapshotError.value = res.msg || '加载失败'
  } catch (e) {
    snapshotError.value = e.response?.data?.msg || e.message || '网络异常'
  }
}

const imgUrl = (img) => `/images/${img.storagePath}`
const sourceLabel = (s) => ({ upload: '上传', 'ai-text2img': '文生图', 'ai-img2img': '图生图' }[s] || s)
const shortName = (n) => n && n.length > 18 ? n.slice(0, 18) + '…' : n

// ---------- AI 生成(产物进图库,再选用;projectId 为当前项目) ----------
const onGenerateText = () => {
  if (!t2iPrompt.value.trim()) { ElMessage.warning('请输入画面描述'); return }
  doGenerate(imageApi.generateText(projectId.value, t2iPrompt.value.trim(), genSize.value))
}
const onGenerateFromImage = () => {
  if (!refImage.value) { ElMessage.warning('请先选择参考图'); return }
  if (!i2iPrompt.value.trim()) { ElMessage.warning('请输入画面描述'); return }
  doGenerate(imageApi.generateFromImage(projectId.value, refImage.value.id, i2iPrompt.value.trim(), genSize.value))
}
const doGenerate = async (req) => {
  generating.value = true
  try {
    const res = await req
    if (res.code === 0) { ElMessage.success('生成成功，已进图库'); await loadSnapshot() }
    else ElMessage.error(res.msg || '生成失败')
  } catch (e) {
    ElMessage.error('生成失败：' + (e.response?.data?.msg || e.message || '网络异常或超时'))
  } finally { generating.value = false }
}

// ---------- 参考图 / 封面 / 插图 / 完成 ----------
const chooseRef = (img) => { refImage.value = img; refDialog.value = false }

const onSetCover = async (imageId) => {
  busy.value = true
  try {
    const res = await imageApi.setCover(projectId.value, imageId)
    if (res.code === 0) { ElMessage.success('已设为封面'); await loadSnapshot() }
    else ElMessage.error(res.msg || '设置失败')
  } catch (e) {
    ElMessage.error('设置失败：' + (e.response?.data?.msg || e.message || '网络异常'))
  } finally { busy.value = false }
}
const onAddBody = async (imageId) => {
  busy.value = true
  try {
    const res = await imageApi.addBodyImage(projectId.value, imageId)
    if (res.code === 0) await loadSnapshot()
    else ElMessage.error(res.msg || '操作失败')
  } catch (e) {
    ElMessage.error('操作失败：' + (e.response?.data?.msg || e.message))
  } finally { busy.value = false }
}
const onRemoveBody = async (imageId) => {
  busy.value = true
  try {
    const res = await imageApi.removeBodyImage(projectId.value, imageId)
    if (res.code === 0) await loadSnapshot()
    else ElMessage.error(res.msg || '操作失败')
  } catch (e) {
    ElMessage.error('操作失败：' + (e.response?.data?.msg || e.message))
  } finally { busy.value = false }
}
const onComplete = async () => {
  completing.value = true
  try {
    const res = await imageApi.completeImages(projectId.value)
    if (res.code === 0) {
      ElMessage.success('配图完成，进入「预览」步骤')
      await store.ensureProject(projectId.value, { force: true })
      await loadSnapshot()
      // S4:配图完成直接跳预览
      router.push({ name: 'project-preview', params: { id: projectId.value } })
    } else ElMessage.error(res.msg || '操作失败')
  } catch (e) {
    ElMessage.error('操作失败：' + (e.response?.data?.msg || e.message || '网络异常'))
  } finally { completing.value = false }
}
</script>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: baseline; width: 100%; gap: 12px; }
.step-title { font-size: 16px; font-weight: 700; }
.card-head .meta { font-size: 12px; color: var(--muted); font-weight: normal; white-space: nowrap; }
.muted { color: var(--muted); font-size: 13px; line-height: 1.7; margin: 4px 0 10px; }
.muted-small { color: var(--muted); font-size: 12px; }
.state-error { padding: 36px 16px; }
.state-title { font-weight: 700; margin: 8px 0 4px; }
.state-msg { color: var(--muted); font-size: 13px; margin-bottom: 12px; }
.sec-title { font-weight: 700; margin-right: 10px; }
.section-head { display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
.empty-state { padding: 8px 0 16px; }

.gen-form { max-width: 560px; }
.size-select { width: 240px; }
.gen-actions { display: flex; gap: 8px; }

.ref-pick { display: flex; align-items: center; gap: 10px; }
.ref-thumb { width: 72px; height: 72px; border-radius: var(--radius-sm, 6px); object-fit: cover; border: 1px solid var(--line); }
.ref-meta { display: flex; flex-direction: column; gap: 4px; }
.ref-name { font-size: 12px; color: var(--muted); max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.img-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(210px, 1fr)); gap: 12px; }
.img-card { border: 1px solid var(--line); border-radius: var(--radius-sm); padding: 10px; background: var(--card); transition: border-color .2s, box-shadow .2s; }
.img-card:hover { box-shadow: var(--shadow-hover); }
.img-card.cover { border-color: var(--ok); box-shadow: 0 0 0 2px color-mix(in srgb, var(--ok) 18%, transparent); }
.img-thumb { width: 100%; aspect-ratio: 4 / 3; border-radius: var(--radius-sm); background: var(--paper); }
.img-tags { display: flex; gap: 4px; flex-wrap: wrap; margin-top: 8px; }
.src-tag { margin-left: auto; }
.img-meta { display: flex; justify-content: space-between; gap: 6px; margin: 6px 0; font-size: 12px; color: var(--muted); }
.img-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.img-actions { display: flex; gap: 6px; flex-wrap: wrap; }
.img-actions .el-button { flex: 1; min-height: 32px; }

.next-row { margin-top: 18px; display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.next-row .el-button:last-child { margin-left: auto; }

.ref-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: 10px; max-height: 60vh; overflow-y: auto; }
.ref-cell { cursor: pointer; border: 1px solid var(--line); border-radius: var(--radius-sm); padding: 6px; text-align: center; }
.ref-cell:hover { border-color: var(--brand); }
.ref-cell-thumb { width: 100%; aspect-ratio: 1; border-radius: var(--radius-sm); }
.ref-cell-name { display: block; font-size: 12px; color: var(--muted); margin-top: 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

@media (max-width: 768px) {
  .img-grid { grid-template-columns: repeat(2, 1fr); }
  .img-actions .el-button { min-height: 44px; }  /* 触控目标 ≥44px */
  .size-select { width: 100%; }
  .next-row .el-button { flex: 1; margin-left: 0; }
  .next-row .el-button:last-child { margin-left: 0; }
}
</style>