<template>
  <div class="bp-review">
    <div class="bp-head">
      <el-icon><Document /></el-icon>写作蓝图
      <el-tag v-if="confirmed" size="small" type="success" effect="plain" round>已确认</el-tag>
      <el-tag v-else size="small" type="warning" effect="plain" round>待评审</el-tag>
      <span class="bp-hint">{{ confirmed ? '蓝图已确认,写作已解锁' : '请核对论证结构与证据绑定后确认' }}</span>
    </div>

    <!-- 质量信号 -->
    <div v-if="quality" class="quality">
      <div class="q-item"><span class="q-k">论证密度</span><span class="q-v">{{ quality.argumentDensity ?? '—' }} 节</span></div>
      <div class="q-item"><span class="q-k">证据覆盖</span><span class="q-v">{{ pct(quality.evidenceCoverage) }}</span></div>
      <div class="q-item"><span class="q-k">证据缺口</span><span class="q-v">{{ quality.gapCount ?? '—' }} 处</span></div>
      <div class="q-item"><span class="q-k">契约一致性</span><span class="q-v">{{ pct(quality.taskBriefConsistency) }}</span></div>
    </div>

    <!-- 中心论点 / 切入角度 / 叙事弧线 -->
    <section v-if="local.thesis" class="bp-sec">
      <div class="bp-label">中心论点</div>
      <div class="bp-text strong">{{ local.thesis }}</div>
    </section>
    <div class="bp-cols">
      <section v-if="local.audienceAngle" class="bp-panel">
        <div class="bp-label">切入角度</div>
        <div class="bp-text">{{ local.audienceAngle }}</div>
      </section>
      <section v-if="local.narrativeArc" class="bp-panel">
        <div class="bp-label">叙事弧线</div>
        <div class="bp-text">{{ local.narrativeArc }}</div>
      </section>
    </div>

    <!-- 论证结构 + 证据映射 -->
    <section class="bp-sec">
      <div class="bp-label">论证结构 · 证据映射
        <span class="label-hint">{{ sections.length }} 节</span>
      </div>
      <div v-for="(sec, i) in sections" :key="sec.sectionId || i" class="sec-card">
        <div class="sec-head">
          <span class="sec-id">{{ sec.sectionId || `S${i + 1}` }}</span>
          <span class="sec-heading">{{ sec.heading || '(无标题)' }}</span>
          <el-tag size="small" effect="plain" round>{{ roleLabel(sec.role) }}</el-tag>
        </div>

        <!-- 论据:可编辑(仅 REVIEWING) -->
        <div class="sec-row">
          <span class="row-k">分论点</span>
          <el-input v-if="!confirmed" v-model="sec.claim" type="textarea" :autosize="{ minRows: 1, maxRows: 4 }" size="small" placeholder="该节分论点" />
          <span v-else class="bp-text">{{ sec.claim || '(未给出)' }}</span>
        </div>
        <div v-if="sec.narrativeIntent" class="sec-row">
          <span class="row-k">叙事意图</span><span class="bp-text">{{ sec.narrativeIntent }}</span>
        </div>
        <div v-if="sec.argumentRelation" class="sec-row">
          <span class="row-k">与中心关系</span><span class="bp-text">{{ sec.argumentRelation }}</span>
        </div>

        <!-- 证据项(sectionId 关联) -->
        <div v-for="(ev, j) in evidenceOf(sec.sectionId)" :key="j" class="ev">
          <div class="ev-head">
            <span class="ev-arg">{{ ev.argument || '证据项' }}</span>
            <el-tag size="small" :type="covType(ev.coverage)" effect="plain" round>{{ covLabel(ev.coverage) }}</el-tag>
          </div>
          <div v-if="ev.evidenceNeeded" class="ev-need">所需证据:{{ ev.evidenceNeeded }}</div>
          <div class="ev-keys">
            <span class="row-k">绑定条目</span>
            <template v-if="!confirmed">
              <div v-for="(k, ki) in ev.entryKeys" :key="ki" class="key-row">
                <el-input v-model="ev.entryKeys[ki]" size="small" placeholder="事实手册条目 key" />
                <el-button size="small" text type="danger" @click="removeKey(ev, ki)">删除</el-button>
              </div>
              <el-button size="small" text type="primary" @click="addKey(ev)">+ 添加条目</el-button>
            </template>
            <template v-else>
              <span v-if="(ev.entryKeys || []).length" class="bp-text">{{ ev.entryKeys.join('；') }}</span>
              <span v-else class="bp-text empty">无绑定</span>
            </template>
          </div>
          <div v-if="ev.note" class="ev-note">{{ ev.note }}</div>
        </div>
      </div>
      <div v-if="orphanEvidence.length" class="sec-card">
        <div class="ev-head"><span class="ev-arg">未关联分节的证据项</span></div>
        <div v-for="(ev, j) in orphanEvidence" :key="j" class="ev">
          <span class="bp-text">{{ ev.sectionId || '(无 sectionId)' }} · {{ ev.argument || '' }}</span>
        </div>
      </div>
      <div v-if="!sections.length" class="bp-text empty">暂无论证结构</div>
    </section>

    <!-- 约束 / 缺口 -->
    <div class="bp-cols">
      <section v-if="(local.constraints || []).length" class="bp-panel">
        <div class="bp-label"><el-icon><Warning /></el-icon>写作约束</div>
        <ul class="bp-list"><li v-for="(c, i) in local.constraints" :key="i">{{ c }}</li></ul>
      </section>
      <section v-if="(local.gaps || []).length" class="bp-panel">
        <div class="bp-label"><el-icon><WarningFilled /></el-icon>证据缺口</div>
        <ul class="bp-list"><li v-for="(g, i) in local.gaps" :key="i">
          <b>{{ g.sectionId || '—' }}</b> {{ g.reason || '' }}
        </li></ul>
      </section>
    </div>

    <div class="bp-actions">
      <template v-if="!confirmed">
        <el-button type="primary" :loading="busy" :disabled="busy" @click="onConfirm">确认蓝图,解锁写作</el-button>
        <el-button :disabled="busy" @click="emit('regenerate')">重新生成蓝图</el-button>
      </template>
      <el-button v-else type="primary" :disabled="busy" @click="emit('next')">进入多版本生成 →</el-button>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { Document, Warning, WarningFilled } from '@element-plus/icons-vue'

const props = defineProps({
  blueprint: { type: [Object, String], default: null },
  status: { type: String, default: 'REVIEWING' },
  busy: { type: Boolean, default: false }
})
const emit = defineEmits(['confirm', 'regenerate', 'next'])

const confirmed = computed(() => props.status === 'CONFIRMED')

// 深拷贝,编辑不落地 prop
const clone = (v) => {
  if (!v) return {}
  try { return JSON.parse(JSON.stringify(typeof v === 'string' ? JSON.parse(v) : v)) } catch { return {} }
}
const local = ref(clone(props.blueprint))
watch(() => props.blueprint, (v) => { local.value = clone(v) }, { deep: true })

const sections = computed(() => Array.isArray(local.value?.argumentStructure) ? local.value.argumentStructure : [])
const evidenceMap = computed(() => Array.isArray(local.value?.evidenceMap) ? local.value.evidenceMap : [])
const quality = computed(() => local.value?.quality || null)

const evidenceOf = (sectionId) => evidenceMap.value.filter(e => e && e.sectionId === sectionId)
const orphanEvidence = computed(() => {
  const ids = new Set(sections.value.map(s => s?.sectionId).filter(Boolean))
  return evidenceMap.value.filter(e => e && !ids.has(e.sectionId))
})

const addKey = (ev) => {
  if (!Array.isArray(ev.entryKeys)) ev.entryKeys = []
  ev.entryKeys.push('')
}
const removeKey = (ev, i) => {
  if (Array.isArray(ev.entryKeys)) ev.entryKeys.splice(i, 1)
}
const onConfirm = () => {
  if (props.busy) return
  emit('confirm', JSON.stringify(local.value))
}

const roleLabel = (r) => ({ HOOK: '开篇', CONTEXT: '背景', ARGUMENT: '论证', EVIDENCE: '证据', COUNTER: '反方', CONCLUSION: '结论' }[r] || r || '—')
const covLabel = (c) => ({ COVERED: '已覆盖', PARTIAL: '部分覆盖', MISSING: '未覆盖' }[c] || c || '—')
const covType = (c) => ({ COVERED: 'success', PARTIAL: 'warning', MISSING: 'danger' }[c] || 'info')
const pct = (v) => typeof v === 'number' ? `${Math.round(v * 100)}%` : '—'
</script>

<style scoped>
/* PC-only:token 化,无媒体查询 */
.bp-review { display: flex; flex-direction: column; gap: var(--sp-4); }
.bp-head { display: flex; align-items: center; gap: var(--sp-2); font-weight: 700; font-size: var(--fs-14); line-height: var(--lh-14); color: var(--ink); }
.bp-head .el-icon { color: var(--brand); }
.bp-hint { font-weight: 400; font-size: var(--fs-12); line-height: var(--lh-12); color: var(--faint); }

.quality { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: var(--sp-2) var(--sp-5); padding: var(--sp-3) var(--sp-4); background: var(--el-fill-color-lighter); border-radius: var(--radius-sm); }
.q-item { display: flex; align-items: baseline; justify-content: space-between; gap: var(--sp-2); }
.q-k { color: var(--faint); font-size: var(--fs-12); line-height: var(--lh-12); }
.q-v { color: var(--ink); font-size: var(--fs-14); line-height: var(--lh-14); font-weight: 700; }

.bp-cols { display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: var(--sp-4); }
.bp-panel { background: var(--el-fill-color-lighter); border-radius: var(--radius-sm); padding: var(--sp-4); min-width: 0; }
.bp-sec { min-width: 0; }
.bp-label { display: flex; align-items: center; gap: var(--sp-2); font-weight: 700; font-size: var(--fs-13); line-height: var(--lh-13); color: var(--ink); margin-bottom: var(--sp-2); letter-spacing: .03em; }
.bp-label .el-icon { color: var(--brand); }
.label-hint { font-weight: 400; font-size: var(--fs-12); color: var(--faint); }
.bp-text { font-size: var(--fs-14); line-height: var(--lh-16); color: var(--ink); }
.bp-text.strong { font-weight: 600; }
.bp-text.empty { color: var(--faint); }
.bp-list { margin: 0; padding-left: var(--sp-5); }
.bp-list li { font-size: var(--fs-13); line-height: var(--lh-16); color: var(--ink); }

.sec-card { border: 1px solid var(--line); border-radius: var(--radius-sm); padding: var(--sp-3) var(--sp-4); margin-bottom: var(--sp-3); }
.sec-head { display: flex; align-items: center; gap: var(--sp-2); margin-bottom: var(--sp-2); flex-wrap: wrap; }
.sec-id { display: inline-flex; align-items: center; justify-content: center; min-width: 24px; height: 22px; padding: 0 var(--sp-1); border-radius: var(--radius-xs); background: var(--brand-weak); color: var(--brand-strong); font-size: var(--fs-12); font-weight: 700; }
.sec-heading { font-weight: 700; font-size: var(--fs-14); }
.sec-row { display: flex; gap: var(--sp-3); align-items: flex-start; margin-bottom: var(--sp-1); }
.row-k { flex-shrink: 0; width: 72px; color: var(--faint); font-size: var(--fs-12); line-height: var(--lh-14); padding-top: 2px; }
.sec-row .el-input, .ev-keys .el-input { flex: 1; }

.ev { margin-top: var(--sp-3); padding-top: var(--sp-2); border-top: 1px dashed var(--line); }
.ev-head { display: flex; align-items: center; gap: var(--sp-2); }
.ev-arg { font-weight: 600; font-size: var(--fs-14); color: var(--ink); }
.ev-need { color: var(--muted); font-size: var(--fs-12); line-height: var(--lh-14); margin-top: var(--sp-1); }
.ev-keys { display: flex; gap: var(--sp-2); align-items: flex-start; margin-top: var(--sp-1); flex-wrap: wrap; }
.key-row { display: flex; gap: var(--sp-2); align-items: center; width: 100%; }
.ev-note { color: var(--faint); font-size: var(--fs-12); line-height: var(--lh-14); margin-top: var(--sp-1); }

.bp-actions { display: flex; align-items: center; gap: var(--sp-3); border-top: 1px solid var(--line); padding-top: var(--sp-4); }
</style>
