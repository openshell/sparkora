<template>
  <div class="ctrl-bar">
    <div class="ctrl-group">
      <span class="field-label">主题</span>
      <el-select :model-value="theme" class="theme-select" @update:model-value="(v) => emit('update:theme', v)">
        <template #label>
          <span class="theme-dot" :style="{ background: themeColor(theme) }" :class="{ 'is-bright': themeIsBright(theme) }"></span>
          <span class="select-label-text">{{ themeLabel(theme) }}</span>
        </template>
        <el-option-group v-if="builtinThemes.length" label="内置主题">
          <el-option v-for="t in builtinThemes" :key="t.id" :label="t.name" :value="t.id">
            <span class="option-row">
              <span class="theme-dot" :style="{ background: t.color }" :class="{ 'is-bright': t.bright }"></span>
              <span class="option-name">{{ t.name }}</span>
              <el-icon v-if="t.id === theme" class="option-check"><Check /></el-icon>
            </span>
          </el-option>
        </el-option-group>
        <el-option-group v-if="communityThemes.length" label="社区主题">
          <el-option v-for="t in communityThemes" :key="t.id" :label="t.name" :value="t.id">
            <span class="option-row">
              <span class="theme-dot" :style="{ background: t.color }" :class="{ 'is-bright': t.bright }"></span>
              <span class="option-name">{{ t.name }}</span>
              <el-icon v-if="t.id === theme" class="option-check"><Check /></el-icon>
            </span>
          </el-option>
        </el-option-group>
      </el-select>
      <span class="field-label">高亮</span>
      <el-select :model-value="highlight" class="hl-select" @update:model-value="(v) => emit('update:highlight', v)">
        <el-option v-for="h in highlightOptions" :key="h" :label="h" :value="h" />
      </el-select>
    </div>
    <span class="ctrl-divider" aria-hidden="true"></span>
    <div class="ctrl-group">
      <el-tooltip content="代码块顶部仿 Mac 红绿灯" placement="top" :show-after="300">
        <span class="switch-item">
          <el-switch :model-value="macStyle" size="small" @update:model-value="(v) => emit('update:macStyle', v)" />
          <span class="switch-label">Mac 代码块</span>
        </span>
      </el-tooltip>
      <el-tooltip content="外链转为文末引用脚注" placement="top" :show-after="300">
        <span class="switch-item">
          <el-switch :model-value="footnote" size="small" @update:model-value="(v) => emit('update:footnote', v)" />
          <span class="switch-label">链接转脚注</span>
        </span>
      </el-tooltip>
    </div>
    <span class="ctrl-divider" aria-hidden="true"></span>
    <div class="ctrl-group">
      <span class="field-label">宽度</span>
      <el-radio-group :model-value="previewWidth" size="small" class="width-toggle" @update:model-value="(v) => emit('update:previewWidth', v)">
        <el-radio-button value="phone">手机</el-radio-button>
        <el-radio-button value="tablet">平板</el-radio-button>
        <el-radio-button value="full">全宽</el-radio-button>
      </el-radio-group>
    </div>
    <span class="ctrl-divider" aria-hidden="true"></span>
    <el-button size="small" @click="emit('open-images')">
      <el-icon style="margin-right: 4px"><Picture /></el-icon>
      配图 {{ insertedCount }}/{{ snapshotCount }}
    </el-button>
    <span class="flex-sp"></span>
    <el-tag v-if="saveState === 'dirty'" type="warning" effect="plain" size="small">未保存</el-tag>
    <el-tag v-else-if="saveState === 'error'" type="danger" effect="plain" size="small">保存失败</el-tag>
    <el-tag v-else-if="savedAt" type="success" effect="plain" size="small">已保存 {{ savedAt }}</el-tag>
    <el-button size="small" type="primary" plain :loading="saving" :disabled="!dirty" @click="emit('save')">保存正文</el-button>
    <el-button size="small" type="primary" :icon="DocumentCopy" :loading="copying" :disabled="renderError" @click="emit('copy')">复制排版</el-button>
    <el-button size="small" type="success" :disabled="!!renderError" @click="emit('publish')">去发布</el-button>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { DocumentCopy, Check, Picture } from '@element-plus/icons-vue'

/**
 * 预览页工具栏（主题/高亮/样式开关/宽度档位/保存状态/动作按钮）。
 * 从 StepPreview.vue 抽出（09-27-split-monoliths，纯结构重构，零行为变化）。
 * 主题变更与开关变更统一 emit 到父，由父处理「重渲染 + 落库」编排。
 */
const props = defineProps({
  theme: { type: String, default: '' },
  highlight: { type: String, default: '' },
  macStyle: { type: Boolean, default: true },
  footnote: { type: Boolean, default: true },
  previewWidth: { type: String, default: 'phone' },
  themeOptions: { type: Array, default: () => [] },
  highlightOptions: { type: Array, default: () => ['solarized-light'] },
  saveState: { type: String, default: 'clean' },
  savedAt: { type: String, default: '' },
  saving: { type: Boolean, default: false },
  copying: { type: Boolean, default: false },
  dirty: { type: Boolean, default: false },
  renderError: { type: String, default: '' },
  insertedCount: { type: Number, default: 0 },
  snapshotCount: { type: Number, default: 0 }
})
const emit = defineEmits([
  'update:theme', 'update:highlight', 'update:macStyle', 'update:footnote', 'update:previewWidth',
  'save', 'copy', 'publish', 'open-images'
])

// ==== 主题目录(后端下发:内置 + 社区,分组/名称/色点单一真值) ====
/** 按 id 查目录项(未知返回 undefined)。 */
const themeMeta = (t) => (props.themeOptions || []).find((x) => x.id === t)
const themeColor = (t) => themeMeta(t)?.color || '#8a8f98'
const themeIsBright = (t) => !!themeMeta(t)?.bright
/** 主题显示名:内置主题为 id 原样,社区主题为中文名。 */
const themeLabel = (t) => themeMeta(t)?.name || t || ''
// 全量主题按 group 分组(内置主题 / 社区主题),供 el-option-group 渲染
const builtinThemes = computed(() => (props.themeOptions || []).filter((t) => t.group !== 'community'))
const communityThemes = computed(() => (props.themeOptions || []).filter((t) => t.group === 'community'))
</script>

<style scoped>
/* 控件微动效(150-200ms,无布局位移) */
.ctrl-bar :deep(.el-button) { transition: background-color .2s ease, border-color .2s ease, color .2s ease, box-shadow .2s ease; }
.ctrl-bar :deep(.el-switch__core) { transition: background-color .2s ease; }
/* ===== 工具栏(三段分组:选择器 | 开关 | 动作;统一 36px 高度) ===== */
.ctrl-bar {
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 14px;
  padding: 10px 12px; border: 1px solid var(--line); border-radius: var(--radius-sm);
  background: var(--el-fill-color-light);
  /* 粘性工具栏:长文滚动时操作不丢失(停在顶栏下方) */
  position: sticky; top: 68px; z-index: 20;
  box-shadow: 0 2px 8px rgba(0, 0, 0, .04);
  backdrop-filter: blur(6px);
}
.ctrl-group { display: inline-flex; align-items: center; gap: 8px; }
.ctrl-divider { width: 1px; height: 18px; background: var(--line); margin: 0 2px; }
/* 宽度档位分段控件(仅预览视觉,不改渲染内容) */
.width-toggle { flex: none; }
.width-toggle :deep(.el-radio-button__inner) { padding: 6px 12px; }

:deep(.theme-select .el-select__wrapper),
:deep(.hl-select .el-select__wrapper) { height: 34px; border-radius: 8px; }
:deep(.theme-select .el-select__selection) { display: inline-flex; align-items: center; gap: 7px; }
/* 字段标签(收起态语义可见,无需点开下拉) */
.field-label { font-size: 13px; color: var(--muted); flex: none; white-space: nowrap; }
/* 固定宽度防塌陷:收起态完整显示 色点+名称 */
.theme-select { width: 188px; flex: none; }
.hl-select { width: 190px; flex: none; }
.theme-dot { width: 10px; height: 10px; border-radius: 50%; flex: none; box-shadow: inset 0 0 0 1px rgba(0,0,0,.08); }
.theme-dot.is-bright { box-shadow: inset 0 0 0 1px rgba(0,0,0,.14); }
.select-label-text { font-size: 13px; color: var(--ink); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.option-row { display: inline-flex; align-items: center; gap: 8px; width: 100%; min-width: 0; }
.option-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.option-check { color: var(--brand); flex: none; }

.switch-item { display: inline-flex; align-items: center; gap: 6px; cursor: pointer; }
.switch-label { font-size: 13px; color: var(--muted); user-select: none; }
.flex-sp { flex: 1; }

@media (max-width: 900px) {
  .theme-select, .hl-select { width: 100%; flex: auto; }
  .ctrl-divider { display: none; }
  .ctrl-bar { position: static; top: auto; box-shadow: none; }
  .width-toggle { display: none; } /* 移动端容器已满宽,档位无意义 */
}
</style>
