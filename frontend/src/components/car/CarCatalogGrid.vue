<template>
  <div class="catalog-grid-wrap">
    <div class="grid-toolbar">
      <el-input v-model="keyword" placeholder="搜索车型 / 网络" clearable class="search">
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <el-select v-model="statusFilter" class="status-filter" placeholder="全部">
        <el-option label="全部" value="all" />
        <el-option label="未入库" value="not" />
        <el-option label="已入库" value="synced" />
      </el-select>
      <el-checkbox :model-value="allSelected" :indeterminate="indeterminate" @change="toggleAll">全选</el-checkbox>
      <span class="sel-count">已选 {{ selected.length }} 个</span>
    </div>

    <div v-if="!filtered.length" class="grid-empty">没有符合条件的车型</div>
    <div v-else class="car-grid">
      <div v-for="c in filtered" :key="c.id" class="car-card" :class="{ selected: isSelected(c.id) }"
        role="button" tabindex="0" @click="toggle(c.id)"
        @keydown.enter.prevent="toggle(c.id)" @keydown.space.prevent="toggle(c.id)">
        <div class="thumb">
          <el-image v-if="c.img" :src="c.img" fit="cover" lazy />
          <div v-else class="thumb-ph"><el-icon :size="22"><Van /></el-icon></div>
          <el-checkbox class="pick" :model-value="isSelected(c.id)" @click.stop @change="toggle(c.id)" />
        </div>
        <div class="card-body">
          <div class="c-name serif">{{ c.name }}</div>
          <div class="c-meta">{{ c.salesNetworkName || '—' }} · {{ c.price || '价格待同步' }}</div>
          <el-tag v-if="isSynced(c.id)" size="small" type="success" effect="plain" round>已入库</el-tag>
          <el-tag v-else size="small" type="warning" effect="plain" round>未入库</el-tag>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { Search, Van } from '@element-plus/icons-vue'

const props = defineProps({
  catalog: { type: Array, default: () => [] },
  /** 已入库 goodsId 集合（Set 或数组，兼容两种传入）。 */
  syncedIds: { type: [Set, Array], default: () => [] },
  /** 已选 goodsId / catalog id 数组。 */
  modelValue: { type: Array, default: () => [] }
})

const emit = defineEmits(['update:modelValue'])

const keyword = ref('')
const statusFilter = ref('all')

const selected = computed(() => props.modelValue || [])

const isSynced = (id) => {
  const target = String(id)
  if (props.syncedIds instanceof Set) return props.syncedIds.has(target)
  return (props.syncedIds || []).some(x => String(x) === target)
}

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return (props.catalog || []).filter(c => {
    if (kw && !(c.name || '').toLowerCase().includes(kw) && !(c.salesNetworkName || '').toLowerCase().includes(kw)) return false
    const synced = isSynced(c.id)
    if (statusFilter.value === 'not' && synced) return false
    if (statusFilter.value === 'synced' && !synced) return false
    return true
  })
})

const isSelected = (id) => selected.value.includes(id)

const allSelected = computed(() =>
  filtered.value.length > 0 && filtered.value.every(c => isSelected(c.id)))

const indeterminate = computed(() => {
  const n = filtered.value.filter(c => isSelected(c.id)).length
  return n > 0 && n < filtered.value.length
})

const toggle = (id) => {
  emit('update:modelValue', isSelected(id)
    ? selected.value.filter(x => x !== id)
    : [...selected.value, id])
}

// 全选只作用于当前筛选结果；取消全选不误删筛选外已选项
const toggleAll = (val) => {
  const ids = filtered.value.map(c => c.id)
  if (val) {
    const merged = [...selected.value]
    ids.forEach(id => { if (!merged.includes(id)) merged.push(id) })
    emit('update:modelValue', merged)
  } else {
    emit('update:modelValue', selected.value.filter(id => !ids.includes(id)))
  }
}
</script>

<style scoped>
.catalog-grid-wrap { min-height: 0; }
.grid-toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; flex-wrap: wrap; }
.search { width: 240px; }
.status-filter { width: 130px; }
.sel-count { color: var(--muted); font-size: 13px; }
.grid-empty { padding: 40px 0; text-align: center; color: var(--faint); }

.car-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 14px; }
.car-card {
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--card);
  overflow: hidden;
  cursor: pointer;
  transition: box-shadow .2s, border-color .2s;
}
.car-card:hover { box-shadow: var(--shadow-hover); }
.car-card:focus-visible { outline: none; box-shadow: var(--focus-ring); }
.car-card.selected { border-color: var(--brand); box-shadow: 0 0 0 2px var(--brand-weak); }

.thumb { position: relative; aspect-ratio: 4 / 3; background: var(--paper); }
.thumb :deep(.el-image) { width: 100%; height: 100%; }
.thumb-ph { width: 100%; height: 100%; display: flex; align-items: center; justify-content: center; color: var(--faint); }
.pick { position: absolute; top: 6px; left: 8px; padding: 2px 4px; border-radius: var(--radius-sm); background: color-mix(in srgb, var(--card) 82%, transparent); }

.card-body { padding: 10px 12px 12px; }
.c-name { font-weight: 700; font-size: 15px; line-height: 1.4; }
.c-meta { color: var(--muted); font-size: 12px; margin: 4px 0 8px; }
</style>
