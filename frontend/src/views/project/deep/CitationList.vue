<template>
  <!-- R3 知识库引用明细:可折叠列表,来源标签 + 名称 + 相似度/置信度 + 块摘要;
       本地知识库(rag_citations)与深度模式 WEB 搜索来源(fact_sheet)合并展示 -->
  <div v-if="list.length" class="cite-wrap">
    <el-collapse class="cite-collapse">
      <el-collapse-item :title="`共 ${list.length} 条引用（点击展开）`" name="cites">
        <div v-for="(c,i) in list" :key="i" class="cite-item">
          <div class="cite-line">
            <el-tag :type="tagType(c)" size="small" effect="plain" round>{{ sourceLabel(c) }}</el-tag>
            <span class="cite-name">{{ c.modelName || '（未标注）' }}</span>
            <span class="cite-score">{{ scoreText(c) }}</span>
          </div>
          <div class="cite-text">{{ c.chunkText }}</div>
        </div>
      </el-collapse-item>
    </el-collapse>
  </div>
  <!-- 空态/降级:无任何引用时,说明原因 -->
  <div v-else class="cite-empty">{{ emptyText }}</div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  citations: { type: [Array, String], default: null },  // 后端 rag_citations(JSON 字符串或已解析数组,本地知识库 CAR|KB)
  ragStatus: { type: String, default: '' },             // OK/LOW_CONFIDENCE/FAILED/NO_KNOWLEDGE
  factSheet: { type: [Object, String], default: null }  // 深度模式事实手册(JSON),其 WEB/MULTI 条目并入展示
})

/** 本地知识库引用(rag_citations)。 */
const localList = computed(() => {
  if (Array.isArray(props.citations)) return props.citations
  if (typeof props.citations === 'string' && props.citations) {
    try { const v = JSON.parse(props.citations); return Array.isArray(v) ? v : [] } catch { return [] }
  }
  return []
})

/** 深度模式事实手册来源(fact_sheet.entries 全部类型:KB/WEB/MULTI,映射为引用条目)。
 *  KB 条目(置信 0.9/0.6)与本地 rag_citations 同款「通用知识」展示——修复「内容引用了知识库、页面却显示未引用」的深度模式展示断链(2026-09-06)。
 */
const SHEET_ENTRY_MAX = 24
const sheetEntries = computed(() => {
  const raw = typeof props.factSheet === 'string' ? safeParse(props.factSheet) : (props.factSheet || {})
  const entries = Array.isArray(raw?.entries) ? raw.entries : []
  const out = []
  for (const e of entries) {
    if (out.length >= SHEET_ENTRY_MAX) break
    const t = e?.sources?.type || ''
    if (t !== 'KB' && t !== 'WEB' && t !== 'MULTI') continue
    out.push({
      source: t,
      // KB:条目名(车型/知识标题);WEB:域名;MULTI:多源交叉
      modelName: t === 'WEB' ? hostOf(e?.sources?.url) : (t === 'MULTI' ? '多源交叉' : (e?.sources?.modelName || '（未标注）') ),
      chunkType: t,
      score: typeof e?.confidence === 'number' ? e.confidence : 0,
      confidence: true,  // score 为置信度(区别于本地知识库的相似度)
      chunkText: [e?.key || '', e?.value ? ` = ${e.value}` : ''].filter(Boolean).join('')
    })
  }
  return out
})

const list = computed(() => [...localList.value, ...sheetEntries.value])

/** 域名提取(WEB 条目标题展示,可读化)。 */
const hostOf = (url) => {
  if (!url) return '（未标注）'
  try { return url.replace(/^https?:\/\//, '').split('/')[0] } catch { return url }
}

function safeParse(s) { try { return JSON.parse(s) } catch { return {} } }

const tagType = (c) => {
  if (c.source === 'KB') return 'success'
  if (c.source === 'WEB') return 'warning'
  if (c.source === 'MULTI') return 'success'
  return 'primary'
}

const sourceLabel = (c) => {
  if (c.source === 'KB') return '通用知识'
  if (c.source === 'WEB') return 'WEB 搜索'
  if (c.source === 'MULTI') return '多源交叉'
  return '车型数据'
}

const scoreText = (c) => {
  if (typeof c.score !== 'number') return ''
  const n = c.score.toFixed(2)
  return c.confidence ? `置信 ${n}` : `相似 ${n}`
}

const emptyText = computed(() => ({
  LOW_CONFIDENCE: '知识库有数据但与主题相关性过低，已全部抛弃，未注入 AI（数据未经知识库核实，发布前请人工核查参数）',
  FAILED: '本次知识库检索失败，未注入知识（数据未经知识库核实，建议人工核查参数后发布）',
  NO_KNOWLEDGE: '知识库无相关命中，本次未引用知识库内容',
}[props.ragStatus] || '本次未引用知识库内容'))
</script>

<style scoped>
.cite-collapse { border: none; }
.cite-item { padding: 6px 0; border-bottom: 1px dashed var(--el-border-color-lighter, #eee); }
.cite-item:last-child { border-bottom: none; }
.cite-line { display: flex; align-items: center; gap: 8px; }
.cite-name { font-size: 13px; font-weight: 600; }
.cite-score { font-size: 12px; color: var(--muted, #999); white-space: nowrap; }
.cite-text { font-size: 12px; color: var(--muted, #888); margin-top: 3px; line-height: 1.5; }
.cite-empty { font-size: 12px; color: var(--muted, #999); padding: 4px 0; }
</style>