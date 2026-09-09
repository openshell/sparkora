<template>
  <div class="page">
    <TopBar />
    <div class="page-body">
      <div class="settings-card">
        <h2 class="serif">系统设置</h2>
        <p class="muted">控制文章生成时的资料检索来源；变更仅影响之后的生成，已生成产物不追溯。</p>

        <el-skeleton v-if="loading" :rows="2" animated />

        <template v-else>
          <div class="setting-row">
            <div class="setting-info">
              <div class="setting-name">内部知识库（车型 + 通用知识）</div>
              <div class="setting-desc">
                启用后，深度研究的子代理会检索本地知识库（车型参数 / 通用汽车知识），命中块作为权威数据注入。
                知识库数据质量仍在治理中，停用期间生成将优先使用外部搜索资料。
              </div>
            </div>
            <el-switch v-model="form.kbEnabled" :disabled="!canEdit" />
          </div>

          <div class="setting-row">
            <div class="setting-info">
              <div class="setting-name">外部搜索（SearxNG / Tavily）</div>
              <div class="setting-desc">
                启用后，研究子代理可联网检索公开资料（SEARXNG 不可用时自动降级 Tavily）；
                单一来源的资料会标记「待核实」。
              </div>
            </div>
            <el-switch v-model="form.webSearchEnabled" :disabled="!canEdit" />
          </div>

          <el-alert v-if="!form.kbEnabled && !form.webSearchEnabled" type="warning" :closable="false" show-icon
                    title="两项均已停用"
                    description="生成将不使用任何外部资料，仅凭模型自身知识写作，事实风险会显著升高（factRisks 将标注数据未核实）。" />

          <div class="actions" v-if="canEdit">
            <el-button type="primary" :loading="saving" @click="onSave">保存设置</el-button>
          </div>
          <p v-if="updatedAt" class="muted updated-at">最近更新：{{ updatedAt }}</p>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup>
// 系统检索设置页(09-09-brief-gen-redesign R1):控制内部知识库/外部搜索启用。
// 读 ADMIN/EDITOR;写仅 ADMIN(后端 @PreAuthorize 校验,前端按角色隐藏写入口)。
import { onMounted, reactive, ref, computed } from 'vue'
import { settingApi } from '../api'
import { useUserStore } from '../store/user'
import { ElMessage } from 'element-plus'
import TopBar from '../layouts/TopBar.vue'

const user = useUserStore()
const canEdit = computed(() => user.isAdmin)

const loading = ref(true)
const saving = ref(false)
const updatedAt = ref('')
const form = reactive({ kbEnabled: false, webSearchEnabled: true })

onMounted(async () => {
  try {
    const res = await settingApi.get()
    if (res.data) {
      form.kbEnabled = !!res.data.kbEnabled
      form.webSearchEnabled = !!res.data.webSearchEnabled
      updatedAt.value = res.data.updatedAt ? String(res.data.updatedAt).replace('T', ' ').slice(0, 19) : ''
    }
  } catch { /* 拦截器统一提示 */ } finally { loading.value = false }
})

const onSave = async () => {
  saving.value = true
  try {
    const res = await settingApi.update({ kbEnabled: form.kbEnabled, webSearchEnabled: form.webSearchEnabled })
    if (res.code === 0 && res.data) {
      updatedAt.value = res.data.updatedAt ? String(res.data.updatedAt).replace('T', ' ').slice(0, 19) : ''
      ElMessage.success('设置已保存')
    } else {
      ElMessage.error(res.msg || '保存失败')
    }
  } catch { /* 拦截器统一提示 */ } finally { saving.value = false }
}
</script>

<style scoped>
.settings-card {
  max-width: 720px;
  margin: 24px auto;
  padding: 24px;
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  box-shadow: var(--shadow-card);
}
h2 { margin: 0 0 4px; }
.muted { color: var(--muted, #909399); font-size: 13px; }
.setting-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 0;
  border-bottom: 1px dashed var(--line);
}
.setting-name { font-weight: 600; margin-bottom: 4px; }
.setting-desc { font-size: 13px; color: var(--muted, #909399); line-height: 1.6; }
.actions { margin-top: 20px; }
.updated-at { margin-top: 8px; }
@media (max-width: 640px) {
  .settings-card { margin: 12px; padding: 16px; }
  .setting-row { flex-direction: column; gap: 8px; }
}
</style>